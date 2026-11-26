package com.vineyard.editoraPRO.app.domain.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class VideoProbeResult(
    val durationSec: Float,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean
)

data class FramePixelStats(
    val nonBlackPct: Int,
    val flatPct: Int,
    val avgR: Int,
    val avgG: Int,
    val avgB: Int
)

object FrameExtractor {

    suspend fun copyUriToCache(
        context: Context,
        uri: Uri,
        prefix: String
    ): Pair<File, Pair<String, String>> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        var displayName = "${prefix}_${UUID.randomUUID().toString().take(8)}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val colIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (colIdx >= 0) {
                    val name = cursor.getString(colIdx)
                    if (!name.isNullOrBlank()) displayName = name
                }
            }
        }
        val mediaDir = File(context.cacheDir, "media").apply { mkdirs() }
        val safeName = "${prefix}_${System.currentTimeMillis()}_${displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}"
        val outFile = File(mediaDir, safeName)
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output, bufferSize = 64 * 1024)
            }
        } ?: throw IllegalStateException("Cannot read selected media URI.")
        outFile to (displayName to mime)
    }

    fun isImageAsset(name: String, mimeType: String): Boolean {
        if (mimeType.startsWith("image/", ignoreCase = true)) return true
        return Regex("\\.(jpe?g|png|webp|gif|bmp|avif|heic)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }

    suspend fun probeVideoMetadata(file: File): VideoProbeResult = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var durationMs = 0L
        var width = 540
        var height = 960
        var hasAudio = false
        try {
            retriever.setDataSource(file.absolutePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 540
            val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 960
            val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                width = rawH
                height = rawW
            } else {
                width = rawW
                height = rawH
            }
            hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
        } finally {
            runCatching { retriever.release() }
        }

        if (!hasAudio) {
            hasAudio = probeHasAudioTrack(file)
        }
        val safeDur = if (durationMs > 0L) (durationMs / 1000f) else 10f
        VideoProbeResult(
            durationSec = safeDur.coerceAtLeast(0.2f),
            width = width.coerceAtLeast(2),
            height = height.coerceAtLeast(2),
            hasAudio = hasAudio
        )
    }

    private fun probeHasAudioTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return true
            }
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    suspend fun decodeImageBitmap(file: File, maxDim: Int = 1080): Bitmap = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        val rawW = opts.outWidth.coerceAtLeast(1)
        val rawH = opts.outHeight.coerceAtLeast(1)
        var inSample = 1
        while (max(rawW / inSample, rawH / inSample) > maxDim * 2) {
            inSample *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = inSample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            ?: throw IllegalStateException("Cannot decode image ${file.name}")

        // Apply EXIF rotation if present
        val rotation = runCatching {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)

        if (rotation != 0f) {
            val m = Matrix().apply { postRotate(rotation) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        if (max(bmp.width, bmp.height) > maxDim) {
            val scale = maxDim.toFloat() / max(bmp.width, bmp.height).toFloat()
            val nw = (bmp.width * scale).roundToInt().coerceAtLeast(2)
            val nh = (bmp.height * scale).roundToInt().coerceAtLeast(2)
            val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        bmp
    }

    fun extractVideoFrame(
        retriever: MediaMetadataRetriever,
        durationSec: Float,
        timeSec: Float,
        maxEdge: Int = 1080,
        exact: Boolean = true
    ): Bitmap? {
        val safeDur = durationSec.coerceAtLeast(0.1f)
        val wrapped = ((timeSec % safeDur) + safeDur) % safeDur
        val clamped = min(max(0f, wrapped), max(0f, safeDur - 0.03f))
        val timeUs = (clamped * 1_000_000f).toLong()
        val option = if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC

        val raw = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, option, maxEdge, maxEdge)
            } else {
                retriever.getFrameAtTime(timeUs, option)
            }
        }.getOrNull() ?: runCatching { retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) }.getOrNull()
            ?: return null

        val argb = if (raw.config != Bitmap.Config.ARGB_8888) {
            raw.copy(Bitmap.Config.ARGB_8888, false).also { raw.recycle() }
        } else {
            raw
        }

        if (max(argb.width, argb.height) > maxEdge) {
            val scale = maxEdge.toFloat() / max(argb.width, argb.height).toFloat()
            val nw = (argb.width * scale).roundToInt().coerceAtLeast(2)
            val nh = (argb.height * scale).roundToInt().coerceAtLeast(2)
            val scaled = Bitmap.createScaledBitmap(argb, nw, nh, true)
            if (scaled !== argb) argb.recycle()
            return scaled
        }
        return argb
    }

    fun bitmapToJpegBase64(bitmap: Bitmap, quality: Int = 82): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun measureFrameStats(source: Bitmap): FramePixelStats {
        val pw = 48
        val ph = 85
        val probe = Bitmap.createScaledBitmap(source, pw, ph, true)
        val pixels = IntArray(pw * ph)
        probe.getPixels(pixels, 0, pw, 0, 0, pw, ph)
        if (probe !== source) probe.recycle()

        var nonBlack = 0
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L

        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (r + g + b > 30) nonBlack++
            sumR += r
            sumG += g
            sumB += b
        }

        val total = pixels.size
        val avgR = (sumR / total).toInt()
        val avgG = (sumG / total).toInt()
        val avgB = (sumB / total).toInt()

        var flatCount = 0
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            if (kotlin.math.abs(r - avgR) + kotlin.math.abs(g - avgG) + kotlin.math.abs(b - avgB) <= 18) {
                flatCount++
            }
        }

        return FramePixelStats(
            nonBlackPct = ((nonBlack * 100f) / total).roundToInt(),
            flatPct = ((flatCount * 100f) / total).roundToInt(),
            avgR = avgR,
            avgG = avgG,
            avgB = avgB
        )
    }
}