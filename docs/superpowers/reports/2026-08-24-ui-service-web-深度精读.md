# legado（「阅读3.0」AI fork）UI 层 / 后台 Service / Web·API 层 深度精读报告

> 分析对象：`/root/github/legado`（`app/src/main/java/io/legado/app/`）
> 该 fork 在上游 gedoor/legado 基础上新增了 `ai/` 平台包（AgentHub、AIFloatBallView、AiConfig 等），本文在描述原架构的同时标注 AI fork 的挂载点。

---

## 0. 全局总览

- UI 层 `ui/` 共 **33,950 行 Kotlin**，分 50+ 子包；`service/` 2,540 行；`web/` 436 行；`api/` 仅 `ReaderProvider.kt` 271 行；`base/` 1,187 行；`receiver/` 200 行；`lib/` 2,909 行。
- 架构风格：**单 Activity 多 Fragment 不成立**——是"每屏一个 Activity"的传统 MVC/MVP 杂糅风格，配合自定义 `Coroutine` 封装、LiveEventBus 事件总线、单例状态对象（`ReadBook`/`AudioPlay`/`Download`）。
- 无 Hilt/Dagger、无 Room 以外的 DI；数据库为 Room（`App.db`，Application 静态字段）；网络为 OkHttp+Jsoup（书源解析）；JSON 为 Gson。
- AI fork 新增：`ai/` 包 38 个文件（runtime/tool/bridge/ui 等）、`ui/config/AiConfigFragment.kt`、`ui/main/MainActivity.kt` 的 `agent_select_tab` 导航、阅读页 `AIFloatBallView` 与 `ReadMenu.fabAiAssistant`。

---

## 1. 应用入口链路

### 1.1 Manifest 关键声明（`app/src/main/AndroidManifest.xml`）

| 组件 | 说明 |
|---|---|
| `.App` | Application 类 |
| `.ui.welcome.WelcomeActivity` | **LAUNCHER 入口**（MAIN/LAUNCHER intent-filter） |
| `.ui.welcome.Launcher1`~`Launcher6` | 6 个 LAUNCHER 别名空壳 Activity，用于桌面图标切换 |
| `.ui.main.MainActivity` | 主页（singleTask） |
| `.ui.book.read.ReadBookActivity` | 阅读页 |
| `.ai.ui.AgentHubActivity` / `.ai.ui.AiLogActivity` | AI 助手 Hub / AI 日志（fork 新增） |
| `.receiver.SharedReceiverActivity` | PROCESS_TEXT + SEND 分享接收 |
| `.ui.association.ImportBookSourceActivity` 等 | scheme `yuedu://booksource|rsssource|replace`、file/content 打开文件 |
| 6 个 Service：`CheckSourceService`、`DownloadService`、`WebService`、`TTSReadAloudService`、`HttpReadAloudService`、`AudioPlayService` | 见 §4 |
| `.receiver.MediaButtonReceiver` | MEDIA_BUTTON 静态注册 |
| `.api.ReaderProvider` | authority=`${applicationId}.readerProvider`，**exported=true** |
| androidx FileProvider | `${applicationId}.fileProvider` |

### 1.2 App.kt 初始化顺序（`io/legado/app/App.kt#onCreate()`）

```
INSTANCE = this
→ androidId = Settings.Secure.ANDROID_ID            // 设备标识
→ CrashHandler().init(this)                          // 全局崩溃捕获(help/CrashHandler.kt)
→ LanguageUtils.setConfigurationOld(this)            // 应用内语言
→ db = AppDatabase.createDatabase(INSTANCE)          // Room 单例 → companion App.db
→ versionCode/versionName                            // PackageManager
→ createChannelId()                                  // API26+: 3 个通知渠道 channelIdDownload/channelIdReadAloud/channelIdWeb(均 IMPORTANCE_LOW)
→ applyDayNight()                                    // ReadBookConfig.upBg()→applyTheme()(ThemeStore 主题色)→initNightMode(AppCompatDelegate)→postEvent(RECREATE)
→ LiveEventBus.config().supportBroadcast(this).lifecycleObserverAlwaysActive(true).autoClear(false)
→ registerActivityLifecycleCallbacks(ActivityHelp)   // Activity 栈管理(ActivityHelp.isExist 等)
→ AiLog.attach(this)                                 // 【fork】AI 内存环形日志 + 文件持久化
→ AiPlatform.init()                                  // 【fork】装配 AI runtime(client/bridge/registry)
```

主题体系：`applyTheme()` 按 E-Ink / 夜间 / 日间三态写 `ThemeStore.editTheme()`（primaryColor/accentColor/backgroundColor/bottomBackground），全部界面经 `ATH`（lib/theme）取色染色。

### 1.3 启动导航链

`WelcomeActivity`（`ui/welcome/WelcomeActivity.kt`）
- `onActivityCreated()`：若带 `FLAG_ACTIVITY_BROUGHT_TO_FRONT` 直接 finish（防重复实例化）；否则 `init()`：
  - `Coroutine.async{}` 清理过期搜索记录 `App.db.searchBookDao().clearExpired(1天前)`；
  - 按 `AppConfig.chineseConverterType` 用 HanLP 初始化简繁转换引擎（预热）；
  - `SyncBookProgress.downloadBookProgress()` 同步阅读进度；
  - `root_view.postDelayed(::startMainActivity, 500)` → `startActivity<MainActivity>()`；若偏好 `pk_default_read` 为真再直接 `startActivity<ReadBookActivity>()`；finish()。
- `class Launcher1~6 : WelcomeActivity()` 纯继承空壳。

`MainActivity`（`ui/main/MainActivity.kt`，213 行）
- `ViewPager + BottomNavigationView` 四 Tab：`BookshelfFragment`(书架) / `ExploreFragment`(发现) / `RssFragment`(订阅) / `MyFragment`(我的)；内部类 `TabFragmentPageAdapter` 的 `getItemPosition` 固定返回 `POSITION_NONE`。
- `onPostCreate()`：`upVersion()` 弹更新日志 TextDialog；`autoRefreshBook` 时延迟 1s `viewModel.upAllBookToc()`；延迟 3s `viewModel.postLoad()`；**【fork】`handleRequestTab(intent)` 读取 extra `agent_select_tab` 让 AI 把主页面板切到指定 Tab**。
- 返回键逻辑：非书架 Tab 先回 Tab0；2s 内二次返回才退出；朗读未暂停时 `moveTaskToBack` 而非 finish。
- `onPause` 自动备份 `Backup.autoBack(this)`；`onDestroy` 清理已删书籍缓存 `BookHelp.clearRemovedCache()`。

