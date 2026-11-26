package com.vineyard.editoraPRO.app.domain.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ReferenceInspectionSheets(
    val storyboardB64: String,
    val cutSheetB64: String,
    val sceneCuts: List<Double>
)

data class OutputStoryboardResult(
    val storyboardB64: String,
    val avgNonBlackPct: Int,
    val avgFlatPct: Int
)

object InspectionSheetBuilder {

    suspend fun extractReferenceInspectionData(
        videoFile: File,
        durationSec: Float,
        width: Int,
        height: Int,
        beatsSec: List<Double>,
        onProgress: (status: String, progress: Float, feed: String) -> Unit
    ): ReferenceInspectionSheets = withContext(Dispatchers.Default) {
        val dur = durationSec.coerceAtLeast(0.5f)
        val vw = width.coerceAtLeast(2)
        val vh = height.coerceAtLeast(2)

        val sceneCuts = SceneCutDetector.detectSceneCuts(
            videoFile = videoFile,
            durationSec = dur,
            videoWidth = vw,
            videoHeight = vh,
            onProgress = onProgress
        )

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)

            // 1. 5x5 evenly-spaced timestamped storyboard (190px cell width)
            onProgress("Extracting 25-frame timestamped storyboard...", 0.10f, "Building 5x5 reference storyboard...")
            val cols = 5
            val rows = 5
            val cellW = 190
            val cellH = max(60, ((cellW * vh.toFloat()) / vw).roundToInt())
            val board = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(board)
            canvas.drawColor(Color.BLACK)

