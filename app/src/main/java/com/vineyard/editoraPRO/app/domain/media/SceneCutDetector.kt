package com.vineyard.editoraPRO.app.domain.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Exact 64px-wide Mean Absolute Difference (MAD) frame-difference scene cut detector.
 */
object SceneCutDetector {

    suspend fun detectSceneCuts(
        videoFile: File,
        durationSec: Float,
        videoWidth: Int,
        videoHeight: Int,
        onProgress: (status: String, progress: Float, feed: String) -> Unit
    ): List<Double> = withContext(Dispatchers.Default) {
        val dur = durationSec.coerceAtLeast(0.5f)
        val vw = videoWidth.coerceAtLeast(2)
        val vh = videoHeight.coerceAtLeast(2)

        val sampleCount = (dur * 2.5f).roundToInt().coerceIn(16, 72)
        val probeW = 64
        val probeH = ((probeW * vh) / vw).coerceIn(36, 128)

        val retriever = MediaMetadataRetriever()
        val diffs = ArrayList<Pair<Double, Double>>(sampleCount)
        var prevPixels: IntArray? = null

        try {
            retriever.setDataSource(videoFile.absolutePath)
            val span = max(0.05f, dur - 0.04f)
            for (i in 0 until sampleCount) {
                coroutineContext.ensureActive()
                val ts = ((i.toFloat() / max(1, sampleCount - 1)) * span).coerceIn(0f, max(0f, dur - 0.04f))
                val frame = FrameExtractor.extractVideoFrame(retriever, dur, ts, maxEdge = max(probeW, probeH), exact = true)
                if (frame != null) {
                    val scaled = Bitmap.createScaledBitmap(frame, probeW, probeH, false)
                    val pixels = IntArray(probeW * probeH)
                    scaled.getPixels(pixels, 0, probeW, 0, 0, probeW, probeH)
                    if (scaled !== frame) scaled.recycle()
                    frame.recycle()

                    val prev = prevPixels
                    if (prev != null) {
                        var absSum = 0L
                        for (p in pixels.indices) {
                            val c1 = pixels[p]
                            val c0 = prev[p]
                            absSum += abs(((c1 shr 16) and 0xFF) - ((c0 shr 16) and 0xFF))
                            absSum += abs(((c1 shr 8) and 0xFF) - ((c0 shr 8) and 0xFF))
                            absSum += abs((c1 and 0xFF) - (c0 and 0xFF))
                        }
                        val mad = absSum.toDouble() / (pixels.size * 3.0)
                        val roundedTs = ((ts * 100.0).roundToInt()) / 100.0
                        diffs.add(roundedTs to mad)
                    }
                    prevPixels = pixels
                }

                if (i % 8 == 0) {
                    val pct = (((i + 1) * 100f) / sampleCount).roundToInt()
                    onProgress(
                        "Detecting reference scene cuts ($pct%)...",
                        0.05f + (pct / 100f) * 0.05f,
                        "Probing frame ${i + 1}/$sampleCount (${"%.2f".format(ts)}s) for visual cuts..."
                    )
                }
            }
        } finally {
            runCatching { retriever.release() }
        }

        if (diffs.isEmpty()) return@withContext emptyList()

        val avgMad = diffs.map { it.second }.average()
        val variance = diffs.map { (it.second - avgMad) * (it.second - avgMad) }.average()
        val stdMad = sqrt(variance)
        val threshold = max(8.0, avgMad + 0.75 * stdMad)

        val peaks = ArrayList<Pair<Double, Double>>()
        for (i in diffs.indices) {
            val cur = diffs[i]
            val prevMad = if (i > 0) diffs[i - 1].second else 0.0
            val nextMad = if (i < diffs.lastIndex) diffs[i + 1].second else 0.0
            if (cur.second >= threshold && cur.second >= prevMad && cur.second >= nextMad) {
                peaks.add(cur)
            }
        }

        val candidates = (if (peaks.size >= 3) peaks else diffs.filter { it.second >= max(6.0, avgMad * 0.9) })
            .sortedByDescending { it.second }

        val chosen = ArrayList<Double>()
        for ((ts, _) in candidates) {
            if (ts < 0.15) continue
            if (chosen.all { abs(it - ts) >= 0.35 }) {
                chosen.add(ts)
                if (chosen.size >= 16) break
            }
        }
        chosen.sorted()
    }
}
