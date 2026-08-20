package io.legado.app.ai

import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.bridge.DefaultBookFetcher
import io.legado.app.ai.bridge.DefaultBookSourceAnalyzer
import io.legado.app.ai.bridge.DefaultChapterReader
import io.legado.app.ai.runtime.AgentRuntime
import io.legado.app.ai.runtime.OpenAIClient
import io.legado.app.ai.tool.impl.buildRegistry

/**
 * AI 平台统一装配点。
 * 在 [io.legado.app.App.onCreate] 初始化 client / bridge / registry / runtime，
 * 供 Agent Hub 与其 UI 消费。
 */
object AiPlatform {

    @Volatile
    private var initialized = false

    lateinit var runtime: AgentRuntime
        private set
    lateinit var bridge: AiBridge
        private set

    @Synchronized
    fun init() {
        if (initialized) return
        initialized = true
        val config = ModelManager.getConfig()
        val client = OpenAIClient(
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.name,
            timeoutMillis = config.timeoutMillis
        )
        bridge = AiBridge(
            bookFetcher = DefaultBookFetcher(),
            chapterReader = DefaultChapterReader(),
            sourceAnalyzer = DefaultBookSourceAnalyzer()
        )
        runtime = AgentRuntime(
            client = client,
            registry = buildRegistry(bridge),
            maxRounds = config.maxRounds,
            maxTokens = 16_000L,
            confirmTimeoutMs = config.timeoutMillis
        )
    }
}