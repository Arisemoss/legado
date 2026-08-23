package io.legado.app.ui.config

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.View
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.ai.ModelManager
import io.legado.app.ai.log.AiLog
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.model.AiProviderPresets
import io.legado.app.ai.model.ChatCompletionRequest
import io.legado.app.ai.model.ChatMessage
import io.legado.app.base.BasePreferenceFragment
import io.legado.app.constant.PreferKey
import io.legado.app.lib.theme.ATH
import io.legado.app.lib.theme.accentColor
import io.legado.app.ui.widget.prefs.NameListPreference
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 配置：服务商预设 / Base URL / API Key（加密）/ 模型 / 流式 / 轮询超时 / 最大轮数 / 会话窗口 / 测试连接
 * 预设表统一来自 [AiProviderPresets]，与 Hub 状态栏共享。
 */
class AiConfigFragment : BasePreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var testJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_ai)
        addTestConnectionPreference()

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
        testJob?.cancel()
        preferenceManager.sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    /** 动态插入「测试连接」，配完即可一键验证 */
    private fun addTestConnectionPreference() {
        val test = Preference(requireContext())
        test.key = "ai_test_connection"
        test.title = "测试连接"
        test.summary = "用当前配置发一条测试消息，验证 Base URL / Key / 模型是否可用"
        test.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            testConnection(test)
            true
        }
        preferenceScreen.addPreference(test)
    }

    private fun testConnection(preference: Preference) {
        val cfg = AiModelConfig(
            name = getPrefString(PreferKey.aiModel) ?: "gpt-4o-mini",
            baseUrl = getPrefString(PreferKey.aiBaseUrl) ?: "https://api.openai.com/v1",
            apiKey = getPrefString(PreferKey.aiApiKey) ?: "",
            timeoutMillis = 20_000L
        )
        if (cfg.apiKey.isBlank()) {
            preference.summary = "⚠️ 请先填写 API Key"
            return
        }
        preference.summary = "测试中…"
        testJob?.cancel()
        testJob = scope.launch {
            val started = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    ModelManager.chatCompletion(
                        ChatCompletionRequest(
                            model = cfg.name,
                            messages = listOf(ChatMessage("user", "ping")),
                            maxTokens = 8
                        ),
                        config = cfg
                    )
                }
            }
            val elapsed = (System.currentTimeMillis() - started) / 1000.0
            preference.summary = result.fold(
                onSuccess = { resp ->
                    val text = resp?.choices?.firstOrNull()?.message?.content.orEmpty().take(30)
                    io.legado.app.ai.log.AiLog.i(
                        "Test", "连接成功 ${elapsed}s model=${cfg.name} 回复=${text.ifBlank { "(空)" }}"
                    )
                    "✓ 连接成功（${elapsed}s）${if (text.isNotBlank()) " 回复：$text" else ""}"
                },
                onFailure = {
                    io.legado.app.ai.log.AiLog.e("Test", "连接失败 model=${cfg.name} baseUrl=${cfg.baseUrl}", it)
                    "✗ 连接失败：${it.localizedMessage?.take(120)}"
                }
            )
        }
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
        val preset = AiProviderPresets.byId(providerId) ?: return
        ppp(PreferKey.aiBaseUrl, preset.baseUrl)
        preset.models.firstOrNull()?.let { ppp(PreferKey.aiModel, it) }
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
        // 服务商 summary 追加说明
        val providerId = getPrefString(PreferKey.aiProvider)
        findPreference<NameListPreference>(PreferKey.aiProvider)?.let { pref ->
            val preset = AiProviderPresets.byId(providerId)
            pref.summary = if (preset != null) {
                "${preset.label} · ${preset.note}"
            } else {
                "选择服务商可自动填充 Base URL 与推荐模型"
            }
        }
    }

    private fun upSummary(prefKey: String) {
        val pref = findPreference<Preference>(prefKey) ?: return
        pref.summary = getPrefString(prefKey)
    }
}
