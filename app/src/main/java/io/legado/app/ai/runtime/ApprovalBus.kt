package io.legado.app.ai.runtime

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 写操作确认总线。与具体 [AgentRuntime] 实例解耦：
 * 配置热更新会重建 runtime，但未决的确认请求经此总线仍可送达当前等待者，
 * 不再随旧实例的 Channel 一同失效；广播语义也天然丢弃过期 token（各等待者自行过滤）。
 */
object ApprovalBus {

    private val decisions = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 64)

    /** UI 决策入口：[token] 对应某次 pending_confirm 的 call id */
    fun offer(token: String, approved: Boolean) {
        decisions.tryEmit(token to approved)
    }

    /** 等待匹配 [token] 的决策；超时返回 null（调用方按拒绝处理）。非匹配 token 广播忽略，不消耗窗口 */
    suspend fun await(token: String, timeoutMs: Long): Pair<String, Boolean>? =
        withTimeoutOrNull(timeoutMs) { decisions.first { it.first == token } }
}
