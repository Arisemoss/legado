# 「阅读3.0」legado（AI fork）业务核心层精读报告：model/ 与 help/

> 分析对象：`/root/github/legado`（分支 HEAD：`4333845eb`，AI 增强版 fork）
> 范围：`app/src/main/java/io/legado/app/model/**`（22 个 Kotlin 文件，4168 行）与 `app/src/main/java/io/legado/app/help/**`（52 个文件，4901 行），并延伸到与之耦合的 `service/help/`、`lib/theme/`、`web/`。

---

## 0. 重要前提：本 fork 与上游 legado 的包布局差异

任务清单中引用的是**上游 gedoor/legado 的经典布局**，本 AI fork 已做了大幅裁剪/搬迁，先给出映射表，后文全部按实际代码位置展开：

| 任务描述中的位置 | 本仓库实际位置 | 说明 |
|---|---|---|
| `model/analyzeRule/RuleAnalyzer.kt` | **不存在** | 上游的性能优化版规则分析器被删除，`AnalyzeRule.splitSourceRule()` 直接承担规则拆分 |
| `model/analyzeRule/AnalyzeByJSon.kt` | **不存在** | 仅保留 Jayway JsonPath 一条 JSON 路线 |
| `model/webBook/WebBookModel.kt` | **不存在** | 书架刷新调度上移到 ViewModel/service |
| `model/Download/` | `service/DownloadService.kt` + `service/help/Download.kt` | 离线下载改为 Service |
| `model/readAloud/`、`model/audio/` | `service/*ReadAloudService.kt`、`service/AudioPlayService.kt` + `service/help/ReadAloud.kt`/`AudioPlay.kt` | 朗读/播放均为前台 Service |
| `model/themes/` | `constant/Theme.kt` + `lib/theme/*`（ATH/ThemeStore）+ `help/ReadBookConfig.kt` | 三层拆分 |
| `model/CacheManager`、`model/CleanUp` | **不存在**；残留 `help/BookHelp.clearCache()`、`utils/ACache.kt` | 无统一缓存管理器 |
| `model/WebOkHttp` | **不存在**，网络栈为 `help/http/HttpHelper.kt`（OkHttp+Retrofit） | |
| `help/book/`（BookHelp/BookUpdate） | `help/BookHelp.kt`（平铺）；**无 BookUpdate**（更新检查散落在 ViewModel） | |
| `help/config/`（AppConfig/UserSourceConfig） | `help/AppConfig.kt`（平铺）；**无 UserSourceConfig**（源变量存 `Book.variable` JSON） | |
| `help/source/BookSourceHelp` | `help/SourceHelp.kt`（18+ 源过滤导入） | |
| `help/storage/Directory` | **不存在**！目录规范硬编码在各使用点（见 §4） | |
| `help/cachedac/` | **不存在**；最接近物为 `utils/ACache.kt`（自研文件 KV 缓存） | |
| `help/contentprovider/`、`help/process/` | **不存在** | |

以下所有结论均以实际代码为准。

---

## 1. 规则引擎 `model/analyzeRule/` —— 书源系统的灵魂

### 1.1 总体架构：AnalyzeRule 统一门面 + 四种解析后端

```
                       ┌──────────────────────────────────────────────┐
书源JSON ──GSON──▶ BookSource.ruleSearch/ruleExplore/ruleBookInfo/     │
                        ruleToc/ruleContent (data/entities/rule/*.kt) │
                       └──────────────┬───────────────────────────────┘
                                      ▼
        ┌────────────────── AnalyzeRule (门面, 646行) ──────────────────┐
        │ setContent(content, baseUrl)  ← HTML字符串/Element/JXNode/JSON│
        │ splitSourceRule(ruleStr) → List<SourceRule>  (模式识别+分段) │
        │ getString / getStringList / getElements / getElement         │
        │ put()/get()  变量上下文(@put:/@get:{})                        │
        └──┬──────────────┬───────────────┬──────────────┬─────────────┘
           ▼Mode.Default  ▼Mode.Json      ▼Mode.XPath    ▼Mode.Js / Mode.Regex
     AnalyzeByJSoup   AnalyzeByJSonPath AnalyzeByXPath  SCRIPT_ENGINE(Rhino)
     (JSoup CSS+      (Jayway JsonPath, (SeimiCrawler    AnalyzeByRegex
      legado默认语法)  {$.x}嵌套模板)     JXDocument)      (:开头,&&多段)
```

- 门面类：`class AnalyzeRule(var book: BaseBook? = null) : JsExtensions`（`AnalyzeRule.kt:25`）
  - 持有三个惰性后端字段 `analyzeByXPath / analyzeByJSoup / analyzeByJSonPath`，用脏标记 `objectChangedXP/JS/JP` 控制：`setContent()`（`:42`）把三个标记置 true，下次取对应后端时才重新 `parse(content)`——**同一份 content 上连续求值多条规则时 DOM 只解析一次**，这是本 fork 替代上游 RuleAnalyzer 的核心缓存手段。
  - `setContent` 自动嗅探：`isJSON = content.toString().isJson()`，后续规则若未显式指定前缀，将按 `isJSON → Mode.Json` 兜底（`splitSourceRule` `:370-371`）。

### 1.2 五种 Mode 与选择器识别（`splitSourceRule` :347-395，`SourceRule.init` :400-507）

`enum class Mode { XPath, Json, Default, Js, Regex }`（`:573`）。整条规则先做**整体模式判定**，再按 `<js>...</js>` / `@js:` 正则（`AppPattern.JS_PATTERN = "(<js>[\\w\\W]*?</js>|@js:[\\w\\W]*$)"`）切成「普通规则段 + JS 段」交替的列表：

整体前缀 → 模式：
| 规则前缀 | Mode | 备注 |
|---|---|---|
| `@@` | Default | 剥掉 `@@` 后走 JSoup |
| `@XPath:`（忽略大小写）或以 `//` 开头 | XPath | `//` 特征明显无需标头（源码注释 ：432） |
| `@Json:` 或 `$.` 开头 | Json | Jayway 语法 |
| `@CSS:`（忽略大小写） | Default(JSoup CSS) | |
| `:` 开头 | Regex | 且置实例级 `isRegex=true`，后续段落延续 Regex |
| 无前缀 + 内容是 JSON | Json | 由 `isJSON` 推断 |
| 无前缀 HTML/文本 | Default | legado 自创默认语法 |

