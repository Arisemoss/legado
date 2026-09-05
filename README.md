<div align="center">

# legado · AI 增强版

**知名开源阅读器「阅读3.0」的 AI Agent 深度改造版**

让 AI 真实操控阅读器：搜书、读书、懂书、修书源 —— 而不只是聊天

[![Build AI Coexist APK](https://github.com/Arisemoss/legado/actions/workflows/ai-build.yml/badge.svg)](https://github.com/Arisemoss/legado/actions/workflows/ai-build.yml)
[![Android CI](https://github.com/Arisemoss/legado/actions/workflows/android.yml/badge.svg)](https://github.com/Arisemoss/legado/actions/workflows/android.yml)
![Release](https://img.shields.io/github/v/release/Arisemoss/legado?label=正式版)
![Platform](https://img.shields.io/badge/platform-Android%205.0%2B-green)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

</div>

---

## ✨ 这是什么？

原版「阅读3.0」的全部能力（网络书源 / 本地书籍 / 书架管理 / 朗读 / RSS / Web 服务）**完整保留**，
在此之上新增了一层 **AI Agent 平台**：

- 🤖 **AI 智能助手中心页**：自由对话 + 快捷指令 + 多会话持久化
- 🛠️ **22 个领域工具**，AI 能真实执行：跨源搜书、读章节、总结/人物/主题分析、
  书源诊断、修复提案（**写操作强制二次确认**）、书架与设置操控
- 🌊 **真·流式输出**：SSE 打字机效果，tool_calls 分片组装，服务端不支持时自动回退
- 🔌 **工具调用三协议**（学习 [Operit](https://github.com/AAswordman/Operit)）：
  `auto`（原生函数调用 + 文本 XML 双通道，默认）/ `native` / `text`——
  连不支持函数调用的服务商和本地小模型也能用工具
- 📋 **应用内运行日志**：模型请求 / 流式 / 工具 / 错误全链路记录，一键导出排障
- 🎨 **RikkaHub 风格 UI**：尾角气泡、时间戳、胶囊输入栏、聊天背景自定义（相册图片 / 极光渐变）
- 📖 **现代阅读主题**：内置五套 MIT 开源配色系统（[Flexoki](https://stephango.com/flexoki) /
  [Everforest](https://github.com/sainnhe/everforest) / [Rosé Pine](https://rosepinetheme.com) /
  [Nord](https://www.nordtheme.com) / [Catppuccin](https://catppuccin.com)），
  日/夜双模式，全部通过 WCAG AA 对比度，阅读→界面 一键切换

## 🤖 AI 能力一览

### 内置 13 家服务商预设

DeepSeek · 通义千问 · 智谱 GLM · Kimi · 硅基流动 · MiniMax · 豆包 ·
OpenAI · OpenRouter · Groq · xAI · Ollama（本地）· LM Studio（本地）

选预设自动填充 Base URL 与推荐模型；API Key 经 Android Keystore **加密存储**；
支持自定义 OpenAI 兼容接口。

### 工具矩阵（22 个）

| 分类 | 工具 |
|------|------|
| 选书 | `search_books` `recommend_books` |
| 读书 | `read_chapter` `summarize_chapter` `plot_recap` |
| 懂书 | `analyze_characters` `explain_text` `analyze_theme` |
| 书源 | `analyze_book_source` `get_source_rules` `list_book_sources` `get_source_stats` `test_book_source` `suggest_source_fix`🔐 `set_source_enabled`🔐 |
| 书架 | `list_shelf` `open_book` `remove_book`🔐 `open_search` `open_bookshelf` |
| 设置 | `get_setting` `set_setting`🔐 |

> 🔐 = 写操作，走 `pending_confirm` 异步确认状态机：AI 只产出提案，
> 用户在聊天内点「同意」才真正落库；token 一次性，超时/拒绝自动作废。

### 兼容性设计

| 场景 | 行为 |
|------|------|
| 服务商支持函数调用 | 原生 `tools/tool_calls` 通道（DeepSeek/GLM/Kimi/OpenAI…） |
| 服务商不支持 / 本地小模型 | `text` 模式：工具清单注入系统提示，模型按 XML 协议输出，流式解析执行 |
| 模型参数类型不规范 | 全链路容错读取（布尔 `"True"/"1"`、数值字符串、限幅） |

## 📥 下载安装

| 渠道 | 说明 |
|------|------|
| [GitHub Releases](https://github.com/Arisemoss/legado/releases/latest) | **推荐**，正式签名版 |
| 仓库直链 | [`ai-apk-download/legado-ai-apk/`](ai-apk-download/legado-ai-apk/) |
| Actions 产物 | 每次 push 自动构建（含 debug 签名包） |

**包名说明（可共存安装）**：

| 包名 | 说明 |
|------|------|
| `io.legado.ai.release` | 本项目 AI 正式版 |
| `io.legado.ai.debug` | 开发调试版 |
| `io.legado.app.release` | 原版阅读（数据与本项目不互通） |

## ⚙️ 三步上手

1. 安装 APK → 打开「阅读」→ 进入 **AI 智能助手**
2. 右上角 ⚙ → 选服务商预设 → 粘贴 API Key → 点「测试连接」
3. 回到对话页，试试快捷指令：**总结当前章节 / 帮我找书 / 诊断书源**

> 💡 入门推荐 DeepSeek 或智谱 `glm-4-flash`（免费）。
> 🐛 遇到问题？助手顶栏 🐛 打开运行日志，点「分享」把日志发出去即可定位。

## 🛠️ 从源码构建

```bash
# 环境：JDK 8 + Android SDK 29（compileSdk 29 / minSdk 21）
git clone https://github.com/Arisemoss/legado.git
cd legado
./gradlew assembleAiRelease          # AI 共存正式版
./gradlew assembleAiDebug            # AI 调试版
```

Flavor 说明：`app`（原版）/ `google`（Play 版）/ `ai`（AI 共存版，本项目主线）。
推送 master 自动触发 CI 构建（`.github/workflows/`）。

## 📂 AI 平台架构

```
io.legado.app
└── ai
    ├── runtime    AgentRuntime 多轮循环 · OpenAIClient(SSE) · 会话持久化 · KeyStore
    ├── tool       ToolRegistry · 22 个工具 · TextToolCallParser(XML协议兼容层)
    ├── bridge     领域桥：AI 不直接碰 DB/WebBook，读写分离
    ├── skill      小说领域技能声明（选书/读书/懂书/书源）
    ├── model      消息/配置/服务商预设
    ├── log        AiLog 运行日志（内存环形 + 文件持久化）
    └── ui         AgentHub 对话页 · 日志页 · 极光背景
```

核心原则：AI 不直接访问数据库；写操作强制确认；工具执行三阶段流水线
（解析校验 → 并行执行 → 结果回填），带超时/重试/预算控制。

## 📚 开发文档

- [AI Agent 平台设计（Rev.2）](docs/superpowers/specs/2026-08-20-ai-agent-platform-design.md)
- [📌 项目地图与状态报告（2026-09，最新调研基线）](docs/superpowers/reports/2026-09-05-项目地图-状态报告.md)
- [RikkaHub UI 移植说明](docs/superpowers/specs/2026-08-23-rikkahub-ui-migration.md)
- [Operit 工具兼容层说明](docs/superpowers/specs/2026-08-23-operit-toolcall-compat.md)
- [小白上手指南](docs/小白上手指南.md)
- [Web / ContentProvider API](api.md)

## ⚠️ 免责声明

本项目基于 legado 二次开发。原版因版权问题已由作者清空代码并发布公告——
**请只使用正版/授权书源或本地书籍，勿导入来路不明的盗版书源**。
AI 的写操作均有确认机制，看不懂的确认弹窗请勿轻点「同意」。
[原版免责声明](https://gedoor.github.io/MyBookshelf/disclaimer.html)

## 🙏 致谢

- [gedoor/legado](https://github.com/gedoor/legado) — 阅读3.0 原项目
- [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub) — 对话 UI 与背景设计参考
- [AAswordman/Operit](https://github.com/AAswordman/Operit) — 工具调用兼容层参考
- [langchain4j](https://github.com/langchain4j/langchain4j) — 工具抽象基线参考

---

<div align="center">

如果这个项目对你有帮助，欢迎点个 ⭐

</div>
