package com.vineyard.editoraPRO.app.domain.engine

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.DetectedBox
import com.vineyard.editoraPRO.app.data.model.EditPlanSegment
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.domain.media.FrameExtractor
import com.vineyard.editoraPRO.app.domain.media.FramePixelStats
import com.vineyard.editoraPRO.app.domain.vision.MaskResult
import com.vineyard.editoraPRO.app.domain.vision.SamPointPrompt
import com.vineyard.editoraPRO.app.domain.vision.SamPromptOptions
import com.vineyard.editoraPRO.app.domain.vision.SmartSegmenter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

data class TechnicalValidationResult(
    val code: String,
    val probeCount: Int
)

/**
 * Native JavaScript Execution Bridge powered by QuickJS (io.github.dokar3:quickjs-kt-android).
 * Hosts Canvas2D buffers, OpenGL ES 3.0 shaders, MediaPipe + EdgeSAM segmentation, and clip media frames.
 */
class QuickJsRunner(
    private val smartSegmenter: SmartSegmenter,
    private val glShaderRunner: GlShaderRunner,
    private val onLog: (String) -> Unit
) : NativeRuntimeApi {

    private data class ResolvedBitmap(
        val bitmap: Bitmap,
        val isTemporary: Boolean
    )

    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "Editora-QuickJS").apply { isDaemon = true }
    }
    private val jsDispatcher = jsExecutor.asCoroutineDispatcher()

    private val c2d = Canvas2dHost()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var qjs: QuickJs? = null
    private var clipsList: List<ClipAsset> = emptyList()
    private val imageCache = ConcurrentHashMap<Int, Bitmap>()
    private val retrieverCache = ConcurrentHashMap<Int, MediaMetadataRetriever>()
    private val clipSeekTimes = ConcurrentHashMap<Int, Float>()
    private val frameRingKeys = ArrayDeque<String>()
    private val frameRingLock = Any()
    private val pendingAsyncEvals = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val asyncEvalSeq = AtomicInteger(1)
    private val frameCutoutSeq = AtomicInteger(0)
    private val initCutoutSeq = AtomicInteger(0)
    @Volatile
    private var isRenderingFrame: Boolean = false

    @Volatile
    var aiGeneratedAudioBuffer: PcmAudioBuffer? = null
        private set

    private suspend fun ensureQuickJsReady(): QuickJs = withContext(jsDispatcher) {
        qjs?.let { return@withContext it }
        val instance = QuickJs.create(jsDispatcher)
        bindHostApis(instance)
        instance.evaluate<Any?>(JsRuntimeBootstrap.SCRIPT)
        qjs = instance
        instance
    }

    private fun bindHostApis(instance: QuickJs) {
        instance.define("__host") {
            function("onAsyncDone") { args ->
                val id = (args.getOrNull(0) as? Number)?.toInt() ?: return@function null
                val ok = (args.getOrNull(1) as? Boolean) ?: false
                val err = args.getOrNull(2)?.toString().orEmpty()
                val deferred = pendingAsyncEvals.remove(id)
                if (deferred != null) {
                    if (ok) deferred.complete(Unit)
                    else deferred.completeExceptionally(
                        IllegalStateException(err.ifBlank { "JS async execution failed." })
                    )
                }
                null
            }
            function("ensureCanvas") { args ->
                val h = args.getOrNull(0)?.toString() ?: "__tmp"
                val w = (args.getOrNull(1) as? Number)?.toInt() ?: 2
                val ht = (args.getOrNull(2) as? Number)?.toInt() ?: 2
                c2d.ensureSurface(h, w, ht, preserveIfSameSize = true)
                null
            }
            function("getCanvas") { args ->
                val key = args.getOrNull(0)?.toString() ?: "offscreen"
                val w = (args.getOrNull(1) as? Number)?.toInt() ?: 2
                val h = (args.getOrNull(2) as? Number)?.toInt() ?: 2
                getCanvas(key, w, h)
            }
            function("getWidth") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                c2d.getBitmap(h)?.width ?: 0
            }
            function("getHeight") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                c2d.getBitmap(h)?.height ?: 0
            }
            function("setFillStyle") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                val v = args.getOrNull(1)?.toString() ?: "#000000"
                c2d.updateStyle(h, isFill = true, rawValue = v)
                null
            }
            function("setStrokeStyle") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                val v = args.getOrNull(1)?.toString() ?: "#000000"
                c2d.updateStyle(h, isFill = false, rawValue = v)
                null
            }
            function("setNumProp") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                val prop = args.getOrNull(1)?.toString().orEmpty()
                val num = (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
                val surf = c2d.getSurface(h)
                if (surf != null) {
                    when (prop) {
                        "lineWidth" -> surf.currentState.lineWidth = num
                        "miterLimit" -> surf.currentState.miterLimit = num
                        "globalAlpha" -> surf.currentState.globalAlpha = num.coerceIn(0f, 1f)
                        "shadowBlur" -> surf.currentState.shadowBlur = num.coerceAtLeast(0f)
                        "shadowOffsetX" -> surf.currentState.shadowOffsetX = num
                        "shadowOffsetY" -> surf.currentState.shadowOffsetY = num
                        "lineDashOffset" -> surf.currentState.lineDashOffset = num
                    }
                }
                null
            }
            function("setStrProp") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                val prop = args.getOrNull(1)?.toString().orEmpty()
                val v = args.getOrNull(2)?.toString().orEmpty()
                val surf = c2d.getSurface(h)
                if (surf != null) {
                    when (prop) {
                        "lineCap" -> surf.currentState.lineCap = when (v.lowercase()) {
                            "round" -> android.graphics.Paint.Cap.ROUND
                            "square" -> android.graphics.Paint.Cap.SQUARE
                            else -> android.graphics.Paint.Cap.BUTT
                        }
                        "lineJoin" -> surf.currentState.lineJoin = when (v.lowercase()) {
                            "round" -> android.graphics.Paint.Join.ROUND
                            "bevel" -> android.graphics.Paint.Join.BEVEL
                            else -> android.graphics.Paint.Join.MITER
                        }
                        "compositeOp" -> surf.currentState.compositeOp = v
                        "font" -> c2d.updateFont(h, v)
                        "textAlign" -> surf.currentState.textAlign = when (v.lowercase()) {
                            "center" -> android.graphics.Paint.Align.CENTER
                            "right", "end" -> android.graphics.Paint.Align.RIGHT
                            else -> android.graphics.Paint.Align.LEFT
                        }
                        "textBaseline" -> surf.currentState.textBaseline = v
                        "shadowColor" -> surf.currentState.shadowColor = Canvas2dHost.parseCssColor(v)
                    }
                }
                null
            }
            function("setLineDash") { args ->
                val h = args.getOrNull(0)?.toString().orEmpty()
                val raw = args.getOrNull(1) as? List<*>
                val surf = c2d.getSurface(h)
                if (surf != null) {
                    surf.currentState.lineDash = raw?.mapNotNull { (it as? Number)?.toFloat() }?.toFloatArray()
                        ?: FloatArray(0)
                }
                null
            }
            function("save") { args -> c2d.save(args.getOrNull(0)?.toString().orEmpty()); null }
            function("restore") { args -> c2d.restore(args.getOrNull(0)?.toString().orEmpty()); null }
            function("translate") { args ->
                c2d.translate(
                    args.getOrNull(0)?.toString().orEmpty(),
                    (args.getOrNull(1) as? Number)?.toFloat() ?: 0f,
                    (args.getOrNull(2) as? Number)?.toFloat() ?: 0f
                ); null
            }
            function("rotate") { args ->
                c2d.rotate(args.getOrNull(0)?.toString().orEmpty(), (args.getOrNull(1) as? Number)?.toFloat() ?: 0f); null
            }
            function("scale") { args ->
                c2d.scale(
                    args.getOrNull(0)?.toString().orEmpty(),
                    (args.getOrNull(1) as? Number)?.toFloat() ?: 1f,
                    (args.getOrNull(2) as? Number)?.toFloat() ?: 1f
                ); null
            }
            function("transform") { args ->
                c2d.transform(
                    args.getOrNull(0)?.toString().orEmpty(),
                    num(args, 1, 1f), num(args, 2, 0f), num(args, 3, 0f),
                    num(args, 4, 1f), num(args, 5, 0f), num(args, 6, 0f)
                ); null
            }
            function("setTransform") { args ->
                c2d.setTransform(
                    args.getOrNull(0)?.toString().orEmpty(),
                    num(args, 1, 1f), num(args, 2, 0f), num(args, 3, 0f),
                    num(args, 4, 1f), num(args, 5, 0f), num(args, 6, 0f)
                ); null
            }
            function("clearRect") { args ->
                c2d.clearRect(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4)); null
            }
            function("fillRect") { args ->
                c2d.fillRect(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4)); null
            }
            function("strokeRect") { args ->
                c2d.strokeRect(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4)); null
            }
            function("beginPath") { args -> c2d.beginPath(str(args, 0)); null }
            function("closePath") { args -> c2d.closePath(str(args, 0)); null }
            function("moveTo") { args -> c2d.moveTo(str(args, 0), num(args, 1), num(args, 2)); null }
            function("lineTo") { args -> c2d.lineTo(str(args, 0), num(args, 1), num(args, 2)); null }
            function("quadraticCurveTo") { args ->
                c2d.quadraticCurveTo(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4)); null
            }
            function("bezierCurveTo") { args ->
                c2d.bezierCurveTo(
                    str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4), num(args, 5), num(args, 6)
                ); null
            }
            function("rect") { args ->
                c2d.rect(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4)); null
            }
            function("roundRect") { args ->
                c2d.roundRect(str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4), num(args, 5)); null
            }
            function("arc") { args ->
                c2d.arc(
                    str(args, 0), num(args, 1), num(args, 2), num(args, 3),
                    num(args, 4), num(args, 5), (args.getOrNull(6) as? Boolean) ?: false
                ); null
            }
            function("ellipse") { args ->
                c2d.ellipse(
                    str(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4),
                    num(args, 5), num(args, 6), num(args, 7), (args.getOrNull(8) as? Boolean) ?: false
                ); null
            }
            function("fill") { args -> c2d.fill(str(args, 0), str(args, 1).ifBlank { "nonzero" }); null }
            function("stroke") { args -> c2d.stroke(str(args, 0)); null }
            function("clip") { args -> c2d.clip(str(args, 0)); null }
            function("fillText") { args ->
                val maxW = num(args, 4, -1f).let { if (it > 0f) it else null }
                c2d.fillText(str(args, 0), str(args, 1), num(args, 2), num(args, 3), maxW); null
            }
            function("strokeText") { args ->
                val maxW = num(args, 4, -1f).let { if (it > 0f) it else null }
                c2d.strokeText(str(args, 0), str(args, 1), num(args, 2), num(args, 3), maxW); null
            }
            function("measureText") { args -> c2d.measureTextWidth(str(args, 0), str(args, 1)) }
            function("gradLinear") { args -> c2d.createLinearGradient(num(args, 0), num(args, 1), num(args, 2), num(args, 3)) }
            function("gradRadial") { args ->
                c2d.createRadialGradient(num(args, 0), num(args, 1), num(args, 2), num(args, 3), num(args, 4), num(args, 5))
            }
            function("gradAddStop") { args -> c2d.addColorStop(str(args, 0), num(args, 1), str(args, 2)); null }
            function("drawImage") { args ->
                val dstH = str(args, 0)
                val rawSrc = str(args, 1)
                val resolvedSrc = resolveSourceToHandleSync(rawSrc) ?: return@function null
                val srcBmp = c2d.getBitmap(resolvedSrc) ?: return@function null
                val sx = num(args, 2)
                val sy = num(args, 3)
                val sw = num(args, 4, -1f).let { if (it < 0f) srcBmp.width.toFloat() else it }
                val sh = num(args, 5, -1f).let { if (it < 0f) srcBmp.height.toFloat() else it }
                val dx = num(args, 6)
                val dy = num(args, 7)
                val dw = num(args, 8, -1f).let { if (it < 0f) srcBmp.width.toFloat() else it }
                val dh = num(args, 9, -1f).let { if (it < 0f) srcBmp.height.toFloat() else it }
                c2d.drawImage(dstH, resolvedSrc, sx, sy, sw, sh, dx, dy, dw, dh)
                null
            }
            function("drawCover") { args ->
                drawCover(
                    str(args, 0), args.getOrNull(1), num(args, 2), num(args, 3),
                    num(args, 4), num(args, 5), num(args, 6, 1f)
                )
                null
            }
            function("getImageData") { args ->
                c2d.getImageData(str(args, 0), num(args, 1).toInt(), num(args, 2).toInt(), num(args, 3).toInt(), num(args, 4).toInt())
            }
            function("putImageData") { args ->
                val bytes = args.getOrNull(1) as? ByteArray ?: return@function null
                c2d.putImageData(str(args, 0), bytes, num(args, 2).toInt(), num(args, 3).toInt(), num(args, 4).toInt(), num(args, 5).toInt())
                null
            }
            function("chromaKey") { args ->
                chromaKey(
                    str(args, 0),
                    num(args, 1).toInt(),
                    num(args, 2).toInt(),
                    listOf(num(args, 3, 0f).toInt(), num(args, 4, 200f).toInt(), num(args, 5, 0f).toInt()),
                    num(args, 6, 90f),
                    num(args, 7, 35f)
                )
            }
            function("extractAudio") { args ->
                val clipIdx = parseClipIndex(str(args, 0)) ?: return@function null
                val clip = clipsList.getOrNull(clipIdx)
                if (clip != null && clip.hasAudio && clip.audioBuffer != null) {
                    mapOf("__audioClipIdx" to clipIdx, "duration" to clip.audioDurationSec.toDouble(), "sampleRate" to clip.audioBuffer.sampleRate)
                } else null
            }
            function("clearAudioBuffer") { _ -> aiGeneratedAudioBuffer = null; null }
            function("setAudioFromClip") { args ->
                val idx = (args.getOrNull(0) as? Number)?.toInt() ?: -1
                aiGeneratedAudioBuffer = clipsList.getOrNull(idx)?.audioBuffer
                null
            }
            function("setAudioSamples") { args ->
                val bytes = args.getOrNull(0) as? ByteArray ?: return@function null
                val sr = (args.getOrNull(1) as? Number)?.toInt()?.coerceAtLeast(8000) ?: 44100
                val ch = (args.getOrNull(2) as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
                val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val floats = FloatArray(fb.remaining())
                fb.get(floats)
                val dur = (floats.size / ch).toFloat() / sr.toFloat()
                aiGeneratedAudioBuffer = PcmAudioBuffer(floats, sr, ch, dur)
                null
            }
            function("log") { args ->
                onLog("JS: ${args.getOrNull(0)?.toString().orEmpty()}")
                null
            }
        }

        instance.define("__hostAsync") {
            asyncFunction("seekClip") { args ->
                val idx = parseClipIndex(str(args, 0))
                if (idx != null) seekClip(idx, num(args, 1))
                null
            }
            asyncFunction("getFrame") { args ->
                val handle = str(args, 0)
                val t = num(args, 1)
                val clipIdx = parseClipIndex(handle)
                if (clipIdx != null) {
                    getFrame(clipIdx, t)
                } else {
                    // Already a canvas handle
                    handle
                }
            }
            asyncFunction("runShader") { args ->
                val key = str(args, 0, "fx")
                val w = num(args, 1, 2f).toInt()
                val h = num(args, 2, 2f).toInt()
                val glsl = str(args, 3)
                @Suppress("UNCHECKED_CAST")
                val texMap = (args.getOrNull(4) as? Map<Any?, Any?>)
                    ?.mapNotNull { (k, v) -> if (k != null && v != null) k.toString() to v.toString() else null }
                    ?.toMap() ?: emptyMap()
                @Suppress("UNCHECKED_CAST")
                val uniMap = (args.getOrNull(5) as? Map<Any?, Any?>)
                    ?.mapNotNull { (k, v) -> if (k != null) k.toString() to v else null }
                    ?.toMap() ?: emptyMap()
                runShader(key, w, h, glsl, texMap, uniMap)
            }
            asyncFunction("detectBoxes") { args ->
                val target = str(args, 0)
                val t = num(args, 1, 0f)
                val boxes = detectBoxes(target, t)
                boxes.map { b -> mapOf("label" to b.label, "score" to b.score.toDouble(), "box" to b.box.map { it.toDouble() }) }
            }
            asyncFunction("getMediaPipeMask") { args ->
                val mask = getMediaPipeMask(str(args, 0), num(args, 1, 2f).toInt(), num(args, 2, 2f).toInt())
                mapOf("width" to mask.width, "height" to mask.height, "bytes" to floatArrayToBytes(mask.data))
            }
            asyncFunction("segmentMediaPipe") { args ->
                val feather = num(args, 3, 0.45f)
                val hardEdge = (args.getOrNull(4) as? Boolean) ?: false
                val threshold = num(args, 5, 0.5f)
                segmentMediaPipe(str(args, 0), num(args, 1, 2f).toInt(), num(args, 2, 2f).toInt(), feather, hardEdge, threshold)
            }
            asyncFunction("getSamMask") { args ->
                val promptOpts = parsePromptOpts(args.getOrNull(3))
                val mask = getSamMask(str(args, 0), num(args, 1, 2f).toInt(), num(args, 2, 2f).toInt(), promptOpts)
                mapOf("width" to mask.width, "height" to mask.height, "bytes" to floatArrayToBytes(mask.data))
            }
            asyncFunction("segmentSam") { args ->
                val rawArg3 = args.getOrNull(3)
                val promptOpts = parsePromptOpts(rawArg3)
                val defaultFeather = if (rawArg3 is Number) rawArg3.toFloat() else 0.45f
                val feather = num(args, 4, defaultFeather)
                val hardEdge = (args.getOrNull(5) as? Boolean) ?: false
                val threshold = num(args, 6, 0.5f)
                segmentSam(str(args, 0), num(args, 1, 2f).toInt(), num(args, 2, 2f).toInt(), promptOpts, feather, hardEdge, threshold)
            }
        }
    }

    private suspend fun evaluateAsyncJs(engine: QuickJs, asyncBodyJs: String) {
        val id = asyncEvalSeq.getAndIncrement()
        val deferred = CompletableDeferred<Unit>()
        pendingAsyncEvals[id] = deferred
        try {
            withContext(jsDispatcher) {
                engine.evaluate<Any?>(
                    """
                    (function() {
                      const __evalId = $id;
                      Promise.resolve()
                        .then(async function() {
                          $asyncBodyJs
                        })
                        .then(
                          function() { __host.onAsyncDone(__evalId, true, ""); },
                          function(err) {
                            const msg = (err && (err.stack || err.message)) ? String(err.stack || err.message) : String(err);
                            __host.onAsyncDone(__evalId, false, msg);
                          }
                        );
                    })();
                    """.trimIndent()
                )
            }
            deferred.await()
        } finally {
            pendingAsyncEvals.remove(id)
        }
    }

    suspend fun compileTools(toolsCode: String): List<String> = withContext(jsDispatcher) {
        val engine = ensureQuickJsReady()
        val cleaned = toolsCode.ifBlank { "const tools = {};" }
        engine.evaluate<List<String>>(
            """
            (function() {
              $cleaned
              globalThis.__stage1Tools = typeof tools !== "undefined" ? tools : {};
              return Object.keys(globalThis.__stage1Tools);
            })();
            """.trimIndent()
        ) ?: emptyList()
    }

    suspend fun setEditPlan(editPlan: List<EditPlanSegment>) = withContext(jsDispatcher) {
        val engine = ensureQuickJsReady()
        val planJson = json.encodeToString(editPlan)
        engine.evaluate<Any?>("globalThis.__activeEditPlan = $planJson;")
    }

    suspend fun compileAndInitPipeline(
        pipelineCode: String,
        toolsCode: String,
        clips: List<ClipAsset>,
        editPlan: List<EditPlanSegment>
    ) {
        val engine = withContext(jsDispatcher) {
            isRenderingFrame = false
            initCutoutSeq.set(0)
            frameCutoutSeq.set(0)
            updateClips(clips)
            synchronized(frameRingLock) {
                frameRingKeys.clear()
            }
            c2d.clearAllUserBuffers()
            val q = ensureQuickJsReady()
            aiGeneratedAudioBuffer = null

            val planJson = json.encodeToString(editPlan)
            val escapedTools = toolsCode.ifBlank { "const tools = {};" }

            val clipObjsJs = clips.mapIndexed { idx, c ->
                """{
                  clipIndex: $idx,
                  isImage: ${c.isImage},
                  name: ${json.encodeToString(c.name)},
                  width: ${c.width},
                  height: ${c.height},
                  videoWidth: ${c.width},
                  videoHeight: ${c.height},
                  naturalWidth: ${c.width},
                  naturalHeight: ${c.height},
                  duration: ${c.durationSec},
                  durationSec: ${c.durationSec},
                  hasAudio: ${c.hasAudio},
                  audioDuration: ${c.audioDurationSec}
                }"""
            }.joinToString(",\n")

            q.evaluate<Any?>(
                """
                (function() {
                  $escapedTools
                  globalThis.__stage1Tools = typeof tools !== "undefined" ? tools : (globalThis.__stage1Tools || {});
                  globalThis.__activeEditPlan = $planJson;
                  globalThis.__activeClips = [$clipObjsJs];
                })();
                """.trimIndent()
            )
            q
        }

        val hasToolsDecl = Regex("\\b(?:const|let|var)\\s+tools\\b").containsMatchIn(pipelineCode)
        val toolsParam = if (hasToolsDecl) "_stage1Tools" else "tools"

        evaluateAsyncJs(
            engine,
            """
            const __factory = function(runtime, $toolsParam, editPlan) {
              "use strict";
              $pipelineCode
              let _m = null;
              if (typeof renderFrame === "function") _m = renderFrame;
              else if (typeof renderScene === "function") _m = renderScene;
              else if (typeof tools !== "undefined" && typeof tools.renderFrame === "function") _m = tools.renderFrame;
              else if (typeof tools !== "undefined" && typeof tools.renderScene === "function") _m = tools.renderScene;
              else if (typeof _stage1Tools !== "undefined" && typeof _stage1Tools.renderFrame === "function") _m = _stage1Tools.renderFrame;
              else if (typeof _stage1Tools !== "undefined" && typeof _stage1Tools.renderScene === "function") _m = _stage1Tools.renderScene;
              return {
                init: typeof init === "function" ? init : async () => {},
                renderFrame: _m
              };
            };
            const exported = __factory(globalThis.runtime, globalThis.__stage1Tools || {}, globalThis.__activeEditPlan || []);
            if (!exported.renderFrame) {
              throw new Error("No renderFrame or renderScene function was exported in code.");
            }
            globalThis.__activeExported = exported;
            globalThis.__mainCtxHandle = "__buf_main";
            globalThis.__mainCtxObj = globalThis.__makeCtx("__buf_main");
            await exported.init(globalThis.__activeClips);
            """.trimIndent()
        )
    }

    suspend fun renderFrame(width: Int, height: Int, tSec: Float, durationSec: Float): Bitmap {
        val w = width.coerceAtLeast(2)
        val h = height.coerceAtLeast(2)
        val engine = withContext(jsDispatcher) {
            val q = ensureQuickJsReady()
            isRenderingFrame = true
            frameCutoutSeq.set(0)
            val surf = c2d.ensureSurface("__buf_main", w, h, preserveIfSameSize = true)
            surf.resetState()
            c2d.clearRect("__buf_main", 0f, 0f, w.toFloat(), h.toFloat())
            q
        }

        try {
            evaluateAsyncJs(
                engine,
                """
                const ctx = globalThis.__mainCtxObj;
                const fn = globalThis.__activeExported.renderFrame;
                const width = $w;
                const height = $h;
                const t = $tSec;
                const duration = $durationSec;
                const clips = globalThis.__activeClips || [];
                const runtime = globalThis.runtime;

                ctx.runtime = runtime;
                ctx.getCanvas = runtime.getCanvas;
                ctx.runShader = runtime.runShader;
                ctx.getFrame = runtime.getFrame;
                ctx.drawCover = runtime.drawCover;
                ctx.segmentSubject = runtime.segmentSubject;
                ctx.getSubjectMask = runtime.getSubjectMask;
                ctx.segmentMediaPipe = runtime.segmentMediaPipe;
                ctx.getMediaPipeMask = runtime.getMediaPipeMask;
                ctx.segmentSam = runtime.segmentSam;
                ctx.getSamMask = runtime.getSamMask;
                ctx.detectBoxes = runtime.detectBoxes;
                ctx.chromaKey = runtime.chromaKey;
                ctx.seekClip = runtime.seekClip;
                ctx.extractAudio = runtime.extractAudio;

                const fnStr = fn.toString();
                const match = fnStr.slice(0, 320).match(/\(([^)]*)\)/);
                const params = match ? match[1].split(",").map((s) => s.trim().toLowerCase()).filter(Boolean) : [];
                let ret;
                if (params.length > 0) {
                  const args = [];
                  for (let i = 0; i < params.length; i++) {
                    const p = params[i];
                    if (p.includes("clip") || p.includes("video") || p.includes("image") || p.includes("media") || p.includes("input") || p.includes("source")) {
                      args.push(clips);
                    } else if (p === "ctx" || p.includes("context")) {
                      args.push(ctx);
                    } else if (p === "runtime" || p.includes("helper")) {
                      args.push(runtime);
                    } else if (p === "w" || p.includes("width")) {
                      args.push(width);
                    } else if (p === "h" || p.includes("height")) {
                      args.push(height);
                    } else if (p === "t" || p.includes("time") || p.includes("sec")) {
                      args.push(t);
                    } else if (p.includes("progress")) {
                      args.push(duration > 0 ? t / duration : 0);
                    } else if (p === "duration" || p.includes("total") || p.includes("dur")) {
                      args.push(duration);
                    } else {
                      const defaults = [ctx, width, height, t, duration, clips, runtime];
                      args.push(defaults[i] !== undefined ? defaults[i] : null);
                    }
                  }
                  ret = await fn(...args);
                } else {
                  ret = await fn(ctx, width, height, t, duration, clips, runtime);
                }

                if (ret && ret.__handle && ret.__handle !== "__buf_main") {
                  ctx.drawImage(ret, 0, 0, width, height);
                }
                """.trimIndent()
            )
        } finally {
            isRenderingFrame = false
        }

        return withContext(jsDispatcher) {
            c2d.getBitmap("__buf_main") ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
    }

    fun buildValidationProbeTimes(
        duration: Float,
        editPlan: List<EditPlanSegment>,
        extraTimes: List<Float> = emptyList()
    ): List<Float> {
        val minT = 0.05f
        val maxT = max(0.05f, duration - 0.05f)
        val raw = mutableListOf<Float>()
        for (seg in editPlan) {
            val s = seg.inSec.toFloat()
            val e = seg.outSec.toFloat()
            if (e > s) raw.add((s + e) * 0.5f)
        }
        raw.addAll(extraTimes)

        val evenCount = 9
        for (i in 0 until evenCount) {
            raw.add(minT + (i / (evenCount - 1).toFloat()) * max(0f, maxT - minT))
        }

        val sorted = raw
            .map { ((it.coerceIn(minT, maxT) * 100f).roundToInt() / 100f) }
            .sorted()

        val deduped = mutableListOf<Float>()
        for (t in sorted) {
            if (deduped.all { abs(it - t) >= 0.15f }) deduped.add(t)
        }
        if (deduped.size <= 14) return deduped
        return (0 until 14).map { i ->
            deduped[((i / 13f) * (deduped.size - 1)).roundToInt()]
        }
    }

    suspend fun compileAndValidateTechnical(
        initialCode: String,
        toolsCode: String,
        clips: List<ClipAsset>,
        editPlan: List<EditPlanSegment>,
        canvasW: Int,
        canvasH: Int,
        durationSec: Float,
        extraTimes: List<Float> = emptyList(),
        initialError: Throwable? = null,
        onStatus: (String, Float?) -> Unit,
        onRequestFix: suspend (attempt: Int, failingCode: String, errorMsg: String) -> String
    ): TechnicalValidationResult {
        var currentCode = initialCode
        var consecutiveErrors = 0
        var pendingInitialErr = initialError

        while (true) {
            coroutineContext.ensureActive()
            try {
                if (pendingInitialErr != null) {
                    val err = pendingInitialErr
                    pendingInitialErr = null
                    throw err
                }
                if (currentCode.isBlank()) throw IllegalStateException("Missing renderPipelineCode.")
                compileAndInitPipeline(currentCode, toolsCode, clips, editPlan)

                val probeTimes = buildValidationProbeTimes(durationSec, editPlan, extraTimes)
                var visibleFrames = 0
                var flatDetail = ""

                for (tt in probeTimes) {
                    coroutineContext.ensureActive()
                    val bmp = renderFrame(canvasW, canvasH, tt, durationSec)
                    val stats: FramePixelStats = FrameExtractor.measureFrameStats(bmp)
                    val hasPixels = stats.nonBlackPct >= 1 && stats.flatPct < 96
                    if (hasPixels) visibleFrames++
                    else if (stats.flatPct >= 96) {
                        flatDetail = "flat single-color screen (${stats.flatPct}% uniform RGB ~ [${stats.avgR},${stats.avgG},${stats.avgB}]) at t=${"%.2f".format(tt)}s"
                    }
                    onLog("Validation render t=${"%.2f".format(tt)}s (visibleContent=$hasPixels, nonBlack=${stats.nonBlackPct}%, flat=${stats.flatPct}%)")
                }

                if (visibleFrames < max(2, ceil(probeTimes.size * 0.5).toInt())) {
                    throw IllegalStateException(
                        if (flatDetail.isNotEmpty()) {
                            "Validation check: rendered output was a $flatDetail across test timestamps — likely a full-canvas fillRect or composite operation ('source-in'/'destination-in') applied directly on main ctx instead of runtime.getCanvas(...)."
                        } else {
                            "Validation check: rendered output was black/blank across test timestamps."
                        }
                    )
                }

                return TechnicalValidationResult(code = currentCode, probeCount = probeTimes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (execErr: Throwable) {
                consecutiveErrors++
                val msg = execErr.message ?: execErr::class.java.simpleName
                onLog("Code error detected ($consecutiveErrors): $msg")

                if (consecutiveErrors == 6) {
                    onLog("Too many consecutive patch errors. Synthesizing guaranteed fallback baseline...")
                    currentCode = buildFallbackBaselineCode(clips.isNotEmpty())
                    continue
                }
                if (consecutiveErrors > 6) {
                    throw execErr
                }

                onStatus("AI Auto-fixing code error ($consecutiveErrors)...", 0.90f)
                currentCode = onRequestFix(consecutiveErrors, currentCode, msg)
            }
        }
    }

    private fun buildFallbackBaselineCode(hasClips: Boolean): String {
        return if (hasClips) {
            """
            async function init(clips) {}
            async function renderFrame(ctx, width, height, t, duration, clips) {
              ctx.fillStyle = '#000';
              ctx.fillRect(0, 0, width, height);
              const clip = clips[0];
              if (clip) {
                const frame = await runtime.getFrame(clip, t);
                runtime.drawCover(ctx, frame, 0, 0, width, height, 1.0);
              }
            }
            """.trimIndent()
        } else {
            """
            async function init(clips) {}
            async function renderFrame(ctx, width, height, t, duration, clips) {
              const g = ctx.createLinearGradient(0, 0, width, height);
              g.addColorStop(0, '#121833');
              g.addColorStop(1, '#1e3a5f');
              ctx.fillStyle = g;
              ctx.fillRect(0, 0, width, height);
            }
            """.trimIndent()
        }
    }

    private fun updateClips(clips: List<ClipAsset>) {
        clipsList = clips
        retrieverCache.values.forEach { runCatching { it.release() } }
        retrieverCache.clear()
        imageCache.values.forEach { if (!it.isRecycled) it.recycle() }
        imageCache.clear()
        clipSeekTimes.clear()
    }

    private fun parseFrameHandle(raw: String): Pair<Int, Float>? {
        if (!raw.startsWith("__frame_")) return null
        val parts = raw.removePrefix("__frame_").split("_")
        if (parts.size != 2) return null
        val clipIdx = parts[0].toIntOrNull() ?: return null
        val qTick = parts[1].toIntOrNull() ?: return null
        return clipIdx to (qTick / 24f)
    }

    private fun nextCutoutHandle(prefix: String, safeW: Int, safeH: Int): String {
        return if (isRenderingFrame) {
            "${prefix}_f${frameCutoutSeq.getAndIncrement()}_${safeW}x$safeH"
        } else {
            "${prefix}_i${initCutoutSeq.getAndIncrement()}_${safeW}x$safeH"
        }
    }

    private fun resolveSourceBitmapInternal(handleOrIdx: Any?): ResolvedBitmap? {
        val raw = when (handleOrIdx) {
            is Number -> "__clip_${handleOrIdx.toInt()}"
            else -> handleOrIdx?.toString().orEmpty()
        }
        c2d.getBitmap(raw)?.takeIf { !it.isRecycled }?.let {
            return ResolvedBitmap(it, isTemporary = false)
        }
        val framePair = parseFrameHandle(raw)
        if (framePair != null) {
            val (clipIdx, t) = framePair
            val bmp = extractClipBitmapSync(clipIdx, t)?.takeIf { !it.isRecycled } ?: return null
            val isTemp = clipsList.getOrNull(clipIdx)?.isImage != true
            return ResolvedBitmap(bmp, isTemporary = isTemp)
        }
        val clipIdx = parseClipIndex(raw) ?: return null
        val t = clipSeekTimes[clipIdx] ?: 0f
        val bmp = extractClipBitmapSync(clipIdx, t)?.takeIf { !it.isRecycled } ?: return null
        val isTemp = clipsList.getOrNull(clipIdx)?.isImage != true
        return ResolvedBitmap(bmp, isTemporary = isTemp)
    }

    private fun resolveSourceToHandleSync(raw: String): String? {
        if (c2d.getBitmap(raw)?.let { !it.isRecycled } == true) return raw
        val framePair = parseFrameHandle(raw)
        val clipIdx: Int
        val t: Float
        val key: String
        if (framePair != null) {
            clipIdx = framePair.first
            t = framePair.second
            key = raw
        } else {
            clipIdx = parseClipIndex(raw) ?: return null
            t = clipSeekTimes[clipIdx] ?: 0f
            val qTick = (t * 24f).roundToInt()
            key = "__frame_${clipIdx}_$qTick"
        }
        synchronized(frameRingLock) {
            if (c2d.getBitmap(key)?.let { !it.isRecycled } == true) return key
        }
        val bmp = extractClipBitmapSync(clipIdx, t) ?: return null
        val copy = if (!bmp.isRecycled) bmp.copy(Bitmap.Config.ARGB_8888, true) else null
        if (clipsList.getOrNull(clipIdx)?.isImage != true && !bmp.isRecycled) {
            bmp.recycle()
        }
        if (copy == null) return null
        synchronized(frameRingLock) {
            c2d.putBitmapAsSurface(key, copy)
            frameRingKeys.remove(key)
            frameRingKeys.addLast(key)
            while (frameRingKeys.size > 18) {
                val oldKey = frameRingKeys.removeFirst()
                if (oldKey != key) {
                    c2d.removeSurface(oldKey)
                }
            }
        }
        return key
    }

    private fun extractClipBitmapSync(clipIndex: Int, timeSec: Float): Bitmap? {
        val clip = clipsList.getOrNull(clipIndex) ?: return null
        if (clip.isImage) {
            imageCache[clipIndex]?.takeIf { !it.isRecycled }?.let { return it }
            val decoded = runCatching {
                kotlinx.coroutines.runBlocking { FrameExtractor.decodeImageBitmap(clip.localFile, 1080) }
            }.getOrNull() ?: return null
            imageCache[clipIndex] = decoded
            return decoded
        }

        val retriever = retrieverCache.getOrPut(clipIndex) {
            MediaMetadataRetriever().apply { setDataSource(clip.localFile.absolutePath) }
        }
        return FrameExtractor.extractVideoFrame(
            retriever = retriever,
            durationSec = clip.durationSec,
            timeSec = timeSec,
            maxEdge = 1080,
            exact = true
        )
    }

    // ---- NativeRuntimeApi implementations ----

    override fun getCanvas(key: String, width: Int, height: Int): String {
        val handle = "__user_$key"
        c2d.ensureSurface(handle, width, height, preserveIfSameSize = true)
        return handle
    }

    override suspend fun seekClip(clipIndex: Int, timeSec: Float) {
        clipSeekTimes[clipIndex] = timeSec
    }

    override suspend fun getFrame(clipIndex: Int, timeSec: Float): String = withContext(Dispatchers.Default) {
        clipSeekTimes[clipIndex] = timeSec
        val qTick = (timeSec * 24f).roundToInt()
        val ringKey = "__frame_${clipIndex}_$qTick"
        synchronized(frameRingLock) {
            if (c2d.getBitmap(ringKey)?.let { !it.isRecycled } == true) {
                return@withContext ringKey
            }
        }
        val bmp = extractClipBitmapSync(clipIndex, timeSec)
            ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val copy = if (!bmp.isRecycled) {
            bmp.copy(Bitmap.Config.ARGB_8888, true) ?: Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        } else {
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        }
        if (clipsList.getOrNull(clipIndex)?.isImage != true && !bmp.isRecycled) {
            bmp.recycle()
        }
        synchronized(frameRingLock) {
            c2d.putBitmapAsSurface(ringKey, copy)
            frameRingKeys.remove(ringKey)
            frameRingKeys.addLast(ringKey)
            while (frameRingKeys.size > 18) {
                val oldKey = frameRingKeys.removeFirst()
                if (oldKey != ringKey) {
                    c2d.removeSurface(oldKey)
                }
            }
        }
        ringKey
    }

    override fun drawCover(
        ctxHandle: String,
        sourceHandleOrClipIndex: Any?,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        zoom: Float
    ) {
        val dst = c2d.getSurface(ctxHandle) ?: return
        val resolved = resolveSourceBitmapInternal(sourceHandleOrClipIndex) ?: return
        try {
            val srcBmp = resolved.bitmap
            if (srcBmp.isRecycled || width <= 0f || height <= 0f) return
            smartSegmenter.drawCover(dst.canvas, srcBmp, x, y, width, height, zoom)
        } finally {
            if (resolved.isTemporary && !resolved.bitmap.isRecycled) {
                resolved.bitmap.recycle()
            }
        }
    }

    override suspend fun runShader(
        key: String,
        outWidth: Int,
        outHeight: Int,
        fragmentGlsl: String,
        texturesMap: Map<String, String>,
        uniformsMap: Map<String, Any?>
    ): String {
        val resolvedTextures = mutableMapOf<String, Bitmap?>()
        val tempBitmaps = mutableListOf<Bitmap>()
        try {
            for ((texKey, handleOrIdx) in texturesMap) {
                val resolved = resolveSourceBitmapInternal(handleOrIdx)
                resolvedTextures[texKey] = resolved?.bitmap
                if (resolved != null && resolved.isTemporary) {
                    tempBitmaps.add(resolved.bitmap)
                }
            }
            val rendered = glShaderRunner.runShader(
                key = key,
                outW = outWidth,
                outH = outHeight,
                fragmentGlsl = fragmentGlsl,
                texturesMap = resolvedTextures,
                uniformsMap = uniformsMap
            )
            val handle = "__shader_$key"
            c2d.putBitmapAsSurface(handle, rendered)
            return handle
        } finally {
            for (tmp in tempBitmaps) {
                if (!tmp.isRecycled) tmp.recycle()
            }
        }
    }

    override suspend fun detectBoxes(clipIndexOrHandle: Any?, timestampSec: Float): List<DetectedBox> {
        val raw = clipIndexOrHandle?.toString().orEmpty()
        val clipIdx = parseClipIndex(raw)
        val resolved = if (clipIdx != null) {
            val bmp = extractClipBitmapSync(clipIdx, timestampSec)
            bmp?.let { ResolvedBitmap(it, isTemporary = clipsList.getOrNull(clipIdx)?.isImage != true) }
        } else {
            resolveSourceBitmapInternal(raw)
        } ?: return emptyList()

        return try {
            val bmp = resolved.bitmap
            if (!bmp.isRecycled) smartSegmenter.detectBoxes(bmp) else emptyList()
        } finally {
            if (resolved.isTemporary && !resolved.bitmap.isRecycled) {
                resolved.bitmap.recycle()
            }
        }
    }

    override suspend fun getMediaPipeMask(clipIndexOrHandle: Any?, width: Int, height: Int): MaskResult {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val resolved = resolveSourceBitmapInternal(clipIndexOrHandle)
        val bmp = resolved?.bitmap ?: Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val shouldRecycle = resolved == null || resolved.isTemporary
        return try {
            smartSegmenter.getMediaPipeMask(bmp, safeW, safeH)
        } finally {
            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
        }
    }

    override suspend fun segmentMediaPipe(
        clipIndexOrHandle: Any?,
        width: Int,
        height: Int,
        feather: Float,
        hardEdge: Boolean,
        threshold: Float
    ): String {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val resolved = resolveSourceBitmapInternal(clipIndexOrHandle)
        val bmp = resolved?.bitmap ?: Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val shouldRecycle = resolved == null || resolved.isTemporary
        return try {
            val mask = smartSegmenter.getMediaPipeMask(bmp, safeW, safeH)
            val cutout = smartSegmenter.applyMaskToCutout(bmp, mask, feather, hardEdge, threshold)
            val handle = nextCutoutHandle("__seg_mp", safeW, safeH)
            c2d.putBitmapAsSurface(handle, cutout)
            handle
        } finally {
            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
        }
    }

    override suspend fun getSamMask(
        clipIndexOrHandle: Any?,
        width: Int,
        height: Int,
        promptOpts: SamPromptOptions
    ): MaskResult {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val resolved = resolveSourceBitmapInternal(clipIndexOrHandle)
        val bmp = resolved?.bitmap ?: Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val shouldRecycle = resolved == null || resolved.isTemporary
        return try {
            smartSegmenter.getSamMask(bmp, safeW, safeH, promptOpts)
        } finally {
            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
        }
    }

    override suspend fun segmentSam(
        clipIndexOrHandle: Any?,
        width: Int,
        height: Int,
        promptOpts: SamPromptOptions,
        feather: Float,
        hardEdge: Boolean,
        threshold: Float
    ): String {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val resolved = resolveSourceBitmapInternal(clipIndexOrHandle)
        val bmp = resolved?.bitmap ?: Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val shouldRecycle = resolved == null || resolved.isTemporary
        return try {
            val mask = smartSegmenter.getSamMask(bmp, safeW, safeH, promptOpts)
            val cutout = smartSegmenter.applyMaskToCutout(bmp, mask, feather, hardEdge, threshold)
            val handle = nextCutoutHandle("__seg_sam", safeW, safeH)
            c2d.putBitmapAsSurface(handle, cutout)
            handle
        } finally {
            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
        }
    }

    override fun chromaKey(
        clipIndexOrHandle: Any?,
        width: Int,
        height: Int,
        targetRgb: List<Int>,
        tolerance: Float,
        feather: Float
    ): String {
        val safeW = width.coerceAtLeast(2)
        val safeH = height.coerceAtLeast(2)
        val resolved = resolveSourceBitmapInternal(clipIndexOrHandle)
        val bmp = resolved?.bitmap ?: Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
        val shouldRecycle = resolved == null || resolved.isTemporary
        return try {
            val cutout = smartSegmenter.chromaKey(
                source = bmp,
                outW = safeW,
                outH = safeH,
                targetR = targetRgb.getOrElse(0) { 0 },
                targetG = targetRgb.getOrElse(1) { 200 },
                targetB = targetRgb.getOrElse(2) { 0 },
                tolerance = tolerance,
                feather = feather
            )
            val handle = nextCutoutHandle("__chroma", safeW, safeH)
            c2d.putBitmapAsSurface(handle, cutout)
            handle
        } finally {
            if (shouldRecycle && !bmp.isRecycled) bmp.recycle()
        }
    }

    override fun extractAudio(clipIndex: Int): PcmAudioBuffer? = clipsList.getOrNull(clipIndex)?.audioBuffer

    override fun setAudioBuffer(buffer: PcmAudioBuffer?) {
        aiGeneratedAudioBuffer = buffer
    }

    private fun parsePromptOpts(raw: Any?): SamPromptOptions {
        val map = raw as? Map<*, *> ?: return SamPromptOptions()
        val boxes = (map["boxes"] as? List<*>)?.mapNotNull { b ->
            (b as? List<*>)?.mapNotNull { (it as? Number)?.toFloat() }?.takeIf { it.size >= 4 }
        } ?: emptyList()
        val points = (map["points"] as? List<*>)?.mapNotNull { p ->
            val pm = p as? Map<*, *> ?: return@mapNotNull null
            val px = (pm["x"] as? Number)?.toFloat() ?: return@mapNotNull null
            val py = (pm["y"] as? Number)?.toFloat() ?: return@mapNotNull null
            val lbl = (pm["label"] as? Number)?.toInt() ?: 1
            SamPointPrompt(px, py, lbl)
        } ?: emptyList()
        return SamPromptOptions(boxes = boxes, points = points)
    }

    private fun floatArrayToBytes(arr: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in arr) buf.putFloat(v)
        return buf.array()
    }

    private fun parseClipIndex(handle: String): Int? =
        if (handle.startsWith("__clip_")) handle.removePrefix("__clip_").toIntOrNull() else handle.toIntOrNull()

    private fun str(args: Array<out Any?>, idx: Int, default: String = ""): String =
        args.getOrNull(idx)?.toString() ?: default

    private fun num(args: Array<out Any?>, idx: Int, default: Float = 0f): Float =
        (args.getOrNull(idx) as? Number)?.toFloat() ?: default

    fun release() {
        pendingAsyncEvals.values.forEach { it.cancel() }
        pendingAsyncEvals.clear()
        jsExecutor.execute {
            runCatching { qjs?.close() }
            qjs = null
            synchronized(frameRingLock) {
                frameRingKeys.clear()
            }
            c2d.clearAllUserBuffers()
            retrieverCache.values.forEach { runCatching { it.release() } }
            retrieverCache.clear()
            imageCache.values.forEach { if (!it.isRecycled) it.recycle() }
            imageCache.clear()
        }
        jsExecutor.shutdown()
    }
}