每一段 `SourceRule` 内部再做四步加工：
1. **剥离 `@put:{...}`**：`splitPutRule()`（`:311`），正则 `putPattern = "@put:(\{[^}]+?\})"`，解析成 `HashMap<String,String> putMap`，求值前由 `putRule()` 先执行（把变量写入上下文）。
2. **`##` 正则净化分离**：`rule##正则##替换##只替换首次`（`:457-467`）→ `replaceRegex/replacement/replaceFirst`。注意 ：450-456 有个细节：先找 `}}`，其之前的部分拼回主规则，避免 `{{js}}` 内部的 `##` 被误切。
3. **内嵌求值符拆分**（`evalPattern = "@get:\{[^}]+?\}|\{\{[\w\W]*?\}\}|\$\d{1,2}"`，`:640`）：把规则再切成 `ruleParam/ruleType` 平行数组——`$N`（type=N，引用上一级 Regex 结果的第 N 个分组）、`{{js}}`(type=-1)、`@get:{key}`(type=-2)、其余字面量(type=0)。
4. **求值期回填 `makeUpRule(result)`**（`:512-558`）：从尾向头 insert(0) 拼接——`$N` 从 `result as? List<String?>` 取分组（result 非 List 则保留原样）；`{{js}}` 先判断 `isRule()`（是否 `$.`/`//`/`@CSS:` 等真规则，是则递归 `getString()`，否则当 JS eval，Double 整数去小数点）；`@get:{key}` 读变量。最终重组出实际执行的 `rule` 字符串。

### 1.3 求值流程（四个公开入口）

- `getString(ruleStr, isUrl=false): String`（`:177`）：`splitSourceRule` → 逐段执行：Js→`evalJS(rule, result)`；Json→`getAnalyzeByJSonPath(it).getString`；XPath→`.getString`；Default→JSoup `.getString`（**isUrl=true 时走 `getString0`，只取列表第一个元素**，避免多 URL 拼接）；每段结果过 `replaceRegex()`。末尾统一 `Entities.unescape` HTML 反转义；`isUrl=true` 时 `NetworkUtils.getAbsoluteURL(baseUrl, str)` 补全相对链接。
- `getStringList(ruleStr, isUrl)`（`:112`）：同上返回 `List<String>`；`isUrl` 时对每个 URL 做绝对化并去重。注意 ：170 `result as? List<String>` —— 若 JS 返回非 List 会安全转 null，但 `content is NativeObject` 分支（Rhino 返回对象直接按键取值 ：124-125）只取第一条规则的键，是 JS 对象直读捷径。
- `getElements(ruleStr): List<Any>`（`:266`）：列表规则，Regex 分支调 `AnalyzeByRegex.getElements(text, rule.splitNotBlank("&&"))`（多段正则逐级收缩匹配范围，最后一段的 group(0..n) 作为一行数据）。
- `getElement(ruleStr)`（`:233`）：详情页 init 规则用，先缩小 DOM 范围再在其上继续取字段（`BookInfo.analyzeBookInfo` / `BookList.getInfoItem` 中 `analyzeRule.setContent(analyzeRule.getElement(init))`）。

### 1.4 变量上下文：@put:/@get:{} 与 book/chapter.variableMap

```kotlin
fun put(key: String, value: String): String {      // AnalyzeRule.kt:577
    chapter?.putVariable(key, value) ?: book?.putVariable(key, value)
    return value
}
fun get(key: String): String {                     // :583
    return chapter?.variableMap?.get(key) ?: book?.variableMap?.get(key) ?: ""
}
```
- 存储实体：`Book.putVariable`（`data/entities/Book.kt:94`）把 `variableMap` 序列化为 JSON 存进 `Book.variable` 字段（Room 持久化）；`BookChapter.putVariable`（`BookChapter.kt:50`）同理存章级变量。章级优先于书级——即**目录页 @put 的变量可在正文页 @get 回来**（跨请求传参的标准姿势）。
- 每轮求值入口都先 `putRule(sourceRule.putMap)`（putMap 的 value 本身也是规则，递归 `getString(value)` 求值后再 put），因此规则可以边解析边写变量。

### 1.5 JS 执行环境

- 引擎：`AppConst.SCRIPT_ENGINE = ScriptEngineManager().getEngineByName("rhino")`（`constant/AppConst.kt:26`，全局单例）。
- `AnalyzeRule.evalJS(jsStr, result)` bindings：`java=this(AnalyzeRule)`、`book`、`result`、`baseUrl`（`:593-600`）。
- `AnalyzeUrl.evalJS` 额外注入 `page/key/speakText/speakSpeed`（`:279-288`）。
- `java.xxx` 能力面 = `JsExtensions` 接口（`help/JsExtensions.kt`）：`ajax(urlStr)`（同步跨域抓取，注释明言“不能删”）、`base64Decode/base64Encode/md5Encode/md5Encode16/timeFormat/utf8ToGbk/encodeURI`。这是书源 JS 沙箱的全部原生桥（远小于上游的完整 JsExtensions：无 java.ajaxFile/zip/cache 系列）。

### 1.6 四个后端实现要点

**AnalyzeByJSoup（`AnalyzeByJSoup.kt`，414 行）——legado 自创默认语法**
- `parse(doc)` 接受 `String/Element/JXNode`，一律落到 JSoup `Element`。
- 列表/文本规则通用骨架：`elementsRule.contains("&&") → &拼接`、`"%%" → %交错合并（按索引轮流取各结果集）`、否则 `|| → |短路取第一个非空`。
- 单段定位 `getElementsSingle(temp, rule)`（`:220-330`）语法：
  - `class.类名[.索引]` / `tag.标签[.索引]` / `id.值[.索引]` / `text.文本`（负索引从尾部数）；
  - `children` 取直接子节点；
  - `A.B.C` 点分层级；`>` 表示子代约束：`tag.a.0>class.abc` 中第二段作为**过滤器**（`filterElements` :199，支持 class/id/tag/text 四种包含判断）；
  - `!N` 排除第 N 个元素（可为多个 `:1:3` 形式，:311-324 置 null 后 removeAll）；
  - `@` 链式：`tag.div@tag.a` 逐级下钻；
  - 未知前缀直接 `temp.select(rulePcx[0])` 回落到 **JSoup CSS 选择器**。
- 取值末端 `getResultLast(elements, lastRule)`（`:358`）：`text / ownText / textNodes / html（剔除 script/style）/ all（含 script 原始 outerHtml）/ 其它按 attr 名取属性（自动去重）`。
- `@CSS:` 前缀走纯 CSS：`ruleStrX.lastIndexOf('@')` 前半 select、后半为属性名。

**AnalyzeByJSonPath（`AnalyzeByJSonPath.kt`，219 行）**
- `parse(json)` → `JsonPath.parse` 得 `ReadContext ctx`。
- `getString/getStringList/getList` 同样支持 `&& / %% / ||` 组合。
- 特色：**`{$.xx}` 内嵌模板**（`jsonRulePattern = "(?<=\\{)\\$\\..+?(?=\\})"`，:217）——规则形如 `{ $.data.list[*] }` 或字符串中嵌 `id={$.id}` 时先递归求出内层再替换；`getStringList` 中内嵌会产生笛卡尔展开（每个匹配值生成一条替换后的完整规则，:115-123）。
- `getObject(rule)` 直接 `ctx!!.read(rule)` 返回原生对象（供 getElement/init 使用）。

