package com.vineyard.editoraPRO.app.data.model

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

enum class MainTab {
    STUDIO,
    AI_LOGIC,
    SETTINGS
}

enum class ProcessStatus {
    IDLE,
    RUNNING,
    SUCCESS,
    ERROR
}

data class ProcessMonitorState(
    val visible: Boolean = false,
    val status: ProcessStatus = ProcessStatus.IDLE,
    val statusText: String = "Preparing...",
    val elapsedMs: Long = 0L,
    val charCount: Int = 0,
    val progress: Float = 0.08f,
    val liveFeedLines: List<String> = listOf("Waiting for stream..."),
    val showSwitchModelButton: Boolean = false
)

data class AppState(
    val activeTab: MainTab = MainTab.STUDIO,
    val wizardStep: Int = 1,
    val step2SubStep: Int = 1,
    val step1Done: Boolean = false,
    val step2Done: Boolean = false,
    val step3Done: Boolean = false,

    // Provider & Settings
    val settingsSubPage: Int = 1,
    val apiProvider: ProviderMode = ProviderMode.VERTEX_AI,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    val apiKeys: List<ApiKeyEntry> = emptyList(),
    val cycleIdx: Int = 0,
    val models: List<GeminiModelInfo> = emptyList(),
    val selectedModel: String = "",
    val isFetchingModels: Boolean = false,
    val exportFps: Int = 30,
    val maxCanvasEdge: Int = 1080,

    // Step 1: Reference
    val refSourceType: ReferenceSourceType = ReferenceSourceType.NONE,
    val refFile: File? = null,
    val refUri: Uri? = null,
    val refName: String = "",
    val refYoutubeUrl: String = "",
    val refYoutubeId: String = "",
    val refDurationSec: Float = 0f,
    val refWidth: Int = 0,
    val refHeight: Int = 0,
    val refBeats: List<Double> = emptyList(),
    val refSceneCuts: List<Double> = emptyList(),
    val refCutSheetB64: String? = null,
    val refStoryboardB64: String? = null,
    val refAnalysis: ReferenceBlueprint? = null,
    val toolsCode: String = "",
    val stage1Monitor: ProcessMonitorState = ProcessMonitorState(),

    // Step 2: Media & Prompt
    val userClips: List<ClipAsset> = emptyList(),
    val customAudioName: String = "",
    val customAudioDurationSec: Float = 0f,
    val customAudioBeats: List<Double> = emptyList(),
    val includeAudioTrack: Boolean = true,
    val autoVisualQa: Boolean = false,
    val extraNotes: String = "",
    val stage2Monitor: ProcessMonitorState = ProcessMonitorState(),

    // Step 3: Output Preview, QA & Export
    val canvasWidth: Int = 540,
    val canvasHeight: Int = 960,
    val isRenderReady: Boolean = false,
    val previewFrame: Bitmap? = null,
    val isPlaying: Boolean = false,
    val currentTimeSec: Float = 0f,
    val totalDurationSec: Float = 10f,
    val qaFeedbackInput: String = "",
    val stage3Monitor: ProcessMonitorState = ProcessMonitorState(),
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportStatusText: String = "",
    val exportedVideoFile: File? = null,
    val exportedVideoUri: Uri? = null,

    // AI Logic Inspector
    val streamMonitorStatus: ProcessStatus = ProcessStatus.IDLE,
    val liveStreamText: String = "Waiting for AI request...",
    val executionLogs: List<String> = listOf("Ready. Configure API keys & fetch live models in Settings."),
    val blueprintJsonText: String = "No reference or prompt plan generated yet.",
    val toolsCodeText: String = "AI will synthesize custom JS tools here after analyzing a reference video (optional in prompt-only mode).",
    val pipelineCodeText: String = "AI will synthesize the live timeline & frame renderer here after generating your edit.",
    val editPlan: List<EditPlanSegment> = emptyList(),

    // Token Usage & Pricing Ledger
    val usageRecords: List<SectionUsageRecord> = emptyList(),
    val customPricingOverrides: Map<String, ModelPricingRule> = emptyMap(),
    val estimateCostInFreeTier: Boolean = true,
    val lifetimeTotalTokens: Long = 0L,
    val lifetimeTotalCostUsd: Double = 0.0,

    // Persisted Crash Report
    val persistedCrashReport: PersistedCrashReport? = null,

    // Toast
    val toastMessage: String? = null,
    val toastCounter: Long = 0L
) {
    val editCostSummary: EditCostSummary
        get() = EditCostSummary.fromRecords(usageRecords)
}