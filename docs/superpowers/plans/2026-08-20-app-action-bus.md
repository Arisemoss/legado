# App Action Bus（AI 操控整个软件）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立「应用动作总线」，让 Agent 能真实驱动 Legado（搜索/开书/书架增删/跳转导航），而不仅是只读聊天；首批落地看书/书架 + 导航垂直切片。

**Architecture:** 在现有 AI bridge（只读 BookFetcher/ChapterReader/BookSourceAnalyzer）旁新增 `AppController`（写数据层）与 `AppNav`（导航请求）。工具在 `ToolContext.onNavigate` 上发出导航目标，`AgentHubActivity` 消费后经 anko `startActivity` 真实打开阅读/搜索界面。写操作沿用 `manualConfirm + PENDING_CONFIRM` 确认流。

**Tech Stack:** Kotlin, AndroidX Activity, anko(`startActivity`/`IntentDataHelp`), Room(`App.db.bookDao`), coroutines, StateFlow。

---

## 文件结构

- Create `app/src/main/java/io/legado/app/ai/bridge/AppController.kt` — 动作总线写/查接口 + `AppNav` 导航 DTO。
- Create `app/src/main/java/io/legado/app/ai/bridge/DefaultAppController.kt` — 用 `App.db.bookDao` 实现书架增删查与定位。
- Modify `app/src/main/java/io/legado/app/ai/bridge/AiBridge.kt` — 增加 `appController` 字段。
- Modify `app/src/main/java/io/legado/app/ai/AiPlatform.kt` — 装配 `DefaultAppController`。
- Modify `app/src/main/java/io/legado/app/ai/tool/ToolContext.kt` — 增加 `onNavigate: MutableStateFlow<AppNav?>`。
- Create `app/src/main/java/io/legado/app/ai/tool/impl/BookShelfTool.kt` — `list_shelf` / `open_book` / `remove_book` 工具（remove 为写操作需确认）。
- Modify `app/src/main/java/io/legado/app/ai/tool/impl/ToolRegistryFactory.kt` — 注册新工具。
- Modify `app/src/main/java/io/legado/app/ai/ui/AgentHubActivity.kt` — 消费导航请求，`startActivity<ReadBookActivity>`。
- Modify `app/src/main/java/io/legado/app/ai/ui/AgentHubViewModel.kt` — 初始化 `ToolContext` 时为每个会话建立导航接收容器。

> 其余三域（全局搜索/书源管理/设置）作为后续计划复用同一套动作总线 + 导航缝（见「后续域」）。

---

## Task 1: AppNav 导航 DTO 与 ToolContext 导航缝

**Files:**
- Create: `app/src/main/java/io/legado/app/ai/bridge/AppController.kt`
- Modify: `app/src/main/java/io/legado/app/ai/tool/ToolContext.kt`

- [ ] **Step 1: 定义导航目标与动作总线接口**

在 `AppController.kt` 中定义导航 DTO 与接口：

```kotlin
package io.legado.app.ai.bridge

/**
 * Agent 导航目标：通知宿主 App 执行真实跳转（由 AgentHubActivity 消费）。
 */
sealed class AppNav {
    /** 打开阅读界面；bookUrl 非空则直接定位，否则按 bookName 查书架 */
    data class OpenBook(val bookUrl: String?, val bookName: String?) : AppNav()
    /** 进入全局搜索并填入关键词 */
    data class GlobalSearch(val keyword: String) : AppNav()
    /** 打开书架列表 */
    data object ToBookshelf : AppNav()
}

/**
 * 全软件动作总线：向 Agent 暴露「读写 App 数据」的能力。
 * 只读能力见 BookFetcher/ChapterReader/BookSourceAnalyzer；导航经 [AppNav] 由宿主消费。
 */
interface AppController {
    suspend fun listShelf(keyword: String?): List<Map<String, Any>>
    suspend fun locateBook(bookName: String): Map<String, Any>      // 空 map 表示未入架
    suspend fun removeFromShelf(bookName: String): Map<String, Any>
}
```

- [ ] **Step 2: ToolContext 增加导航缝**

在 `ToolContext` 增加 `onNavigate`：

```kotlin
class ToolContext(
    var sessionId: Long,
    val preset: AiPreset = AiPreset(),
    val onConfirmRequested: MutableStateFlow<ConfirmRequest?> = MutableStateFlow(null),
    val onNavigate: MutableStateFlow<io.legado.app.ai.bridge.AppNav?> = MutableStateFlow(null)
) {
    val stopRequested = MutableStateFlow(false)
    var allowConfirm: Boolean = true
}
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/bridge/AppController.kt app/src/main/java/io/legado/app/ai/tool/ToolContext.kt
git commit -m "feat(ai): 动作总线 AppNav 与 ToolContext 导航缝"
```

