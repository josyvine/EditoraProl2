package com.vineyard.editoraPRO.app.domain.engine

import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.Stage2PlanResult
import com.vineyard.editoraPRO.app.data.remote.GeminiClient
import com.vineyard.editoraPRO.app.data.remote.GeminiPrompts
import com.vineyard.editoraPRO.app.ui.StagePromptFactory
import kotlin.math.max

data class ParsedExternalScript(
    val plan: Stage2PlanResult,
    val code: String
)

/**
 * Generates self-contained app SDK prompt packs for external Google AI Studio Web sessions
 * and parses imported edit scripts cleanly offline (zero API calls).
 */
object ExternalStudioScriptHelper {

    fun buildAiStudioWebPack(
        userPrompt: String,
        clips: List<ClipAsset>,
        refLoadedInApp: Boolean,
        refName: String,
        refDurationSec: Float,
        refWidth: Int,
        refHeight: Int,
        refBeats: List<Double>,
        refSceneCuts: List<Double>,
        customAudioName: String,
        customAudioDurationSec: Float,
        customAudioBeats: List<Double>
    ): String {
        val clipManifest = if (clips.isEmpty()) {
            "No user media clips pre-loaded in app (if you upload files directly in AI Studio Web, map clipIndex 0..N in exact order)."
        } else {
            clips.mapIndexed { idx, c ->
                val kind = if (c.isImage) "IMAGE" else "VIDEO"
                val dur = if (c.isImage) "still" else "${"%.2f".format(c.durationSec)}s"
                val audioNote = if (c.hasAudio) "hasAudio=true (${"%.2f".format(c.audioDurationSec)}s)" else "hasAudio=false"
                "  clipIndex=$idx | name=\"${c.name}\" | type=$kind | size=${c.width}x${c.height} | dur=$dur | $audioNote"
            }.joinToString("\n")
        }

        val clipSummariesJson = StagePromptFactory.buildClipSummariesJson(clips)

        val refBlock = if (refLoadedInApp) {
            listOfNotNull(
                "REFERENCE VIDEO TELEMETRY (loaded in app for audio/timing reference: \"$refName\", ${refWidth}x${refHeight}, ${"%.2f".format(refDurationSec)}s):",
                if (refSceneCuts.isNotEmpty()) "  Detected visual cuts (sec): $refSceneCuts" else null,
                if (refBeats.isNotEmpty()) "  Detected audio beats (sec): $refBeats" else null
            ).joinToString("\n")
        } else {
            "REFERENCE VIDEO NOTE: If I attached a Reference Video here in Google AI Studio Web, analyze it A-to-Z (exact duration, aspect ratio, every visual cut, beat timing, transitions, blur direction, shaders, color grading, overlays, and whether foreground cutouts are needed). If NO Reference Video is attached, build the edit purely from my User Media and User Prompt."
        }

        val audioBlock = if (customAudioDurationSec > 0.05f) {
            "CUSTOM AUDIO ATTACHED IN APP: \"$customAudioName\" (${"%.2f".format(customAudioDurationSec)}s), beats(sec)=$customAudioBeats"
        } else {
            "AUDIO HANDLING: If I loaded Reference Video or Custom Audio in the app, the app automatically supplies that audio track. Only call `runtime.extractAudio(clips[idx])` + `runtime.setAudioBuffer(...)` in `init(clips)` if `hasAudio === true` on a user clip AND my prompt wants that clip's audio."
        }

        return listOf(
            "You are an expert AI Video Editor, WebGL2/OpenGL ES 3.0 Shader Engineer & Real-Time Canvas2D Director generating a runnable script for my Android app (EditoraPRO / QuickJS Engine).",
            "",
            "USER PROMPT / EDIT INSTRUCTIONS (HIGHEST PRIORITY):",
            "\"${userPrompt.ifBlank { "Analyze the reference video A-to-Z (cuts, beats, transitions, effects, grading, cutouts) and recreate it 1:1 using my aligned user media, or create a dynamic rhythm edit if no reference is attached." }}\"",
            "",
            "ALIGNED USER MEDIA ORDER (${clips.size} item(s), clipIndex 0..${max(0, clips.size - 1)} — preserve exact filename & beginning-to-end alignment):",
            clipManifest,
            "JSON clipSummaries: $clipSummariesJson",
            "",
            refBlock,
            audioBlock,
            "",
            GeminiPrompts.RUNTIME_DOC_STRING,
            "",
            GeminiPrompts.ENGINE_SELECTION_RULES,
            "",
            "CRITICAL ENGINEERING & QUALITY RULES (NOTHING HARDCODED):",
            "1. MATCH FILE ALIGNMENT BY NAME & INDEX: Map `clips[0]`..`clips[N-1]` strictly according to the ALIGNED USER MEDIA ORDER above (beginning -> middle -> end).",
            "2. NO STRETCHED/SQUASHED PANELS: Always draw images/videos using `runtime.drawCover(ctx, frame, x, y, w, h, zoom)` — NEVER raw stretched `ctx.drawImage(...)`!",
            "3. DYNAMIC SEGMENTATION CHOICE: Decide dynamically between `runtime.segmentMediaPipe` vs `runtime.segmentSam` (pass `{}` for zero-click auto-prompt, or `runtime.detectBoxes` + `runtime.segmentSam` for multi-subject scenes) per the Engine Selection Rules & User Prompt above — never hardcode!",
            "4. SHADERS & COMPOSITING ISOLATION: Use `await runtime.runShader(...)` for FBM procedural skies/smoke, motion blur, bloom, flashes, and grading. Always perform `source-in`/`destination-in` tinting on an isolated `runtime.getCanvas(...)`, never directly on main `ctx`.",
            "5. RESOLUTION-INDEPENDENT SCALING & TIMING: Compute every size, coordinate, lineWidth, and font proportionally from `(width, height)`. Parameter `t` is in seconds (`0..duration`); never multiply `t * duration`.",
            "6. SELF-CONTAINED SCRIPT: Include any helper functions directly in the script so it runs standalone in QuickJS without external imports.",
            "",
            "EXACT OUTPUT FORMAT (DO NOT SKIP MARKERS):",
            "Output ONLY the two sections below so the app can import your script directly:",
            "===JSON_RESULT===",
            "{",
            "  \"aspectWidth\": number,",
            "  \"aspectHeight\": number,",
            "  \"totalDurationSec\": number,",
            "  \"editPlan\": [ { \"segmentIndex\": number, \"clipIndex\": number, \"inSec\": number, \"outSec\": number, \"role\": \"string\" } ]",
            "}",
            "===CODE_RESULT===",
            "// Pure executable JavaScript defining async function init(clips) and async function renderFrame(ctx, width, height, t, duration, clips)"
        ).joinToString("\n")
    }

