package io.legado.app.ai.tools

import io.legado.app.ai.ToolRegistry
import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition

/**
 * AI 阅读助手工具
 * 让 AI Agent 能够在阅读过程中提供辅助功能，如解释段落、查询信息等
 */
object ReadingAssistant {

    fun register() {
        // 工具1: 解释文本
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "explain_text",
                        description = "解释指定的文本段落，提供含义分析、背景知识、修辞手法等",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "text" to mapOf(
                                    "type" to "string",
                                    "description" to "需要解释的文本内容"
                                ),
                                "context" to mapOf(
                                    "type" to "string",
                                    "description" to "可选的上下文信息，如书名、章节名"
                                )
                            ),
                            "required" to listOf("text")
                        )
                    )
                ),
                executor = { args ->
                    val text = args["text"]?.toString()
                    if (text == null) {
                        "{\"error\": \"缺少文本内容\"}"
                    } else {
                        val context = args["context"]?.toString() ?: ""
                        // 返回结构化数据供 LLM 组织回答
                        """
                        {
                            "text": "${ToolUtils.escapeJson(text.take(500))}",
                            "context": "${ToolUtils.escapeJson(context)}",
                            "textLength": ${text.length},
                            "hasContext": ${context.isNotBlank()}
                        }
                        """.trimIndent()
                    }
                }
            )
        )

        // 工具2: 获取阅读建议
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "get_reading_tips",
                        description = "基于当前阅读内容提供阅读建议，包括理解难点、人物关系梳理、情节预测等",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "bookName" to mapOf(
                                    "type" to "string",
                                    "description" to "书名"
                                ),
                                "currentChapter" to mapOf(
                                    "type" to "string",
                                    "description" to "当前章节标题"
                                ),
                                "recentContent" to mapOf(
                                    "type" to "string",
                                    "description" to "最近阅读的文本内容"
                                )
                            ),
                            "required" to listOf("bookName")
                        )
                    )
                ),
                executor = { args ->
                    val bookName = args["bookName"]?.toString() ?: "未知书籍"
                    val chapter = args["currentChapter"]?.toString() ?: ""
                    val content = args["recentContent"]?.toString() ?: ""
                    // 返回结构化数据供 LLM 组织回答
                    """
                    {
                        "bookName": "${ToolUtils.escapeJson(bookName)}",
                        "chapter": "${ToolUtils.escapeJson(chapter)}",
                        "hasContent": ${content.isNotBlank()},
                        "contentLength": ${content.length}
                    }
                    """.trimIndent()
                }
            )
        )
    }
}