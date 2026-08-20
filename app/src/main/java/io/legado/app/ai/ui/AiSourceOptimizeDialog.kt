package io.legado.app.ai.ui

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.gson.JsonObject
import io.legado.app.App
import io.legado.app.ai.AiAgent
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.*
import io.legado.app.utils.GSON
import kotlinx.coroutines.*

/**
 * AI 书源分析优化对话框
 * 回传书源连通性测试 + 规则详情，AI 生成结构化修复建议，用户确认后写回书源
 */
class AiSourceOptimizeDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var job: Job? = null

    private val systemPrompt = """
你是一个书源分析修复助手，集成在阅读APP中。用户会提供书源 URL 或要求分析所有书源。
你可以使用的工具：
- test_book_source：对书源做真实连通性测试（网络可达性、搜索规则可用性）
- get_source_rules：获取书源完整规则配置
- analyze_book_source：检查规则完整性
- get_source_stats / list_book_sources：获取统计与书源列表

判断流程：先用 test_book_source 测试连通性，再用 get_source_rules 获取规则，将测试结果与规则对照，找出问题并给出修复建议。

针对单个书源时，在了解问题后，输出一段简要的中文分析，并在最后附上一段**只含 JSON** 的修复计划，格式如下（不要加 Markdown 代码块标记）：
{"sourceUrl":"书源URL","issues":["问题1","问题2"],"changes":[{"field":"searchUrl","new":"新的URL","reason":"原因"}]}

field 可选值：searchUrl、exploreUrl、loginUrl、header、bookSourceName、enabled、ruleSearch、ruleBookInfo、ruleToc、ruleContent、ruleExplore。
若某字段无法确定正确的值，不要盲目填写，宁可在 issues 中说明而不给出 change。
请用中文回复。
"""

    fun show(sourceUrl: String? = null) {
        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val urlInput = EditText(context).apply {
            hint = "书源 URL（留空分析所有书源）"
            setText(sourceUrl ?: "")
            setSingleLine(true)
        }
        inputLayout.addView(urlInput)

        val progressBar = ProgressBar(context).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        inputLayout.addView(progressBar)

        val resultText = TextView(context).apply {
            visibility = View.GONE
            textSize = 14f
            setLineSpacing(0f, 1.3f)
        }
        inputLayout.addView(resultText)

        dialog = AlertDialog.Builder(context)
            .setTitle("AI 书源分析")
            .setView(inputLayout)
            .setPositiveButton("分析") { _, _ -> }
            .setNeutralButton("配置") { _, _ ->
                AiConfigDialog.show(context)
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener {
                job?.cancel()
                job = null
            }
            .show()

        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            val url = urlInput.text.toString().trim()
            urlInput.isEnabled = false
            progressBar.visibility = View.VISIBLE
            resultText.visibility = View.GONE
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

            val query = if (url.isNotBlank()) {
                "请用 test_book_source 测试书源 $url 的连通性，再用 get_source_rules 获取它的规则，然后分析问题并给出修复计划（含 JSON changes）。"
            } else {
                "请先用 get_source_stats 获取统计，再用 list_book_sources 列出书源，给出总体分析报告。"
            }

            job = CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    AiAgent.execute(
                        userMessage = query,
                        systemPrompt = systemPrompt
                    )
                }

                progressBar.visibility = View.GONE
                resultText.visibility = View.VISIBLE

                if (result != null) {
                    resultText.text = result
                    // 若结果是针对单个书源且可解析出修复计划，提供应用入口
                    if (url.isNotBlank()) {
                        tryApplyFixes(url, result)
                    }
                } else {
                    resultText.text = "分析失败，请检查 AI 配置是否正确。"
                }

                urlInput.isEnabled = true
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
        }
    }

    /**
     * 尝试从结果中解析修复计划，若有则弹出确认框，用户确认后写回书源
     */
    private fun tryApplyFixes(sourceUrl: String, result: String) {
        val json = extractJsonObject(result) ?: return
        val obj: JsonObject = try {
            GSON.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            return
        }
        val changes = obj.getAsJsonArray("changes") ?: return
        if (changes.size() == 0) return

        val sb = StringBuilder("将对该书源应用以下修改：\n\n")
        val items = mutableListOf<Pair<String, String>>()
        for (el in changes) {
            val field = el.asJsonObject.get("field")?.asString ?: continue
            val new = el.asJsonObject.get("new")?.asString ?: continue
            sb.append("• $field\n  改为：${new.take(120)}\n")
            items.add(field to new)
        }
        sb.append("\n确认无误后应用？")

        AlertDialog.Builder(context)
            .setTitle("确认应用修复建议")
            .setMessage(sb.toString())
            .setPositiveButton("应用") { _, _ ->
                applyChanges(sourceUrl, items)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun applyChanges(sourceUrl: String, items: List<Pair<String, String>>) {
        CoroutineScope(Dispatchers.IO).launch {
            var success = 0
            var failed = 0
            try {
                val source = App.db.bookSourceDao().getBookSource(sourceUrl)
                if (source != null) {
                    for ((field, new) in items) {
                        if (applyField(source, field, new)) success++ else failed++
                    }
                    App.db.bookSourceDao().update(source)
                } else {
                    failed = items.size
                }
            } catch (e: Exception) {
                failed = items.size
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (success > 0) "已应用 $success 项修改${if (failed > 0) "，$failed 项失败" else ""}" else "应用失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applyField(source: BookSource, field: String, new: String): Boolean {
        return try {
            when (field) {
                "searchUrl" -> source.searchUrl = new
                "exploreUrl" -> source.exploreUrl = new
                "loginUrl" -> source.loginUrl = new
                "header" -> source.header = new
                "bookSourceName" -> source.bookSourceName = new
                "enabled" -> source.enabled = new.toBoolean()
                "ruleSearch" -> source.ruleSearch = GSON.fromJson(new, SearchRule::class.java)
                "ruleBookInfo" -> source.ruleBookInfo = GSON.fromJson(new, BookInfoRule::class.java)
                "ruleToc" -> source.ruleToc = GSON.fromJson(new, TocRule::class.java)
                "ruleContent" -> source.ruleContent = GSON.fromJson(new, ContentRule::class.java)
                "ruleExplore" -> source.ruleExplore = GSON.fromJson(new, ExploreRule::class.java)
                else -> return false
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 从文本中提取第一段合法 JSON 对象（忽略前后散文）
     */
    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(start, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    fun dismiss() {
        job?.cancel()
        job = null
        dialog?.dismiss()
    }
}