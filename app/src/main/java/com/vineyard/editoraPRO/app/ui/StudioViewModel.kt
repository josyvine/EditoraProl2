package com.vineyard.editoraPRO.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vineyard.editoraPRO.app.EditoraApp
import com.vineyard.editoraPRO.app.StudioServiceContainer
import com.vineyard.editoraPRO.app.data.model.ApiKeyEntry
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ClipAsset
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import com.vineyard.editoraPRO.app.data.model.MainTab
import com.vineyard.editoraPRO.app.data.model.ModelPricingRule
import com.vineyard.editoraPRO.app.data.model.PcmAudioBuffer
import com.vineyard.editoraPRO.app.data.model.ProcessMonitorState
import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.data.model.ReferenceBlueprint
import com.vineyard.editoraPRO.app.data.model.ReferenceSourceType
import com.vineyard.editoraPRO.app.data.model.SectionUsageRecord
import com.vineyard.editoraPRO.app.data.model.SessionDiagnosticSnapshot
import com.vineyard.editoraPRO.app.data.model.Stage2PlanResult
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import com.vineyard.editoraPRO.app.data.model.UsageStage
import com.vineyard.editoraPRO.app.data.remote.ModelPricingCatalog
import com.vineyard.editoraPRO.app.domain.media.BeatDetector
import com.vineyard.editoraPRO.app.domain.media.FrameExtractor
import com.vineyard.editoraPRO.app.domain.media.InspectionSheetBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class ParsedYoutubeRef(val id: String, val watchUrl: String)

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as EditoraApp
    private val prefs = app.preferencesManager
    private val crashManager = app.crashReportManager
    private val geminiClient = app.geminiClient
    private val prettyJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    private val services = StudioServiceContainer(app) { msg -> appendLog(msg) }
    private val audioPlayer = AudioPreviewPlayer()
    private val drawMutex = Mutex()

    @Volatile
    private var currentBreadcrumbStage: String = "Idle"

    @Volatile
    private var isOperationInProgress: Boolean = false

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<AppState> = _uiState.asStateFlow()

    private var refAudioBuffer: PcmAudioBuffer? = null
    private var customAudioBuffer: PcmAudioBuffer? = null
    private var activeStageJob: Job? = null
    private var playbackJob: Job? = null

    private fun createInitialState(): AppState {
        val provider = prefs.getProviderMode()
        val recoveredCrash = crashManager.loadOrRecoverCrashReport()
        val lastSnapshot = crashManager.getLatestSnapshot()
        val base = AppState(
            apiProvider = provider,
            thinkingLevel = prefs.getThinkingLevel(),
            apiKeys = prefs.getApiKeys(provider),
            cycleIdx = prefs.getCycleIndex(provider),
            models = prefs.getModels(provider),
            selectedModel = prefs.getSelectedModel(provider),
            exportFps = prefs.getExportFps(),
            maxCanvasEdge = prefs.getMaxCanvasEdge(),
            autoVisualQa = (provider == ProviderMode.AI_STUDIO),
            usageRecords = prefs.getLastEditUsageRecords(),
            customPricingOverrides = prefs.getCustomPricingOverrides(),
            estimateCostInFreeTier = prefs.getEstimateCostInFreeTier(),
            lifetimeTotalTokens = prefs.getLifetimeTotalTokens(),
            lifetimeTotalCostUsd = prefs.getLifetimeTotalCostUsd(),
            persistedCrashReport = recoveredCrash
        )
        return if (recoveredCrash != null) {
            base.copy(
                executionLogs = recoveredCrash.executionLogsTail.ifEmpty { lastSnapshot.executionLogs }
                    .ifEmpty { base.executionLogs },
                liveStreamText = recoveredCrash.liveStreamTail.ifBlank { lastSnapshot.liveStreamText }
                    .ifBlank { base.liveStreamText },
                blueprintJsonText = recoveredCrash.blueprintJson.ifBlank { lastSnapshot.blueprintJsonText }
                    .ifBlank { base.blueprintJsonText },
                toolsCodeText = recoveredCrash.toolsCode.ifBlank { lastSnapshot.toolsCodeText }
                    .ifBlank { base.toolsCodeText },
                pipelineCodeText = recoveredCrash.pipelineCode.ifBlank { lastSnapshot.pipelineCodeText }
                    .ifBlank { base.pipelineCodeText },
                toastMessage = "Crash report recovered — open AI Logic to inspect.",
                toastCounter = 1L
            )
        } else {
            base
        }
    }

    private fun syncCrashCheckpoint(
        stage: String = currentBreadcrumbStage,
        inProgress: Boolean = isOperationInProgress,
        forceFlush: Boolean = false
    ) {
        currentBreadcrumbStage = stage
        isOperationInProgress = inProgress
        val st = _uiState.value
        crashManager.updateSessionSnapshot(
            snapshot = SessionDiagnosticSnapshot(
                activeStage = stage,
                inProgress = inProgress,
                providerAndModel = "${st.apiProvider.shortLabel}: ${st.selectedModel.ifBlank { "None" }}",
                executionLogs = st.executionLogs,
                liveStreamText = st.liveStreamText,
                blueprintJsonText = st.blueprintJsonText,
                toolsCodeText = st.toolsCodeText,
                pipelineCodeText = st.pipelineCodeText
            ),
            forceFlush = forceFlush
        )
    }

    fun clearCrashReport() {
        crashManager.clearCrashReport()
        _uiState.update { it.copy(persistedCrashReport = null) }
        showToast("Crash report cleared")
    }

    // ---- Navigation & Toast ----

    fun setActiveTab(tab: MainTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setWizardStep(step: Int) {
        val target = step.coerceIn(1, 3)
        _uiState.update { st ->
            val nextSubStep = when {
                target == 2 && st.wizardStep < 2 -> 1
                target == 2 && st.wizardStep > 2 -> 2
                else -> st.step2SubStep
            }
            st.copy(wizardStep = target, step2SubStep = nextSubStep)
        }
    }

    fun setStep2SubStep(subStep: Int) {
        _uiState.update { it.copy(step2SubStep = subStep.coerceIn(1, 2)) }
    }

    fun setSettingsSubPage(page: Int) {
        _uiState.update { it.copy(settingsSubPage = page.coerceIn(1, 3)) }
    }

    fun handleBackPress(): Boolean {
        val st = _uiState.value
        if (st.activeTab == MainTab.SETTINGS && st.settingsSubPage > 1) {
            setSettingsSubPage(st.settingsSubPage - 1)
            return true
        }
        if (st.activeTab != MainTab.STUDIO) {
            setActiveTab(MainTab.STUDIO)
            return true
        }
        if (st.wizardStep == 2 && st.step2SubStep > 1) {
            setStep2SubStep(st.step2SubStep - 1)
            return true
        }
        if (st.wizardStep > 1) {
            setWizardStep(st.wizardStep - 1)
            return true
        }
        return false
    }

    fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    fun showToast(msg: String) {
        _uiState.update { it.copy(toastMessage = msg, toastCounter = it.toastCounter + 1L) }
    }

    fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $msg"
        _uiState.update { st ->
            val base = if (st.executionLogs.size == 1 && st.executionLogs[0].startsWith("Ready.")) emptyList() else st.executionLogs
            st.copy(executionLogs = (base + line).takeLast(400))
        }
        syncCrashCheckpoint(forceFlush = false)
    }

    fun clearExecutionLog() {
        _uiState.update { it.copy(executionLogs = listOf("Log cleared.")) }
        syncCrashCheckpoint(forceFlush = true)
    }

    // ---- Settings & Provider Management ----

    fun switchProvider(mode: ProviderMode) {
        if (mode == _uiState.value.apiProvider) return
        prefs.setProviderMode(mode)
        val keys = prefs.getApiKeys(mode)
        val cycle = prefs.getCycleIndex(mode)
        val models = prefs.getModels(mode)
        val selModel = prefs.getSelectedModel(mode)
        val qaDefault = (mode == ProviderMode.AI_STUDIO)

        _uiState.update {
            it.copy(
                apiProvider = mode,
                apiKeys = keys,
                cycleIdx = cycle,
                models = models,
                selectedModel = selModel,
                autoVisualQa = qaDefault
            )
        }
        appendLog(
            "Switched API provider → ${if (mode == ProviderMode.AI_STUDIO) "AI Studio (v1beta)" else "Vertex AI (lunar-tag/global)"} " +
                "(${keys.size} key(s), ${models.size} cached model(s), Visual QA default: ${if (qaDefault) "ON" else "OFF"})."
        )
        showToast("Switched to ${if (mode == ProviderMode.AI_STUDIO) "AI Studio (v1beta)" else "Vertex AI"}")
    }

    fun addApiKey(rawKey: String) {
        val clean = rawKey.trim()
        if (clean.length < 10) {
            showToast("Enter a valid Gemini API key.")
            return
        }
        val st = _uiState.value
        if (st.apiKeys.any { it.key == clean }) {
            showToast("Key already added.")
            return
        }
        val updated = st.apiKeys + ApiKeyEntry(key = clean)
        prefs.saveApiKeys(st.apiProvider, updated)
        _uiState.update { it.copy(apiKeys = updated) }
        appendLog("Added API key #${updated.size} for ${st.apiProvider.shortLabel}")
        showToast("API key added (${updated.size} total)")
    }

    fun removeApiKey(index: Int) {
        val st = _uiState.value
        if (index !in st.apiKeys.indices) return
        val updated = st.apiKeys.toMutableList().apply { removeAt(index) }
        val nextCycle = if (updated.isEmpty()) 0 else st.cycleIdx % updated.size
        prefs.saveApiKeys(st.apiProvider, updated)
        prefs.saveCycleIndex(st.apiProvider, nextCycle)
        _uiState.update { it.copy(apiKeys = updated, cycleIdx = nextCycle) }
        showToast("API key removed")
    }

    fun enableApiKey(index: Int) {
        val st = _uiState.value
        if (index !in st.apiKeys.indices) return
        val updated = st.apiKeys.toMutableList().apply {
            this[index] = this[index].copy(disabledReason = null)
        }
        prefs.saveApiKeys(st.apiProvider, updated)
        _uiState.update { it.copy(apiKeys = updated) }
        appendLog("Re-enabled API key #${index + 1} (${updated[index].masked()}).")
        showToast("API key #${index + 1} re-enabled")
    }

    fun fetchLiveModels() {
        val st = _uiState.value
        if (st.apiKeys.isEmpty()) {
            showToast("Add an API key first.")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isFetchingModels = true) }
            try {
                val fetched = geminiClient.runWithApiCycle(
                    provider = st.apiProvider,
                    getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                    updateKeys = { k, c ->
                        prefs.saveApiKeys(_uiState.value.apiProvider, k)
                        prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                        _uiState.update { cur -> cur.copy(apiKeys = k, cycleIdx = c) }
                    },
                    onStatus = { },
                    onLog = { appendLog(it) }
                ) { apiKey, _ ->
                    geminiClient.fetchLiveModels(st.apiProvider, apiKey) { appendLog(it) }
                }

                val curSelected = _uiState.value.selectedModel
                val resolvedSelected = if (fetched.any { it.name == curSelected }) {
                    curSelected
                } else {
                    fetched.firstOrNull()?.name.orEmpty()
                }
                prefs.saveModels(_uiState.value.apiProvider, fetched)
                prefs.saveSelectedModel(_uiState.value.apiProvider, resolvedSelected)
                _uiState.update {
                    it.copy(models = fetched, selectedModel = resolvedSelected, isFetchingModels = false)
                }
                appendLog("Fetched ${fetched.size} live models. Active model: $resolvedSelected")
                showToast("Loaded ${fetched.size} live models!")
            } catch (err: Throwable) {
                _uiState.update { it.copy(isFetchingModels = false) }
                appendLog("Model fetch failed: ${err.message}")
                showToast("Fetch failed: ${err.message}")
            }
        }
    }

    fun selectModel(modelName: String) {
        val mode = _uiState.value.apiProvider
        prefs.saveSelectedModel(mode, modelName)
        _uiState.update { it.copy(selectedModel = modelName) }
        appendLog("Selected model: $modelName")
    }

    fun setThinkingLevel(level: ThinkingLevel) {
        prefs.setThinkingLevel(level)
        _uiState.update { it.copy(thinkingLevel = level) }
        appendLog("Thinking level updated → ${level.label}")
    }

    fun setExportFps(fps: Int) {
        val safe = if (fps == 24) 24 else 30
        prefs.setExportFps(safe)
        _uiState.update { it.copy(exportFps = safe) }
    }

    fun setMaxCanvasEdge(edge: Int) {
        val safe = if (edge == 720) 720 else 1080
        prefs.setMaxCanvasEdge(safe)
        _uiState.update { it.copy(maxCanvasEdge = safe) }
    }

    // ---- Token Usage & Pricing Management ----

    fun setEstimateCostInFreeTier(enabled: Boolean) {
        prefs.setEstimateCostInFreeTier(enabled)
        _uiState.update { cur ->
            val recalculated = recalculateRecordsCost(cur.usageRecords, cur.customPricingOverrides, enabled)
            prefs.saveLastEditUsageRecords(recalculated)
            cur.copy(estimateCostInFreeTier = enabled, usageRecords = recalculated)
        }
        showToast(if (enabled) "Showing estimated USD cost" else "Free-tier mode: USD cost set to $0")
    }

    fun saveModelPricingOverride(
        modelId: String,
        inputPer1M: Double,
        outputPer1M: Double,
        inputOver200kPer1M: Double? = null,
        outputOver200kPer1M: Double? = null
    ) {
        val norm = ModelPricingCatalog.normalizeModelId(modelId)
        if (norm.isBlank()) return
        val (baseRule, _) = ModelPricingCatalog.resolveRule(norm)
        val updatedRule = baseRule.copy(
            modelId = norm,
            inputPer1M = inputPer1M.coerceAtLeast(0.0),
            outputPer1M = outputPer1M.coerceAtLeast(0.0),
            inputOver200kPer1M = inputOver200kPer1M?.coerceAtLeast(0.0),
            outputOver200kPer1M = outputOver200kPer1M?.coerceAtLeast(0.0),
            isCustomOverride = true
        )
        _uiState.update { cur ->
            val nextOverrides = cur.customPricingOverrides.toMutableMap().apply { put(norm, updatedRule) }
            prefs.saveCustomPricingOverrides(nextOverrides)
            val recalculated = recalculateRecordsCost(cur.usageRecords, nextOverrides, cur.estimateCostInFreeTier)
            prefs.saveLastEditUsageRecords(recalculated)
            cur.copy(customPricingOverrides = nextOverrides, usageRecords = recalculated)
        }
        appendLog("Saved custom pricing for $norm → in=${ModelPricingCatalog.formatUsd(updatedRule.inputPer1M)}/1M, out=${ModelPricingCatalog.formatUsd(updatedRule.outputPer1M)}/1M")
        showToast("Updated pricing for $norm")
    }

    fun resetModelPricingOverride(modelId: String) {
        val norm = ModelPricingCatalog.normalizeModelId(modelId)
        if (norm.isBlank()) return
        _uiState.update { cur ->
            val nextOverrides = cur.customPricingOverrides.toMutableMap().apply { remove(norm) }
            prefs.saveCustomPricingOverrides(nextOverrides)
            val recalculated = recalculateRecordsCost(cur.usageRecords, nextOverrides, cur.estimateCostInFreeTier)
            prefs.saveLastEditUsageRecords(recalculated)
            cur.copy(customPricingOverrides = nextOverrides, usageRecords = recalculated)
        }
        showToast("Reset pricing for $norm to default")
    }

    fun clearCurrentEditUsage() {
        prefs.saveLastEditUsageRecords(emptyList())
        _uiState.update { it.copy(usageRecords = emptyList()) }
        showToast("Current edit token ledger cleared")
    }

    fun resetLifetimeUsage() {
        prefs.saveLifetimeTotals(0L, 0.0)
        _uiState.update { it.copy(lifetimeTotalTokens = 0L, lifetimeTotalCostUsd = 0.0) }
        showToast("Lifetime usage totals reset")
    }

    private fun recalculateRecordsCost(
        records: List<SectionUsageRecord>,
        overrides: Map<String, ModelPricingRule>,
        estimateCost: Boolean
    ): List<SectionUsageRecord> {
        return records.map { rec ->
            val snap = TokenUsageSnapshot(
                promptTokens = rec.promptTokens,
                candidatesTokens = rec.candidatesTokens,
                thoughtsTokens = rec.thoughtsTokens,
                cachedTokens = rec.cachedTokens,
                totalTokens = rec.totalTokens
            )
            val calc = ModelPricingCatalog.calculateCost(rec.modelName, snap, overrides, estimateCost)
            rec.copy(
                inputCostUsd = calc.inputCostUsd,
                outputCostUsd = calc.outputCostUsd,
                totalCostUsd = calc.totalCostUsd
            )
        }
    }

    private fun recordTokenUsage(
        stage: UsageStage,
        usage: TokenUsageSnapshot,
        isPartialFailure: Boolean = false
    ) {
        if (!usage.hasData) return
        val st = _uiState.value
        val modelName = st.selectedModel.ifBlank { "unknown" }
        val calc = ModelPricingCatalog.calculateCost(
            modelName = modelName,
            usage = usage,
            customOverrides = st.customPricingOverrides,
            estimateCost = st.estimateCostInFreeTier
        )
        val rec = SectionUsageRecord(
            id = UUID.randomUUID().toString(),
            stage = stage,
            modelName = ModelPricingCatalog.normalizeModelId(modelName),
            providerLabel = st.apiProvider.shortLabel,
            promptTokens = usage.promptTokens,
            candidatesTokens = usage.candidatesTokens,
            thoughtsTokens = usage.thoughtsTokens,
            cachedTokens = usage.cachedTokens,
            totalTokens = usage.effectiveTotalTokens,
            inputCostUsd = calc.inputCostUsd,
            outputCostUsd = calc.outputCostUsd,
            totalCostUsd = calc.totalCostUsd,
            isPartialFailure = isPartialFailure
        )
        _uiState.update { cur ->
            val nextRecords = cur.usageRecords + rec
            val nextLifetimeTokens = cur.lifetimeTotalTokens + rec.totalTokens
            val nextLifetimeCost = cur.lifetimeTotalCostUsd + rec.totalCostUsd
            prefs.saveLastEditUsageRecords(nextRecords)
            prefs.saveLifetimeTotals(nextLifetimeTokens, nextLifetimeCost)
            cur.copy(
                usageRecords = nextRecords,
                lifetimeTotalTokens = nextLifetimeTokens,
                lifetimeTotalCostUsd = nextLifetimeCost
            )
        }
        appendLog(
            "Cost [${stage.label}${if (isPartialFailure) " partial" else ""}] → " +
                "${ModelPricingCatalog.formatTokens(rec.totalTokens)} tok " +
                "(in=${rec.promptTokens}, out=${rec.candidatesTokens}, think=${rec.thoughtsTokens}) • " +
                ModelPricingCatalog.formatUsd(rec.totalCostUsd)
        )
    }

    // ---- Step 1: Reference Input & Analysis ----

    fun loadReferenceVideo(uri: Uri) {
        viewModelScope.launch {
            clearReference(silent = true)
            try {
                val (file, nameMime) = FrameExtractor.copyUriToCache(app, uri, "ref")
                val meta = FrameExtractor.probeVideoMetadata(file)
                _uiState.update {
                    it.copy(
                        refSourceType = ReferenceSourceType.FILE,
                        refFile = file,
                        refUri = Uri.fromFile(file),
                        refName = nameMime.first,
                        refDurationSec = meta.durationSec,
                        refWidth = meta.width,
                        refHeight = meta.height
                    )
                }
                appendLog("Loaded reference video: ${nameMime.first} (${"%.2f".format(meta.durationSec)}s, ${meta.width}x${meta.height})")

                val pcm = BeatDetector.decodeAudioFromFile(file)
                if (pcm != null) {
                    refAudioBuffer = pcm
                    val beats = BeatDetector.detectAudioBeats(pcm)
                    _uiState.update { it.copy(refBeats = beats) }
                    appendLog("Decoded audio (${"%.2f".format(pcm.durationSec)}s, ${pcm.sampleRate}Hz). Detected ${beats.size} audio beat peaks.")
                } else {
                    appendLog("Reference audio decode skipped or silent track.")
                }
            } catch (err: Throwable) {
                appendLog("Reference load error: ${err.message}")
                showToast("Failed to load reference: ${err.message}")
            }
        }
    }

    fun loadYoutubeReference(rawUrl: String) {
        val parsed = parseYoutubeUrl(rawUrl)
        if (parsed == null) {
            showToast("Enter a valid YouTube or Shorts URL.")
            return
        }
        clearReference(silent = true)
        _uiState.update {
            it.copy(
                refSourceType = ReferenceSourceType.YOUTUBE,
                refYoutubeUrl = parsed.watchUrl,
                refYoutubeId = parsed.id,
                refName = parsed.watchUrl
            )
        }
        appendLog("Loaded YouTube Reference URL: ${parsed.watchUrl} (ready for native Gemini video+audio analysis).")
        showToast("YouTube reference loaded! Tap Analyze Reference.")
    }

    fun clearReference(silent: Boolean = false) {
        refAudioBuffer = null
        _uiState.update {
            it.copy(
                refSourceType = ReferenceSourceType.NONE,
                refFile = null,
                refUri = null,
                refName = "",
                refYoutubeUrl = "",
                refYoutubeId = "",
                refDurationSec = 0f,
                refWidth = 0,
                refHeight = 0,
                refBeats = emptyList(),
                refSceneCuts = emptyList(),
                refCutSheetB64 = null,
                refStoryboardB64 = null,
                refAnalysis = null,
                toolsCode = "",
                step1Done = false,
                stage1Monitor = ProcessMonitorState(),
                blueprintJsonText = "No reference or prompt plan generated yet.",
                toolsCodeText = "AI will synthesize custom JS tools here after analyzing a reference video (optional in prompt-only mode)."
            )
        }
        if (!silent) {
            appendLog("Reference cleared → Ready for new reference or Prompt/Media mode.")
            showToast("Reference cleared")
        }
    }

    fun analyzeReference() {
        val st = _uiState.value
        if (st.refSourceType == ReferenceSourceType.NONE) {
            showToast("Upload a reference video or load a YouTube URL first.")
            return
        }
        if (st.apiKeys.isEmpty() || st.selectedModel.isBlank()) {
            showToast("Add API key and fetch live model in Settings.")
            return
        }

        prefs.saveLastEditUsageRecords(emptyList())
        _uiState.update { it.copy(usageRecords = emptyList()) }

        activeStageJob?.cancel()
        val tracker = TelemetryStreamTracker(
            onMonitorUpdate = { m -> _uiState.update { it.copy(stage1Monitor = m) } },
            onLiveStreamUpdate = { txt, status -> _uiState.update { it.copy(liveStreamText = txt, streamMonitorStatus = status) } },
            onLog = { appendLog(it) }
        )

        activeStageJob = viewModelScope.launch {
            tracker.start(this, "Preparing reference analysis...")
            syncCrashCheckpoint("Stage 1: Reference Analysis", inProgress = true, forceFlush = true)
            try {
                val cur = _uiState.value
                val isYt = cur.refSourceType == ReferenceSourceType.YOUTUBE
                var storyboard = cur.refStoryboardB64
                var cutSheet = cur.refCutSheetB64
                var cuts = cur.refSceneCuts

                if (!isYt && (storyboard == null || cutSheet == null) && cur.refFile != null) {
                    val insp = InspectionSheetBuilder.extractReferenceInspectionData(
                        videoFile = cur.refFile,
                        durationSec = cur.refDurationSec,
                        width = cur.refWidth,
                        height = cur.refHeight,
                        beatsSec = cur.refBeats,
                        onProgress = { msg, p, feed ->
                            tracker.setStatus(msg, p)
                            tracker.setFeedMessage(feed)
                        }
                    )
                    storyboard = insp.storyboardB64
                    cutSheet = insp.cutSheetB64
                    cuts = insp.sceneCuts
                    _uiState.update {
                        it.copy(refStoryboardB64 = storyboard, refCutSheetB64 = cutSheet, refSceneCuts = cuts)
                    }
                    appendLog("Detected ${cuts.size} visual scene cuts: $cuts")
                    appendLog("Extracted Cut/Beat Keyframe Sheet (4-col) + 25-frame helper storyboard (5x5).")
                }

                val promptText = StagePromptFactory.buildStage1Prompt(
                    isYoutube = isYt,
                    youtubeUrl = cur.refYoutubeUrl,
                    refDurationSec = cur.refDurationSec,
                    sceneCuts = cuts,
                    beatsSec = cur.refBeats
                )

                val resultText = geminiClient.runWithApiCycle(
                    provider = cur.apiProvider,
                    getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                    updateKeys = { k, c ->
                        prefs.saveApiKeys(_uiState.value.apiProvider, k)
                        prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                        _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                    },
                    onStatus = { msg -> tracker.setStatus(msg, null) },
                    onLog = { appendLog(it) },
                    onRetryReset = { tracker.resetStreamPhase() }
                ) { apiKey, _ ->
                    val parts = mutableListOf<GeminiPart>()
                    parts.add(GeminiPart.Text("PRIMARY REFERENCE VIDEO (the actual edit to reverse-engineer):"))
                    if (isYt) {
                        parts.add(GeminiPart.FileData(fileUri = cur.refYoutubeUrl))
                    } else if (cur.refFile != null) {
                        parts.add(
                            geminiClient.buildFileMediaPart(
                                file = cur.refFile,
                                mimeType = "video/mp4",
                                provider = cur.apiProvider,
                                apiKey = apiKey,
                                onStatus = { s, p -> tracker.setStatus(s, p) },
                                onFeed = { f -> tracker.setFeedMessage(f) },
                                onLog = { appendLog(it) }
                            )
                        )
                    }
                    if (!isYt && cutSheet != null) {
                        parts.add(GeminiPart.Text("EXACT CUT & BEAT KEYFRAME SHEET (frames captured directly at detected scene cuts & beats — use this to verify every sub-second cut and transition!):"))
                        parts.add(GeminiPart.InlineData("image/jpeg", cutSheet))
                    }
                    if (!isYt && storyboard != null) {
                        parts.add(GeminiPart.Text("HELPER DIAGNOSTIC 5x5 TIMESTAMP STORYBOARD ONLY (NOT part of the video — 25 evenly-spaced frame thumbnails of the same video above; NEVER include this 5x5 grid in timelineBlueprint or tools!):"))
                        parts.add(GeminiPart.InlineData("image/jpeg", storyboard))
                    }
                    parts.add(GeminiPart.Text(promptText))

                    var attemptUsage: TokenUsageSnapshot? = null
                    try {
                        val out = geminiClient.streamGenerateContent(
                            provider = cur.apiProvider,
                            modelName = cur.selectedModel,
                            parts = parts,
                            apiKey = apiKey,
                            thinkingLevel = cur.thinkingLevel,
                            onStatus = { s, p -> tracker.setStatus(s, p) },
                            onLog = { appendLog(it) },
                            onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                            onUsage = { u -> attemptUsage = u }
                        )
                        attemptUsage?.let { recordTokenUsage(UsageStage.REFERENCE_ANALYSIS, it, isPartialFailure = false) }
                        out
                    } catch (e: Throwable) {
                        attemptUsage?.let { recordTokenUsage(UsageStage.REFERENCE_ANALYSIS, it, isPartialFailure = true) }
                        throw e
                    }
                }

                tracker.setStatus("Parsing blueprint & compiling AI tools...", 0.95f)
                syncCrashCheckpoint("Stage 1: Compiling AI Tools", inProgress = true, forceFlush = true)
                val parsed = geminiClient.parseJsonAndCodeResult(resultText, "toolsCode")
                val blueprint = geminiClient.decodeJson(parsed.rawJson, ReferenceBlueprint())
                val toolsJs = parsed.code.ifBlank { "const tools = {};" }
                val toolNames = services.quickJsRunner.compileTools(toolsJs)

                _uiState.update { stateNow ->
                    stateNow.copy(
                        refAnalysis = blueprint,
                        toolsCode = toolsJs,
                        toolsCodeText = toolsJs,
                        blueprintJsonText = prettyJson.encodeToString(blueprint),
                        refDurationSec = if (isYt && blueprint.meta.durationSec > 0) blueprint.meta.durationSec.toFloat() else stateNow.refDurationSec,
                        refBeats = if (isYt && blueprint.meta.beatsSec.isNotEmpty()) blueprint.meta.beatsSec else stateNow.refBeats,
                        refSceneCuts = if (isYt && blueprint.meta.sceneCutsSec.isNotEmpty()) blueprint.meta.sceneCutsSec else stateNow.refSceneCuts,
                        step1Done = true
                    )
                }
                tracker.finish(
                    "Done! ${blueprint.timelineBlueprint.size} segments & ${toolNames.size} tools synthesized.",
                    success = true
                )
                appendLog("Stage 1 complete → ${blueprint.timelineBlueprint.size} segments parsed. Compiled tools: $toolNames")
                showToast("Reference analyzed! Tap Next to upload media or prompt.")
            } catch (e: CancellationException) {
                tracker.finish("Cancelled by user.", success = false)
                appendLog("Stage 1 cancelled by user.")
            } catch (err: Throwable) {
                val msg = err.message ?: "Stage 1 failed"
                val isModelIssue = Regex("503|high demand|overloaded|unavailable|400|404|did not respond", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                tracker.finish(msg, success = false, showSwitchModel = isModelIssue)
                appendLog("Stage 1 error: $msg")
                showToast("Analysis failed: $msg")
            } finally {
                syncCrashCheckpoint("Idle", inProgress = false, forceFlush = true)
            }
        }
    }

    fun cancelActiveProcess() {
        activeStageJob?.cancel()
        activeStageJob = null
        syncCrashCheckpoint("Idle", inProgress = false, forceFlush = true)
    }

    // ---- Step 2: User Media, Audio & Prompts ----

    fun addUserClips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            showToast("Loading ${uris.size} file(s)...")
            val newClips = mutableListOf<ClipAsset>()
            for (uri in uris) {
                try {
                    val (file, nameMime) = FrameExtractor.copyUriToCache(app, uri, "clip")
                    val isImg = FrameExtractor.isImageAsset(nameMime.first, nameMime.second)
                    if (isImg) {
                        val bmp = FrameExtractor.decodeImageBitmap(file, 1080)
                        val sheetBmp = Bitmap.createScaledBitmap(bmp, 512, max(120, ((512f * bmp.height) / bmp.width).roundToInt()), true)
                        val b64 = FrameExtractor.bitmapToJpegBase64(sheetBmp, 84)
                        val thumb = Bitmap.createScaledBitmap(bmp, 220, max(100, ((220f * bmp.height) / bmp.width).roundToInt()), true)
                        val w = bmp.width
                        val h = bmp.height
                        if (sheetBmp !== bmp) sheetBmp.recycle()
                        bmp.recycle()
                        val dur = _uiState.value.refDurationSec.takeIf { it > 0.2f } ?: 10f
                        newClips.add(
                            ClipAsset(
                                id = UUID.randomUUID().toString(),
                                uri = uri,
                                localFile = file,
                                name = nameMime.first,
                                mimeType = nameMime.second,
                                isImage = true,
                                durationSec = dur,
                                width = w,
                                height = h,
                                hasAudio = false,
                                audioBuffer = null,
                                audioDurationSec = 0f,
                                thumbnail = thumb,
                                contactSheetBase64 = b64
                            )
                        )
                        appendLog("Inspected user image: ${nameMime.first} (${w}x${h}) → Ready for AI edit.")
                    } else {
                        val meta = FrameExtractor.probeVideoMetadata(file)
                        val audioBuf = if (meta.hasAudio) BeatDetector.decodeAudioFromFile(file) else null
                        val (thumb, sheetB64) = InspectionSheetBuilder.buildVideoContactSheet(file, meta.durationSec, meta.width, meta.height)
                        newClips.add(
                            ClipAsset(
                                id = UUID.randomUUID().toString(),
                                uri = uri,
                                localFile = file,
                                name = nameMime.first,
                                mimeType = nameMime.second,
                                isImage = false,
                                durationSec = meta.durationSec,
                                width = meta.width,
                                height = meta.height,
                                hasAudio = audioBuf != null,
                                audioBuffer = audioBuf,
                                audioDurationSec = audioBuf?.durationSec ?: 0f,
                                thumbnail = thumb,
                                contactSheetBase64 = sheetB64
                            )
                        )
                        appendLog("Inspected user video: ${nameMime.first} (${"%.2f".format(meta.durationSec)}s, ${meta.width}x${meta.height}, audio=${audioBuf?.let { "%.2f".format(it.durationSec) + "s" } ?: "none"})")
                    }
                } catch (err: Throwable) {
                    appendLog("Media load error: ${err.message}")
                    showToast("Media error: ${err.message}")
                }
            }
            if (newClips.isNotEmpty()) {
                _uiState.update { it.copy(userClips = it.userClips + newClips) }
            }
        }
    }

    fun removeUserClip(index: Int) {
        _uiState.update { st ->
            if (index !in st.userClips.indices) st
            else st.copy(userClips = st.userClips.toMutableList().apply { removeAt(index) })
        }
    }

    fun loadCustomAudio(uri: Uri) {
        viewModelScope.launch {
            try {
                val (file, nameMime) = FrameExtractor.copyUriToCache(app, uri, "audio")
                val pcm = BeatDetector.decodeAudioFromFile(file)
                    ?: throw IllegalStateException("No playable audio track found.")
                val beats = BeatDetector.detectAudioBeats(pcm)
                customAudioBuffer = pcm
                _uiState.update {
                    it.copy(
                        customAudioName = nameMime.first,
                        customAudioDurationSec = pcm.durationSec,
                        customAudioBeats = beats
                    )
                }
                appendLog("Loaded custom audio track: ${nameMime.first} (${"%.2f".format(pcm.durationSec)}s, ${beats.size} beat peaks detected).")
                showToast("Custom audio loaded!")
            } catch (err: Throwable) {
                appendLog("Custom audio decode error: ${err.message}")
                showToast("Cannot decode audio: ${err.message}")
            }
        }
    }

    fun clearCustomAudio() {
        customAudioBuffer = null
        _uiState.update {
            it.copy(customAudioName = "", customAudioDurationSec = 0f, customAudioBeats = emptyList())
        }
        appendLog("Custom audio cleared.")
    }

    fun setIncludeAudioTrack(enabled: Boolean) {
        _uiState.update { it.copy(includeAudioTrack = enabled) }
    }

    fun setAutoVisualQa(enabled: Boolean) {
        _uiState.update { it.copy(autoVisualQa = enabled) }
    }

    fun setExtraNotes(notes: String) {
        _uiState.update { it.copy(extraNotes = notes) }
    }

    fun buildEdit() {
        val st = _uiState.value
        val extraNotes = st.extraNotes.trim()
        if (st.userClips.isEmpty() && extraNotes.isEmpty()) return
        if (st.apiKeys.isEmpty() || st.selectedModel.isBlank()) {
            showToast("Add API key and fetch live model in Settings.")
            return
        }

        if (st.refAnalysis == null) {
            prefs.saveLastEditUsageRecords(emptyList())
            _uiState.update { it.copy(usageRecords = emptyList()) }
        }

        stopPlayback()
        activeStageJob?.cancel()
        val tracker = TelemetryStreamTracker(
            onMonitorUpdate = { m -> _uiState.update { it.copy(stage2Monitor = m) } },
            onLiveStreamUpdate = { txt, status -> _uiState.update { it.copy(liveStreamText = txt, streamMonitorStatus = status) } },
            onLog = { appendLog(it) }
        )

        activeStageJob = viewModelScope.launch {
            tracker.start(this, "Preparing AI director edit...")
            syncCrashCheckpoint("Stage 2: Edit Generation", inProgress = true, forceFlush = true)
            try {
                val cur = _uiState.value
                val hasRef = cur.refAnalysis != null
                val stage2Prompt = if (hasRef && cur.refAnalysis != null) {
                    StagePromptFactory.buildStage2WithRefPrompt(
                        extraNotes = extraNotes,
                        refAnalysis = cur.refAnalysis,
                        sceneCuts = cur.refSceneCuts,
                        beats = cur.refBeats,
                        toolsCode = cur.toolsCode,
                        clips = cur.userClips,
                        customAudioBeats = cur.customAudioBeats
                    )
                } else {
                    StagePromptFactory.buildStage2NoRefPrompt(
                        extraNotes = extraNotes,
                        clips = cur.userClips,
                        customAudioName = cur.customAudioName,
                        customAudioDurationSec = cur.customAudioDurationSec,
                        customAudioBeats = cur.customAudioBeats
                    )
                }

                val assetParts = InspectionSheetBuilder.buildUserAssetParts(cur.userClips)
                appendLog("Sending Stage 2 request (${if (hasRef) "With Ref" else "Prompt/Media Mode"} + ${cur.userClips.size} asset(s)) to Gemini (${cur.selectedModel})...")

                var rawResp = geminiClient.runWithApiCycle(
                    provider = cur.apiProvider,
                    getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                    updateKeys = { k, c ->
                        prefs.saveApiKeys(_uiState.value.apiProvider, k)
                        prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                        _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                    },
                    onStatus = { msg -> tracker.setStatus(msg, null) },
                    onLog = { appendLog(it) },
                    onRetryReset = { tracker.resetStreamPhase() }
                ) { apiKey, _ ->
                    val parts = mutableListOf<GeminiPart>(GeminiPart.Text(stage2Prompt))
                    if (hasRef) {
                        if (cur.refSourceType == ReferenceSourceType.YOUTUBE && cur.refYoutubeUrl.isNotBlank()) {
                            parts.add(GeminiPart.Text("YouTube Reference Video (verify 1:1 editing mechanics directly against this video):"))
                            parts.add(GeminiPart.FileData(cur.refYoutubeUrl))
                        } else {
                            if (cur.refFile != null) {
                                parts.add(GeminiPart.Text("PRIMARY REFERENCE VIDEO (verify EVERY cut, framing, blur, light trail, and timing 1:1):"))
                                parts.add(
                                    geminiClient.buildFileMediaPart(
                                        file = cur.refFile,
                                        mimeType = "video/mp4",
                                        provider = cur.apiProvider,
                                        apiKey = apiKey,
                                        onStatus = { s, p -> tracker.setStatus(s, p) },
                                        onFeed = { f -> tracker.setFeedMessage(f) },
                                        onLog = { appendLog(it) }
                                    )
                                )
                            }
                            cur.refCutSheetB64?.let {
                                parts.add(GeminiPart.Text("EXACT CUT & BEAT KEYFRAME SHEET OF REFERENCE VIDEO:"))
                                parts.add(GeminiPart.InlineData("image/jpeg", it))
                            }
                            cur.refStoryboardB64?.let {
                                parts.add(GeminiPart.Text("HELPER DIAGNOSTIC 5x5 STORYBOARD OF REFERENCE VIDEO ONLY (NOT part of user edit):"))
                                parts.add(GeminiPart.InlineData("image/jpeg", it))
                            }
                        }
                    }
                    parts.addAll(assetParts)
                    var attemptUsage: TokenUsageSnapshot? = null
                    try {
                        val out = geminiClient.streamGenerateContent(
                            provider = cur.apiProvider,
                            modelName = cur.selectedModel,
                            parts = parts,
                            apiKey = apiKey,
                            thinkingLevel = cur.thinkingLevel,
                            onStatus = { s, p -> tracker.setStatus(s, p) },
                            onLog = { appendLog(it) },
                            onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                            onUsage = { u -> attemptUsage = u }
                        )
                        attemptUsage?.let { recordTokenUsage(UsageStage.EDIT_GENERATION, it, isPartialFailure = false) }
                        out
                    } catch (e: Throwable) {
                        attemptUsage?.let { recordTokenUsage(UsageStage.EDIT_GENERATION, it, isPartialFailure = true) }
                        throw e
                    }
                }

                tracker.setStatus("Compiling & validating rendered frames...", 0.78f)
                var parsedRes = geminiClient.parseJsonAndCodeResult(rawResp, "renderPipelineCode")
                var planJson = geminiClient.decodeJson(parsedRes.rawJson, Stage2PlanResult())

                if (parsedRes.code.isBlank()) {
                    tracker.resetStreamPhase()
                    val fbW = if (planJson.aspectWidth > 0) planJson.aspectWidth else cur.userClips.firstOrNull()?.width ?: 9
                    val fbH = if (planJson.aspectHeight > 0) planJson.aspectHeight else cur.userClips.firstOrNull()?.height ?: 16
                    val fbDur = if (planJson.totalDurationSec > 0) planJson.totalDurationSec else cur.userClips.firstOrNull()?.durationSec?.toDouble() ?: 10.0
                    rawResp = geminiClient.runWithApiCycle(
                        provider = cur.apiProvider,
                        getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                        updateKeys = { k, c ->
                            prefs.saveApiKeys(_uiState.value.apiProvider, k)
                            prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                            _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                        },
                        onStatus = { msg -> tracker.setStatus(msg, null) },
                        onLog = { appendLog(it) },
                        onRetryReset = { tracker.resetStreamPhase() }
                    ) { apiKey, _ ->
                        var attemptUsage: TokenUsageSnapshot? = null
                        try {
                            val out = geminiClient.streamGenerateContent(
                                provider = cur.apiProvider,
                                modelName = cur.selectedModel,
                                parts = listOf(
                                    GeminiPart.Text(
                                        "Re-output cleanly using:\n===JSON_RESULT===\n{\"aspectWidth\":$fbW,\"aspectHeight\":$fbH,\"totalDurationSec\":$fbDur,\"editPlan\":[]}\n===CODE_RESULT===\nasync function init(clips){}\nasync function renderFrame(ctx, width, height, t, duration, clips){...}\n\nPrevious response:\n${rawResp.take(6000)}"
                                    )
                                ),
                                apiKey = apiKey,
                                thinkingLevel = cur.thinkingLevel,
                                onStatus = { s, p -> tracker.setStatus(s, p) },
                                onLog = { appendLog(it) },
                                onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                                onUsage = { u -> attemptUsage = u }
                            )
                            attemptUsage?.let { recordTokenUsage(UsageStage.EDIT_GENERATION, it, isPartialFailure = false) }
                            out
                        } catch (e: Throwable) {
                            attemptUsage?.let { recordTokenUsage(UsageStage.EDIT_GENERATION, it, isPartialFailure = true) }
                            throw e
                        }
                    }
                    parsedRes = geminiClient.parseJsonAndCodeResult(rawResp, "renderPipelineCode")
                    planJson = geminiClient.decodeJson(parsedRes.rawJson, Stage2PlanResult())
                }

                val fallbackW = if (hasRef) (cur.refAnalysis?.meta?.aspectWidth ?: cur.userClips.firstOrNull()?.width ?: 9) else (cur.userClips.firstOrNull()?.width ?: 9)
                val fallbackH = if (hasRef) (cur.refAnalysis?.meta?.aspectHeight ?: cur.userClips.firstOrNull()?.height ?: 16) else (cur.userClips.firstOrNull()?.height ?: 16)
                val aw = if (planJson.aspectWidth > 0) planJson.aspectWidth else fallbackW
                val ah = if (planJson.aspectHeight > 0) planJson.aspectHeight else fallbackH
                var (canvasW, canvasH) = computeCanvasSize(aw, ah, cur.maxCanvasEdge)

                var durationSec = max(
                    1f,
                    when {
                        planJson.totalDurationSec > 0.2 -> planJson.totalDurationSec.toFloat()
                        hasRef && (cur.refAnalysis?.meta?.durationSec ?: 0.0) > 0.2 -> cur.refAnalysis!!.meta.durationSec.toFloat()
                        cur.refDurationSec > 0.2f -> cur.refDurationSec
                        cur.customAudioDurationSec > 0.2f -> cur.customAudioDurationSec
                        else -> 10f
                    }
                )

                val blueprintUpdate = if (!hasRef) prettyJson.encodeToString(planJson) else cur.blueprintJsonText
                _uiState.update {
                    it.copy(
                        canvasWidth = canvasW,
                        canvasHeight = canvasH,
                        totalDurationSec = durationSec,
                        editPlan = planJson.editPlan,
                        blueprintJsonText = blueprintUpdate,
                        pipelineCodeText = parsedRes.code.ifBlank { it.pipelineCodeText }
                    )
                }
                syncCrashCheckpoint("Stage 2: Compile & Validate (${canvasW}x${canvasH})", inProgress = true, forceFlush = true)

                val validated = services.quickJsRunner.compileAndValidateTechnical(
                    initialCode = parsedRes.code,
                    toolsCode = cur.toolsCode,
                    clips = cur.userClips,
                    editPlan = planJson.editPlan,
                    canvasW = canvasW,
                    canvasH = canvasH,
                    durationSec = durationSec,
                    onStatus = { s, p -> tracker.setStatus(s, p) },
                    onRequestFix = { attempt, failingCode, errMsg ->
                        services.visualQaManager.requestTechnicalFix(
                            provider = cur.apiProvider,
                            modelName = cur.selectedModel,
                            thinkingLevel = cur.thinkingLevel,
                            getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                            updateKeys = { k, c ->
                                prefs.saveApiKeys(_uiState.value.apiProvider, k)
                                prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                                _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                            },
                            failingCode = failingCode,
                            errorMsg = errMsg,
                            clipCount = cur.userClips.size,
                            attempt = attempt,
                            onStatus = { s, p -> tracker.setStatus(s, p) },
                            onLog = { appendLog(it) },
                            onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                            onResetStreamPhase = { tracker.resetStreamPhase() },
                            onUsage = { stage, u, partial -> recordTokenUsage(stage, u, partial) }
                        )
                    }
                )

                var activeCode = validated.code
                _uiState.update { it.copy(pipelineCodeText = activeCode) }
                syncCrashCheckpoint("Stage 2: Post-Validation", inProgress = true, forceFlush = true)

                var qaSkippedWarning = ""
                if (cur.autoVisualQa) {
                    val maxQaPasses = if (cur.apiProvider == ProviderMode.AI_STUDIO) 2 else 1
                    syncCrashCheckpoint("Stage 2: Auto Visual QA", inProgress = true, forceFlush = true)
                    try {
                        val qaOutcome = services.visualQaManager.runVisualQaSelfFix(
                            provider = cur.apiProvider,
                            modelName = cur.selectedModel,
                            thinkingLevel = cur.thinkingLevel,
                            getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                            updateKeys = { k, c ->
                                prefs.saveApiKeys(_uiState.value.apiProvider, k)
                                prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                                _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                            },
                            canvasW = canvasW,
                            canvasH = canvasH,
                            durationSec = durationSec,
                            maxPasses = maxQaPasses,
                            userFeedbackInput = "",
                            extraNotes = extraNotes,
                            refSourceType = cur.refSourceType,
                            refFile = cur.refFile,
                            refWidth = cur.refWidth,
                            refHeight = cur.refHeight,
                            refDurationSec = cur.refDurationSec,
                            refYoutubeUrl = cur.refYoutubeUrl,
                            refCutSheetB64 = cur.refCutSheetB64,
                            refStoryboardB64 = cur.refStoryboardB64,
                            refSceneCuts = cur.refSceneCuts,
                            refBeats = cur.refBeats,
                            refAnalysis = cur.refAnalysis,
                            toolsCode = cur.toolsCode,
                            initialPipelineCode = activeCode,
                            clips = cur.userClips,
                            editPlan = planJson.editPlan,
                            onCanvasSizeUpdate = { newAw, newAh, newDur ->
                                val (nw, nh) = if (newAw > 0 && newAh > 0) computeCanvasSize(newAw, newAh, _uiState.value.maxCanvasEdge) else (canvasW to canvasH)
                                canvasW = nw
                                canvasH = nh
                                durationSec = newDur
                                _uiState.update { it.copy(canvasWidth = nw, canvasHeight = nh, totalDurationSec = newDur) }
                                nw to nh
                            },
                            onPipelineUpdated = { nc, nd ->
                                activeCode = nc
                                durationSec = nd
                                _uiState.update { it.copy(pipelineCodeText = nc, totalDurationSec = nd) }
                                syncCrashCheckpoint("Stage 2: Auto Visual QA (updated)", inProgress = true, forceFlush = true)
                            },
                            onStatus = { s, p -> tracker.setStatus(s, p) },
                            onFeed = { f -> tracker.setFeedMessage(f) },
                            onLog = { appendLog(it) },
                            onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                            onResetStreamPhase = { tracker.resetStreamPhase() },
                            onUsage = { stage, u, partial -> recordTokenUsage(stage, u, partial) }
                        )
                        activeCode = qaOutcome.finalCode
                        durationSec = qaOutcome.finalDurationSec
                    } catch (e: CancellationException) {
                        throw e
                    } catch (qaErr: Throwable) {
                        if (cur.apiProvider == ProviderMode.AI_STUDIO) throw qaErr
                        qaSkippedWarning = qaErr.message ?: qaErr.toString()
                        appendLog("Warning: Auto Visual QA stopped ($qaSkippedWarning). Keeping validated edit pipeline for preview & export.")
                        showToast("Visual QA skipped ($qaSkippedWarning) — your generated edit is ready!")
                    }
                }

                val previewStart = min(durationSec * 0.5f, 2.0f)
                _uiState.update {
                    it.copy(
                        isRenderReady = true,
                        pipelineCodeText = activeCode,
                        totalDurationSec = durationSec,
                        currentTimeSec = previewStart,
                        step2Done = true
                    )
                }
                syncCrashCheckpoint("Stage 2: Initial Preview Render", inProgress = true, forceFlush = true)
                drawFrameAtTime(previewStart)

                if (qaSkippedWarning.isNotEmpty()) {
                    tracker.finish("Edit ready! (Visual QA skipped due to API/network limit — preview & export enabled.)", true)
                } else {
                    tracker.finish("Edit ready & visually verified! Tap Next → to preview & export.", true)
                    showToast("AI edit generated & visually verified!")
                }
            } catch (e: CancellationException) {
                tracker.finish("Cancelled by user.", success = false)
                appendLog("Stage 2 cancelled by user.")
            } catch (err: Throwable) {
                val msg = err.message ?: "Build failed"
                val isModelIssue = Regex("503|high demand|overloaded|unavailable|400|404|did not respond", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                tracker.finish(msg, success = false, showSwitchModel = isModelIssue)
                appendLog("Stage 2 error: $msg")
                showToast("Build failed: $msg")
            } finally {
                syncCrashCheckpoint("Idle", inProgress = false, forceFlush = true)
            }
        }
    }

    // ---- Step 3: Playback, Scrubbing, Manual Auto-Fix & Hardware Export ----

    fun setQaFeedbackInput(text: String) {
        _uiState.update { it.copy(qaFeedbackInput = text) }
    }

    fun seekTo(timeSec: Float) {
        if (!_uiState.value.isRenderReady) return
        if (_uiState.value.isPlaying) stopPlayback()
        val clamped = timeSec.coerceIn(0f, _uiState.value.totalDurationSec)
        _uiState.update { it.copy(currentTimeSec = clamped) }
        viewModelScope.launch { drawFrameAtTime(clamped) }
    }

    fun togglePlayPause() {
        val st = _uiState.value
        if (!st.isRenderReady) return
        if (st.isPlaying) {
            stopPlayback()
            return
        }

        val startT = if (st.currentTimeSec >= st.totalDurationSec - 0.05f) 0f else st.currentTimeSec
        _uiState.update { it.copy(isPlaying = true, currentTimeSec = startT) }
        val activeBuf = getActiveAudioBuffer()
        audioPlayer.start(viewModelScope, activeBuf, startT, st.totalDurationSec)

        playbackJob = viewModelScope.launch {
            var lastElapsedMs = SystemClock.elapsedRealtime()
            var t = startT
            val fpsDelay = (1000L / _uiState.value.exportFps.coerceIn(15, 30))
            while (isActive && _uiState.value.isPlaying) {
                val now = SystemClock.elapsedRealtime()
                val dt = (now - lastElapsedMs) / 1000f
                lastElapsedMs = now
                t += dt
                val totalDur = _uiState.value.totalDurationSec
                if (t >= totalDur) {
                    stopPlayback()
                    val restT = min(totalDur * 0.5f, 2.0f)
                    _uiState.update { it.copy(currentTimeSec = 0f) }
                    drawFrameAtTime(restT)
                    break
                }
                _uiState.update { it.copy(currentTimeSec = t) }
                drawFrameAtTime(t)
                delay(fpsDelay)
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        audioPlayer.stop()
        _uiState.update { it.copy(isPlaying = false) }
    }

    private suspend fun drawFrameAtTime(t: Float) {
        val st = _uiState.value
        if (!st.isRenderReady) return
        drawMutex.withLock {
            runCatching {
                val raw = services.quickJsRunner.renderFrame(st.canvasWidth, st.canvasHeight, t, st.totalDurationSec)
                val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
                _uiState.update { cur ->
                    cur.copy(previewFrame = copy)
                }
            }.onFailure { err ->
                appendLog("Render tick error at t=${"%.2f".format(t)}s: ${err.message}")
            }
        }
    }

    fun runManualAutoFix() {
        val st = _uiState.value
        if (!st.isRenderReady) {
            showToast("Generate an edit first.")
            return
        }
        if (st.isPlaying) stopPlayback()

        activeStageJob?.cancel()
        val tracker = TelemetryStreamTracker(
            onMonitorUpdate = { m -> _uiState.update { it.copy(stage3Monitor = m) } },
            onLiveStreamUpdate = { txt, status -> _uiState.update { it.copy(liveStreamText = txt, streamMonitorStatus = status) } },
            onLog = { appendLog(it) }
        )

        activeStageJob = viewModelScope.launch {
            tracker.start(this, "Inspecting render...")
            syncCrashCheckpoint("Stage 3: Manual AI Auto-Fix", inProgress = true, forceFlush = true)
            try {
                val cur = _uiState.value
                val hasRef = cur.refAnalysis != null
                val rawFeedback = cur.qaFeedbackInput.trim()
                val feedback = rawFeedback.ifBlank {
                    if (hasRef) {
                        "Compare reference video/storyboard and uploaded user assets against output video/storyboard, and fix any layout, tile alignment, photo resizing inside tiles, grid cols/rows, timing, cutout, or text differences."
                    } else {
                        "Compare the user prompt and uploaded user assets against the generated output video/storyboard, and improve visual quality, tile/asset sizing & alignment, animation smoothness, and prompt accuracy."
                    }
                }
                appendLog("Manual AI Auto-Fix requested (${if (hasRef) "With Reference" else "Prompt/Media Mode"}) → \"$feedback\"")

                val outcome = services.visualQaManager.runVisualQaSelfFix(
                    provider = cur.apiProvider,
                    modelName = cur.selectedModel,
                    thinkingLevel = cur.thinkingLevel,
                    getKeys = { _uiState.value.apiKeys to _uiState.value.cycleIdx },
                    updateKeys = { k, c ->
                        prefs.saveApiKeys(_uiState.value.apiProvider, k)
                        prefs.saveCycleIndex(_uiState.value.apiProvider, c)
                        _uiState.update { s -> s.copy(apiKeys = k, cycleIdx = c) }
                    },
                    canvasW = cur.canvasWidth,
                    canvasH = cur.canvasHeight,
                    durationSec = cur.totalDurationSec,
                    maxPasses = 1,
                    userFeedbackInput = feedback,
                    extraNotes = cur.extraNotes.trim(),
                    refSourceType = cur.refSourceType,
                    refFile = cur.refFile,
                    refWidth = cur.refWidth,
                    refHeight = cur.refHeight,
                    refDurationSec = cur.refDurationSec,
                    refYoutubeUrl = cur.refYoutubeUrl,
                    refCutSheetB64 = cur.refCutSheetB64,
                    refStoryboardB64 = cur.refStoryboardB64,
                    refSceneCuts = cur.refSceneCuts,
                    refBeats = cur.refBeats,
                    refAnalysis = cur.refAnalysis,
                    toolsCode = cur.toolsCode,
                    initialPipelineCode = cur.pipelineCodeText,
                    clips = cur.userClips,
                    editPlan = cur.editPlan,
                    onCanvasSizeUpdate = { newAw, newAh, newDur ->
                        val (nw, nh) = if (newAw > 0 && newAh > 0) computeCanvasSize(newAw, newAh, _uiState.value.maxCanvasEdge) else (_uiState.value.canvasWidth to _uiState.value.canvasHeight)
                        _uiState.update { it.copy(canvasWidth = nw, canvasHeight = nh, totalDurationSec = newDur) }
                        nw to nh
                    },
                    onPipelineUpdated = { nc, nd ->
                        _uiState.update { it.copy(pipelineCodeText = nc, totalDurationSec = nd) }
                        syncCrashCheckpoint("Stage 3: Manual AI Auto-Fix (updated)", inProgress = true, forceFlush = true)
                    },
                    onStatus = { s, p -> tracker.setStatus(s, p) },
                    onFeed = { f -> tracker.setFeedMessage(f) },
                    onLog = { appendLog(it) },
                    onToken = { txt, isThought -> tracker.appendToken(txt, isThought) },
                    onResetStreamPhase = { tracker.resetStreamPhase() },
                    onUsage = { stage, u, partial -> recordTokenUsage(stage, u, partial) }
                )

                val safeT = min(max(0.05f, outcome.finalDurationSec - 0.05f), max(0.5f, cur.currentTimeSec))
                drawFrameAtTime(safeT)

                if (outcome.changed) {
                    _uiState.update { it.copy(qaFeedbackInput = "") }
                    tracker.finish("Auto-Fix updated code & preview!", true)
                    showToast("AI Auto-Fix updated code & preview!")
                } else {
                    tracker.finish("AI returned no code changes — try a clearer note or different model.", false, showSwitchModel = true)
                    showToast("AI returned no code changes.")
                }
            } catch (e: CancellationException) {
                tracker.finish("Auto-Fix cancelled by user.", false)
            } catch (err: Throwable) {
                val msg = err.message ?: "Auto-Fix failed"
                val isModelIssue = Regex("503|high demand|overloaded|unavailable|400|404|did not respond", RegexOption.IGNORE_CASE).containsMatchIn(msg)
                tracker.finish(msg, false, showSwitchModel = isModelIssue)
                appendLog("Stage 3 Auto-Fix error: $msg")
                showToast("Auto-Fix failed: $msg")
            } finally {
                syncCrashCheckpoint("Idle", inProgress = false, forceFlush = true)
            }
        }
    }

    fun exportVideo() {
        val st = _uiState.value
        if (!st.isRenderReady || st.isExporting) return
        if (st.isPlaying) stopPlayback()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    exportProgress = 0.02f,
                    exportStatusText = "Preparing hardware encoder...",
                    exportedVideoFile = null,
                    exportedVideoUri = null
                )
            }
            syncCrashCheckpoint("Stage 3: Hardware MP4 Export", inProgress = true, forceFlush = true)
            try {
                val activeAudio = getActiveAudioBuffer()
                appendLog("Starting deterministic hardware MP4 export (${st.canvasWidth}x${st.canvasHeight} @ ${st.exportFps}fps, audio=${activeAudio != null})...")
                val res = services.videoExporter.exportVideo(
                    width = st.canvasWidth,
                    height = st.canvasHeight,
                    fps = st.exportFps,
                    durationSec = st.totalDurationSec,
                    audioBuffer = activeAudio,
                    renderFrameAt = { t ->
                        drawMutex.withLock {
                            services.quickJsRunner.renderFrame(st.canvasWidth, st.canvasHeight, t, st.totalDurationSec)
                        }
                    },
                    onProgress = { prog, msg ->
                        _uiState.update { it.copy(exportProgress = prog, exportStatusText = msg) }
                    }
                )
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportProgress = 1f,
                        exportedVideoFile = res.file,
                        exportedVideoUri = res.shareUri,
                        step3Done = true
                    )
                }
                appendLog("Hardware MP4 export finished: ${"%.1f".format(res.durationSec)}s, ${"%.2f".format(res.sizeBytes / (1024.0 * 1024.0))} MB")
                showToast("Video exported with exact duration!")
            } catch (err: Throwable) {
                _uiState.update {
                    it.copy(isExporting = false, exportStatusText = "Export error: ${err.message}")
                }
                appendLog("Export error: ${err.message}")
                showToast("Export failed: ${err.message}")
            } finally {
                syncCrashCheckpoint("Idle", inProgress = false, forceFlush = true)
            }
        }
    }

    fun saveExportedToGallery() {
        val file = _uiState.value.exportedVideoFile ?: return
        viewModelScope.launch {
            val savedUri = services.videoExporter.saveToMoviesGallery(file, "editoraPRO_${System.currentTimeMillis()}.mp4")
            if (savedUri != null) {
                showToast("Saved to Movies/editoraPRO!")
                appendLog("Exported video saved to MediaStore: $savedUri")
            } else {
                showToast("Could not save to gallery.")
            }
        }
    }

    // ---- Diagnostic Report ----

    fun buildFullReportText(): String {
        val st = _uiState.value
        val costSummary = st.editCostSummary
        val refDesc = when {
            st.refSourceType == ReferenceSourceType.YOUTUBE -> "YouTube URL (${st.refYoutubeUrl}, ${"%.2f".format(st.refDurationSec)}s)"
            st.refSourceType == ReferenceSourceType.FILE && st.refFile != null ->
                "${st.refName} (${"%.2f".format(st.refDurationSec)}s${if (st.refSceneCuts.isNotEmpty()) ", cuts:${st.refSceneCuts}" else ""})"
            else -> "None (Prompt / Media Mode)"
        }
        val mediaSummary = st.userClips.mapIndexed { i, c ->
            "#$i [${if (c.isImage) "IMG" else "VID"}] ${c.name} (${"%.1f".format(c.durationSec)}s, ${c.width}x${c.height}${if (c.hasAudio) ", audio:${"%.1f".format(c.audioDurationSec)}s" else ""})"
        }.joinToString(" | ").ifBlank { "None (Pure Procedural)" }

        val usageLines = if (costSummary.byStage.isNotEmpty()) {
            buildList {
                add("")
                add("--- TOKEN USAGE & PRICING SUMMARY ---")
                costSummary.byStage.forEach { sec ->
                    add(
                        "${sec.stage.label} (${sec.callCount} call(s)): " +
                            "in=${sec.promptTokens}, out=${sec.candidatesTokens}, think=${sec.thoughtsTokens}, " +
                            "total=${sec.totalTokens} | ${ModelPricingCatalog.formatUsd(sec.totalCostUsd)}"
                    )
                }
                add(
                    "Edit Total (${costSummary.totalCalls} call(s)): " +
                        "in=${costSummary.totalPromptTokens}, out=${costSummary.totalCandidatesTokens}, " +
                        "think=${costSummary.totalThoughtsTokens}, total=${costSummary.totalTokens} | " +
                        ModelPricingCatalog.formatUsd(costSummary.totalCostUsd)
                )
                add(
                    "Lifetime Total: ${st.lifetimeTotalTokens} tokens | " +
                        ModelPricingCatalog.formatUsd(st.lifetimeTotalCostUsd)
                )
            }
        } else {
            emptyList()
        }

        val crashLines = st.persistedCrashReport?.let { crash ->
            listOf("", crash.toFormattedText())
        } ?: emptyList()

        return (listOfNotNull(
            "=== AI VIDEO DIRECTOR DIAGNOSTIC REPORT ===",
            "Generated: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())}",
            "API Provider: ${if (st.apiProvider == ProviderMode.AI_STUDIO) "AI Studio (v1beta)" else "Vertex AI (Gemini Platform)"}",
            "Selected Model: ${st.selectedModel.ifBlank { "None" }}",
            if (st.apiProvider == ProviderMode.AI_STUDIO) "Thinking Level: ${st.thinkingLevel.wireValue}" else null,
            "API Keys Count: ${st.apiKeys.size}",
            "Reference Source: $refDesc",
            "Custom Audio: ${st.customAudioName.ifBlank { "None" }}",
            "Prompt / Notes: ${st.extraNotes.trim().ifBlank { "None" }}",
            "User Media: $mediaSummary"
        ) + crashLines + usageLines + listOf(
            "",
            "--- DEEP EXECUTION LOG ---",
            st.executionLogs.joinToString("\n"),
            "",
            "--- LIVE AI STREAM & THOUGHT MONITOR ---",
            st.liveStreamText,
            "",
            "--- 1. AI REFERENCE / CREATIVE BLUEPRINT (JSON) ---",
            st.blueprintJsonText,
            "",
            "--- 2. AI-GENERATED INTERNAL TOOLS (JS) ---",
            st.toolsCodeText,
            "",
            "--- 3. AI-GENERATED EDIT PIPELINE (JS) ---",
            st.pipelineCodeText
        )).joinToString("\n")
    }

    fun writeReportFileAndGetUri(): Uri {
        val dir = File(app.cacheDir, "reports").apply { mkdirs() }
        val file = File(dir, "ai-director-report-${System.currentTimeMillis()}.txt")
        file.writeText(buildFullReportText())
        return FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    }

    private fun getActiveAudioBuffer(): PcmAudioBuffer? {
        if (!_uiState.value.includeAudioTrack) return null
        return customAudioBuffer ?: refAudioBuffer ?: services.quickJsRunner.aiGeneratedAudioBuffer
    }

    private fun computeCanvasSize(aw: Int, ah: Int, maxEdge: Int): Pair<Int, Int> {
        val wRatio = max(1, aw)
        val hRatio = max(1, ah)
        return if (hRatio >= wRatio) {
            val ch = maxEdge and 1.inv()
            val cw = max(2, ((maxEdge * wRatio.toFloat()) / hRatio.toFloat()).roundToInt() and 1.inv())
            cw to ch
        } else {
            val cw = maxEdge and 1.inv()
            val ch = max(2, ((maxEdge * hRatio.toFloat()) / wRatio.toFloat()).roundToInt() and 1.inv())
            cw to ch
        }
    }

    fun parseYoutubeUrl(input: String): ParsedYoutubeRef? {
        val raw = input.trim()
        if (raw.isEmpty()) return null
        val normalized = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return null
        val pathSegs = uri.pathSegments.orEmpty().filter { it.isNotBlank() }

        val rawId = when {
            host == "youtu.be" -> pathSegs.firstOrNull().orEmpty()
            host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
                if (uri.path == "/watch") uri.getQueryParameter("v").orEmpty()
                else if (pathSegs.firstOrNull() in listOf("shorts", "embed", "live", "v")) pathSegs.getOrNull(1).orEmpty()
                else ""
            }
            else -> ""
        }
        val cleanId = Regex("^([a-zA-Z0-9_-]{11})").find(rawId)?.groupValues?.get(1) ?: return null
        return ParsedYoutubeRef(id = cleanId, watchUrl = "https://www.youtube.com/watch?v=$cleanId")
    }

    override fun onCleared() {
        super.onCleared()
        activeStageJob?.cancel()
        stopPlayback()
        services.release()
    }
}