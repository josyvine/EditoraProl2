package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ThinkingLevel(val wireValue: String, val label: String, val badgeLabel: String) {
    DEFAULT("default", "Model Default (Auto)", "Model Default"),
    MINIMAL("minimal", "Minimal (Fastest)", "Minimal"),
    LOW("low", "Low", "Low"),
    MEDIUM("medium", "Medium", "Medium"),
    HIGH("high", "High", "High"),
    XHIGH("xhigh", "Extra High (Gemini 4 Neon / Hydrogen)", "Extra High");

    companion object {
        fun fromWire(raw: String?): ThinkingLevel =
            entries.firstOrNull { it.wireValue == raw } ?: DEFAULT
    }
}
