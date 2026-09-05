# legado 模块职责与边界

> 版本：Draft 1
>
> 目标：把 legado 从“功能集合”整理成“边界清晰的模块系统”，为后续重构、测试、AI 工具化和插件化打基础。

---

## 1. 为什么要拆模块

当前项目已经具备较完整的阅读、书源、AI、设置、日志和 UI 能力。下一步最重要的事情不是继续堆功能，而是把这些能力拆成可维护的边界。

模块化的价值：

- 降低耦合
- 让职责更清楚
- 方便单独测试
- 方便 AI Agent 调用
- 方便未来插件化和独立演进

---

## 2. 模块化原则

### 2.1 一个模块只解决一类问题

例如：

- 阅读模块只管阅读体验
- 书源模块只管抓取与解析
- AI 模块只管 Agent 会话与工具执行
- 设置模块只管配置与偏好
- 日志模块只管可观测性

### 2.2 UI 不直接碰底层数据

页面不应直接读取数据库、直接执行规则、直接发网络请求。

正确路径是：

```text
UI -> ViewModel -> UseCase -> Repository -> Data Source
```

### 2.3 模块之间通过接口协作

禁止：

- 一个页面直接调用另一个页面内部逻辑
- AI 直接操作数据库实体
- 书源逻辑散落到多个页面

推荐：

- 通过 Repository / Service 暴露接口
- 通过事件和状态对象交互
- 通过统一的模型传递结果

### 2.4 写操作必须显式化

所有修改用户数据的行为必须显式触发，并保留确认入口：

- 删除书籍
- 修改设置
- 启用/禁用书源
- 写入同步数据
- AI 执行高风险操作

---

## 3. 推荐模块总览

建议将项目逐步组织成以下模块：

```text
:app
:core:common
:core:model
:core:ui
:core:data
:core:database
:core:network
:core:rule
:feature:bookshelf
:feature:reader
:feature:search
:feature:ai
:feature:download
:feature:settings
:feature:log
:feature:sync
```

这不是一次性强制拆分目标，而是推荐演进方向。

---

## 4. 核心模块说明

### 4.1 `:app`

职责：

- 应用入口
- 依赖装配
- 全局初始化
- 路由壳
- 主题切换入口

不应包含：

- 大量业务逻辑
- 页面级状态
- 数据访问细节

---

### 4.2 `:core:common`

职责：

- 通用工具
- 扩展函数
- 错误模型
- 结果类型
- 基础常量

建议放入：

- `Result` / `UiResult`
- `AppError`
- `Dispatchers`
- 通用格式化工具

---

### 4.3 `:core:model`

职责：

- 统一数据模型
- UI 状态模型
- 领域实体
- 请求/响应 DTO
- AI 消息模型

建议包含：

- 书籍、章节、书源、用户偏好
- AI 会话、消息、工具调用记录
- 日志记录、任务状态

原则：模型应尽可能稳定，避免被 UI 直接污染。

---

### 4.4 `:core:ui`

职责：

- 统一设计系统
- 基础组件
- 主题
- 颜色
- 字体
- 间距
- 圆角
- 动效

建议提供：

- Button
- Card
- TopBar
- EmptyState
- LoadingState
- ErrorState
- Dialog
- BottomSheet
- Chips

这个模块是未来 Compose 化的重要基础。

---

### 4.5 `:core:data`

职责：

- 仓库实现
- 数据装配
- 数据转换
- 缓存协调

它是 Repository 层的主要承载模块。

---

### 4.6 `:core:database`

职责：

- Room 数据库
- DAO
- Entity
- Migration

建议：

- 数据库写操作统一异步化
- DAO 只做存取，不做业务判断
- Migration 独立维护

---

### 4.7 `:core:network`

职责：

- HTTP 请求
- Provider 适配
- SSE 流式处理
- 重试与超时
- API 解析

可包含：

- 搜索请求
- AI Provider 请求
- 资源下载请求

---

### 4.8 `:core:rule`

职责：

- 书源规则解析
- 章节解析
- 脚本执行
- 规则调试
- 规则沙箱

这是项目最敏感、最复杂的模块之一，需要单独隔离。

建议：

- 输入必须校验
- 输出必须结构化
- 规则执行必须可超时
- 规则执行必须可追踪

---

## 5. 功能模块说明

### 5.1 `:feature:bookshelf`

职责：

- 书架展示
- 分类
- 排序
- 最近阅读
- 置顶
- 批量管理

与外部协作：

- 读取书籍列表
- 接收更新状态
- 触发打开书籍

---

### 5.2 `:feature:reader`

职责：

- 章节内容展示
- 翻页 / 滚动
- 阅读设置
- 进度保存
- 朗读
- 阅读界面交互

