package io.legado.app.ai.ui

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import io.legado.app.ai.AiAgent
import kotlinx.coroutines.*

/**
 * AI 阅读助手对话框
 * 在阅读过程中提供上下文解释、人物分析、内容总结等
 */
class AiAssistantDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var job: Job? = null

    fun show(bookName: String? = null, chapterTitle: String? = null, content: String? = null) {
        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // 上下文信息
        if (bookName != null) {
            val contextInfo = TextView(context).apply {
                text = "📖 $bookName${if (chapterTitle != null) " - $chapterTitle" else ""}"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                setPadding(0, 0, 0, 10)
            }
            inputLayout.addView(contextInfo)
        }

        val inputEdit = EditText(context).apply {
            hint = "问 AI 助手：这段在说什么？这个人物是谁？..."
            setMinLines(2)
            setMaxLines(4)
        }
        inputLayout.addView(inputEdit)

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
            .setTitle("AI 阅读助手")
            .setView(inputLayout)
            .setPositiveButton("提问") { _, _ -> }
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
            val query = inputEdit.text.toString().trim()
            if (query.isBlank()) return@setOnClickListener

            inputEdit.isEnabled = false
            progressBar.visibility = View.VISIBLE
            resultText.visibility = View.GONE
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false

            val systemPrompt = """
你是一个阅读助手，帮助用户理解当前阅读的书籍内容。
你可以使用 explain_text 和 get_reading_tips 工具来分析文本。
请用中文回复，回答要简洁、有深度。
"""

            val fullQuery = buildString {
                append("用户提问：$query")
                if (bookName != null) append("\n书籍：$bookName")
                if (chapterTitle != null) append("\n章节：$chapterTitle")
                if (content != null) append("\n当前内容：${content.take(1000)}")
            }

            job = CoroutineScope(Dispatchers.Main).launch {
                val result = withContext(Dispatchers.IO) {
                    AiAgent.execute(
                        userMessage = fullQuery,
                        systemPrompt = systemPrompt
                    )
                }

                progressBar.visibility = View.GONE
                resultText.visibility = View.VISIBLE

                if (result != null) {
                    resultText.text = result
                } else {
                    resultText.text = "请求失败，请检查 AI 配置是否正确。"
                }

                inputEdit.isEnabled = true
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