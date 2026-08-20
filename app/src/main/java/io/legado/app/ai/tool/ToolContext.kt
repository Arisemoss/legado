package io.legado.app.ai.tool

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 上下文中可注入到工具的领域信息（来自阅读/搜索/书源页面的 preset）。
 * 后续阶段会注入 bridge 只读服务（BookFetcher/ChapterReader/BookSourceAnalyzer）。
 */
data class AiPreset(
    val bookName: String? = null,
    val chapterTitle: String? = null,
    val content: String? = null,        // 章节正文片段（如注入）
    val sourceUrl: String? = null,
    val searchKeyword: String? = null
)

/**
 * 写操作二次确认请求。
 */
data class ConfirmRequest(val confirmToken: String, val proposal: Map<String, Any>)

class ToolContext(
    val sessionId: Long,
    val preset: AiPreset = AiPreset(),
    val onConfirmRequested: MutableStateFlow<ConfirmRequest?> = MutableStateFlow(null)
) {
    val stopRequested = MutableStateFlow(false)
}