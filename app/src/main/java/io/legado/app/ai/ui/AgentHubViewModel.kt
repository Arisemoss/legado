package io.legado.app.ai.ui

import io.legado.app.ai.AiPlatform
import io.legado.app.ai.model.ChatMessage
import io.legado.app.ai.runtime.AgentResult
import io.legado.app.ai.runtime.AgentResultState
import io.legado.app.ai.runtime.ConversationService
import io.legado.app.ai.runtime.SystemPromptBuilder
import io.legado.app.ai.skill.SkillRegistry
import io.legado.app.ai.tool.AiPreset
import io.legado.app.ai.tool.ConfirmRequest
import io.legado.app.ai.tool.ToolContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Hub 中的一条对话气泡 */
data class ChatRow(val role: String, val content: String)

/**
 * Agent Hub 会话状态：拉通「对话 → AgentRuntime → 回流消息/工具确认」。
 * [confirm] 出现即需 UI 弹出二次确认框，将结果反馈 [AgentRuntime.approve]。
 * 会话经 [ConversationService] 落库，重开 App 可恢复历史。
 */
class AgentHubViewModel(
    private val preset: AiPreset = AiPreset(),
    private val conversation: ConversationService = ConversationService(window = 50)
) {

    private val runtime = AiPlatform.runtime
    private val ctx = ToolContext(sessionId = -1L, preset = preset)
    private val systemPrompt: String by lazy {
        SystemPromptBuilder(SkillRegistry()).build()
    }

    val sessionId = MutableStateFlow(-1L)
    val messages = MutableStateFlow<List<ChatRow>>(emptyList())
    val typing = MutableStateFlow(false)
    val confirm = MutableStateFlow<ConfirmRequest?>(null)

    private var turns = ArrayList<Pair<String, String>>()
    private var collectorJob: Job? = null

    /** 初始化：创建会话并从房间加载历史。由 Activity 在 onCreate 之后调用 */
    suspend fun init() {
        val id = conversation.create()
        sessionId.value = id
        ctx.sessionId = id
        // 从持久化历史恢复上下文（仅还原用户与助手文本，跳过工具内部消息）
        val loaded = conversation.loadChat(id)
        turns = ArrayList(loaded.flatMap { m ->
            when {
                m.role == "user" && !m.content.isNullOrBlank() -> listOf(m.content to "")
                m.role == "assistant" && m.toolCalls.isNullOrEmpty() && !m.content.isNullOrBlank() ->
                    listOf("" to m.content)
                else -> emptyList()
            }
        })
        messages.value = loaded.mapNotNull { m ->
            when {
                m.role == "user" || (m.role == "assistant" && m.toolCalls.isNullOrEmpty()) ->
                    ChatRow(m.role, m.content ?: "")
                else -> null
            }
        }
    }

    /** 开始监听工具确认事件；由 Activity onCreate 调用一次 */
    fun start(scope: CoroutineScope) {
        if (collectorJob != null) return
        collectorJob = scope.launch {
            while (isActive) {
                val req = ctx.onConfirmRequested.value
                if (req != null && confirm.value?.confirmToken != req.confirmToken) {
                    confirm.value = req
                }
                delay(200)
            }
        }
    }

    fun send(text: String, scope: CoroutineScope) {
        if (text.isBlank()) return
        // 一次新的对话开始，清除上一次遗留的停止标志，避免会话被永久中断
        ctx.stopRequested.value = false
        scope.launch {
            val sid = sessionId.value
            messages.value = messages.value + ChatRow("user", text)
            typing.value = true
            conversation.appendText(sid, "user", text)
            val history = turns.flatMap { (u, a) ->
                listOf(ChatMessage("user", u), ChatMessage("assistant", a))
            }
            val result: AgentResult = runCatching {
                runtime.execute(text, history, ctx, systemPrompt)
            }.getOrElse {
                AgentResult(it.localizedMessage ?: "执行出错", AgentResultState.ERROR)
            }
            turns.add(text to result.answer)
            typing.value = false
            messages.value = messages.value + ChatRow("assistant", result.answer)
            conversation.appendText(sid, "assistant", result.answer)
            // 该次会话已结束，复位确认状态并清空桥接层遗留的确认源
            confirm.value = null
            ctx.onConfirmRequested.value = null
        }
    }

    fun approve(token: String, approved: Boolean) {
        runtime.approve(token, approved)
        // 清除已消费的确认源，避免 UI 轮询重复弹出同一确认
        ctx.onConfirmRequested.value = null
        confirm.value = null
    }

    fun stop() {
        ctx.stopRequested.value = true
    }

    fun dispose() {
        collectorJob?.cancel()
        ctx.stopRequested.value = true
    }
}