关键点：

- 保持低延迟
- 避免主线程解析
- 保持 UI 简洁

---

### 5.3 `:feature:search`

职责：

- 跨书源搜索
- 结果去重
- 结果排序
- 搜索筛选
- 搜索状态管理

建议统一：

- 搜索中
- 成功
- 空结果
- 失败
- 书源不可用

---

### 5.4 `:feature:ai`

职责：

- AI 会话管理
- Prompt 组装
- 工具注册
- 工具调用编排
- 流式输出
- 写操作确认
- 运行日志联动
- 记忆与偏好

建议拆为：

- runtime
- tool
- bridge
- skill
- memory
- log
- ui

AI 模块必须遵守：

- 不直接操作数据库
- 不绕过确认
- 不直接访问私有页面逻辑

---

### 5.5 `:feature:download`

职责：

- 下载任务
- 资源导入
- 章节拉取
- 封面缓存
- 队列调度

建议把大文件、长任务都纳入统一任务系统。

---

### 5.6 `:feature:settings`

职责：

- 阅读设置
- 主题设置
- AI 设置
- 服务商配置
- 备份恢复

原则：

- 设置项要有默认值
- 设置项要能恢复
- 设置项要能导出

---

### 5.7 `:feature:log`

职责：

- 操作日志
- AI 调用日志
- 工具调用日志
- 规则错误日志
- 导出与分享

建议日志分层：

- 用户可读日志
- 开发调试日志
- 机器可解析日志

---

### 5.8 `:feature:sync`

职责：

- 云同步
- 本地备份
- 数据恢复
- 多端一致性

当前如果还没完全实现，也应先预留接口和模型。

---

## 6. 依赖关系建议

推荐依赖方向：

```text
feature -> core
app -> feature + core
feature 之间尽量不直接互相依赖
```

### 6.1 允许的依赖

- `feature:reader` 依赖 `core:model`、`core:ui`、`core:data`
- `feature:ai` 依赖 `core:model`、`core:network`、`core:rule`、`core:log`
- `feature:search` 依赖 `core:network`、`core:model`

### 6.2 禁止的依赖

- 页面直接依赖数据库实现
- AI 直接依赖页面私有类
- 书架页面直接访问规则引擎内部对象
- 设置页面直接改写业务逻辑

---

## 7. 每个模块都应具备的标准接口

每个模块建议至少提供：

- `Api` 或 `Facade`
- `UiState`
- `Action`
- `Event`
- `Mapper`
- `Repository` 或 `UseCase`

这样后续测试、迁移和替换会更容易。

---

## 8. 模块化迁移顺序

### 第一批

1. `:feature:ai`
2. `:feature:reader`
3. `:feature:bookshelf`
4. `:feature:settings`

### 第二批

5. `:feature:search`
6. `:feature:log`
7. `:feature:download`

### 第三批

8. `:core:ui`
9. `:core:model`
10. `:core:data`
11. `:core:rule`
12. `:core:network`
13. `:core:database`

### 第四批

14. `:feature:sync`
15. 更细粒度的 `api/impl` 拆分

建议按“价值高、耦合强、问题多”的模块优先切分。

---

## 9. 与 Compose 化的关系

模块化和 Compose 化应该同步推进，但不要混成一件事。

建议：

- Compose 负责 UI 形态升级
- 模块化负责工程边界升级

也就是说：

- 可以先把一个模块的 UI 改成 Compose
- 也可以先把一个模块拆边界，但 UI 仍用 Views
- 不要求所有模块同时迁移

---

## 10. 与 AI 平台的关系

AI 平台是模块化之后最受益的部分。

原因：

- AI 工具可以通过清晰的模块边界暴露能力
- AI 不再直接碰页面细节
- AI 任务可以按模块执行、按模块审计
- AI 写操作更容易被限制和回放

---

## 11. 迁移风险

- 过早拆太细会让工程变复杂
- 模块边界不清会让依赖关系失控
- 一次性迁移全部 UI 会导致回归风险很高

所以迁移应采用：

- 先边界
- 再抽象
- 后拆分

---

## 12. 模块化结论

legado 最适合的模块化方式不是“把每个文件都拆出去”，而是：

1. 先把核心业务边界厘清
2. 再把高耦合模块独立出来
3. 再逐步引入 feature module
4. 再围绕 Compose、StateFlow、WorkManager、AI 工具进行现代化升级

---

## 13. 下一步建议

下一份建议补：

- `docs/ai/AI_PLATFORM.md`
- `docs/ui/DESIGN_SYSTEM.md`
- `docs/performance/PERFORMANCE.md`
- `docs/security/SECURITY.md`
