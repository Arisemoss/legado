# 环境化 AI：悬浮球 · 后台任务 · 过程折叠

- 日期：2026-08-23
- 目标：AI 从「一个聊天页」进化为「贯穿阅读流程的常驻能力」（参考 RikkaHub 的后台生成与 ChainOfThought）
- 状态：**仅保存到本地，未推送构建**

## 一、阅读页 AI 悬浮球（`AIFloatBallView`）

- 挂载于 `activity_book_read.xml` 根 FrameLayout，默认右下角（bottom|end, marginBottom 120dp 避开菜单区）
- **拖拽**：8dp 触发阈值区分点击/拖动；拖动中 `requestDisallowInterceptTouchEvent` 防止与翻页手势冲突
- **贴边隐藏**：松手吸附最近左右边缘 + alpha 0.5 半透明；点击恢复不透明并打开 Hub
- **位置记忆**：按「边(L/R) + 纵向比例」持久化（`ai_float_ball_side/y_ratio`），下次进书自动还原
- **上下文预设**：点击携带 `preset_book`(ReadBook.book.name) 与 `preset_chapter`(curTextChapter.title) 打开 Hub
- 新增通用偏好助手 `getPrefFloat/putPrefFloat`

## 二、后台任务中心（`AgentTaskCenter`）

- 任务运行在**进程级作用域**（SupervisorJob+Main.immediate），Hub Activity 销毁不再终止任务
- 共享单例 `ToolContext`：工具卡片、确认请求、流式增量经同一通道回流；
  Hub 重新进入自动重新绑定进行中任务（轮询逻辑天然复用）
- 生命周期：`start(sid,prompt,history,systemPrompt,preset)` → RUNNING → 完成后
  落库(user+assistant) → 回调 FinishListener → （耗时>3s）发系统通知
- 通知渠道 `channel_ai_task` 惰性创建；点击通知回到助手页（init 续接最近会话即任务会话）
- VM 契约变化：
  - `busy/typing` StateFlow → `isBusy()/isTyping()`（与中心实时对齐）
  - `dispose()` 不再停止任务；仅解绑监听
  - 切换/删除会话前 `ensureIdle()` 仍强制 stop（防跨会话写入）
  - 用户消息落库移入中心（避免重复）；`appendAssistantResult` 仅做 UI 行与 turns 记忆

## 三、思考过程折叠（ChainOfThought 风格）

- 渲染层实现（VM 数据源不变）：`foldProcess()` 把每个用户回合内的工具卡/错误条
  归组到一个折叠头之下
- 折叠头 `ChatRow.Process(key="proc_<turnKey>", steps, expanded)`：
  `▸ 工作过程 · N 步` / `▾ 工作过程 · N 步`
- **确认卡永远外显**——等待用户决策的卡片不受折叠影响（安全底线）
- 折叠状态存于 Activity 会话级 `collapsedTurns`；点击即时重渲染
- 无过程行的回合零开销快速路径（`none{ToolCard/ErrorRow}` 直接返回原列表）

## 四、涉及文件

```
新增: ai/ui/AIFloatBallView.kt          悬浮球
     ai/runtime/AgentTaskCenter.kt      后台任务中心
     res/layout/ai_item_process.xml     过程折叠头
修改: layout/activity_book_read.xml     挂载悬浮球
     ai/tool/ToolContext.kt             preset 改 var（按任务注入）
     constant/PreferKey.kt              悬浮球位置记忆键
     utils/ContextExtensions.kt         getPrefFloat/putPrefFloat
     ai/ui/AgentHubViewModel.kt         对接任务中心(整体重写)
     ai/ui/AgentHubActivity.kt          isBusy/isTyping + 过程折叠渲染 + VT_PROCESS
```

## 五、待办
- [ ] 悬浮球随阅读翻页手势的边缘避让微调（当前靠 clamp 兜底）
- [ ] 进程被杀后的任务恢复（需持久化任务队列，评估 WorkManager/前台服务）
- [ ] DeepSeek reasoner 等 `<think>` 推理内容的独立折叠展示
