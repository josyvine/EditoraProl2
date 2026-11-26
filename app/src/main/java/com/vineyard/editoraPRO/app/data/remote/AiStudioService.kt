package com.vineyard.editoraPRO.app.data.remote

import com.vineyard.editoraPRO.app.data.model.GeminiModelInfo
import com.vineyard.editoraPRO.app.data.model.GeminiPart
import com.vineyard.editoraPRO.app.data.model.ThinkingLevel
import com.vineyard.editoraPRO.app.data.model.TokenUsageSnapshot
import com.vineyard.editoraPRO.app.data.model.UploadedFileRecord
import com.vineyard.editoraPRO.app.security.SecurityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class AiStudioService(
    private val httpClient: OkHttpClient = VertexAiService.defaultStreamingClient()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadedUriCache = ConcurrentHashMap<String, UploadedFileRecord>()

    suspend fun fetchLiveModels(
        apiKey: String,
        onLog: (String) -> Unit
    ): List<GeminiModelInfo> = withContext(Dispatchers.IO) {
        val base = SecurityGuard.getAiStudioBaseUrl()
        val url = "$base/v1beta/models?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        onLog("Querying GET /v1beta/models for live AI Studio models...")

        val req = Request.Builder().url(url).get().build()
        val call = httpClient.newCall(req)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }

        try {
            call.execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractErrorMessage(bodyStr) ?: "Fetch failed"
                    throw GeminiHttpException(resp.code, "[HTTP ${resp.code}] $msg")
                }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val arr = root["models"]?.jsonArray ?: return@withContext emptyList()
                arr.mapNotNull { el ->
                    val obj = el.jsonObject
                    val methods = obj["supportedGenerationMethods"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    if (!methods.contains("generateContent")) return@mapNotNull null
                    GeminiModelInfo(
                        name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull ?: "",
                        description = obj["description"]?.jsonPrimitive?.contentOrNull ?: "",
                        inputTokenLimit = obj["inputTokenLimit"]?.jsonPrimitive?.intOrNull,
                        outputTokenLimit = obj["outputTokenLimit"]?.jsonPrimitive?.intOrNull
                    )
                }
            }
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("Cancelled by user.")
            throw e
        } finally {
            cancelHandle?.dispose()
        }
    }

    suspend fun uploadToFilesApi(
        file: File,
        mimeType: String,
        displayName: String,
        apiKey: String,
        onStatus: (String, Float?) -> Unit,
        onFeed: (String) -> Unit,
        onLog: (String) -> Unit
    ): UploadedFileRecord = withContext(Dispatchers.IO) {
        val cacheKey = "$apiKey|${file.name}|${file.length()}|${file.lastModified()}"
        val cached = uploadedUriCache[cacheKey]
        if (cached != null && cached.state == "ACTIVE") {
            onLog("Reusing active uploaded file URI on this key (${cached.name})")
            return@withContext cached
        }

        val totalBytes = file.length().coerceAtLeast(1L)
        val sizeMb = "%.2f".format(totalBytes / (1024.0 * 1024.0))
        onLog("Uploading $displayName ($sizeMb MB) to Gemini Files API...")
        onStatus("Uploading video ($sizeMb MB)... 0%", 0.12f)

        val boundary = "----AVDFormBoundary${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val metaJson = buildJsonObject {
            put("file", buildJsonObject {
                put("display_name", displayName.ifBlank { "video.mp4" })
            })
        }.toString()

        val headerBytes = (
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metaJson\r\n" +
                "--$boundary\r\nContent-Type: ${mimeType.ifBlank { "video/mp4" }}\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val footerBytes = "\r\n--$boundary--".toByteArray(Charsets.UTF_8)

        val requestBody = object : RequestBody() {
            override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()
            override fun contentLength() = headerBytes.size + totalBytes + footerBytes.size
            override fun writeTo(sink: BufferedSink) {
                sink.write(headerBytes)
                val buffer = ByteArray(64 * 1024)
                var uploaded = 0L
                var lastQuarter = 0
                FileInputStream(file).use { fis ->
                    while (true) {
                        val read = fis.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        uploaded += read
                        val pct = ((uploaded * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onStatus("Uploading video... $pct%", 0.12f + (pct * 0.0022f))
                        val upMb = "%.2f".format(uploaded / (1024.0 * 1024.0))
                        onFeed("Uploading $displayName: $pct% ($upMb / $sizeMb MB)")
                        val q = pct / 25
                        if (q > lastQuarter) {
                            lastQuarter = q
                            onLog("Upload progress: $pct% ($upMb / $sizeMb MB)")
                        }
                    }
                }
                sink.write(footerBytes)
            }
        }

        val base = SecurityGuard.getAiStudioBaseUrl()
        val uploadUrl = "$base/upload/v1beta/files?uploadType=multipart&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val req = Request.Builder().url(uploadUrl).post(requestBody).build()
        val uploadCall = httpClient.newCall(req)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { uploadCall.cancel() }

        var record: UploadedFileRecord = try {
            uploadCall.execute().use { resp ->
                val respText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = extractErrorMessage(respText) ?: "Files API upload failed"
                    throw GeminiHttpException(resp.code, "[HTTP ${resp.code}] $msg")
                }
                val fileObj = json.parseToJsonElement(respText).jsonObject["file"]?.jsonObject
                    ?: throw IOException("Missing file object in upload response")
                UploadedFileRecord(
                    name = fileObj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    uri = fileObj["uri"]?.jsonPrimitive?.contentOrNull ?: "",
                    mimeType = fileObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: mimeType,
                    state = fileObj["state"]?.jsonPrimitive?.contentOrNull ?: "PROCESSING"
                )
            }
        } catch (e: IOException) {
            if (!coroutineContext.isActive) throw CancellationException("Cancelled by user.")
            throw e
        } finally {
            cancelHandle?.dispose()
        }

        onLog("Upload finished → File URI: ${record.name} (state: ${record.state})")

        while (record.state == "PROCESSING") {
            onStatus("Google server indexing video frames...", 0.38f)
            onFeed("Video uploaded. Google server processing frames (${record.name})...")
            delay(1500)
            val pollUrl = "$base/v1beta/${record.name}?key=${URLEncoder.encode(apiKey, "UTF-8")}"
            val pollReq = Request.Builder().url(pollUrl).get().build()
            val pollCall = httpClient.newCall(pollReq)
            val pollCancelHandle = coroutineContext[Job]?.invokeOnCompletion { pollCall.cancel() }
            record = try {
                pollCall.execute().use { pollResp ->
                    val pollText = pollResp.body?.string().orEmpty()
                    if (!pollResp.isSuccessful) {
                        val msg = extractErrorMessage(pollText) ?: "File polling failed"
                        throw GeminiHttpException(pollResp.code, "[HTTP ${pollResp.code}] $msg")
                    }
                    val fileObj = json.parseToJsonElement(pollText).jsonObject
                    UploadedFileRecord(
                        name = fileObj["name"]?.jsonPrimitive?.contentOrNull ?: record.name,
                        uri = fileObj["uri"]?.jsonPrimitive?.contentOrNull ?: record.uri,
                        mimeType = fileObj["mimeType"]?.jsonPrimitive?.contentOrNull ?: record.mimeType,
                        state = fileObj["state"]?.jsonPrimitive?.contentOrNull ?: "ACTIVE"
                    )
                }
            } catch (e: IOException) {
                if (!coroutineContext.isActive) throw CancellationException("Cancelled by user.")
                throw e
            } finally {
                pollCancelHandle?.dispose()
            }
            onLog("Server video processing state: ${record.state}")
        }

        if (record.state == "FAILED") {
            throw IOException("Gemini failed to process video file.")
        }
        uploadedUriCache[cacheKey] = record
        onLog("Video ready on Gemini server (${record.uri})")
        record
    }

    suspend fun streamGenerateContent(
        modelName: String,
        parts: List<GeminiPart>,
        apiKey: String,
        thinkingLevel: ThinkingLevel,
        useThinkingConfig: Boolean = true,
        useCustomThinkingLevel: Boolean = true,
        onStatus: (String, Float?) -> Unit,
        onLog: (String) -> Unit,
        onToken: (text: String, isThought: Boolean) -> Unit,
        onUsage: (TokenUsageSnapshot) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val modelId = modelName.removePrefix("models/")
        val modelPath = if (modelName.startsWith("models/")) modelName else "models/$modelName"
        val base = SecurityGuard.getAiStudioBaseUrl()
        val url = "$base/v1beta/$modelPath:streamGenerateContent?alt=sse&key=${URLEncoder.encode(apiKey, "UTF-8")}"

        var thinkingDetail = useThinkingConfig.toString()
        val genCfg = buildJsonObject {
            put("temperature", 0.2)
            if (useThinkingConfig) {
                put("thinkingConfig", buildJsonObject {
                    put("includeThoughts", true)
                    if (useCustomThinkingLevel && thinkingLevel != ThinkingLevel.DEFAULT) {
                        if (Regex("gemini-2\\.5", RegexOption.IGNORE_CASE).containsMatchIn(modelId)) {
                            val budget = when (thinkingLevel) {
                                ThinkingLevel.MINIMAL -> if (Regex("2\\.5-pro", RegexOption.IGNORE_CASE).containsMatchIn(modelId)) 128 else 0
                                ThinkingLevel.LOW -> 1024
                                ThinkingLevel.MEDIUM -> 8192
                                ThinkingLevel.HIGH -> 24576
                                ThinkingLevel.XHIGH -> -1
                                ThinkingLevel.DEFAULT -> -1
                            }
                            put("thinkingBudget", budget)
                            thinkingDetail = "budget=$budget"
                        } else {
                            put("thinkingLevel", thinkingLevel.wireValue)
                            thinkingDetail = "level=${thinkingLevel.wireValue}"
                        }
                    }
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
        onLog("POST $modelPath:streamGenerateContent?alt=sse (thoughts=$thinkingDetail)")
        onStatus("Requesting $modelId...", 0.42f)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
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
                    if (useThinkingConfig && useCustomThinkingLevel && thinkingLevel != ThinkingLevel.DEFAULT &&
                        resp.code == 400 &&
                        Regex("thinking|include_thoughts|thinkingConfig|thinkingLevel|thinkingBudget|thinking_level|thinking_budget", RegexOption.IGNORE_CASE).containsMatchIn(errMsg)
                    ) {
                        onLog("Model rejected thinking level \"${thinkingLevel.wireValue}\" ($errMsg) → Retrying with model default thinking...")
                        watchdogThread.interrupt()
                        return@withContext streamGenerateContent(
                            modelName = modelName,
                            parts = parts,
                            apiKey = apiKey,
                            thinkingLevel = thinkingLevel,
                            useThinkingConfig = true,
                            useCustomThinkingLevel = false,
                            onStatus = onStatus,
                            onLog = onLog,
                            onToken = onToken,
                            onUsage = onUsage
                        )
                    }
                    if (useThinkingConfig && resp.code == 400 &&
                        Regex("thinking|include_thoughts|thinkingConfig", RegexOption.IGNORE_CASE).containsMatchIn(errMsg)
                    ) {
                        onLog("Model doesn't use thinkingConfig ($errMsg) → Retrying without thinkingConfig...")
                        watchdogThread.interrupt()
                        return@withContext streamGenerateContent(
                            modelName = modelName,
                            parts = parts,
                            apiKey = apiKey,
                            thinkingLevel = thinkingLevel,
                            useThinkingConfig = false,
                            useCustomThinkingLevel = false,
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
    }
}