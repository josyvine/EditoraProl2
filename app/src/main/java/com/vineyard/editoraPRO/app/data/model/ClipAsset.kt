package com.vineyard.editoraPRO.app.data.model

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

data class PcmAudioBuffer(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val durationSec: Float
)

data class ClipAsset(
    val id: String,
    val uri: Uri,
    val localFile: File,
    val name: String,
    val mimeType: String,
    val isImage: Boolean,
    val durationSec: Float,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val audioBuffer: PcmAudioBuffer?,
    val audioDurationSec: Float,
    val thumbnail: Bitmap?,
    val contactSheetBase64: String
)

enum class ReferenceSourceType {
    NONE,
    FILE,
    YOUTUBE
}
