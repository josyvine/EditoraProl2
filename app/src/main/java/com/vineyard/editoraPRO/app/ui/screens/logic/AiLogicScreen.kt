package com.vineyard.editoraPRO.app.ui.screens.logic

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ProcessStatus
import com.vineyard.editoraPRO.app.data.remote.ModelPricingCatalog
import com.vineyard.editoraPRO.app.ui.components.CardStepHeader
import com.vineyard.editoraPRO.app.ui.components.CodeViewer
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraCyanStreamText
import com.vineyard.editoraPRO.app.ui.theme.EditoraDangerText
import com.vineyard.editoraPRO.app.ui.theme.EditoraMonoStyle
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccessGreen
import com.vineyard.editoraPRO.app.ui.theme.EditoraTerminalBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import com.vineyard.editoraPRO.app.ui.theme.EditoraWarn

@Composable
fun AiLogicScreen(
    state: AppState,
    onBuildReportText: () -> String,
    onWriteReportUri: () -> Uri,
    onClearExecutionLog: () -> Unit,
    onShowToast: (String) -> Unit,
    onOpenPricingSettings: () -> Unit = {},
    onClearCrashReport: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val costSummary = state.editCostSummary

    val (streamLabel, streamTone) = when (state.streamMonitorStatus) {
        ProcessStatus.RUNNING -> "Streaming" to PillTone.NEXT
        ProcessStatus.SUCCESS -> "Done" to PillTone.READY
        ProcessStatus.ERROR -> "Error" to PillTone.WARN
        ProcessStatus.IDLE -> "Idle" to PillTone.NEUTRAL
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Persisted Crash & Recovery Report Card (only shown when a crash or abnormal OS kill was recorded)
        if (state.persistedCrashReport != null) {
            val crash = state.persistedCrashReport
            EditoraCard {
                CardStepHeader(
                    stepNumber = null,
                    title = "Persisted Crash & Recovery Report",
                    pillText = crash.crashType,
                    pillTone = PillTone.WARN,
                    pillTag = "crash_report_badge"
                )
                Text(
                    text = "A crash or abnormal process termination was preserved from your previous session. All pre-crash logs, stream tokens, and synthesized code have been restored below.",
                    color = EditoraTextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = EditoraDangerText.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, EditoraDangerText.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Crash Time: ${crash.timestampIso}",
                            style = EditoraMonoStyle.copy(fontSize = 11.5.sp),
                            color = EditoraTextPrimary
                        )
                        if (crash.activeStage.isNotBlank()) {
                            Text(
                                text = "Failed Stage: ${crash.activeStage}",
                                style = EditoraMonoStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                                color = EditoraWarn
                            )
                        }
                        if (crash.threadName.isNotBlank()) {
                            Text(
                                text = "Thread: ${crash.threadName}",
                                style = EditoraMonoStyle.copy(fontSize = 11.sp),
                                color = EditoraTextMuted
                            )
                        }
                        if (crash.exceptionClass.isNotBlank() || crash.exceptionMessage.isNotBlank()) {
                            Text(
                                text = "${crash.exceptionClass}: ${crash.exceptionMessage}",
                                style = EditoraMonoStyle.copy(fontSize = 11.sp),
                                color = EditoraDangerText
                            )
                        }
                        if (crash.memoryInfo.isNotBlank()) {
                            Text(
                                text = crash.memoryInfo,
                                style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                                color = EditoraAccentCyan
                            )
                        }
                    }
                }

                if (crash.stackTrace.isNotBlank()) {
                    Text(
                        text = "Stack Trace / OS Exit Trace:",
                        color = EditoraTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    CodeViewer(
                        text = crash.stackTrace,
                        maxHeight = 220.dp,
                        testTag = "crash_stack_trace_view"
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    EditoraButton(
                        text = "Clear Crash Report",
                        onClick = onClearCrashReport,
                        tone = EditoraButtonTone.SECONDARY,
                        testTag = "clear_crash_report_btn",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        // Diagnostic Report Card
        EditoraCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Diagnostic Report",
                    color = EditoraTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EditoraButton(
                        text = "Copy All",
                        onClick = {
                            clipboard.setText(AnnotatedString(onBuildReportText()))
                            onShowToast("Diagnostic report copied!")
                        },
                        tone = EditoraButtonTone.SECONDARY,
                        testTag = "copy_report_button",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                    EditoraButton(
                        text = "Download",
                        onClick = {
                            runCatching {
                                val reportUri = onWriteReportUri()
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, reportUri)
                                    putExtra(Intent.EXTRA_SUBJECT, "editoraPRO Diagnostic Report")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    Intent.createChooser(shareIntent, "Export Diagnostic Report")
                                )
                            }.onFailure { err ->
                                onShowToast("Export report failed: ${err.message}")
                            }
                        },
                        tone = EditoraButtonTone.SECONDARY,
                        testTag = "download_report_button",
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
            Text(
                text = "Copy or download all logs, Visual QA reports, blueprint JSON, synthesized tools, and generated pipeline code in one file.",
                color = EditoraTextMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        }

        // Token Usage & Pricing Breakdown Card
        EditoraCard {
            CardStepHeader(
                stepNumber = null,
                title = "Token Usage & Cost Breakdown",
                pillText = if (costSummary.totalTokens > 0) {
                    "${ModelPricingCatalog.formatTokens(costSummary.totalTokens)} • ${ModelPricingCatalog.formatUsd(costSummary.totalCostUsd)}"
                } else {
                    "0 tok"
                },
                pillTone = if (costSummary.totalTokens > 0) PillTone.READY else PillTone.NEUTRAL,
                pillTag = "logic_cost_badge"
            )

            if (costSummary.byStage.isEmpty()) {
                Text(
                    text = "No token usage recorded for this edit yet. Run Reference Analysis or Build AI Edit to track input, output, and thinking tokens per section.",
                    color = EditoraTextMuted,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("logic_usage_breakdown"),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    costSummary.byStage.forEach { sec ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = EditoraTerminalBg,
                            border = BorderStroke(1.dp, EditoraBorder)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${sec.stage.label} (${sec.callCount}x)",
                                        color = EditoraTextPrimary,
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = ModelPricingCatalog.formatUsd(sec.totalCostUsd),
                                        style = EditoraMonoStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                                        color = EditoraSuccessGreen
                                    )
                                }
                                Text(
                                    text = "in=${ModelPricingCatalog.formatTokens(sec.promptTokens)} • out=${ModelPricingCatalog.formatTokens(sec.candidatesTokens)} • think=${ModelPricingCatalog.formatTokens(sec.thoughtsTokens)} • total=${ModelPricingCatalog.formatTokens(sec.totalTokens)}",
                                    style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                                    color = EditoraAccentCyan
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = EditoraAccent.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, EditoraAccent.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Edit Total (${costSummary.totalCalls} call(s))",
                                color = EditoraTextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${ModelPricingCatalog.formatTokens(costSummary.totalTokens)} tok • ${ModelPricingCatalog.formatUsd(costSummary.totalCostUsd)}",
                                style = EditoraMonoStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                                color = EditoraAccentCyan
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lifetime: ${ModelPricingCatalog.formatTokens(state.lifetimeTotalTokens.toInt().coerceAtLeast(0))} tok • ${ModelPricingCatalog.formatUsd(state.lifetimeTotalCostUsd)}",
                    style = EditoraMonoStyle.copy(fontSize = 11.sp),
                    color = EditoraTextMuted
                )
                EditoraButton(
                    text = "Pricing →",
                    onClick = onOpenPricingSettings,
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "logic_open_pricing_button"
                )
            }
        }

        // Live AI Stream & Thought Monitor
        EditoraCard {
            CardStepHeader(
                stepNumber = null,
                title = "Live AI Stream & Thought Monitor",
                pillText = streamLabel,
                pillTone = streamTone,
                pillTag = "stream_monitor_badge"
            )
            CodeViewer(
                text = state.liveStreamText,
                textColor = EditoraCyanStreamText,
                maxHeight = 190.dp,
                testTag = "live_stream_view"
            )
        }

        // Deep Execution Log
        EditoraCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Deep Execution Log",
                    color = EditoraTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                EditoraButton(
                    text = "Clear",
                    onClick = onClearExecutionLog,
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "clear_log_button"
                )
            }
            CodeViewer(
                text = state.executionLogs.joinToString("\n"),
                testTag = "execution_log_view"
            )
        }

        // 1. Blueprint JSON
        EditoraCard {
            Text(
                text = "1. AI Reference / Creative Blueprint (JSON)",
                color = EditoraTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            CodeViewer(
                text = state.blueprintJsonText,
                testTag = "blueprint_code_view"
            )
        }

        // 2. Tools JS
        EditoraCard {
            Text(
                text = "2. AI-Generated Internal Tools (JS)",
                color = EditoraTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            CodeViewer(
                text = state.toolsCodeText,
                testTag = "tools_code_view"
            )
        }

        // 3. Pipeline JS
        EditoraCard {
            Text(
                text = "3. AI-Generated Edit Pipeline (JS)",
                color = EditoraTextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            CodeViewer(
                text = state.pipelineCodeText,
                testTag = "pipeline_code_view"
            )
        }
    }
}