package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PersistedCrashReport(
    val id: String,
    val timestampIso: String,
    val timestampMs: Long,
    val crashType: String,
    val threadName: String = "",
    val activeStage: String = "Idle",
    val exceptionClass: String = "",
    val exceptionMessage: String = "",
    val stackTrace: String = "",
    val deviceInfo: String = "",
    val memoryInfo: String = "",
    val appVersion: String = "",
    val providerAndModel: String = "",
    val executionLogsTail: List<String> = emptyList(),
    val liveStreamTail: String = "",
    val blueprintJson: String = "",
    val toolsCode: String = "",
    val pipelineCode: String = ""
) {
    fun toFormattedText(): String = buildString {
        appendLine("=== PERSISTED CRASH & RECOVERY REPORT ===")
        appendLine("Crash ID: $id")
        appendLine("Time: $timestampIso")
        appendLine("Type: $crashType")
        if (threadName.isNotBlank()) appendLine("Thread: $threadName")
        if (activeStage.isNotBlank()) appendLine("Active Stage: $activeStage")
        if (providerAndModel.isNotBlank()) appendLine("Provider/Model: $providerAndModel")
        if (appVersion.isNotBlank()) appendLine("App Version: $appVersion")
        if (deviceInfo.isNotBlank()) appendLine("Device: $deviceInfo")
        if (memoryInfo.isNotBlank()) appendLine("Memory: $memoryInfo")
        if (exceptionClass.isNotBlank() || exceptionMessage.isNotBlank()) {
            appendLine("Error: $exceptionClass: $exceptionMessage")
        }
        if (stackTrace.isNotBlank()) {
            appendLine()
            appendLine("--- STACK TRACE / OS EXIT TRACE ---")
            appendLine(stackTrace)
        }
        if (executionLogsTail.isNotEmpty()) {
            appendLine()
            appendLine("--- LAST PRE-CRASH EXECUTION LOGS (${executionLogsTail.size} lines) ---")
            appendLine(executionLogsTail.joinToString("\n"))
        }
        if (liveStreamTail.isNotBlank()) {
            appendLine()
            appendLine("--- LAST PRE-CRASH AI STREAM ---")
            appendLine(liveStreamTail)
        }
        if (pipelineCode.isNotBlank()) {
            appendLine()
            appendLine("--- LAST PRE-CRASH PIPELINE JS ---")
            appendLine(pipelineCode)
        }
    }.trimEnd()
}

@Serializable
data class SessionDiagnosticSnapshot(
    val activeStage: String = "Idle",
    val inProgress: Boolean = false,
    val providerAndModel: String = "",
    val executionLogs: List<String> = emptyList(),
    val liveStreamText: String = "",
    val blueprintJsonText: String = "",
    val toolsCodeText: String = "",
    val pipelineCodeText: String = "",
    val savedAtMs: Long = 0L
)