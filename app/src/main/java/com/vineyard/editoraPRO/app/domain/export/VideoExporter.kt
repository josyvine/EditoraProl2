package com.vineyard.editoraPRO.app.domain.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.domain.media.BeatDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ExportResult(
    val file: File,
    val shareUri: Uri,
    val durationSec: Float,
    val sizeBytes: Long
)

/**
 * Deterministic frame-by-frame Hardware Video Encoder (H.264/AVC via YUV420Flexible Image)
 * and Hardware AAC Audio Encoder muxed via MediaMuxer into an exact-duration MP4.
 */
class VideoExporter(private val context: Context) {

    suspend fun exportVideo(
        width: Int,
        height: Int,
        fps: Int,
        durationSec: Float,
        audioBuffer: PcmAudioBuffer?,
        filePrefix: String = "editora_export",
        bitrate: Int = 5_000_000,
        renderFrameAt: suspend (timeSec: Float) -> Bitmap,
        onProgress: (progress: Float, statusText: String) -> Unit
    ): ExportResult = withContext(Dispatchers.Default) {
        val outW = (width and 1.inv()).coerceAtLeast(16)
        val outH = (height and 1.inv()).coerceAtLeast(16)
        val safeFps = fps.coerceIn(10, 60)
        val safeDur = durationSec.coerceAtLeast(0.5f)
        val totalFrames = ceil(safeDur * safeFps).toInt().coerceAtLeast(1)

        val exportsDir = File(context.filesDir, "exports").apply { mkdirs() }
        val outFile = File(exportsDir, "${filePrefix}_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val wantAudio = audioBuffer != null && audioBuffer.samples.isNotEmpty()
        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val vFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, safeFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(vFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            if (wantAudio) {
                val aFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48_000, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }
                audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(aFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrackIdx = -1
            var audioTrackIdx = -1
            var muxerStarted = false
            var pendingVideoFormat: MediaFormat? = null
            var pendingAudioFormat: MediaFormat? = null

            fun tryStartMuxer() {
                if (muxerStarted) return
                val vf = pendingVideoFormat ?: return
                if (wantAudio && pendingAudioFormat == null) return
                videoTrackIdx = muxer.addTrack(vf)
                if (wantAudio && pendingAudioFormat != null) {
                    audioTrackIdx = muxer.addTrack(pendingAudioFormat!!)
                }
                muxer.start()
                muxerStarted = true
            }

            val queuedAudioSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()
            val queuedVideoSamples = mutableListOf<Pair<ByteBuffer, MediaCodec.BufferInfo>>()

            fun flushQueuedSamples() {
                if (!muxerStarted) return
                while (queuedVideoSamples.isNotEmpty() && videoTrackIdx >= 0) {
                    val (buf, info) = queuedVideoSamples.removeAt(0)
                    muxer.writeSampleData(videoTrackIdx, buf, info)
                }
                while (queuedAudioSamples.isNotEmpty() && audioTrackIdx >= 0) {
                    val (buf, info) = queuedAudioSamples.removeAt(0)
                    muxer.writeSampleData(audioTrackIdx, buf, info)
                }
            }

            fun drainVideo(endOfStream: Boolean): Boolean {
                val enc = videoEncoder ?: return true
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            pendingVideoFormat = enc.outputFormat
                            tryStartMuxer()
                            flushQueuedSamples()
                        }
                        outIdx >= 0 -> {
                            val encoded = enc.getOutputBuffer(outIdx)
                            if (encoded != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                if (muxerStarted && videoTrackIdx >= 0) {
                                    flushQueuedSamples()
                                    muxer.writeSampleData(videoTrackIdx, encoded, info)
                                } else {
                                    val copy = ByteBuffer.allocate(info.size).apply {
                                        put(encoded)
                                        flip()
                                    }
                                    val copyInfo = MediaCodec.BufferInfo().apply {
                                        set(0, info.size, info.presentationTimeUs, info.flags)
                                    }
                                    queuedVideoSamples.add(copy to copyInfo)
                                }
                            }
                            enc.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                return true
                            }
                        }
                        else -> break
                    }
                }
                return false
            }