**AnalyzeByXPath（`AnalyzeByXPath.kt`，189 行）**
- 基于 SeimiCrawler `JXDocument`；`parse` 支持 `JXNode`（非 Element 节点转字符串重建 doc）、jsoup Document/Element(s)、纯字符串。
- `strToJXDocument`（:41）对残缺表格片段自动补 `<tr>/<table>` 包裹——处理列表项切片后失去父节点的场景。
- `getElements/getStringList` 支持 `&&/%%/||`；**注意 `getString()` 只实现了 `&&/||`，不支持 `%%`**（:162）。
- `jxNode?.sel(...) ?: jxDocument?.selN(...)`：当前焦点是 JXNode 时用相对查询。

**AnalyzeByRegex（`AnalyzeByRegex.kt`，object，61 行）**
- `getElement(res, regs, index)` / `getElements(res, regs, index)`：regs 为 `&&` 分隔的多段正则；非末段把所有 match 拼接后递归下一段（逐步缩小文本）；末段输出 group(0..n)。触发方式：规则以 `:` 开头，或规则中出现 `$1` 等分组引用（evalPattern 命中会把 mode 改成 Regex，`AnalyzeRule.kt:473-474`）。

### 1.7 AnalyzeUrl —— URL/Option 语法（`AnalyzeUrl.kt`，455 行）

构造器签名：
```kotlin
class AnalyzeUrl(
    var ruleUrl: String,
    key: String? = null,          // 搜索关键字
    page: Int? = null,            // 页码
    speakText: String? = null,    // 朗读文本(HttpTTS)
    speakSpeed: Int? = null,      // 语速
    headerMapF: Map<String, String>? = null,
    baseUrl: String? = null,
    book: BaseBook? = null,
    var useWebView: Boolean = false,
) : JsExtensions
```

init 三阶段（`:64-80`）：
1. `baseUrl` 先经 `splitUrlRegex = Regex(",\s*(?=\{)")` 切掉尾部 option；
2. `analyzeJs(...)`（:82）：按 JS_PATTERN 把 ruleUrl 切成片段序列，`<js>...</js>` 取中间体、`@js:` 取 substring(4)，**顺序链式求值**——每段 JS 的 bindings 里 `result=上一段结果`、`@result` 占位符在非 JS 段做字符串替换（:123）；
3. `replaceKeyPageJs(...)`（:131）：
   - **`<1,20,50>` 翻页表**：`pagePattern="<(.*?)>"`，逗号分隔页码→真实偏移映射，`page` 越界取最后一个；
   - **`{{js表达式}}` 内联求值**：`EXP_PATTERN="\{\{([\w\W]*?)\}\}"`，bindings 注入 `java/page/key/speakText/speakSpeed/book/baseUrl`，结果 String 直插、Double 整数格式化 `%.0f`。

`initUrl()`（:183）——**option 解析核心**：ruleUrl 按 `,\s*(?=\{)` 切成 `url` + `UrlOption` JSON：
```kotlin
data class UrlOption(val method: String?, val charset: String?,
                     val webView: Any?, val headers: Any?, val body: Any?)
```
- `method=POST`（忽略大小写才切换，GET 不显式支持其它动词）；
- `headers` 兼容 Map 与 JSON 字符串两种形态；UA 未指定时补 `AppConst.userAgent`（Chrome/81 桌面 UA）；
- `charset` 传递给 EncodeConverter；特殊值 `"escape"` 走 `EncoderUtils.escape`；
- `body`：String 且 `isJson()` → 直接作 JSON RequestBody（jsonType）；否则按 form 解析进 fieldMap；POST 无 body 时构造空 FormBody；
- `webView` 非空 → `useWebView=true`；
- **GET 且非 webView** 时把 `?` 后的 query 拆进 fieldMap（`analyzeFields` :246：按 `&`/`=` 拆，未指定 charset 时已编码的原样、否则 UTF-8 urlencode；LinkedHashMap 保序）；**useWebView=true 时整个 url 原样交给 WebView**。

请求出口四件套：
| 方法 | 返回 | 用途 |
|---|---|---|
| `getResponse(tag): Call<String>` | Retrofit 同步 Call | JsExtensions.ajax / 老代码 |
| `getResponseAwait(tag, jsStr, sourceRegex): Res` | suspend | **WebBook 主通道**；useWebView 时转 `HttpHelper.ajax(AjaxWebView.AjaxParams)`（带 contentRule.webJs 与 sourceRegex 嗅探）；支持 proxy（headerMap 里的 `proxy` 键在 init 时被摘出 ：70-73） |
| `getImageBytes(tag)` / `getResponseBytes(tag)` | ByteArray | 封面/HttpTTS 音频；后者被 HttpReadAloudService 用于合成音频下载 |

所有出口都先 `CookieStore.getCookie(tag)` 注入 `Cookie` 头（tag=书源 URL，按子域查 Room）。

### 1.8 端到端示例：一条搜索规则如何变成章节/书籍列表

以书源 `searchUrl = "https://a.com/search,{"method":"POST","body":"kw={{key}}&p={{page}}","headers":{"Referer":"https://a.com/"}}"`、`ruleSearch.bookList = "class.result"@tag.a` 为例：

```
SearchBookModel.search("书名")
 └▶ WebBook(source).searchBookSuspend()                      [WebBook.kt:33]
     ├▶ AnalyzeUrl(searchUrl, key="书名", page=1, baseUrl=sourceUrl, headerMapF=source.getHeaderMap())
     │    ① analyzeJs: 无 <js>/@js:,跳过
     │    ② {{key}}→书名 {{page}}→1   (replaceKeyPageJs)
     │    ③ initUrl: ",{" 切分 → url=https://a.com/search, method=POST
     │        body 含 {{}} 已替换完 → isJson()? 否 → analyzeFields → fieldMap{kw,p}
     │    ④ getResponseAwait(tag=bookSourceUrl):
     │          CookieStore.getCookie → headerMap["Cookie"]
     │          HttpHelper.getApiService<HttpPostApi>(baseUrl).postMapAsync(url, fieldMap, headerMap)
     │             └▶ Retrofit(baseUrl=new) → OkHttp(client单例) → Response<String>
     │                (EncodeConverter: 去 BOM → charset/头/EncodingDetect 探测解码)
     └▶ BookList.analyzeBookList(scope, body, source, analyzeUrl, res.url, isSearch=true)  [BookList.kt:22]
          ├ AnalyzeRule(SearchBook()).setContent(body, baseUrl)
          ├ bookUrlPattern 匹配? → 是: 直接按详情页解析(getInfoItem) 返回单条
          ├ ruleList 前缀 "-"反转/"+": analyzeRule.getElements(ruleList) → List<Element>
          ├ 预 splitSourceRule(name/bookUrl/author/kind/coverUrl/intro/lastChapter/wordCount 八组子规则)
          ├ for each item: getSearchItem(): setContent(item) → 逐字段 getString → SearchBook
          │    (name 为空则丢弃该书目; bookUrl 相对链接由 getString(isUrl=true) 绝对化)
          └ 返回 ArrayList<SearchBook> → callBack.onSearchSuccess
```

