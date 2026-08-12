package io.legado.app.ai.tools

import io.legado.app.App
import io.legado.app.ai.ToolRegistry
import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 书源分析与优化工具
 * 让 AI Agent 能够分析书源配置、检测问题、优化规则
 */
object BookSourceTool {

    fun register() {
        // 工具1: 分析书源
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "analyze_book_source",
                        description = "分析书源的配置，检测可能的问题（如规则是否完整、URL是否可达等），返回分析报告",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "sourceUrl" to mapOf(
                                    "type" to "string",
                                    "description" to "书源的 URL 地址"
                                )
                            ),
                            "required" to listOf("sourceUrl")
                        )
                    )
                ),
                executor = { args ->
                    val sourceUrl = args["sourceUrl"]?.toString() ?: return@ToolRegistry.Tool.executor "{\"error\": \"缺少书源URL\"}"
                    analyzeSourceInternal(sourceUrl)
                }
            )
        )

        // 工具2: 获取书源列表
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "list_book_sources",
                        description = "获取当前所有书源的列表，包含名称、URL、是否启用、分组等信息",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "group" to mapOf(
                                    "type" to "string",
                                    "description" to "按分组筛选（可选）"
                                ),
                                "enabled" to mapOf(
                                    "type" to "boolean",
                                    "description" to "只显示启用的书源（可选）"
                                )
                            )
                        )
                    )
                ),
                executor = { args ->
                    val group = args["group"]?.toString()
                    val enabledOnly = args["enabled"] as? Boolean ?: false
                    listSourcesInternal(group, enabledOnly)
                }
            )
        )

        // 工具3: 书源统计
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "get_source_stats",
                        description = "获取书源统计信息，包括总数、各分组数量、各类型规则完整性等",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to emptyMap<String, Any>()
                        )
                    )
                )
            ) {
                getSourceStatsInternal()
            }
        )
    }

    private suspend fun analyzeSourceInternal(sourceUrl: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val source = App.db.bookSourceDao().getBookSource(sourceUrl)
                if (source == null) {
                    return@withContext "{\"error\": \"未找到书源: $sourceUrl\"}"
                }

                val issues = mutableListOf<String>()
                val recommendations = mutableListOf<String>()

                // 检查各规则
                if (source.searchUrl.isNullOrBlank()) {
                    issues.add("缺少搜索URL")
                } else {
                    recommendations.add("搜索URL已配置")
                }

                if (source.ruleSearch?.bookList.isNullOrBlank()) {
                    issues.add("缺少搜索列表规则 (ruleSearch.bookList)")
                }

                if (source.ruleBookInfo == null ||
                    (source.ruleBookInfo?.name.isNullOrBlank() &&
                     source.ruleBookInfo?.author.isNullOrBlank())
                ) {
                    issues.add("书籍信息规则不完整")
                }

                if (source.ruleToc?.chapterList.isNullOrBlank()) {
                    issues.add("缺少目录列表规则 (ruleToc.chapterList)")
                }

                if (source.ruleContent?.content.isNullOrBlank()) {
                    issues.add("缺少正文内容规则 (ruleContent.content)")
                }

                buildAnalysisJson(source, issues, recommendations)
            } catch (e: Exception) {
                "{\"error\": \"分析失败: ${e.message}\"}"
            }
        }
    }

    private suspend fun listSourcesInternal(group: String?, enabledOnly: Boolean): String {
        return withContext(Dispatchers.IO) {
            try {
                var sources = App.db.bookSourceDao().all
                if (enabledOnly) {
                    sources = sources.filter { it.enabled }
                }
                if (!group.isNullOrBlank()) {
                    sources = sources.filter { it.bookSourceGroup?.contains(group) == true }
                }

                buildSourceListJson(sources)
            } catch (e: Exception) {
                "{\"error\": \"获取书源列表失败: ${e.message}\"}"
            }
        }
    }

    private suspend fun getSourceStatsInternal(): String {
        return withContext(Dispatchers.IO) {
            try {
                val sources = App.db.bookSourceDao().all
                val enabled = sources.count { it.enabled }
                val disabled = sources.size - enabled
                val groups = sources.mapNotNull { it.bookSourceGroup }
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .groupBy { it }
                    .mapValues { it.value.size }

                val withSearch = sources.count { !it.searchUrl.isNullOrBlank() }
                val withExplore = sources.count { !it.exploreUrl.isNullOrBlank() }

                """
                {
                    "total": ${sources.size},
                    "enabled": $enabled,
                    "disabled": $disabled,
                    "groups": ${GSON.toJson(groups)},
                    "withSearchUrl": $withSearch,
                    "withExploreUrl": $withExplore
                }
                """.trimIndent()
            } catch (e: Exception) {
                "{\"error\": \"统计失败: ${e.message}\"}"
            }
        }
    }

    private fun buildAnalysisJson(
        source: BookSource,
        issues: List<String>,
        recommendations: List<String>
    ): String {
        return """
        {
            "sourceName": "${ToolUtils.escapeJson(source.bookSourceName)}",
            "sourceUrl": "${ToolUtils.escapeJson(source.bookSourceUrl)}",
            "enabled": ${source.enabled},
            "type": ${source.bookSourceType},
            "group": "${ToolUtils.escapeJson(source.bookSourceGroup ?: "")}",
            "hasSearchUrl": ${!source.searchUrl.isNullOrBlank()},
            "hasExploreUrl": ${!source.exploreUrl.isNullOrBlank()},
            "hasLoginUrl": ${!source.loginUrl.isNullOrBlank()},
            "issues": ${GSON.toJson(issues)},
            "recommendations": ${GSON.toJson(recommendations)}
        }
        """.trimIndent()
    }

    private fun buildSourceListJson(sources: List<BookSource>): String {
        val sb = StringBuilder()
        sb.append("{\"sources\": [")
        sources.forEachIndexed { index, source ->
            if (index > 0) sb.append(",")
            sb.append("""
                {
                    "name": "${ToolUtils.escapeJson(source.bookSourceName)}",
                    "url": "${ToolUtils.escapeJson(source.bookSourceUrl)}",
                    "group": "${ToolUtils.escapeJson(source.bookSourceGroup ?: "")}",
                    "enabled": ${source.enabled},
                    "hasSearch": ${!source.searchUrl.isNullOrBlank()},
                    "weight": ${source.weight}
                }
            """.trimIndent())
        }
        sb.append("]}")
        return sb.toString()
    }
}