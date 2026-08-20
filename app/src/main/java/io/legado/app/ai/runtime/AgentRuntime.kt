package io.legado.app.ai.runtime

import com.google.gson.JsonParser
import io.legado.app.ai.model.AgentError
import io.legado.app.ai.model.AgentErrorCode
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.FunctionCall
import io.legado.app.ai.model.ToolCall
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.ai.tool.ToolContext
import io.legado.app.ai.tool.ToolRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

data class AgentResult(
    val answer: String,
    val state: AgentResultState = AgentResultState.DONE
)

enum class AgentResultState { DONE, STOPPED, BUDGET_EXCEEDED, ERROR }

/**
 * Agent 引擎：非流式多轮 function-calling 循环。
 * 支持中断（[ToolContext.stopRequested]）、预算上限、以及写操作 pending_confirm 异步确认。
 */
class AgentRuntime(
    private val client: ChatModelClient,
    private val registry: ToolRegistry,
    private val maxRounds: Int = 5,
    private val maxTokens: Long = 16_000L,
    private val confirmTimeoutMs: Long = 300_000L
) {
    private val approvals = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)

    /** 供 UI 调用的确认入口；token 与 [ConfirmRequest.confirmToken] 对应 */
    fun approve(confirmToken: String, approved: Boolean) {
        approvals.offer(confirmToken to approved)
    }

    suspend fun execute(
        userPrompt: String,
        history: List<ChatMessage>,
        ctx: ToolContext,
        systemPrompt: String
    ): AgentResult {
        val messages = ArrayList<ChatMessage>()
        messages += ChatMessage(role = "system", content = systemPrompt)
        messages += history
        messages += ChatMessage(role = "user", content = userPrompt)

        var billed = 0L
        repeat(maxRounds) {
            if (ctx.stopRequested.value) return AgentResult(lastAnswer(messages), AgentResultState.STOPPED)

            val completion = try {
                client.complete(messages, registry.toOpenAiSchema(), stream = false)
            } catch (e: AgentException) {
                val toolMsg = ChatMessage(
                    role = "tool",
                    content = """{"error":{"tool":"_client","code":"${e.code.name}","retryable":${e.code.retryable}}}"""
                )
                messages += toolMsg
                return AgentResult(
                    "模型调用失败：${e.message}",
                    if (e.code.retryable) AgentResultState.ERROR else AgentResultState.ERROR
                )
            }

            billed += 512 // 占位计费，阶段2改为从响应 usage 累加
            if (billed > maxTokens) {
                messages += ChatMessage("assistant", content = completion.content)
                return AgentResult(completion.content ?: "", AgentResultState.BUDGET_EXCEEDED)
            }

            if (completion.content != null) {
                messages += ChatMessage(role = "assistant", content = completion.content)
            }

            val calls = completion.toolCalls ?: return AgentResult(
                completion.content ?: "无回复",
                AgentResultState.DONE
            )

            for (call in calls) {
                if (ctx.stopRequested.value) return AgentResult(lastAnswer(messages), AgentResultState.STOPPED)
                val def = registry.find(call.name) ?: continue

                val args = try {
                    parseArgs(call.arguments)
                } catch (e: Exception) {
                    messages += ChatMessage(
                        role = "tool",
                        content = """{"error":{"tool":"${call.name}","message":"参数解析失败"}}""",
                        toolCallId = call.id
                    )
                    continue
                }

                val result = try {
                    def.execute(ctx, args)
                } catch (e: Exception) {
                    ToolResult(
                        text = """{"error":{"tool":"${call.name}","message":"${e.localizedMessage ?: "tool error"}"}}""",
                        state = ToolResultState.OK,
                        error = AgentError(AgentErrorCode.TOOL_FAILED, "tool error")
                    )
                }

                messages += ChatMessage(
                    role = "assistant",
                    content = null,
                    toolCalls = listOf(ToolCall(call.id, "function", FunctionCall(call.name, call.arguments)))
                )

                when (result.state) {
                    ToolResultState.PENDING_CONFIRM -> {
                        ctx.onConfirmRequested.value = ConfirmRequest(call.id, args)
                        if (awaitApproval(ctx, call.id)) {
                            messages += ChatMessage(role = "tool", content = result.text, toolCallId = call.id)
                        } else {
                            messages += ChatMessage(
                                role = "tool",
                                content = """{"status":"denied","tool":"${call.name}"}""",
                                toolCallId = call.id
                            )
                        }
                    }
                    else -> messages += ChatMessage(role = "tool", content = result.text, toolCallId = call.id)
                }
            }
        }
        return AgentResult(lastAnswer(messages), AgentResultState.DONE)
    }

    private suspend fun awaitApproval(ctx: ToolContext, token: String): Boolean {
        while (true) {
            if (ctx.stopRequested.value) return false
            val event = withTimeoutOrNull(confirmTimeoutMs) { approvals.receive() } ?: return false
            if (event.first == token) return event.second
            // token 不匹配说明是历史确认请求，忽略并继续等待当前 token
        }
    }

    private fun lastAnswer(messages: List<ChatMessage>): String {
        for (i in messages.indices.reversed()) {
            val m = messages[i]
            if (m.role == "assistant" && !m.content.isNullOrBlank()) return m.content
        }
        return ""
    }

    /** 把 OpenAI 返回的 arguments JSON 字符串解析为扁平 Map；value 转为 String/Double/Boolean/List/Map */
    private fun parseArgs(arguments: String): Map<String, Any> {
        val element = JsonParser().parse(arguments)
        if (!element.isJsonObject) return emptyMap()
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
}