目录与正文同理，差异在 §2.2/§2.3 的翻页编排。

---

## 2. WebBook 流水线 `model/webBook/`

### 2.1 入口类 `WebBook`（`WebBook.kt`，196 行）

`class WebBook(val bookSource: BookSource)`，五个入口全部包装为 `Coroutine.async(scope, Dispatchers.IO)`：

| 方法 | 行 | 规则来源 | 关键参数 |
|---|---|---|---|
| `searchBook(key, page, scope, context): Coroutine<ArrayList<SearchBook>>` | :22 | `searchUrl`+`ruleSearch` | headerMapF=`bookSource.getHeaderMap()`（支持 header 字段本身是 `@js:`，`BookSource.getHeaderMap()` :58） |
| `searchBookSuspend(scope,key,page)` | :33 | 同上 | 供外部协程直连 |
| `exploreBook(url,page,…)` | :62 | `exploreUrl`+`ruleExplore`（explore 规则为空回落 search 规则，见 BookList:57） | |
| `getBookInfo(book,…,canReName): Coroutine<Book>` | :90 | `ruleBookInfo` | `book.infoHtml` 非空则**跳过网络**直接解析（搜索结果直开详情的零请求路径） |
| `getChapterList(book,…)` | :118 | `ruleToc` | `tocUrl==bookUrl && tocHtml` 非空复用详情页 HTML |
| `getContent(book,chapter,nextChapterUrl,…)` / `getContentSuspend` | :143/:160 | `ruleContent` | 正文规则为空 → 直接把 `chapter.url` 当内容返回（Debug 日志 ：167）；传 `webJs`/`sourceRegex` 给 AnalyzeUrl 走 WebView |

### 2.2 目录流水线 `BookChapterList`（267 行，全 object）

```
WebBook.getChapterList
 └▶ analyzeChapterList(coroutineScope, book, body, source, baseUrl)      [:21] suspendCancellableCoroutine
     ├ 第1页: 私有 analyzeChapterList(book, baseUrl, body, tocRule, listRule…) [:202]
     │    ├ nextTocUrl 规则 → getStringList(isUrl=true) → ChapterData.nextUrl[]
     │    ├ getElements(listRule) ("-"/"+" 前缀控制 reverse)
     │    └ 每元素: setContent(item); analyzeRule.chapter=bookChapter(章级变量挂载!)
     │        title=chapterName, url=chapterUrl(isUrl绝对化, 空则回落baseUrl),
     │        tag=updateTime, isVip 规则命中 → 标题加 🔒 (\uD83D\uDD12)
     ├ 按 nextUrl.size 分派:
     │  ├ ==0 → finish()
     │  ├ ==1 → Coroutine.async{ while(nextUrl 非空且未访问过){ 串行抓下一页 } }   [:58]
     │  └ >1  → 为每个 nextUrl 建 ChapterData 占位, 全部 downloadToc() 并发       [:95]
     │            downloadToc: Coroutine.async{ 抓页→解析→synchronized(chapterDataList){
     │              addChapterListIsFinish: 填充自身, 全部就绪才 onFinish() } }    [:141]
     └ finish(book, list, reverse)[:175]:
         reverse? 不反 → list.reverse(); LinkedHashSet<BookChapter> 按 url 去重;
         再 reverse → index 重排; latestChapterTitle/durChapterTitle/totalChapterNum/
         lastCheckCount/latestChapterTime 回写 book
```

要点：**nextUrl 只有 1 条时串行 while（防环靠 nextUrlList 访问集）；≥2 条时并发抓取 + 内存屏障式汇合**（每个任务完成后检查全局完成度，最后完成者负责 resume）。`suspendCancellableCoroutine` 把旧回调风格桥接成协程。

### 2.3 正文流水线 `BookContent`（138 行）

```
ReadBook.download(chapter) / DownloadService / WebBook.getContent
 └▶ BookContent.analyzeContent(scope, body, book, chapter, source, baseUrl, nextChapterUrlF)[:20]
     ├ 私有 analyzeContent(...)[:114]: AnalyzeRule(book).setContent(body);
     │   analyzeRule.chapter=chapter(正文页可 @get 章节变量);
     │   nextContentUrl 规则 → ContentData(content, nextUrl[])
     ├ nextUrl==1 → while 串行翻页; 每页先判
     │   "下一页绝对地址 == 下一章地址(db.bookChapterDao().getChapter(index+1))" 则停  [:51]
     ├ nextUrl>1  → withContext(scope.context) 顺序发起(注:for 循环内 await, 实际仍串行!) [:79-93]
     ├ 拼接 → htmlFormat() → ruleContent.replaceRegex 按 "##" 切 regex/替换 二段应用 [:100]
     └ 返回 String → 调用方 BookHelp.saveContent 落盘
```
⚠️ 注意 `>1` 分支虽然用了 `withContext(coroutineScope.coroutineContext)`，但 for 循环体内没有 launch/async，**实际是顺序执行**——与目录的多页并发不对称（技术债，§9）。

### 2.4 并发控制全景

| 场景 | 机制 | 参数 |
|---|---|---|
| 多源搜索 | `SearchBookModel`（116 行）：`Executors.newFixedThreadPool(AppConfig.threadCount).asCoroutineDispatcher()`；`search(searchId)` 首启起 threadCount 个 worker，每个 worker 完成后在 `onFinally` 里 `synchronized(this){ searchIndex++ }` **自取下一个源**（工作窃取式队列）；单源 `.timeout(30000L)` | threadCount 默认 16（AppConfig.threadCount，PreferKey.threadCount） |
| 完成判定 | `searchIndex >= lastIndex + min(size, threadCount)` 时回调 onSearchFinish（:88-92）——计数跨越逻辑较晦涩 | |
| 离线下载 | `DownloadService`（service 层）同样 fixedThreadPool(threadCount)；`download()` 从 `downloadMap: Map<bookUrl, CopyOnWriteArraySet<BookChapter>>` synchronized 取一个未下载章节；`downloadingList` 防重；已有缓存（`BookHelp.hasContent`）直接计成功不请求；单章 `.timeout(60000L)` | |
| 目录多页 | ≥2 条 nextTocUrl 时并发（§2.2） | |
| 单请求 | OkHttp 默认 Dispatcher（64 并发/5 per host）之上再叠加上述线程池 | |

