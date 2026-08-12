package io.legado.app.ai.ui

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import io.legado.app.ai.AiAgent
import kotlinx.coroutines.*

/**
 * AI 书源分析优化对话框
 * 分析书源配置，发现问题，提出优化建议
 */
class AiSourceOptimizeDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var job: Job? = null

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

            val systemPrompt = """
你是一个书源分析助手。用户会提供书源 URL 或要求分析所有书源。
你必须使用以下工具来完成任务：
- 如果用户提供了具体 URL，使用 analyze_book_source 工具分析该书源
- 如果用户没有提供 URL，先使用 get_source_stats 工具获取统计信息，再使用 list_book_sources 工具列出书源
请分析书源配置的完整性、发现潜在问题、给出优化建议。
请用中文回复。
"""

            val query = if (url.isNotBlank()) {
                "请使用 analyze_book_source 工具分析书源 $url 的配置情况，检查规则是否完整"
            } else {
                "请先使用 get_source_stats 工具获取所有书源统计信息，再用 list_book_sources 工具列出书源清单，然后给我一份完整的分析报告"
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

                result.fold(
                    onSuccess = { reply ->
                        resultText.text = reply
                    },
                    onFailure = { error ->
                        resultText.text = "分析失败: ${error.message}"
                    }
                )

                urlInput.isEnabled = true
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
            }
        }
    }

    fun dismiss() {
        job?.cancel()
        job = null
        dialog?.dismiss()
    }
}