书架 → 阅读：
- `BookshelfFragment`（TabLayout 分组 + `BooksFragment` 列表）：菜单项 `menu_search→SearchActivity`、`menu_add_local→ImportBookActivity`、`menu_arrange_bookshelf→ArrangeBookActivity`、`menu_download→DownloadActivity`。
- `BooksFragment.open(book)`：`BookType.audio → AudioPlayActivity("bookUrl")`，否则 `ReadBookActivity(Pair("bookUrl",...), Pair("key", IntentDataHelp.putData(book)))`；`openBookInfo(book)` → `BookInfoActivity(name, author)`。
- `BookInfoActivity.startReadActivity(book)`：audio → `AudioPlayActivity`，其余 → `ReadBookActivity`（大对象 Book 经 `IntentDataHelp` 内存键值中转）。
- 完整链：**WelcomeActivity → MainActivity(BookshelfFragment) → [BookInfoActivity] → ReadBookActivity ↔ ChapterListActivity / BookSourceEditActivity / ChangeSourceDialog / ReplaceRuleActivity …**

---

## 2. 阅读页架构（重点）

### 2.1 角色划分

| 角色 | 文件 | 职责 |
|---|---|---|
| 控制器 | `ui/book/read/ReadBookActivity.kt`（866 行） | 实现 12 个 CallBack 接口（View.OnTouchListener、PageView.CallBack、TextActionMenu.CallBack、ContentTextView.CallBack、ReadMenu.CallBack、ReadAloudDialog.CallBack、ChangeSourceDialog.CallBack、**ReadBook.CallBack**、AutoReadDialog.CallBack、TocRegexDialog.CallBack、ReplaceEditDialog.CallBack、ColorPickerDialogListener） |
| 数据引擎 | `service/help/ReadBook.kt`（object 单例，409 行） | 三章缓存 + 加载/预下载/保存进度，通过 `callBack` 回调 Activity |
| VM | `ui/book/read/ReadBookViewModel.kt` | `initData(intent)` 解析入参并初始化书目、目录、换源 |
| 视图容器 | `page/PageView.kt` | 三页 ContentView 容器 + 翻页 delegate 调度 |
| 页面内容 | `page/ContentView.kt` + `page/ContentTextView.kt`（617 行） | ContentTextView 自绘文字/图片/选区 |
| 排版器 | `page/provider/ChapterProvider.kt`（462 行，object 单例） | 文本→TextChapter(TextPage/TextLine/TextChar) 排版 |
| 翻页动画 | `page/delegate/*.kt` | 5 种 PageDelegate |
| 菜单浮层 | `ReadMenu.kt`（321 行） | 顶栏/底栏/亮度浮层 |
| 文本操作 | `TextActionMenu.kt` | 选择文字后的 PopupWindow 菜单 |
| 辅助 | `Help.kt`（object） | 沉浸式、方向、屏幕常亮、刘海、下载/书签/编码对话框 |

布局 `res/layout/activity_book_read.xml` 结构：

```xml
FrameLayout
 ├─ io.legado.app.ui.book.read.page.PageView   (@id/page_view)
 ├─ View text_menu_position（文本选择菜单位锚点）
 ├─ ImageView cursor_left / cursor_right（选择起止光标）
 ├─ io.legado.app.ui.book.read.ReadMenu        (@id/read_menu, 默认 gone)
 └─ io.legado.app.ai.ui.AIFloatBallView        (@id/ai_float_ball, 48dp,
                                                layout_gravity="bottom|end",
                                                marginBottom=120dp marginEnd=16dp) ←【fork】
```

### 2.2 数据流与章节预加载（`service/help/ReadBook.kt`）

- 状态字段：`book / durChapterIndex / durPageIndex / chapterSize / prevTextChapter / curTextChapter / nextTextChapter / bookSource / webBook / msg / callBack / loadingChapters(去重表)`；`titleDate = MutableLiveData<String>()` 供标题栏观察。
- **三章窗口加载**：`loadContent(resetPageOffset)` 一次性对 `durChapterIndex±1` 调 `loadContent(index,...)`；每个 index 经 `addLoading()/removeLoading()`（synchronized）去重后 `Coroutine.async` 执行：查 `bookChapterDao.getChapter` → `BookHelp.getContent()` 读缓存；无缓存走 `download(chapter)` → `WebBook.getContent()` 成功后 `BookHelp.saveContent()` 再回调。
- `contentLoadFinish(...)`：仅处理落在 `[dur-1, dur+1]` 的章节；先做简繁转换（HanLP）与净化 `BookHelp.disposeContent(title,name,sourceUrl,content,useReplaceRule)`（替换规则在此生效），再按 index 写入 cur/prev/next `ChapterProvider.getTextChapter(...)` 并回调 `callBack.upContent/upView/pageChanged/contentLoadFinish`；随后 `ImageProvider.clearOut(durChapterIndex)` 清理离页图片缓存。
- **翻章时的指针平移**：`moveToNextChapter()` 将 `prev←cur←next` 平移、置 `next=null`，立即排版 `dur+1`，并在 `GlobalScope.launch(IO){ for(i in 2..10){ delay(100); download(dur+i) } }` 做**只下载不排版的 10 章预取**；`moveToPrevChapter()` 对称地预取 `-5..-2`。
- 进度持久化：`saveRead()` 更新 `book.durChapterIndex/durChapterPos/durChapterTitle` 写库；朗读中翻页自动续读 `curPageChanged()` 里 `if(BaseReadAloudService.isRun) readAloud(!pause)`。
- VM 侧 `ReadBookViewModel.initData(intent)`：优先 `IntentDataHelp.getData<Book>("key")` → `bookUrl` 查库 → `lastReadBook` 兜底；目录为空时 `loadBookInfo→loadChapterList`（本地走 `LocalBook.getChapterList`，在线走 `WebBook.getChapterList`），完成后 `ReadBook.loadContent(resetPageOffset=true)`；书源丢失时 `autoChangeSource(name,author)` 自动换源。

### 2.3 渲染管线：PageView / ContentView / ContentTextView / ChapterProvider

**PageView**（`page/PageView.kt`，FrameLayout，实现 `DataSource`）
- init 依次 `addView(nextPage); addView(curPage); addView(prevPage)`（z 序：prev 最上）；`upPageAnim()` 按 `ReadBookConfig.pageAnim` 实例化 delegate：`0 CoverPageDelegate / 1 SlidePageDelegate / 2 SimulationPageDelegate(仿真) / 3 ScrollPageDelegate(上下滚动) / 其他 NoAnimPageDelegate`。
- `dispatchDraw()`：先 super 画三个 ContentView，再 `pageDelegate?.onDraw(canvas)` 叠加翻页阴影/卷角；自动翻页时把 nextPage 截图按进度条画出进度指示。
- `computeScroll()` → `delegate.scroll()`（Scroller 驱动）；`onInterceptTouchEvent()` 恒 true，所有手势交给 delegate；触摸同时触发 `callBack.screenOffTimerStart()` 重置息屏计时。
- 尺寸变化 `onSizeChanged` 中 `ReadBook.loadContent(resetPageOffset=false)` 重排（旋转屏幕场景）。
- `DataSource` 接口把数据请求转发到 `ReadBook.textChapter(0/1/-1)`、`hasNextChapter()` 等；`CallBack` 要求宿主实现 `clickCenter()/isAutoPage/autoPageProgress/screenOffTimerStart/showTextActionMenu/isInitFinish`。

