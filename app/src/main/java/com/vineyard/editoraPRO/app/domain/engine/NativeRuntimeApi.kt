package com.vineyard.editoraPRO.app.domain.engine

import android.graphics.Bitmap
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.DetectedBox
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.domain.vision.MaskResult
import com.vineyard.editoraPRO.app.domain.vision.SamPromptOptions

/**
 * Raw host primitives contract exposed to the QuickJS JavaScript runtime.
 */
interface NativeRuntimeApi {
    fun getCanvas(key: String, width: Int, height: Int): String
    suspend fun seekClip(clipIndex: Int, timeSec: Float)
    suspend fun getFrame(clipIndex: Int, timeSec: Float): String
    fun drawCover(ctxHandle: String, sourceHandleOrClipIndex: Any?, x: Float, y: Float, width: Float, height: Float, zoom: Float = 1f)
    suspend fun runShader(
        key: String,
        outWidth: Int,
        outHeight: Int,
        fragmentGlsl: String,
        texturesMap: Map<String, String>,
        uniformsMap: Map<String, Any?>
    ): String

    suspend fun detectBoxes(clipIndexOrHandle: Any?, timestampSec: Float): List<DetectedBox>
    suspend fun getMediaPipeMask(clipIndexOrHandle: Any?, width: Int, height: Int): MaskResult
    suspend fun segmentMediaPipe(clipIndexOrHandle: Any?, width: Int, height: Int, feather: Float, hardEdge: Boolean, threshold: Float): String
    suspend fun getSamMask(clipIndexOrHandle: Any?, width: Int, height: Int, promptOpts: SamPromptOptions): MaskResult
    suspend fun segmentSam(clipIndexOrHandle: Any?, width: Int, height: Int, promptOpts: SamPromptOptions, feather: Float, hardEdge: Boolean, threshold: Float): String
    fun chromaKey(clipIndexOrHandle: Any?, width: Int, height: Int, targetRgb: List<Int>, tolerance: Float, feather: Float): String
    fun extractAudio(clipIndex: Int): PcmAudioBuffer?
    fun setAudioBuffer(buffer: PcmAudioBuffer?)
}
