package io.legado.app.ai.bridge

import io.legado.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppController] 默认实现：直接操作 App.db 书架数据。
 */
class DefaultAppController : AppController {

    override suspend fun listShelf(keyword: String?): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val dao = App.db.bookDao()
            val books = if (keyword.isNullOrBlank()) {
                dao.all
            } else {
                dao.liveDataSearch(keyword).value.orEmpty()
            }
            books.map {
                mapOf(
                    "name" to it.name,
                    "author" to it.author.orEmpty(),
                    "bookUrl" to it.bookUrl,
                    "chapter" to it.durChapterTitle.orEmpty(),
                    "progressIndex" to it.durChapterIndex,
                    "progressPos" to it.durChapterPos
                )
            }
        }

    override suspend fun locateBook(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val book = App.db.bookDao().findByName(bookName).firstOrNull()
            if (book == null) {
                emptyMap()
            } else {
                mapOf(
                    "name" to book.name,
                    "bookUrl" to book.bookUrl,
                    "author" to book.author.orEmpty()
                )
            }
        }

    override suspend fun removeFromShelf(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val hit = App.db.bookDao().findByName(bookName).firstOrNull()
            if (hit == null) {
                mapOf("ok" to false, "message" to "书架中未找到《$bookName》")
            } else {
                App.db.bookDao().delete(hit)
                mapOf("ok" to true, "message" to "已将《${hit.name}》移出书架")
            }
        }
}