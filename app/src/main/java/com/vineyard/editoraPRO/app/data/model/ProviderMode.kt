package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProviderMode(val storageId: String, val shortLabel: String, val fullLabel: String) {
    VERTEX_AI(
        storageId = "vertex",
        shortLabel = "Vertex AI",
        fullLabel = "Vertex AI (Gemini Platform • lunar-tag/global)"
    ),
    AI_STUDIO(
        storageId = "aistudio",
        shortLabel = "AI Studio",
        fullLabel = "AI Studio (Google Generative Language v1beta)"
    );

    companion object {
        fun fromStorage(raw: String?): ProviderMode =
            if (raw == AI_STUDIO.storageId) AI_STUDIO else VERTEX_AI
    }
}
