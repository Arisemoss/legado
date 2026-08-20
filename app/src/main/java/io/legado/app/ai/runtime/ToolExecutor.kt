package io.legado.app.ai.runtime

import com.google.gson.JsonParser
import io.legado.app.ai.model.AgentError
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext
import io.legado.app.ai.tool.ToolRegistry

/**
 * Pi 内核风格的 ToolExecutor 三阶段流水线：
 *  ① 参数解析与校验（resolve）
 *  ② 工具执行（invoke，异常自愈不炸弹循环）
 *  ③ 结果格式化/回填（toolMessage / errorMessage）
 *
 * 写操作（PENDING_CONFIRM）的二次确认由 [AgentRuntime] 在主循环接管。
 */
sealed class ToolResolution {
    data class Ready(val def: ToolDefinition, val args: Map<String, Any>) : ToolResolution()
    data class Error(val toolName: String, val message: String) : ToolResolution()
}

class ToolExecutor(private val registry: ToolRegistry) {

    /** 阶段①：解析 arguments JSON 并按工具 schema 校验必填 / 枚举取值。 */
    fun resolve(call: ToolCallData): ToolResolution {
        val def = registry.find(call.name)
            ?: return ToolResolution.Error(call.name, "工具不存在: ${call.name}")
        val raw = parseRaw(call.arguments)
            ?: return ToolResolution.Error(call.name, "参数不是合法 JSON 对象")
        val violation = validate(def, raw)
        if (violation != null) return ToolResolution.Error(call.name, violation)
        return ToolResolution.Ready(def, raw)
    }

    /** 阶段②：执行工具；任何异常都收敛为「失败」的 ToolResult，避免中断 Agent 循环。 */
    suspend fun invoke(def: ToolDefinition, ctx: ToolContext, args: Map<String, Any>): ToolResult = try {
        def.execute(ctx, args)
    } catch (e: Exception) {
        ToolResult(
            text = jsonError(def.id, e.localizedMessage ?: "tool error"),
            state = ToolResultState.OK,
            error = AgentError(AgentErrorCode.TOOL_FAILED, "tool error")
        )
    }

    /** 阶段③：生成回喂给模型的标准 tool 消息；[approved]=false 时回填拒绝结果。 */
    fun toolMessage(call: ToolCallData, result: ToolResult, approved: Boolean = true): ChatMessage =
        ChatMessage(
            role = "tool",
            content = if (approved) result.text else """{"status":"denied","tool":"${call.name}"}""",
            toolCallId = call.id
        )

    /** 阶段③：参数/工具级错误的 tool 消息，模型可据此修正重试。 */
    fun errorMessage(call: ToolCallData, message: String): ChatMessage =
        ChatMessage(role = "tool", content = jsonError(call.name, message), toolCallId = call.id)

    private fun parseRaw(arguments: String): Map<String, Any>? {
        val element = try {
            JsonParser().parse(arguments)
        } catch (e: Exception) {
            return null
        }
        if (!element.isJsonObject) return null
        val map = mutableMapOf<String, Any>()
        element.asJsonObject.entrySet().forEach { (k, v) ->
            map[k] = when {
                v.isJsonNull -> ""
                v.isJsonPrimitive -> {
                    val p = v.asJsonPrimitive
                    when {
                        p.isString -> p.asString
                        p.isBoolean -> p.asBoolean
                        p.isNumber -> p.asDouble
                        else -> p.asString
                    }
                }
                v.isJsonObject -> v.asJsonObject.toString()
                else -> v.asJsonArray.toString()
            }
        }
        return map
    }

    private fun validate(def: ToolDefinition, args: Map<String, Any>): String? {
        for (p in def.info.parameters) {
            if (p.required && !args.containsKey(p.name)) return "缺少必填参数: ${p.name}"
            p.enum?.let { allowed ->
                val v = args[p.name]
                if (v != null && v.toString() !in allowed) {
                    return "参数 ${p.name} 取值无效: $v（可选: ${allowed.joinToString("/")}）"
                }
            }
        }
        return null
    }

    private fun jsonError(tool: String, message: String): String =
        """{"error":{"tool":"${escape(tool)}","message":"${escape(message)}"}}"""

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}