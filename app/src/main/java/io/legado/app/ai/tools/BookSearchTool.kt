package io.legado.app.ai.tools

import io.legado.app.App
import io.legado.app.ai.ToolRegistry
import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope

/**
 * AI 智能搜索工具
 * 让 AI Agent 能够跨书源搜索书籍，理解自然语言搜索意图
 */
object BookSearchTool {

    fun register() {
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "search_books",
                        description = "跨多个书源搜索书籍，支持自然语言查询。返回搜索结果列表，包含书名、作者、简介、封面等信息",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "keyword" to mapOf(
                                    "type" to "string",
                                    "description" to "搜索关键词，如书名、作者名"
                                ),
                                "maxResults" to mapOf(
                                    "type" to "integer",
                                    "description" to "最大返回结果数，默认 20",
                                    "default" to 20
                                )
                            ),
                            "required" to listOf("keyword")
                        )
                    )
                ),
                executor = { args ->
                    val keyword = args["keyword"]?.toString()
                    if (keyword == null) {
                        "{\"error\": \"缺少搜索关键词\"}"
                    } else {
                        val maxResults = (args["maxResults"] as? Number)?.toInt() ?: 20
                        searchBooksInternal(keyword, maxResults)
                    }
                }
            )
        )
    }

    private suspend fun searchBooksInternal(keyword: String, maxResults: Int): String {
        return withContext(Dispatchers.IO) {
            try {
                val sources = App.db.bookSourceDao().all
                    .filter { it.enabled && !it.searchUrl.isNullOrBlank() }
                    .take(5)

                val deferred = sources.map { source ->
                    async(Dispatchers.IO) {
                        try {
                            val webBook = WebBook(source)
                            webBook.searchBookSuspend(
                                scope = CoroutineScope(Dispatchers.IO),
                                key = keyword
                            )
                        } catch (_: Exception) {
                            emptyList<SearchBook>()
                        }
                    }
                }

                val allResults = mutableListOf<SearchBook>()
                deferred.forEach { job ->
                    try {
                        allResults.addAll(job.await())
                    } catch (_: Exception) { }
                }

                val seen = mutableSetOf<String>()
                val deduped = allResults
                    .filter { seen.add(it.bookUrl) }
                    .take(maxResults)

                buildJsonResult(deduped)
            } catch (e: Exception) {
                "{\"error\": \"搜索失败: ${e.message}\"}"
            }
        }
    }

    private fun buildJsonResult(books: List<SearchBook>): String {
        val sb = StringBuilder()
        sb.append("{\"results\": [")
        books.forEachIndexed { index, book ->
            if (index > 0) sb.append(",")
            sb.append("""
                {
                    "name": "${ToolUtils.escapeJson(book.name)}",
                    "author": "${ToolUtils.escapeJson(book.author)}",
                    "origin": "${ToolUtils.escapeJson(book.originName)}",
                    "intro": "${ToolUtils.escapeJson(book.intro?.take(200) ?: "")}",
                    "coverUrl": "${ToolUtils.escapeJson(book.coverUrl ?: "")}",
                    "bookUrl": "${ToolUtils.escapeJson(book.bookUrl)}",
                    "latestChapter": "${ToolUtils.escapeJson(book.latestChapterTitle ?: "")}"
                }
            """.trimIndent())
        }
        sb.append("]}")
        return sb.toString()
    }
}