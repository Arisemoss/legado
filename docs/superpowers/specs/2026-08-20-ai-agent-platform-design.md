# AI Agent 平台化设计（设置集成 · 小说领域技能）【Rev.2】

- 日期：2026-08-20（Rev.2，依据评审报告修订）
- 范围：把现有散落的 AI 能力（`ai/` 包 + 3 个上下文弹窗 + 独立配置弹窗）重构为 App 一等架构层——**AI Agent 平台**，纳入设置体系，并提供**小说领域**的 Agent 技能包。
- 目标：设置中可完整配置模型并进入「AI 智能助手」中心页自由对话、开关技能、管理持久化会话；阅读/搜索/书源处保留快捷入口并接入中心页；书源写回保持二次确认。

## 1. 总体分层架构

以「AI 一等公民」重构，收敛为若干内聚子包：

```
io.legado.app
├── ai
│   ├── runtime                  AI Agent 运行时（核心引擎）
│   │   ├── AgentRuntime          通用 function-calling 执行器（多轮 + 中断 + 预算）
│   │   ├── ModelManager          模型客户端抽象（OpenAI 兼容 + 预设 + 厂商路由）
│   │   ├── ConversationService   会话（Room 持久化、多会话、裁剪）
│   │   └── SystemPromptBuilder   按会话/技能上下文拼系统提示
│   ├── model                    纯数据模型（ChatMessage / 工具 schema / AiSession/AiMessage 实体）
│   ├── skill                    小说领域技能（声明式定义与组合，不承载执行逻辑）
│   ├── tool                     可插拔工具框架（执行原子）
│   ├── bridge                   领域桥：能力抽象成接口（实现用 WebBook/DB）
│   └── ui                        中心页 + 上下文桥 + 设置项
```

**命名说明（评审 #1/#2）**
- `appservice` → 更名 **`bridge`**：杜绝与 Android `Service` 组件混淆。
- `skill` **不承载运行时类**：`skill/` 仅含声明式 `SkillDefinition`/`SkillRegistry`（技能 = id/名称/分类/描述/所属工具列表），作为**逻辑分组与预设上下文的元数据**；真正的执行原子仍是 `tool/` 的 `ToolDefinition`。无独立执行引擎。

**核心原则**
1. AI 不直接碰 `WebBook`/`App.db`，统一经 `bridge` 接口注入，可测、可替换。
2. Agent 只认识 `ToolDefinition`，不感知业务；新增能力＝注册技能/工具。
3. 会话升级为 Room 持久化（`AiSession`/`AiMessage`）。
4. 设置成为事实入口：`pref_main` 新增「AI 智能助手」；阅读/搜索/书源按钮跳转中心页并注入 preset。

## 2. Agent 运行时（`ai/runtime`）

**`AgentRuntime`**（替代 `AiAgent`）
- 入口 `suspend execute(prompt, toolContext, scope): Flow<AgentEvent>`，多轮 function-calling 循环；
- **取消（评审 #3）**：复用 Kotlin Coroutines，不引入自研 token。整个执行是 single `suspend` 流程，UI 通过 `viewLifecycleOwner.lifecycleScope` 启动；「停止」按钮：① 持有当前 Job 并 `job.cancel()`，② 另设 `MutableStateFlow<Boolean> stopRequested`，运行时在**每轮模型请求前**与**每个工具执行前**检查，置位则优雅终止（保留已生效副作用但不继续）。二者互为双保险。
- **预算粒度（评审 #5）**：一轮「用户提问」= 多次模型请求 + 多次工具调用。预算为**事后累计**：`maxRounds`（默认 5，每轮指的是一次完整的 工具调用 → 模型继续 往返，即最大模型请求数）、`maxTotalTokens`（流式/请求触发时对返回用量累计）。超预算＝**优雅截断**（返回已有内容并附 `BUDGET_EXCEEDED` 状态），不静默拒绝。
- **错误分类（评审 #9，约定错误码）**：工具/网络错误以结构化对象返回，字段 `{code, message, retryable}`：
  - `RETRYABLE_TIMEOUT`（可重试）、`NETWORK_UNAVAILABLE`（可重试）、`AUTH_FAILED`（不可重试，提示用户）、`BUDGET_EXCEEDED`（不可重试）、`TOOL_FAILED`（依具体错误，默认可重试但工具应内部重试过一次）、`NO_PERMISSION`（不可重试，权限缺失）。模型据此区分"重试"与"放弃"（评审诉求）。