### 2.5 缓存策略（网络流程相关）

1. **HTML 级**：`SearchBook.infoHtml`、`Book.infoHtml/tocHtml`（Room @Ignore 字段）进程内复用——搜索结果直进详情、详情直出目录时不再发请求。
2. **磁盘级**：正文成功后由调用方 `BookHelp.saveContent()` 写 `book_cache`（§4），`hasContent()` 命中即跳过网络（下载/重入阅读均受益）。
3. **无 LRU/过期机制**：清缓存只有手动 `clearCache()/clearRemovedCache()`（按书架差集删除孤儿文件夹）。

### 2.6 调试链路 `model/Debug.kt`（234 行）

`Debug.startDebug(webBook, key)` 按 key 形态路由：绝对 URL→详情、`xxx::url`→发现、`++url`→目录、`--url`→正文、否则搜索；串起 `searchDebug→infoDebug→tocDebug→contentDebug`，`log()` 以 `[mm:ss.SSS]` 时间戳推给 Callback（书源调试界面）。`debugSource` 单值过滤保证日志只来自被调试源。

---

## 3. 本地书籍 `model/localBook/`

### 3.1 支持格式与入口分发

`LocalBook.kt`（89 行）：
- **仅支持 TXT 与 EPUB 两类**（无 mobi/azw3/pdf 解析器；`book.isEpub()` 按扩展名判断）：
```kotlin
fun getChapterList(book) = if (book.isEpub()) EPUBFile.getChapterList(book)
                            else AnalyzeTxtFile().analyze(book)
fun getContext(book, chapter) = if (book.isEpub()) EPUBFile.getContent(book, chapter)
                                else AnalyzeTxtFile.getContent(book, chapter)
```
- `importFile(path): Book`（:34）：从文件名猜元数据——`作者` 关键词切段、`《》` 提取书名；封面路径预定 `externalFilesDir/covers/md5_16(path).jpg`；立即入库 `App.db.bookDao().insert`。
- `deleteBook(book, deleteOriginal)`：txt 需同时删 `AnalyzeTxtFile.cacheFolder/originName` 缓存副本；`content://` 走 DocumentFile 删除。

### 3.2 TXT：`AnalyzeTxtFile`（302 行）

- 编码探测：`EncodingDetect.getEncode(bookFile)` → `book.charset`；后续 `book.fileCharset()`。
- 目录规则来源：`book.tocUrl` 非空则它就是正则；否则取 DB 启用的 `TxtTocRule`（空则从 assets `txtTocRule.json` 种子导入，`getDefaultEnabledRules()` :289）。
- 分块扫描（`analyze(RandomAccessFile,…)` :39）：512KB buffer（BUFFER_SIZE），块尾按最后一个 `\n` 截齐字节重定位；块内 `Pattern.MULTILINE` 匹配章节标题，维护 `(start,end)` 字节区间生成 `BookChapter`（首块前置文本生成「前言」章）。
- **规则自淘汰**：单章间隔超 50000 字节且整块无匹配（seekPos==0 && length>50000）→ `tocRules.remove(tocRule)` 换下一条规则重来（:89-93/:145-149）——穷举式自适应。
- 无规则兜底：虚拟分章 `MAX_LENGTH_WITH_NO_CHAPTER=10KB`，标题 `第{blockPos}章({chapterPos})`，在 10KB 之后找 `0x0a` 换行收尾。
- 章节定位持久化：`chapter.url = MD5_16(originName + i + title)`（:209），`start/end` 存 DB；读取 `getContent(book, chapter)`（companion :256）`RandomAccessFile.seek(start)` 定长读出按原编码转 String。
- `content://` URI 的书会整本拷到 `cacheFolder = externalFilesDir/bookTxt/`（:266-279 getBookFile）规避 SAF 随机读低效。
- 每 15 块显式 `System.gc()+runFinalization()`（:199）——老派手法。

### 3.3 EPUB：`EPUBFile`（185 行，epublib）

- companion 单例 `eFile` **只缓存一本**（bookUrl 变了才重建），所有静态入口 `@Synchronized`——同进程串化解析。
- init（:57）：`EpubReader.readEpub(inputStream)`（content:// 走 ContentResolver）；无封面时导出 `covers/md5_16(bookUrl).jpg`（JPEG 90）。
- `getChapterList()`（:105）：metadata 取书名/作者/简介；TOC 引用树存在 → `parseMenu` 递归平铺（children 也展开，层级压平后重排 index）；TOC 空 → 遍历 spine，resource.title 缺失时解析该 xhtml 的 `<title>`，首个空标题命名「封面」。
- `getContent`：按 href 取 resource data → Jsoup 解析 body children → 删 script/style → `outerHtml().htmlFormat()` 富文本入库。
- `getImage(href)`：剥 `../` 后从 resources 取流（正文图片由阅读器经此渲染）。

---

## 4. help/storage 与目录规范（无 Directory 类，规范即约定）

存储根：
```kotlin
// utils/ContextExtensions.kt:170
val Context.externalFilesDir: File
    get() = App.INSTANCE.getExternalFilesDir(null) ?: App.INSTANCE.filesDir   // SD不可用时回落内部
```

实际布局（`Android/data/io.legado.app/files/` 为主）：

| 路径 | 内容 | 代码出处 |
|---|---|---|
| `…/files/book_cache/<书名+md5_16(bookUrl)>/` | 正文缓存，章节文件 `%05d-index-md5_16(title).nb` | `BookHelp.formatChapterName` :27 |
| `…/files/book_cache/<书>/images/` | 章节插图 `md5_16(src)+后缀`（后缀>5字符按 .jpg） | `BookHelp.saveImage/getImage` :87-108 |
| `…/files/bookTxt/` | content:// 导入 TXT 的整书副本 | `AnalyzeTxtFile.cacheFolder` :249 |
| `…/files/covers/` | 本地书/EPUB 封面 jpg | LocalBook.importFile :63、EPUBFile.init :68 |
| `filesDir/backup/` | 备份中间目录：bookshelf/bookmark/bookGroup/bookSource/rssSource/rssStar/replaceRule/txtTocRule/readRecord/httpTTS `*.json` + `readConfig.json` + `config.xml` | `Backup.backupFileNames` :29 |
| `filesDir/readConfig.json` | 阅读排版/主题配置（正式存放处） | ReadBookConfig.configFilePath :22 |
| `filesDir/restoreIgnore.json` | 恢复时用户选择跳过的配置键 | Restore :30 |
| `externalCacheDir/httpTTS/{i}.mp3` | HttpTTS 合成音频分段 | HttpReadAloudService :33 |
| `cacheDir/bookProgress.json` | WebDav 进度同步暂存 | SyncBookProgress :17 |
| `cacheDir/ACache/`（默认） | ACache 文件 KV 缓存（UI 层杂项：书源列表快照、发现页缓存等） | utils/ACache.kt |
| `cacheDir/crash/`(行为见 CrashHandler) | 崩溃信息文件 | help/CrashHandler.kt |

