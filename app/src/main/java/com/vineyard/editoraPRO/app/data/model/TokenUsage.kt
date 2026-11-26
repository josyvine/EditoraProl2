package com.vineyard.editoraPRO.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class UsageStage(val label: String) {
    REFERENCE_ANALYSIS("Reference Analysis (Stage 1)"),
    EDIT_GENERATION("Edit Generation (Stage 2)"),
    TECHNICAL_FIX("Technical Auto-Fix"),
    VISUAL_QA("Visual QA / Auto-Fix")
}

@Serializable
data class TokenUsageSnapshot(
    val promptTokens: Int = 0,
    val candidatesTokens: Int = 0,
    val thoughtsTokens: Int = 0,
    val cachedTokens: Int = 0,
    val totalTokens: Int = 0
) {
    val billableOutputTokens: Int
        get() = (candidatesTokens + thoughtsTokens).coerceAtLeast(0)

    val effectiveTotalTokens: Int
        get() = if (totalTokens > 0) totalTokens else (promptTokens + candidatesTokens + thoughtsTokens).coerceAtLeast(0)

    val hasData: Boolean
        get() = promptTokens > 0 || candidatesTokens > 0 || thoughtsTokens > 0 || totalTokens > 0
}

@Serializable
data class ModelPricingRule(
    val modelId: String,
    val inputPer1M: Double,
    val outputPer1M: Double,
    val inputOver200kPer1M: Double? = null,
    val outputOver200kPer1M: Double? = null,
    val audioInputPer1M: Double? = null,
    val freeTierRpm: Int? = null,
    val freeTierRpd: Int? = null,
    val isCustomOverride: Boolean = false
) {
    val hasTieredPricing: Boolean
        get() = inputOver200kPer1M != null || outputOver200kPer1M != null
}

@Serializable
data class SectionUsageRecord(
    val id: String,
    val stage: UsageStage,
    val modelName: String,
    val providerLabel: String,
    val promptTokens: Int,
    val candidatesTokens: Int,
    val thoughtsTokens: Int,
    val cachedTokens: Int = 0,
    val totalTokens: Int,
    val inputCostUsd: Double,
    val outputCostUsd: Double,
    val totalCostUsd: Double,
    val isPartialFailure: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class StageUsageSummary(
    val stage: UsageStage,
    val callCount: Int,
    val promptTokens: Int,
    val candidatesTokens: Int,
    val thoughtsTokens: Int,
    val totalTokens: Int,
    val totalCostUsd: Double
)

data class EditCostSummary(
    val byStage: List<StageUsageSummary> = emptyList(),
    val totalCalls: Int = 0,
    val totalPromptTokens: Int = 0,
    val totalCandidatesTokens: Int = 0,
    val totalThoughtsTokens: Int = 0,
    val totalTokens: Int = 0,
    val totalInputCostUsd: Double = 0.0,
    val totalOutputCostUsd: Double = 0.0,
    val totalCostUsd: Double = 0.0
) {
    companion object {
        fun fromRecords(records: List<SectionUsageRecord>): EditCostSummary {
            if (records.isEmpty()) return EditCostSummary()
            val byStage = UsageStage.entries.mapNotNull { stage ->
                val stageRecords = records.filter { it.stage == stage }
                if (stageRecords.isEmpty()) null
                else StageUsageSummary(
                    stage = stage,
                    callCount = stageRecords.size,
                    promptTokens = stageRecords.sumOf { it.promptTokens },
                    candidatesTokens = stageRecords.sumOf { it.candidatesTokens },
                    thoughtsTokens = stageRecords.sumOf { it.thoughtsTokens },
                    totalTokens = stageRecords.sumOf { it.totalTokens },
                    totalCostUsd = stageRecords.sumOf { it.totalCostUsd }
                )
            }
            return EditCostSummary(
                byStage = byStage,
                totalCalls = records.size,
                totalPromptTokens = records.sumOf { it.promptTokens },
                totalCandidatesTokens = records.sumOf { it.candidatesTokens },
                totalThoughtsTokens = records.sumOf { it.thoughtsTokens },
                totalTokens = records.sumOf { it.totalTokens },
                totalInputCostUsd = records.sumOf { it.inputCostUsd },
                totalOutputCostUsd = records.sumOf { it.outputCostUsd },
                totalCostUsd = records.sumOf { it.totalCostUsd }
            )
        }
    }
}