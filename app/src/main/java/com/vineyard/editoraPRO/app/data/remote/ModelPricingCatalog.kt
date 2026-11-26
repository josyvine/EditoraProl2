package com.vineyard.editoraPRO.app.data.remote

import com.vineyard.editoraPRO.app.data.model.ModelPricingRule
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import java.util.Locale

data class CostCalculation(
    val rule: ModelPricingRule,
    val appliedInputPer1M: Double,
    val appliedOutputPer1M: Double,
    val usedOver200kTier: Boolean,
    val inputCostUsd: Double,
    val outputCostUsd: Double,
    val totalCostUsd: Double,
    val isKnownModelRate: Boolean
)

/**
 * Official Gemini token pricing & free-tier rate-limit catalog,
 * with support for user-editable custom overrides per model.
 */
object ModelPricingCatalog {

    val DEFAULT_RULES: List<ModelPricingRule> = listOf(
        // Gemini 4 family
        ModelPricingRule(
            modelId = "gemini-4-neon",
            inputPer1M = 3.00,
            outputPer1M = 15.00,
            freeTierRpm = 0,
            freeTierRpd = 0
        ),
        ModelPricingRule(
            modelId = "gemini-4-carbon",
            inputPer1M = 2.00,
            outputPer1M = 10.00,
            freeTierRpm = 0,
            freeTierRpd = 0
        ),
        ModelPricingRule(
            modelId = "gemini-4-argon",
            inputPer1M = 2.00,
            outputPer1M = 10.00,
            freeTierRpm = 0,
            freeTierRpd = 0
        ),
        ModelPricingRule(
            modelId = "gemini-4-hydrogen",
            inputPer1M = 1.50,
            outputPer1M = 7.50,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),

        // Gemini 3.x family
        ModelPricingRule(
            modelId = "gemini-3.8-flash",
            inputPer1M = 0.75,
            outputPer1M = 3.75,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),
        ModelPricingRule(
            modelId = "gemini-3.6-flash",
            inputPer1M = 0.75,
            outputPer1M = 3.75,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),
        ModelPricingRule(
            modelId = "gemini-3.5-flash-lite",
            inputPer1M = 0.30,
            outputPer1M = 2.50,
            freeTierRpm = 15,
            freeTierRpd = 500
        ),
        ModelPricingRule(
            modelId = "gemini-3.5-flash",
            inputPer1M = 0.75,
            outputPer1M = 3.75,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),
        ModelPricingRule(
            modelId = "gemini-3.1-pro-preview",
            inputPer1M = 2.00,
            outputPer1M = 12.00,
            inputOver200kPer1M = 4.00,
            outputOver200kPer1M = 18.00,
            freeTierRpm = 0,
            freeTierRpd = 0
        ),
        ModelPricingRule(
            modelId = "gemini-3.1-flash-lite-preview",
            inputPer1M = 0.25,
            outputPer1M = 1.50,
            audioInputPer1M = 0.50,
            freeTierRpm = 15,
            freeTierRpd = 500
        ),
        ModelPricingRule(
            modelId = "gemini-3.1-flash-lite",
            inputPer1M = 0.25,
            outputPer1M = 1.50,
            audioInputPer1M = 0.50,
            freeTierRpm = 15,
            freeTierRpd = 500
        ),
        ModelPricingRule(
            modelId = "gemini-3-flash-preview",
            inputPer1M = 0.50,
            outputPer1M = 3.00,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),
        ModelPricingRule(
            modelId = "gemini-decision-1",
            inputPer1M = 0.10,
            outputPer1M = 0.10,
            freeTierRpm = 200,
            freeTierRpd = 10_000
        ),

        // Gemini 2.5 family
        ModelPricingRule(
            modelId = "gemini-2.5-pro",
            inputPer1M = 1.25,
            outputPer1M = 10.00,
            inputOver200kPer1M = 2.50,
            outputOver200kPer1M = 15.00,
            freeTierRpm = 0,
            freeTierRpd = 0
        ),
        ModelPricingRule(
            modelId = "gemini-2.5-flash",
            inputPer1M = 0.30,
            outputPer1M = 2.50,
            freeTierRpm = 5,
            freeTierRpd = 20
        ),
        ModelPricingRule(
            modelId = "gemini-2.5-flash-lite",
            inputPer1M = 0.10,
            outputPer1M = 0.40,
            freeTierRpm = 10,
            freeTierRpd = 20
        )
    )

    fun normalizeModelId(modelName: String): String =
        modelName.removePrefix("models/").trim().lowercase(Locale.US)

    fun resolveRule(
        modelName: String,
        customOverrides: Map<String, ModelPricingRule> = emptyMap()
    ): Pair<ModelPricingRule, Boolean> {
        val norm = normalizeModelId(modelName)
        if (norm.isBlank()) {
            return ModelPricingRule(modelId = "unknown", inputPer1M = 0.0, outputPer1M = 0.0) to false
        }

        customOverrides[norm]?.let { override ->
            return override.copy(modelId = norm, isCustomOverride = true) to true
        }

        DEFAULT_RULES.firstOrNull { it.modelId == norm }?.let { return it to true }

        // Match preview/dated aliases (e.g. gemini-3.1-pro -> gemini-3.1-pro-preview, gemini-3.5-flash-lite-001)
        DEFAULT_RULES.firstOrNull { rule ->
            norm.startsWith(rule.modelId) || rule.modelId.startsWith(norm)
        }?.let { return it to true }

        return ModelPricingRule(modelId = norm, inputPer1M = 0.0, outputPer1M = 0.0) to false
    }

    fun calculateCost(
        modelName: String,
        usage: TokenUsageSnapshot,
        customOverrides: Map<String, ModelPricingRule> = emptyMap(),
        estimateCost: Boolean = true
    ): CostCalculation {
        val (rule, known) = resolveRule(modelName, customOverrides)
        val over200k = usage.promptTokens > 200_000 && rule.hasTieredPricing
        val inputRate = if (over200k) (rule.inputOver200kPer1M ?: rule.inputPer1M) else rule.inputPer1M
        val outputRate = if (over200k) (rule.outputOver200kPer1M ?: rule.outputPer1M) else rule.outputPer1M

        val rawInputCost = (usage.promptTokens.coerceAtLeast(0) / 1_000_000.0) * inputRate
        // Google bills thinking tokens (thoughtsTokenCount) at the output token rate
        val rawOutputCost = (usage.billableOutputTokens / 1_000_000.0) * outputRate

        val inCost = if (estimateCost) rawInputCost else 0.0
        val outCost = if (estimateCost) rawOutputCost else 0.0

        return CostCalculation(
            rule = rule,
            appliedInputPer1M = inputRate,
            appliedOutputPer1M = outputRate,
            usedOver200kTier = over200k,
            inputCostUsd = inCost,
            outputCostUsd = outCost,
            totalCostUsd = inCost + outCost,
            isKnownModelRate = known
        )
    }

    fun formatUsd(usd: Double): String = when {
        usd <= 0.0 -> "$0.0000"
        usd < 0.01 -> String.format(Locale.US, "$%.6f", usd)
        usd < 1.0 -> String.format(Locale.US, "$%.4f", usd)
        else -> String.format(Locale.US, "$%.2f", usd)
    }

    fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
        tokens >= 10_000 -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
        else -> String.format(Locale.US, "%,d", tokens)
    }
}