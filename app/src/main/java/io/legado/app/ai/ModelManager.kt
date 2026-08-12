package io.legado.app.ai

import com.google.gson.Gson
import io.legado.app.ai.model.*
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.util.concurrent.TimeUnit

/**
 * 管理 AI 模型的配置与 API 调用
 */
object ModelManager {

    private val gson = Gson()
    private val jsonMediaType = MediaType.parse("application/json; charset=utf-8")

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    fun getConfig(): AiModelConfig {
        val baseUrl = io.legado.app.App.INSTANCE.getPrefString(PreferKey.aiBaseUrl)
            ?: "https://api.openai.com/v1"
        val apiKey = io.legado.app.App.INSTANCE.getPrefString(PreferKey.aiApiKey) ?: ""
        val model = io.legado.app.App.INSTANCE.getPrefString(PreferKey.aiModel) ?: "gpt-4o-mini"
        return AiModelConfig(
            name = model,
            baseUrl = baseUrl,
            apiKey = apiKey
        )
    }

    fun saveConfig(config: AiModelConfig) {
        io.legado.app.App.INSTANCE.putPrefString(PreferKey.aiBaseUrl, config.baseUrl)
        io.legado.app.App.INSTANCE.putPrefString(PreferKey.aiApiKey, config.apiKey)
        io.legado.app.App.INSTANCE.putPrefString(PreferKey.aiModel, config.name)
    }

    /**
     * 发送非流式聊天请求
     */
    suspend fun chatCompletion(
        request: ChatCompletionRequest,
        config: AiModelConfig = getConfig()
    ): Result<ChatCompletionResponse> {
        if (config.apiKey.isBlank()) {
            return Result.failure(IllegalStateException("请先在设置中配置 AI API Key"))
        }

        return try {
            val jsonBody = gson.toJson(request)
            val url = "${config.baseUrl.trimEnd('/')}/chat/completions"

            val body = RequestBody.create(jsonMediaType, jsonBody)
            val httpRequest = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = try {
                    gson.fromJson(responseBody, ErrorResponse::class.java)?.message
                } catch (_: Exception) { null }
                return Result.failure(
                    RuntimeException(errorMsg ?: "HTTP ${response.code}: $responseBody")
                )
            }

            val result = gson.fromJson(responseBody, ChatCompletionResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}