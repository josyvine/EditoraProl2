package com.vineyard.editoraPRO.app.domain.vision

import com.vineyard.editoraPRO.app.data.model.DetectedBox
import kotlin.math.roundToInt

data class CachedSamEmbedding(
    val data: FloatArray,
    val shape: LongArray
)

/**
 * Thread-safe multi-frame LRU cache for SmartSegmenter:
 * - Caches EfficientDet box lists, EdgeSAM encoder embeddings, and quantized 8-bit masks
 *   so slow-motion, forward-reverse ramps, scrubbing, and re-exports avoid redundant ONNX/TFLite runs.
 * - Stores masks internally as compact ByteArrays (4x smaller than FloatArrays) to stay safe on heap.
 */
class MaskCacheManager(
    private val maxEmbeddings: Int = 6,
    private val maxSamMasks: Int = 40,
    private val maxMediaPipeMasks: Int = 24,
    private val maxBoxes: Int = 64
) {
    private data class MaskKey(
        val frameHash: Int,
        val width: Int,
        val height: Int,
        val promptSignature: Int
    )

    private data class CompactMask(
        val width: Int,
        val height: Int,
        val bytes: ByteArray
    )

    private val lock = Any()

    private val embeddingCache = object : LinkedHashMap<Int, CachedSamEmbedding>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CachedSamEmbedding>?): Boolean {
            return size > maxEmbeddings
        }
    }

    private val samMaskCache = object : LinkedHashMap<MaskKey, CompactMask>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MaskKey, CompactMask>?): Boolean {
            return size > maxSamMasks
        }
    }

    private val mediaPipeMaskCache = object : LinkedHashMap<MaskKey, CompactMask>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MaskKey, CompactMask>?): Boolean {
            return size > maxMediaPipeMasks
        }
    }

    private val boxesCache = object : LinkedHashMap<Int, List<DetectedBox>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<DetectedBox>>?): Boolean {
            return size > maxBoxes
        }
    }

    fun getMediaPipeMask(frameHash: Int, width: Int, height: Int): MaskResult? {
        val key = MaskKey(frameHash, width, height, 0)
        val compact = synchronized(lock) { mediaPipeMaskCache[key] } ?: return null
        return expandMask(compact)
    }

    fun putMediaPipeMask(frameHash: Int, mask: MaskResult) {
        val compact = compressMask(mask) ?: return
        val key = MaskKey(frameHash, mask.width, mask.height, 0)
        synchronized(lock) {
            mediaPipeMaskCache[key] = compact
        }
    }

    fun getBoxes(frameHash: Int): List<DetectedBox>? = synchronized(lock) {
        boxesCache[frameHash]
    }

    fun putBoxes(frameHash: Int, boxes: List<DetectedBox>) {
        val snapshot = boxes.toList()
        synchronized(lock) {
            boxesCache[frameHash] = snapshot
        }
    }

    fun getEmbedding(frameHash: Int): CachedSamEmbedding? = synchronized(lock) {
        embeddingCache[frameHash]
    }

    fun putEmbedding(frameHash: Int, data: FloatArray, shape: LongArray) {
        val entry = CachedSamEmbedding(data = data, shape = shape.copyOf())
        synchronized(lock) {
            embeddingCache[frameHash] = entry
        }
    }

    fun getSamMask(frameHash: Int, width: Int, height: Int, promptSignature: Int): MaskResult? {
        val key = MaskKey(frameHash, width, height, promptSignature)
        val compact = synchronized(lock) { samMaskCache[key] } ?: return null
        return expandMask(compact)
    }

    fun putSamMask(frameHash: Int, promptSignature: Int, mask: MaskResult) {
        val compact = compressMask(mask) ?: return
        val key = MaskKey(frameHash, mask.width, mask.height, promptSignature)
        synchronized(lock) {
            samMaskCache[key] = compact
        }
    }

    fun clear() {
        synchronized(lock) {
            embeddingCache.clear()
            samMaskCache.clear()
            mediaPipeMaskCache.clear()
            boxesCache.clear()
        }
    }

    private fun compressMask(mask: MaskResult): CompactMask? {
        val total = mask.width * mask.height
        if (mask.width <= 0 || mask.height <= 0 || mask.data.size < total) return null
        val bytes = ByteArray(total)
        val src = mask.data
        for (i in 0 until total) {
            val q = (src[i].coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            bytes[i] = q.toByte()
        }
        return CompactMask(mask.width, mask.height, bytes)
    }

    private fun expandMask(compact: CompactMask): MaskResult {
        val total = compact.width * compact.height
        val out = FloatArray(total)
        val bytes = compact.bytes
        val inv = 1f / 255f
        for (i in 0 until total) {
            out[i] = (bytes[i].toInt() and 0xFF) * inv
        }
        return MaskResult(compact.width, compact.height, out)
    }
}