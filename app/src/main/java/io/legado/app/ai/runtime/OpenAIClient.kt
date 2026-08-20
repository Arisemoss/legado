package io.legado.app.ai.runtime

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容 chat/completions 客户端（DeepSeek/通义/智谱/OpenAI 底层协议一致）。
 * 非流式请求，tools 原样透传，供 Agent 做多轮 function-calling。
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
        return ChatCompletion(content, calls)
    }

    private companion object {
        val JSON_MEDIA = MediaType.parse("application/json; charset=utf-8")
        val GSON = com.google.gson.Gson()
    }
}