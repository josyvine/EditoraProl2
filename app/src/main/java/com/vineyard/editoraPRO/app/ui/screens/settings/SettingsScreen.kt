package com.vineyard.editoraPRO.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vineyard.editoraPRO.app.data.model.ApiKeyEntry
import com.vineyard.editoraPRO.app.data.model.AppState
import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.remote.ModelPricingCatalog
import com.vineyard.editoraPRO.app.ui.components.CardStepHeader
import com.vineyard.editoraPRO.app.ui.components.EditoraButton
import com.vineyard.editoraPRO.app.ui.components.EditoraButtonTone
import com.vineyard.editoraPRO.app.ui.components.EditoraCard
import com.vineyard.editoraPRO.app.ui.components.EditoraTextField
import com.vineyard.editoraPRO.app.ui.components.PillTone
import com.vineyard.editoraPRO.app.ui.components.StatusPill
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccent
import com.vineyard.editoraPRO.app.ui.theme.EditoraAccentCyan
import com.vineyard.editoraPRO.app.ui.theme.EditoraBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraBorder
import com.vineyard.editoraPRO.app.ui.theme.EditoraDangerText
import com.vineyard.editoraPRO.app.ui.theme.EditoraMonoStyle
import com.vineyard.editoraPRO.app.ui.theme.EditoraSuccessGreen
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface
import com.vineyard.editoraPRO.app.ui.theme.EditoraSurface2
import com.vineyard.editoraPRO.app.ui.theme.EditoraTerminalBg
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextMuted
import com.vineyard.editoraPRO.app.ui.theme.EditoraTextPrimary
import com.vineyard.editoraPRO.app.ui.theme.EditoraWarn

