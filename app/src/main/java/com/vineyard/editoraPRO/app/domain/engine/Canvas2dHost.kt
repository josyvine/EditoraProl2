package com.vineyard.editoraPRO.app.domain.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure Native Android Graphics Engine backing the JavaScript CanvasRenderingContext2D shim.
 * Provides isolated offscreen Bitmaps keyed by handle, stateful save/restore stacks,
 * gradients, PorterDuff compositing (source-in, destination-in, lighter/ADD, etc.),
 * paths, curves, shadows, text rendering, and raw RGBA pixel read/write.
 */
class Canvas2dHost {

    data class GradientStop(val offset: Float, val color: Int)

    sealed class GradientSpec {
        abstract fun createShader(): Shader
        data class Linear(
            val x0: Float,
            val y0: Float,
            val x1: Float,
            val y1: Float,
            val stops: MutableList<GradientStop> = mutableListOf()
        ) : GradientSpec() {
            override fun createShader(): Shader {
                val sorted = if (stops.isEmpty()) {
                    listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE))
                } else {
                    stops.sortedBy { it.offset }
                }
                val colors = sorted.map { it.color }.toIntArray()
                val positions = sorted.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
                val dx = x1 - x0
                val dy = y1 - y0
                val safeX1 = if (abs(dx) < 0.001f && abs(dy) < 0.001f) x0 + 0.001f else x1
                return LinearGradient(x0, y0, safeX1, y1, colors, positions, Shader.TileMode.CLAMP)
            }
        }

        data class Radial(
            val x0: Float,
            val y0: Float,
            val r0: Float,
            val x1: Float,
            val y1: Float,
            val r1: Float,
            val stops: MutableList<GradientStop> = mutableListOf()
        ) : GradientSpec() {
            override fun createShader(): Shader {
                val sorted = if (stops.isEmpty()) {
                    listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE))
                } else {
                    stops.sortedBy { it.offset }
                }
                val colors = sorted.map { it.color }.toIntArray()
                val positions = sorted.map { it.offset.coerceIn(0f, 1f) }.toFloatArray()
                val radius = max(1f, r1)
                return RadialGradient(x1, y1, radius, colors, positions, Shader.TileMode.CLAMP)
            }
        }
    }

    data class DrawState(
        var fillColor: Int = Color.BLACK,
        var fillGradientId: String? = null,
        var strokeColor: Int = Color.BLACK,
        var strokeGradientId: String? = null,
        var lineWidth: Float = 1f,
        var lineCap: Paint.Cap = Paint.Cap.BUTT,
        var lineJoin: Paint.Join = Paint.Join.MITER,
        var miterLimit: Float = 10f,
        var lineDash: FloatArray = FloatArray(0),
        var lineDashOffset: Float = 0f,
        var globalAlpha: Float = 1f,
        var compositeOp: String = "source-over",
        var fontSizePx: Float = 16f,
        var fontWeightBold: Boolean = false,
        var fontItalic: Boolean = false,
        var fontFamily: Typeface = Typeface.SANS_SERIF,
        var textAlign: Paint.Align = Paint.Align.LEFT,
        var textBaseline: String = "alphabetic",
        var shadowColor: Int = Color.TRANSPARENT,
        var shadowBlur: Float = 0f,
        var shadowOffsetX: Float = 0f,
        var shadowOffsetY: Float = 0f
    ) {
        fun copyState(): DrawState = copy(lineDash = lineDash.copyOf())
    }

    class BufferSurface(
        val handle: String,
        var bitmap: Bitmap,
        var canvas: Canvas
    ) {
        val stateStack = ArrayDeque<DrawState>()
        var currentState = DrawState()
        var currentPath = Path()
        var hasSubpath = false
        var lastX = 0f
        var lastY = 0f

        fun resetState() {
            stateStack.clear()
            currentState = DrawState()
            currentPath = Path()
            hasSubpath = false
            canvas.setMatrix(Matrix())
        }
    }

    private val surfaces = ConcurrentHashMap<String, BufferSurface>()
    private val gradients = ConcurrentHashMap<String, GradientSpec>()
    private var nextGradientSeq = 1

    fun ensureSurface(handle: String, width: Int, height: Int, preserveIfSameSize: Boolean = true): BufferSurface {
        val w = width.coerceIn(2, 4096)
        val h = height.coerceIn(2, 4096)
        val existing = surfaces[handle]
        if (existing != null && !existing.bitmap.isRecycled) {
            if (preserveIfSameSize && existing.bitmap.width == w && existing.bitmap.height == h) {
                return existing
            }
            existing.bitmap.recycle()
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        return BufferSurface(handle, bmp, canvas).also {
            surfaces[handle] = it
        }
    }

    fun putBitmapAsSurface(handle: String, bitmap: Bitmap): BufferSurface {
        val safeBmp = when {
            bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 ->
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.config != Bitmap.Config.ARGB_8888 || !bitmap.isMutable ->
                bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(
                    bitmap.width.coerceAtLeast(2),
                    bitmap.height.coerceAtLeast(2),
                    Bitmap.Config.ARGB_8888
                )
            else -> bitmap
        }
        val existing = surfaces[handle]
        if (existing != null && existing.bitmap !== safeBmp && !existing.bitmap.isRecycled) {
            existing.bitmap.recycle()
        }
        return BufferSurface(handle, safeBmp, Canvas(safeBmp)).also {
            surfaces[handle] = it
        }
    }

    fun getSurface(handle: String): BufferSurface? = surfaces[handle]?.takeIf { !it.bitmap.isRecycled }

    fun getBitmap(handle: String): Bitmap? = getSurface(handle)?.bitmap

    fun removeSurface(handle: String): Boolean {
        val removed = surfaces.remove(handle) ?: return false
        if (!removed.bitmap.isRecycled) {
            removed.bitmap.recycle()
        }
        return true
    }

    fun clearAllUserBuffers() {
        val it = surfaces.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!entry.value.bitmap.isRecycled) entry.value.bitmap.recycle()
            it.remove()
        }
        gradients.clear()
    }

    fun createLinearGradient(x0: Float, y0: Float, x1: Float, y1: Float): String {
        val id = "grad_${nextGradientSeq++}"
        gradients[id] = GradientSpec.Linear(x0, y0, x1, y1)
        return id
    }

    fun createRadialGradient(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float): String {
        val id = "grad_${nextGradientSeq++}"
        gradients[id] = GradientSpec.Radial(x0, y0, r0, x1, y1, r1)
        return id
    }

    fun addColorStop(gradId: String, offset: Float, cssColor: String) {
        val spec = gradients[gradId] ?: return
        val color = parseCssColor(cssColor)
        when (spec) {
            is GradientSpec.Linear -> spec.stops.add(GradientStop(offset.coerceIn(0f, 1f), color))
            is GradientSpec.Radial -> spec.stops.add(GradientStop(offset.coerceIn(0f, 1f), color))
        }
    }

    fun save(handle: String) {
        val s = getSurface(handle) ?: return
        s.canvas.save()
        s.stateStack.addLast(s.currentState.copyState())
    }

    fun restore(handle: String) {
        val s = getSurface(handle) ?: return
        if (s.canvas.saveCount > 1) {
            s.canvas.restore()
        }
        if (s.stateStack.isNotEmpty()) {
            s.currentState = s.stateStack.removeLast()
        }
    }

    fun translate(handle: String, dx: Float, dy: Float) {
        getSurface(handle)?.canvas?.translate(dx, dy)
    }

    fun rotate(handle: String, rad: Float) {
        getSurface(handle)?.canvas?.rotate((rad * 180.0 / PI).toFloat())
    }

    fun scale(handle: String, sx: Float, sy: Float) {
        getSurface(handle)?.canvas?.scale(sx, sy)
    }

    fun transform(handle: String, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        val s = getSurface(handle) ?: return
        val m = Matrix().apply {
            setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
        }
        s.canvas.concat(m)
    }

    fun setTransform(handle: String, a: Float, b: Float, c: Float, d: Float, e: Float, f: Float) {
        val s = getSurface(handle) ?: return
        val m = Matrix().apply {
            setValues(floatArrayOf(a, c, e, b, d, f, 0f, 0f, 1f))
        }
        s.canvas.setMatrix(m)
    }

    fun clearRect(handle: String, x: Float, y: Float, w: Float, h: Float) {
        val s = getSurface(handle) ?: return
        val p = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        s.canvas.drawRect(x, y, x + w, y + h, p)
    }

    fun fillRect(handle: String, x: Float, y: Float, w: Float, h: Float) {
        val s = getSurface(handle) ?: return
        val paint = buildFillPaint(s.currentState)
        s.canvas.drawRect(x, y, x + w, y + h, paint)
    }

    fun strokeRect(handle: String, x: Float, y: Float, w: Float, h: Float) {
        val s = getSurface(handle) ?: return
        val paint = buildStrokePaint(s.currentState)
        s.canvas.drawRect(x, y, x + w, y + h, paint)
    }

    fun beginPath(handle: String) {
        val s = getSurface(handle) ?: return
        s.currentPath = Path()
        s.hasSubpath = false
    }

    fun closePath(handle: String) {
        val s = getSurface(handle) ?: return
        if (s.hasSubpath) s.currentPath.close()
    }

    fun moveTo(handle: String, x: Float, y: Float) {
        val s = getSurface(handle) ?: return
        s.currentPath.moveTo(x, y)
        s.hasSubpath = true
        s.lastX = x
        s.lastY = y
    }

    fun lineTo(handle: String, x: Float, y: Float) {
        val s = getSurface(handle) ?: return
        if (!s.hasSubpath) {
            s.currentPath.moveTo(x, y)
            s.hasSubpath = true
        } else {
            s.currentPath.lineTo(x, y)
        }
        s.lastX = x
        s.lastY = y
    }

    fun quadraticCurveTo(handle: String, cpx: Float, cpy: Float, x: Float, y: Float) {
        val s = getSurface(handle) ?: return
        if (!s.hasSubpath) {
            s.currentPath.moveTo(cpx, cpy)
            s.hasSubpath = true
        }
        s.currentPath.quadTo(cpx, cpy, x, y)
        s.lastX = x
        s.lastY = y
    }

    fun bezierCurveTo(handle: String, cp1x: Float, cp1y: Float, cp2x: Float, cp2y: Float, x: Float, y: Float) {
        val s = getSurface(handle) ?: return
        if (!s.hasSubpath) {
            s.currentPath.moveTo(cp1x, cp1y)
            s.hasSubpath = true
        }
        s.currentPath.cubicTo(cp1x, cp1y, cp2x, cp2y, x, y)
        s.lastX = x
        s.lastY = y
    }

    fun rect(handle: String, x: Float, y: Float, w: Float, h: Float) {
        val s = getSurface(handle) ?: return
        s.currentPath.addRect(x, y, x + w, y + h, Path.Direction.CW)
        s.hasSubpath = true
        s.lastX = x
        s.lastY = y
    }

    fun roundRect(handle: String, x: Float, y: Float, w: Float, h: Float, r: Float) {
        val s = getSurface(handle) ?: return
        val rr = r.coerceAtLeast(0f)
        s.currentPath.addRoundRect(RectF(x, y, x + w, y + h), rr, rr, Path.Direction.CW)
        s.hasSubpath = true
        s.lastX = x
        s.lastY = y
    }

    fun arc(handle: String, x: Float, y: Float, radius: Float, startAngle: Float, endAngle: Float, ccw: Boolean) {
        val s = getSurface(handle) ?: return
        val r = abs(radius)
        if (r <= 0f) return
        val startDeg = Math.toDegrees(startAngle.toDouble()).toFloat()
        var sweepDeg = Math.toDegrees((endAngle - startAngle).toDouble()).toFloat()
        if (!ccw) {
            if (sweepDeg >= 360f) sweepDeg = 360f
            else if (sweepDeg < 0f) sweepDeg = ((sweepDeg % 360f) + 360f)
        } else {
            if (sweepDeg <= -360f) sweepDeg = -360f
            else if (sweepDeg > 0f) sweepDeg = (sweepDeg % 360f) - 360f
        }
        val sx = x + r * cos(startAngle)
        val sy = y + r * sin(startAngle)
        if (s.hasSubpath) s.currentPath.lineTo(sx, sy)
        else {
            s.currentPath.moveTo(sx, sy)
            s.hasSubpath = true
        }
        if (abs(sweepDeg) >= 359.9f) {
            s.currentPath.addCircle(x, y, r, if (ccw) Path.Direction.CCW else Path.Direction.CW)
        } else {
            s.currentPath.arcTo(RectF(x - r, y - r, x + r, y + r), startDeg, sweepDeg, false)
        }
        s.lastX = x + r * cos(endAngle)
        s.lastY = y + r * sin(endAngle)
    }

    fun ellipse(
        handle: String,
        x: Float,
        y: Float,
        rx: Float,
        ry: Float,
        rotation: Float,
        startAngle: Float,
        endAngle: Float,
        ccw: Boolean
    ) {
        val s = getSurface(handle) ?: return
        val arx = abs(rx)
        val ary = abs(ry)
        if (arx <= 0f || ary <= 0f) return
        val localPath = Path()
        val startDeg = Math.toDegrees(startAngle.toDouble()).toFloat()
        var sweepDeg = Math.toDegrees((endAngle - startAngle).toDouble()).toFloat()
        if (!ccw && sweepDeg < 0f) sweepDeg = (sweepDeg % 360f) + 360f
        if (ccw && sweepDeg > 0f) sweepDeg = (sweepDeg % 360f) - 360f
        if (abs(sweepDeg) >= 359.9f) {
            localPath.addOval(RectF(-arx, -ary, arx, ary), if (ccw) Path.Direction.CCW else Path.Direction.CW)
        } else {
            localPath.addArc(RectF(-arx, -ary, arx, ary), startDeg, sweepDeg)
        }
        val m = Matrix().apply {
            postRotate(Math.toDegrees(rotation.toDouble()).toFloat())
            postTranslate(x, y)
        }
        localPath.transform(m)
        s.currentPath.addPath(localPath)
        s.hasSubpath = true
    }

    fun fill(handle: String, fillRule: String = "nonzero") {
        val s = getSurface(handle) ?: return
        s.currentPath.fillType = if (fillRule == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
        s.canvas.drawPath(s.currentPath, buildFillPaint(s.currentState))
    }

    fun stroke(handle: String) {
        val s = getSurface(handle) ?: return
        s.canvas.drawPath(s.currentPath, buildStrokePaint(s.currentState))
    }

    fun clip(handle: String) {
        val s = getSurface(handle) ?: return
        s.canvas.clipPath(s.currentPath)
    }

    fun fillText(handle: String, text: String, x: Float, y: Float, maxWidth: Float?) {
        val s = getSurface(handle) ?: return
        val paint = buildTextPaint(s.currentState, stroke = false)
        drawAlignedText(s.canvas, text, x, y, maxWidth, s.currentState.textBaseline, paint)
    }

    fun strokeText(handle: String, text: String, x: Float, y: Float, maxWidth: Float?) {
        val s = getSurface(handle) ?: return
        val paint = buildTextPaint(s.currentState, stroke = true)
        drawAlignedText(s.canvas, text, x, y, maxWidth, s.currentState.textBaseline, paint)
    }

    fun measureTextWidth(handle: String, text: String): Float {
        val s = getSurface(handle) ?: return text.length * 8f
        val paint = buildTextPaint(s.currentState, stroke = false)
        return paint.measureText(text)
    }

    fun drawImage(
        dstHandle: String,
        srcHandle: String,
        sx: Float,
        sy: Float,
        sw: Float,
        sh: Float,
        dx: Float,
        dy: Float,
        dw: Float,
        dh: Float
    ) {
        val dst = getSurface(dstHandle) ?: return
        val rawSrc = getBitmap(srcHandle) ?: return
        if (dst.bitmap.isRecycled || rawSrc.isRecycled || rawSrc.width <= 0 || rawSrc.height <= 0 || sw <= 0f || sh <= 0f || dw == 0f || dh == 0f) {
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (dst.currentState.globalAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
            porterDuffMode(dst.currentState.compositeOp)?.let { xfermode = PorterDuffXfermode(it) }
            if (dst.currentState.shadowBlur > 0f && Color.alpha(dst.currentState.shadowColor) > 0) {
                setShadowLayer(
                    dst.currentState.shadowBlur,
                    dst.currentState.shadowOffsetX,
                    dst.currentState.shadowOffsetY,
                    dst.currentState.shadowColor
                )
            }
        }
        val srcRect = Rect(
            sx.roundToInt().coerceIn(0, rawSrc.width - 1),
            sy.roundToInt().coerceIn(0, rawSrc.height - 1),
            (sx + sw).roundToInt().coerceIn(1, rawSrc.width),
            (sy + sh).roundToInt().coerceIn(1, rawSrc.height)
        )
        val dstRect = RectF(dx, dy, dx + dw, dy + dh)
        var tempSoft: Bitmap? = null
        val drawBmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && rawSrc.config == Bitmap.Config.HARDWARE) {
            rawSrc.copy(Bitmap.Config.ARGB_8888, false)?.also { tempSoft = it } ?: return
        } else {
            rawSrc
        }
        try {
            if (!dst.bitmap.isRecycled && !drawBmp.isRecycled) {
                dst.canvas.drawBitmap(drawBmp, srcRect, dstRect, paint)
            }
        } finally {
            tempSoft?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    fun getImageData(handle: String, x: Int, y: Int, w: Int, h: Int): ByteArray {
        val bmp = getBitmap(handle)?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?: return ByteArray(max(1, w * h * 4))
        val safeW = w.coerceIn(1, bmp.width)
        val safeH = h.coerceIn(1, bmp.height)
        val sx = x.coerceIn(0, max(0, bmp.width - safeW))
        val sy = y.coerceIn(0, max(0, bmp.height - safeH))
        val pixels = IntArray(safeW * safeH)
        bmp.getPixels(pixels, 0, safeW, sx, sy, safeW, safeH)
        val rgba = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val px = pixels[i]
            val o = i * 4
            rgba[o] = ((px shr 16) and 0xFF).toByte()
            rgba[o + 1] = ((px shr 8) and 0xFF).toByte()
            rgba[o + 2] = (px and 0xFF).toByte()
            rgba[o + 3] = ((px ushr 24) and 0xFF).toByte()
        }
        return rgba
    }

    fun putImageData(handle: String, rgbaBytes: ByteArray, dx: Int, dy: Int, w: Int, h: Int) {
        val s = getSurface(handle) ?: return
        val safeW = w.coerceIn(1, s.bitmap.width)
        val safeH = h.coerceIn(1, s.bitmap.height)
        val count = min(safeW * safeH, rgbaBytes.size / 4)
        val pixels = IntArray(count)
        for (i in 0 until count) {
            val o = i * 4
            val r = rgbaBytes[o].toInt() and 0xFF
            val g = rgbaBytes[o + 1].toInt() and 0xFF
            val b = rgbaBytes[o + 2].toInt() and 0xFF
            val a = rgbaBytes[o + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val dstX = dx.coerceIn(0, max(0, s.bitmap.width - safeW))
        val dstY = dy.coerceIn(0, max(0, s.bitmap.height - safeH))
        s.bitmap.setPixels(pixels, 0, safeW, dstX, dstY, safeW, min(safeH, count / safeW.coerceAtLeast(1)))
    }

    fun updateStyle(handle: String, isFill: Boolean, rawValue: String) {
        val s = getSurface(handle) ?: return
        if (rawValue.startsWith("grad_")) {
            if (isFill) s.currentState.fillGradientId = rawValue
            else s.currentState.strokeGradientId = rawValue
        } else {
            val color = parseCssColor(rawValue)
            if (isFill) {
                s.currentState.fillGradientId = null
                s.currentState.fillColor = color
            } else {
                s.currentState.strokeGradientId = null
                s.currentState.strokeColor = color
            }
        }
    }

    fun updateFont(handle: String, cssFont: String) {
        val s = getSurface(handle) ?: return
        val lower = cssFont.lowercase()
        val sizeMatch = Regex("(\\d+(?:\\.\\d+)?)\\s*(px|pt|dp|em|rem)").find(lower)
        if (sizeMatch != null) {
            s.currentState.fontSizePx = sizeMatch.groupValues[1].toFloatOrNull()?.coerceIn(4f, 400f) ?: 16f
        }
        s.currentState.fontWeightBold = Regex("\\b(bold|700|800|900|black|heavy)\\b").containsMatchIn(lower)
        s.currentState.fontItalic = Regex("\\b(italic|oblique)\\b").containsMatchIn(lower)
        s.currentState.fontFamily = when {
            lower.contains("mono") || lower.contains("courier") -> Typeface.MONOSPACE
            lower.contains("serif") && !lower.contains("sans") -> Typeface.SERIF
            else -> Typeface.SANS_SERIF
        }
    }

    private fun buildFillPaint(st: DrawState): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        val grad = st.fillGradientId?.let { gradients[it] }
        if (grad != null) {
            shader = grad.createShader()
            alpha = (st.globalAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        } else {
            color = applyAlpha(st.fillColor, st.globalAlpha)
        }
        porterDuffMode(st.compositeOp)?.let { xfermode = PorterDuffXfermode(it) }
        if (st.shadowBlur > 0f && Color.alpha(st.shadowColor) > 0) {
            setShadowLayer(st.shadowBlur, st.shadowOffsetX, st.shadowOffsetY, st.shadowColor)
        }
    }

    private fun buildStrokePaint(st: DrawState): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = st.lineWidth.coerceAtLeast(0.25f)
        strokeCap = st.lineCap
        strokeJoin = st.lineJoin
        strokeMiter = st.miterLimit
        if (st.lineDash.size >= 2) {
            pathEffect = DashPathEffect(st.lineDash, st.lineDashOffset)
        }
        val grad = st.strokeGradientId?.let { gradients[it] }
        if (grad != null) {
            shader = grad.createShader()
            alpha = (st.globalAlpha.coerceIn(0f, 1f) * 255f).roundToInt()
        } else {
            color = applyAlpha(st.strokeColor, st.globalAlpha)
        }
        porterDuffMode(st.compositeOp)?.let { xfermode = PorterDuffXfermode(it) }
        if (st.shadowBlur > 0f && Color.alpha(st.shadowColor) > 0) {
            setShadowLayer(st.shadowBlur, st.shadowOffsetX, st.shadowOffsetY, st.shadowColor)
        }
    }

    private fun buildTextPaint(st: DrawState, stroke: Boolean): Paint {
        val base = if (stroke) buildStrokePaint(st) else buildFillPaint(st)
        base.textSize = st.fontSizePx
        base.textAlign = st.textAlign
        val tfStyle = when {
            st.fontWeightBold && st.fontItalic -> Typeface.BOLD_ITALIC
            st.fontWeightBold -> Typeface.BOLD
            st.fontItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        base.typeface = Typeface.create(st.fontFamily, tfStyle)
        return base
    }

    private fun drawAlignedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        maxWidth: Float?,
        baseline: String,
        paint: Paint
    ) {
        val fm = paint.fontMetrics
        val dy = when (baseline.lowercase()) {
            "top" -> -fm.ascent
            "hanging" -> -fm.ascent * 0.85f
            "middle" -> -(fm.ascent + fm.descent) * 0.5f
            "bottom" -> -fm.descent
            else -> 0f
        }
        val measured = paint.measureText(text)
        if (maxWidth != null && maxWidth > 1f && measured > maxWidth) {
            canvas.save()
            val scaleX = maxWidth / measured
            canvas.translate(x, y + dy)
            canvas.scale(scaleX, 1f)
            canvas.drawText(text, 0f, 0f, paint)
            canvas.restore()
        } else {
            canvas.drawText(text, x, y + dy, paint)
        }
    }

    private fun applyAlpha(color: Int, globalAlpha: Float): Int {
        val a = ((Color.alpha(color) * globalAlpha.coerceIn(0f, 1f))).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun porterDuffMode(op: String): PorterDuff.Mode? = when (op.lowercase()) {
        "source-over" -> null
        "source-in" -> PorterDuff.Mode.SRC_IN
        "source-out" -> PorterDuff.Mode.SRC_OUT
        "source-atop" -> PorterDuff.Mode.SRC_ATOP
        "destination-over" -> PorterDuff.Mode.DST_OVER
        "destination-in" -> PorterDuff.Mode.DST_IN
        "destination-out" -> PorterDuff.Mode.DST_OUT
        "destination-atop" -> PorterDuff.Mode.DST_ATOP
        "lighter", "plus-lighter" -> PorterDuff.Mode.ADD
        "copy" -> PorterDuff.Mode.SRC
        "xor" -> PorterDuff.Mode.XOR
        "multiply" -> PorterDuff.Mode.MULTIPLY
        "screen" -> PorterDuff.Mode.SCREEN
        "overlay" -> PorterDuff.Mode.OVERLAY
        "darken" -> PorterDuff.Mode.DARKEN
        "lighten" -> PorterDuff.Mode.LIGHTEN
        else -> null
    }

    companion object {
        fun parseCssColor(rawInput: String?): Int {
            val raw = rawInput?.trim()?.lowercase().orEmpty()
            if (raw.isEmpty() || raw == "transparent" || raw == "none") return Color.TRANSPARENT

            if (raw.startsWith("#")) {
                val hex = raw.substring(1)
                return when (hex.length) {
                    3 -> {
                        val r = "${hex[0]}${hex[0]}".toIntOrNull(16) ?: 0
                        val g = "${hex[1]}${hex[1]}".toIntOrNull(16) ?: 0
                        val b = "${hex[2]}${hex[2]}".toIntOrNull(16) ?: 0
                        Color.rgb(r, g, b)
                    }
                    4 -> {
                        val r = "${hex[0]}${hex[0]}".toIntOrNull(16) ?: 0
                        val g = "${hex[1]}${hex[1]}".toIntOrNull(16) ?: 0
                        val b = "${hex[2]}${hex[2]}".toIntOrNull(16) ?: 0
                        val a = "${hex[3]}${hex[3]}".toIntOrNull(16) ?: 255
                        Color.argb(a, r, g, b)
                    }
                    6 -> runCatching { Color.parseColor("#$hex") }.getOrDefault(Color.BLACK)
                    8 -> {
                        val r = hex.substring(0, 2).toIntOrNull(16) ?: 0
                        val g = hex.substring(2, 4).toIntOrNull(16) ?: 0
                        val b = hex.substring(4, 6).toIntOrNull(16) ?: 0
                        val a = hex.substring(6, 8).toIntOrNull(16) ?: 255
                        Color.argb(a, r, g, b)
                    }
                    else -> Color.BLACK
                }
            }

            if (raw.startsWith("rgb")) {
                val inner = raw.substringAfter("(", "").substringBeforeLast(")")
                val parts = inner.split(Regex("[,/\\s]+")).filter { it.isNotBlank() }
                if (parts.size >= 3) {
                    val r = parseChannel(parts[0])
                    val g = parseChannel(parts[1])
                    val b = parseChannel(parts[2])
                    val a = if (parts.size >= 4) parseAlpha(parts[3]) else 255
                    return Color.argb(a, r, g, b)
                }
            }

            if (raw.startsWith("hsl")) {
                val inner = raw.substringAfter("(", "").substringBeforeLast(")")
                val parts = inner.split(Regex("[,/\\s]+")).filter { it.isNotBlank() }
                if (parts.size >= 3) {
                    val h = ((parts[0].removeSuffix("deg").toFloatOrNull() ?: 0f) % 360f + 360f) % 360f
                    val s = (parts[1].removeSuffix("%").toFloatOrNull() ?: 100f) / 100f
                    val l = (parts[2].removeSuffix("%").toFloatOrNull() ?: 50f) / 100f
                    val a = if (parts.size >= 4) parseAlpha(parts[3]) else 255
                    return hslToColor(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f), a)
                }
            }

            return when (raw) {
                "white" -> Color.WHITE
                "black" -> Color.BLACK
                "red" -> Color.RED
                "green" -> Color.rgb(0, 128, 0)
                "lime" -> Color.GREEN
                "blue" -> Color.BLUE
                "cyan", "aqua" -> Color.CYAN
                "yellow" -> Color.YELLOW
                "magenta", "fuchsia" -> Color.MAGENTA
                "gray", "grey" -> Color.GRAY
                "darkgray", "darkgrey" -> Color.DKGRAY
                "lightgray", "lightgrey" -> Color.LTGRAY
                "orange" -> Color.rgb(255, 165, 0)
                "pink" -> Color.rgb(255, 192, 203)
                "purple" -> Color.rgb(128, 0, 128)
                "gold" -> Color.rgb(255, 215, 0)
                else -> runCatching { Color.parseColor(raw) }.getOrDefault(Color.WHITE)
            }
        }

        private fun parseChannel(token: String): Int {
            return if (token.endsWith("%")) {
                ((token.removeSuffix("%").toFloatOrNull() ?: 0f) * 2.55f).roundToInt().coerceIn(0, 255)
            } else {
                (token.toFloatOrNull() ?: 0f).roundToInt().coerceIn(0, 255)
            }
        }

        private fun parseAlpha(token: String): Int {
            return if (token.endsWith("%")) {
                ((token.removeSuffix("%").toFloatOrNull() ?: 100f) * 2.55f).roundToInt().coerceIn(0, 255)
            } else {
                val f = token.toFloatOrNull() ?: 1f
                if (f <= 1f) (f * 255f).roundToInt().coerceIn(0, 255) else f.roundToInt().coerceIn(0, 255)
            }
        }

        private fun hslToColor(h: Float, s: Float, l: Float, alpha: Int): Int {
            val c = (1f - abs(2f * l - 1f)) * s
            val x = c * (1f - abs((h / 60f) % 2f - 1f))
            val m = l - c / 2f
            val (r1, g1, b1) = when {
                h < 60f -> Triple(c, x, 0f)
                h < 120f -> Triple(x, c, 0f)
                h < 180f -> Triple(0f, c, x)
                h < 240f -> Triple(0f, x, c)
                h < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            return Color.argb(
                alpha,
                ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
                ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
                ((b1 + m) * 255f).roundToInt().coerceIn(0, 255)
            )
        }
    }
}