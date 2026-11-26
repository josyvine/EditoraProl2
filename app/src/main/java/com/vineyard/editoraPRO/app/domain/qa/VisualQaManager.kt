package com.vineyard.editoraPRO.app.domain.qa

import com.vineyard.editoraPRO.app.data.model.ApiKeyEntry
import com.vineyard.editoraPRO.app.data.model.BlindVisualAuditResult
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.CodeRewriteJsonResult
import com.vineyard.editoraPRO.app.data.model.EditPlanSegment
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.data.model.ReferenceBlueprint
import com.vineyard.editoraPRO.app.data.model.ReferenceSourceType
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import com.vineyard.editoraPRO.app.data.model.UsageStage
import com.vineyard.editoraPRO.app.data.remote.GeminiClient
import com.vineyard.editoraPRO.app.data.remote.GeminiPrompts
import com.vineyard.editoraPRO.app.domain.engine.QuickJsRunner
import com.vineyard.editoraPRO.app.domain.export.VideoExporter
import com.vineyard.editoraPRO.app.domain.media.InspectionSheetBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.min

data class QaLoopOutcome(
    val changed: Boolean,
    val finalCode: String,
    val finalDurationSec: Float,
    val finalAspectW: Int?,
    val finalAspectH: Int?
)

/**
 * Orchestrates the Two-Step Blind Visual Audit (Step A without code) + Code Rewrite (Step B)
 * self-healing loop, including technical probe validation and best-pipeline rollback.
 */
