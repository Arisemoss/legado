package io.legado.app.ai.runtime

import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.Usage

/**
 * 模型客户端抽象。Agent 通过该接口与任意厂商的 chat completion API 交互。
 */
interface ChatModelClient {
    val supportsStream: Boolean

    /** 非流式 chat completion；[tools] 为 OpenAI function schema 数组，可为 null */
    suspend fun complete(
        messages: List<ChatMessage>,
        tools: List<Map<String, Any>>?,
        stream: Boolean
    ): ChatCompletion
}

data class ChatCompletion(
    val content: String?,
    val toolCalls: List<ToolCallData>?,
    val usage: Usage? = null
)

data class ToolCallData(
    val id: String,
    val name: String,
    val arguments: String
)

/** 模型调用失败时抛出的可分类异常，Agent 据此做重试/退出决策。 */
class AgentException(val code: AgentErrorCode, override val message: String) : Exception(message)