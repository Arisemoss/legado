# AI Agent 平台化设计（设置集成 · 小说领域技能）

- 日期：2026-08-20
- 范围：把现有散落的 AI 能力（`ai/` 包 + 3 个上下文弹窗 + 独立配置弹窗）重构为 App 一等架构层——**AI Agent 平台**，纳入设置体系，并提供**小说领域**的 Agent 技能包。
- 目标：设置中可完整配置模型并进入「AI 智能助手」中心页自由对话、开关技能、管理持久化会话；阅读/搜索/书源处保留快捷入口并接入中心页；书源写回保持二次确认。

## 1. 总体分层架构

以「AI 一等公民」重构，收敛为若干内聚子包：

```
io.legado.app
├── ai
│   ├── runtime
│   │   ├── AgentRuntime         通用 function-calling 执行器（流式 + 工具调度 + 中断 + 预算）
│   │   ├── ModelManager         模型客户端抽象（OpenAI 兼容 + 预设 + 厂商路由）
│   │   ├── ConversationService 会话（Room 持久化、多会话、裁剪）
│   │   └── SystemPromptBuilder 按会话/技能上下文拼系统提示
│   ├── model                   纯数据模型（ChatMessage / 工具 schema / AiSession 实体）
│   ├── skill                   小说领域技能（业务语义/组合）
│   ├── tool                    可插拔工具框架（执行原子）
│   │   ├── ToolDefinition       名称/描述/参数 schema/开关/manualConfirm/category
│   │   ├── ToolRegistry
│   │   └── ToolContext          运行时向工具注入领域服务与当前上下文 preset
│   ├── appservice              领域桥：能力抽象成接口（实现用 WebBook/DB）
│   └── ui                       中心页 + 上下文桥 + 设置项
```

**核心原则**
1. AI 不直接碰 `WebBook`/`App.db`，统一经 `appservice` 接口注入，可测、可替换。
2. Agent 只认识 `ToolDefinition`，不感知业务；新增能力＝注册技能/工具。
3. 会话升级为 Room 持久化（新增 `AiSession`/`AiMessage` 表）。
4. 设置成为事实入口：`pref_main` 新增「AI 智能助手」；阅读/搜索/书源按钮跳转中心页并注入 preset。

## 2. Agent 运行时（`ai/runtime`）

**`AgentRuntime`**（替代 `AiAgent`）
- 入口 `execute(prompt, toolContext): AgentResult`，多轮 function-calling 循环：
  1. `ModelManager.complete(messages, tools)` 发请求
  2. 返回 `tool_calls` → 经 `ToolContext` 调度执行（走 `appservice`）→ 工具结果追加为 `tool` 消息 → 回到 1
  3. 预算硬上限：最大轮数（可配，默认 5）、最大请求数、Token 预算 → 超限强制返回
  4. 中断：`CancellationToken`，UI 可「停止」，已执行副作用保留、终止生成
- **流式**：`stream` 开关，逐段回调 UI（当前仅非流式，本设计补齐）
- 错误分类：`NETWORK/AUTH/TIMEOUT/LIMIT/TOOL`；工具异常以结构化 `{"error":...}` 返回，不向模型抛原始异常

**`ModelManager`**
- 抽象 `interface ChatModelClient` + `OpenAIClient`（兼容直连/预设）
- 负责 baseURL 归一化、鉴权头、超时、流式（SSE/OkHttp）

**`ConversationService`**
- 现内存版 `ConversationManager` → Room 持久化：`AiSession`（id/标题）+ `AiMessage`（会话内消息）
- 多会话新建/归档/删除；Token 预算裁剪

## 3. 小说领域 Agent Skills（技能）与工具框架

**框架层**
- `interface ToolDefinition`：`name/description/parameters(JSON Schema)/enabled/manualConfirm/category`
- `ToolRegistry`：按 `enabled`（设置开关）筛选并生成模型可见的 `tools` schema；经 `ToolContext` 调度
- `ToolContext`：运行时环境——注入当前会话 + 预设上下文（preset，如正在读的书/章节）；上下文入口即为 preset 来源
- `manualConfirm`：高风险工具（书源写回）要求 UI 二次确认后才真正执行

**技能=业务语义，工具=执行原子**。表达上与工具一对多组合，框架层仍是 `ToolDefinition`。按「读小说」链路分类：

