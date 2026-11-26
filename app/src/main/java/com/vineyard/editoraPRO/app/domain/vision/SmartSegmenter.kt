package com.vineyard.editoraPRO.app.domain.vision

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.vineyard.editoraPRO.app.data.model.DetectedBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class SamPointPrompt(
    val x: Float,
    val y: Float,
    val label: Int
)

data class SamPromptOptions(
    val boxes: List<List<Float>> = emptyList(),
    val points: List<SamPointPrompt> = emptyList()
)

data class MaskResult(
    val width: Int,
    val height: Int,
    val data: FloatArray
)

/**
 * 5-Model Cooperative Vision Engine:
 * - Tier 1 (Fast All-People MediaPipe): deeplab_v3.tflite (class 15) + selfie_multiclass_256x256.tflite (1 - class 0) fused via max().
 * - Tier 2 (Zero-Click Detection & Promptable HD Cutout): efficientdet_lite0.tflite + edge_sam_3x_encoder.onnx + edge_sam_3x_decoder.onnx.
 */
class SmartSegmenter(
    private val context: Context,
    private val onLog: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val initMutex = Mutex()
    private val inferMutex = Mutex()

    private var deepLabSegmenter: ImageSegmenter? = null
    private var multiclassSegmenter: ImageSegmenter? = null
    private var objectDetector: ObjectDetector? = null

    private var ortEnv: OrtEnvironment? = null
    private var samEncoderSession: OrtSession? = null
    private var samDecoderSession: OrtSession? = null

    // Embedding cache for EdgeSAM so multiple prompts on the same frame reuse [1, 256, 64, 64]
    private var lastEmbeddingHash: Int = 0
    private var lastEmbeddingData: FloatArray? = null
    private var lastEmbeddingShape: LongArray? = null

    private fun assetExists(name: String): Boolean = runCatching {
        appContext.assets.open(name).use { true }
    }.getOrDefault(false)

    private fun ensureSegmenterWithDelegate(assetName: String): ImageSegmenter? {
        if (!assetExists(assetName)) {
            onLog("Model asset missing: $assetName")
            return null
        }
        return runCatching {
            val baseOpts = BaseOptions.builder()
                .setModelAssetPath(assetName)
                .setDelegate(Delegate.CPU)
                .build()
            val opts = ImageSegmenter.ImageSegmenterOptions.builder()
                .setBaseOptions(baseOpts)
                .setRunningMode(RunningMode.IMAGE)
                .setOutputConfidenceMasks(true)
                .setOutputCategoryMask(false)
                .build()
            ImageSegmenter.createFromOptions(appContext, opts)
        }.getOrElse { cpuErr ->
            onLog("CPU delegate failed for $assetName (${cpuErr.message}).")
            null
        }
    }

    private suspend fun ensureTier1Ready() = initMutex.withLock {
        if (deepLabSegmenter == null) {
            deepLabSegmenter = ensureSegmenterWithDelegate(MODEL_DEEPLAB)
            if (deepLabSegmenter != null) onLog("Tier 1 loaded: $MODEL_DEEPLAB")
        }
        if (multiclassSegmenter == null) {
            multiclassSegmenter = ensureSegmenterWithDelegate(MODEL_SELFIE_MULTICLASS)
            if (multiclassSegmenter != null) onLog("Tier 1 loaded: $MODEL_SELFIE_MULTICLASS")
        }
    }

    private suspend fun ensureDetectorReady() = initMutex.withLock {
        if (objectDetector == null && assetExists(MODEL_EFFICIENTDET)) {
            objectDetector = runCatching {
                val baseOpts = BaseOptions.builder()
                    .setModelAssetPath(MODEL_EFFICIENTDET)
                    .setDelegate(Delegate.CPU)
                    .build()
                val opts = ObjectDetector.ObjectDetectorOptions.builder()
                    .setBaseOptions(baseOpts)
                    .setRunningMode(RunningMode.IMAGE)
                    .setScoreThreshold(0.25f)
                    .setMaxResults(12)
                    .build()
                ObjectDetector.createFromOptions(appContext, opts)
            }.getOrElse { err ->
                onLog("Tier 2 detector load failed for $MODEL_EFFICIENTDET (${err.message}).")
                null
            }
            if (objectDetector != null) onLog("Tier 2 loaded: $MODEL_EFFICIENTDET")
        }
    }

    private fun copyOnnxAssetToFilesDir(assetName: String): File? {
        if (!assetExists(assetName)) return null
        val dir = File(appContext.filesDir, "onnx_models").apply { mkdirs() }
        val outFile = File(dir, assetName)
        if (outFile.exists() && outFile.length() > 100_000L) return outFile
        return runCatching {
            appContext.assets.open(assetName).use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            outFile
        }.getOrNull()
    }

    private suspend fun ensureEdgeSamReady() = initMutex.withLock {
        if (ortEnv == null) {
            ortEnv = OrtEnvironment.getEnvironment()
        }
        val env = ortEnv ?: return@withLock
        if (samEncoderSession == null) {
            val encFile = copyOnnxAssetToFilesDir(MODEL_EDGE_SAM_ENCODER)
            if (encFile != null) {
                samEncoderSession = createOrtSession(env, encFile)
                if (samEncoderSession != null) onLog("Tier 2 mmap-loaded: $MODEL_EDGE_SAM_ENCODER")
            }
        }
        if (samDecoderSession == null) {
            val decFile = copyOnnxAssetToFilesDir(MODEL_EDGE_SAM_DECODER)
            if (decFile != null) {
                samDecoderSession = createOrtSession(env, decFile)
                if (samDecoderSession != null) onLog("Tier 2 mmap-loaded: $MODEL_EDGE_SAM_DECODER")
            }
        }
    }

    private fun createOrtSession(env: OrtEnvironment, modelFile: File): OrtSession? {
        return runCatching {
            val opts = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { addNnapi() }
            }
            env.createSession(modelFile.absolutePath, opts)
        }.getOrElse {
            runCatching {
                val cpuOpts = OrtSession.SessionOptions()
                env.createSession(modelFile.absolutePath, cpuOpts)
            }.getOrNull()
        }
    }

    private fun createCoverBitmap(source: Bitmap, w: Int, h: Int): Bitmap {
        if (source.width == w && source.height == h) return source
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCover(Canvas(out), source, 0f, 0f, w.toFloat(), h.toFloat(), 1f)
        return out
    }

    private fun mapPromptsToCover(
        opts: SamPromptOptions,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ): SamPromptOptions {
        if (srcW == dstW && srcH == dstH) return opts
        val sw = srcW.coerceAtLeast(1).toFloat()
        val sh = srcH.coerceAtLeast(1).toFloat()
        val dwF = dstW.toFloat()
        val dhF = dstH.toFloat()
        val scale = max(dwF / sw, dhF / sh)
        val scaledW = sw * scale
        val scaledH = sh * scale
        val offX = (dwF - scaledW) * 0.5f
        val offY = (dhF - scaledH) * 0.5f

        val mappedBoxes = opts.boxes.mapNotNull { b ->
            if (b.size < 4) return@mapNotNull null
            val rawX0 = (b[0] * scaledW + offX) / dwF
            val rawY0 = (b[1] * scaledH + offY) / dhF
            val rawX1 = ((b[0] + b[2]) * scaledW + offX) / dwF
            val rawY1 = ((b[1] + b[3]) * scaledH + offY) / dhF
            if (rawX1 <= 0f || rawX0 >= 1f || rawY1 <= 0f || rawY0 >= 1f) {
                return@mapNotNull null
            }
            val x0 = rawX0.coerceIn(0f, 1f)
            val y0 = rawY0.coerceIn(0f, 1f)
            val x1 = rawX1.coerceIn(0f, 1f)
            val y1 = rawY1.coerceIn(0f, 1f)
            val bw = (x1 - x0).coerceAtLeast(0f)
            val bh = (y1 - y0).coerceAtLeast(0f)
            if (bw > 0.005f && bh > 0.005f) listOf(x0, y0, bw, bh) else null
        }

        val mappedPoints = opts.points.mapNotNull { p ->
            val px = (p.x * scaledW + offX) / dwF
            val py = (p.y * scaledH + offY) / dhF
            if (px in -0.05f..1.05f && py in -0.05f..1.05f) {
                SamPointPrompt(px.coerceIn(0f, 1f), py.coerceIn(0f, 1f), p.label)
            } else {
                null
            }
        }

        return SamPromptOptions(boxes = mappedBoxes, points = mappedPoints)
    }

    /**
     * Tier 1: Dual MediaPipe human segmentation (DeepLab-v3 class 15 + Selfie Multiclass inverted class 0).
     */
    suspend fun getMediaPipeMask(source: Bitmap, outW: Int, outH: Int): MaskResult =
        withContext(Dispatchers.Default) {
            val w = outW.coerceAtLeast(2)
            val h = outH.coerceAtLeast(2)
            if (source.isRecycled || source.width <= 0 || source.height <= 0) {
                onLog("Tier 1 MediaPipe skipped: invalid or recycled source bitmap.")
                return@withContext MaskResult(w, h, FloatArray(w * h))
            }
            ensureTier1Ready()
            inferMutex.withLock {
                if (source.isRecycled || source.width <= 0 || source.height <= 0) {
                    onLog("Tier 1 MediaPipe skipped: source bitmap recycled before inference.")
                    return@withLock MaskResult(w, h, FloatArray(w * h))
                }
                val startMs = SystemClock.elapsedRealtime()
                val scaledIn = if (source.width != w || source.height != h) {
                    createCoverBitmap(source, w, h)
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return@withLock MaskResult(w, h, FloatArray(w * h))
                }

                val mpImage = BitmapImageBuilder(scaledIn).build()
                val fused = FloatArray(w * h)
                var anyMask = false

                try {
                    val dl = deepLabSegmenter
                    if (dl != null) {
                        val res = dl.segment(mpImage)
                        val masksOpt = res.confidenceMasks()
                        if (masksOpt.isPresent && masksOpt.get().size > 15) {
                            val personMp = masksOpt.get()[15]
                            val buf = ByteBufferExtractor.extract(personMp)
                                .order(ByteOrder.nativeOrder())
                                .asFloatBuffer()
                            val raw = FloatArray(buf.remaining())
                            buf.get(raw)
                            val resized = resizeMaskBilinear(raw, personMp.width, personMp.height, w, h)
                            for (i in fused.indices) {
                                fused[i] = max(fused[i], resized[i].coerceIn(0f, 1f))
                            }
                            anyMask = true
                        }
                    }

                    val mc = multiclassSegmenter
                    if (mc != null) {
                        val res = mc.segment(mpImage)
                        val masksOpt = res.confidenceMasks()
                        if (masksOpt.isPresent && masksOpt.get().isNotEmpty()) {
                            val bgMp = masksOpt.get()[0]
                            val buf = ByteBufferExtractor.extract(bgMp)
                                .order(ByteOrder.nativeOrder())
                                .asFloatBuffer()
                            val raw = FloatArray(buf.remaining())
                            buf.get(raw)
                            val resized = resizeMaskBilinear(raw, bgMp.width, bgMp.height, w, h)
                            for (i in fused.indices) {
                                val fgConf = (1.0f - resized[i]).coerceIn(0f, 1f)
                                fused[i] = max(fused[i], fgConf)
                            }
                            anyMask = true
                        }
                    }
                } finally {
                    mpImage.close()
                    if (scaledIn !== source && !scaledIn.isRecycled) scaledIn.recycle()
                }

                val elapsed = SystemClock.elapsedRealtime() - startMs
                if (!anyMask) {
                    fused.fill(0f)
                    onLog("Tier 1 MediaPipe failed (${w}x${h}): no segmentation model loaded/active (${elapsed}ms)")
                } else {
                    onLog("Tier 1 MediaPipe dual fusion (${w}x${h}) completed in ${elapsed}ms")
                }
                MaskResult(w, h, fused)
            }
        }

    /**
     * Tier 2 Step 1: EfficientDet-Lite0 zero-click 80-class COCO detector.
     */
    suspend fun detectBoxes(source: Bitmap): List<DetectedBox> = withContext(Dispatchers.Default) {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            return@withContext emptyList()
        }
        ensureDetectorReady()
        inferMutex.withLock {
            if (source.isRecycled || source.width <= 0 || source.height <= 0) {
                return@withLock emptyList()
            }
            val detector = objectDetector ?: return@withLock emptyList()
            val detInput = source.copy(Bitmap.Config.ARGB_8888, false)
                ?: return@withLock emptyList()
            val mpImage = BitmapImageBuilder(detInput).build()
            try {
                val result = detector.detect(mpImage)
                val imgW = detInput.width.coerceAtLeast(1).toFloat()
                val imgH = detInput.height.coerceAtLeast(1).toFloat()
                result.detections().mapNotNull { det ->
                    val cat = det.categories().firstOrNull() ?: return@mapNotNull null
                    val box = det.boundingBox()
                    val nx = (box.left / imgW).coerceIn(0f, 1f)
                    val ny = (box.top / imgH).coerceIn(0f, 1f)
                    val nw = (box.width() / imgW).coerceIn(0f, 1f - nx)
                    val nh = (box.height() / imgH).coerceIn(0f, 1f - ny)
                    DetectedBox(
                        label = cat.categoryName(),
                        score = cat.score(),
                        box = listOf(nx, ny, nw, nh)
                    )
                }
            } finally {
                mpImage.close()
                if (!detInput.isRecycled) detInput.recycle()
            }
        }
    }

    /**
     * Tier 2 Step 2 & 3: EdgeSAM ONNX Encoder + Decoder (aspect-agnostic & subject-agnostic).
     */
    suspend fun getSamMask(
        source: Bitmap,
        outW: Int,
        outH: Int,
        promptOpts: SamPromptOptions
    ): MaskResult = withContext(Dispatchers.Default) {
        val w = outW.coerceAtLeast(2)
        val h = outH.coerceAtLeast(2)
        if (source.isRecycled || source.width <= 0 || source.height <= 0) {
            onLog("Tier 2 EdgeSAM skipped: invalid or recycled source bitmap.")
            return@withContext MaskResult(w, h, FloatArray(w * h))
        }
        ensureEdgeSamReady()

        val hasExplicitPrompts = promptOpts.boxes.isNotEmpty() || promptOpts.points.isNotEmpty()
        val workBmp = createCoverBitmap(source, w, h)

        try {
            val resolvedPrompts = if (!hasExplicitPrompts) {
                val detected = detectBoxes(workBmp)
                val best = detected.maxByOrNull { det ->
                    val bw = det.box.getOrElse(2) { 0f }.coerceAtLeast(0f)
                    val bh = det.box.getOrElse(3) { 0f }.coerceAtLeast(0f)
                    val area = bw * bh
                    val cx = det.box.getOrElse(0) { 0.5f } + bw * 0.5f
                    val cy = det.box.getOrElse(1) { 0.5f } + bh * 0.5f
                    val dx = cx - 0.5f
                    val dy = cy - 0.5f
                    val centerWeight = (1.0f - sqrt(dx * dx + dy * dy)).coerceIn(0.25f, 1.0f)
                    det.score * sqrt(area.coerceAtLeast(0.0001f)) * centerWeight
                }
                if (best != null && best.box.size >= 4) {
                    val cx = (best.box[0] + best.box[2] * 0.5f).coerceIn(0f, 1f)
                    val cy = (best.box[1] + best.box[3] * 0.5f).coerceIn(0f, 1f)
                    SamPromptOptions(
                        boxes = listOf(best.box),
                        points = listOf(SamPointPrompt(cx, cy, 1))
                    )
                } else {
                    deriveAutoPromptFromMask(workBmp)
                }
            } else {
                mapPromptsToCover(promptOpts, source.width, source.height, w, h)
            }

            if (resolvedPrompts.boxes.isEmpty() && resolvedPrompts.points.isEmpty()) {
                return@withContext MaskResult(w, h, FloatArray(w * h))
            }

            inferMutex.withLock {
                if (workBmp.isRecycled || workBmp.width <= 0 || workBmp.height <= 0) {
                    onLog("Tier 2 EdgeSAM skipped: source bitmap recycled before inference.")
                    return@withLock MaskResult(w, h, FloatArray(w * h))
                }
                val env = ortEnv
                val enc = samEncoderSession
                val dec = samDecoderSession
                if (env == null || enc == null || dec == null) {
                    onLog("EdgeSAM ONNX sessions unavailable; falling back to Tier 1 mask.")
                    return@withLock MaskResult(w, h, FloatArray(w * h))
                }

                // 1. Run or reuse 1024x1024 EdgeSAM encoder embedding
                val frameHash = computeBitmapHash(workBmp)
                val embData: FloatArray
                val embShape: LongArray

                if (frameHash == lastEmbeddingHash && lastEmbeddingData != null && lastEmbeddingShape != null) {
                    embData = lastEmbeddingData!!
                    embShape = lastEmbeddingShape!!
                } else {
                    val input1024 = Bitmap.createScaledBitmap(workBmp, 1024, 1024, true)
                    val pixels = IntArray(1024 * 1024)
                    input1024.getPixels(pixels, 0, 1024, 0, 0, 1024, 1024)
                    if (input1024 !== workBmp) input1024.recycle()

                    val tensorData = FloatArray(3 * 1024 * 1024)
                    val plane = 1024 * 1024
                    for (i in 0 until plane) {
                        val px = pixels[i]
                        val r = ((px shr 16) and 0xFF).toFloat()
                        val g = ((px shr 8) and 0xFF).toFloat()
                        val b = (px and 0xFF).toFloat()
                        tensorData[i] = (r - 123.675f) / 58.395f
                        tensorData[plane + i] = (g - 116.28f) / 57.12f
                        tensorData[2 * plane + i] = (b - 103.53f) / 57.375f
                    }

                    val encInputName = enc.inputNames.first()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(tensorData), longArrayOf(1, 3, 1024, 1024)).use { inpTensor ->
                        enc.run(mapOf(encInputName to inpTensor)).use { encRes ->
                            val outTensor = encRes[0] as OnnxTensor
                            embShape = outTensor.info.shape
                            val fb = outTensor.floatBuffer
                            embData = FloatArray(fb.remaining())
                            fb.get(embData)
                            lastEmbeddingHash = frameHash
                            lastEmbeddingData = embData
                            lastEmbeddingShape = embShape
                        }
                    }
                }

                // 2. Map boxes (labels 2.0 top-left, 3.0 bottom-right) & points into 1024-space
                val coordsList = mutableListOf<Float>()
                val labelsList = mutableListOf<Float>()

                for (box in resolvedPrompts.boxes) {
                    if (box.size >= 4) {
                        val x0 = (box[0].coerceIn(0f, 1f)) * 1024f
                        val y0 = (box[1].coerceIn(0f, 1f)) * 1024f
                        val x1 = ((box[0] + box[2]).coerceIn(0f, 1f)) * 1024f
                        val y1 = ((box[1] + box[3]).coerceIn(0f, 1f)) * 1024f
                        coordsList.add(x0); coordsList.add(y0); labelsList.add(2.0f)
                        coordsList.add(x1); coordsList.add(y1); labelsList.add(3.0f)
                    }
                }
                for (pt in resolvedPrompts.points) {
                    coordsList.add(pt.x.coerceIn(0f, 1f) * 1024f)
                    coordsList.add(pt.y.coerceIn(0f, 1f) * 1024f)
                    labelsList.add(pt.label.toFloat())
                }
                if (resolvedPrompts.boxes.isEmpty() && resolvedPrompts.points.isNotEmpty()) {
                    coordsList.add(0f); coordsList.add(0f); labelsList.add(-1f)
                }

                val numPts = labelsList.size.coerceAtLeast(1)
                val coordsArr = coordsList.toFloatArray()
                val labelsArr = labelsList.toFloatArray()

                val decInputs = mutableMapOf<String, OnnxTensor>()
                val createdTensors = mutableListOf<OnnxTensor>()
                try {
                    for (name in dec.inputNames) {
                        val tensor = when (name) {
                            "image_embeddings" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(embData), embShape)
                            "point_coords" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(coordsArr), longArrayOf(1, numPts.toLong(), 2))
                            "point_labels" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(labelsArr), longArrayOf(1, numPts.toLong()))
                            "mask_input" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256))
                            "has_mask_input" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1))
                            "orig_im_size" -> OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(h.toFloat(), w.toFloat())), longArrayOf(2))
                            else -> null
                        }
                        if (tensor != null) {
                            decInputs[name] = tensor
                            createdTensors.add(tensor)
                        }
                    }

                    dec.run(decInputs).use { decRes ->
                        var bestMaskIdx = 0
                        var maskTensor: OnnxTensor? = null
                        for ((idx, entry) in decRes.withIndex()) {
                            val t = entry.value as? OnnxTensor ?: continue
                            val shape = t.info.shape
                            if (shape.size == 2 && shape[0] == 1L && shape[1] in 1L..4L) {
                                val scores = FloatArray(shape[1].toInt())
                                t.floatBuffer.get(scores)
                                bestMaskIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
                            } else if (shape.size == 4 && maskTensor == null) {
                                maskTensor = t
                            } else if (idx == 0 && maskTensor == null && shape.size == 4) {
                                maskTensor = t
                            }
                        }

                        val chosenMask = maskTensor ?: (decRes[0] as OnnxTensor)
                        val mShape = chosenMask.info.shape // [1, C, MH, MW]
                        val numMasks = mShape[1].toInt().coerceAtLeast(1)
                        val mh = mShape[2].toInt().coerceAtLeast(1)
                        val mw = mShape[3].toInt().coerceAtLeast(1)
                        val planeSize = mh * mw
                        val pickedIdx = bestMaskIdx.coerceIn(0, numMasks - 1)

                        val fb = chosenMask.floatBuffer
                        val allLogits = FloatArray(fb.remaining())
                        fb.get(allLogits)

                        val rawMask = FloatArray(planeSize)
                        val offset = pickedIdx * planeSize
                        for (i in 0 until planeSize) {
                            val logit = allLogits.getOrElse(offset + i) { -10f }
                            rawMask[i] = (1.0f / (1.0f + exp(-logit))).coerceIn(0f, 1f)
                        }

                        val resized = resizeMaskBilinear(rawMask, mw, mh, w, h)
                        MaskResult(w, h, resized)
                    }
                } finally {
                    createdTensors.forEach { runCatching { it.close() } }
                }
            }
        } finally {
            if (workBmp !== source) workBmp.recycle()
        }
    }

    private suspend fun deriveAutoPromptFromMask(source: Bitmap): SamPromptOptions {
        val probeW = 128
        val probeH = 128
        val probe = getMediaPipeMask(source, probeW, probeH)
        var minX = probeW
        var minY = probeH
        var maxX = -1
        var maxY = -1
        var sumX = 0f
        var sumY = 0f
        var count = 0

        for (y in 0 until probeH) {
            val row = y * probeW
            for (x in 0 until probeW) {
                if (probe.data[row + x] >= 0.45f) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    sumX += x
                    sumY += y
                    count++
                }
            }
        }

        return if (count >= 16 && maxX > minX && maxY > minY) {
            val bx = (minX.toFloat() / probeW).coerceIn(0f, 1f)
            val by = (minY.toFloat() / probeH).coerceIn(0f, 1f)
            val bw = ((maxX - minX + 1).toFloat() / probeW).coerceIn(0.02f, 1f - bx)
            val bh = ((maxY - minY + 1).toFloat() / probeH).coerceIn(0.02f, 1f - by)
            val cx = (sumX / count / probeW).coerceIn(0f, 1f)
            val cy = (sumY / count / probeH).coerceIn(0f, 1f)
            SamPromptOptions(
                boxes = listOf(listOf(bx, by, bw, bh)),
                points = listOf(SamPointPrompt(cx, cy, 1))
            )
        } else {
            SamPromptOptions(points = listOf(SamPointPrompt(0.5f, 0.5f, 1)))
        }
    }

    fun applyMaskToCutout(
        source: Bitmap,
        mask: MaskResult,
        feather: Float = 0.45f,
        hardEdge: Boolean = false,
        threshold: Float = 0.5f
    ): Bitmap {
        val w = mask.width
        val h = mask.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        drawCover(canvas, source, 0f, 0f, w.toFloat(), h.toFloat(), 1f)

        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val data = mask.data
        val denom = max(0.05f, 1f - feather)

        for (i in pixels.indices) {
            val conf = data.getOrElse(i) { 0f }
            val alpha = if (hardEdge || feather <= 0f) {
                if (conf >= threshold) 1f else 0f
            } else {
                if (conf > feather) min(1f, (conf - feather) / denom) else 0f
            }
            val a = (255f * alpha).roundToInt().coerceIn(0, 255)
            pixels[i] = (pixels[i] and 0x00FFFFFF) or (a shl 24)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    fun drawCover(
        canvas: Canvas,
        media: Bitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        zoom: Float = 1f
    ) {
        if (media.isRecycled || media.width <= 0 || media.height <= 0 || w <= 0f || h <= 0f) return
        val mw = media.width.coerceAtLeast(1).toFloat()
        val mh = media.height.coerceAtLeast(1).toFloat()
        val scale = max(w / mw, h / mh) * zoom
        val dw = mw * scale
        val dh = mh * scale
        val left = x + (w - dw) / 2f
        val top = y + (h - dh) / 2f
        canvas.save()
        canvas.clipRect(x, y, x + w, y + h)
        canvas.drawBitmap(
            media,
            null,
            android.graphics.RectF(left, top, left + dw, top + dh),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
        canvas.restore()
    }

    fun chromaKey(
        source: Bitmap,
        outW: Int,
        outH: Int,
        targetR: Int = 0,
        targetG: Int = 200,
        targetB: Int = 0,
        tolerance: Float = 90f,
        feather: Float = 35f
    ): Bitmap {
        val w = outW.coerceAtLeast(2)
        val h = outH.coerceAtLeast(2)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawCover(Canvas(out), source, 0f, 0f, w.toFloat(), h.toFloat(), 1f)

        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val safeFeather = max(1f, feather)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = (px ushr 24) and 0xFF
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val dr = (r - targetR).toFloat()
            val dg = (g - targetG).toFloat()
            val db = (b - targetB).toFloat()
            val dist = sqrt(dr * dr + dg * dg + db * db)
            val newA = when {
                dist < tolerance -> 0
                dist < tolerance + safeFeather -> (a * ((dist - tolerance) / safeFeather)).roundToInt().coerceIn(0, 255)
                else -> a
            }
            pixels[i] = (px and 0x00FFFFFF) or (newA shl 24)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun resizeMaskBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        if (sw == dw && sh == dh && src.size >= sw * sh) return src
        if (sw <= 0 || sh <= 0 || src.isEmpty()) return FloatArray(dw * dh)
        val out = FloatArray(dw * dh)
        val xRatio = if (dw > 1) (sw - 1).toFloat() / (dw - 1) else 0f
        val yRatio = if (dh > 1) (sh - 1).toFloat() / (dh - 1) else 0f

        for (y in 0 until dh) {
            val gy = y * yRatio
            val y0 = gy.toInt().coerceIn(0, sh - 1)
            val y1 = min(y0 + 1, sh - 1)
            val dy = gy - y0
            val row0 = y0 * sw
            val row1 = y1 * sw
            val outRow = y * dw

            for (x in 0 until dw) {
                val gx = x * xRatio
                val x0 = gx.toInt().coerceIn(0, sw - 1)
                val x1 = min(x0 + 1, sw - 1)
                val dx = gx - x0

                val v00 = src.getOrElse(row0 + x0) { 0f }
                val v10 = src.getOrElse(row0 + x1) { 0f }
                val v01 = src.getOrElse(row1 + x0) { 0f }
                val v11 = src.getOrElse(row1 + x1) { 0f }
                val top = v00 + (v10 - v00) * dx
                val bot = v01 + (v11 - v01) * dx
                out[outRow + x] = (top + (bot - top) * dy).coerceIn(0f, 1f)
            }
        }
        return out
    }

    private fun computeBitmapHash(bmp: Bitmap): Int {
        val sample = Bitmap.createScaledBitmap(bmp, 16, 16, false)
        val px = IntArray(256)
        sample.getPixels(px, 0, 16, 0, 0, 16, 16)
        if (sample !== bmp) sample.recycle()
        var h = bmp.width * 31 + bmp.height
        for (p in px) h = h * 31 + p
        return h
    }

    fun release() {
        runCatching { deepLabSegmenter?.close() }
        runCatching { multiclassSegmenter?.close() }
        runCatching { objectDetector?.close() }
        runCatching { samEncoderSession?.close() }
        runCatching { samDecoderSession?.close() }
        deepLabSegmenter = null
        multiclassSegmenter = null
        objectDetector = null
        samEncoderSession = null
        samDecoderSession = null
    }

    companion object {
        const val MODEL_DEEPLAB = "deeplab_v3.tflite"
        const val MODEL_SELFIE_MULTICLASS = "selfie_multiclass_256x256.tflite"
        const val MODEL_EFFICIENTDET = "efficientdet_lite0.tflite"
        const val MODEL_EDGE_SAM_ENCODER = "edge_sam_3x_encoder.onnx"
        const val MODEL_EDGE_SAM_DECODER = "edge_sam_3x_decoder.onnx"
    }
}