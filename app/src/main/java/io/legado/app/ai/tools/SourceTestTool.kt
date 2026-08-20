package io.legado.app.ai.tools

import io.legado.app.App
import io.legado.app.ai.ToolRegistry
import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * AI 书源连通性测试工具
 * 对指定书源执行一次真实搜索请求，验证网络可达性和搜索规则可用性
 */
object SourceTestTool {

    fun register() {
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "test_book_source",
                        description = "对指定书源执行真实的连通性测试：会发起一次搜索请求，检测网站是否可达、搜索规则是否可用，返回延迟、结果数量与采样书名等信息",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "sourceUrl" to mapOf(
                                    "type" to "string",
                                    "description" to "要测试的书源 URL"
                                ),
                                "keyword" to mapOf(
                                    "type" to "string",
                                    "description" to "测试用的搜索关键词，默认“我的”"
                                )
                            ),
                            "required" to listOf("sourceUrl")
                        )
                    )
                ),
                executor = { args ->
                    val sourceUrl = args["sourceUrl"]?.toString()
                    if (sourceUrl == null) {
                        "{\"error\": \"缺少书源URL\"}"
                    } else {
                        val keyword = args["keyword"]?.toString()?.ifBlank { "我的" } ?: "我的"
                        testSourceInternal(sourceUrl, keyword)
                    }
                }
            )
        )
    }

    private suspend fun testSourceInternal(sourceUrl: String, keyword: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val source = App.db.bookSourceDao().getBookSource(sourceUrl)
                if (source == null) {
                    return@withContext "{\"error\": \"未找到书源: $sourceUrl\"}"
                }

                if (source.searchUrl.isNullOrBlank()) {
                    return@withContext buildJson(
                        sourceName = source.bookSourceName,
                        sourceUrl = source.bookSourceUrl,
                        ok = false,
                        reachable = false,
                        message = "书源没有配置搜索URL，无法验证网络连通性",
                        hasContentUrl = !source.ruleContent?.content.isNullOrBlank()
                    )
                }

                val start = System.currentTimeMillis()
                val webBook = WebBook(source)
                val results = try {
                    withTimeout(15000L) {
                        webBook.searchBookSuspend(
                            scope = CoroutineScope(Dispatchers.IO),
                            key = keyword,
                            page = 1
                        )
                    }
                } catch (e: TimeoutCancellationException) {
                    return@withContext buildJson(
                        sourceName = source.bookSourceName,
                        sourceUrl = source.bookSourceUrl,
                        ok = false,
                        reachable = false,
                        message = "请求超时（>15 秒）",
                        latencyMs = System.currentTimeMillis() - start
                    )
                } catch (e: Exception) {
                    return@withContext buildJson(
                        sourceName = source.bookSourceName,
                        sourceUrl = source.bookSourceUrl,
                        ok = false,
                        reachable = true,
                        message = "网络可达但搜索失败: ${e.message}",
                        latencyMs = System.currentTimeMillis() - start
                    )
                }

                val latency = System.currentTimeMillis() - start
                val sampleNames = results.take(3).map {
                    ToolUtils.escapeJson(it.name)
                }
                buildJson(
                    sourceName = source.bookSourceName,
                    sourceUrl = source.bookSourceUrl,
                    ok = true,
                    reachable = true,
                    message = results.isEmpty()
                        ? "连接正常，但关键词“$keyword”未搜索到结果"
                        : "连接正常，搜索到 ${results.size} 条结果",
                    latencyMs = latency,
                    searchCount = results.size,
                    sampleNames = sampleNames,
                    hasContentUrl = !source.ruleContent?.content.isNullOrBlank()
                )
            } catch (e: Exception) {
                "{\"error\": \"测试失败: ${e.message}\"}"
            }
        }
    }

    private fun buildJson(
        sourceName: String,
        sourceUrl: String,
        ok: Boolean,
        reachable: Boolean,
        message: String,
        latencyMs: Long? = null,
        searchCount: Int? = null,
        sampleNames: List<String>? = null,
        hasContentUrl: Boolean? = null
    ): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"sourceName\": \"${ToolUtils.escapeJson(sourceName)}\",")
        sb.append("\"sourceUrl\": \"${ToolUtils.escapeJson(sourceUrl)}\",")
        sb.append("\"ok\": $ok,")
        sb.append("\"reachable\": $reachable,")
        sb.append("\"message\": \"${ToolUtils.escapeJson(message)}\"")
        if (latencyMs != null) sb.append(",\"latencyMs\": $latencyMs")
        if (searchCount != null) sb.append(",\"searchCount\": $searchCount")
        if (sampleNames != null && sampleNames.isNotEmpty()) {
            sb.append(",\"sampleNames\": [\"${sampleNames.joinToString("\",\"")}\"]")
        }
        if (hasContentUrl != null) sb.append(",\"hasContentUrl\": $hasContentUrl")
        sb.append("}")
        return sb.toString()
    }
}