备份/恢复/迁移三件套：
- `Backup`（140 行）：每日自动（`autoBack` 按 PreferKey.lastBackup 判 24h）；各 DAO 全量→JSON 写 `filesDir/backup/`；`config.xml` 通过 **反射篡改 ContextImpl.mPreferencesDir**（`Preferences.getSharedPreferences`，storage/Preferences.kt）导出默认SharedPreferences；再复制到用户选的 SAF 目录（auto 备份放 `auto/` 子目录）或 WebDav（`WebDavHelp.backUpWebDav`）。
- `Restore`（255 行）：restoreDatabase（逐 JSON 反序列化 upsert）+ restoreConfig（readConfig.json/config.xml，配合 restoreIgnore 忽略清单）。
- `OldRule`（281 行）/`OldBook`/`OldReplace`/`ImportOldData`（107 行）：**阅读 2.0 → 3.0 迁移**——`OldRule.toNewUrl` 把老搜索 URL 模板翻译成新 option 格式（`@Header:{...}`→header JSON 等），`ImportOldData` 从 legacy 目录读 myBookshelf/myBookSource 等。
- `SyncBookProgress`（49 行）：`bookDao.allBookProgress` → `bookProgress.json` → WebDav 上传/下载回写（`upBookProgress` 仅同步四元进度字段）。

---

## 5. 网络栈 `help/http/`

### 5.1 OkHttp/Retrofit 封装层次（HttpHelper.kt，194 行）

唯一 OkHttpClient 单例（:17-39）：
```kotlin
connectTimeout/writeTimeout/readTimeout = 15s
.sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, unsafeTrustManager)  // 信任一切证书
.hostnameVerifier(SSLHelper.unsafeHostnameVerifier)                      // 恒true
.connectionSpecs(MODERN_TLS, COMPATIBLE_TLS, CLEARTEXT)                  // 允许明文http
.protocols(HTTP_1_1)                                                     // 禁h2
.retryOnConnectionFailure(true); followRedirects(true/ssl true)
.addInterceptor(getHeaderInterceptor())  // Keep-Alive:300 / Connection:Keep-Alive / Cache-Control:no-cache
```
之上按 baseUrl 动态建 Retrofit（**每次调用 new 一个 Retrofit 实例**）：
- `getRetrofit(baseUrl, encode)` + `EncodeConverter(encode)`：响应解码链 = 指定 charset → HTTP Content-Type charset → `EncodingDetect.getHtmlEncode(bytes)` 内容嗅探；解码前 `UTF8BOMFighter.removeUTF8BOM`。
- `getBytesRetrofit` + `ByteConverter`：图片/音频字节流。
- `getApiServiceWithProxy(baseUrl, encode, proxy)`：`client.newBuilder()` 加代理；代理串正则 `(http|socks4|socks5)://(.*):(\\d{2,5})(@.*@.*)?`，socks4/5 归并为 SOCKS 类型，支持 `@user@pass` 的 Basic `Proxy-Authorization`。
- API 面：`api/HttpGetApi.kt`（get/getMap/getByte/getMapByte × sync Call 与 suspend 双版本）、`api/HttpPostApi.kt`（postMap=@FieldUrlEncoded、postBody=@Body RequestBody × 同上）。`@QueryMap(encoded=true)/@FieldMap(encoded=true)`——因为 AnalyzeUrl 已经自己做过 URL 编码，禁止 Retrofit 二次编码。

并发/DNS：无自定义 DNS、无缓存、无速率限制；并发完全交给协程线程池 × OkHttp 默认 Dispatcher。

### 5.2 Cookie：CookieStore（112 行）

双身份：
1. 业务侧：按 `NetworkUtils.getSubDomain(url)` 存 `Cookie` 实体进 Room（setCookie 异步、replaceCookie 做 map 级合并、removeCookie）；
2. 框架侧：实现 `PersistentCookieJar` 的 `CookiePersistor` 接口（loadAll/saveAll/removeAll/clear，SerializableCookie 编解码），可挂给 okhttp3 persistent-cookiejar。

### 5.3 WebView 动态渲染：AjaxWebView（230 行）

- `AjaxParams{url, tag, requestMethod, postData, headerMap, sourceRegex, javaScript}`；
- 主 Handler（Main Looper）四种消息：AJAX_START/SNIFF_START/SUCCESS/ERROR；WebView 设置：JS 开、DOM storage 开、**blockNetworkImage=true**（省流量）、MIXED_CONTENT_ALWAYS_ALLOW、UA 取 headerMap。
- 普通模式 `HtmlWebViewClient`：onPageFinished 后延时 1s `evaluateJavascript(params.getJs() ?: "document.documentElement.outerHTML")`，结果非空即成功，**最多重试 30 次**（EvalJsRunnable，:149-178）——等待 SPA 异步渲染。
- 嗅探模式 `SnifferWebClient`：`onLoadResource` 中资源 URL 匹配 `sourceRegex` 即回调（用于抓 m3u8/音频真实地址），页面加载后还可执行 `webJs`。
- 结束时 `params.setCookie(url)` 把 WebView CookieManager 里的 cookie 回写 CookieStore。
- `HttpHelper.ajax(params)`（:174）用 `suspendCancellableCoroutine` 包装，取消时销毁 WebView。

### 5.4 CORS 处理

两条战线：
1. **出站（爬虫侧）**：App 不是浏览器，无 CORS 概念；真正的“跨域”发生在书源 JS 里，通过 `JsExtensions.ajax()` 原生桥绕过（AnalyzeRule.kt:608 注释“js实现跨域访问，不能删”）。
2. **入站（内置 Web 服务）**：`web/HttpServer.kt`（NanoHTTPD）对 `OPTIONS` 预检返回 `Access-Control-Allow-Methods: POST`、`Allow-Headers: content-type`、**`Allow-Origin: <请求方 origin 原样反射>`**（:25）；GET/POST 响应同样附 `Methods: GET, POST` + 反射 origin（:65-66）——允许局域网 Web 端（web/index）跨源访问手机 API，等于全开放 CORS（内网服务，风险可控但值得记录）。

---

## 6. 朗读引擎（service/ 层，抽象位于 BaseReadAloudService）

### 6.1 双引擎选择与门面

