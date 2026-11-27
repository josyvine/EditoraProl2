package com.vineyard.editoraPRO.app.domain.export

import android.content.Context
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.domain.engine.QuickJsRunner
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Builds a fast, hardware-encoded playable MP4 preview proxy in app cache so ExoVideoPreview
 * can play back heavy scripts (such as EdgeSAM + multi-pass GLSL) smoothly in real time
 * instead of stalling on live per-frame QuickJS execution.
 */
class PreviewProxyRenderer(
    private val context: Context,
    private val videoExporter: VideoExporter
) {
    private val proxyDir: File
        get() = File(context.cacheDir, "preview_proxies").apply { mkdirs() }

    suspend fun buildPreviewProxy(
        quickJsRunner: QuickJsRunner,
        canvasWidth: Int,
        canvasHeight: Int,
        durationSec: Float,
        audioBuffer: PcmAudioBuffer?,
        maxProxyEdge: Int = 480,
        proxyFps: Int = 15,
        bitrate: Int = 1_600_000,
        restoreDecodeEdge: Int = 1080,
        onProgress: (progress: Float, statusText: String) -> Unit,
        onFrameProgress: ((frameIndex: Int, totalFrames: Int, etaSec: Long) -> Unit)? = null
    ): File {
        val safeW = canvasWidth.coerceAtLeast(16)
        val safeH = canvasHeight.coerceAtLeast(16)
        val maxDim = max(safeW, safeH).coerceAtLeast(1)
        val scale = min(1f, maxProxyEdge.coerceAtLeast(16).toFloat() / maxDim.toFloat())
        val proxyW = ((safeW * scale).roundToInt() and 1.inv()).coerceAtLeast(16)
        val proxyH = ((safeH * scale).roundToInt() and 1.inv()).coerceAtLeast(16)
        val safeDur = durationSec.coerceAtLeast(0.5f)
        val safeFps = proxyFps.coerceIn(10, 30)

        clearProxyFiles()

        quickJsRunner.setDecodeMaxEdge(maxProxyEdge.coerceIn(320, 1080))
        return try {
            val result = videoExporter.exportVideo(
                width = proxyW,
                height = proxyH,
                fps = safeFps,
                durationSec = safeDur,
                audioBuffer = audioBuffer,
                filePrefix = "preview_proxy",
                bitrate = bitrate,
                renderFrameAt = { tSec ->
                    quickJsRunner.renderFrame(proxyW, proxyH, tSec, safeDur)
                },
                onProgress = onProgress,
                outputDir = proxyDir,
                preferSurfaceInput = true,
                onFrameProgress = onFrameProgress
            )
            result.file
        } finally {
            quickJsRunner.setDecodeMaxEdge(restoreDecodeEdge.coerceIn(320, 1920))
        }
    }

    fun clearProxyFiles() {
        runCatching {
            val dir = File(context.cacheDir, "preview_proxies")
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    runCatching { file.delete() }
                }
            }
        }
    }
}