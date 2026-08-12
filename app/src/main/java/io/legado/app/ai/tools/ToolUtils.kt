package io.legado.app.ai.tools

/**
 * AI 工具公共工具函数
 */
object ToolUtils {

    fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}