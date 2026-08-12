package io.legado.app.ai.ui

import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import io.legado.app.ai.AiAgent
import kotlinx.coroutines.*

/**
 * AI 智能搜索对话框
 * 用户可以用自然语言描述想要的书籍，AI Agent 跨书源搜索并推荐
 */
class AiSearchDialog(private val context: Context) {

    private var dialog: AlertDialog? = null
    private var job: Job? = null

    fun show() {
        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val inputEdit = EditText(context).apply {
            hint = "描述你想找的书籍，例如：找一本修仙类的小说，主角性格沉稳，文笔细腻"
            setMinLines(3)
            setMaxLines(6)
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
            .setTitle("AI 智能搜索")
            .setView(inputLayout)
            .setPositiveButton("搜索") { _, _ -> }
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
你是一个智能搜索助手，集成在阅读APP中。用户会描述他们想找的书籍。
你可以使用 search_books 工具来搜索书籍。
根据搜索结果，向用户推荐最匹配的书籍，并说明推荐理由。
请用中文回复。
"""

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
                } else {
                    resultText.text = "搜索失败，请检查 AI 配置是否正确。"
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