**`ModelManager`**
- `interface ChatModelClient` + `OpenAIClient`（兼容直连/预设）；负责 baseURL 归一化、鉴权头、超时、以及（后续阶段的）流式 SSE 分支。
- 本周期 MVP **不做流式**（见第 6 节阶段划分）。

**`ConversationService`**
- Room 持久化：`AiSession`/`AiMessage`（结构见第 3 节）。
- 多会话新建/归档/删除；Token 裁剪；消息分页加载（见第 3 节）。

## 3. 持久化数据模型（评审 P0）

**`AiSession`（会话）**
```
id:Long(PK, autogen)   title:String   createdAt:Long   updatedAt:Long
archived:Boolean(false)   model:String?   lastSummaryAt:Long?
```

**`AiMessage`（消息，含顺带存储工具调用/结果）**
```
id:Long(PK)   sessionId:Long(FK→AiSession, index)   seq:Int        // 会话内递增序号
kind: ENUM{ user, assistant, tool_call, tool_result, system,
            confirm_request, confirm_decision }       // 扩展 confirm 相关类型
role: String(User/Assistant/Tool/System)
content: String       // 文本；工具结果/元数据走 payload(JSON)
payload: String?      // 工具调用参数、工具结果、confirm 提案(JSON)
toolName: String?
quotaBilled: Long?    // 该请求累计的 token 用量
flags: String?        // 存档位：如 "awaiting_confirm", "expired"
createdAt:Long
```
- **消息序列化**：`content` 存纯文本；结构化数据（工具调用参数/结果/confirm 提案）统一存 `payload`(JSON)。查询用 seq 排序。
- **工具调用与结果**：`tool_call`（参数在 payload）与 `tool_result`（结果在 payload, 附 status) 成对存，重建对话时按序回放。
- **长会话性能（评审 #6）**：加载策略为**分页/截断**——按窗口取最近的 N 条（默认 50）+ 可选**历史摘要**（会话 `lastSummaryAt` 后，若历史超限则后台把更早消息压缩为一条 `kind=system` 的摘要消息）。查询走 `sessionId+seq` 联合索引。

**配置迁移（评审 #7，#13）**
- 配置：`PreferKey.aiProvider/aiModel/aiApiKey/aiBaseUrl` 等沿用。迁移在 `AiConfig` 初始化时**同步自动**（首次读取就 `if 旧 key 存在 → 搬入新表/新结构并删除旧 key`），不等到用户首次进设置。
- **API Key 安全（#13）**：接入 `androidx.security:security-crypto`，`aiApiKey` 迁移到 `EncryptedSharedPreferences`；若无可用主密钥则回退明文本地存储并打日志，UI 提示"建议开启系统锁屏以加密 Key"。

## 4. 小说领域 Agent Skills 与工具框架

**框架层（`tool/`）**
- `interface ToolDefinition`：`name/description/parameters(JSON Schema)/enabled/manualConfirm/category`
- `ToolRegistry`：按 `enabled` 筛选并生成模型可见的 `tools` schema；经 `ToolContext` 调度。
- `ToolContext`：运行时环境——当前会话 + 预设上下文 preset（如正在读的书/章节）；**预设为 session 作用域**，随会话提交，默认不落库。
- `bridge` 接口带**权限标记（评审 #14 前置化）**：`@ReadOnly` / `@ReadWrite`。`SourceRuleWriter` 标注 `@ReadWrite` 且**强制**经 `manualConfirm` 拦截器（在 `ToolContext` 层统一检查，依赖工具实现自觉而非调用方）。

**`manualConfirm` 异步状态机（评审 P0/#8）**

> Android 上不能用"同步阻塞弹窗"，采用**异步回调**：工具返回 `pending_confirm`，运行时挂起该次写操作并提起 UI 事件，用户确认后经 `ConversationService` 追加决策消息恢复执行。