---

## Task 2: DefaultAppController 实现

**Files:**
- Create: `app/src/main/java/io/legado/app/ai/bridge/DefaultAppController.kt`

- [ ] **Step 1: 实现书架读/写**

```kotlin
package io.legado.app.ai.bridge

import io.legado.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [AppController] 默认实现：直接操作 App.db 书架数据。
 */
class DefaultAppController : AppController {

    override suspend fun listShelf(keyword: String?): List<Map<String, Any>> =
        withContext(Dispatchers.IO) {
            val dao = App.db.bookDao()
            val books = if (keyword.isNullOrBlank()) dao.all else dao.liveDataSearch(keyword).value.orEmpty()
            books.map {
                mapOf(
                    "name" to it.name,
                    "author" to it.author,
                    "bookUrl" to it.bookUrl,
                    "chapter" to it.durChapterTitle,
                    "progressIndex" to it.durChapterIndex,
                    "progressPos" to it.durChapterPos
                )
            }
        }

    override suspend fun locateBook(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val book = App.db.bookDao().findByName(bookName).firstOrNull()
            if (book == null) {
                emptyMap()
            } else {
                mapOf("name" to book.name, "bookUrl" to book.bookUrl, "author" to book.author)
            }
        }

    override suspend fun removeFromShelf(bookName: String): Map<String, Any> =
        withContext(Dispatchers.IO) {
            val hit = App.db.bookDao().findByName(bookName).firstOrNull()
            if (hit == null) {
                mapOf("ok" to false, "message" to "书架中未找到《$bookName》")
            } else {
                App.db.bookDao().delete(hit)
                mapOf("ok" to true, "message" to "已将《${hit.name}》移出书架")
            }
        }
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/bridge/DefaultAppController.kt
git commit -m "feat(ai): DefaultAppController 书架读写实现"
```

---

## Task 3: AiBridge 装配 AppController

**Files:**
- Modify: `app/src/main/java/io/legado/app/ai/bridge/AiBridge.kt`
- Modify: `app/src/main/java/io/legado/app/ai/AiPlatform.kt`

- [ ] **Step 1: AiBridge 增加 AppController**

```kotlin
class AiBridge(
    val bookFetcher: BookFetcher,
    val chapterReader: ChapterReader,
    val sourceAnalyzer: BookSourceAnalyzer,
    val appController: AppController
)
```

- [ ] **Step 2: AiPlatform 装配**

在 `AiPlatform.kt` 的 `bridge = AiBridge(...)` 处补一个实参：

```kotlin
bridge = AiBridge(
    bookFetcher = DefaultBookFetcher(),
    chapterReader = DefaultChapterReader(),
    sourceAnalyzer = DefaultBookSourceAnalyzer(),
    appController = DefaultAppController()
)
```

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/bridge/AiBridge.kt app/src/main/java/io/legado/app/ai/AiPlatform.kt
git commit -m "feat(ai): AiBridge 装配 AppController"
```

---

## Task 4: 书架/开书工具

**Files:**
- Create: `app/src/main/java/io/legado/app/ai/tool/impl/BookShelfTool.kt`

- [ ] **Step 1: 实现 list_shelf / open_book / remove_book**

```kotlin
package io.legado.app.ai.tool.impl

import com.google.gson.Gson
import io.legado.app.ai.bridge.AiBridge
import io.legado.app.ai.bridge.AppNav
import io.legado.app.ai.model.ToolDefinition
import io.legado.app.ai.model.ToolDefinitionInfo
import io.legado.app.ai.model.ToolParam
import io.legado.app.ai.model.ToolResult
import io.legado.app.ai.model.ToolResultState
import io.legado.app.ai.tool.ToolContext

/** 查看书架 */
class ListShelfTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "list_shelf"
    override val info = ToolDefinitionInfo(
        name = "list_shelf",
        description = "列出书架中的书籍；可按书名关键词过滤",
        parameters = listOf(ToolParam("keyword", "string", "书名关键词，缺省列出全部", required = false))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val shelf = bridge.appController.listShelf(args["keyword"]?.toString())
        if (shelf.isEmpty()) return ToolResult(text = """{"error":"书架为空"}""")
        return ToolResult(text = Gson().toJson(mapOf("books" to shelf)))
    }
}

