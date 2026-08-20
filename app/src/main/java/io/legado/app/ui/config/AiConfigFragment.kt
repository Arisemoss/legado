package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.base.BasePreferenceFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ATH
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.prefs.NameListPreference
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

/**
 * AI 配置：服务商预设 / Base URL / API Key（加密）/ 模型 / 流式 / 轮询超时 / 最大轮数 / 会话窗口
 */
class AiConfigFragment : BasePreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** 服务商预设表：id -> Pair(baseUrl, 推荐模型) */
    private val providers: Map<String, Pair<String, String>> = mapOf(
        "deepseek" to ("https://api.deepseek.com/v1" to "deepseek-chat"),
        "qwen" to ("https://dashscope.aliyuncs.com/compatible-mode/v1" to "qwen-plus"),
        "zhipu" to ("https://open.bigmodel.cn/api/paas/v4" to "glm-4-flash"),
        "openai" to ("https://api.openai.com/v1" to "gpt-4o-mini"),
        "ollama" to ("http://localhost:11434/v1" to "llama3")
    )

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_ai)
        findPreference<NameListPreference>(PreferKey.aiProvider)?.setOnPreferenceChangeListener { _, newValue ->
            applyProviderPreset(newValue as? String)
            true
        }
        findPreference<EditTextPreference>(PreferKey.aiApiKey)?.setOnBindEditTextListener { editText ->
            ATH.setTint(editText, requireContext().accentColor)
            editText.inputType =
                InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
        }
        upAllSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferenceManager.sharedPreferences.registerOnSharedPreferenceChangeListener(this)
        ATH.applyEdgeEffectColor(listView)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.aiProvider,
            PreferKey.aiBaseUrl,
            PreferKey.aiApiKey,
            PreferKey.aiModel,
            PreferKey.aiMaxRounds,
            PreferKey.aiSessionWindow -> upAllSummary()
        }
    }

    private fun applyProviderPreset(providerId: String?) {
        val preset = providers[providerId] ?: return
        ppp(PreferKey.aiBaseUrl, preset.first)
        ppp(PreferKey.aiModel, preset.second)
        upAllSummary()
    }

    private fun ppp(key: String, value: String) {
        putPrefString(key, value)
        findPreference<EditTextPreference>(key)?.summary = value
    }

    private fun upAllSummary() {
        upSummary(PreferKey.aiBaseUrl)
        upSummary(PreferKey.aiModel)
        upSummary(PreferKey.aiMaxRounds)
        upSummary(PreferKey.aiSessionWindow)
        findPreference<EditTextPreference>(PreferKey.aiApiKey)?.let {
            val key = getPrefString(PreferKey.aiApiKey)
            it.summary = if (key.isNullOrEmpty()) "未设置" else "*".repeat(key.length.coerceAtMost(12))
        }
    }

    private fun upSummary(prefKey: String) {
        val pref = findPreference<Preference>(prefKey) ?: return
        pref.summary = getPrefString(prefKey)
    }
}