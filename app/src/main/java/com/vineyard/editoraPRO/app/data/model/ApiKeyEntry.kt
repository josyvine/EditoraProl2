package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ApiKeyEntry(
    val key: String,
    val requests: Int = 0,
    val errors: Int = 0,
    val disabledReason: String? = null
) {
    val isDisabled: Boolean get() = !disabledReason.isNullOrBlank()

    fun masked(): String = when {
        key.length <= 10 -> "••••${key.takeLast(4)}"
        else -> "${key.take(6)}••••${key.takeLast(4)}"
    }
}

@Serializable
data class GeminiModelInfo(
    val name: String,
    val displayName: String = "",
    val description: String = "",
    val inputTokenLimit: Int? = null,
    val outputTokenLimit: Int? = null
) {
    fun shortName(): String = name.removePrefix("models/")
}