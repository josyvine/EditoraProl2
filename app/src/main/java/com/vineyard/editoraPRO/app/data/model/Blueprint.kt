package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TextOverlaySpec(
    val text: String = "",
    val startSec: Double = 0.0,
    val endSec: Double = 0.0,
    val style: String = "",
    val position: String = "",
    val wordByWord: Boolean = false
)

@Serializable
data class BlueprintSegment(
    val segmentIndex: Int = 0,
    val startSec: Double = 0.0,
    val endSec: Double = 0.0,
    val layout: String = "",
    val panelRectsNormalized: List<List<Double>> = emptyList(),
    val borderless: Boolean = true,
    val requiredClipRole: String = "",
    val effectsAndTransitions: String = "",
    val textOverlays: List<TextOverlaySpec> = emptyList(),
    val maskOrCutout: String = "none",
    val maskEngine: String? = null
)

@Serializable
data class ReferenceMeta(
    val summary: String = "",
    val durationSec: Double = 10.0,
    val aspectWidth: Int = 9,
    val aspectHeight: Int = 16,
    val gridCols: Int = 1,
    val gridRows: Int = 1,
    val persistentOverlay: String = "",
    val beatsSec: List<Double> = emptyList(),
    val sceneCutsSec: List<Double> = emptyList(),
    val audioMood: String = "",
    val maskEngine: String? = null
)

@Serializable
data class ReferenceBlueprint(
    val meta: ReferenceMeta = ReferenceMeta(),
    val timelineBlueprint: List<BlueprintSegment> = emptyList()
)

@Serializable
data class EditPlanSegment(
    val segmentIndex: Int = 0,
    val clipIndex: Int = 0,
    val inSec: Double = 0.0,
    val outSec: Double = 0.0,
    val role: String = ""
)

@Serializable
data class Stage2PlanResult(
    val summary: String = "",
    val aspectWidth: Int = 9,
    val aspectHeight: Int = 16,
    val totalDurationSec: Double = 10.0,
    val editPlan: List<EditPlanSegment> = emptyList()
)

@Serializable
data class BlindVisualAuditResult(
    val passed: Boolean = false,
    val score1to10: Double = 0.0,
    val aspectAndFraming1to10: Double = 0.0,
    val layoutAndGeometry1to10: Double = 0.0,
    val effectsAndPolish1to10: Double = 0.0,
    val timingAndCuts1to10: Double = 0.0,
    val colorAndMood1to10: Double = 0.0,
    val visualObservations: String = "",
    val issuesFound: List<String> = emptyList()
)

@Serializable
data class CodeRewriteJsonResult(
    val totalDurationSec: Double = 0.0,
    val aspectWidth: Int? = null,
    val aspectHeight: Int? = null,
    val appliedFixes: List<String> = emptyList()
)

@Serializable
data class DetectedBox(
    val label: String,
    val score: Float,
    val box: List<Float> // [x, y, w, h] normalized 0..1
)

sealed interface GeminiPart {
    data class Text(val text: String) : GeminiPart
    data class InlineData(val mimeType: String, val base64Data: String) : GeminiPart
    data class FileData(val fileUri: String, val mimeType: String? = null) : GeminiPart
}

data class UploadedFileRecord(
    val name: String,
    val uri: String,
    val mimeType: String,
    val state: String
)

data class ParsedJsonAndCode(
    val rawJson: JsonElement?,
    val code: String
)
