package io.legado.app.ai.runtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.Usage
import io.legado.app.ai.log.AiLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 chat/completions 客户端（DeepSeek/通义/智谱/OpenAI 底层协议一致）。
 * 支持非流式与 SSE 流式（stream=true 时逐块回调增量文本，tool_calls 分片自动组装），
 * tools 原样透传，供 Agent 做多轮 function-calling。
 */
class OpenAIClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val timeoutMillis: Long = 120_000L,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()
) : ChatModelClient {

    override val supportsStream: Boolean = true

    override suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>?,
        stream: Boolean
    ): ChatCompletion = withContext(Dispatchers.IO) {
        val body = buildBody(messages, tools, stream)
        val req = Request.Builder()
            .url("${normalizeBase(baseUrl)}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(RequestBody.create(JSON_MEDIA, body))
            .build()
        val respBody = try {
            client.newCall(req).execute().use { it.body()?.string() }
        } catch (e: Exception) {
            throw AgentException(AgentErrorCode.NETWORK_UNAVAILABLE, e.localizedMessage ?: "network error")
        } ?: throw AgentException(AgentErrorCode.NETWORK_UNAVAILABLE, "empty body")
        if (respBody.isBlank()) throw AgentException(AgentErrorCode.NETWORK_UNAVAILABLE, "empty body")
        parseCompletion(respBody)
    }

    override suspend fun completeStreaming(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>?,
        onDelta: (String) -> Unit,
        isCancelled: () -> Boolean
    ): ChatCompletion = withContext(Dispatchers.IO) {
        val body = buildBody(messages, tools, stream = true)
        val req = Request.Builder()
            .url("${normalizeBase(baseUrl)}/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(JSON_MEDIA, body))
            .build()

        val content = StringBuilder()
        val callIds = HashMap<Int, String>()
        val callNames = HashMap<Int, String>()
        val callArgs = HashMap<Int, StringBuilder>()
        var usage: Usage? = null
        var sawSseData = false
        val rawFallback = StringBuilder()
        val startMs = System.currentTimeMillis()
        var chunks = 0
        var firstDeltaMs = 0L
        AiLog.i("SSE", "连接 ${normalizeBase(baseUrl)} model=$model")

        try {
            client.newCall(req).execute().use { resp ->
                val respBody = resp.body()
                    ?: throw AgentException(AgentErrorCode.NETWORK_UNAVAILABLE, "empty body")
                if (!resp.isSuccessful) {
                    AiLog.e("SSE", "HTTP ${resp.code()}")
                    throw AgentException(
                        AgentErrorCode.AUTH_FAILED,
                        "HTTP ${resp.code()}: ${respBody.string().take(200)}"
                    )
                }
                val source = respBody.source()
                while (true) {
                    if (isCancelled()) break
                    val line = source.readUtf8Line() ?: break
                    // 仅在尚未确认是 SSE 时保留原始行（供非流式回退解析），避免长回答双倍内存
                    if (!sawSseData) rawFallback.append(line).append('\n')
                    if (!line.startsWith("data:")) continue // 空行 / ": keep-alive" 注释行
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    sawSseData = true
                    val deltaCount = chunks
                    consumeChunk(
                        payload, content, onDelta = { piece ->
                            chunks++
                            if (firstDeltaMs == 0L) {
                                firstDeltaMs = System.currentTimeMillis() - startMs
                                AiLog.i("SSE", "首块到达 ${firstDeltaMs}ms")
                            }
                            onDelta(piece)
                        },
                        callIds = callIds, callNames = callNames, callArgs = callArgs
                    ) { u -> usage = u }
                    if (chunks == deltaCount) chunks++ // tool_call/usage 块也计入块数
                }
            }
        } catch (e: AgentException) {
            throw e
        } catch (e: Exception) {
            // 已有部分内容时视为提前结束（网络中断），否则按错误抛出
            if (sawSseData && (content.isNotEmpty() || callIds.isNotEmpty())) {
                AiLog.w("SSE", "流中断，保留 ${content.length} 字部分内容")
            } else {
                AiLog.e("SSE", "失败: ${e.localizedMessage}", e)
                throw AgentException(AgentErrorCode.NETWORK_UNAVAILABLE, e.localizedMessage ?: "stream error")
            }
        }

        // 服务端不支持流式（返回了普通 JSON）：回退非流式解析
        if (!sawSseData && content.isEmpty() && callIds.isEmpty()) {
            AiLog.w("SSE", "响应非 SSE，回退 JSON 解析")
            return@withContext parseCompletion(rawFallback.toString())
        }

        val calls = callIds.keys.toSortedSet().mapNotNull { idx ->
            val name = callNames[idx] ?: return@mapNotNull null
            ToolCallData(id = callIds[idx] ?: "", name = name, arguments = callArgs[idx]?.toString() ?: "{}")
        }
        AiLog.i(
            "SSE",
            "完成: ${content.length}字/$chunks块/${System.currentTimeMillis() - startMs}ms" +
                (if (calls.isNotEmpty()) " toolCalls=${calls.size}" else "")
        )
        ChatCompletion(
            content = content.toString().ifEmpty { null },
            toolCalls = calls.ifEmpty { null },
            usage = usage
        )
    }

    /** 解析单个 SSE data 块：content 增量回调、tool_calls 分片累积、usage 提取 */
    private inline fun consumeChunk(
        payload: String,
        content: StringBuilder,
        onDelta: (String) -> Unit,
        callIds: MutableMap<Int, String>,
        callNames: MutableMap<Int, String>,
        callArgs: MutableMap<Int, StringBuilder>,
        setUsage: (Usage?) -> Unit
    ) {
        val root = try {
            JsonParser.parseString(payload).asJsonObject
        } catch (_: Exception) {
            return
        }
        root.getAsJsonObject("error")?.let {
            throw AgentException(AgentErrorCode.AUTH_FAILED, it["message"]?.asString ?: "api error")
        }
        (root.getAsJsonArray("choices")?.firstOrNull() as? JsonObject)?.let { choice ->
            val delta = choice.getAsJsonObject("delta") ?: JsonObject()
            delta.get("content")?.takeIf { !it.isJsonNull }?.asString?.let { piece ->
                if (piece.isNotEmpty()) {
                    content.append(piece)
                    onDelta(piece)
                }
            }
            delta.getAsJsonArray("tool_calls")?.forEach { tcEl ->
                val tc = tcEl.asJsonObject
                val idx = tc.get("index")?.asInt ?: callIds.size
                tc.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { callIds[idx] = it }
                val fn = tc.getAsJsonObject("function")
                fn?.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { callNames[idx] = it }
                fn?.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { frag ->
                    callArgs.getOrPut(idx) { StringBuilder() }.append(frag)
                }
            }
        }
        root.getAsJsonObject("usage")?.let { u ->
            setUsage(
                Usage(
                    promptTokens = u.get("prompt_tokens")?.asInt,
                    completionTokens = u.get("completion_tokens")?.asInt,
                    totalTokens = u.get("total_tokens")?.asInt
                )
            )
        }
    }

    private fun buildBody(messages: List<ChatMessage>, tools: List<Map<String, Any>>?, stream: Boolean): String {
        val root = JsonObject()
        root.addProperty("model", model)
        root.addProperty("stream", stream)
        val arr = JsonArray()
        messages.forEach { m ->
            val o = JsonObject()
            o.addProperty("role", m.role)
            if (!m.content.isNullOrEmpty()) o.addProperty("content", m.content)
            m.toolCalls?.let { calls ->
                val tc = JsonArray()
                calls.forEach { c ->
                    val f = JsonObject()
                    f.addProperty("name", c.function.name)
                    f.addProperty("arguments", c.function.arguments)
                    val call = JsonObject()
                    call.addProperty("id", c.id)
                    call.addProperty("type", c.type)
                    call.add("function", f)
                    tc.add(call)
                }
                o.add("tool_calls", tc)
            }
            if (!m.toolCallId.isNullOrEmpty()) o.addProperty("tool_call_id", m.toolCallId)
            if (!m.name.isNullOrEmpty()) o.addProperty("name", m.name)
            arr.add(o)
        }
        root.add("messages", arr)
        if (!tools.isNullOrEmpty()) {
            root.add("tools", JsonParser.parseString(GSON.toJson(tools)))
        }
        return GSON.toJson(root)
    }

    private fun normalizeBase(u: String): String = u.trim().trimEnd('/')

    /** 解析非流式 completion 响应。纯逻辑，可在 JVM 单测中验证。 */
    fun parseCompletion(json: String): ChatCompletion {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw AgentException(AgentErrorCode.TOOL_FAILED, "invalid json response")
        }
        root.getAsJsonObject("error")?.let {
            throw AgentException(AgentErrorCode.AUTH_FAILED, it["message"]?.asString ?: "api error")
        }
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: throw AgentException(AgentErrorCode.TOOL_FAILED, "no choices")
        val msg = choice.getAsJsonObject("message") ?: JsonObject()
        val content = if (msg.has("content") && !msg.get("content").isJsonNull)
            msg["content"].asString else null
        val calls = if (msg.has("tool_calls")) {
            msg.getAsJsonArray("tool_calls").map { c ->
                val f = c.asJsonObject.getAsJsonObject("function")
                ToolCallData(
                    id = c.asJsonObject["id"]?.asString ?: "",
                    name = f["name"]?.asString ?: "",
                    arguments = f["arguments"]?.asString ?: "{}"
                )
            }
        } else null
        val usage = root.getAsJsonObject("usage")?.let { u ->
            io.legado.app.ai.model.Usage(
                promptTokens = u.get("prompt_tokens")?.asInt,
                completionTokens = u.get("completion_tokens")?.asInt,
                totalTokens = u.get("total_tokens")?.asInt
            )
        }
        return ChatCompletion(content, calls, usage)
    }

    private companion object {
        val JSON_MEDIA = MediaType.parse("application/json; charset=utf-8")
        val GSON = com.google.gson.Gson()
    }
}