时序：
1. 模型调用 `suggest_source_fix` → 工具只做**只读分析**，产出提案，返回 `{"status":"pending_confirm","confirmToken":"k1","proposal":{...的规则 diff}}`。
2. `AgentRuntime` 检测 `pending_confirm` → 不改写书源，把 `confirm_request` 消息落库（`payload`=提案, `flags="awaiting_confirm"`），向 UI 发 `AgentEvent.ConfirmRequired(proposal, confirmToken)`，**本轮工具循环暂停**。
3. UI 在 Hub 技能/会话区渲染「待确认卡片」（展示 diff 与 确认/拒绝）。
4a. 用户「确认」→ UI 调 `ConversationService.approve(confirmToken)` → 追加 `kind=confirm_decision, payload={"approved":true}` → 运行时收到即**真正调用 `SourceRuleWriter.apply(proposal)`**（此时才写库）→ 把结果以 `tool_result` 落库 → 恢复正常循环。
4b. 用户「拒绝」→ 追加 `payload={"approved":false}` → 运行时不写库，向模型补一条 `tool_result`（说明用户拒绝）→ 继续。
5. 交互边界：确认 token 一次性；会话若在 `awaiting_confirm` 状态被**删除/重启**，则该 token 作废（`expired`），禁止延迟生效。

状态流转：
```
idle → model_turn → tool_called ──(tool 不要求确认)──▶ model_turn → … → done
                        │
                        └─(pending_confirm)──▶ awaiting_confirm ─(approved)──▶ apply→model_turn
                                                      └────────(denied)──▶ model_turn
```

**小说技能四分类（评审已认可）**
| 阶段 | 技能 | 对应工具（示例） |
|------|------|------------------|
| 选书 | 跨书源搜书 / 按作者类型关键词找书 / 相似书同作者推荐 | `search_books`、`recommend_books` |
| 读书 | 章节正文读取 / 当前章节总结 / 情节梳理回顾 | `read_chapter`、`summarize_chapter`、`plot_recap` |
| 懂书 | 人物关系与性格 / 背景设定 / 专有名词用典 / 主题伏笔 | `analyze_characters`、`explain_text`、`analyze_theme` |
| 书源 | 连通测试 / 规则诊断 / 规则修复建议(需确认) | `test_book_source`、`analyze_book_source`、`suggest_source_fix` |

**领域桥 `bridge`（接口，实现复用 `WebBook`/`BookHelp.getContent`/`DB`）**
- `BookFetcher(@ReadOnly)`、`ChapterReader(@ReadOnly)`、`BookSourceAnalyzer(@ReadOnly)`、`SourceRuleWriter(@ReadWrite, 仅经 manualConfirm)`

**既有工具迁移清单**
- `search_books`（BookSearchTool）、`analyze_book_source/list_book_sources/get_source_stats/get_source_rules`（BookSourceTool）、`test_book_source`（SourceTestTool）、`summarize_chapter/analyze_characters/explain_text/get_reading_tips`（BookReadingTool、ReadingAssistant）→ 全部改写为 `ToolDefinition` 实现，逻辑走 `bridge`。
- 新工具按需：`recommend_books`/`read_chapter`/`plot_recap`/`analyze_theme`。

## 5. UI 与设置集成

**`AgentHub`（设置中的 AI 中心页）**
- 会话区：多轮自由对话 + 待确认卡片 + 会话列表（新建/归档/删除）
- 技能区：小说技能/工具列表 + 启用开关 + 说明
- 配置区：服务商/模型/API Key、超时、最大轮数、会话保留数、会话窗口大小（技能开关独立于配置）
- 输出分块：模型文字 + 工具调用卡片（状态/耗时/结果摘要）+ 确认卡片 + 错误提示

**上下文桥（评审 #10/#11 规范化）**
- 明确三个上下文弹窗：**`AiAssistantDialog`（阅读）**、**`AiSearchDialog`（搜索）**、**`AiSourceOptimizeDialog`（书源）**；其逻辑收敛为 Hub 的预设入口。
- 导航：新增 `AgentHubActivity`，`startActivity`(带 extras) 压栈；**返回栈自然回退**（阅读页 → Hub → 返回回阅读页），不额外管理。
- preset 生命周期：**session 作用域**——经 `intent.extras` 一次性注入 `ToolContext`，仅当次会话生效，默认不持久化；用户可主动「保存为该书的预设」时再落库（后续阶段）。

