# Operit 工具调用兼容层移植说明

- 日期：2026-08-23
- 参考：[AAswordman/Operit](https://github.com/AAswordman/Operit)（工具调用核心：
  `core/tools/AIToolHandler.kt`、`api/chat/enhance/ToolExecutionManager.kt`、
  `api/chat/llmprovider/StructuredToolCallBridge.kt`、`util/stream/plugins/StreamXmlPlugin.kt`）
- 原则：方案迭代、正常增删；不破坏既有原生 function-calling 链路（Don't Break Userspace）
- 状态：**仅保存到本地，未推送构建**

## 一、Operit 工具调用架构研究结论

1. **统一中间协议 = XML 文本**
   - `<tool name="xxx"><param name="yyy">value</param></tool>`
   - 上层（执行器/UI/历史）只认这一种格式
2. **双通道适配（兼容性核心）**
   - 支持 FC 的模型：Provider 请求前把 XML→OpenAI `tools/tool_calls`，响应后把原生
     tool_calls→XML 回填（`enableToolCall` 开关，OpenAIProvider 头部注释完整描述双向转换）
   - 不支持 FC 的模型：工具定义渲染进系统提示（`ToolPrompt.toString()` 文本清单），
     模型在正文输出 XML 标签；流式阶段用 KMP 流式解析器实时提取
3. **参数全字符串化**：`AITool(name, parameters: List<ToolParameter(name,value:String)>)`，
   规避各模型 JSON 类型差异（数字传成字符串等）
4. **健壮性细节**：CDATA 与 XML 实体转义还原；执行前 `validateParameters`；
   请求→拦截→权限→执行→结果→错误→完成 全生命周期 Hook；历史按 provider 能力重编译

## 二、本项目移植内容

| 移植点 | 实现 | 文件 |
|--------|------|------|
| 文本协议解析 | `<tool>/<param>` 正则解析 + CDATA/实体还原 + 代码围栏剥离，输出与原生一致的 `ToolCallData` | NEW `ai/tool/TextToolCallParser.kt` |
| 协议模式开关 | `ai_tool_protocol`: auto(默认)/native/text；text 模式不发送原生 tools schema | `AiModelConfig.toolProtocol`、`PreferKey.aiToolProtocol`、配置页 ListPreference |
| 主循环集成 | 无原生 toolCalls 时自动尝试文本解析；命中则剥离标签回填 assistant(tool_calls)，复用既有三阶段流水线与写操作确认 | `AgentRuntime.execute/completeOnce` |
| 提示注入 | auto/text 模式向系统提示追加「工具调用协议」+ 全量工具清单（名称/描述/参数类型与必填性） | `SystemPromptBuilder(skills, tools)` |
| 装配解耦 | registry 从 syncConfig 提前到 init() 装配一次，供提示构建与运行时共享 | `AiPlatform.registry` |

### 兼容性保障
- **向后兼容**：auto 为默认值，原生 FC 行为不变；native 模式可完全回到旧行为
- **参数兼容**：文本协议参数全字符串化 → 工具侧读取已具备容错
  （`boolArg` 接受 true/"True"/"1"，数值参数接受字符串数字并限幅）
- **流水线复用**：文本协议产出的调用走同一 resolve→invoke→confirm 流水线，
  写操作二次确认、超时、重试、日志全部自动生效

## 三、待办
- [ ] 流式模式下文本协议标签可能闪现在打字机气泡（v2 可在 onDelta 检测 `<tool` 起缓冲不上屏）
- [ ] 参照 AIToolHook 引入工具生命周期 Hook（当前以 AiLog 埋点代替）
- [ ] MCP / JS 包代理等 Operit 高级特性评估
