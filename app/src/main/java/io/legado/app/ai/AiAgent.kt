package io.legado.app.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.app.ai.model.*
import io.legado.app.ai.tools.BookSearchTool
import io.legado.app.ai.tools.BookSourceTool
import io.legado.app.ai.tools.ReadingAssistant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Agent 核心协调器
 * 负责接收用户请求、调用 LLM、执行工具、返回结果
 */
object AiAgent {

    private val gson = Gson()
    private var initialized = false

    /**
     * 初始化 Agent，注册所有工具
     */
    fun init() {
        if (initialized) return
        initialized = true

        BookSearchTool.register()
        BookSourceTool.register()
        ReadingAssistant.register()
    }

    /**
     * 执行 AI 对话，自动处理工具调用循环
     */
    suspend fun execute(
        userMessage: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        convId: String? = null,
        config: AiModelConfig = ModelManager.getConfig()
    ): String? = withContext(Dispatchers.IO) {
        init()

        val conversation = if (convId != null) {
            ConversationManager.getConversation(convId)
                ?: ConversationManager.createConversation(systemPrompt)
        } else {
            ConversationManager.createConversation(systemPrompt)
        }

        // 添加用户消息
        ConversationManager.addMessage(conversation.id, ChatMessage(role = "user", content = userMessage))

        processToolCalls(conversation.id, config, maxTurns = 5)
    }

    /**
     * 处理工具调用循环
     */
    private suspend fun processToolCalls(
        convId: String,
        config: AiModelConfig,
        maxTurns: Int
    ): String? {
        var turn = 0

        while (turn < maxTurns) {
            turn++

            val messages = ConversationManager.buildRequestMessages(convId)
            val tools = ToolRegistry.getToolDefinitions()

            val request = ChatCompletionRequest(
                model = config.name,
                messages = messages,
                tools = if (tools.isNotEmpty()) tools else null,
                temperature = config.temperature,
                maxTokens = config.maxTokens
            )

            val response = ModelManager.chatCompletion(request, config) ?: return null
            val choice = response.choices?.firstOrNull() ?: return null

            val replyMessage = choice.message
            ConversationManager.addMessage(convId, replyMessage)

            // 检查是否有工具调用
            val toolCalls = replyMessage.toolCalls
            if (toolCalls.isNullOrEmpty()) {
                // 没有工具调用，返回最终回复
                return replyMessage.content ?: ""
            }

            // 执行工具调用
            for (toolCall in toolCalls) {
                val toolName = toolCall.function.name
                val tool = ToolRegistry.getTool(toolName)

                if (tool != null) {
                    try {
                        val argsMap = parseArguments(toolCall.function.arguments)
                        val toolResult = tool.executor(argsMap)
                        ConversationManager.addMessage(
                            convId,
                            ChatMessage(
                                role = "tool",
                                content = toolResult,
                                toolCallId = toolCall.id,
                                name = toolName
                            )
                        )
                    } catch (e: Exception) {
                        ConversationManager.addMessage(
                            convId,
                            ChatMessage(
                                role = "tool",
                                content = "{\"error\": \"${e.message}\"}",
                                toolCallId = toolCall.id,
                                name = toolName
                            )
                        )
                    }
                }
            }
        }

        return null
    }

    private fun parseArguments(json: String): Map<String, Any?> {
        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private const val DEFAULT_SYSTEM_PROMPT = """
你是一个智能阅读助手，集成在阅读APP中。你可以帮助用户完成以下任务：

1. **智能搜索书籍**：根据用户描述搜索书籍，支持跨书源搜索
2. **书源分析与优化**：分析书源配置，发现规则问题，提出优化建议
3. **阅读辅助**：解释文本段落，提供阅读建议，分析人物关系

请使用提供的工具来帮助用户。在回答时，请用中文回复，保持简洁清晰。
"""
}