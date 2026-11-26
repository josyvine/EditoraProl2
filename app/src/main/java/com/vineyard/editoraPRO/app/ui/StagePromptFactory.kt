package com.vineyard.editoraPRO.app.ui

import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.ReferenceBlueprint
import com.vineyard.editoraPRO.app.data.remote.GeminiPrompts
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Exact 1:1 Stage 1 and Stage 2 prompt builders preserving every prompt contract,
 * 5-model selection rule, and RUNTIME_DOC_STRING from the specification.
 */
object StagePromptFactory {

    private val json = Json { encodeDefaults = false }

    fun buildStage1Prompt(
        isYoutube: Boolean,
        youtubeUrl: String,
        refDurationSec: Float,
        sceneCuts: List<Double>,
        beatsSec: List<Double>
    ): String {
        return listOfNotNull(
            "You are an expert video editing reverse-engineer, compositing analyst & WebGL2/OpenGL ES 3.0 + Canvas2D graphics compiler.",
            if (isYoutube) {
                "Attached: Public YouTube Reference Video ($youtubeUrl). Watch its full video stream AND listen to its audio track carefully to extract exact duration, aspect ratio, every visual cut, beats/pacing, and visual compositing mechanics."
            } else {
                "Attached: (1) The full Reference Video (${"%.2f".format(refDurationSec)}s), (2) An EXACT CUT & BEAT KEYFRAME SHEET showing frames captured right at detected visual cuts and audio beat peaks, and (3) A HELPER 5x5 timestamp index image of that same video (0.0s to ${"%.1f".format(refDurationSec * 0.98f)}s) — strictly an inspection helper, NEVER treat the 5x5 storyboard itself as a grid segment or ending of the video!"
            },
            if (!isYoutube && sceneCuts.isNotEmpty()) {
                "Local MAD scene-cut detector found sharp visual transitions at seconds: $sceneCuts."
            } else null,
            if (!isYoutube && beatsSec.isNotEmpty()) {
                "Local audio pre-scan detected beats at seconds: $beatsSec."
            } else {
                "Listen closely to the audio/music rhythm in the video and report key beat/transition timestamps in meta.beatsSec."
            },
            "",
            GeminiPrompts.RUNTIME_DOC_STRING,
            "",
            GeminiPrompts.ENGINE_SELECTION_RULES,
            "",
            "CRITICAL VISUAL ENGINEERING INSTRUCTIONS (TAKE YOUR TIME — CHRONOLOGICAL 1:1 PRECISION):",
            "1. Do NOT rush or collapse 10-20+ cuts into 4 vague buckets! In your '[LOG] ' lines BEFORE JSON, walk through EVERY SINGLE cut and visual state change chronologically from 0.00s to the end, citing exact timestamps:",
            "   - Exact normalized panel rectangles `[x, y, w, h]` (`0..1`) for any split/collage screens (e.g. 3-panel asymmetric split, stacked 4-panel split, 50/50 vertical split), AND explicitly record whether seams are BORDERLESS (`borderWidth: 0`) or bordered.",
            "   - Exact intro/outro transitions & blur direction (`vertical` motion smear vs `horizontal` vs `radial/zoom` vs slit/curtain wipe, black bar behavior, aspect framing).",
            "   - Exact overlay mechanics: light-trail strand count (e.g. 6-10 flowing curves vs single streak), stroke taper, and blend mode (`lighter`/additive glow), corner sparkles/flares, flashes, beat zoom easing curves, and color grading.",
            "   - Layer stack & cutouts: ONLY report `subjectCutout` if a real foreground cutout overlay ACTUALLY appears in the video (do NOT guess cutouts or 3x3 grids when none exist!). Record `" + "maskEngine" + ": \"mediapipe\" | \"edgesam\"`.",
            "2. Only report grid/split dimensions that ACTUALLY appear in the Reference Video itself (never count the 5x5 or 4-col helper sheets!).",
            "3. Ensure `timelineBlueprint` lists EVERY individual segment/cut chronologically from 0.00s to durationSec with exact `panelRectsNormalized`, `borderless`, `maskOrCutout`, `maskEngine`, and `effectsAndTransitions` details.",
            "",
            "OUTPUT FORMAT (FOLLOW THIS EXACT 3-SECTION ORDER):",
            "Section 1: Stream thorough chronological '[LOG] ' lines documenting EVERY cut/transition from 0.00s to end, exact normalized `[x,y,w,h]` panel layouts, borderless vs bordered, blur direction, light-trail strand count/blend mode, beat zoom easing, flashes, maskEngine selection, and color grading.",
            "Section 2: On a new line output exact marker: ===JSON_RESULT===",
            "Then output ONLY valid JSON (NO code inside this JSON):",
            "{",
            "  \"meta\": {",
            "    \"summary\": \"concise description of the edit style and compositing recipe\",",
            "    \"durationSec\": number,",
            "    \"aspectWidth\": number,",
            "    \"aspectHeight\": number,",
            "    \"gridCols\": number,",
            "    \"gridRows\": number,",
            "    \"persistentOverlay\": \"string\",",
            "    \"beatsSec\": [number],",
            "    \"sceneCutsSec\": [number],",
            "    \"audioMood\": \"string\",",
            "    \"maskEngine\": \"mediapipe | edgesam\"",
            "  },",
            "  \"timelineBlueprint\": [",
            "    {",
            "      \"segmentIndex\": number,",
            "      \"startSec\": number,",
            "      \"endSec\": number,",
            "      \"layout\": \"string\",",
            "      \"panelRectsNormalized\": [[number, number, number, number]],",
            "      \"borderless\": boolean,",
            "      \"requiredClipRole\": \"string\",",
            "      \"effectsAndTransitions\": \"string\",",
            "      \"textOverlays\": [{ \"text\": \"string\", \"startSec\": number, \"endSec\": number, \"style\": \"string\", \"position\": \"string\", \"wordByWord\": boolean }],",
            "      \"maskOrCutout\": \"none | greenScreen | subjectCutout | lumaMask | gridCell | custom\",",
            "      \"maskEngine\": \"mediapipe | edgesam\"",
            "    }",
            "  ]",
            "}",
            "",
            "Section 3: On a new line output exact marker: ===CODE_RESULT===",
            "Then output ONLY raw executable JavaScript defining `const tools = { ... };` with specialized helper functions implementing the EXACT visual effects/compositing mechanics of THIS reference video (using `runtime.runShader(...)` for directional/zoom blurs, bloom, or grading, `runtime.drawCover(...)` for aspect-safe panel framing, `runtime.segmentMediaPipe` / `runtime.detectBoxes` + `runtime.segmentSam` when cutouts are present, additive multi-strand spline helpers if swirling trails exist, etc.). All helper functions in `tools` MUST compute positions, dimensions, line widths, and fonts proportionally from `(width, height)` — NEVER hardcode fixed pixel numbers, keep timing `t` in seconds (never multiply `t * duration`), and any overlay/UI helper function MUST NEVER call `ctx.fillRect(0, 0, w, h)` to wipe the full main canvas! Use ONLY exact `runtime` methods. No external imports."
        ).joinToString("\n")
    }

