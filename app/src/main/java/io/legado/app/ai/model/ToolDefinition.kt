package io.legado.app.ai.model

import io.legado.app.ai.tool.ToolContext

/**
 * 工具参数描述，用于生成 OpenAI function-calling tools schema。
 */
data class ToolParam(
    val name: String,
    val type: String,        // "string" | "integer" | "boolean" | "object"
    val description: String,
    val required: Boolean = false,
    val enum: List<String>? = null
)

data class ToolDefinitionInfo(
    val name: String,
    val description: String,
    val parameters: List<ToolParam>
)

/**
 * 工具抽象接口。所有可被 Agent 调用的能力都以该接口实现。
 * 写操作（[manualConfirm]=true）必须返回 [ToolResultState.PENDING_CONFIRM]，经确认后才生效。
 */
interface ToolDefinition {
    val id: String
    val info: ToolDefinitionInfo
    val category: String          // skill: 选书/读书/懂书/书源
    val enabled: Boolean
    val manualConfirm: Boolean    // true => 写操作需二次确认

    suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult
}

/**
 * 工具执行结果。[text] 会回喂给 LLM；[state] 标记当前流程节点。
 */
data class ToolResult(
    val text: String,
    val state: ToolResultState = ToolResultState.OK,
    val error: AgentError? = null
)

enum class ToolResultState { OK, PENDING_CONFIRM, DENIED }