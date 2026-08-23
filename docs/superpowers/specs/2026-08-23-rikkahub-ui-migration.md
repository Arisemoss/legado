# RikkaHub UI 设计移植说明

- 日期：2026-08-23
- 参考：[rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)（Jetpack Compose + Material 3）
- 目标：把 RikkaHub 的对话视觉语言与背景自定义能力移植到本项目的 View 体系（Kotlin 1.4 / appcompat），**本次仅保存到本地，未推送构建**。

## 一、RikkaHub 设计语言要点（源码研究结论）

| 要点 | RikkaHub 实现 | 本项目移植 |
|------|--------------|-----------|
| 聊天背景 | `AssistantBackground`：自定义图片 + `backgroundOpacity` + 垂直渐变遮罩；或 `MeshGradientBackground` 极光渐变 | `iv_chat_bg` + alpha + `ai_bg_scrim_chat` 遮罩；`AuroraBackgroundView` 移植极光 |
| 消息气泡 | M3 Surface 大圆角卡，用户右/AI 左 | 20dp 圆角 + 6dp 尾角气泡，用户右/AI 左 |
| 元数据行 | `ChatMessageNerdLine`（模型/token/耗时小字） | 气泡下 10sp 时间戳行（`tv_user_time`/`tv_ai_time`） |
| 思考指示 | 打字中动效 | 「思考中···」省略号逐帧推进 |
| 输入区 | 胶囊输入框 + 圆形发送按钮 | `ai_bg_input_card`(28dp 胶囊) + 44dp 圆形发送/停止钮 |

## 二、已落地内容

### 1. AI 助手 Hub（对话页）
- **背景三明治结构**：`FrameLayout{ aurora_view / iv_chat_bg / view_scrim / 内容层 }`
- **气泡 v2**：20dp 圆角带尾角；AI 气泡白底描边、用户气泡品牌蓝；新增时间戳元数据行
- **输入栏 v2**：胶囊卡片输入框（无内嵌边框）+ 圆形发送（箭头 -45°）/圆形停止按钮
- **思考点动画**：首 token 前「思考中·→··→···」600ms 循环
- **背景自定义**：
  - 设置 → AI 配置 → 「聊天背景」：选图（拷贝至 `filesDir/ai_chat_bg.jpg` 持久化）、不透明度(20~100%)、极光渐变开关、清除
  - Hub onResume 自动应用；图片按屏幕采样解码防 OOM；路径不变不重复解码

### 2. 极光渐变背景 `ai/ui/AuroraBackgroundView.kt`
- 移植自 RikkaHub `MeshGradientBackground`（Gemini 风）：底层线性渐变 + 4 个正弦漂移径向光斑
- 纯 View 实现，无 blur/Compose 依赖，全 API 可用；`onDetachedFromWindow` 自动停帧省电
- 亮暗两套配色，`setDarkMode()` 切换

### 3. 原阅读器 UI（低侵入优化）
- 底部菜单浮动圆角卡片化：外层留 10/12dp 边距形成浮层，`ReadMenu` 内将运行时纯色背景改为 18dp 圆角 `GradientDrawable`（颜色仍随阅读主题自适应日夜间）
- 其余阅读器主题体系保持不动（避免无构建验证下的回归风险）

## 三、涉及文件

```
新增: ai/ui/AuroraBackgroundView.kt
     drawable/{ai_bg_send_circle, ai_bg_stop_circle, ai_bg_scrim_chat,
               ai_bg_input_card, ai_bg_header_card, bg_reader_menu_card}
修改: layout/activity_agent_hub.xml        背景层+头部+输入栏重构
     layout/ai_item_msg_user.xml           气泡v2+时间戳
     layout/ai_item_msg_ai.xml             气泡v2+时间戳
     drawable/ai_bg_bubble_user|ai         20dp圆角+尾角
     layout/view_read_menu.xml             底部菜单浮动边距+elevation
     ui/book/read/ReadMenu.kt              运行时圆角菜单卡
     xml/pref_config_ai.xml                「聊天背景」设置分类
     values/arrays.xml                     不透明度数组
     values/colors_ai.xml                  设计令牌(气泡文字/停止钮/菜单卡)
     constant/PreferKey.kt                 aiChatBgPath/Opacity/Gradient
     ui/config/AiConfigFragment.kt         选图/清除/持久化逻辑
     ai/ui/AgentHubActivity.kt             背景应用/时间戳绑定/思考点动画
     ai/ui/AgentHubViewModel.kt            ChatRow.Msg 增加 time 字段
```

## 四、待办（下次构建验证后）
- [ ] 真机检查：自定义图片各透明度下的可读性；极光动画帧率与耗电
- [ ] 夜间模式下 Hub 配色联动（当前 Hub 为固定亮色系）
- [ ] 气泡长按操作菜单（复制/删除/重发——对齐 RikkaHub ChatMessageActions）
- [ ] Markdown 渲染（RikkaHub 用 MarkdownBlock；本项目可用 markwon 已有依赖）
- [ ] 书架/搜索页的 RikkaHub 卡片化风格统一
