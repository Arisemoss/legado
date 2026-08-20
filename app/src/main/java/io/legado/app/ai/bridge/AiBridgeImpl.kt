package io.legado.app.ai.bridge

import com.google.gson.Gson
import io.legado.app.App
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [BookFetcher] 默认实现：跨已启用书源并行/顺序搜索，返回书名/作者/来源。
 */
class DefaultBookFetcher : BookFetcher {

    override suspend fun search(keyword: String, limit: Int): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<Map<String, Any>>()
            val seen = HashSet<String>()
            val sources = App.db.bookSourceDao().allEnabled.take(5)
            for (source in sources) {
                if (result.size >= limit) break
                val found = try {
                    WebBook(source).searchBookSuspend(
                        scope = CoroutineScope(Dispatchers.IO),
                        key = keyword,
                        page = 1
                    )
                } catch (_: Exception) {
                    arrayListOf<SearchBook>()
                }
                for (book in found) {
                    if (result.size >= limit) break
                    if (!seen.add(book.name)) continue
                    result.add(
                        mapOf(
                            "name" to book.name,
                            "author" to book.author,
                            "from" to (book.originName.ifBlank { book.origin })
                        )
                    )
                }
            }
            result
        }

    override suspend fun recommendByName(name: String): List<Map<String, Any>> = search(name, 5)
}

/**
 * [ChapterReader] 默认实现：定位书架书籍，优先读缓存，无缓存联网抓取。
 */
class DefaultChapterReader : ChapterReader {

    override suspend fun chapter(bookName: String, chapterTitle: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val book = resolveBook(bookName)
                    ?: return@withContext null
                val chapters = App.db.bookChapterDao().getChapterList(book.bookUrl)
                if (chapters.isEmpty()) return@withContext null

                val chapter = resolveChapter(book, chapters, chapterTitle)
                    ?: return@withContext null

                var content = BookHelp.getContent(book, chapter)
                if (content.isNullOrBlank()) {
                    val source = App.db.bookSourceDao().getBookSource(book.origin)
                    content = if (source != null) {
                        try {
                            WebBook(source).getContentSuspend(
                                book = book,
                                bookChapter = chapter,
                                scope = CoroutineScope(Dispatchers.IO)
                            )
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                }
                content?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

    private fun resolveBook(bookName: String): Book? =
        App.db.bookDao().findByName(bookName).firstOrNull()

    private fun resolveChapter(
        book: Book,
        chapters: List<BookChapter>,
        chapterTitle: String?
    ): BookChapter? {
        if (chapterTitle != null) {
            chapters.firstOrNull { it.title.contains(chapterTitle) }
                ?.let { return it }
        }
        return chapters.getOrNull(book.durChapterIndex) ?: chapters.firstOrNull()
    }
}

/**
 * [BookSourceAnalyzer] 默认实现：基于书源 DAO 提供的结构化只读信息。
 * 网络连通性测试在 SourceTestTool（阶段2 工具迁移）中进一步细化。
 */
class DefaultBookSourceAnalyzer : BookSourceAnalyzer {

    private companion object {
        val gson = Gson()
    }

    override suspend fun list(): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        App.db.bookSourceDao().allEnabled
            .map {
                mapOf(
                    "name" to it.bookSourceName,
                    "url" to it.bookSourceUrl,
                    "enabled" to it.enabled
                )
            }
    }

    override suspend fun rules(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val source = App.db.bookSourceDao().getBookSource(url)
        if (source == null) {
            mapOf("url" to url, "found" to false)
        } else {
            mapOf(
                "url" to url,
                "found" to true,
                "name" to source.bookSourceName,
                "enabled" to source.enabled,
                "rules" to gson.toJson(source)
            )
        }
    }

    override suspend fun test(url: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val source = App.db.bookSourceDao().getBookSource(url)
        if (source == null) {
            mapOf("url" to url, "status" to "missing", "message" to "书源不存在")
        } else {
            mapOf(
                "url" to url,
                "status" to "ok",
                "name" to source.bookSourceName,
                "enabled" to source.enabled
            )
        }
    }
}