@Composable
fun SettingsScreen(
    state: AppState,
    onSwitchProvider: (ProviderMode) -> Unit,
    onAddApiKey: (String) -> Unit,
    onRemoveApiKey: (Int) -> Unit,
    onFetchLiveModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectThinkingLevel: (ThinkingLevel) -> Unit,
    onSelectExportFps: (Int) -> Unit,
    onSelectMaxCanvasEdge: (Int) -> Unit,
    onSelectSettingsPage: (Int) -> Unit = {},
    onEnableApiKey: (Int) -> Unit = {},
    onToggleEstimateCostInFreeTier: (Boolean) -> Unit = {},
    onSavePricingOverride: (modelId: String, inputPer1M: Double, outputPer1M: Double, inputOver200k: Double?, outputOver200k: Double?) -> Unit = { _, _, _, _, _ -> },
    onResetPricingOverride: (String) -> Unit = {},
    onClearEditUsage: () -> Unit = {},
    onResetLifetimeUsage: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var apiKeyInput by remember { mutableStateOf("") }
    val nextKeyIdx = remember(state.apiKeys, state.cycleIdx) {
        if (state.apiKeys.isEmpty()) -1
        else {
            val start = state.cycleIdx % state.apiKeys.size
            (0 until state.apiKeys.size)
                .map { (start + it) % state.apiKeys.size }
                .firstOrNull { !state.apiKeys[it].isDisabled } ?: -1
        }
    }
    val currentPage = state.settingsSubPage.coerceIn(1, 3)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SettingsSubPageBar(
            currentPage = currentPage,
            hasKeys = state.apiKeys.isNotEmpty(),
            hasModel = state.selectedModel.isNotBlank(),
            hasUsage = state.usageRecords.isNotEmpty() || state.lifetimeTotalTokens > 0L,
            onSelectPage = onSelectSettingsPage
        )

        when (currentPage) {
            1 -> {
                // Page 1: API Provider Mode + Gemini API Keys (Auto-Cycle)
                EditoraCard {
                    CardStepHeader(
                        stepNumber = null,
                        title = "API Provider Mode",
                        pillText = state.apiProvider.shortLabel,
                        pillTone = PillTone.READY,
                        pillTag = "provider_badge"
                    )
                    Text(
                        text = "Choose between Vertex AI (Gemini Platform) or AI Studio (v1beta). Each mode preserves its own API key rotation pool, model list, upload pipeline, and Visual QA behavior.",
                        color = EditoraTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    EditoraDropdown(
                        selectedLabel = state.apiProvider.fullLabel,
                        options = ProviderMode.entries.map { it to it.fullLabel },
                        onSelect = onSwitchProvider,
                        testTag = "provider_select"
                    )
                }

                EditoraCard {
                    CardStepHeader(
                        stepNumber = null,
                        title = "Gemini API Keys (Auto-Cycle)",
                        pillText = "${state.apiKeys.size} key${if (state.apiKeys.size == 1) "" else "s"}",
                        pillTone = if (state.apiKeys.isNotEmpty()) PillTone.READY else PillTone.NEUTRAL,
                        pillTag = "cycle_status_badge"
                    )
                    Text(
                        text = if (state.apiProvider == ProviderMode.VERTEX_AI) {
                            "Add multiple Gemini Platform API keys. Each request automatically rotates to the next key in sequence to avoid single-key rate limits."
                        } else {
                            "Add multiple AI Studio API keys. Each request automatically rotates keys in sequence across v1beta endpoints."
                        },
                        color = EditoraTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EditoraTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            placeholder = if (state.apiProvider == ProviderMode.VERTEX_AI) {
                                "Paste Gemini Platform API Key"
                            } else {
                                "Paste AI Studio API Key"
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            testTag = "api_key_input",
                            modifier = Modifier.weight(1f)
                        )
                        EditoraButton(
                            text = "Add",
                            onClick = {
                                onAddApiKey(apiKeyInput)
                                if (apiKeyInput.trim().length >= 10) {
                                    apiKeyInput = ""
                                }
                            },
                            testTag = "add_key_button"
                        )
                    }

                    if (state.apiKeys.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_list"),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            state.apiKeys.forEachIndexed { index, entry ->
                                ApiKeyRow(
                                    entry = entry,
                                    isNext = index == nextKeyIdx,
                                    index = index,
                                    onEnable = { onEnableApiKey(index) },
                                    onRemove = { onRemoveApiKey(index) }
                                )
                            }
                        }
                    }
                }

                EditoraButton(
                    text = "Next: Models & Render →",
                    onClick = { onSelectSettingsPage(2) },
                    tone = EditoraButtonTone.SECONDARY,
                    testTag = "settings_next_btn",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            2 -> {
                // Page 2: Live Server Models (+ Thinking Level) + Render Preferences
                EditoraCard {
                    CardStepHeader(
                        stepNumber = null,
                        title = "Live Server Models",
                        pillText = if (state.models.isEmpty()) "Not fetched" else "${state.models.size} live",
                        pillTone = if (state.models.isNotEmpty()) PillTone.READY else PillTone.NEUTRAL,
                        pillTag = "model_count_badge"
                    )
                    Text(
                        text = if (state.apiProvider == ProviderMode.VERTEX_AI) {
                            "Tap the fetch icon to verify available models directly against Gemini Platform (lunar-tag/global). Only your selected model is used."
                        } else {
                            "Tap the fetch icon to verify available models directly against Google AI Studio (v1beta). Only your selected model is used."
                        },
                        color = EditoraTextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val modelLabel = state.selectedModel.removePrefix("models/")
                            .ifBlank { "-- Fetch live models first --" }
                        EditoraDropdown(
                            selectedLabel = modelLabel,
                            options = state.models.map { it.name to it.shortName() },
                            onSelect = onSelectModel,
                            enabled = state.models.isNotEmpty(),
                            testTag = "model_select",
                            modifier = Modifier.weight(1f)
                        )

                        val infiniteTransition = rememberInfiniteTransition(label = "fetch_spin")
                        val spinAngle by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 850, easing = LinearEasing),
                                repeatMode = RepeatMode.Restart
                            ),
                            label = "spin_deg"
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = EditoraSurface2,
                            border = BorderStroke(1.dp, EditoraBorder)
                        ) {
                            IconButton(
                                onClick = onFetchLiveModels,
                                enabled = !state.isFetchingModels,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("fetch_models_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = "Fetch live models from server",
                                    tint = EditoraAccentCyan,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .rotate(if (state.isFetchingModels) spinAngle else 0f)
                                )
                            }
                        }
                    }

                    val selectedInfo = state.models.firstOrNull { it.name == state.selectedModel }
                    AnimatedVisibility(visible = selectedInfo != null) {
                        if (selectedInfo != null) {
                            val (rateRule, knownRate) = ModelPricingCatalog.resolveRule(
                                selectedInfo.name,
                                state.customPricingOverrides
                            )
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("model_info_box"),
                                shape = RoundedCornerShape(10.dp),
                                color = EditoraTerminalBg,
                                border = BorderStroke(1.dp, EditoraBorder)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    if (selectedInfo.displayName.isNotBlank()) {
                                        Text(
                                            text = selectedInfo.displayName,
                                            color = EditoraTextPrimary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (selectedInfo.description.isNotBlank()) {
                                        Text(
                                            text = selectedInfo.description,
                                            color = EditoraTextMuted,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp
                                        )
                                    }
                                    if (selectedInfo.inputTokenLimit != null || selectedInfo.outputTokenLimit != null) {
                                        Text(
                                            text = "Limits: in=${selectedInfo.inputTokenLimit ?: "?"} • out=${selectedInfo.outputTokenLimit ?: "?"}",
                                            style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                                            color = EditoraAccentCyan
                                        )
                                    }
                                    val quotaText = when {
                                        rateRule.freeTierRpm == null -> null
                                        rateRule.freeTierRpm == 0 -> "Free Tier: Paid only (0/0)"
                                        else -> "Free Tier: ${rateRule.freeTierRpm} RPM • ${rateRule.freeTierRpd ?: "?"} RPD"
                                    }
                                    val priceText = if (knownRate) {
                                        "Rate: ${ModelPricingCatalog.formatUsd(rateRule.inputPer1M)}/1M in • ${ModelPricingCatalog.formatUsd(rateRule.outputPer1M)}/1M out"
                                    } else {
                                        "Rate: Unlisted (edit in 3. Pricing)"
                                    }
                                    Text(
                                        text = if (quotaText != null) "$priceText | $quotaText" else priceText,
                                        style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                                        color = EditoraSuccessGreen
                                    )
                                }
                            }
                        }
                    }

                    AnimatedVisibility(visible = state.apiProvider == ProviderMode.AI_STUDIO && state.selectedModel.isNotBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("thinking_level_wrap"),
                            shape = RoundedCornerShape(12.dp),
                            color = EditoraAccent.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, EditoraAccent.copy(alpha = 0.22f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Reasoning / Thinking Level (AI Studio)",
                                        color = EditoraAccentCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StatusPill(
                                        text = state.thinkingLevel.badgeLabel,
                                        tone = PillTone.NEXT,
                                        modifier = Modifier.testTag("thinking_level_badge")
                                    )
                                }
                                EditoraDropdown(
                                    selectedLabel = state.thinkingLevel.label,
                                    options = ThinkingLevel.entries.map { it to it.label },
                                    onSelect = onSelectThinkingLevel,
                                    testTag = "thinking_level_select"
                                )
                                Text(
                                    text = "Applies thinkingLevel (Gemini 3 / 3.5 / 4) or mapped thinkingBudget (Gemini 2.5) automatically for the selected AI Studio model.",
                                    color = EditoraTextMuted,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }

                EditoraCard {
                    Text(
                        text = "Render Preferences",
                        color = EditoraTextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = "Export FPS", color = EditoraTextMuted, fontSize = 12.sp)
                            EditoraDropdown(
                                selectedLabel = if (state.exportFps == 24) "24 fps (Cinematic)" else "30 fps (Smooth)",
                                options = listOf(24 to "24 fps (Cinematic)", 30 to "30 fps (Smooth)"),
                                onSelect = onSelectExportFps,
                                testTag = "fps_select"
                            )
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(text = "Max Canvas Edge", color = EditoraTextMuted, fontSize = 12.sp)
                            EditoraDropdown(
                                selectedLabel = if (state.maxCanvasEdge == 720) "720p (Fast Mobile)" else "1080p (HD)",
                                options = listOf(720 to "720p (Fast Mobile)", 1080 to "1080p (HD)"),
                                onSelect = onSelectMaxCanvasEdge,
                                testTag = "res_select"
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EditoraButton(
                        text = "← Keys",
                        onClick = { onSelectSettingsPage(1) },
                        tone = EditoraButtonTone.SECONDARY,
                        testTag = "settings_prev_btn",
                        modifier = Modifier.weight(1f)
                    )
                    EditoraButton(
                        text = "Next: Pricing →",
                        onClick = { onSelectSettingsPage(3) },
                        tone = EditoraButtonTone.SECONDARY,
                        testTag = "settings_next_btn",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            else -> {
                // Page 3: Token Usage & Model Pricing
                PricingSettingsPage(
                    state = state,
                    onToggleEstimateCostInFreeTier = onToggleEstimateCostInFreeTier,
                    onSavePricingOverride = onSavePricingOverride,
                    onResetPricingOverride = onResetPricingOverride,
                    onClearEditUsage = onClearEditUsage,
                    onResetLifetimeUsage = onResetLifetimeUsage,
                    onPrevious = { onSelectSettingsPage(2) }
                )
            }
        }
    }
}

@Composable
private fun PricingSettingsPage(
    state: AppState,
    onToggleEstimateCostInFreeTier: (Boolean) -> Unit,
    onSavePricingOverride: (String, Double, Double, Double?, Double?) -> Unit,
    onResetPricingOverride: (String) -> Unit,
    onClearEditUsage: () -> Unit,
    onResetLifetimeUsage: () -> Unit,
    onPrevious: () -> Unit
) {
    val activeModelNorm = ModelPricingCatalog.normalizeModelId(
        state.selectedModel.ifBlank { "gemini-3.5-flash-lite" }
    )
    var editingModelId by remember(activeModelNorm) { mutableStateOf(activeModelNorm) }
    val (editingRule, isKnown) = ModelPricingCatalog.resolveRule(editingModelId, state.customPricingOverrides)

    var inputRateStr by remember(editingModelId, state.customPricingOverrides) {
        mutableStateOf(editingRule.inputPer1M.toString())
    }
    var outputRateStr by remember(editingModelId, state.customPricingOverrides) {
        mutableStateOf(editingRule.outputPer1M.toString())
    }
    LaunchedEffect(editingModelId, editingRule) {
        inputRateStr = editingRule.inputPer1M.toString()
        outputRateStr = editingRule.outputPer1M.toString()
    }

    val summary = state.editCostSummary

    // Card 1: Current Edit Per-Section Breakdown + Lifetime Summary
    EditoraCard {
        CardStepHeader(
            stepNumber = null,
            title = "Edit Token & Cost Ledger",
            pillText = ModelPricingCatalog.formatUsd(summary.totalCostUsd),
            pillTone = if (summary.totalTokens > 0) PillTone.READY else PillTone.NEUTRAL,
            pillTag = "pricing_edit_total_pill"
        )
        Text(
            text = "Exact token consumption captured from Gemini API usageMetadata per section (thinking tokens are billed at output rate).",
            color = EditoraTextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Estimate Paid USD Rates",
                    color = EditoraTextPrimary,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (state.estimateCostInFreeTier) {
                        "Calculating equivalent USD cost using catalog/custom rates"
                    } else {
                        "Free-tier zero mode ($0.0000); token counts still tracked"
                    },
                    color = EditoraTextMuted,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = state.estimateCostInFreeTier,
                onCheckedChange = onToggleEstimateCostInFreeTier,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = EditoraBg,
                    checkedTrackColor = EditoraAccentCyan
                )
            )
        }

        if (summary.byStage.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = EditoraTerminalBg,
                border = BorderStroke(1.dp, EditoraBorder)
            ) {
                Text(
                    text = "No API calls recorded for the current edit yet. Run Reference Analysis or Build AI Edit to see per-section token & cost breakdown.",
                    color = EditoraTextMuted,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                summary.byStage.forEach { sec ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = EditoraTerminalBg,
                        border = BorderStroke(1.dp, EditoraBorder)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${sec.stage.label} (${sec.callCount}x)",
                                    color = EditoraTextPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = ModelPricingCatalog.formatUsd(sec.totalCostUsd),
                                    style = EditoraMonoStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold),
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
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = EditoraAccent.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, EditoraAccent.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Current Edit Total (${summary.totalCalls} call(s))",
                        color = EditoraTextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${ModelPricingCatalog.formatTokens(summary.totalTokens)} tok • ${ModelPricingCatalog.formatUsd(summary.totalCostUsd)}",
                        style = EditoraMonoStyle.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = EditoraAccentCyan
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Lifetime Accumulated",
                        color = EditoraTextMuted,
                        fontSize = 11.5.sp
                    )
                    Text(
                        text = "${ModelPricingCatalog.formatTokens(state.lifetimeTotalTokens.toInt().coerceAtLeast(0))} tok • ${ModelPricingCatalog.formatUsd(state.lifetimeTotalCostUsd)}",
                        style = EditoraMonoStyle.copy(fontSize = 11.sp),
                        color = EditoraTextPrimary
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditoraButton(
                text = "Clear Edit",
                onClick = onClearEditUsage,
                tone = EditoraButtonTone.SECONDARY,
                enabled = summary.totalCalls > 0,
                testTag = "pricing_clear_edit_btn",
                modifier = Modifier.weight(1f)
            )
            EditoraButton(
                text = "Reset Lifetime",
                onClick = onResetLifetimeUsage,
                tone = EditoraButtonTone.DANGER,
                enabled = state.lifetimeTotalTokens > 0L,
                testTag = "pricing_reset_lifetime_btn",
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Card 2: Model Rate Editor & Catalog
    EditoraCard {
        CardStepHeader(
            stepNumber = null,
            title = "Model Pricing ($ / 1M Tokens)",
            pillText = if (editingRule.isCustomOverride) "Custom" else if (isKnown) "Official" else "Unlisted",
            pillTone = if (editingRule.isCustomOverride) PillTone.NEXT else if (isKnown) PillTone.READY else PillTone.NEUTRAL
        )
        Text(
            text = "Gemini APIs do not publish pricing dynamically. Built-in rates match Google AI Studio; you can edit and save overrides per model anytime.",
            color = EditoraTextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )

        val candidateModelIds = remember(state.models, state.selectedModel) {
            val fromCatalog = ModelPricingCatalog.DEFAULT_RULES.map { it.modelId }
            val fromServer = state.models.map { ModelPricingCatalog.normalizeModelId(it.name) }
            val current = ModelPricingCatalog.normalizeModelId(state.selectedModel)
            (listOfNotNull(current.takeIf { it.isNotBlank() }) + fromServer + fromCatalog).distinct()
        }
        EditoraDropdown(
            selectedLabel = editingModelId,
            options = candidateModelIds.map { it to it },
            onSelect = { editingModelId = it },
            testTag = "pricing_model_select"
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Input $/1M", color = EditoraTextMuted, fontSize = 11.5.sp)
                EditoraTextField(
                    value = inputRateStr,
                    onValueChange = { inputRateStr = it },
                    placeholder = "0.30",
                    testTag = "pricing_input_rate"
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Output+Think $/1M", color = EditoraTextMuted, fontSize = 11.5.sp)
                EditoraTextField(
                    value = outputRateStr,
                    onValueChange = { outputRateStr = it },
                    placeholder = "2.50",
                    testTag = "pricing_output_rate"
                )
            }
        }

        if (editingRule.hasTieredPricing) {
            Text(
                text = "Tier >200K input: ${ModelPricingCatalog.formatUsd(editingRule.inputOver200kPer1M ?: editingRule.inputPer1M)} in • ${ModelPricingCatalog.formatUsd(editingRule.outputOver200kPer1M ?: editingRule.outputPer1M)} out",
                style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                color = EditoraWarn
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EditoraButton(
                text = "Save Rate",
                onClick = {
                    val inVal = inputRateStr.trim().toDoubleOrNull() ?: editingRule.inputPer1M
                    val outVal = outputRateStr.trim().toDoubleOrNull() ?: editingRule.outputPer1M
                    onSavePricingOverride(
                        editingModelId,
                        inVal,
                        outVal,
                        editingRule.inputOver200kPer1M,
                        editingRule.outputOver200kPer1M
                    )
                },
                testTag = "pricing_save_rate_btn",
                modifier = Modifier.weight(1f)
            )
            EditoraButton(
                text = "Reset Default",
                onClick = { onResetPricingOverride(editingModelId) },
                tone = EditoraButtonTone.SECONDARY,
                enabled = editingRule.isCustomOverride,
                testTag = "pricing_reset_rate_btn",
                modifier = Modifier.weight(1f)
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModelPricingCatalog.DEFAULT_RULES.forEach { baseRule ->
                val (effRule, _) = ModelPricingCatalog.resolveRule(baseRule.modelId, state.customPricingOverrides)
                val rpmLabel = when (effRule.freeTierRpm) {
                    null -> ""
                    0 -> " • Paid"
                    else -> " • ${effRule.freeTierRpm} RPM/${effRule.freeTierRpd ?: "?"} RPD"
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editingModelId = baseRule.modelId },
                    shape = RoundedCornerShape(8.dp),
                    color = if (editingModelId == baseRule.modelId) EditoraAccent.copy(alpha = 0.14f) else EditoraTerminalBg,
                    border = BorderStroke(1.dp, EditoraBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = baseRule.modelId + if (effRule.isCustomOverride) " *" else "",
                            style = EditoraMonoStyle.copy(fontSize = 11.sp),
                            color = EditoraTextPrimary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${ModelPricingCatalog.formatUsd(effRule.inputPer1M)} / ${ModelPricingCatalog.formatUsd(effRule.outputPer1M)}$rpmLabel",
                            style = EditoraMonoStyle.copy(fontSize = 10.5.sp),
                            color = EditoraAccentCyan
                        )
                    }
                }
            }
        }
    }

    EditoraButton(
        text = "← Previous: Models & Render",
        onClick = onPrevious,
        tone = EditoraButtonTone.SECONDARY,
        testTag = "settings_prev_btn",
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsSubPageBar(
    currentPage: Int,
    hasKeys: Boolean,
    hasModel: Boolean,
    hasUsage: Boolean,
    onSelectPage: (Int) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = EditoraSurface,
        border = BorderStroke(1.dp, EditoraBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            val pages = listOf(
                Triple(1, "1. Keys", hasKeys),
                Triple(2, "2. Models", hasModel),
                Triple(3, "3. Pricing", hasUsage)
            )
            pages.forEach { (page, label, done) ->
                val active = currentPage == page
                val bg = if (active) EditoraAccent.copy(alpha = 0.18f) else Color.Transparent
                val fg = when {
                    active -> EditoraAccentCyan
                    done -> EditoraSuccessGreen
                    else -> EditoraTextMuted
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .testTag("settings_page_tab_$page")
                        .clickable(role = Role.Tab) { onSelectPage(page) }
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = fg,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyRow(
    entry: ApiKeyEntry,
    isNext: Boolean,
    index: Int,
    onEnable: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("api_key_item_$index"),
        shape = RoundedCornerShape(12.dp),
        color = when {
            entry.isDisabled -> EditoraDangerText.copy(alpha = 0.06f)
            isNext -> EditoraAccentCyan.copy(alpha = 0.06f)
            else -> EditoraSurface2
        },
        border = BorderStroke(
            width = 1.dp,
            color = when {
                entry.isDisabled -> EditoraDangerText.copy(alpha = 0.45f)
                isNext -> EditoraAccentCyan.copy(alpha = 0.5f)
                else -> EditoraBorder
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.masked(),
                        style = EditoraMonoStyle.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold),
                        color = if (entry.isDisabled) EditoraTextMuted else EditoraTextPrimary
                    )
                    if (entry.isDisabled) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(text = entry.disabledReason ?: "Disabled", tone = PillTone.WARN)
                    } else if (isNext) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusPill(text = "NEXT", tone = PillTone.NEXT)
                    }
                }
                Text(
                    text = "Requests: ${entry.requests} • Errors: ${entry.errors}",
                    color = EditoraTextMuted,
                    fontSize = 11.sp
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (entry.isDisabled) {
                    IconButton(
                        onClick = onEnable,
                        modifier = Modifier
                            .size(44.dp)
                            .testTag("enable_key_$index")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Re-enable API key ${entry.masked()}",
                            tint = EditoraAccentCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("remove_key_$index")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = "Remove API key ${entry.masked()}",
                        tint = EditoraDangerText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> EditoraDropdown(
    selectedLabel: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .testTag(testTag)
                .clickable(enabled = enabled && options.isNotEmpty(), role = Role.DropdownList) {
                    expanded = true
                },
            shape = RoundedCornerShape(12.dp),
            color = EditoraBg,
            border = BorderStroke(1.dp, EditoraBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedLabel,
                    color = if (enabled) EditoraTextPrimary else EditoraTextMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "Open selector",
                    tint = EditoraTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(EditoraSurface)
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = EditoraTextPrimary,
                            fontSize = 13.sp
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    }
                )
            }
        }
    }
}