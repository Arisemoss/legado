package io.legado.app.ai.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import io.legado.app.ai.ModelManager
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.model.AiProvider
import io.legado.app.ai.model.AiProviders
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/**
 * AI 模型配置对话框：支持服务商预设一键填充 + 模型下拉选择
 */
object AiConfigDialog {

    fun show(context: Context) {
        val config = ModelManager.getConfig()

        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 20, 60, 20)
        }

        // 服务商下拉
        var selectedProvider: AiProvider? = null
        var selectedModel: String = config.name
        var baseUrlInput: EditText? = null
        var apiKeyInput: EditText? = null
        var modelSpinner: Spinner? = null

        // 服务商标签
        inputLayout.addView(label(context, "服务商"))

        val providerSpinner = Spinner(context)
        // 服务商可选项：内置预设；若当前配置不匹配任何预设，追加“自定义/其他”项以保留原值，避免误覆盖
        val providers = AiProviders.list.toMutableList()
        val matchedPreset = AiProviders.findByBaseUrl(config.baseUrl)
            ?: AiProviders.findByModel(config.name)
        var matched = matchedPreset
        if (matchedPreset == null) {
            matched = AiProvider("custom", "自定义/其他", config.baseUrl, listOf(config.name))
            providers.add(matched)
        }
        val providerNames = providers.map { it.name }
        providerSpinner.adapter =
            ArrayAdapter(context, android.R.layout.simple_spinner_item, providerNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
        val savedProvider = providers.indexOfFirst { it.code == context.getPrefString(PreferKey.aiProvider) }
        val initialProviderIndex = if (savedProvider >= 0) savedProvider else providers.indexOf(matched)
        inputLayout.addView(providerSpinner)

        // 模型标签
        inputLayout.addView(label(context, "模型"))

        var modelAdapter: ArrayAdapter<String>? = null
        fun refreshModels(provider: AiProvider) {
            modelAdapter = ArrayAdapter(
                context, android.R.layout.simple_spinner_item, provider.models
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            modelSpinner?.adapter = modelAdapter
            val idx = provider.models.indexOfFirst { it == selectedModel }
            modelSpinner?.setSelection(if (idx >= 0) idx else 0)
        }

        modelSpinner = Spinner(context)
        inputLayout.addView(modelSpinner)

        // 默认选中与当前配置匹配（若保存过则按已保存的服务商）的下拉项
        val initialProvider = providers[initialProviderIndex]
        selectedProvider = initialProvider
        selectedModel = config.name
        refreshModels(initialProvider)
        providerSpinner.setSelection(initialProviderIndex)

        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = providers[position]
                selectedProvider = provider
                refreshModels(provider)
                baseUrlInput?.setText(provider.baseUrl)
                modelSpinner?.post {
                    selectedModel = provider.models.getOrElse(0) { provider.models.first() }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val provider = selectedProvider
                if (provider != null && position in provider.models.indices) {
                    selectedModel = provider.models[position]
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Base URL
        inputLayout.addView(label(context, "API Base URL"))
        baseUrlInput = EditText(context).apply {
            hint = "API Base URL"
            setText(providers[initialProviderIndex].baseUrl)
            setSingleLine(true)
        }
        inputLayout.addView(baseUrlInput)

        // API Key
        inputLayout.addView(label(context, "API Key"))
        apiKeyInput = EditText(context).apply {
            hint = "API Key（本地 Ollama 可留空）"
            setText(config.apiKey)
            setSingleLine(true)
        }
        inputLayout.addView(apiKeyInput)

        AlertDialog.Builder(context)
            .setTitle("AI 模型配置")
            .setView(inputLayout)
            .setPositiveButton("保存") { _, _ ->
                val newConfig = AiModelConfig(
                    baseUrl = baseUrlInput?.text.toString().trim(),
                    apiKey = apiKeyInput?.text.toString().trim(),
                    name = selectedModel.ifBlank { "gpt-4o-mini" }
                )
                ModelManager.saveConfig(newConfig)
                context.putPrefString(PreferKey.aiProvider, selectedProvider?.code ?: "")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.START
            setPadding(0, 12, 0, 4)
        }
    }
}