/** 打开书籍（导航到阅读器） */
class OpenBookTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "open_book"
    override val info = ToolDefinitionInfo(
        name = "open_book",
        description = "打开一本书进入阅读界面；默认打开当前阅读进度所在书籍",
        parameters = listOf(ToolParam("bookName", "string", "书名，缺省用当前阅读上下文", required = false))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = false

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: ctx.preset.bookName ?: return ToolResult("""{"error":"缺少书名"}""")
        val hit = bridge.appController.locateBook(name)
        val bookUrl = hit["bookUrl"]?.toString()
        (ctx.onNavigate.value = AppNav.OpenBook(bookUrl, name))
        return ToolResult(
            text = Gson().toJson(
                mapOf("opened" to true, "book" to name, "toReader" to true, "bookUrl" to bookUrl)
            )
        )
    }
}

/** 移出书架（写操作，需确认）：execute 产提案，onApproved 才真正删库 */
class RemoveBookTool(private val bridge: AiBridge) : ToolDefinition {
    override val id = "remove_book"
    override val info = ToolDefinitionInfo(
        name = "remove_book",
        description = "把指定书籍移出书架（不删除本地文件，仅移出书架）",
        parameters = listOf(ToolParam("bookName", "string", "书名", required = true))
    )
    override val category = "书架"
    override val enabled = true
    override val manualConfirm = true

    override suspend fun execute(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: return ToolResult("""{"error":"缺少书名"}""")
        return ToolResult(
            text = Gson().toJson(mapOf("status" to "pending_confirm", "proposal" to mapOf("action" to "remove_book", "bookName" to name))),
            state = ToolResultState.PENDING_CONFIRM
        )
    }

    override suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult {
        val name = args["bookName"]?.toString() ?: return ToolResult("""{"error":"缺少书名"}""")
        val r = bridge.appController.removeFromShelf(name)
        return ToolResult(text = Gson().toJson(r))
    }
}
```

> 说明：写工具采用**两阶段确认**——`execute` 只产出确认提案（PENDING_CONFIRM）；运行时等待用户确认后调用 `onApproved` 真正写库。基础能力见 Task 6。

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/tool/impl/BookShelfTool.kt
git commit -m "feat(ai): 书架 list/open/remove 工具"
```

---

## Task 5: 注册新工具

**Files:**
- Modify: `app/src/main/java/io/legado/app/ai/tool/impl/ToolRegistryFactory.kt`

- [ ] **Step 1: 注册书架工具**

```kotlin
fun buildRegistry(bridge: AiBridge): ToolRegistry {
    val r = ToolRegistry()
    // ... 现有工具 ...
    // 书架
    r.register(ListShelfTool(bridge))
    r.register(OpenBookTool(bridge))
    r.register(RemoveBookTool(bridge))
    return r
}
```

- [ ] **Step 2: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/tool/impl/ToolRegistryFactory.kt
git commit -m "feat(ai): 注册书架工具"
```

---

## Task 6: 确认后写落（onApproved 两阶段基础）

这是让「AI 真正操控软件写操作」成立的**关键修复**。现状：`SuggestSourceFixTool` 只产出 PENDING_CONFIRM 提案，确认后运行时仅把 approved 打标回喂模型，**从不真正写库**（现存空洞）。本任务补上「确认后才写」的回调。

**Files:**
- Modify: `app/src/main/java/io/legado/app/ai/model/ToolDefinition.kt`
- Modify: `app/src/main/java/io/legado/app/ai/runtime/AgentRuntime.kt`

- [ ] **Step 1: ToolDefinition 增加 onApproved 钩子（带默认实现，不破坏既有工具）**

```kotlin
interface ToolDefinition {
    // ... 既有成员 ...

    /** 用户确认写操作后调用，返回真正写库后的结果。默认为确认通过的回包。 */
    suspend fun onApproved(ctx: ToolContext, args: Map<String, Any?>): ToolResult =
        ToolResult(text = """{"status":"approved"}""")
}
```

- [ ] **Step 2: AgentRuntime 在确认通过后调用 onApproved**

把 PENDING_CONFIRM 分支改为：确认通过 → 调 `onApproved` 真正执行写，并把写后结果回喂模型；否决 → 原样标记 denied 回喂。

```kotlin
ToolResultState.PENDING_CONFIRM -> {
    ctx.onConfirmRequested.value = ConfirmRequest(call.id, res.args)
    val approved = awaitApproval(ctx, call.id)
    val finalResult = if (approved) {
        try { res.def.onApproved(ctx, res.args) } catch (e: Exception) {
            ToolResult(text = "{\"error\":${Gson().toJson(e.localizedMessage)}}", error = AgentError(AgentErrorCode.TOOL_FAILED, "onApproved"))
        }
    } else {
        ToolResult(
            text = Gson().toJson(mapOf("status" to "denied", "tool" to res.def.id)),
            error = AgentError(AgentErrorCode.NO_PERMISSION, "user rejected")
        )
    }
    messages += executor.toolMessage(call, finalResult)
}
```

> 需要 import：`com.google.gson.Gson`、`io.legado.app.ai.model.ToolResult`（现有）、`AgentException`/`AgentError`/`AgentErrorCode`（同包）。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/model/ToolDefinition.kt app/src/main/java/io/legado/app/ai/runtime/AgentRuntime.kt
git commit -m "fix(ai): 确认后经 onApproved 真正写库"
```