            val bgPaint = Paint().apply { color = Color.argb(200, 0, 0, 0) }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22D3EE")
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }

            for (i in 0 until 25) {
                coroutineContext.ensureActive()
                val ts = max(0.05f, (i / 24.5f) * dur).coerceIn(0f, max(0f, dur - 0.05f))
                val frame = FrameExtractor.extractVideoFrame(retriever, dur, ts, maxEdge = max(cellW, cellH) * 2, exact = true)
                val col = i % cols
                val row = i / cols
                val x = col * cellW
                val y = row * cellH
                if (frame != null) {
                    canvas.drawBitmap(frame, null, Rect(x, y, x + cellW, y + cellH), null)
                    frame.recycle()
                }
                canvas.drawRect((x + 3).toFloat(), (y + 3).toFloat(), (x + 61).toFloat(), (y + 23).toFloat(), bgPaint)
                canvas.drawText("${"%.1f".format(ts)}s", (x + 7).toFloat(), (y + 17).toFloat(), textPaint)
            }
            val storyboardB64 = FrameExtractor.bitmapToJpegBase64(board, 80)
            board.recycle()

            // 2. 4-column Cut & Beat Anchor Keyframe Sheet (220px cell width)
            onProgress("Building cut & beat anchor keyframe sheet...", 0.11f, "Capturing exact cut & beat anchor frames...")
            data class Anchor(var ts: Double, var kind: String)

            val anchorList = mutableListOf<Anchor>()
            fun addAnchor(ts: Double, kind: String) {
                val clamped = ((ts.coerceIn(0.04, max(0.05, dur - 0.04).toDouble()) * 100.0).roundToInt()) / 100.0
                val existing = anchorList.firstOrNull { abs(it.ts - clamped) < 0.25 }
                if (existing == null) {
                    anchorList.add(Anchor(clamped, kind))
                } else if (kind == "CUT" && existing.kind != "CUT") {
                    existing.ts = clamped
                    existing.kind = "CUT"
                }
            }

            addAnchor(0.08, "START")
            for (ct in sceneCuts) addAnchor(ct, "CUT")
            for (bt in beatsSec) addAnchor(bt, "BEAT")
            addAnchor(max(0.2, dur - 0.3), "END")
            anchorList.sortBy { it.ts }

            val finalAnchors = if (anchorList.size > 24) {
                val cutsAndEnds = anchorList.filter { it.kind != "BEAT" }
                val beatOnly = anchorList.filter { it.kind == "BEAT" }
                val room = max(0, 24 - cutsAndEnds.size)
                val step = if (room > 0) max(1, ceil(beatOnly.size.toDouble() / room).toInt()) else beatOnly.size + 1
                val sampledBeats = beatOnly.filterIndexed { idx, _ -> idx % step == 0 }.take(room)
                (cutsAndEnds + sampledBeats).sortedBy { it.ts }.take(24)
            } else {
                anchorList
            }

            val cutCols = 4
            val cutRows = max(1, ceil(finalAnchors.size / cutCols.toDouble()).toInt())
            val cutCellW = 220
            val cutCellH = max(70, ((cutCellW * vh.toFloat()) / vw).roundToInt())
            val cutSheet = Bitmap.createBitmap(cutCellW * cutCols, cutCellH * cutRows, Bitmap.Config.ARGB_8888)
            val csCanvas = Canvas(cutSheet)
            csCanvas.drawColor(Color.parseColor("#080B12"))

            val cutBgPaint = Paint().apply { color = Color.argb(210, 0, 0, 0) }
            val cutTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }

            for (i in finalAnchors.indices) {
                coroutineContext.ensureActive()
                val anc = finalAnchors[i]
                val frame = FrameExtractor.extractVideoFrame(retriever, dur, anc.ts.toFloat(), maxEdge = max(cutCellW, cutCellH) * 2, exact = true)
                val col = i % cutCols
                val row = i / cutCols
                val x = col * cutCellW
                val y = row * cutCellH
                if (frame != null) {
                    csCanvas.drawBitmap(frame, null, Rect(x, y, x + cutCellW, y + cutCellH), null)
                    frame.recycle()
                }
                csCanvas.drawRect((x + 3).toFloat(), (y + 3).toFloat(), (x + 111).toFloat(), (y + 23).toFloat(), cutBgPaint)
                cutTextPaint.color = if (anc.kind == "CUT") Color.parseColor("#F59E0B") else Color.parseColor("#22D3EE")
                csCanvas.drawText("${anc.kind} ${"%.2f".format(anc.ts)}s", (x + 7).toFloat(), (y + 17).toFloat(), cutTextPaint)
            }
            val cutSheetB64 = FrameExtractor.bitmapToJpegBase64(cutSheet, 82)
            cutSheet.recycle()

            ReferenceInspectionSheets(
                storyboardB64 = storyboardB64,
                cutSheetB64 = cutSheetB64,
                sceneCuts = sceneCuts
            )
        } finally {
            runCatching { retriever.release() }
        }
    }

    suspend fun buildVideoContactSheet(
        file: File,
        durationSec: Float,
        width: Int,
        height: Int
    ): Pair<Bitmap?, String> = withContext(Dispatchers.Default) {
        val cellW = 220
        val cellH = max(100, ((cellW * height.toFloat()) / width.coerceAtLeast(2)).roundToInt())
        val bannerH = 26
        val pad = 6
        val sheet = Bitmap.createBitmap(cellW * 2 + pad * 3, bannerH + cellH * 2 + pad * 3, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.parseColor("#080B12"))

        val headerPaint = Paint().apply { color = Color.parseColor("#121826") }
        canvas.drawRect(0f, 0f, sheet.width.toFloat(), bannerH.toFloat(), headerPaint)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        canvas.drawText("4 TIMESTAMPS OF ONE FULL-SCREEN VIDEO (${width}x${height})", 8f, 17f, titlePaint)

        val borderPaint = Paint().apply {
            color = Color.parseColor("#334155")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val tagBgPaint = Paint().apply { color = Color.argb(210, 0, 0, 0) }
        val tagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val retriever = MediaMetadataRetriever()
        var thumb: Bitmap? = null
        try {
            retriever.setDataSource(file.absolutePath)
            val fractions = listOf(0.1f, 0.35f, 0.65f, 0.9f)
            for (i in fractions.indices) {
                val ts = min(max(0.05f, durationSec * fractions[i]), max(0.05f, durationSec - 0.05f))
                val frame = FrameExtractor.extractVideoFrame(retriever, durationSec, ts, maxEdge = max(cellW, cellH) * 2, exact = true)
                val col = i % 2
                val row = i / 2
                val cx = pad + col * (cellW + pad)
                val cy = bannerH + pad + row * (cellH + pad)
                if (frame != null) {
                    canvas.drawBitmap(frame, null, Rect(cx, cy, cx + cellW, cy + cellH), null)
                    if (i == 1) {
                        thumb = Bitmap.createScaledBitmap(frame, cellW, cellH, true)
                    }
                    frame.recycle()
                }
                canvas.drawRect(cx.toFloat(), cy.toFloat(), (cx + cellW).toFloat(), (cy + cellH).toFloat(), borderPaint)
                canvas.drawRect((cx + 3).toFloat(), (cy + 3).toFloat(), (cx + 99).toFloat(), (cy + 21).toFloat(), tagBgPaint)
                canvas.drawText("#${i + 1} @ ${"%.1f".format(ts)}s", (cx + 7).toFloat(), (cy + 16).toFloat(), tagTextPaint)
            }
        } finally {
            runCatching { retriever.release() }
        }

        val b64 = FrameExtractor.bitmapToJpegBase64(sheet, 78)
        sheet.recycle()
        thumb to b64
    }

    suspend fun buildUserAssetParts(clips: List<ClipAsset>): List<GeminiPart> = withContext(Dispatchers.Default) {
        if (clips.isEmpty()) return@withContext emptyList()

        if (clips.size <= 6) {
            return@withContext clips.flatMapIndexed { idx, c ->
                val kind = if (c.isImage) "FULL-FRAME IMAGE" else "FULL-FRAME VIDEO (4 labeled timestamp samples of ONE single full-screen video — NOT a 4-grid split!)"
                val audioTag = if (c.hasAudio) ", hasAudio=true (${"%.1f".format(c.audioDurationSec)}s)" else ", hasAudio=false"
                listOf(
                    GeminiPart.Text("User Asset clipIndex $idx ($kind: ${c.name}, native size ${c.width}x${c.height}$audioTag):"),
                    GeminiPart.InlineData("image/jpeg", c.contactSheetBase64)
                )
            }
        }

        val outParts = mutableListOf<GeminiPart>()
        val perSheet = 6
        val cols = 3
        val rows = 2
        val cellW = 340
        val cellH = 340
        val headerPaint = Paint().apply { color = Color.argb(220, 0, 0, 0) }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22D3EE")
            textSize = 13f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        var start = 0
        while (start < clips.size) {
            val end = min(clips.size, start + perSheet)
            val slice = clips.subList(start, end)
            val board = Bitmap.createBitmap(cols * cellW, rows * cellH, Bitmap.Config.ARGB_8888)
            val bctx = Canvas(board)
            bctx.drawColor(Color.parseColor("#080B12"))

            for (i in slice.indices) {
                val clipIdx = start + i
                val c = slice[i]
                val col = i % cols
                val row = i / cols
                val x = col * cellW
                val y = row * cellH
                val pad = 10
                val innerW = cellW - pad * 2
                val innerH = cellH - 36

                val rawBytes = Base64.decode(c.contactSheetBase64, Base64.DEFAULT)
                val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size)
                if (decoded != null) {
                    val scale = min(innerW.toFloat() / decoded.width, innerH.toFloat() / decoded.height)
                    val dw = decoded.width * scale
                    val dh = decoded.height * scale
                    val left = x + pad + (innerW - dw) / 2f
                    val top = y + 28 + (innerH - dh) / 2f
                    bctx.drawBitmap(decoded, null, RectF(left, top, left + dw, top + dh), null)
                    decoded.recycle()
                }

                bctx.drawRect((x + 4).toFloat(), (y + 4).toFloat(), (x + cellW - 4).toFloat(), (y + 26).toFloat(), headerPaint)
                bctx.drawText(
                    "#$clipIdx ${if (c.isImage) "IMG" else "VID"} ${c.width}x${c.height}",
                    (x + 10).toFloat(),
                    (y + 19).toFloat(),
                    textPaint
                )
            }

            val b64 = FrameExtractor.bitmapToJpegBase64(board, 84)
            board.recycle()
            outParts.add(GeminiPart.Text("User Assets Sheet (clipIndex $start..${end - 1} of ${clips.size} total):"))
            outParts.add(GeminiPart.InlineData("image/jpeg", b64))
            start += perSheet
        }
        outParts
    }

    suspend fun renderOutputStoryboard(
        width: Int,
        height: Int,
        durationSec: Float,
        renderAtTime: suspend (timeSec: Float) -> Bitmap,
        onFrameProgress: (idx: Int, ts: Float) -> Unit
    ): OutputStoryboardResult = withContext(Dispatchers.Default) {
        val cols = 5
        val rows = 5
        val cellW = 190
        val cellH = max(60, ((cellW * height.toFloat()) / width.coerceAtLeast(2)).roundToInt())
        val board = Bitmap.createBitmap(cellW * cols, cellH * rows, Bitmap.Config.ARGB_8888)
        val bctx = Canvas(board)
        bctx.drawColor(Color.BLACK)

        val tagBg = Paint().apply { color = Color.argb(200, 0, 0, 0) }
        val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#22C55E")
            textSize = 12f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        var coverageSum = 0
        var flatSum = 0

        for (i in 0 until 25) {
            coroutineContext.ensureActive()
            val ts = min(max(0.05f, durationSec - 0.05f), max(0.05f, (i / 24.5f) * durationSec))
            onFrameProgress(i, ts)
            val rendered = renderAtTime(ts)
            val stats = FrameExtractor.measureFrameStats(rendered)
            coverageSum += stats.nonBlackPct
            flatSum += stats.flatPct

            val col = i % cols
            val row = i / cols
            val x = col * cellW
            val y = row * cellH
            bctx.drawBitmap(rendered, null, Rect(x, y, x + cellW, y + cellH), null)
            bctx.drawRect((x + 3).toFloat(), (y + 3).toFloat(), (x + 61).toFloat(), (y + 23).toFloat(), tagBg)
            bctx.drawText("${"%.1f".format(ts)}s", (x + 7).toFloat(), (y + 17).toFloat(), tagText)
        }

        val b64 = FrameExtractor.bitmapToJpegBase64(board, 80)
        board.recycle()
        OutputStoryboardResult(
            storyboardB64 = b64,
            avgNonBlackPct = (coverageSum / 25f).roundToInt(),
            avgFlatPct = (flatSum / 25f).roundToInt()
        )
    }

    suspend fun renderSideBySideComparison(
        refFile: File,
        refWidth: Int,
        refHeight: Int,
        refDurationSec: Float,
        sceneCuts: List<Double>,
        outW: Int,
        outH: Int,
        durationSec: Float,
        renderAtTime: suspend (timeSec: Float) -> Bitmap,
        onPairProgress: (idx: Int, total: Int, ts: Float) -> Unit
    ): String = withContext(Dispatchers.Default) {
        val rawTimes = mutableListOf(min(0.15, durationSec * 0.05))
        for (ct in sceneCuts) {
            if (ct > 0.2 && ct < durationSec - 0.25) rawTimes.add(ct)
        }
        rawTimes.add(durationSec * 0.5)
        rawTimes.add(max(0.25, durationSec - 0.35))

        val deduped = mutableListOf<Double>()
        for (ts in rawTimes.sorted()) {
            if (deduped.all { abs(it - ts) >= 0.28 }) {
                deduped.add(((ts * 100.0).roundToInt()) / 100.0)
            }
        }
        while (deduped.size < 8) {
            var bestGap = 0.0
            var midVal = durationSec * 0.5
            val sorted = listOf(0.0) + deduped + listOf(durationSec.toDouble())
            for (i in 0 until sorted.lastIndex) {
                val gap = sorted[i + 1] - sorted[i]
                if (gap > bestGap) {
                    bestGap = gap
                    midVal = (((sorted[i] + sorted[i + 1]) * 0.5) * 100.0).roundToInt() / 100.0
                }
            }
            if (bestGap < 0.2) break
            deduped.add(midVal)
            deduped.sort()
        }

        val sampleTimes = if (deduped.size > 8) {
            (0 until 8).map { i -> deduped[((i / 7.0) * (deduped.size - 1)).roundToInt()] }
        } else deduped

        val pairCols = 2
        val pairRows = max(1, ceil(sampleTimes.size / pairCols.toDouble()).toInt())
        val halfW = 165
        val halfH = ((halfW * outH.toFloat()) / outW.coerceAtLeast(2)).roundToInt().coerceIn(80, 320)
        val innerGap = 2
        val pairGap = 8
        val pairW = halfW * 2 + innerGap

        val board = Bitmap.createBitmap(
            pairCols * pairW + (pairCols - 1) * pairGap,
            pairRows * halfH + (pairRows - 1) * pairGap,
            Bitmap.Config.ARGB_8888
        )
        val bctx = Canvas(board)
        bctx.drawColor(Color.parseColor("#080B12"))

        val blackPaint = Paint().apply { color = Color.BLACK }
        val tagBg = Paint().apply { color = Color.argb(210, 0, 0, 0) }
        val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(refFile.absolutePath)
            val rw = refWidth.coerceAtLeast(2)
            val rh = refHeight.coerceAtLeast(2)

            for (i in sampleTimes.indices) {
                coroutineContext.ensureActive()
                val ts = sampleTimes[i].toFloat()
                onPairProgress(i, sampleTimes.size, ts)

                val refTs = min(max(0.03f, refDurationSec - 0.04f), ts)
                val refFrame = FrameExtractor.extractVideoFrame(retriever, refDurationSec, refTs, maxEdge = max(halfW, halfH) * 2, exact = true)
                val outFrame = renderAtTime(ts)

                val pCol = i % pairCols
                val pRow = i / pairCols
                val px = pCol * (pairW + pairGap)
                val py = pRow * (halfH + pairGap)

                bctx.drawRect(px.toFloat(), py.toFloat(), (px + halfW).toFloat(), (py + halfH).toFloat(), blackPaint)
                if (refFrame != null) {
                    val rScale = min(halfW.toFloat() / rw, halfH.toFloat() / rh)
                    val rdw = rw * rScale
                    val rdh = rh * rScale
                    val rl = px + (halfW - rdw) / 2f
                    val rt = py + (halfH - rdh) / 2f
                    bctx.drawBitmap(refFrame, null, RectF(rl, rt, rl + rdw, rt + rdh), null)
                    refFrame.recycle()
                }

                val ox = px + halfW + innerGap
                bctx.drawRect(ox.toFloat(), py.toFloat(), (ox + halfW).toFloat(), (py + halfH).toFloat(), blackPaint)
                bctx.drawBitmap(outFrame, null, Rect(ox, py, ox + halfW, py + halfH), null)

                bctx.drawRect((px + 2).toFloat(), (py + 2).toFloat(), (px + 84).toFloat(), (py + 19).toFloat(), tagBg)
                bctx.drawRect((ox + 2).toFloat(), (py + 2).toFloat(), (ox + 84).toFloat(), (py + 19).toFloat(), tagBg)
                tagText.color = Color.parseColor("#22D3EE")
                bctx.drawText("REF ${"%.2f".format(ts)}s", (px + 5).toFloat(), (py + 14).toFloat(), tagText)
                tagText.color = Color.parseColor("#22C55E")
                bctx.drawText("OUT ${"%.2f".format(ts)}s", (ox + 5).toFloat(), (py + 14).toFloat(), tagText)
            }
        } finally {
            runCatching { retriever.release() }
        }

        val b64 = FrameExtractor.bitmapToJpegBase64(board, 82)
        board.recycle()
        b64
    }
}
