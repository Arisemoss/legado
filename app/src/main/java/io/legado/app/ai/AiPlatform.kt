package io.legado.app.ai

import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.bridge.DefaultAppController
import io.legado.app.ai.bridge.DefaultBookFetcher
import io.legado.app.ai.bridge.DefaultBookSourceAnalyzer
import io.legado.app.ai.bridge.DefaultChapterReader
import io.legado.app.ai.model.AiModelConfig
import io.legado.app.ai.runtime.AgentRuntime
import io.legado.app.ai.runtime.OpenAIClient
import io.legado.app.ai.tool.impl.buildRegistry

/**
 * AI 平台统一装配点。
 * 在 [io.legado.app.App.onCreate] 初始化 client / bridge / registry / runtime，
 * 供 Agent Hub 与其 UI 消费。
 *
 * [syncConfig] 支持配置热更新：设置页改动后调用即可重建模型客户端，
 * 无需重启 App；bridge（领域桥）不随配置变化，仅装配一次。
 */
object AiPlatform {

    @Volatile
    private var initialized = false

    private var lastConfig: AiModelConfig? = null
    private val lock = Any()

    lateinit var runtime: AgentRuntime
        private set
    lateinit var bridge: AiBridge
        private set

    /** 当前生效的配置（供 UI 状态栏显示） */
    val config: AiModelConfig? get() = lastConfig

    @Synchronized
    fun init() {
        if (initialized) return
        bridge = AiBridge(
            bookFetcher = DefaultBookFetcher(),
            chapterReader = DefaultChapterReader(),
            sourceAnalyzer = DefaultBookSourceAnalyzer(),
            appController = DefaultAppController()
        )
        syncConfig()
    }

    /**
     * 按最新 [ModelManager.getConfig] 重建 runtime/client。
     * 配置未变化时为空操作，可安全地在 onResume 高频调用。
     */
    fun syncConfig() {
        synchronized(lock) {
            // 防御：历史坏数据(如键类型冲突)不应导致宿主页面崩溃，保持旧配置继续可用
            val cfg = runCatching { ModelManager.getConfig() }.getOrElse { lastConfig }
                ?: return
            if (initialized && cfg == lastConfig) return
            val client = OpenAIClient(
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                model = cfg.name,
                timeoutMillis = cfg.timeoutMillis
            )
            runtime = AgentRuntime(
                client = client,
                registry = buildRegistry(bridge),
                maxRounds = cfg.maxRounds,
                maxTokens = 16_000L,
                confirmTimeoutMs = cfg.timeoutMillis,
                preferStream = cfg.stream
            )
            lastConfig = cfg
            initialized = true
        }
    }
}