- `service/help/ReadAloud.kt`：`getReadAloudClass()` 按 PreferKey.speakEngine 查 `httpTTSDao`——**配置了 HttpTTS → `HttpReadAloudService`，否则系统 `TTSReadAloudService`**；`play/pause/resume/stop/prevParagraph/nextParagraph/upTtsSpeechRate/setTimer` 全部以 Intent(action=IntentAction.*) startService 转发。
- 抽象基类 `BaseReadAloudService`（:30）：`newReadAloud/play/pauseReadAloud/resumeReadAloud` open，`upSpeechRate/prevP/nextP/aloudServicePendingIntent` abstract；公共设施：音频焦点(`requestFocus`/MediaHelp.getFocusRequest)、`MediaSessionCompat`（MEDIA_SESSION_ACTIONS 全家桶，MediaHelp.kt:14）、前台通知（upNotification）、定时停止(setTimer/addTimer/doDs)、耳机拔出广播。

### 6.2 系统 TTS：`TTSReadAloudService`（190 行）

`TextToSpeech.OnInitListener`；`play()` 将 `contentList` 当前段 `speak(text, QUEUE_ADD, utteranceId)`；`TTSUtteranceListener.onRangeStart`（:173）把字级区间发 EventBus 驱动高亮；语速 `setSpeechRate((rate+13)/15f)` 映射。

### 6.3 HttpTTS：`HttpReadAloudService`（205 行）

```
play() → nowSpeak==0 ? downloadAudio() : playAudio(getSpeakFile(nowSpeak))
downloadAudio(): 删旧 ttsFolder → for 每段:
    AnalyzeUrl(httpTTS.url, speakText=contentList[i], speakSpeed=AppConfig.ttsSpeechRate)
        .getResponseBytes() → 写 externalCacheDir/httpTTS/{i}.mp3
    若 i==nowSpeak 立即 playAudio(FileInputStream.fd)   // 边下边播
playAudio: MediaPlayer.reset→setDataSource(fd)→prepareAsync; playingIndex!=nowSpeak 防重
onCompletion → nextP()/换章; onError 兜底跳段
```
即 **HttpTTS 的“规则”就是一个 AnalyzeUrl 模板 URL**（`{{speakText}}/{{speakSpeed}}` 占位），合成结果当作 mp3 字节落盘顺序播放。`AudioPlayService`/`service/help/AudioPlay.kt` 是另一条独立的有声书播放线（MediaPlayer + bookChapter.resourceUrl，headerMap 由 AudioPlay.headers() 提供）。

---

## 7. 缓存体系

本 fork **没有** 上游 CacheManager/DiskLruCache/cachedac，实际三层：

1. **正文/图片文件缓存**（`help/BookHelp.kt`，320 行）：
   - `saveContent(book, chapter, content)`：写 `.nb` 文件 + 正则 `AppPattern.imgPattern` 抽 `<img src>` 逐张 `saveImage`（`AnalyzeUrl.getImageBytes`，cookie 带 book.origin）+ `postEvent(SAVE_CONTENT)`；
   - `getContent/hasContent/delContent/getChapterFiles/clearCache(book)/clearRemovedCache()`（对照书架差集清理）；
   - `disposeContent(...)`（:258）：**净文处理管道**——ReplaceRule（DB 按 name+origin scope 查询，isRegex 分支，出错 toast 继续）→ HanLP 简繁转换（chineseConverterType 1/2）→ 逐行去首空白/\r、加 `ReadBookConfig.bodyIndent` 缩进、插标题行 → `List<String>` 给排版层。
   - 其他工具：`formatBookName/formatBookAuthor`（正则去“作者：”尾巴）、`getDurChapterIndexByChapterTitle`（Jaccard 相似度 ±50 章窗口找换源后的当前章，:190）。
2. **ACache**（utils/ACache.kt，自研）：目录型 KV（string/json/bitmap/drawable，带 TTL），默认 `cacheDir/ACache`，上限 50MB/不限条数，实例按目录+pid 缓存于 `mInstanceMap`。用途散在 UI（BookSourceActivity、ExploreAdapter、TocRegexDialog 等）。
3. **Room 数据库**（App.db）：书源/Cookie/替换规则/TxtTocRule/HttpTTS/阅读记录等结构化缓存，CookieStore 与 SourceHelp 都直接读写。

---

## 8. 主题系统

三层结构（替代上游 model/themes）：

1. **模式枚举** `constant/Theme.kt`：`Dark/Light/Auto/Transparent`；`getTheme()` 依 `AppConfig.isNightTheme`。
2. **日夜判定** `help/AppConfig.isNightTheme`（:14）：themeMode `"0"跟随系统 /"1"亮/"2"暗/"3"E-Ink`；配套 `isTransparentStatusBar/isEInkMode/elevation` 等。
3. **View 染色工具箱** `lib/theme/`：
   - `ATH.kt`（250+ 行）：statusBar/navigationBar/taskDescription 自动配色、LightStatusBar 系统旗标、RecyclerView/ViewPager(**反射取内部 EdgeEffect**)/ScrollView 水波纹染色、AlertDialog tint、BottomNavigation 应用；
   - `ThemeStore(.kt/.Interface/.PrefKeys)`：主题色持久化（primary/accent/background 等键值）；
   - `ATHUtils.resolveColor(context, attr)` 从主题 attr 解析。
4. **阅读区专属** `help/ReadBookConfig.kt`（409 行）：`readConfig.json`（assets 种子 + filesDir 覆盖）持有 `ArrayList<Config>`（每套=字体/字号/粗体/字距/行距/段距/标题样式/内边距/背景/文字颜色/亮度遮罩…）；`styleSelect` 当前方案、`shareLayout` 横竖屏共用、`bg/bgMeanColor` 背景Drawable 与均值色（`upBg()` 按屏宽解码）、`durConfig.textColor()` 决定正文颜色。UI 编辑入口 `ui/config/ThemeConfigFragment`。备份清单里的 `readConfig.json` 即此文件。

---

## 9. 其余 help/ 子系统速览

