package io.legado.app.ai.ui

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import io.legado.app.ai.ModelManager
import io.legado.app.ai.model.AiModelConfig

/**
 * AI 模型配置对话框
 */
object AiConfigDialog {

    fun show(context: Context) {
        val config = ModelManager.getConfig()

        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val baseUrlInput = EditText(context).apply {
            hint = "API Base URL"
            setText(config.baseUrl)
            setSingleLine(true)
        }
        inputLayout.addView(baseUrlInput)

        val apiKeyInput = EditText(context).apply {
            hint = "API Key"
            setText(config.apiKey)
            setSingleLine(true)
        }
        inputLayout.addView(apiKeyInput)

        val modelInput = EditText(context).apply {
            hint = "模型名称 (如 gpt-4o-mini)"
            setText(config.name)
            setSingleLine(true)
        }
        inputLayout.addView(modelInput)

        AlertDialog.Builder(context)
            .setTitle("AI 模型配置")
            .setView(inputLayout)
            .setPositiveButton("保存") { _, _ ->
                val newConfig = AiModelConfig(
                    baseUrl = baseUrlInput.text.toString().trim(),
                    apiKey = apiKeyInput.text.toString().trim(),
                    name = modelInput.text.toString().trim().ifBlank { "gpt-4o-mini" }
                )
                ModelManager.saveConfig(newConfig)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}