---

## Task 7: 导航消费（AgentHub 打开阅读器）

**Files:**
- Modify: `app/src/main/java/io/legado/app/ai/ui/AgentHubActivity.kt`
- Modify: `app/src/main/java/io/legado/app/ai/ui/AgentHubViewModel.kt`

- [ ] **Step 1: ViewModel 暴露导航持有**

`AgentHubViewModel` 已持有 `ctx: ToolContext`，`onNavigate` 就在其上。确保 Activity 能订阅它即可（ctx 是私有字段，暴露 getter）：

```kotlin
val navigation get() = ctx.onNavigate
```

- [ ] **Step 2: Activity 消费导航并打开阅读器**

在 `AgentHubActivity.initVm()` 内新增一个轮询 job，消费 `vm.navigation`：

```kotlin
uiJobs += launch {
    while (isActive) {
        val nav = vm.navigation.value
        if (nav != null) {
            vm.navigation.value = null // 消费掉，避免重复跳转
            when (nav) {
                is io.legado.app.ai.bridge.AppNav.OpenBook -> openReader(nav)
                is io.legado.app.ai.bridge.AppNav.GlobalSearch,
                io.legado.app.ai.bridge.AppNav.ToBookshelf -> {
                    // 全局搜索/书架页跳转属于后续子计划，先消费掉避免重复触发
                }
            }
        }
        delay(200)
    }
}

private fun openReader(nav: AppNav.OpenBook) {
    if (nav.bookUrl.isNullOrBlank()) { toast("未定位到《${nav.bookName}》，可能未加入书架"); return }
    startActivity<io.legado.app.ui.book.read.ReadBookActivity>(
        Pair("bookUrl", nav.bookUrl),
        Pair("inBookshelf", true)
    )
}
```

> 注：`GlobalSearch` 的任务落到后续「全局搜索计划」，此处仅留占位导航。`startActivity<T>`/`Pair` 来自 `org.jetbrains.anko`，本项目 `BookInfoActivity` 已有同款用法可复用。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/io/legado/app/ai/ui/AgentHubActivity.kt app/src/main/java/io/legado/app/ai/ui/AgentHubViewModel.kt
git commit -m "feat(ai): AgentHub 消费导航打开阅读器"
```

---

## Task 8: 编译验证

- [ ] **Step 1: 编译**

```bash
export ANDROID_SDK_ROOT=/opt/android-sdk ANDROID_HOME=/opt/android-sdk JAVA_HOME=/root/.local/share/mise/installs/java/11.0.2
./gradlew :app:compileAppDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: 修复编译错误并重跑**，直到通过。

---

## 后续域（复用同一动作总线 + 导航缝，另立子计划）

- **全局搜索**：`AppController.plusSearch(keyword)` 经 WebBook 搜索并返回结果；`AppNav.GlobalSearch` 打开 `SearchActivity` 填入关键词。
- **书源管理**：`AppController.toggleSource(url, enabled)` 写 `bookSourceDao.update(source.copy(enabled=...))`；工具设 `manualConfirm=true` 需确认。
- **全局设置**：读写 `AppConfig`/`pref_config_ai` 对应 `PreferKey`；敏感项写走确认。

---

## Self-Review

- **Spec 覆盖**：动作总线 ✅(Task1-3)、看书/书架 ✅(Task4-6)、导航 ✅(Task6)；全局搜索/书源/设置列为后续子计划（Apple-sliced）。
- **占位符扫描**：无 TBD/TODO；唯一软化点是 `GlobalSearch` 等后续，已在计划中显式标注为后续域。
- **类型一致性**：`AppNav`(Task1) 在 Task6 消费、`AppController`(Task1) 在 Task2 实现并在 Task5 注入工具、`onNavigate`(Task1) 在 Task6 订阅，签名前后一致。