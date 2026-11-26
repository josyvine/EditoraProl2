package com.vineyard.editoraPRO.app.data.remote

import android.util.Base64
import com.vineyard.editoraPRO.app.data.model.GeminiModelInfo
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import com.vineyard.editoraPRO.app.security.SecurityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiHttpException(val statusCode: Int, message: String) : IOException(message)

class VertexAiService(
    private val httpClient: OkHttpClient = defaultStreamingClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val saTokenCache = ConcurrentHashMap<String, CachedToken>()

    private data class CachedToken(val token: String, val expiresAtMs: Long)

    private fun resolveAuth(credential: String): Pair<String, String> {
        val clean = credential.trim()
        if (clean.startsWith("{") && clean.contains("\"private_key\"") && clean.contains("\"client_email\"")) {
            val token = getServiceAccountBearerToken(clean)
            return "Authorization" to "Bearer $token"
        }
        if (clean.startsWith("ya29.") || clean.startsWith("Bearer ", ignoreCase = true)) {
            val token = clean.removePrefix("Bearer ").removePrefix("bearer ").trim()
            return "Authorization" to "Bearer $token"
        }
        return "x-goog-api-key" to clean
    }

    private fun getServiceAccountBearerToken(saJson: String): String {
        val root = json.parseToJsonElement(saJson).jsonObject
        val clientEmail = root["client_email"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Service Account JSON missing 'client_email'")
        val privateKeyPem = root["private_key"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Service Account JSON missing 'private_key'")
        val tokenUri = root["token_uri"]?.jsonPrimitive?.contentOrNull ?: "https://oauth2.googleapis.com/token"

        val cacheKey = clientEmail
        val cached = saTokenCache[cacheKey]
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAtMs > (now + 60_000L)) {
            return cached.token
        }

        val headerB64 = Base64.encodeToString(
            """{"alg":"RS256","typ":"JWT"}""".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val nowSec = now / 1000L
        val expSec = nowSec + 3600L
        val claimJson = """{
            "iss":"$clientEmail",
            "sub":"$clientEmail",
            "aud":"$tokenUri",
            "iat":$nowSec,
            "exp":$expSec,
            "scope":"https://www.googleapis.com/auth/cloud-platform"
        }""".replace(Regex("\\s+"), "")

        val claimB64 = Base64.encodeToString(
            claimJson.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val signedData = "$headerB64.$claimB64".toByteArray(Charsets.US_ASCII)

        val cleanPem = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace(Regex("\\s+"), "")
        val keyBytes = Base64.decode(cleanPem, Base64.DEFAULT)
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance("RSA")
        val privKey = kf.generatePrivate(spec)

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privKey)
        sig.update(signedData)
        val sigB64 = Base64.encodeToString(
            sig.sign(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        val jwtAssertion = "$headerB64.$claimB64.$sigB64"

        val formBody = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwtAssertion"
        val req = Request.Builder()
            .url(tokenUri)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        val tokenResp = httpClient.newCall(req).execute()
        val tokenBody = tokenResp.body?.string().orEmpty()
        if (!tokenResp.isSuccessful) {
            throw IOException("Failed to obtain OAuth2 token from Service Account: [HTTP ${tokenResp.code}] $tokenBody")
        }

        val parsed = json.parseToJsonElement(tokenBody).jsonObject
        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IOException("Token response missing 'access_token'")
        val expiresInSec = parsed["expires_in"]?.jsonPrimitive?.intOrNull ?: 3600
        saTokenCache[cacheKey] = CachedToken(accessToken, now + (expiresInSec * 1000L))
        return accessToken
    }

    suspend fun fetchLiveModels(
        apiKey: String,
        onLog: (String) -> Unit
    ): List<GeminiModelInfo> = coroutineScope {
        val project = SecurityGuard.getVertexProject()
        val location = SecurityGuard.getVertexLocation()
        val base = SecurityGuard.getVertexBaseUrl()
        val (headerName, headerVal) = resolveAuth(apiKey)
        onLog("Probing live Gemini Platform models on $project/$location via :countTokens ($headerName)...")

        data class ProbeResult(
            val cand: GeminiModelInfo,
            val ok: Boolean,
            val status: Int,
            val message: String
        )

        val checks = CANDIDATE_MODELS.map { cand ->
            async(Dispatchers.IO) {
                val url = "$base/${URLEncoder.encode(cand.name, "UTF-8")}:countTokens"
                val payload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", "ping") })
                            })
                        })
                    })
                }.toString()

                val req = Request.Builder()
                    .url(url)
                    .header("Content-Type", "application/json")
                    .header(headerName, headerVal)
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()

                val call = httpClient.newCall(req)
                val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

                try {
                    call.execute().use { resp ->
                        val bodyStr = resp.body?.string().orEmpty()
                        val errMsg = extractErrorMessage(bodyStr) ?: resp.message
                        ProbeResult(cand, resp.isSuccessful, resp.code, errMsg)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!coroutineContext.isActive) throw CancellationException("Cancelled by user.")
                    ProbeResult(cand, false, 0, e.message ?: "Network error")
                } finally {
                    cancelHandle?.dispose()
                }
            }
        }.awaitAll()

        val available = checks.filter { it.ok }.map {
            it.cand.copy(
                description = "Verified live on Gemini Platform ($project/$location)"
            )
        }

        if (available.isEmpty()) {
            val priority = checks.firstOrNull { it.status in listOf(401, 403, 429) }
                ?: checks.firstOrNull { it.status >= 500 }
                ?: checks.first()
            throw GeminiHttpException(
                priority.status,
                "[HTTP ${priority.status}] ${priority.message.ifBlank { "No live models available" }}"
            )
        }
        available
    }

    suspend fun streamGenerateContent(
        modelName: String,
        parts: List<GeminiPart>,
        apiKey: String,
        useThinkingConfig: Boolean = true,
        onStatus: (String, Float?) -> Unit,
        onLog: (String) -> Unit,
        onToken: (text: String, isThought: Boolean) -> Unit,
        onUsage: (TokenUsageSnapshot) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val modelId = modelName.removePrefix("models/")
        val project = SecurityGuard.getVertexProject()
        val location = SecurityGuard.getVertexLocation()
        val base = SecurityGuard.getVertexBaseUrl()
        val url = "$base/${URLEncoder.encode(modelId, "UTF-8")}:streamGenerateContent?alt=sse"
        val (headerName, headerVal) = resolveAuth(apiKey)

        val genCfg = buildJsonObject {
            put("temperature", 0.2)
            if (useThinkingConfig) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)
                })
            }
        }

        val bodyJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", parts.toJsonArray())
                })
            })
            put("generationConfig", genCfg)
        }.toString()

        val reqStart = System.nanoTime()
        onLog("POST $project/$location/models/$modelId:streamGenerateContent?alt=sse (thoughts=$useThinkingConfig, auth=$headerName)")
        onStatus("Requesting $modelId...", 0.42f)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header(headerName, headerVal)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()

        val call = httpClient.newCall(request)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        val gotAnyData = AtomicBoolean(false)

        val watchdogThread = Thread {
            try {
                Thread.sleep(90_000L)
                if (!gotAnyData.get()) {
                    call.cancel()
                }
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true; start() }

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errText = resp.body?.string().orEmpty()
                    val errMsg = extractErrorMessage(errText) ?: "Request failed"
                    if (useThinkingConfig && resp.code == 400 &&
                        Regex("thinking|include_thoughts|thinkingConfig", RegexOption.IGNORE_CASE).containsMatchIn(errMsg)
                    ) {
                        onLog("Model doesn't use thinkingConfig ($errMsg) → Retrying without thinkingConfig...")
                        watchdogThread.interrupt()
                        return@withContext streamGenerateContent(
                            modelName = modelName,
                            parts = parts,
                            apiKey = apiKey,
                            useThinkingConfig = false,
                            onStatus = onStatus,
                            onLog = onLog,
                            onToken = onToken,
                            onUsage = onUsage
                        )
                    }
                    throw GeminiHttpException(resp.code, "[HTTP ${resp.code}] $errMsg")
                }

                val connectSec = (System.nanoTime() - reqStart) / 1_000_000_000.0
                onLog("Stream connected (HTTP ${resp.code}) in ${"%.2f".format(connectSec)}s. Reading live SSE...")
                onStatus("Streaming from $modelId...", 0.46f)

                val source = resp.body?.source() ?: throw IOException("Empty SSE response body")
                val fullOutput = StringBuilder()
                var gotFirstToken = false
                var chunkCount = 0
                var latestUsage: TokenUsageSnapshot? = null

                while (true) {
                    val line = source.readUtf8Line() ?: break
                    gotAnyData.set(true)
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val chunkObj = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                    chunkObj["usageMetadata"]?.jsonObject?.let { um ->
                        latestUsage = mergeUsage(latestUsage, parseUsageMetadata(um))
                    }

                    val errObj = chunkObj["error"]?.jsonObject
                    if (errObj != null) {
                        latestUsage?.takeIf { it.hasData }?.let(onUsage)
                        val code = errObj["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 500
                        val msg = errObj["message"]?.jsonPrimitive?.contentOrNull ?: "Stream error"
                        throw GeminiHttpException(code, "[HTTP $code] $msg")
                    }

                    val respParts = chunkObj["candidates"]?.jsonArray
                        ?.firstOrNull()?.jsonObject
                        ?.get("content")?.jsonObject
                        ?.get("parts")?.jsonArray ?: continue

                    for (el in respParts) {
                        val pObj = el.jsonObject
                        val text = pObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                        if (!gotFirstToken) {
                            gotFirstToken = true
                            val ttfb = (System.nanoTime() - reqStart) / 1_000_000_000.0
                            onLog("First AI token arrived in ${"%.2f".format(ttfb)}s from $modelId!")
                        }
                        chunkCount++
                        val isThought = pObj["thought"]?.jsonPrimitive?.contentOrNull == "true"
                        if (isThought) {
                            onStatus("AI is reasoning...", null)
                            onToken(text, true)
                        } else {
                            onStatus("AI is reporting & synthesizing...", null)
                            onToken(text, false)
                            fullOutput.append(text)
                        }
                    }
                }

                latestUsage?.takeIf { it.hasData }?.let { u ->
                    onUsage(u)
                    onLog("Tokens → in=${u.promptTokens}, out=${u.candidatesTokens}, think=${u.thoughtsTokens}, total=${u.effectiveTotalTokens}")
                }

                val totalSec = (System.nanoTime() - reqStart) / 1_000_000_000.0
                val result = fullOutput.toString()
                onLog("Stream completed in ${"%.2f".format(totalSec)}s ($chunkCount chunks, ${result.length} chars).")
                if (result.isBlank()) throw IOException("Stream ended with empty output.")
                result
            }
        } catch (e: IOException) {
            if (!coroutineContext.isActive) {
                throw CancellationException("Cancelled by user.")
            }
            if (!gotAnyData.get() && call.isCanceled()) {
                throw GeminiHttpException(408, "Model \"$modelId\" did not respond within 90s")
            }
            throw e
        } finally {
            cancelHandle?.dispose()
            watchdogThread.interrupt()
        }
    }

    private fun parseUsageMetadata(um: JsonObject): TokenUsageSnapshot = TokenUsageSnapshot(
        promptTokens = um["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        candidatesTokens = um["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        thoughtsTokens = um["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        cachedTokens = um["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
        totalTokens = um["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
    )

    private fun mergeUsage(prev: TokenUsageSnapshot?, cur: TokenUsageSnapshot): TokenUsageSnapshot {
        if (prev == null) return cur
        return TokenUsageSnapshot(
            promptTokens = maxOf(prev.promptTokens, cur.promptTokens),
            candidatesTokens = maxOf(prev.candidatesTokens, cur.candidatesTokens),
            thoughtsTokens = maxOf(prev.thoughtsTokens, cur.thoughtsTokens),
            cachedTokens = maxOf(prev.cachedTokens, cur.cachedTokens),
            totalTokens = maxOf(prev.totalTokens, cur.totalTokens)
        )
    }

    private fun extractErrorMessage(body: String): String? {
        if (body.isBlank()) return null
        return runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        val CANDIDATE_MODELS = listOf(
            GeminiModelInfo("gemini-4-neon", "Gemini 4 Neon"),
            GeminiModelInfo("gemini-4-hydrogen", "Gemini 4 Hydrogen"),
            GeminiModelInfo("gemini-4-carbon", "Gemini 4 Carbon"),
            GeminiModelInfo("gemini-4-argon", "Gemini 4 Argon"),
            GeminiModelInfo("gemini-3.8-flash", "Gemini 3.8 Flash"),
            GeminiModelInfo("gemini-3.5-flash", "Gemini 3.5 Flash"),
            GeminiModelInfo("gemini-3.5-flash-lite", "Gemini 3.5 Flash Lite"),
            GeminiModelInfo("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview"),
            GeminiModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash"),
            GeminiModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro")
        )

        fun defaultStreamingClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

internal fun List<GeminiPart>.toJsonArray(): JsonArray = buildJsonArray {
    for (part in this@toJsonArray) {
        when (part) {
            is GeminiPart.Text -> add(buildJsonObject { put("text", part.text) })
            is GeminiPart.InlineData -> add(buildJsonObject {
                put("inlineData", buildJsonObject {
                    put("mimeType", part.mimeType)
                    put("data", part.base64Data)
                })
            })
            is GeminiPart.FileData -> add(buildJsonObject {
                put("fileData", buildJsonObject {
                    part.mimeType?.let { put("mimeType", it) }
                    put("fileUri", part.fileUri)
                })
            })
        }
    }
}