    fun buildStage2WithRefPrompt(
        extraNotes: String,
        refAnalysis: ReferenceBlueprint,
        sceneCuts: List<Double>,
        beats: List<Double>,
        toolsCode: String,
        clips: List<ClipAsset>,
        customAudioBeats: List<Double>
    ): String {
        val meta = refAnalysis.meta
        val firstAsset = clips.firstOrNull()
        val refAspectStr = "${max(1, meta.aspectWidth)}:${max(1, meta.aspectHeight)}"
        val userAspectStr = firstAsset?.let { "${it.width}x${it.height}" } ?: "none"
        val cutsList = sceneCuts.ifEmpty { meta.sceneCutsSec }
        val beatsList = beats.ifEmpty { meta.beatsSec }
        val clipSummaries = buildClipSummariesJson(clips)

        return listOfNotNull(
            "You are an expert AI Video Editor, WebGL2/OpenGL ES 3.0 Shader Engineer & Real-Time Canvas2D Director.",
            "Generate complete, high-craft JavaScript rendering code combining the Reference Video's exact 1:1 editing mechanics WITH 100% of the User's Prompt instructions and Uploaded Media Assets.",
            "",
            "USER PROMPT / GUIDANCE (HIGHEST PRIORITY — ALWAYS IMPLEMENT EVERY VISUAL REQUEST): \"${extraNotes.ifBlank { "Replicate reference edit style faithfully with user's uploaded media" }}\"",
            "",
            "STAGE 1 REFERENCE BLUEPRINT (DRAFT — verify EVERY cut, layout, border, blur direction, and overlay directly against the attached Reference Video and Cut Sheet, and OVERRIDE Stage 1 wherever Stage 1 missed sub-second cuts or hallucinated grids/cutouts!):",
            json.encodeToString(refAnalysis),
            if (cutsList.isNotEmpty()) "DETECTED VISUAL SCENE CUTS (sec): $cutsList" else null,
            if (beatsList.isNotEmpty()) "DETECTED AUDIO BEATS (sec): $beatsList" else null,
            "",
            "STAGE 1 TOOLS CODE (already passed in as 'tools' parameter — inspect carefully before calling; if Stage 1 tools contain any wrong grid/border/cutout guesses, ignore/override them and implement cleanly! Do NOT redeclare `const tools = ...`, and NEVER call a `tools` helper AFTER drawing media if that helper calls `ctx.fillRect(0, 0, w, h)`!):",
            toolsCode.ifBlank { "/* empty */" },
            "",
            "UPLOADED USER MEDIA ITEMS (${clips.size} items, clipIndex 0..${max(0, clips.size - 1)}):",
            clipSummaries,
            "REFERENCE ASPECT RATIO: $refAspectStr | PRIMARY USER MEDIA NATIVE SIZE: $userAspectStr",
            if (customAudioBeats.isNotEmpty()) "CUSTOM ATTACHED AUDIO BEATS (sec): $customAudioBeats" else null,
            "",
            GeminiPrompts.RUNTIME_DOC_STRING,
            "",
            GeminiPrompts.ENGINE_SELECTION_RULES,
            "",
            "CRITICAL VISUAL & 1:1 CRAFT RULES:",
            "1. PROMPT & REAL-VIDEO OVERRIDE RULE: Verify the attached Reference Video directly and follow its real cuts/effects 1:1, EXCEPT anywhere the User Prompt adds, modifies, or overrides visuals (e.g. 'keep his premises as original', 'original aspect ratio', 'no 3x3 grid', 'no white borders', 'add an animated piano', 'resize photos to fit tile grid'). You MUST draw/animate ALL user-requested visual elements on the canvas in `renderFrame()` — NEVER omit them and NEVER replace a visual request with audio-only SFX!",
            "2. NO STRETCHED/SQUASHED PANELS (MANDATORY): NEVER use raw stretched `ctx.drawImage(frame, 0, 0, frame.width, frame.height, x, y, w, h)` on split/collage panels! ALWAYS draw EVERY panel and fullscreen image/video using `runtime.drawCover(ctx, frame, x, y, w, h, zoom)` so faces and subjects are NEVER squashed!",
            "3. REAL SHADERS FOR MOTION BLUR, SLIT SMEARS, BLOOM & GRADING: Use `runtime.runShader(key, width, height, fragmentGlsl, { u_tex: srcCanvas }, { ... })` for directional/vertical/zoom motion blur, slit reveal smears, highlights/bloom, and cinematic color grading!",
            "4. MULTI-STRAND LIGHT WISPS (6-10 ADDITIVE SPLINES): When creating glowing swirling white light-streak ribbons/wisps, draw 6-10 organic curving spline strands with `ctx.globalCompositeOperation = 'lighter'`, tapering widths/alphas, and soft white glow!",
            "5. BEAT-SYNCED CUTS & EXPONENTIAL EASE-OUT ZOOM SNAPS: Sync scene cuts and zoom pulses directly to `beatsSec` / `sceneCuts` using crisp exponential ease-out snaps (`1 + amp * Math.exp(-decay * dtSinceBeat)`)!",
            "6. DYNAMIC ASPECT RATIO & DURATION: Output `aspectWidth`, `aspectHeight`, and `totalDurationSec` in JSON dynamically! If the prompt asks to keep the user video's original premises/aspect ratio/duration, set `aspectWidth` & `aspectHeight` to primary user clip's native dimensions (${firstAsset?.let { "${it.width}, ${it.height}" } ?: "9, 16"}) and `totalDurationSec` to its duration; otherwise match the reference aspect/duration.",
            "7. 'clips' passed to init(clips) and renderFrame(ctx, width, height, t, duration, clips) contains clip metadata & media descriptors. Always seek/fetch via `await runtime.seekClip(clip, localTime)` or `await runtime.getFrame(clip, localTime)` before drawing or segmenting.",
            "8. COMPOSITING ISOLATION (CRITICAL): If applying `globalCompositeOperation = 'source-in'` or `'destination-in'` to tint a cutout solid white or colored, ALWAYS do it on an isolated offscreen canvas (`runtime.getCanvas('tintTemp', width, height)`) and draw that offscreen canvas onto `ctx` — NEVER call `source-in` + `fillRect` directly on main `ctx` after drawing a background!",
            "9. CUTOUTS, DETECTION & GREEN SCREEN: Follow the Engine Selection Rules above (`runtime.segmentMediaPipe` / `runtime.getMediaPipeMask` for humans/crowds, `runtime.detectBoxes` + `runtime.segmentSam` / `runtime.getSamMask` for non-human objects, props, vehicles, animals, or isolated individuals). Use `runtime.chromaKey(...)` for green-screen despill.",
            "10. AUDIO FROM USER CLIPS: Only call `runtime.extractAudio(clips[idx])` if `hasAudio === true` on that clip AND the user explicitly wants clip audio.",
            "11. EXACT TILE PLATE & GRID SIZING: If one of the user's images is a background/tile grid plate, draw THAT exact tile image as the background, count its EXACT columns and rows, and fill 100% of each tile interior cleanly using `runtime.drawCover(...)`.",
            "12. Do NOT declare `const tools = ...` in your code (Stage 1 `tools` is already in scope). Declare variables cleanly inside each `if/else` branch.",
            "13. TEXT SIZING & SAFETY: Use proportional font size (`Math.round(width * 0.068)`) and pass `width * 0.88` as `maxWidth` in `ctx.fillText(text, x, y, width * 0.88)`.",
            "14. RESOLUTION-INDEPENDENT SCALING & TIMING: NEVER hardcode fixed pixel coordinates — compute EVERY position, dimension, lineWidth, radius, and fontSize proportionally from `(width, height)`. Name the 4th parameter `t` (in seconds from `0` to `duration`), never multiply `t * duration`, and draw directly onto `ctx`.",
            "",
            "OUTPUT FORMAT (FOLLOW THIS EXACT 3-SECTION ORDER):",
            "Section 1: Stream live '[LOG] ' lines explaining chosen aspectWidth/aspectHeight & duration, any Stage 1 errors overridden, which clipIndex maps to each segment, `maskEngine` selection, shader/light-wisp strategy, beat snap timing, and audio selection.",
            "Section 2: On a new line output exact marker: ===JSON_RESULT===",
            "Then output ONLY valid JSON (NO code inside this JSON):",
            "{",
            "  \"aspectWidth\": number,",
            "  \"aspectHeight\": number,",
            "  \"totalDurationSec\": number,",
            "  \"editPlan\": [ { \"segmentIndex\": number, \"clipIndex\": number, \"inSec\": number, \"outSec\": number, \"role\": \"string\" } ]",
            "}",
            "",
            "Section 3: On a new line output exact marker: ===CODE_RESULT===",
            "Then output ONLY raw executable JavaScript defining `async function init(clips)` and `async function renderFrame(ctx, width, height, t, duration, clips)`."
        ).joinToString("\n")
    }