| 阶段 | 技能 | 对应工具（示例） |
|------|------|------------------|
| 选书 | 跨书源搜书 / 按作者类型关键词找书 / 相似书与同作者推荐 | `search_books`、`recommend_books` |
| 读书 | 章节正文读取 / 当前章节总结 / 情节梳理回顾 | `read_chapter`、`summarize_chapter`、`plot_recap` |
| 懂书 | 人物关系与性格分析 / 背景设定解析 / 专有名词与用典解释 / 主题伏笔分析 | `analyze_characters`、`explain_text`、`analyze_theme` |
| 书源 | 书源连通测试 / 规则诊断 / 规则修复建议（需确认） | `test_book_source`、`analyze_book_source`、`suggest_source_fix` |

既有工具全部映射到上述技能（见下方迁移清单）。

**领域桥 `ai/appservice`**（接口，实现复用 `WebBook`/`BookHelp.getContent`/`DB`）
- `BookFetcher`：搜索/推荐
- `ChapterReader`：缓存→联网正文
- `BookSourceAnalyzer`：规则读取/统计/连通测试
- `SourceRuleWriter`：**仅经 `manualConfirm` 确认后**写回

**既有工具迁移清单**
- `search_books`（BookSearchTool）、`analyze_book_source/list_book_sources/get_source_stats/get_source_rules`（BookSourceTool）、`test_book_source`（SourceTestTool）、`summarize_chapter/analyze_characters/explain_text/get_reading_tips`（BookReadingTool、ReadingAssistant）→ 全部改写为 `ToolDefinition` 实现，逻辑走 `appservice`。

## 4. UI 与设置集成

**`AgentHub`（设置中的 AI 中心页）**
- 会话区：多轮自由对话（流式展示）+ 会话列表（新建/归档/删除，走 `ConversationService`）
- 技能区：小说技能/工具列表 + 启用开关 + 说明
- 配置区：服务商/模型/API Key、流式、超时、最大轮数、会话保留数（原 `AiConfigDialog` 能力并入，缩为「编辑配置」入口）
- 输出分块：模型文字 + 工具调用卡片（状态/耗时/结果摘要）+ 错误提示

**上下文桥（保留快捷入口，接入 Hub）**
- 阅读菜单 → Hub + preset(`bookName/chapterTitle/content`)
- 搜索页「AI 搜索」→ Hub + 搜索 preset
- 书源管理「AI 优化」→ Hub + 待分析 `sourceUrl`（写回走 `manualConfirm`）
- 3 个 `*Dialog` 与 `AiConfigDialog` 中的重复逻辑收敛为 Hub 的预设入口/复用组件

**设置体系**
- `pref_main.xml` 顶部新增「AI 智能助手」条目 → 跳转 `AgentHub`
- 新增 `pref_config_ai.xml`：模型/服务商、流式、超时、最大轮数、会话保留数、技能开关

## 5. 本周期交付范围与阶段演进

**本周期交付（可编译可用单元）**
1. 分层骨架：`ai/runtime|model|skill|tool|appservice|ui`
2. Agent 引擎：`AgentRuntime`（流式+中断+预算）+ `ConversationService`（Room `AiSession`/`AiMessage`）+ 模型抽象
3. 工具框架：`ToolDefinition`+`ToolRegistry`+`ToolContext`（含 `manualConfirm`）+ 既有工具迁移 + 小说技能映射
4. UI：`AgentHub` 中心页（会话/技能/配置）+ 上下文桥 + `pref_main` 入口 + `pref_config_ai.xml`
5. 删/并被覆盖的旧实现：`AiAgent`、`ConversationManager`、`AiConfigDialog`、3 个 `*Dialog` 的重复逻辑
6. 用本仓库 CI（`assembleAppRelease`）验证编译

**后续阶段（承接「全量重架构」，不在本周期）**
- 阶段 2：`appservice` 抽象下沉为通用领域层（书源引擎/阅读器）
- 阶段 3：书架/列表/下载分层解耦
- 阶段 4：阅读器组件化；更安全的 `manualConfirm` 权限模型
- 全周期强化 Agent：记忆/长上下文/RAG、多厂商插件

**成功标准（本周期）**
- 设置中可完成模型配置 → 中心页可自由对话、开关技能、持久化/切换会话
- 阅读/搜索/书源入口跳转 Hub 获得同等 AI 能力；书源写回需二次确认
- `assembleAppRelease` 通过

## 边界与不变量
- 不触碰书架/书源/阅读器核心业务逻辑（本周期只做 AI 平台层 + 接入点）。
- AI 不直接访问 DB；所有写操作（尤其书源）经 `manualConfirm` 确认。
- 用户已配置的模型/API Key 迁移保留（原 `PreferKey.aiXXX` 兼容）。