- **coroutine/**：`Coroutine<T>`（214 行）——`async(scope, Dispatchers.IO, block)` 起 Job（宿主 MainScope+Dispatchers.Main 外壳），链式 `timeout(ms)/onStart/onSuccess(ctx)/onError(ctx)/onFinally/onCancel/onErrorReturn`，错误吞与不吞由 errorReturn 决定；回调可用 withContext 指定上下文。`CompositeCoroutine`（HashSet + synchronized）批量 cancel（remove 即 cancel，clear 全取消）；`CoroutineContainer` 接口。这是全书所有异步的基座（WebBook/Debug/Download/备份…）。
- **permission/**：`Permissions/PermissionsCompat/Request(RequestManager/RequestPlugins)/PermissionActivity`——向 Activity/Fragment **反射注册 OnRequestPermissionsResultCallback**（ActivitySource/FragmentSource :20），链式 addPermissions/setRationale/onGranted/onDenied/start；拒绝后跳 PermissionActivity 引导。
- **CrashHandler**（156 行）：`Thread.UncaughtExceptionHandler`，collectDeviceInfo + 自定义 info + `saveCrashInfo2File(ex)` 落盘后交还默认 handler。
- **其他工具**：`ItemTouchCallback/LayoutManager`（拖拽排序与 RV 布局）、`ImageLoader`（Glide 薄封装）、`BlurTransformation`、`LauncherIconHelp`（换图标 activity-alias）、`IntentHelp/IntentDataHelp`（跨进程 URI 与 **内存静态 Map 传大对象**，如朗读的 TextChapter）、`EventMessage`（事件常量）、`ActivityHelp`、`DefaultValueHelp`、`AdapterDataObserverHeader`。
- **rss/（model 下）**：`Rss.getArticles/getContent`（56 行）→ `RssParserByRule.parseXML`（116 行，规则驱动：ruleArticles 列表 + `-`反转 + ruleNextPage 特殊值 "PAGE"、title/pubDate/description/image/link 五字段 getItem）或规则为空时 `RssParser.parseXML`（144 行，标准 RSS/Atom XML 解析）；`Result(articles, nextPageUrl)`。

---

## 10. 技术债与坑（按严重度）

**正确性 Bug**
1. `AnalyzeUrl.getImageBytes`（:395）：`headerMap["Cookie"] += cookie`——map 无该键时是 `null + string`，产生字面量 `"nullxxx"` Cookie 头（同文件其他出口都是 `=` 赋值）。
2. `AjaxWebView.destroyWebView()`（:92-94）：`mHandler.obtainMessage(DESTROY_WEB_VIEW)` **忘了 `.sendToTarget()`**，销毁消息永不投递（实际靠 handler 成功/失败分支里 destroyWebView 兜底）。
3. `BookContent.analyzeContent` nextUrl>1 分支（:78-93）：for 循环内顺序 await，名为并发实为串行，长章多页耗时线性叠加。
4. `AnalyzeByXPath.getString` 不支持 `%%`，与其余后端不一致；文档缺失时书源作者极易踩坑。
5. `AnalyzeByJSonPath.getObject` 用 `ctx!!`，未 parse/parse 失败时直接 NPE（其余方法都有空保护）。

**健壮性/性能**
6. `HttpHelper.getRetrofit/getApiService` 每次请求 new Retrofit + Proxy.newProxyInstance——高频搜索（16 线程×几十源）下有明显反射开销，且没有任何 Retrofit 实例缓存。
7. SSL 信任一切证书 + hostnameVerifier 恒 true（SSLHelper:22-53）：中间人风险，书源生态的历史妥协；强制 HTTP/1.1 放弃 h2 多路复用。
8. `AnalyzeRule.splitSourceRule` 每条规则求值都重新字符串切分/正则编译（无规则缓存，上游 RuleAnalyzer 的增量优化在本 fork 缺失）；`SourceRule.makeUpRule` 每次重建字符串。
9. `EPUBFile` companion 单例只容一书 + 全方法 `@Synchronized`：批量扫描 EPUB 书库时完全串行，且切书反复 readEpub 整本解析。
10. `AnalyzeTxtFile`：块内 `\n` 截断需 `toByteArray` 重算长度（O(块大小)）；显式 `System.gc()`；魔法数字 50000/512KB/10KB 无解释。
11. `Preferences.getSharedPreferences` 反射改 `ContextImpl.mPreferencesDir`——hidden API 限制（Android 9+ greylist）随时可能失效，进而破坏 config.xml 备份。
12. `SearchBookModel` 完成判定 `searchIndex >= lastIndex + min(size, threadCount)`（:88）与自增时序强耦合，线程池大小变更时边界脆弱；`synchronized(this)` 锁散布。
13. `BookChapterList.finish()` 的 reverse→LinkedHashSet 去重→reverse 组合依赖 `BookChapter.equals(url)`，语义正确但难读；去重静默丢章无日志。
14. `CookieStore.setCookie` 裸 `Coroutine.async{}` 无 Composite 管理，生命周期失控；`JsExtensions.ajax` 在 Rhino 线程同步 execute，恶意/慢速书源可长时间占住规则线程。
15. `BookHelp.upReplaceRules/disposeContent` 双重检查锁写在 `synchronized(this)`（object 单例）上，replaceRules 缓存与 `bookName/bookOrigin` 弱一致。

**结构性/迁移性**
16. 上游组件大面积缺失（Directory/CacheManager/CleanUp/WebOkHttp/BookUpdate/UserSourceConfig），目录规范退化为散落常量（"book_cache"/"images"/"bookTxt"/"covers" 字符串分布在 4 个类里），新增存储点容易漂移。
17. `Debug` 全局单例 callback/debugSource，多处 WebBook 流程内嵌 `Debug.log` 字符串拼接成本（即使不打印也先拼参，Kotlin 非内联 lambda 默认参数）。
18. `LocalBook.getContext` 方法名拼写（应为 getContent 语义），`AnalyzeRule.getString0` 命名同样晦涩。
19. `AnalyzeUrl` 的 `splitUrlRegex = ",\s*(?=\{)"` 要求 option 必须紧跟 `{`，URL 自身含 `,{"` 串的场景会被误切（书源编写常见坑）。
20. Web 服务器 CORS 全反射 origin + 无鉴权（web/HttpServer），局域网内任意页面可调用 `/getBookContent` 等接口读取书架与正文。

---

## 附：关键调用链总图

```
【搜索】SearchBookModel.search(id,key)
   └▶ WebBook.searchBook ─▶ Coroutine.async(IO池) ─▶ searchBookSuspend
        └▶ AnalyzeUrl(searchUrl,key,page) ─▶ getResponseAwait ─▶ HttpHelper(Retrofit→OkHttp)
                                                          ⇐ Res(body,url)
        └▶ BookList.analyzeBookList ─▶ AnalyzeRule.getElements/getSearchItem ⇒ ArrayList<SearchBook>

【详情】UI ─▶ WebBook.getBookInfo ─▶ BookInfo.analyzeBookInfo ⇒ Book(tocUrl/infoHtml)

【目录】ReadBook.resetData/loadChapterList ─▶ WebBook.getChapterList
   └▶ BookChapterList.analyzeChapterList ─┬(单链) Coroutine.async{while 抓 nextTocUrl}
                                          └(多链) downloadToc×N ─synchronized→ finish() ⇒ List<BookChapter>

【正文】ReadBook.download(chapter) / DownloadService
   └▶ WebBook.getContentSuspend ─▶ BookContent.analyzeContent
        └▶ 私有analyzeContent×N页(while/伪并发) ─▶ replaceRegex ⇒ String
             └▶ BookHelp.disposeContent(替换规则/HanLP/缩进) ─▶ BookHelp.saveContent(book_cache/*.nb)

【朗读】ReadBook.play ─▶ (HttpTTS? HttpReadAloudService: AnaylzeUrl(speakText).getResponseBytes→mp3→MediaPlayer
                          : TTSReadAloudService: TextToSpeech.speak + onRangeStart 高亮)
```

（完）