class VisualQaManager(
    private val geminiClient: GeminiClient,
    private val quickJsRunner: QuickJsRunner,
    private val videoExporter: VideoExporter
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun runVisualQaSelfFix(
        provider: ProviderMode,
        modelName: String,
        thinkingLevel: ThinkingLevel,
        getKeys: () -> Pair<List<ApiKeyEntry>, Int>,
        updateKeys: (List<ApiKeyEntry>, Int) -> Unit,
        canvasW: Int,
        canvasH: Int,
        durationSec: Float,
        maxPasses: Int,
        userFeedbackInput: String,
        extraNotes: String,
        refSourceType: ReferenceSourceType,
        refFile: File?,
        refWidth: Int,
        refHeight: Int,
        refDurationSec: Float,
        refYoutubeUrl: String,
        refCutSheetB64: String?,
        refStoryboardB64: String?,
        refSceneCuts: List<Double>,
        refBeats: List<Double>,
        refAnalysis: ReferenceBlueprint?,
        toolsCode: String,
        initialPipelineCode: String,
        clips: List<ClipAsset>,
        editPlan: List<EditPlanSegment>,
        onCanvasSizeUpdate: (aspectW: Int, aspectH: Int, newDurationSec: Float) -> Pair<Int, Int>,
        onPipelineUpdated: (newCode: String, newDur: Float) -> Unit,
        onStatus: (String, Float?) -> Unit,
        onFeed: (String) -> Unit,
        onLog: (String) -> Unit,
        onToken: (text: String, isThought: Boolean) -> Unit,
        onResetStreamPhase: () -> Unit,
        onUsage: (UsageStage, TokenUsageSnapshot, Boolean) -> Unit = { _, _, _ -> }
    ): QaLoopOutcome {
        var currentW = canvasW
        var currentH = canvasH
        var currentDuration = durationSec
        var activeCode = initialPipelineCode
        var bestScore = 0.0
        var bestCode = initialPipelineCode
        var anyCodeChanged = false
        var feedback = userFeedbackInput

        val hasRef = refAnalysis != null
        val isYt = refSourceType == ReferenceSourceType.YOUTUBE && refYoutubeUrl.isNotBlank()
        val assetParts = InspectionSheetBuilder.buildUserAssetParts(clips)
        val assetDimensionSummary = if (clips.isNotEmpty()) {
            clips.mapIndexed { idx, c ->
                "#$idx ${if (c.isImage) "IMG" else "VID"}: ${c.width}x${c.height} (${if (c.width >= c.height) "landscape" else "portrait"}, ${"%.1f".format(c.durationSec)}s)"
            }.joinToString(" | ")
        } else "None"

        for (pass in 1..maxPasses) {
            var outBoardB64 = ""
            var avgNonBlackPct = 0
            var avgFlatPct = 100
            var sideBySideB64: String? = null
            var outVideoFile: File? = null
            var lastRenderT = 0.05f

            for (qaHealTry in 0 until 3) {
                try {
                    onStatus("Visual QA Pass $pass: rendering 25-frame output...", 0.72f)
                    val outBoard = InspectionSheetBuilder.renderOutputStoryboard(
                        width = currentW,
                        height = currentH,
                        durationSec = currentDuration,
                        renderAtTime = { ts ->
                            lastRenderT = ts
                            quickJsRunner.renderFrame(currentW, currentH, ts, currentDuration)
                        },
                        onFrameProgress = { idx, _ ->
                            onStatus("Visual QA Pass $pass: rendering output frame ${idx + 1}/25...", 0.72f + idx * 0.002f)
                        }
                    )
                    outBoardB64 = outBoard.storyboardB64
                    avgNonBlackPct = outBoard.avgNonBlackPct
                    avgFlatPct = outBoard.avgFlatPct

                    if (hasRef && !isYt && refFile != null) {
                        sideBySideB64 = InspectionSheetBuilder.renderSideBySideComparison(
                            refFile = refFile,
                            refWidth = refWidth,
                            refHeight = refHeight,
                            refDurationSec = refDurationSec,
                            sceneCuts = refSceneCuts,
                            outW = currentW,
                            outH = currentH,
                            durationSec = currentDuration,
                            renderAtTime = { ts ->
                                lastRenderT = ts
                                quickJsRunner.renderFrame(currentW, currentH, ts, currentDuration)
                            },
                            onPairProgress = { idx, total, _ ->
                                onStatus("Visual QA Pass $pass: side-by-side frame ${idx + 1}/$total...", 0.76f)
                            }
                        )
                    }

                    if (avgFlatPct < 96) {
                        try {
                            outVideoFile = videoExporter.exportQaVideoFile(
                                canvasW = currentW,
                                canvasH = currentH,
                                durationSec = currentDuration,
                                renderFrameAt = { ts ->
                                    lastRenderT = ts
                                    quickJsRunner.renderFrame(currentW, currentH, ts, currentDuration)
                                },
                                onStatus = { msg -> onStatus("QA video encode: $msg", 0.77f) }
                            )
                            onLog("Visual QA Pass $pass → Encoded generated output video (${outVideoFile.length() / 1024} KB, nonBlack=$avgNonBlackPct%, flat=$avgFlatPct%) for Gemini inspection.")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (vidErr: Throwable) {
                            val errMsg = vidErr.message.orEmpty()
                            if (Regex("GLSL|ReferenceError|TypeError|SyntaxError|is not defined|Cannot read", RegexOption.IGNORE_CASE).containsMatchIn(errMsg)) {
                                throw vidErr
                            }
                            onLog("QA output video encode skipped ($errMsg); using 25-frame storyboard & side-by-side sheet.")
                        }
                    } else {
                        onLog("Visual QA Pass $pass → Detected flat single-color frames ($avgFlatPct% uniform); skipping redundant QA video encode and forcing fix.")
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (qaErr: Throwable) {
                    onLog("Visual QA Pass $pass render error at t=${"%.2f".format(lastRenderT)}s → Self-healing code: ${qaErr.message}")
                    if (qaHealTry >= 2) throw qaErr

                    val healed = quickJsRunner.compileAndValidateTechnical(
                        initialCode = activeCode,
                        toolsCode = toolsCode,
                        clips = clips,
                        editPlan = editPlan,
                        canvasW = currentW,
                        canvasH = currentH,
                        durationSec = currentDuration,
                        extraTimes = listOf(lastRenderT),
                        initialError = qaErr,
                        onStatus = onStatus,
                        onRequestFix = { attempt, failingCode, errMsg ->
                            requestTechnicalFix(
                                provider = provider,
                                modelName = modelName,
                                thinkingLevel = thinkingLevel,
                                getKeys = getKeys,
                                updateKeys = updateKeys,
                                failingCode = failingCode,
                                errorMsg = errMsg,
                                clipCount = clips.size,
                                attempt = attempt,
                                onStatus = onStatus,
                                onLog = onLog,
                                onToken = onToken,
                                onResetStreamPhase = onResetStreamPhase,
                                onUsage = onUsage
                            )
                        }
                    )
                    activeCode = healed.code
                    anyCodeChanged = true
                    bestCode = activeCode
                    onPipelineUpdated(activeCode, currentDuration)
                }
            }

            val mandatoryMode = feedback.isNotBlank()
            val suspiciousFlatVideo = avgFlatPct >= 92 || (outVideoFile != null && outVideoFile.length() < 6000 && currentDuration >= 2f)
            onLog("Visual QA Pass $pass → Step A: Blind Visual Audit (${if (mandatoryMode) "MANDATORY USER AUTO-FIX" else "1:1 Reference & Multi-Criteria Check"})...")

            suspend fun sendVisualMediaAndStream(promptText: String, includeCodeRef: Boolean): String {
                return geminiClient.runWithApiCycle(
                    provider = provider,
                    getKeys = getKeys,
                    updateKeys = updateKeys,
                    onStatus = { msg -> onStatus(msg, null) },
                    onLog = onLog,
                    onRetryReset = onResetStreamPhase
                ) { apiKey, _ ->
                    val parts = mutableListOf<GeminiPart>()
                    if (hasRef) {
                        if (isYt) {
                            parts.add(GeminiPart.Text("PRIMARY YOUTUBE REFERENCE VIDEO (1:1 style & timing ground truth):"))
                            parts.add(GeminiPart.FileData(fileUri = refYoutubeUrl))
                        } else {
                            if (refFile != null) {
                                val refPart = geminiClient.buildFileMediaPart(
                                    file = refFile,
                                    mimeType = "video/mp4",
                                    provider = provider,
                                    apiKey = apiKey,
                                    onStatus = onStatus,
                                    onFeed = onFeed,
                                    onLog = onLog
                                )
                                parts.add(GeminiPart.Text("PRIMARY REFERENCE VIDEO (1:1 visual, framing, blur, trail, and timing ground truth):"))
                                parts.add(refPart)
                            }
                            if (!refCutSheetB64.isNullOrBlank()) {
                                parts.add(GeminiPart.Text("REFERENCE CUT & BEAT KEYFRAME SHEET (exact reference frames at visual cuts & beats):"))
                                parts.add(GeminiPart.InlineData("image/jpeg", refCutSheetB64))
                            }
                            if (!refStoryboardB64.isNullOrBlank()) {
                                parts.add(GeminiPart.Text("HELPER DIAGNOSTIC 5x5 STORYBOARD OF REFERENCE ONLY (NOT part of the video — reference timestamps only):"))
                                parts.add(GeminiPart.InlineData("image/jpeg", refStoryboardB64))
                            }
                        }
                    }
                    parts.addAll(assetParts)
                    if (!sideBySideB64.isNullOrBlank()) {
                        parts.add(GeminiPart.Text("SIDE-BY-SIDE COMPARISON SHEET (LEFT = REFERENCE VIDEO AT TIMESTAMP t, RIGHT = GENERATED OUTPUT AT EXACT SAME TIMESTAMP t — inspect 1:1 geometry, squashing, effects, blur, and lighting!):"))
                        parts.add(GeminiPart.InlineData("image/jpeg", sideBySideB64!!))
                    }
                    if (outVideoFile != null && !includeCodeRef) {
                        onStatus(
                            if (provider == ProviderMode.AI_STUDIO) "Uploading generated output video for blind AI inspection..."
                            else "Encoding generated output video for blind AI inspection...",
                            0.78f
                        )
                        val outPart = geminiClient.buildFileMediaPart(
                            file = outVideoFile!!,
                            mimeType = "video/mp4",
                            provider = provider,
                            apiKey = apiKey,
                            onStatus = onStatus,
                            onFeed = onFeed,
                            onLog = onLog
                        )
                        parts.add(GeminiPart.Text("GENERATED OUTPUT VIDEO (watch from 0.0s to end carefully):"))
                        parts.add(outPart)
                    }
                    parts.add(GeminiPart.Text("CURRENT RENDERED OUTPUT (25-frame timestamped storyboard):"))
                    parts.add(GeminiPart.InlineData("image/jpeg", outBoardB64))
                    parts.add(GeminiPart.Text(promptText))

                    var attemptUsage: TokenUsageSnapshot? = null
                    try {
                        val out = geminiClient.streamGenerateContent(
                            provider = provider,
                            modelName = modelName,
                            parts = parts,
                            apiKey = apiKey,
                            thinkingLevel = thinkingLevel,
                            onStatus = onStatus,
                            onLog = onLog,
                            onToken = onToken,
                            onUsage = { u -> attemptUsage = u }
                        )
                        attemptUsage?.let { onUsage(UsageStage.VISUAL_QA, it, false) }
                        out
                    } catch (e: Throwable) {
                        attemptUsage?.let { onUsage(UsageStage.VISUAL_QA, it, true) }
                        throw e
                    }
                }
            }

            // STEP A: Blind Visual Audit (NO JS code sent!)
            val blindAuditPrompt = listOfNotNull(
                "You are a strict, blind Visual QA Inspector.",
                "IMPORTANT: You are NOT shown any JavaScript code. Judge ONLY what is actually visible in the attached GENERATED OUTPUT VIDEO, 25-frame storyboard${if (sideBySideB64 != null) ", and SIDE-BY-SIDE [REF t | OUT t] comparison sheet" else ""}.",
                "RENDERED OUTPUT METRICS: Canvas size=${currentW}x${currentH} (${if (currentW >= currentH) "landscape" else "portrait"}), timeline duration=${"%.1f".format(currentDuration)}s, non-black coverage=${avgNonBlackPct}%, flat single-color percentage=${avgFlatPct}%${outVideoFile?.let { ", encoded QA video size=${"%.1f".format(it.length() / 1024.0)} KB" } ?: ""}.",
                "UPLOADED USER MEDIA NATIVE SIZES: $assetDimensionSummary",
                extraNotes.takeIf { it.isNotBlank() }?.let { "USER MAIN PROMPT: \"$it\"" },
                feedback.takeIf { it.isNotBlank() }?.let { "USER REPORTED BUG / AUTO-FIX REQUEST: \"$it\"" },
                "",
                "STRICT MULTI-CRITERIA VISUAL INSPECTION RULES:",
                "1. ASPECT & FRAMING (`aspectAndFraming1to10`): Inspect EVERY single panel in collages, splits, and full-screen cuts! If ANY face or body looks squashed, stretched, squeezed horizontally/vertically, OR if orientation mismatches the user prompt/assets and crops off heads/limbs, set `aspectAndFraming1to10 <= 4` and `passed = false`!",
                "2. LAYOUT & GEOMETRY (`layoutAndGeometry1to10`): Check split panel counts, exact panel proportions `[x,y,w,h]`, borderless seams vs unwanted white border lines, and unwanted floating miniature cutouts pasted over faces!",
                "3. EFFECTS & POLISH (`effectsAndPolish1to10`): Compare intro reveal/slit curtain, directional/vertical motion blur smears, light-trail strands (multiple flowing glowing wisps vs 1 crude thick curve), flares/sparkles, and outro fade against the Reference Video & Side-by-Side sheet!",
                "4. TIMING & CUTS (`timingAndCuts1to10`): Verify whether transitions, beat-synced zoom snaps, and segment durations match the Reference Video cuts and beats.",
                "5. COLOR & MOOD (`colorAndMood1to10`): Verify lighting, contrast, warm/cool cinematic color grade, and transition fades.",
                "6. SUBJECT MASK / CUTOUT COMPLETENESS (when cutouts are actually requested): Check every timestamp from 0.0s to end for missing torso/limbs, flicker, background spills, or blown-out glow clouds.",
                if (suspiciousFlatVideo) "7. CRITICAL ALERT: Measured pixel telemetry indicates the rendered video is mostly a flat single-color screen (or abnormally tiny encoded file). Mark passed=false and all scores <= 2!" else null,
                if (mandatoryMode) "8. MANDATORY USER AUTO-FIX: The user watched this render and reported: \"$feedback\". You MUST set passed=false and list every exact visual difference/defect needed to fulfill this request." else null,
                "",
                "OUTPUT FORMAT:",
                "Section 1: Stream '[LOG] ' lines comparing REF vs OUT literally from 0.0s to end across all 5 visual criteria.",
                "Section 2: On a new line output exact marker: ===JSON_RESULT===",
                "Then output ONLY valid JSON: { \"passed\": boolean, \"score1to10\": number, \"aspectAndFraming1to10\": number, \"layoutAndGeometry1to10\": number, \"effectsAndPolish1to10\": number, \"timingAndCuts1to10\": number, \"colorAndMood1to10\": number, \"visualObservations\": \"string\", \"issuesFound\": [\"string\"] }"
            ).joinToString("\n")

            onResetStreamPhase()
            val blindResp = sendVisualMediaAndStream(blindAuditPrompt, includeCodeRef = false)
            val parsedBlind = geminiClient.parseJsonAndCodeResult(blindResp)
            val blindObj = geminiClient.decodeJson(parsedBlind.rawJson, BlindVisualAuditResult())

            val score = if (suspiciousFlatVideo) min(2.0, if (blindObj.score1to10 > 0) blindObj.score1to10 else 1.0) else blindObj.score1to10
            val aspectScore = if (suspiciousFlatVideo) 1.0 else blindObj.aspectAndFraming1to10
            val layoutScore = if (suspiciousFlatVideo) 1.0 else blindObj.layoutAndGeometry1to10
            val effectsScore = if (suspiciousFlatVideo) 1.0 else blindObj.effectsAndPolish1to10
            val timingScore = if (suspiciousFlatVideo) 1.0 else blindObj.timingAndCuts1to10
            val colorScore = if (suspiciousFlatVideo) 1.0 else blindObj.colorAndMood1to10

            val allSubPass = aspectScore >= 8.0 && layoutScore >= 7.0 && effectsScore >= 7.0 && timingScore >= 7.0 && colorScore >= 7.0
            val passed = !mandatoryMode && !suspiciousFlatVideo && blindObj.passed && score >= 8.0 && allSubPass

            val visualIssues = buildList {
                addAll(blindObj.issuesFound)
                if (aspectScore < 8.0) add("Aspect/framing score $aspectScore/10 < 8 (check panel cover-cropping / squashed faces)")
                if (layoutScore < 7.0) add("Layout/geometry score $layoutScore/10 < 7")
                if (effectsScore < 7.0) add("Effects/polish score $effectsScore/10 < 7")
                if (timingScore < 7.0) add("Timing/cuts score $timingScore/10 < 7")
                if (colorScore < 7.0) add("Color/mood score $colorScore/10 < 7")
                if (suspiciousFlatVideo) add("Rendered output is a flat uniform-color frame (${avgFlatPct}% flat)")
            }

            onLog("Visual QA Pass $pass (Blind Audit) → Score: $score/10 (Aspect:$aspectScore, Layout:$layoutScore, Effects:$effectsScore, Timing:$timingScore, Color:$colorScore), Passed: $passed, Observations: ${blindObj.visualObservations.ifBlank { "N/A" }}, Issues: ${visualIssues.joinToString("; ").ifBlank { "None" }}")

            if (score >= bestScore && !suspiciousFlatVideo) {
                bestScore = score
                bestCode = activeCode
            }
            if (passed) break

            // STEP B: Code Rewrite (Restoring HTML Rules 7 & 8)
            onLog("Visual QA Pass $pass → Step B: Rewriting pipeline code from blind visual diagnosis...")
            onStatus("Visual QA Pass $pass: rewriting code to fix visual issues...", 0.86f)
            onResetStreamPhase()

            val clipSummaries = clips.mapIndexed { idx, c ->
                """{"clipIndex":$idx,"type":"${if (c.isImage) "image" else "video"}","name":${json.encodeToString(c.name)},"durationSec":${"%.2f".format(c.durationSec)},"width":${c.width},"height":${c.height},"hasAudio":${c.hasAudio},"audioDurationSec":${"%.2f".format(c.audioDurationSec)}}"""
            }.joinToString(",", "[", "]")

            val rewritePrompt = listOfNotNull(
                "You are an expert AI Video Director, OpenGL ES 3.0 Shader Engineer & JavaScript Canvas2D Code Fixer.",
                "BLIND VISUAL QA DIAGNOSIS OF CURRENT OUTPUT (${currentW}x${currentH}, ${"%.1f".format(currentDuration)}s):",
                "- Scores: Overall=$score/10 | Aspect=$aspectScore/10 | Layout=$layoutScore/10 | Effects=$effectsScore/10 | Timing=$timingScore/10 | Color=$colorScore/10",
                "- Visual observations: ${blindObj.visualObservations.ifBlank { "Output failed visual check" }}",
                "- Confirmed issues to fix: ${visualIssues.joinToString("; ").ifBlank { feedback }}",
                extraNotes.takeIf { it.isNotBlank() }?.let { "USER MAIN PROMPT (HIGHEST PRIORITY): \"$it\"" },
                feedback.takeIf { it.isNotBlank() }?.let { "MANDATORY USER AUTO-FIX REQUEST (MUST IMPLEMENT): \"$it\"" },
                "",
                "UPLOADED USER MEDIA ITEMS: $clipSummaries",
                refAnalysis?.let { "STAGE 1 REFERENCE BLUEPRINT (DRAFT — OVERRIDE any Stage 1 errors!): ${json.encodeToString(it)}" },
                if (hasRef && refSceneCuts.isNotEmpty()) "DETECTED VISUAL SCENE CUTS (sec): $refSceneCuts" else null,
                if (hasRef && refBeats.isNotEmpty()) "DETECTED AUDIO BEATS (sec): $refBeats" else null,
                if (hasRef && toolsCode.isNotBlank()) "STAGE 1 TOOLS CODE (already passed as 'tools'):\n$toolsCode" else null,
                "",
                GeminiPrompts.ENGINE_SELECTION_RULES,
                "",
                "CRITICAL FIX RULES FOR 1:1 QUALITY:",
                "1. NO SQUASHED/STRETCHED FACES IN PANELS: ALWAYS use `runtime.drawCover(ctx, frame, x, y, w, h, zoom)` for EVERY panel and full-screen draw!",
                "2. REAL GPU SHADERS FOR BLUR / SMEAR / BLOOM / GRADING: Use `runtime.runShader(key, width, height, fragmentGlsl, { u_tex: srcCanvas }, { u_amount: ..., u_time: t })`.",
                "3. MULTI-STRAND LIGHT WISPS: Draw 6-10 overlapping thin organic spline strands with `ctx.globalCompositeOperation = 'lighter'`.",
                "4. ASPECT RATIO & FRAMING: Return updated `aspectWidth` and `aspectHeight` in `===JSON_RESULT===` if needed.",
                "5. COMPOSITING ISOLATION: Execute `source-in`/`destination-in` on an isolated offscreen `runtime.getCanvas('tempMask', width, height)` — NEVER on main `ctx` after drawing background!",
                "6. ENGINE SWITCHING: If MediaPipe clipped accessories or failed on non-humans (cars, pets, anime, instruments, single person in crowd), switch cleanly to `runtime.detectBoxes` + `runtime.segmentSam` / `runtime.getSamMask`!",
                "7. DURATION & AUDIO: Return updated `totalDurationSec` if changed. Only call `runtime.extractAudio(...)` on video clips with `hasAudio=true` (never overwrite reference audio with silent images). Sync cuts and exponential ease-out zoom pulses to `beatsSec`/`sceneCuts`.",
                "8. Do NOT redeclare `const tools = ...`. Declare all variables cleanly inside each `if/else` branch.",
                "",
                "CURRENT PIPELINE CODE TO REWRITE:",
                activeCode,
                "",
                GeminiPrompts.RUNTIME_DOC_STRING,
                "",
                "OUTPUT FORMAT:",
                "Section 1: Stream '[LOG] ' lines explaining every 1:1 discrepancy spotted and how your rewrite fixes it.",
                "Section 2: On a new line output exact marker: ===JSON_RESULT===",
                "Then output ONLY valid JSON: { \"totalDurationSec\": number, \"aspectWidth\": number, \"aspectHeight\": number, \"appliedFixes\": [\"string\"] }.",
                "Section 3: On a new line output exact marker: ===CODE_RESULT===",
                "Then output ONLY the COMPLETE rewritten raw JavaScript (`async function init(clips)` and `async function renderFrame(ctx, width, height, t, duration, clips)`)."
            ).joinToString("\n")

            val rewriteResp = sendVisualMediaAndStream(rewritePrompt, includeCodeRef = true)
            val parsedRw = geminiClient.parseJsonAndCodeResult(rewriteResp, "renderPipelineCode")
            val improvedCode = parsedRw.code

            if (improvedCode.isBlank() || improvedCode.trim() == activeCode.trim()) {
                onLog("Warning: AI did not produce modified code on this pass.")
                break
            }

            val rwJson = geminiClient.decodeJson(parsedRw.rawJson, CodeRewriteJsonResult())
            val nextDur = if (rwJson.totalDurationSec > 0.0) rwJson.totalDurationSec.toFloat() else currentDuration
            val newDims = onCanvasSizeUpdate(rwJson.aspectWidth ?: 0, rwJson.aspectHeight ?: 0, nextDur)
            currentW = newDims.first
            currentH = newDims.second
            currentDuration = nextDur

            onStatus("Applying Visual QA Pass $pass fixes...", 0.94f)
            try {
                val validated = quickJsRunner.compileAndValidateTechnical(
                    initialCode = improvedCode,
                    toolsCode = toolsCode,
                    clips = clips,
                    editPlan = editPlan,
                    canvasW = currentW,
                    canvasH = currentH,
                    durationSec = currentDuration,
                    onStatus = onStatus,
                    onRequestFix = { attempt, failingCode, errMsg ->
                        requestTechnicalFix(
                            provider = provider,
                            modelName = modelName,
                            thinkingLevel = thinkingLevel,
                            getKeys = getKeys,
                            updateKeys = updateKeys,
                            failingCode = failingCode,
                            errorMsg = errMsg,
                            clipCount = clips.size,
                            attempt = attempt,
                            onStatus = onStatus,
                            onLog = onLog,
                            onToken = onToken,
                            onResetStreamPhase = onResetStreamPhase,
                            onUsage = onUsage
                        )
                    }
                )
                activeCode = validated.code
                anyCodeChanged = true
                onPipelineUpdated(activeCode, currentDuration)
                onLog("Visual QA Pass $pass fix compiled and verified!")
                feedback = ""
            } catch (e: CancellationException) {
                throw e
            } catch (fixErr: Throwable) {
                onLog("Visual QA Pass $pass fix failed validation (${fixErr.message}); keeping best pipeline.")
                activeCode = bestCode
                quickJsRunner.compileAndInitPipeline(bestCode, toolsCode, clips, editPlan)
                onPipelineUpdated(activeCode, currentDuration)
                break
            }
        }

        return QaLoopOutcome(
            changed = anyCodeChanged,
            finalCode = activeCode,
            finalDurationSec = currentDuration,
            finalAspectW = currentW,
            finalAspectH = currentH
        )
    }

    suspend fun requestTechnicalFix(
        provider: ProviderMode,
        modelName: String,
        thinkingLevel: ThinkingLevel,
        getKeys: () -> Pair<List<ApiKeyEntry>, Int>,
        updateKeys: (List<ApiKeyEntry>, Int) -> Unit,
        failingCode: String,
        errorMsg: String,
        clipCount: Int,
        attempt: Int,
        onStatus: (String, Float?) -> Unit,
        onLog: (String) -> Unit,
        onToken: (text: String, isThought: Boolean) -> Unit,
        onResetStreamPhase: () -> Unit,
        onUsage: (UsageStage, TokenUsageSnapshot, Boolean) -> Unit = { _, _, _ -> }
    ): String {
        onStatus("AI Auto-fixing code error ($attempt)...", 0.90f)
        onResetStreamPhase()

        val fixPrompt = listOf(
            "Fix this JavaScript renderPipelineCode immediately.",
            "Error message: $errorMsg",
            "",
            GeminiPrompts.RUNTIME_DOC_STRING,
            "",
            "CRITICAL RULES:",
            "- Define `async function init(clips) {}` and `async function renderFrame(ctx, width, height, t, duration, clips) {}`.",
            "- Declare all variables cleanly inside EVERY `if / else if / else` time branch.",
            "- Current user clips count: $clipCount. " + if (clipCount > 0) {
                "Fetch frames via `const frame = await runtime.getFrame(clips[idx % clips.length], t)` and ALWAYS draw panels/frames with `runtime.drawCover(ctx, frame, x, y, w, h, zoom)`."
            } else {
                "No external clips were uploaded — render complete procedural Canvas2D/WebGL2 graphics/animation across t=0..duration."
            },
            "- If tinting/masking with `globalCompositeOperation = 'source-in'` or `'destination-in'`, ALWAYS do it on an isolated offscreen `runtime.getCanvas('maskTemp', width, height)`.",
            "- Compute all coordinates, sizes, line widths, and fonts proportionally from `(width, height)`.",
            "- Use `t` in seconds (`0` to `duration`); never multiply `t * duration`.",
            "- Paint visibly onto `ctx` at all timestamps (avoid all-black or all-white flat frames).",
            "- Do NOT redeclare `const tools = ...`.",
            "",
            "Failing code:",
            failingCode,
            "",
            "Output ===CODE_RESULT=== followed ONLY by the complete fixed raw JavaScript."
        ).joinToString("\n")

        val fixResp = geminiClient.runWithApiCycle(
            provider = provider,
            getKeys = getKeys,
            updateKeys = updateKeys,
            onStatus = { msg -> onStatus(msg, null) },
            onLog = onLog,
            onRetryReset = onResetStreamPhase
        ) { apiKey, _ ->
            var attemptUsage: TokenUsageSnapshot? = null
            try {
                val out = geminiClient.streamGenerateContent(
                    provider = provider,
                    modelName = modelName,
                    parts = listOf(GeminiPart.Text(fixPrompt)),
                    apiKey = apiKey,
                    thinkingLevel = thinkingLevel,
                    onStatus = onStatus,
                    onLog = onLog,
                    onToken = onToken,
                    onUsage = { u -> attemptUsage = u }
                )
                attemptUsage?.let { onUsage(UsageStage.TECHNICAL_FIX, it, false) }
                out
            } catch (e: Throwable) {
                attemptUsage?.let { onUsage(UsageStage.TECHNICAL_FIX, it, true) }
                throw e
            }
        }
        val parsed = geminiClient.parseJsonAndCodeResult(fixResp, "renderPipelineCode")
        return parsed.code.ifBlank { geminiClient.stripCodeFences(fixResp) }
    }
}