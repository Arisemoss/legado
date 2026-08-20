package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.tool.ToolContext

/** 读取章节正文：经 bridge 定位书籍并返回原文，供各读书/懂书工具复用 */
private suspend fun readChapter(
    bridge: AiBridge,
    ctx: ToolContext,
    args: Map<String, Any?>
): ToolResult {
    val bookName = args["bookName"]?.toString() ?: (ctx.preset.bookName ?: "")
    if (bookName.isBlank()) return ToolResult(text = """{"error":"缺少书名"}""")
    val chapterTitle = args["chapterTitle"]?.toString()
    val content = bridge.chapterReader.chapter(bookName, chapterTitle)
    return if (content == null) {
        ToolResult(text = Gson().toJson(mapOf("error" to "未找到书籍或章节: $bookName")))
    } else {
        ToolResult(text = Gson().toJson(mapOf("bookName" to bookName, "content" to content)))
    }
}

/** 读书：读取章节 */
class ReadChapterTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "read_chapter"
    override val info = ToolDefinitionInfo(
        name = "read_chapter",
        description = "读取指定书籍（默认当前阅读进度）某一章节的正文",
        parameters = listOf(
            ToolParam("bookName", "string", "书名", required = true),
            ToolParam("chapterTitle", "string", "章节标题，缺省用当前阅读进度", required = false)
        )
    )
    override val category = "读书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        readChapter(bridge, ctx, args)
}

/** 读书：章节总结 */
class SummarizeChapterTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "summarize_chapter"
    override val info = ToolDefinitionInfo(
        name = "summarize_chapter",
        description = "读取指定章节正文供总结、提炼要点、分析情节",
        parameters = listOf(
            ToolParam("bookName", "string", "书名", required = true),
            ToolParam("chapterTitle", "string", "章节标题，缺省用当前阅读进度", required = false)
        )
    )
    override val category = "读书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        readChapter(bridge, ctx, args)
}

/** 读书：情节回顾 */
class PlotRecapTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "plot_recap"
    override val info = ToolDefinitionInfo(
        name = "plot_recap",
        description = "读取指定章节正文供梳理情节、回顾剧情",
        parameters = listOf(
            ToolParam("bookName", "string", "书名", required = true),
            ToolParam("chapterTitle", "string", "章节标题，缺省用当前阅读进度", required = false)
        )
    )
    override val category = "读书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        readChapter(bridge, ctx, args)
}

/** 懂书：解释文本 */
class ExplainTextTool : ToolDefinition {
    override val id = "explain_text"
    override val info = ToolDefinitionInfo(
        name = "explain_text",
        description = "解释指定文本段落，供含义分析、背景知识、修辞手法解读；text缺省用当前阅读正文",
        parameters = listOf(
            ToolParam("text", "string", "需要解释的文本", required = false),
            ToolParam("context", "string", "可选上下文（书名/章节名）", required = false)
        )
    )
    override val category = "懂书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val text = args["text"]?.toString() ?: (ctx.preset.content ?: "")
        if (text.isBlank()) return ToolResult(text = """{"error":"缺少文本内容"}""")
        return ToolResult(
            text = Gson().toJson(
                mapOf(
                    "text" to text.take(500),
                    "context" to (args["context"]?.toString() ?: ""),
                    "textLength" to text.length
                )
            )
        )
    }
}

/** 懂书：人物分析 */
class AnalyzeCharactersTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "analyze_characters"
    override val info = ToolDefinitionInfo(
        name = "analyze_characters",
        description = "读取指定书籍章节正文供分析人物性格、梳理人物关系",
        parameters = listOf(
            ToolParam("bookName", "string", "书名", required = true),
            ToolParam("chapterTitle", "string", "章节标题，缺省用当前阅读进度", required = false)
        )
    )
    override val category = "懂书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        readChapter(bridge, ctx, args)
}

/** 懂书：主题分析 */
class AnalyzeThemeTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "analyze_theme"
    override val info = ToolDefinitionInfo(
        name = "analyze_theme",
        description = "读取指定书籍章节正文供分析主题思想、人物设定",
        parameters = listOf(
            ToolParam("bookName", "string", "书名", required = true),
            ToolParam("chapterTitle", "string", "章节标题，缺省用当前阅读进度", required = false)
        )
    )
    override val category = "懂书"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        readChapter(bridge, ctx, args)
}