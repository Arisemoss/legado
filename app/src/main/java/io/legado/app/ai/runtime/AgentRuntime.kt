package io.legado.app.ai.runtime

import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.model.FunctionCall
import io.legado.app.ai.model.ToolCall
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.ai.tool.ToolContext
import io.legado.app.ai.tool.ToolRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

data class AgentResult(
    val answer: String,
    val state: AgentResultState = AgentResultState.DONE,
    val tokensUsed: Long = 0L,
    val rounds: Int = 0
)

enum class AgentResultState { DONE, STOPPED, BUDGET_EXCEEDED, ERROR }

/**
 * Pi Harness 风格的双嵌套 agentLoop：
 *  - 外层：多轮助手补全（[maxRounds] 预算），产出最终答案；
 *  - 内层：对单轮内的整批 tool_calls 并行执行后再回喂，写操作走二次确认。
 * 工具执行经 [ToolExecutor] 三阶段流水线，真实 token 计费强制预算。
 */
class AgentRuntime(
    private val client: ChatModelClient,
    private val registry: ToolRegistry,
    private val maxRounds: Int = 5,
    private val maxTokens: Long = 32_000L,
    private val maxRetries: Int = 1,
    private val confirmTimeoutMs: Long = 300_000L
) {
    private val executor = ToolExecutor(registry)
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
        var rounds = 0
        repeat(maxRounds) {
            if (ctx.stopRequested.value) {
                return AgentResult(lastAnswer(messages), AgentResultState.STOPPED, billed, rounds)
            }
            rounds++

            val completion = try {
                completeOnce(messages, ctx)
                    ?: return AgentResult(lastAnswer(messages), AgentResultState.STOPPED, billed, rounds)
            } catch (e: AgentException) {
                return AgentResult("模型调用失败：${e.message}", AgentResultState.ERROR, billed, rounds)
            }

            appendAssistant(messages, completion)
            val increment = completion.usage?.totalTokens?.toLong()
                ?: (256L + (completion.content?.length ?: 0) / 3L)
            billed += increment
            if (billed > maxTokens) {
                return AgentResult(completion.content ?: "已达预算上限", AgentResultState.BUDGET_EXCEEDED, billed, rounds)
            }

            val calls = completion.toolCalls
            if (calls.isNullOrEmpty()) {
                return AgentResult(completion.content ?: "无回复", AgentResultState.DONE, billed, rounds)
            }

            // 内层①：整批解析（流水线阶段①），失败项就地回填错误消息
            val resolutions = calls.map { executor.resolve(it) }

            // 内层②：整批并行执行（流水线阶段②）；异常自愈，不中断循环
            val running = coroutineScope {
                resolutions.map { res ->
                    if (res is ToolResolution.Ready) async { executor.invoke(res.def, ctx, res.args) }
                    else null
                }
            }

            // 内层③：按原顺序收集并回填（流水线阶段③），写操作二次确认
            for (i in calls.indices) {
                val call = calls[i]
                when (val res = resolutions[i]) {
                    is ToolResolution.Error -> messages += executor.errorMessage(call, res.message)
                    is ToolResolution.Ready -> {
                        val result = running[i]?.await() ?: continue
                        when (result.state) {
                            ToolResultState.PENDING_CONFIRM -> {
                                ctx.onConfirmRequested.value = ConfirmRequest(call.id, res.args)
                                val approved = awaitApproval(ctx, call.id)
                                messages += executor.toolMessage(call, result, approved)
                            }
                            else -> messages += executor.toolMessage(call, result)
                        }
                    }
                }
            }
        }
        return AgentResult(lastAnswer(messages), AgentResultState.DONE, billed, rounds)
    }

    /**
     * 单次模型补全；对可重试错误做指数退避重试（默认 1 次重试），
     * 停止标志在重试间隙同样生效。重试耗尽抛出 [AgentException]。
     */
    private suspend fun completeOnce(messages: List<ChatMessage>, ctx: ToolContext): ChatCompletion? {
        var attempt = 0
        while (true) {
            if (ctx.stopRequested.value) return null
            try {
                return client.complete(messages, registry.toOpenAiSchema(), stream = false)
            } catch (e: AgentException) {
                if (!e.code.retryable || attempt >= maxRetries) throw e
                attempt++
                delay(1000L * attempt) // 退避 1s, 2s...
            }
        }
    }

    /** 把 OpenAI 返回的 messages 追加进上下文（含 tool_calls，供模型下一轮续接 tool 结果） */
    private fun appendAssistant(messages: MutableList<ChatMessage>, c: ChatCompletion) {
        messages += ChatMessage(
            role = "assistant",
            content = c.content,
            toolCalls = c.toolCalls?.map { ToolCall(it.id, "function", FunctionCall(it.name, it.arguments)) }
        )
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
}