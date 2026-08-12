package io.legado.app.ai.model

import com.google.gson.annotations.SerializedName

data class ChatMessage(
    val role: String,        // "system", "user", "assistant", "tool"
    val content: String? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null
)

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

data class FunctionCall(
    val name: String,
    val arguments: String   // JSON string
)

data class ChatCompletionRequest(
    val model: String = "gpt-4o-mini",
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition>? = null,
    val temperature: Double = 0.7,
    @SerializedName("max_tokens")
    val maxTokens: Int = 4096,
    val stream: Boolean = false
)

data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>
)

data class ChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ErrorResponse? = null
)

data class Choice(
    val message: ChatMessage,
    @SerializedName("finish_reason")
    val finishReason: String? = null
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerializedName("completion_tokens")
    val completionTokens: Int? = null,
    @SerializedName("total_tokens")
    val totalTokens: Int? = null
)

data class ErrorResponse(
    val message: String? = null,
    val type: String? = null
)

data class AiModelConfig(
    val name: String = "gpt-4o-mini",
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096
)