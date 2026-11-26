package com.vineyard.editoraPRO.app.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.domain.media.BeatDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * Real-time synchronized AudioTrack PCM player for Step 3 timeline preview playback.
 */
class AudioPreviewPlayer {
    private var track: AudioTrack? = null
    private var playJob: Job? = null

    fun start(scope: CoroutineScope, buffer: PcmAudioBuffer?, offsetSec: Float, totalDurationSec: Float) {
        stop()
        if (buffer == null || buffer.samples.isEmpty()) return

        val pcmShorts = BeatDetector.resampleTo48kStereoPcm16(buffer, totalDurationSec)
        if (pcmShorts.isEmpty()) return

        val safeOffsetSec = offsetSec.coerceIn(0f, (totalDurationSec - 0.05f).coerceAtLeast(0f))
        val startShortIdx = ((safeOffsetSec * 48_000f).toInt() * 2).coerceIn(0, pcmShorts.size)
        if (startShortIdx >= pcmShorts.size) return

        val minBufBytes = AudioTrack.getMinBufferSize(
            48_000,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(minBufBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = audioTrack
        audioTrack.play()

        playJob = scope.launch(Dispatchers.IO) {
            var pos = startShortIdx
            val chunkSize = 4096
            while (isActive && pos < pcmShorts.size) {
                val len = min(chunkSize, pcmShorts.size - pos)
                val written = runCatching { audioTrack.write(pcmShorts, pos, len) }.getOrDefault(-1)
                if (written <= 0) break
                pos += written
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        track?.let { tr ->
            runCatching {
                tr.pause()
                tr.flush()
                tr.stop()
                tr.release()
            }
        }
        track = null
    }
}