**ContentView**（`page/ContentView.kt`）：FrameLayout，内含 `content_text_view` + header/footer 提示视图（`headerHeight`、`upTipStyle()` 时间电量样式、`upStyle()` 字体重载）；核心方法 `setContent(textPage, resetPageOffset)`、`onScroll(offset)`（滚动模式）、`selectText/selectStartMove/selectEndMove/cancelSelect`（选区代理给 ContentTextView）、`selectedText`。

**ContentTextView**（`page/ContentTextView.kt`，自绘 View）
- `onDraw()` → `canvas.clipRect(visibleRect)` → `drawPage()` → `drawChars()`（遍历 TextLine/TextChar 用 ChapterProvider 的 Paint 逐字绘制，标题模式/缩进/两端对齐已在排版期算好坐标）→ 图片段 `drawImage()`（ImageProvider 位图缓存）。
- 选区：`selectText(e){relativePage,lineIndex,charIndex}` 定位起始三元组；拖动光标时 `selectStartMove(x,y)/selectEndMove(x,y)` → `upSelectChars()` 高亮 → 回调 `callBack.upSelectedStart/End` 移动 Activity 层的 cursor 图标；跨页选择通过 `relativeOffset/relativePage` 支持前后翻页。
- `onSizeChanged` 时同步 `ChapterProvider.viewWidth/viewHeight` 并 `textPage.format()` 重排——**排版尺寸是全局的**（见技术债 §9）。

**ChapterProvider**（object，排版单例）
- `getTextChapter(book, chapter, contents, chapterSize, imageStyle)`：用 `AppPattern.imgPattern` 拆分 `<img>` 段 → `setTypeImage()`（图片行居中、记录 ImageFlag）或 `setTypeText()`：用 `StaticLayout` 测量分行，`addCharsToLineFirst/Middle/Last` 逐字生成 TextChar（首行缩进 `bodyIndent`、中间行 gap 平分 `d=(visibleWidth-desiredWidth)/gapCount` 实现两端对齐，`exceed()` 微调越界）。
- `upStyle()`：读取 `ReadBookConfig.textFont` 自定义字体（assets 或 contentResolver 打开 fd）、生成 bold/titleFont/textPaint 等全局 Paint。
- entities：`TextChapter(pages, getReadLength(pageIndex), page(i), lastPage)` / `TextPage(text,title,lines,format())` / `TextLine(isTitle,isImage,textChars)` / `TextChar`。

**分页游标 TextPageFactory**（`page/TextPageFactory.kt` extends 泛型 `PageFactory<T>`）
- `currentPage/nextPage/prevPage/nextPagePlus` 从 DataSource 取页；`ReadBook.msg != null` 时优先返回提示文本页（如"目录更新中"）。
- `moveToNext/moveToPrev(upContent)`：章内 `ReadBook.setPageIndex(±1)`；跨页尾/页首转 `ReadBook.moveToNextChapter/moveToPrevChapter`；`hasNext()/hasPrev()` 决定 delegate 是否弹 Snackbar（no_next_page/no_prev_page）。

### 2.4 翻页机制（PageDelegate 家族）

基类 `page/delegate/PageDelegate.kt`（363 行，GestureDetector.SimpleOnGestureListener）：
- 中央区 `centerRectF`（宽高各 33%~66%）内单击 → `pageView.callBack.clickCenter()`；`clickTurnPage` 开启时左右半屏点击翻页（`clickAllNext` 可全屏下一页）。
- `onLongPress` → `curPage.selectText(e)` 进入选择态（`isTextSelected=true` 记录 firstRelativePage/lineIndex/charIndex），后续 MOVE 走 `selectText(event)` 双向扩展选区。
- 动画统一由 `Scroller(DecelerateInterpolator)` + `startScroll(dx,dy,duration=300*距离/边长)` 驱动；`onAnimStart(speed)/onDraw(canvas)/onScroll()/onAnimStop()` 由子类实现；按键 `keyTurnPage(Direction.PREV/NEXT)` 供音量键/物理键调用。
- 子类：`CoverPageDelegate`（覆盖）、`SlidePageDelegate`（平移）、`NoAnimPageDelegate`、`SimulationPageDelegate`（559 行仿真卷页：`calcPoints()` 计算触点/切点，`getCross()` 求交线，`drawCurrentBackArea/drawCurrentPageShadow/drawNextPageAreaAndShadow/drawCurrentPageArea` 用贝塞尔曲线 + 渐变阴影绘制背面与投影）、`ScrollPageDelegate`（上下滚动：`onScroll` 直接平移 curPage 内容偏移，惯性 fling + VelocityTracker）。

Activity 侧交互入口：
- `onKeyDown/onKeyUp`：可配置翻页键（`PreferKey.prevKey/nextKey`）、音量键翻页 `volumeKeyPage()`（受 `volumeKeyPage/volumeKeyPageOnPlay` 开关控制）、SPACE、BACK 长按退出、朗读播放时 BACK=暂停。
- 光标拖动：Activity 自己 `onTouch` 处理 cursor_left/cursor_right 的 MOVE → `page_view.curPage.selectStartMove/selectEndMove`，UP 后 `showTextActionMenu()`。
- `TextActionMenu`（PopupWindow）：复制/全选/`menu_replace`（选中文字一键创建替换规则 `ReplaceEditDialog.show(pattern=selectedText, scope="书名;书源URL")`）；定位算法在 `showTextActionMenu()`（避开 statusBar 与两个光标）。

### 2.5 菜单浮层与设置对话框

`ReadMenu`（FrameLayout，`view_read_menu.xml`）：
- 显示/隐藏 `runMenuIn()/runMenuOut(callback)`：顶部 `menuTopIn/Out` + 底部 `menuBottomIn/Out` 补间动画；KEYCODE_MENU 由 Activity `dispatchKeyEvent` 配合 `cnaShowMenu` 防抖。
- 顶部：TitleBar（返回/书名/朗读按钮等）+ `seek_read_page` SeekBar（章内拖动 → `ReadBook.skipToPage`）+ `tv_pre/tv_next` 上/下一章（`ReadBook.moveToPrevChapter(toLast=false)/moveToNextChapter`）。
- 底部 RikkaHub 风圆角浮动卡 `ll_bottom_bg`：`ll_catalog`（→`openChapterList()`→`ChapterListActivity(bookUrl)` startActivityForResult 回传 index）、`ll_read_aloud`（→`ReadAloudDialog`）、`ll_font`（→`ReadStyleDialog`）、`ll_setting`（→`MoreConfigDialog`）；FAB 区：`fabAutoPage/fabReplaceRule/fabNightTheme/fabAiAssistant`。
- **【fork】`fabAiAssistant.onClick`：`runMenuOut{}` 后跳 `AgentHubActivity`，extras：`preset_book=ReadBook.book?.name`、`preset_chapter=durChapterTitle`、`preset_content=curTextChapter?.getContent()`、`preset_source_url=book?.origin`** —— 把当前书/章/正文上下文预填给 AI。
- 亮度：`ll_brightness` 半透明卡 + `iv_brightness_auto` + 竖向 `seek_brightness`（`ui/widget/seekbar/VerticalSeekBar`）：`setScreenBrightness(value)` 写 `window.attributes.screenBrightness = value/255`（auto 时 `BRIGHTNESS_OVERRIDE_NONE`），值存 pref `"brightness"`；`showBrightnessView` 偏好变化经事件刷新（`read_menu.upBrightnessState()`）。
- 夜间 FAB：`AppConfig.isNightTheme` 取反 → `App.INSTANCE.applyDayNight()` → `postEvent(RECREATE)`。

