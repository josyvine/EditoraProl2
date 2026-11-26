package com.vineyard.editoraPRO.app

import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.data.model.Stage2PlanResult
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.remote.GeminiClient
import com.vineyard.editoraPRO.app.domain.media.BeatDetector
import com.vineyard.editoraPRO.app.security.SecurityGuard
import com.vineyard.editoraPRO.app.ui.StagePromptFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditoraUnitTests {

    @Test
    fun `all 5 ML models exist on disk in src main assets`() {
        val candidates = listOf(File("src/main/assets"), File("app/src/main/assets"))
        val assetsDir = candidates.firstOrNull { it.exists() && it.isDirectory }
        assertTrue("Assets directory should exist", assetsDir != null)
        val required = listOf(
            "deeplab_v3.tflite",
            "selfie_multiclass_256x256.tflite",
            "efficientdet_lite0.tflite",
            "edge_sam_3x_encoder.onnx",
            "edge_sam_3x_decoder.onnx"
        )
        required.forEach { name ->
            val file = File(assetsDir, name)
            assertTrue("Missing ML asset $name at ${file.absolutePath}", file.exists())
            assertTrue("ML asset $name is too small (${file.length()} bytes)", file.length() > 100_000L)
        }
    }

    @Test
    fun `security guard decodes obfuscated endpoints accurately`() {
        assertEquals("lunar-tag", SecurityGuard.getVertexProject())
        assertEquals("global", SecurityGuard.getVertexLocation())
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/lunar-tag/locations/global/publishers/google/models",
            SecurityGuard.getVertexBaseUrl()
        )
        assertEquals(
            "https://generativelanguage.googleapis.com",
            SecurityGuard.getAiStudioBaseUrl()
        )
    }

    @Test
    fun `gemini client parses JSON and CODE sections accurately`() {
        val client = GeminiClient()
        val raw = """
            [LOG] Step 1 analyzing beats
            ===JSON_RESULT===
            {"aspectWidth":9,"aspectHeight":16,"totalDurationSec":12.5,"editPlan":[]}
            ===CODE_RESULT===
            ```javascript
            async function init(clips) {}
            async function renderFrame(ctx, width, height, t, duration, clips) {
              ctx.fillStyle = '#000';
              ctx.fillRect(0, 0, width, height);
            }
            ```
        """.trimIndent()

        val parsed = client.parseJsonAndCodeResult(raw, "renderPipelineCode")
        val plan = client.decodeJson(parsed.rawJson, Stage2PlanResult())
        assertEquals(9, plan.aspectWidth)
        assertEquals(16, plan.aspectHeight)
        assertEquals(12.5, plan.totalDurationSec, 0.01)
        assertTrue(parsed.code.contains("async function renderFrame"))
        assertTrue(!parsed.code.contains("```"))
    }

    @Test
    fun `stage prompt builders retain critical runtime contract`() {
        val stage1 = StagePromptFactory.buildStage1Prompt(
            isYoutube = false,
            youtubeUrl = "",
            refDurationSec = 10f,
            sceneCuts = listOf(1.2, 4.5),
            beatsSec = listOf(0.5, 1.0, 1.5)
        )
        assertTrue(stage1.contains("runtime.runShader"))
        assertTrue(stage1.contains("===JSON_RESULT==="))
        assertTrue(stage1.contains("===CODE_RESULT==="))
        assertTrue(stage1.contains("edgesam"))

        val stage2 = StagePromptFactory.buildStage2NoRefPrompt(
            extraNotes = "Create an anime style piano animation",
            clips = emptyList(),
            customAudioName = "",
            customAudioDurationSec = 0f,
            customAudioBeats = emptyList()
        )
        assertTrue(stage2.contains("Create an anime style piano animation"))
        assertTrue(stage2.contains("renderFrame"))
    }

    @Test
    fun `provider mode and thinking level wire mappings are reversible`() {
        assertEquals(ProviderMode.VERTEX_AI, ProviderMode.fromStorage("vertex"))
        assertEquals(ProviderMode.AI_STUDIO, ProviderMode.fromStorage("aistudio"))
        ThinkingLevel.entries.forEach { level ->
            assertEquals(level, ThinkingLevel.fromWire(level.wireValue))
        }
    }

    @Test
    fun `beat detector skips silent buffers cleanly`() {
        val silent = com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer(
            samples = FloatArray(48000) { 0f },
            sampleRate = 48000,
            channels = 1,
            durationSec = 1.0f
        )
        assertTrue(BeatDetector.detectAudioBeats(silent).isEmpty())
    }
}
