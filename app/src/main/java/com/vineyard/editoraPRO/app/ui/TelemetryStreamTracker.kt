package com.vineyard.editoraPRO.app.ui

import com.vineyard.editoraPRO.app.data.model.ProcessMonitorState
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages live streaming counters (elapsed timer, received chars, [THOUGHT] / [LOG] parsing)
 * mirroring createProcessMonitor() from the web engine.
 */
class TelemetryStreamTracker(
    private val onMonitorUpdate: (ProcessMonitorState) -> Unit,
    private val onLiveStreamUpdate: (text: String, status: ProcessStatus) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var startTimeMs = 0L
    private var timerJob: Job? = null
    private var currentState = ProcessMonitorState()

    private val thoughtBuf = StringBuilder()
    private val streamBuf = StringBuilder()
    private var thoughtLoggedHeader = false
    private var logLineCarry = ""
    private val seenLogLines = HashSet<String>()
    private val feedLines = ArrayDeque<String>()

    fun start(scope: CoroutineScope, initialStatus: String = "Preparing...") {
        stop()
        startTimeMs = System.currentTimeMillis()
        thoughtBuf.clear()
        streamBuf.clear()
        thoughtLoggedHeader = false
        logLineCarry = ""
        seenLogLines.clear()
        feedLines.clear()
        feedLines.add("Waiting for stream...")

        currentState = ProcessMonitorState(
            visible = true,
            status = ProcessStatus.RUNNING,
            statusText = initialStatus,
            elapsedMs = 0L,
            charCount = 0,
            progress = 0.08f,
            liveFeedLines = feedLines.toList(),
            showSwitchModelButton = false
        )
        onMonitorUpdate(currentState)
        onLiveStreamUpdate("Waiting for AI response...", ProcessStatus.RUNNING)

        timerJob = scope.launch {
            while (isActive && currentState.status == ProcessStatus.RUNNING) {
                currentState = currentState.copy(elapsedMs = System.currentTimeMillis() - startTimeMs)
                onMonitorUpdate(currentState)
                delay(250L)
            }
        }
    }

    fun resetStreamPhase() {
        thoughtBuf.clear()
        streamBuf.clear()
        thoughtLoggedHeader = false
        logLineCarry = ""
        feedLines.clear()
        feedLines.add("Waiting for stream...")
        if (currentState.status == ProcessStatus.RUNNING) {
            currentState = currentState.copy(
                charCount = 0,
                elapsedMs = System.currentTimeMillis() - startTimeMs,
                liveFeedLines = feedLines.toList()
            )
            onMonitorUpdate(currentState)
            onLiveStreamUpdate("Waiting for AI response...", ProcessStatus.RUNNING)
        }
    }

    fun setStatus(text: String, progress: Float? = null) {
        currentState = currentState.copy(
            statusText = text,
            progress = progress?.coerceIn(0f, 1f) ?: currentState.progress,
            elapsedMs = System.currentTimeMillis() - startTimeMs
        )
        onMonitorUpdate(currentState)
    }

    fun setFeedMessage(msg: String) {
        feedLines.clear()
        feedLines.add(msg)
        currentState = currentState.copy(liveFeedLines = feedLines.toList())
        onMonitorUpdate(currentState)
    }

    fun appendToken(chunk: String, isThought: Boolean) {
        if (isThought) {
            thoughtBuf.append(chunk)
        } else {
            streamBuf.append(chunk)
            logLineCarry += chunk
            val parts = logLineCarry.split(Regex("\\r?\\n"))
            logLineCarry = parts.lastOrNull().orEmpty()
            for (i in 0 until parts.size - 1) {
                val clean = parts[i].trim()
                if (clean.startsWith("[LOG]") && seenLogLines.add(clean)) {
                    onLog(clean.replaceFirst("[LOG]", "[AI]").trim())
                }
            }
        }

        val totalChars = thoughtBuf.length + streamBuf.length
        val newProg = if (currentState.progress < 0.90f) {
            (0.46f + (totalChars / 12000f) * 0.44f).coerceIn(0.46f, 0.90f)
        } else currentState.progress

        val preview = buildString {
            if (thoughtBuf.isNotEmpty()) {
                append("[THOUGHT] ")
                append(thoughtBuf.takeLast(1600))
                append("\n\n")
            }
            if (streamBuf.isNotEmpty()) {
                append(streamBuf.takeLast(1400))
            }
        }.trim().ifEmpty { "Streaming..." }

        val miniLines = preview.lines().takeLast(6)
        feedLines.clear()
        feedLines.addAll(miniLines)

        currentState = currentState.copy(
            charCount = totalChars,
            progress = newProg,
            elapsedMs = System.currentTimeMillis() - startTimeMs,
            liveFeedLines = feedLines.toList()
        )
        onMonitorUpdate(currentState)

        val liveMonitorText = buildString {
            if (thoughtBuf.isNotEmpty()) {
                append("[THINKING STREAM]\n")
                append(thoughtBuf.takeLast(5000))
                append("\n\n")
            }
            if (streamBuf.isNotEmpty()) {
                append("[OUTPUT STREAM]\n")
                append(streamBuf.takeLast(6000))
            }
        }.trim().ifEmpty { "Waiting for AI response..." }
        onLiveStreamUpdate(liveMonitorText, ProcessStatus.RUNNING)

        if (!isThought && thoughtBuf.isNotEmpty() && !thoughtLoggedHeader) {
            thoughtLoggedHeader = true
            val snippet = thoughtBuf.toString().replace(Regex("\\s+"), " ").trim().take(420)
            onLog("[AI THOUGHT SUMMARY] $snippet${if (thoughtBuf.length > 420) "..." else ""}")
        }
    }

    fun finish(message: String, success: Boolean, showSwitchModel: Boolean = false) {
        stop()
        currentState = currentState.copy(
            visible = true,
            status = if (success) ProcessStatus.SUCCESS else ProcessStatus.ERROR,
            statusText = message,
            progress = if (success) 1f else currentState.progress,
            elapsedMs = System.currentTimeMillis() - startTimeMs,
            showSwitchModelButton = showSwitchModel
        )
        onMonitorUpdate(currentState)
        onLiveStreamUpdate(
            streamBuf.toString().ifBlank { message },
            if (success) ProcessStatus.SUCCESS else ProcessStatus.ERROR
        )
    }

    fun stop() {
        timerJob?.cancel()
        timerJob = null
    }
}