    fun parseExternalScript(rawText: String, geminiClient: GeminiClient): ParsedExternalScript {
        val trimmed = rawText.trim()
        require(trimmed.isNotBlank()) { "Imported script is empty." }

        val parsed = geminiClient.parseJsonAndCodeResult(trimmed, "renderPipelineCode")
        val plan = geminiClient.decodeJson(parsed.rawJson, Stage2PlanResult())

        val extractedCode = parsed.code.ifBlank {
            extractRawJavaScriptFallback(trimmed)
        }
        require(extractedCode.isNotBlank()) {
            "No runnable JavaScript found. Make sure your script includes async function renderFrame(ctx, width, height, t, duration, clips)."
        }

        return ParsedExternalScript(plan = plan, code = extractedCode)
    }

    private fun extractRawJavaScriptFallback(raw: String): String {
        val fenceRegex = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        val fencedBlocks = fenceRegex.findAll(raw).map { it.groupValues[1].trim() }.toList()
        val bestFence = fencedBlocks.firstOrNull {
            it.contains("renderFrame") || it.contains("renderScene")
        } ?: fencedBlocks.lastOrNull()

        if (!bestFence.isNullOrBlank()) return bestFence

        return if (raw.contains("renderFrame") || raw.contains("renderScene")) {
            raw
        } else {
            ""
        }
    }
}