config 目录 15 个对话框：`ReadStyleDialog`（字体/字号/行距/背景）、`MoreConfigDialog`、`PaddingConfigDialog`、`BgTextConfigDialog`（自带 ColorPicker TEXT_COLOR/BG_COLOR 回调）、`TipConfigDialog`（页眉页脚）、`ReadAloudDialog/AutoReadDialog`（朗读/自动翻页控制）、`SpeakEngineDialog(+VM)`（TTS 引擎管理）、`PageKeyDialog`（翻页键）、`TocRegexDialog(+VM)`（本地 txt 目录正则）、`ChineseConverter/TextFontWeightConverter` 工具。

### 2.6 AI 悬浮球挂载点（grep 结论）

- 引用点共两处 Java 代码 + 一处布局：
  1. `res/layout/activity_book_read.xml:41` —— `<io.legado.app.ai.ui.AIFloatBallView android:id="@+id/ai_float_ball">`，作为 `page_view` 的**兄弟节点**覆盖其上（右下角 bottom|end，marginBottom 120dp）。
  2. `ui/book/read/ReadMenu.kt` import `io.legado.app.ai.ui.AgentHubActivity`（fabAiAssistant 点击跳转，见上）。
  3. `ui/main/MainActivity.kt` `handleRequestTab()` 消费 `agent_select_tab`（AI 反向导航）。
- `ai/ui/AIFloatBallView.kt`（164 行）行为：DOWN 时 alpha→1 并 `parent.requestDisallowInterceptTouchEvent(true)`；MOVE 超 8dp 阈值进入 dragging（`x/y += delta` + `clampToParent()`）；UP 若 dragging 则 `dockToNearestEdge()`（ValueAnimator 220ms 吸附最近左右边缘 + alpha 0.5 + `savePosition()` 存 `PreferKey.aiFloatBallSide(L/R)` 与 `aiFloatBallYRatio`），否则 `performClick()` → `startActivity(AgentHubActivity, preset_book/preset_chapter, FLAG_ACTIVITY_NEW_TASK)`。`post{restorePosition()}` 恢复记忆位置（默认右边 72% 高度处）。

### 2.7 生命周期与系统栏

- `onCreate` 前 `Help.setOrientation(this)`（按 `PreferKey.screenOrientation` 锁方向）；`onActivityCreated` 里 `Help.upLayoutInDisplayCutoutMode(window)` 刘海适配、`ReadBook.callBack = this`、观察 `ReadBook.titleDate`。
- `onResume` 注册动态广播 `TimeBatteryReceiver`（TIME_TICK→TIME_CHANGED、BATTERY_CHANGED→BATTERY_CHANGED 事件刷新页眉时间电量）；`onPause` `ReadBook.saveRead()` + `SyncBookProgress.uploadBookProgress()` + `Backup.autoBack()`。
- 沉浸式：`upSystemUiVisibility()` → `Help.upSystemUiVisibility(window, !read_menu.isVisible)`（隐藏菜单时全沉浸 hide navigation/status），导航栏颜色 `upNavigationBarColor()` 随阅读背景（ColorDrawable 背景/纯色/黑色三态）。
- 息屏策略 `screenOffTimerStart()`：pref `keepLight`（秒，-1 永久常亮），`screenTimeOut - sysScreenOffTime > 0` 时 `Handler.postDelayed(keepScreenRunnable)` 到点取消 `FLAG_KEEP_SCREEN_ON`；每次触摸 `PageView.onTouchEvent` 都会重置计时。
- `finish()`：未加入书架的书弹出"加入书架"确认（lib/dialogs alert + okButton/noButton）。

---

## 3. UI 其余子包职责速览