    fun buildStage2NoRefPrompt(
        extraNotes: String,
        clips: List<ClipAsset>,
        customAudioName: String,
        customAudioDurationSec: Float,
        customAudioBeats: List<Double>
    ): String {
        val defaultDur = when {
            customAudioDurationSec > 0.2f -> customAudioDurationSec.roundToInt().coerceIn(5, 30)
            clips.any { !it.isImage } -> clips.first { !it.isImage }.durationSec.roundToInt().coerceIn(6, 20)
            else -> 10
        }
        val firstAsset = clips.firstOrNull()
        val clipSummaries = buildClipSummariesJson(clips)

        return listOfNotNull(
            "You are an elite AI Video Director, WebGL2/OpenGL ES 3.0 Shader Engineer & Procedural Canvas2D Animator.",
            "There is NO reference video — you are creating the entire video edit and/or procedural animation from scratch based strictly on the User's Prompt and any Uploaded Media Assets.",
            "",
            "USER PROMPT: \"${extraNotes.ifBlank { "Create a dynamic, visually polished rhythm edit from the uploaded assets" }}\"",
            "UPLOADED USER MEDIA ITEMS (${clips.size} items, clipIndex 0..${max(0, clips.size - 1)}):",
            clipSummaries,
            if (customAudioDurationSec > 0.05f) {
                "ATTACHED CUSTOM AUDIO: \"$customAudioName\" (${"%.2f".format(customAudioDurationSec)}s). Detected beat peaks (sec): $customAudioBeats"
            } else {
                "AUDIO HANDLING: Check `hasAudio` on uploaded clips above! If the user asks to use a clip's audio, call `const buf = runtime.extractAudio(clips[idx]); if (buf) runtime.setAudioBuffer(buf);` inside `init(clips)`. Only if no clip audio is used AND the prompt implies music/sound, you MAY synthesize an `AudioBuffer` via `OfflineAudioContext` and pass it to `runtime.setAudioBuffer(...)`."
            },
            "",
            GeminiPrompts.RUNTIME_DOC_STRING,
            "",
            GeminiPrompts.ENGINE_SELECTION_RULES,
            "",
            "CAPABILITIES & QUALITY RULES (NOTHING HARDCODED — BUILD EVERYTHING DYNAMICALLY):",
            "1. ASPECT RATIO & DURATION: Choose `aspectWidth`, `aspectHeight`, and `totalDurationSec` in JSON based on the user's prompt & assets (e.g., if user uploaded a clip like ${firstAsset?.let { "${it.width}x${it.height}" } ?: "9:16"}, match its aspect ratio; if user asks for a cinematic anime landscape scene, use 16:9; for reels/shorts use 9:16; suggested duration ~${defaultDur}s unless prompt specifies otherwise).",
            "2. FULL-FRAME VIDEO PREVIEW NOTE: Every video contact sheet attached shows 4 labeled timestamps of ONE full-screen video (NOT a 4-quadrant split video unless raw frames themselves contain visible split borders).",
            "3. NO STRETCHED PANELS: Always draw images/videos inside panels with `runtime.drawCover(ctx, frame, x, y, w, h, zoom)` — NEVER raw stretched `ctx.drawImage(...)`!",
            "4. SHADERS & MULTI-STRAND EFFECTS: Use `runtime.runShader(...)` for blurs, bloom, glows, and color grading; use 6-10 layered additive (`lighter`) curves for light trails/wisps.",
            "5. COMPOSITING ISOLATION: If applying `globalCompositeOperation = 'source-in'` or `'destination-in'`, ALWAYS do it on an isolated offscreen canvas (`runtime.getCanvas(...)`), NEVER directly on main `ctx` after drawing a background!",
            "6. ZERO-CLICK CUTOUTS & DETECTION: Automatically choose MediaPipe (`runtime.segmentMediaPipe` / `runtime.getMediaPipeMask`) for humans/crowds, or EdgeSAM (`runtime.detectBoxes` + `runtime.segmentSam` / `runtime.getSamMask`) for cars, pets, instruments, anime, products, or single person in a crowd! Use `runtime.chromaKey(...)` for green-screen removal.",
            "7. PURE PROMPT / PROCEDURAL ANIMATION: Even with 0 uploaded clips, paint rich, multi-layered Canvas2D/OpenGL shader art in `renderFrame(ctx, width, height, t, duration, clips)`.",
            "8. Always keep code deterministic for time `t` (never rely on `Date.now()` or unseeded `Math.random()` inside `renderFrame`).",
            "9. TEXT SAFETY: Scale font sizes proportionally (`Math.round(width * 0.06)`) and pass `maxWidth` to `ctx.fillText`.",
            "10. RESOLUTION-INDEPENDENT SCALING & TIMING: NEVER hardcode fixed pixel coordinates — compute EVERY position, dimension, lineWidth, radius, and fontSize proportionally from `(width, height)`. Name the 4th parameter `t` (in seconds from 0 to `duration`) and never multiply `t * duration`.",
            "",
            "OUTPUT FORMAT (FOLLOW THIS EXACT 3-SECTION ORDER):",
            "Section 1: Stream live '[LOG] ' lines describing your visual plan, asset roles, `maskEngine` selection, audio source, scene layers, and timing.",
            "Section 2: On a new line output exact marker: ===JSON_RESULT===",
            "Then output ONLY valid JSON (NO code inside this JSON):",
            "{",
            "  \"summary\": \"concise description of generated video\",",
            "  \"aspectWidth\": number,",
            "  \"aspectHeight\": number,",
            "  \"totalDurationSec\": number,",
            "  \"editPlan\": [ { \"segmentIndex\": number, \"clipIndex\": number, \"inSec\": number, \"outSec\": number, \"role\": \"string\" } ]",
            "}",
            "",
            "Section 3: On a new line output exact marker: ===CODE_RESULT===",
            "Then output ONLY raw executable JavaScript defining `async function init(clips)` and `async function renderFrame(ctx, width, height, t, duration, clips)`."
        ).joinToString("\n")
    }

    fun buildClipSummariesJson(clips: List<ClipAsset>): String {
        return clips.mapIndexed { idx, c ->
            """{"clipIndex":$idx,"type":"${if (c.isImage) "image" else "video"}","name":${json.encodeToString(c.name)},"durationSec":${"%.2f".format(c.durationSec)},"width":${c.width},"height":${c.height},"hasAudio":${c.hasAudio},"audioDurationSec":${"%.2f".format(c.audioDurationSec)}}"""
        }.joinToString(",", "[", "]")
    }
}
