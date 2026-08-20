package io.legado.app.ai.model

/**
 * AI 服务商预设
 */
data class AiProvider(
    val code: String,
    val name: String,
    val baseUrl: String,
    val models: List<String>
)

/**
 * 内置的 AI 服务商预设列表，用于方便地切换供应商与模型
 */
object AiProviders {

    val list = listOf(
        AiProvider(
            code = "deepseek",
            name = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            models = listOf("deepseek-chat", "deepseek-reasoner")
        ),
        AiProvider(
            code = "tongyi",
            name = "通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            models = listOf("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long")
        ),
        AiProvider(
            code = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            models = listOf("glm-4-plus", "glm-4", "glm-4-flash")
        ),
        AiProvider(
            code = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo")
        ),
        AiProvider(
            code = "ollama",
            name = "Ollama (本地)",
            baseUrl = "http://localhost:11434/v1",
            models = listOf("llama3", "qwen2.5", "deepseek-r1")
        )
    )

    fun get(code: String?): AiProvider? =
        if (code.isNullOrBlank()) null else list.find { it.code == code }

    fun findByModel(model: String?): AiProvider? =
        if (model.isNullOrBlank()) null else list.find { it.models.contains(model) }

    fun findByBaseUrl(baseUrl: String?): AiProvider? {
        if (baseUrl.isNullOrBlank()) return null
        val normalized = baseUrl.trimEnd('/')
        return list.find { normalized.startsWith(it.baseUrl) || it.baseUrl.startsWith(normalized) }
    }
}