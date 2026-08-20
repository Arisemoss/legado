package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.BookSourceAnalyzer
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/**
 * 书源修复提案（**写操作**）。
 * 只产出 pending_confirm 提案，不直接写回；确认后由 [io.legado.app.ai.bridge.SourceRuleWriter] 落地。
 */
class SuggestSourceFixTool(private val analyzer: BookSourceAnalyzer) : ToolDefinition {
    override val id = "suggest_source_fix"
    override val info = ToolDefinitionInfo(
        name = "suggest_source_fix",
        description = "分析书源问题并产出修复提案，须用户确认后才生效",
        parameters = listOf(
            ToolParam("sourceUrl", "string", "书源 URL", required = true)
        )
    )
    override val category = "书源"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val url = args["sourceUrl"]?.toString()
            ?: return ToolResult(text = """{"error":"缺少书源URL"}""")
        return runCatching {
            val rules = analyzer.rules(url)
            val proposal = mapOf("url" to url, "analysis" to rules)
            ToolResult(
                text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to proposal)),
                state = ToolResultState.PENDING_CONFIRM
            )
        }.getOrElse { ToolResult(text = """{"error":${Gson().toJson(it.localizedMessage)}}""") }
    }
}