            fun drainAudio(endOfStream: Boolean): Boolean {
                val enc = audioEncoder ?: return true
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val outIdx = enc.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            pendingAudioFormat = enc.outputFormat
                            tryStartMuxer()
                            flushQueuedSamples()
                        }
                        outIdx >= 0 -> {
                            val encoded = enc.getOutputBuffer(outIdx)
                            if (encoded != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                encoded.position(info.offset)
                                encoded.limit(info.offset + info.size)
                                if (muxerStarted && audioTrackIdx >= 0) {
                                    flushQueuedSamples()
                                    muxer.writeSampleData(audioTrackIdx, encoded, info)
                                } else {
                                    val copy = ByteBuffer.allocate(info.size).apply {
                                        put(encoded)
                                        flip()
                                    }
                                    val copyInfo = MediaCodec.BufferInfo().apply {
                                        set(0, info.size, info.presentationTimeUs, info.flags)
                                    }
                                    queuedAudioSamples.add(copy to copyInfo)
                                }
                            }
                            enc.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                return true
                            }
                        }
                        else -> break
                    }
                }
                return false
            }

            // First encode audio track (if enabled) so audio format arrives fast and muxer starts cleanly
            if (wantAudio && audioBuffer != null && audioEncoder != null) {
                onProgress(0.04f, "Preparing synchronized 48kHz audio...")
                val pcmShorts = BeatDetector.resampleTo48kStereoPcm16(audioBuffer, safeDur)
                val samplesPerFrame = 1024
                val shortsPerChunk = samplesPerFrame * 2
                var shortOffset = 0
                var framesEncoded = 0L

                while (shortOffset < pcmShorts.size) {
                    coroutineContext.ensureActive()
                    val inIdx = audioEncoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val countShorts = min(shortsPerChunk, pcmShorts.size - shortOffset)
                        val byteCount = countShorts * 2
                        val inBuf = audioEncoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        inBuf.order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until countShorts) {
                            inBuf.putShort(pcmShorts[shortOffset + i])
                        }
                        val ptsUs = (framesEncoded * 1_000_000L) / 48_000L
                        audioEncoder.queueInputBuffer(inIdx, 0, byteCount, ptsUs, 0)
                        shortOffset += countShorts
                        framesEncoded += (countShorts / 2)
                    }
                    drainAudio(endOfStream = false)
                }

                // Signal audio EOS
                while (true) {
                    val inIdx = audioEncoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ptsUs = (framesEncoded * 1_000_000L) / 48_000L
                        audioEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        break
                    }
                    drainAudio(endOfStream = false)
                }
                var audioEos = false
                var audioDrainSpin = 0
                while (!audioEos && audioDrainSpin++ < 120) {
                    audioEos = drainAudio(endOfStream = true)
                }
            }

            val argbPixels = IntArray(outW * outH)
            for (f in 0 until totalFrames) {
                coroutineContext.ensureActive()
                val t = min(safeDur - 0.001f, f.toFloat() / safeFps.toFloat())
                val rawBmp = renderFrameAt(t)
                val frameBmp = if (rawBmp.width != outW || rawBmp.height != outH) {
                    Bitmap.createScaledBitmap(rawBmp, outW, outH, true)
                } else {
                    rawBmp
                }
                frameBmp.getPixels(argbPixels, 0, outW, 0, 0, outW, outH)
                if (frameBmp !== rawBmp) frameBmp.recycle()

                var submitted = false
                while (!submitted) {
                    coroutineContext.ensureActive()
                    val inIdx = videoEncoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ptsUs = (f.toLong() * 1_000_000L) / safeFps.toLong()
                        val inputImage = runCatching { videoEncoder.getInputImage(inIdx) }.getOrNull()
                        val bytesWritten = if (inputImage != null) {
                            writeArgbToYuvImage(argbPixels, outW, outH, inputImage)
                            outW * outH * 3 / 2
                        } else {
                            val buf = videoEncoder.getInputBuffer(inIdx)!!
                            writeArgbToI420Buffer(argbPixels, outW, outH, buf)
                        }
                        videoEncoder.queueInputBuffer(inIdx, 0, bytesWritten, ptsUs, 0)
                        submitted = true
                    }
                    drainVideo(endOfStream = false)
                }

                val pct = 0.08f + ((f + 1).toFloat() / totalFrames.toFloat()) * 0.88f
                onProgress(
                    pct.coerceIn(0.08f, 0.96f),
                    "Encoding frame ${f + 1}/$totalFrames (${"%.1f".format(t)}s / ${"%.1f".format(safeDur)}s)"
                )
            }

            // Signal video EOS
            while (true) {
                val inIdx = videoEncoder.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ptsUs = (totalFrames.toLong() * 1_000_000L) / safeFps.toLong()
                    videoEncoder.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drainVideo(endOfStream = false)
            }
            var videoEos = false
            var vSpin = 0
            while (!videoEos && vSpin++ < 200) {
                videoEos = drainVideo(endOfStream = true)
            }

            onProgress(0.98f, "Finalizing MP4 container...")
            flushQueuedSamples()
            if (muxerStarted) {
                muxer.stop()
            }
        } finally {
            runCatching { videoEncoder?.stop(); videoEncoder?.release() }
            runCatching { audioEncoder?.stop(); audioEncoder?.release() }
            runCatching { muxer?.release() }
        }

        val shareUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
        onProgress(
            1f,
            "Exported exact ${"%.1f".format(safeDur)}s • ${"%.2f".format(outFile.length() / (1024.0 * 1024.0))} MB (.mp4)"
        )
        ExportResult(
            file = outFile,
            shareUri = shareUri,
            durationSec = safeDur,
            sizeBytes = outFile.length()
        )
    }

    suspend fun exportQaVideoFile(
        canvasW: Int,
        canvasH: Int,
        durationSec: Float,
        renderFrameAt: suspend (timeSec: Float) -> Bitmap,
        onStatus: (String) -> Unit
    ): File = withContext(Dispatchers.Default) {
        val maxEdge = max(canvasW, canvasH).coerceAtLeast(1)
        val scale = min(1f, 480f / maxEdge.toFloat())
        val qaW = (((canvasW * scale).roundToInt()) and 1.inv()).coerceAtLeast(16)
        val qaH = (((canvasH * scale).roundToInt()) and 1.inv()).coerceAtLeast(16)
        val res = exportVideo(
            width = qaW,
            height = qaH,
            fps = 12,
            durationSec = durationSec,
            audioBuffer = null,
            filePrefix = "qa_preview",
            bitrate = 1_200_000,
            renderFrameAt = renderFrameAt,
            onProgress = { _, msg -> onStatus(msg) }
        )
        res.file
    }

    suspend fun saveToMoviesGallery(sourceFile: File, displayName: String): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/editoraPRO")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(itemUri)?.use { out ->
                sourceFile.inputStream().use { inp -> inp.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
            itemUri
        } else {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)
        }
    }

    private fun writeArgbToYuvImage(argb: IntArray, w: Int, h: Int, image: android.media.Image) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride

        for (y in 0 until h) {
            val rowOff = y * w
            val yBase = y * yRowStride
            val uvRow = y / 2
            val uBase = uvRow * uRowStride
            val vBase = uvRow * vRowStride

            for (x in 0 until w) {
                val px = argb[rowOff + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF

                val yy = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255)
                yBuf.put(yBase + x * yPixelStride, yy.toByte())

                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uvCol = x / 2
                    val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255)
                    val uIdx = uBase + uvCol * uPixelStride
                    val vIdx = vBase + uvCol * vPixelStride
                    if (uIdx < uBuf.limit()) uBuf.put(uIdx, uu.toByte())
                    if (vIdx < vBuf.limit()) vBuf.put(vIdx, vv.toByte())
                }
            }
        }
        image.close()
    }

    private fun writeArgbToI420Buffer(argb: IntArray, w: Int, h: Int, buf: ByteBuffer): Int {
        buf.clear()
        val ySize = w * h
        val uvSize = ySize / 4
        val yArr = ByteArray(ySize)
        val uArr = ByteArray(uvSize)
        val vArr = ByteArray(uvSize)
        var uvIdx = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = argb[y * w + x]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                yArr[y * w + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                if ((y and 1) == 0 && (x and 1) == 0 && uvIdx < uvSize) {
                    uArr[uvIdx] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    vArr[uvIdx] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
                    uvIdx++
                }
            }
        }
        buf.put(yArr)
        buf.put(uArr)
        buf.put(vArr)
        return ySize + uvSize * 2
    }
}
