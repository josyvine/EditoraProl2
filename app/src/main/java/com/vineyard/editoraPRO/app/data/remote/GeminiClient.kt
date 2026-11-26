package com.vineyard.editoraPRO.app.data.remote

import android.util.Base64
import com.vineyard.editoraPRO.app.data.model.ApiKeyEntry
import com.vineyard.editoraPRO.app.data.model.GeminiModelInfo
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import com.vineyard.editoraPRO.app.data.model.ParsedJsonAndCode
import com.vineyard.editoraPRO.app.data.model.ProviderMode
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GeminiClient(
    private val vertexAiService: VertexAiService = VertexAiService(),
    private val aiStudioService: AiStudioService = AiStudioService()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val base64Cache = ConcurrentHashMap<String, String>()

    suspend fun fileToBase64Cached(file: File): String = withContext(Dispatchers.IO) {
        val cacheKey = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        base64Cache[cacheKey]?.let { return@withContext it }
        val bytes = file.readBytes()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        if (base64Cache.size > 6) base64Cache.clear()
        base64Cache[cacheKey] = encoded
        encoded
    }

    suspend fun buildFileMediaPart(
        file: File,
        mimeType: String,
        provider: ProviderMode,
        apiKey: String,
        onStatus: (String, Float?) -> Unit,
        onFeed: (String) -> Unit,
        onLog: (String) -> Unit
    ): GeminiPart {
        if (provider == ProviderMode.AI_STUDIO) {
            try {
                val uploaded = aiStudioService.uploadToFilesApi(
                    file = file,
                    mimeType = mimeType,
                    displayName = file.name,
                    apiKey = apiKey,
                    onStatus = onStatus,
                    onFeed = onFeed,
                    onLog = onLog
                )
                return GeminiPart.FileData(
                    fileUri = uploaded.uri,
                    mimeType = uploaded.mimeType.ifBlank { mimeType }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (upErr: Exception) {
                if (file.length() <= 14L * 1024L * 1024L) {
                    onLog("Files API upload failed (${upErr.message}) → Falling back to inline base64...")
                    onStatus("Fallback: encoding video inline...", 0.28f)
                    val b64 = fileToBase64Cached(file)
                    return GeminiPart.InlineData(mimeType = mimeType.ifBlank { "video/mp4" }, base64Data = b64)
                }
                throw upErr
            }
        }

        val sizeMb = "%.2f".format(file.length() / (1024.0 * 1024.0))
        onLog("Encoding ${file.name} ($sizeMb MB) as inlineData base64 for Gemini Platform...")
        onStatus("Encoding video inline ($sizeMb MB)...", 0.28f)
        val b64 = fileToBase64Cached(file)
        return GeminiPart.InlineData(mimeType = mimeType.ifBlank { "video/mp4" }, base64Data = b64)
    }

    suspend fun fetchLiveModels(
        provider: ProviderMode,
        apiKey: String,
        onLog: (String) -> Unit
    ): List<GeminiModelInfo> {
        return when (provider) {
            ProviderMode.AI_STUDIO -> aiStudioService.fetchLiveModels(apiKey, onLog)
            ProviderMode.VERTEX_AI -> vertexAiService.fetchLiveModels(apiKey, onLog)
        }
    }

    suspend fun streamGenerateContent(
        provider: ProviderMode,
        modelName: String,
        parts: List<GeminiPart>,
        apiKey: String,
        thinkingLevel: ThinkingLevel,
        onStatus: (String, Float?) -> Unit,
        onLog: (String) -> Unit,
        onToken: (text: String, isThought: Boolean) -> Unit,
        onUsage: (TokenUsageSnapshot) -> Unit = {}
    ): String {
        return when (provider) {
            ProviderMode.AI_STUDIO -> aiStudioService.streamGenerateContent(
                modelName = modelName,
                parts = parts,
                apiKey = apiKey,
                thinkingLevel = thinkingLevel,
                onStatus = onStatus,
                onLog = onLog,
                onToken = onToken,
                onUsage = onUsage
            )
            ProviderMode.VERTEX_AI -> vertexAiService.streamGenerateContent(
                modelName = modelName,
                parts = parts,
                apiKey = apiKey,
                onStatus = onStatus,
                onLog = onLog,
                onToken = onToken,
                onUsage = onUsage
            )
        }
    }

    /**
     * Executes [block] with sequential API key cycling (skipping permanently disabled keys),
     * backed-off transient overload retry + cross-key 503 rotation,
     * single-key 429 cooldown (Vertex AI), and quota/auth rotation.
     */
    suspend fun <T> runWithApiCycle(
        provider: ProviderMode,
        getKeys: () -> Pair<List<ApiKeyEntry>, Int>,
        updateKeys: (keys: List<ApiKeyEntry>, nextCycleIdx: Int) -> Unit,
        onStatus: (String) -> Unit,
        onLog: (String) -> Unit,
        onRetryReset: () -> Unit = {},
        block: suspend (apiKey: String, keyNumber: Int) -> T
    ): T {
        val initialKeys = getKeys().first
        if (initialKeys.isEmpty()) {
            throw IllegalStateException("Add at least one Gemini API key in Settings.")
        }

        val activeCount = initialKeys.count { !it.isDisabled }
        if (activeCount == 0) {
            throw IllegalStateException("All API keys are disabled/leaked. Add or re-enable a key in Settings.")
        }

        val attempts = activeCount
        var overloadRotations = 0
        val maxOverloadRotations = 3
        var lastError: Throwable? = null

        for (i in 0 until attempts) {
            val (currentKeys, currentIdx) = getKeys()
            if (currentKeys.isEmpty()) break

            val startIdx = currentIdx % currentKeys.size
            var idx = -1
            for (offset in currentKeys.indices) {
                val candIdx = (startIdx + offset) % currentKeys.size
                if (!currentKeys[candIdx].isDisabled) {
                    idx = candIdx
                    break
                }
            }
            if (idx == -1) break

            val selected = currentKeys[idx]
            val nextIdx = (idx + 1) % currentKeys.size

            val updatedForReq = currentKeys.toMutableList().apply {
                this[idx] = selected.copy(requests = selected.requests + 1)
            }
            updateKeys(updatedForReq, nextIdx)
            onLog("API Cycle → Selected Key #${idx + 1} (${selected.masked()}) [Total used: ${selected.requests + 1}]")

            var sameKeyTry = 0
            val maxOverloadSameKeyRetries = if (activeCount == 1) 2 else 1
            while (true) {
                try {
                    return block(selected.key, idx + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (err: Throwable) {
                    lastError = err
                    val msg = err.message.orEmpty()
                    val status = (err as? GeminiHttpException)?.statusCode ?: 0
                    val isServerOverload = status in listOf(408, 500, 502, 503, 504) ||
                        Regex("503|high demand|overloaded|unavailable|stalled", RegexOption.IGNORE_CASE).containsMatchIn(msg)

                    if (isServerOverload && sameKeyTry < maxOverloadSameKeyRetries) {
                        val backoffMs = if (sameKeyTry == 0) 3500L else 7000L
                        val backoffSec = backoffMs / 1000
                        onLog("Key #${idx + 1} transient overload ($status) → Waiting ${backoffSec}s & retrying on same key (reusing active media)...")
                        onStatus("Server busy ($status) → Retrying in ${backoffSec}s...")
                        delay(backoffMs)
                        onRetryReset()
                        sameKeyTry++
                        continue
                    }

                    val is429 = status == 429 || Regex("\\b429\\b").containsMatchIn(msg)
                    if (provider == ProviderMode.VERTEX_AI && is429 && activeCount == 1 && sameKeyTry == 0) {
                        onLog("Key #${idx + 1} rate limited (429) → Waiting 20s to cool down before retrying same key...")
                        onStatus("Quota busy (429) → Waiting 20s to cool down...")
                        delay(20_000)
                        onRetryReset()
                        sameKeyTry++
                        continue
                    }
                    break
                }
            }

            // Record error on key (and flag permanently leaked/invalid keys)
            val msg = lastError?.message.orEmpty()
            val status = (lastError as? GeminiHttpException)?.statusCode ?: 0
            val permanentDisableReason = when {
                status == 403 && Regex("leaked|reported", RegexOption.IGNORE_CASE).containsMatchIn(msg) -> "Leaked (403)"
                status in listOf(401, 403) && Regex("api key not valid|invalid api key|expired|revoked", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
                    "Invalid ($status)"
                else -> null
            }

            val (latestKeys, latestCycle) = getKeys()
            if (idx < latestKeys.size) {
                val errored = latestKeys.toMutableList().apply {
                    this[idx] = this[idx].copy(
                        errors = this[idx].errors + 1,
                        disabledReason = permanentDisableReason ?: this[idx].disabledReason
                    )
                }
                updateKeys(errored, latestCycle)
            }

            onLog("Key #${idx + 1} failed: $msg")
            if (permanentDisableReason != null) {
                onLog("Key #${idx + 1} flagged as \"$permanentDisableReason\" and will be skipped in future requests.")
            }

            val isServerOverload = status in listOf(408, 500, 502, 503, 504) ||
                Regex("503|high demand|overloaded|unavailable|stalled", RegexOption.IGNORE_CASE).containsMatchIn(msg)
            val isKeyQuota = status in listOf(401, 403, 429) ||
                Regex("429|quota|rate|limit|exhausted|permission|api key", RegexOption.IGNORE_CASE).containsMatchIn(msg)

            if (isKeyQuota && i < attempts - 1) {
                val nextNum = ((idx + 1) % currentKeys.size) + 1
                onLog("Key #${idx + 1} limit/quota → Rotating to Key #$nextNum (${i + 2}/$attempts)...")
                onStatus("Key #${idx + 1} limit → Rotating to Key #$nextNum...")
                delay(600)
                onRetryReset()
                continue
            }

            if (isServerOverload && i < attempts - 1 && overloadRotations < maxOverloadRotations) {
                overloadRotations++
                val rotateDelayMs = 2500L + (overloadRotations * 1000L)
                val nextNum = ((idx + 1) % currentKeys.size) + 1
                onLog("Key #${idx + 1} still overloaded ($status) → Waiting ${rotateDelayMs / 1000}s & rotating to Key #$nextNum ($overloadRotations/$maxOverloadRotations)...")
                onStatus("Server busy ($status) → Rotating to Key #$nextNum...")
                delay(rotateDelayMs)
                onRetryReset()
                continue
            }
            break
        }

        throw lastError ?: IllegalStateException("Gemini request failed.")
    }

    fun stripCodeFences(raw: String?): String {
        val s = raw.orEmpty().trim()
        val fullFence = Regex("^```(?:javascript|js)?\\s*([\\s\\S]*?)```$", RegexOption.IGNORE_CASE)
        fullFence.matchEntire(s)?.let { return it.groupValues[1].trim() }
        return s
            .replace(Regex("^```(?:javascript|js)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("```\\s*$"), "")
            .trim()
    }

    fun parseJsonLoose(text: String): JsonElement? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val target = if (start != -1 && end != -1 && end > start) text.substring(start, end + 1) else text.trim()
        if (target.isBlank()) return null

        runCatching { return json.parseToJsonElement(target) }

        val repaired = target
            .replace(Regex(":\\s*`([\\s\\S]*?)`(?=\\s*[,}\\]])")) { m -> ":" + json.encodeToString(m.groupValues[1]) }
            .replace(Regex(",\\s*([}\\]])"), "$1")
        return runCatching { json.parseToJsonElement(repaired) }.getOrNull()
    }

    fun parseJsonAndCodeResult(rawText: String, fallbackJsonKey: String? = null): ParsedJsonAndCode {
        val jsonMarker = "===JSON_RESULT==="
        val codeMarker = "===CODE_RESULT==="
        val jIdx = rawText.indexOf(jsonMarker)
        val cIdx = rawText.indexOf(codeMarker)

        var jsonSegment = ""
        var codeSegment = ""

        when {
            jIdx != -1 && cIdx != -1 && cIdx > jIdx -> {
                jsonSegment = rawText.substring(jIdx + jsonMarker.length, cIdx)
                codeSegment = rawText.substring(cIdx + codeMarker.length)
            }
            cIdx != -1 && jIdx == -1 -> {
                codeSegment = rawText.substring(cIdx + codeMarker.length)
            }
            jIdx != -1 -> {
                jsonSegment = rawText.substring(jIdx + jsonMarker.length)
            }
            else -> {
                val fence = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE).find(rawText)
                if (fence != null) codeSegment = fence.groupValues[1] else jsonSegment = rawText
            }
        }

        val parsedJson = if (jsonSegment.isNotBlank()) parseJsonLoose(jsonSegment) else null
        var code = stripCodeFences(codeSegment)
        if (code.isBlank() && fallbackJsonKey != null && parsedJson is kotlinx.serialization.json.JsonObject) {
            val fb = (parsedJson[fallbackJsonKey] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!fb.isNullOrBlank()) code = stripCodeFences(fb)
        }
        return ParsedJsonAndCode(rawJson = parsedJson, code = code)
    }

    inline fun <reified T> decodeJson(element: JsonElement?, defaultValue: T): T {
        if (element == null) return defaultValue
        return runCatching {
            Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
                .decodeFromJsonElement(kotlinx.serialization.serializer<T>(), element)
        }.getOrDefault(defaultValue)
    }
}