| 子包 | 核心 Activity/Fragment | 关键交互 |
|---|---|---|
| `ui/main/*` | MainActivity + Bookshelf/Explore/Rss/My 四 Fragment；`my/MyFragment` 是 PreferenceFragment（含 Web 服务开关 `PreferKey.webService`→启停 WebService） | ViewPager 换页、分组 Tab、双击回顶 |
| `ui/book/info(/edit)` | `BookInfoActivity`（389 行）/`BookInfoEditActivity` | 详情展示、加入/移出书架、换源 ChangeSourceDialog、封面更换 changecover、`startReadActivity()` 分发音频/文字 |
| `ui/book/search` | `SearchActivity`（357 行）+ SearchViewModel | 多书源并发搜索、聚合结果、加入书架 |
| `ui/book/source/edit` | `BookSourceEditActivity`（430 行） | **书源编辑器**：TabLayout 分信息/搜索/发现/正文等编辑页（`setEditEntities(tabPosition)`），软键盘上方工具条 `KeyboardToolPop`（`showKeyboardTopPopupWindow()`），菜单 `menu_save→viewModel.save` 校验后回写、`menu_debug_source` 保存后跳 `BookSourceDebugActivity`；`insertText()` 向 EditText 插入模板片段；未保存退出弹确认 |
| `ui/book/source/manage` | `BookSourceActivity`（447 行） | 书源批量管理：多选（DragSelectTouchHelper）、扫码导入（QrCodeActivity）、本地/网络导入、启用停用、校验（CheckSourceService） |
| `ui/book/source/debug` | `BookSourceDebugActivity` | 逐步调试搜索/发现/正文流程，日志列表 |
| `ui/book/chapterlist` | `ChapterListActivity`（screenOrientation=behind） | 目录搜索/反查正则/倒序，选章 `setResult(index)` |
| `ui/book/explore`、`ui/book/local`、`ui/book/download`、`ui/book/arrange`、`ui/book/group`、`ui/book/changesource` | ExploreShowActivity、ImportBookActivity（扫描本地 txt/epub）、DownloadActivity（选章下载→DownloadService）、ArrangeBookActivity（批量分组/删除）、GroupManageDialog/GroupSelectDialog、ChangeSourceDialog | — |
| `ui/rss/*` | RssSourceActivity/Edit/Debug（订阅源管理同书源套路）、RssSortActivity（条目列表）、`ReadRssActivity`（WebView 阅读，hardwareAccelerated，js 注入交互）、RssFavoritesActivity | star 收藏、图片点击 PhotoDialog |
| `ui/audio` | `AudioPlayActivity`（227 行） | 听书播放器：封面/进度/倍速/定时，与 AudioPlayService 经 IntentAction + LiveData(AudioPlay.titleData/coverData) + AUDIO_* 事件通信 |
| `ui/replacerule(/edit)` | `ReplaceRuleActivity`（314 行）/ReplaceEditDialog | 替换规则 CRUD、拖拽排序、从阅读页选中文字快捷创建 |
| `ui/config` | `ConfigActivity`（容器）+ OtherConfigFragment/ThemeConfigFragment/BackupConfigFragment/**AiConfigFragment(fork)**（BasePreferenceFragment 族） | configType 决定装载哪个 Fragment；备份恢复 BackupRestoreUi |
| `ui/about` | AboutActivity、DonateActivity、ReadRecordActivity（阅读时长统计图表） | — |
| `ui/association` | `ImportBookSourceActivity/ImportRssSourceActivity/ImportReplaceRuleActivity/FileAssociationActivity`（Transparent 主题） | **scheme 分发**：manifest 注册 `yuedu://booksource|rsssource|replace`；`onActivityCreated` 先看 extra `dataKey`（应用内跳转）再看 `intent.data`；URL 内容多为 base64/json，`FileAssociationViewModel.dispatchIndent(uri)` 按 mimeType/后缀分发到导入书籍流程；透明壳完成导入即 finish |
| `ui/qrcode` | `QrCodeActivity`（QRCodeView.Delegate） | 扫码器，结果 onActivityResult 回给书源/订阅源管理页做 URL 导入 |
| `ui/login` | `SourceLogin` | 书源 loginUrl 万能登录 WebView（header/js 注入回调） |
| `ui/welcome` | WelcomeActivity + Launcher1-6 | 见 §1 |
| `ui/filechooser` | FileChooserDialog/FilePicker（Saf 迁移前自研文件选择器）+ utils/FileUtils | — |
| `ui/widget` | 见 §7.3 自定义控件 | — |

**layout 统计**（`app/src/main/res/layout/` 共 **127 个 xml**）：

| 前缀 | 数量 | 说明 |
|---|---|---|
| `item_` | 35 | 列表条目（书架/目录/搜索结果/书源…） |
| `activity_` | 30 | 每屏一个布局（含 fork 新增 activity_agent_hub/activity_ai_log） |
| `dialog_` | 25 | DialogFragment 布局 |
| `view_` | 18 | 自定义控件布局（view_read_menu/view_progressbar…） |
| `fragment_` | 8 | 四个主 Tab + 书籍分组页等 |
| `ai_` | 7 | **AI fork 新增**（hub 界面/会话 item/输入区等） |
| `popup_` | 2、preference 相关 2 | 键盘工具条、偏好占位 |

命名规律严格遵循 `<类型>_<模块>_<用途>` snake_case；阅读相关布局约 10 个（activity_book_read、view_read_menu、dialog_read_* 系列）。

---

## 4. service 清单（`app/src/main/java/io/legado/app/service/`）

公共骨架 `base/BaseService.kt`：`Service + CoroutineScope by MainScope()`，`execute{} = Coroutine.async(...)`（自定义协程封装 help/coroutine/Coroutine.kt，带 onError/onSuccess/onFinally），`onBind` 返回 null（全是 startService 型），`onDestroy cancel()`。服务控制统一走显式 Intent + `constant/IntentAction.kt` 常量（play/pause/resume/stop/addTimer/setTimer/prevParagraph/nextParagraph/upTtsSpeechRate/adjustSpeed/adjustProgress/prev/next/remove/start/init）。

| Service | 行数 | 职责 | 前台方式 | 与 UI 通信 |
|---|---|---|---|---|
| `WebService` | 124 | 承载内置 HTTP/WebSocket 服务器 | `startForeground(notificationIdWeb, channelIdWeb)`，通知文案显示 `http://ip:port`，附停止 PendingIntent | companion `isRun`；停止时 `postEvent(WEB_SERVICE_STOP)`；开关在 MyFragment（SwitchPreference `webService`） |
| `DownloadService` | 295 | 离线下载章节正文 | channelIdDownload 通知进度（成功/失败计数） | `postEvent(UP_DOWNLOAD)`；`help/Download` 单例封装 start/remove/stop + 内存日志 `logs`(≤1000 条，UI 轮询) |
| `BaseReadAloudService`（抽象） | 343 | 朗读骨架：MediaSessionCompat("readAloud")、AudioFocusRequest、耳机/蓝牙按钮、通知（点击回阅读页 `aloudServicePendingIntent`）、段落队列 contentList、定时关闭 doDs(10min 步进至 180min) | channelIdReadAloud 通知 | companion `isRun/timeMinute/pause`；`postEvent(ALOAD_STATE/TTS_PROGRESS/TTS_DS)` |
| `HttpReadAloudService` | 207 | 在线 TTS 引擎（httpTTS 表用户自配 API）：逐段 POST 合成 mp3 存 `externalCacheDir/httpTTS/{index}.mp3`，MediaPlayer 连播，段完成自动下一段/下一章 | 同上 | 每段开始 `postEvent(TTS_PROGRESS, readAloudNumber+1)` → 阅读页高亮朗读位置 |
| `TTSReadAloudService` | 193 | 系统 TextToSpeech 朗读；UtteranceProgressListener onStart/onDone/onRangeStart 驱动进度与翻段 | 同上 | 同 TTS_PROGRESS 事件 |
| `AudioPlayService` | 536 | 有声书播放：MediaPlayer + MediaSession，action play/pause/resume/prev/next/adjustSpeed/adjustProgress/addTimer/setTimer；章节预载（loadingChapters 去重）、进度写库 saveProgress | channelIdReadAloud 通知（封面+进度） | `AudioPlay` 单例（MutableLiveData titleData/coverData + status/book 字段）+ `AUDIO_STATE/AUDIO_PROGRESS/AUDIO_SIZE/AUDIO_SPEED/AUDIO_SUB_TITLE` 事件 |
| `CheckSourceService` | 126 | 书源可用性批量校验：对所选书源执行 `CheckSource.check()`＝`WebBook.searchBook("我的") timeout(60s)`，失败 `addGroup("失效")` 成功 `removeGroup` | `startForeground(112202, ...)` 无界面通知进度 | 完成即止；结果直接写库，列表页刷新 |

`help/ReadAloud.kt`（object）：按 `PreferKey.speakEngine` 是否命中 httpTTS 表决定路由到 `HttpReadAloudService` 或 `TTSReadAloudService`（`getReadAloudClass()`），play/pause/resume/stop/prevParagraph/nextParagraph/upTtsSpeechRate/setTimer 全部封装成 Intent 发送；朗读内容本体经 `IntentDataHelp.putData(textChapter)` 内存中转（避免 Binder 1MB 限制）。

> 注意：**没有独立的前台"媒体样式通知工具类"**，各 Service 各自拼 NotificationCompat.Builder；`help/MediaHelp.kt` 只负责音频焦点与 MediaSession 辅助。

---

## 5. 内置 Web 服务（`app/src/main/java/io/legado/app/web/`）

- **实现**：NanoHTTPD / NanoWSD（`fi.iki.elonen.*`）。`HttpServer(port) : NanoHTTPD`，`WebSocketServer(port) : NanoWSD`。
- **端口**：HTTP = pref `webPort`（合法域 1024~65530，默认 **1122**，非法回落 1122，见 `WebService.getPort()`）；WS = HTTP 端口 + 1。
- **启动链**：MyFragment 开关 → `WebService.start(context)` → `onStartCommand` → `upWebServer()`：取本机 IP（`NetworkUtils.getLocalIPAddress()`），`httpServer.start(); webSocketServer.start(30s 超时)`；前台通知展示地址；`IntentAction.stop` 停止；onDestroy postEvent(WEB_SERVICE_STOP)。

### 5.1 REST 路由表（`HttpServer.serve()` 内 when(uri)）

| Method | Path | Controller 方法 | 功能 |
|---|---|---|---|
| OPTIONS | * | （内联） | CORS 预检：Allow-Methods POST、Allow-Headers content-type、origin 回显 |
| GET | `/getSources` | `SourceController.sources` | 全部书源 JSON |
| GET | `/getSource?url=` | `SourceController.getSource(params)` | 单个书源 |
| GET | `/getBookshelf` | `BookshelfController.bookshelf` | 全部书籍（按 `bookshelfSort`：1 最近更新/2 名称/3 手动/默认最近阅读排序） |
| GET | `/getChapterList?url=` | `BookshelfController.getChapterList(params)` | 指定书籍目录 |
| GET | `/getBookContent?url=&index=` | `BookshelfController.getBookContent(params)` | 正文：先 `BookHelp.getContent` 缓存，miss 时 `runBlocking { WebBook.getContentSuspend }` 在线抓取；顺带 `saveBookReadIndex()` 若 index 大于当前进度则更新库和内存 `ReadBook` |
| POST | `/saveSource`（body json） | `SourceController.saveSource` | 保存单个书源（名称/URL 必填） |
| POST | `/saveSources` | `SourceController.saveSources` | 批量保存 |
| POST | `/deleteSources` | `SourceController.deleteSources` | 批量删除 |
| POST | `/saveBook` | `BookshelfController.saveBook` | 插入/覆盖书籍 |
| 其它 GET | 任意路径 | `AssetsWeb("web").getResponse(uri)` | 从 `assets/web/` 返回静态 Web 门户（缺省补 index.html），MIME 手工映射 html/js/css/ico |

- 统一响应体 `web/utils/ReturnData.kt`：`{isSuccess, errorCode, errorMsg, data}`，Gson 序列化后 `newFixedLengthResponse`；响应头同样回显 CORS origin。
- **WebSocket**：`/sourceDebug` → `SourceDebugWebSocket`：收到 `{tag:书源URL, key:搜索关键字}` 即 `Debug.startDebug(WebBook(source), key)`，`printLog(state,msg)` 推送调试日志（state==-1||1000 结束），30s ping 保活，close/exception 时 `Debug.cancelDebug(true)`。这是 PC 端书源编辑器实时调试手机书源的通道。
- **二维码连接流程**：该 fork 未提供"Web 服务二维码"，连接流程为：My 页打开 `webService` 开关 → 前台通知给出 `http://<局域网IP>:1122` → PC 浏览器访问静态门户（assets/web）→ 门户 JS 调上述 REST 完成书源导入/书架浏览/阅读。`QrCodeActivity` 是反向能力（扫外部的 URL 二维码用于导入书源/订阅源，结果经 onActivityResult 回调用），与服务互联无关。

---

## 6. ContentProvider API（`app/src/main/java/io/legado/app/api/` + `api.md`）

- `ReaderProvider.kt`（271 行）：authority = `${applicationId}.readerProvider`（release 包名形如 `io.legado.app.release.readerProvider`，不同包不冲突）；**exported=true**。
- 实现思路：**完全复用 Web 层 Controller**（`web/controller/SourceController`、`BookshelfController`），query 结果包装进内部类 `SimpleCursor`——伪 Cursor：count 恒 1、`getString(0)` 返回 ReturnData 的 JSON 字符串。

| Uri（相对 authority） | Provider 操作 | 转发到 | 参数 |
|---|---|---|---|
| `source/insert` | insert | `saveSource` | ContentValues key=`json`=单个书源 JSON |
| `sources/insert` | insert | `saveSources` | key=`json`=书源数组 |
| `book/insert` | insert | `saveBook` | key=`json`=Book JSON |
| `sources/delete` | delete | `deleteSources` | selection=数组 JSON |
| `source/query?url=` | query | `getSource` | url |
| `sources/query` | query | `getSources` | — |
| `books/query` | query | `getBookshelf` | — |
| `book/chapter/query?url=` | query | `getChapterList` | url |
| `book/content/query?url=&index=` | query | `getBookContent` | url,index |

`getType/update` 直接抛 UnsupportedOperationException。`api.md` 声称使用需声明 `io.legado.READ_WRITE` 权限，但 manifest 中 provider **并未配置 permission 属性**（详见 §9 安全坑）。

---

## 7. base 基类约定 / receiver / lib·widget

### 7.1 base 包

- `BaseActivity(layoutID, fullScreen=true, theme=Auto, toolBarTheme=Auto, transparent=false)`（151 行）：
  - `attachBaseContext` → `LanguageUtils.setConfiguration(newBase)` 应用内语言切换；
  - `onCreate` 顺序：`decorView.disableAutoFill()` → `initTheme()`（按 primaryColor 明暗自动选 AppTheme_Light/Dark 或显式 Theme）→ `setupSystemBar()`（fullScreen 时清除 TRANSLUCENT_* 加 DRAWS_SYSTEM_BAR_BACKGROUNDS + LAYOUT_FULLSCREEN|LAYOUT_STABLE，`ATH.setStatusBarColorAuto`、`setLightStatusBar`、`upNavigationBarColor()`）→ `setContentView(layoutID)` → 抽象 `onActivityCreated(savedInstanceState)` → `observeLiveBus()`；
  - 菜单：final 化 onCreateOptionsMenu/onOptionsItemSelected，统一 `applyTint` 染色，home 键 = `supportFinishAfterTransition`；
  - `CoroutineScope by MainScope()`，onDestroy cancel；`finish()` 自动收起软键盘；
  - `onCreateView` 拦截 `AppConst.menuViewNames` 给 popup 菜单容器刷背景色。
- `VMBaseActivity<VM>`：仅增加抽象 `val viewModel`（配合扩展 `getViewModel(Class)`）。
- `BaseFragment(layoutID)`：构造函数直接传布局；`job=Job()` 于 onCreateView 创建，`coroutineContext = job + Dispatchers.Main`，onDestroy cancel；`onViewCreated` 调抽象 `onFragmentCreated(view,savedInstanceState)` + `observeLiveBus()`；`setSupportToolbar(toolbar)` 手动 inflate 菜单并染色（绕过 AppCompatActivity 菜单机制）。
- `BaseViewModel`：AndroidViewModel + MainScope；`execute/submit` 包装 Coroutine.async；toast/longToast；onCleared cancel。
- `BasePreferenceFragment`：PreferenceFragmentCompat，接管 EditText/List/MultiSelectList 偏好的 tint 对话框。
- `base/adapter/*`：`CommonRecyclerAdapter`（456 行，ItemViewDelegate 多类型绑定 + setHasStableIds）、`SimpleRecyclerAdapter`、`InfiniteScrollListener`、动画族 BaseAnimation/AlphaIn/ScaleIn/SlideIn*。
- **权限申请不在 base**：封装于 `help/permission/`（自研 Permissions 库：`Permissions.kt/Request.kt/RequestManager.kt/PermissionActivity`——透明中介 Activity 承接 onRequestPermissionsResult，`ActivitySource/FragmentSource` 提供 `requestPermissions(cb)` DSL）。典型用法 `Permissions.request(this, READ_EXTERNAL_STORAGE){...}`。

### 7.2 receiver 包

| 文件 | 类型 | 行为 |
|---|---|---|
| `TimeBatteryReceiver.kt`（40 行） | 动态注册（`register(context)`，ACTION_TIME_TICK + ACTION_BATTERY_CHANGED） | 每分钟/电量变化 `postEvent(TIME_CHANGED/BATTERY_CHANGED)` 刷新阅读页页眉 |
| `MediaButtonReceiver.kt`（104 行） | manifest 静态注册 MEDIA_BUTTON + 被朗读服务内部广播复用（companion `handleIntent`） | 耳机按键：朗读运行→暂停/继续（连带 AudioPlay）；否则若 AudioPlayActivity/ReadBookActivity 存在 `postEvent(MEDIA_BUTTON,true)`；`mediaButtonOnExit` 开启时 GlobalScope 查 `lastReadBook` 冷启动 MainActivity+ReadBookActivity(extra readAloud=true) 继续听书 |
| `SharedReceiverActivity.kt`（56 行） | PROCESS_TEXT / SEND(text/plain) 透明壳 | 提取文本，`openUrl()` 正则筛 http 链接：含 URL → 直接进 SearchActivity(key=text)；纯文本 → 也进搜索 |

### 7.3 lib 与 ui/widget（自定义控件）

- `lib/theme/`：改编版 Android Theme Library（ATH）：`ThemeStore`（339 行，SharedPreferences 持久化 primary/accent/background/bottomBackground/coloredNavigationBar，editTheme().apply() 链式）、`ATH`（applyEdgeEffectColor/applyBottomNavigationColor/setStatusBarColorAuto/setNavigationBarColorAuto/setLightStatusBar）、`TintHelper`（474 行控件染色）、`Selector.tintSelector`、`view/ATE*`（CheckBox/Switch/SeekBar/RadioButton/ProgressBar 自动随主题染色）、`MaterialValueHelper`（dp/px）。Kotlin 扩展属性 `primaryColor/accentColor/backgroundColor/bottomBackground` 全工程通用。
- `lib/dialogs/`：anko dialogs 的可染色重实现（AlertBuilder/AndroidAlertBuilder/Selectors），配合 `applyTint()` 使用（如阅读页 finish 时加书架确认框）。
- `lib/webdav/`：自研 WebDav 客户端（250 行，OkHttp + PROPFIND/PUT/DELETE，`Handler/HttpAuth`），供备份/恢复（BackupConfigFragment → WebDavSettings）。
- `ui/widget/`：`TitleBar`（统一标题栏）、`SearchView`、`DetailSeekBar`（朗读对话框横向滑条）、`LabelsBar`（标签栏）、`KeyboardToolPop`（书源编辑器键盘上方工具条）、`SelectActionBar`、`ShadowLayout`、`ArcView/BatteryView`（页眉页脚时钟电量装饰）、`anima/RotateLoading`（加载圈）+`explosion_field`（爆裂删除动画）、`checkbox/SmoothCheckBox`、`font/FontSelectDialog`、`image/PhotoView(1268行)+CircleImageView`、`number/NumberPicker` 封装、`prefs/`（ColorPreference 447 行、IconListPreference、EditText/List/MultiSelectList PreferenceDialog、NumberPickerPreference）、`recycler/FastScroller`（518 行侧滑快滚）+ `DragSelectTouchHelper`（975 行长按拖动多选）、`seekbar/VerticalSeekBar`（363 行，阅读菜单亮度条）、`text/BadgeView`、`InertiaScrollTextView`（惯性滚动字幕，音频页歌名）。

---

## 8. EventBus / Flow 事件体系

- **框架**：LiveEventBus（`com.jeremyliao.liveeventbus`），App.onCreate 全局配置 `supportBroadcast + lifecycleObserverAlwaysActive(true) + autoClear(false)`。
- **封装**：`utils/EventBusKt.kt` —— `postEvent(tag,event)`、`postEventDelay(tag,event,delay)`、`AppCompatActivity.observeEvent/observeEventSticky`、`Fragment.observeEvent/observeEventSticky`（lifecycle-aware，自动跟随生命周期注销）。
- **tag 常量**集中在 `constant/EventBus.kt`（19 个）：MEDIA_BUTTON、RECREATE、UP_BOOK、ALOUD_STATE、TTS_PROGRESS、TTS_DS、BATTERY_CHANGED、TIME_CHANGED、UP_CONFIG、OPEN_CHAPTER、AUDIO_SUB_TITLE、AUDIO_STATE、AUDIO_PROGRESS、AUDIO_SIZE、AUDIO_SPEED、SHOW_RSS、WEB_SERVICE_STOP、UP_DOWNLOAD、SAVE_CONTENT。
- **用量**：`postEvent(` 约 **98 处**、`observeEvent` 约 **35 处**；注册点约定为 BaseActivity/BaseFragment 的 `observeLiveBus()` 钩子（onCreate/onViewCreated 内调用）。
- 典型回路举例：
  - 朗读：`TTSReadAloudService.onRangeStart → postEvent(TTS_PROGRESS, n)` → `ReadBookActivity.observeEventSticky(TTS_PROGRESS)` 计算 `pageStart` 更新 `TextPage.upPageAloudSpan` 高亮；`ALOUD_STATE` 驱动菜单图标与 span 清除。
  - 配置：任意配置对话框改完 → `postEvent(UP_CONFIG, needReLoad:Boolean)` → 阅读页 upSystemUiVisibility/upBg/upStyle/(loadContent|upContent)。
  - 电量时间：receiver → BATTERY_CHANGED/TIME_CHANGED → `page_view.upBattery/upTime`。
- **补充通道**（并存，非 Flow）：`MutableLiveData` 直连（`ReadBook.titleDate`、`AudioPlay.titleData/coverData`）；单例 callBack 直调（`ReadBook.callBack` 即 Activity 强引用）；companion 静态标志位（`BaseReadAloudService.isRun/pause`、`WebService.isRun`、`Download.logs`）。**全工程未使用 kotlinx.coroutines.Flow 做事件流**（协程只用于异步任务编排）。

---

## 9. 技术债与坑

1. **`ReadBook.callBack` 强引用 Activity 且从不置 null**：`onActivityCreated` 里 `ReadBook.callBack = this`，`onDestroy` 仅清 `msg`（grep 全仓无 `callBack = null`）——静态单例持 Activity 引用，旋转/重建期间旧实例泄漏且回调可能打到销毁中的对象。
2. **GlobalScope 滥用**：`ReadBook.moveToNextChapter/moveToPrevChapter` 的 10 章/4 章预取循环、`MediaButtonReceiver.readAloud` 冷启动查询都在 GlobalScope，脱离生命周期，进程内不可取消。
3. **kotlinx.android.synthetic**：全工程依赖已废弃的 kotlin-android-extensions 视图合成（`import kotlinx.android.synthetic.main.activity_book_read.*`），升级 Kotlin 后必须迁移 ViewBinding。
4. **ReaderProvider 安全**：manifest `exported="true"` 且无 permission 属性，api.md 所述 `io.legado.READ_WRITE` 并未落地——任意第三方 App 可读写书架、篡改书源（tools:ignore="ExportedContentProvider" 直接忽略 lint）。
5. **Web 服务零鉴权 + CORS 全开**：`HttpServer` 对 origin 直接回显，无 token/签名；同一 Wi-Fi 下任何设备可增删书源、读取全部书架与正文；`getBookContent` 里 `runBlocking { getContentSuspend }` 在 NanoHTTPD worker 线程同步抓网，无超时兜底，慢源会拖死该连接线程。
6. **端口约定脆弱**：WebSocket 固定占用 `webPort+1`，被占时不检测；端口合法性检查粗糙（1024~65530 之外静默回落 1122）。
7. **上帝类**：`ReadBookActivity` 一个类实现 12 个接口、持有 Handler/Runnable/Receiver/PopupMenu 等；菜单逻辑分散在 ReadMenu、Help、15 个 config dialog，回调靠 Activity 强转 `activity as CallBack`（`PageView.callBack get() = activity as CallBack`——任何非阅读页复用该控件直接 ClassCastException）。
8. **ChapterProvider 全局可变排版状态**：viewWidth/viewHeight/Padding/Paint 都是 object 单例字段，横竖屏切换靠 `onConfigurationChanged → loadContent(resetPageOffset=false)` 手工重排；多窗口/分屏尺寸变化时序不稳时会出现排版错位或旧页残留。
9. **逐字符渲染开销**：ContentTextView 遍历 TextChar 逐字 drawText，长章节 TextChar 对象数量巨大（内存峰值），滚动模式 `maxScrollOffset=100f` 硬编码；ImageProvider 缓存依赖 `clearOut(durChapterIndex)` 手工淘汰，快速连翻时易瞬时膨胀。
10. **朗读进度高亮耦合计算**：`TTS_PROGRESS` 携带的 `readAloudNumber+1` 需配合 `textChapter.getReadLength(pageIndex)` 反推页内偏移；切换 `readAloudByPage` 开关、手动跳章后 `nowSpeak/contentList` 与页面 span 易错位。
11. **AIFloatBallView 细节**：`parent.requestDisallowInterceptTouchEvent(true)` 对兄弟节点 PageView 无效（PageView 的 onInterceptTouchEvent 只作用于自己的 children），属无效防御代码；悬浮球固定悬浮于 PageView 上方，长按选择右下角文字时光标/菜单可能被球遮挡；位置记忆读写 SharedPreferences 在每次 UP 触发（主线程 I/O）。
12. **桌面图标切换方案**：Launcher1~6 六个真实 Activity 作为 LAUNCHER，靠 enable/disable 切换（LauncherIconHelp），部分 ROM 上禁用组件会导致图标短时消失/应用抽屉错乱，升级后 alias 状态恢复也是经典坑。
13. **MainActivity Adapter 全量重建**：`getItemPosition=POSITION_NONE` + 预建 fragmentMap，SHOW_RSS 切换时四个 Fragment 全部重建，书架滚动位置丢失。
14. **四套通信机制并存**（Intent action / LiveEventBus / 单例 callBack / LiveData+静态标志位），状态真值分散：如 `BaseReadAloudService.pause` 是静态 var，进程被杀重建后 UI 依据它决定"退出还是退后台"的逻辑可能失真；`Download.logs` 内存日志需 UI 主动轮询而非推送。
15. **假 Cursor 的 Provider 语义**：`SimpleCursor` 所有 move 恒 true、getColumnCount=0，第三方若按标准 Cursor 协议消费（列名/类型）会踩坑；`update/getType` 抛异常而非返回默认值。
16. **硬编码中文与魔法数**：ReturnData 默认错误"未知错误,请联系开发者!"、CheckSource 关键词 `"我的"`、失效分组 `"失效"`、`startForeground(112202,...)` 魔法通知 id、`delay(100)` 预取节奏等散落各处。
17. **AI fork 冷启动成本**：App.onCreate 主线程串行 `AiLog.attach()` + `AiPlatform.init()`（装配 client/registry/runtime），叠加 HanLP 预热（WelcomeActivity 异步）后，低端机冷启动感知变长；AgentHubActivity 通过 extras 传整章 `preset_content`（可能数百 KB），依赖 Intent 容量上限内的内存中转（与 IntentDataHelp 同思路但未复用）。

---

## 附：关键文件索引

```
App 入口     app/src/main/java/io/legado/app/App.kt · ui/welcome/WelcomeActivity.kt · ui/main/MainActivity.kt
阅读页       ui/book/read/{ReadBookActivity,ReadBookViewModel,ReadMenu,TextActionMenu,Help}.kt
             ui/book/read/page/{PageView,ContentView,ContentTextView,TextPageFactory}.kt
             ui/book/read/page/provider/ChapterProvider.kt · page/delegate/*.kt · page/entities/*.kt
数据引擎     service/help/ReadBook.kt · help/BookHelp.kt · help/ReadBookConfig.kt
服务         service/{WebService,DownloadService,BaseReadAloudService,HttpReadAloudService,TTSReadAloudService,AudioPlayService,CheckSourceService}.kt
Web          web/HttpServer.kt · web/WebSocketServer.kt · web/controller/{BookshelfController,SourceController,SourceDebugWebSocket}.kt · web/utils/{ReturnData,AssetsWeb}.kt
API          api/ReaderProvider.kt · api.md（仓库根）
基础         base/{BaseActivity,BaseFragment,BaseViewModel,VMBaseActivity,BaseService}.kt · help/permission/*
事件         constant/EventBus.kt · utils/EventBusKt.kt
AI(fork)     ai/ui/{AIFloatBallView,AgentHubActivity,AuroraBackgroundView}.kt · ai/AiPlatform.kt · ai/runtime/* · ai/bridge/*
```
