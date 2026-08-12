package io.legado.app.ai

import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition

/**
 * AI 工具注册中心，管理所有可被 AI Agent 调用的工具
 */
object ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    data class Tool(
        val definition: ToolDefinition,
        val executor: suspend (Map<String, Any?>) -> String
    )

    fun register(tool: Tool) {
        tools[tool.definition.function.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getToolDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.definition }
    }

    fun getTool(name: String): Tool? {
        return tools[name]
    }

    fun getAllTools(): List<Tool> = tools.values.toList()
}