**设置体系**
- `pref_main.xml` 顶部新增「AI 智能助手」条目 → 跳转 `AgentHub`
- 新增 `pref_config_ai.xml`：模型/服务商/API Key、超时、最大轮数、会话窗口、技能开关

## 6. 交付范围：三阶段（评审 #12 采纳）

为控风险，本周期内部按三阶段顺序实现，每阶段以"可编译 + 冒烟通过"收口：

- **阶段 MVP（最小可跑）**：非流式 `AgentRuntime` + 内存会话（或最简 Room）+ `ToolRegistry`/`ToolContext` + `bridge` 骨架 + Hub 基础对话（含输入/输出、工具卡片、`manualConfirm` **基本异步流**）。
- **阶段 2**：Room 持久化 `AiSession/AiMessage`（含分页/摘要）+ 多会话管理 + 全部既有工具迁移 + 小说技能映射 + 配置文件（API Key 加密）。
- **阶段 3**：**流式 SSE**（独立子任务，含统一超时/中断）+ `manualConfirm` 完善确认卡片 UI + 上下文桥（阅读/搜索/书源 preset 接入）+ 旧实现删除（`AiAgent`/`ConversationManager`/`AiConfigDialog`/3 个 `*Dialog`）。

> 说明：`manualConfirm` 的**核心异步机制**在 MVP 即落地（安全底线）；阶段 3 仅完善其 UI/卡片体验。**流式 SSE** 被明确列为阶段 3 独立任务；若评估后仍高风险，则降级为后续阶段（go/no-go 决策点在阶段 3 开始时）。

**后续阶段（承接「全量重架构」，不在本周期）**
- 阶段 4：`bridge` 抽象下沉为通用领域层（书源引擎/阅读器）
- 阶段 5：书架/列表/下载分层解耦；阅读器组件化；更细 `manualConfirm` 权限模型
- 全周期强化 Agent：记忆/长上下文/RAG、多厂商插件

**成功标准（本周期）**
- 设置可完成模型配置 → Hub 可自由对话、开关技能、持久化/切换会话、工具与确认卡片可见
- 阅读/搜索/书源入口跳转 Hub 获得同等能力；书源写回经二次确认
- `assembleAppRelease` 通过（每阶段末均过）

## 7. 边界与不变量

- 不触碰书架/书源/阅读器核心业务逻辑（本周期只做 AI 平台层 + 接入点）。
- AI 不直接访问 DB；写操作（尤其书源）强制 `manualConfirm`。
- 用户已配置模型/API Key 兼容迁移；Key 迁移至 `EncryptedSharedPreferences`。
- `manualConfirm` token 一次性，会话删除/重启即失效。

## 附：评审采纳对照（简要）

| 评审项 | 处置 |
|--------|------|
| #1 appservice 命名 | → `bridge` |
| #2 skill/tool 边界 | skill 仅声明式元数据，无执行引擎 |
| #3 CancellationToken | 复用协程（Job.cancel + stopRequested 双保险） |
| #4/P0 流式风险 | 列为阶段 3 独立任务，带 go/no-go |
| #5 预算粒度 | 事后累计、优雅截断、state= BUDGET_EXCEEDED |
| #6/P0 表结构 | 第 3 节字段草案 + 索引 + 分页/摘要 |
| #7/#13 配置迁移 | 首次读取同步迁移；Key 加密 |
| #8/P0 manualConfirm | 异步状态机 + 时序 + 状态图，MVP 落地核心 |
| #9 错误码 | 6 类错误码 + retryable |
| #10/#11 上下文桥/对话框 | Activity 出栈自然返回；preset session 作用域；明确 3 弹窗名 |
| #12 交付过大 | 拆分 MVP/阶段2/阶段3 |
| #14 权限 | bridge 注 ReadOnly/ReadWrite；写拦截器前置化 |