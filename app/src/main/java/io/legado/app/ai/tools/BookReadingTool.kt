package io.legado.app.ai.tools

import io.legado.app.App
import io.legado.app.ai.ToolRegistry
import io.legado.app.ai.model.FunctionDefinition
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.data.entities.Book
import io.legado.app.help.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI 书籍阅读工具
 * 让 AI 能读取指定书籍的章节正文，用于章节总结、人物关系分析等阅读辅助
 */
object BookReadingTool {

    private const val DEFAULT_MAX_CHARS = 4000

    fun register() {
        // 章节总结
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "summarize_chapter",
                        description = "读取指定书籍的某一章节正文并返回给AI，供其对章节内容进行总结、提炼要点、分析情节。默认使用当前阅读进度对应章节",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "bookName" to mapOf(
                                    "type" to "string",
                                    "description" to "书名，用于在书架中定位书籍"
                                ),
                                "chapterIndex" to mapOf(
                                    "type" to "integer",
                                    "description" to "章节序号（从0开始），缺省时使用当前阅读进度"
                                ),
                                "bookUrl" to mapOf(
                                    "type" to "string",
                                    "description" to "书籍唯一地址，若提供则优先据此定位"
                                )
                            ),
                            "required" to listOf("bookName")
                        )
                    )
                ),
                executor = { args ->
                    val bookName = args["bookName"]?.toString()
                    if (bookName == null) {
                        "{\"error\": \"缺少书名\"}"
                    } else {
                        val chapterIndex = (args["chapterIndex"] as? Number)?.toInt()
                        val bookUrl = args["bookUrl"]?.toString()
                        fetchChapter(bookName, chapterIndex, bookUrl, DEFAULT_MAX_CHARS)
                    }
                }
            )
        )

        // 人物关系分析
        ToolRegistry.register(
            ToolRegistry.Tool(
                definition = ToolDefinition(
                    function = FunctionDefinition(
                        name = "analyze_characters",
                        description = "读取指定书籍的章节正文并返回给AI，供其分析人物性格、梳理人物关系、追踪角色发展",
                        parameters = mapOf(
                            "type" to "object",
                            "properties" to mapOf(
                                "bookName" to mapOf(
                                    "type" to "string",
                                    "description" to "书名，用于在书架中定位书籍"
                                ),
                                "chapterIndex" to mapOf(
                                    "type" to "integer",
                                    "description" to "章节序号（从0开始），缺省时使用当前阅读进度"
                                ),
                                "bookUrl" to mapOf(
                                    "type" to "string",
                                    "description" to "书籍唯一地址，若提供则优先据此定位"
                                )
                            ),
                            "required" to listOf("bookName")
                        )
                    )
                ),
                executor = { args ->
                    val bookName = args["bookName"]?.toString()
                    if (bookName == null) {
                        "{\"error\": \"缺少书名\"}"
                    } else {
                        val chapterIndex = (args["chapterIndex"] as? Number)?.toInt()
                        val bookUrl = args["bookUrl"]?.toString()
                        fetchChapter(bookName, chapterIndex, bookUrl, DEFAULT_MAX_CHARS)
                    }
                }
            )
        )
    }

    /**
     * 定位书籍并读取指定章节正文，优先读缓存，无缓存则联网抓取
     */
    private suspend fun fetchChapter(
        bookName: String,
        chapterIndex: Int?,
        bookUrl: String?,
        maxChars: Int
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val book = resolveBook(bookName, bookUrl)
                if (book == null) {
                    return@withContext "{\"error\": \"未找到书籍: $bookName，请确认已在书架中\"}"
                }

                val chapters = App.db.bookChapterDao().getChapterList(book.bookUrl)
                if (chapters.isEmpty()) {
                    return@withContext "{\"error\": \"书籍「${ToolUtils.escapeJson(book.name)}」还没有目录，请先联网获取目录\"}"
                }

                var chapter = if (chapterIndex != null) chapters.getOrNull(chapterIndex) else null
                if (chapter == null) {
                    chapter = chapters.getOrNull(book.durChapterIndex) ?: chapters.first()
                }

                var content = BookHelp.getContent(book, chapter)
                var source = "cache"
                if (content.isNullOrBlank()) {
                    source = "web"
                    val bookSource = App.db.bookSourceDao().getBookSource(book.origin)
                    content = if (bookSource != null) {
                        try {
                            WebBook(bookSource).getContentSuspend(
                                book = book,
                                bookChapter = chapter,
                                scope = CoroutineScope(Dispatchers.IO)
                            )
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                if (content.isNullOrBlank()) {
                    content = ""
                    source = "empty"
                }

                buildJson(
                    bookName = book.name,
                    author = book.author,
                    chapterTitle = chapter.title,
                    chapterIndex = chapter.index,
                    content = content.take(maxChars),
                    contentLength = content.length,
                    source = source
                )
            } catch (e: Exception) {
                "{\"error\": \"读取章节失败: ${e.message}\"}"
            }
        }
    }

    private fun resolveBook(bookName: String, bookUrl: String?): Book? {
        if (!bookUrl.isNullOrBlank()) {
            App.db.bookDao().getBook(bookUrl)?.let { return it }
        }
        return App.db.bookDao().findByName(bookName).firstOrNull()
    }

    private fun buildJson(
        bookName: String,
        author: String?,
        chapterTitle: String,
        chapterIndex: Int,
        content: String,
        contentLength: Int,
        source: String
    ): String {
        return """
        {
            "bookName": "${ToolUtils.escapeJson(bookName)}",
            "author": "${ToolUtils.escapeJson(author ?: "")}",
            "chapterTitle": "${ToolUtils.escapeJson(chapterTitle)}",
            "chapterIndex": $chapterIndex,
            "contentSource": "$source",
            "contentLength": $contentLength,
            "content": "${ToolUtils.escapeJson(content)}"
        }
        """.trimIndent()
    }
}