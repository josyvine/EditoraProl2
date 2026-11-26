package com.vineyard.editoraPRO.app.domain.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

object BeatDetector {

    /**
     * Decodes audio stream into mono-normalized float PCM samples using MediaExtractor + MediaCodec.
     */
    suspend fun decodeAudioFromFile(file: File, maxDurationSec: Float = 90f): PcmAudioBuffer? =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            try {
                extractor.setDataSource(file.absolutePath)
                var audioTrackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val fmt = extractor.getTrackFormat(i)
                    val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        format = fmt
                        break
                    }
                }
                if (audioTrackIndex < 0 || format == null) return@withContext null

                extractor.selectTrack(audioTrackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: return@withContext null
                var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else 44100
                var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else 1

                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, null, null, 0)
                codec.start()

                val pcmBytes = ByteArrayOutputStream()
                val bufferInfo = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                val maxTimeUs = (maxDurationSec * 1_000_000f).toLong()

                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0 || extractor.sampleTime > maxTimeUs) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            }
                            if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                channelCount = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.get(chunk)
                                pcmBytes.write(chunk)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                    }
                }

                val raw = pcmBytes.toByteArray()
                if (raw.size < 4) return@withContext null
                val shortBuf = java.nio.ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val totalShorts = shortBuf.remaining()
                val floats = FloatArray(totalShorts)
                for (i in 0 until totalShorts) {
                    floats[i] = shortBuf.get(i) / 32768f
                }

                val safeChannels = channelCount.coerceAtLeast(1)
                val frames = floats.size / safeChannels
                val durationSec = frames.toFloat() / sampleRate.coerceAtLeast(8000).toFloat()
                if (durationSec <= 0.05f) return@withContext null

                PcmAudioBuffer(
                    samples = floats,
                    sampleRate = sampleRate,
                    channels = safeChannels,
                    durationSec = durationSec
                )
            } catch (_: Throwable) {
                null
            } finally {
                runCatching { codec?.stop(); codec?.release() }
                runCatching { extractor.release() }
            }
        }

    /**
     * Exact 50ms RMS energy window peak detection matching web detectAudioBeats().
     */
    fun detectAudioBeats(buffer: PcmAudioBuffer): List<Double> {
        val sr = buffer.sampleRate.coerceAtLeast(8000)
        val ch = buffer.channels.coerceAtLeast(1)
        val totalFrames = buffer.samples.size / ch
        if (totalFrames <= 0) return emptyList()

        val winFrames = (sr * 0.05).toInt().coerceAtLeast(1)
        val energies = ArrayList<Double>(totalFrames / winFrames + 1)

        var frameIdx = 0
        while (frameIdx < totalFrames) {
            val end = min(totalFrames, frameIdx + winFrames)
            var sumSq = 0.0
            for (f in frameIdx until end) {
                val sample = buffer.samples[f * ch].toDouble()
                sumSq += sample * sample
            }
            val count = (end - frameIdx).coerceAtLeast(1)
            energies.add(sqrt(sumSq / count))
            frameIdx += winFrames
        }

        val beats = ArrayList<Double>()
        var lastBeat = -0.3
        for (i in 2 until energies.size - 1) {
            val localAvg = (energies[i - 2] + energies[i - 1] + energies[i + 1]) / 3.0
            val t = ((i * 0.05) * 100.0).toInt() / 100.0
            if (energies[i] > localAvg * 1.45 && energies[i] > 0.08 && t - lastBeat >= 0.25) {
                beats.add(t)
                lastBeat = t
                if (beats.size >= 40) break
            }
        }
        return beats
    }

    /**
     * Resamples interleaved float PCM to 48kHz stereo 16-bit PCM bytes for hardware AAC encoding.
     */
    fun resampleTo48kStereoPcm16(buffer: PcmAudioBuffer, targetDurationSec: Float): ShortArray {
        val targetRate = 48000
        val outFrames = ceil(min(buffer.durationSec, targetDurationSec) * targetRate).toInt().coerceAtLeast(0)
        val out = ShortArray(outFrames * 2)
        val inFrames = buffer.samples.size / buffer.channels.coerceAtLeast(1)
        if (inFrames <= 0 || outFrames <= 0) return out

        val ratio = buffer.sampleRate.toDouble() / targetRate.toDouble()
        val ch = buffer.channels.coerceAtLeast(1)

        for (i in 0 until outFrames) {
            val srcPos = i * ratio
            val idx0 = srcPos.toInt().coerceIn(0, inFrames - 1)
            val idx1 = (idx0 + 1).coerceIn(0, inFrames - 1)
            val frac = (srcPos - idx0).toFloat()

            val l0 = buffer.samples[idx0 * ch]
            val l1 = buffer.samples[idx1 * ch]
            val l = (l0 + (l1 - l0) * frac).coerceIn(-1f, 1f)

            val r = if (ch > 1) {
                val r0 = buffer.samples[idx0 * ch + 1]
                val r1 = buffer.samples[idx1 * ch + 1]
                (r0 + (r1 - r0) * frac).coerceIn(-1f, 1f)
            } else l

            out[i * 2] = (l * 32767f).toInt().toShort()
            out[i * 2 + 1] = (r * 32767f).toInt().toShort()
        }
        return out
    }
}
