# 无课教室（v1.1.13 / versionCode 32）代码审查 + 视觉与交互设计审查

> 审查对象：`feat/empty-classroom` 分支最近 5 个提交
> `06f4e9b`（签名与配置解密）→ `6eaea0b`（模型/API/key 三层兜底）→ `b65069b`（Repository/自然排序/偏好）→ `6ba448b`（页面 UI 与教务 Tab 三段化）→ `fb875c9`（versionCode 32）
> 证据：源码通读 + 2 张真机截图（21:48 / 21:49）+ 本地实跑 `testDebugUnitTest`（24 套 / 220 例全绿）与 `lintDebug`（0 error / 32 warning，**新增文件 0 warning**）+ 两轮独立复核（一轮只读数据层 7 个文件 → 检出高危漏报 C1；一轮只读 UI 层 5 个文件 → 独立复现 C2/C3 并新增 C13/C14/D24/D25）
> 局限：无法在真机上抓包与点击验证，凡涉及"运行时行为"的判断均已在文中标注置信度与验证方法。

---

## 1. 总体结论

**工程质量高于项目平均水平。** 这一版把"逆向第三方平台接口"这种天然脆弱的集成做成了可维护的形态：签名算法有实测依据并写了单测锁住参数、key 三层兜底、解析层统一 `optX` 容错、`noSeatRate` 的 `null` 与 `0` 严格区分、教室名自然排序单独抽成可测对象。41 个新单测是真数据 fixture 驱动的，不是凑数的。

**问题集中在三处：**

1. **一行代码的高危定时炸弹**（`C1`）。`csrkKey` 一旦被服务端轮换，`loadMeta` 里第一个请求就会失败且**永不重试**——自愈代码根本不可达，无课教室对**所有老用户永久失效**，只能清应用数据恢复；而新装用户完全正常，所以开发机上不会复现。提交注释写着"每个接口都做一次重试"，代码只对其中一个做了。
2. **新功能与既有"教务 Tab / 成绩抓取 WebView"的编排没有收口**（`C2`、`C3`），产生真实的导航死路：分段控件在三个分支中有两个不渲染，WebView 一出现就无法切段、也没有取消入口——而且正好撞在"默认打开无课教室"这个产品决定上。
3. **服务器时钟校正是死代码**（`C4`）。写它的人知道"必须用服务器时间"，代码却永远不会真的去校正；这条会以"签名密钥可能已更新"的假故障暴露，把用户和开发者同时带偏。

（另有一处与本次改动无关、但同屏可见的隐私漏网：`C13` 成绩列表行的排名/学分仍未随隐私开关掩码。）

设计层面，页面本身不丑、布局克制，但**它没有回答用户打开它时真正要问的问题**："现在（或下一节）我能去哪间教室"。当前实现是"今天全部 6 个大节的折叠清单"，缺少三样东西：**时间基准（今天/现在/数据新鲜度）**、**决策支持（按座位数/空座率排序筛选）**、**已结束时段的表达**。截图 2 拍于 21:48，页面正把当天 07:58 的第一大节当作最相关结果展开——这一幕就是设计问题的最好证据。

### 修复优先级

| 档 | 内容 | 预估 |
|---|---|---|
| **P0 必须修** | C1 轮换后永久失效 · C2 WebView 锁死导航 · C3 顶栏刷新空操作 · C4 时钟校正死代码 · C5 异常被当成"没有空教室" · C6 切楼栋竞态与空态闪屏 | 0.5～1 天 |
| **P1 应当修** | C7～C14（空态出口、DataStore 兜底、列表懒加载、错误分类、状态重置、首帧分段、隐私掩码漏网、展开态丢失） | 0.5～1 天 |
| **P2 体验升级** | 时间基准条 + 排序/筛选 + 视觉 token 统一 + 无障碍 + 文案 | 1～2 天 |

---

## 2. 🔴 必须修

### C1. 服务端轮换 `csrkKey` 后，无课教室会**永久失效**（自愈代码不可达）

**位置**：`data/repo/FreeRoomRepository.kt:12-18`（注释）/ `29-37`（`loadMeta`，**唯一没走重试的接口**）/ `57-67`（`loadFreeRooms`，走了重试）

```kotlin
 * 此时唯一正确的做法是**丢弃缓存的 key 并重取一次**……所以这里每个接口都做一次重试。  // ← 注释
suspend fun loadMeta(): FreeRoomResult {
    keyProvider.syncClockOrSkip()
    val signedReq = signed()
    val buildings = api.listBuildings(signedReq).getOrElse { throw it }   // ← 没有 withSignedRetry
    ...
}
```

`invalidate()` 只被 `withSignedRetry` 调用（`FreeRoomRepository.kt:90`），而 `withSignedRetry` 只被 `loadFreeRooms` 使用。于是：

1. 平台轮换 `csrkKey`（代码自己在 `SmartClassKeyProvider.kt:17-25` 承认这是会发生的事）；
2. 老用户本地缓存着**旧 key**（`csrkKey()` 在第 47-49 行命中缓存就直接返回，不会去问服务端）；
3. 流程的第一个请求 `listBuildings` 被服务端以 `csrf key validate error` 拒绝；
4. `loadMeta` 直接 `throw`，页面报错；**用户点「重试」→ 还是读同一份缓存 → 还是失败**；
5. `loadFreeRooms` 里那段精心写的"丢弃缓存重取一次"永远跑不到（它要求 `loadMeta` 先成功）。

**结果**：从平台轮换 key 的那一刻起，**所有已安装用户的无课教室功能永久不可用**，只有清应用数据/重装（缓存被清空 → 首次运行必然重新拉 `/config.json`）才能恢复。而新装的用户一切正常——所以这个 bug 在开发者自己的新装机上不会复现，是典型的"上线一段时间后突然全员报错"。

**最小修复**（一行）：

```kotlin
val buildings = withSignedRetry { signed -> api.listBuildings(signed) }
// listNodeTypes 保持宽松即可：它不是决定性请求
```

**顺带**：`SmartClassKeyProvider.csrkKey()` 的缓存策略也建议加一条"缓存值来自 `BUILT_IN_CSRK_KEY` 时优先重取"或 TTL，否则内置兜底值一旦被写进缓存（服务端不可用时的降级路径），下次启动会优先用这个必然过期的值，把上述死锁的概率放大。

---

### C2. 成绩/考试段的 WebView 会锁死导航，新用户可能永远进不去无课教室

**位置**：`ui/grades/GradesScreen.kt:152-161`（`gradeSectionActive` 分支）、`ui/grades/GradesScreen.kt:188-228`（分段控件只在 `else` 分支里）；`ui/grades/GradesViewModel.kt:285-293`、`360-363`

```kotlin
val gradeSectionActive = state.section != GradesSection.FREE_ROOM
if (state.showWebView && gradeSectionActive) {
    WebViewFetch(...)                     // ← 这里没有分段控件
} else if (gradeSectionActive && state.grades.isEmpty() && state.exams.isEmpty()) {
    ...
} else {
    Column { SingleChoiceSegmentedButtonRow { ... }   // ← 分段控件只在这一支
             when (state.section) { ... } }
}
```

**故障链**（全部可由代码推演，无需真机）：

1. 用户装好 App，进「教务」→ 默认段 `FREE_ROOM`，正常。
2. 点「成绩」→ `GradesViewModel.init` 在"无成绩且从未抓取"时已把 `showWebView = true`（`GradesViewModel.kt:289-291`），此时分支命中第一支：**整个页面只有登录 WebView，三个分段按钮消失**。
3. 用户此刻不想登录（或登录页加载慢、统一认证在维护），想回到无课教室——**没有任何入口**：页面内无取消按钮、无 BackHandler、分段控件不在组合里；只能杀掉 App。
4. 更糟的是 `setSection` 已经把 `section = SCORES` 持久化（`GradesViewModel.kt:370-372` → `SettingsStore.setGradesTabIndex`）。**重启后仍然落在成绩段 → `init` 再次置起 WebView → 又进登录页。** 无课教室在这个状态下等于被永久锁住，除非用户完成登录或抓取报错（只有 `onFetchResult` / `onFetchError` 会把 `showWebView` 复位）。

**同类路径**：截图 2 的「重新抓取」也会进入这个状态（见 C3）。

**补充（第二双眼睛复核确认，比上面更严重）**：分段控件存在于**三个分支中的两个之外**——它在"抓取 WebView 分支"和"还没有成绩数据分支"（`GradesScreen.kt:162-187`）里都没有。也就是说：

> 「从未成功抓取过 + 当前停在成绩段」的用户（例如某次抓取失败后：`GradesViewModel.kt:317-318`/`347-350` 会把 `showWebView` 复位并停在成绩段），看到的是"还没有成绩数据 / 去获取"——**此时无课教室与考试两段都切不过去**，只有点「去获取」并抓取成功才能重新拿到分段控件。

而 `GradesScreen` 内没有 `BackHandler`（全项目只有 `ImportScreen.kt:65` 有），系统返回键会直接退出 App；底部 Tab 切走再回来，因为 ViewModel 是 Activity 作用域，状态原样恢复。所以这是一条真正意义上的"锁死"，而不是"多点两下就能绕开"。

**最小修复**：把分段控件提到分支外层，让 WebView 只占据"成绩/考试"的内容区；并给抓取过程一个显式出口。

```kotlin
Column(Modifier.fillMaxSize()) {
    SectionTabs(state.section, viewModel::setSection)        // 永远可见
    when (state.section) {
        FREE_ROOM -> FreeRoomScreen()
        SCORES -> if (state.showWebView) GradeFetchPane(cancel = viewModel::cancelFetch)
                  else if (state.grades.isEmpty()) NoGradesYet(...) else GradesContent(...)
        EXAMS  -> if (state.showWebView) GradeFetchPane(cancel = viewModel::cancelFetch)
                  else ExamListContent(state.examsSorted)
    }
}
```

配套：`GradesViewModel` 增加 `fun cancelFetch() { showWebView.value = false; fetching.value = false }`，`WebViewFetch` 顶部加一行「取消」TextButton（或放在提示条右侧），让"进错了/不想登录"有退路。

---

### C3. 顶栏「重新抓取」在无课教室段是一次静默空操作

**位置**：`GradesScreen.kt:125-129`（刷新按钮只看 `showWebView`，不看 `section`）、`GradesScreen.kt:135-148`（确认对话框）、`GradesViewModel.kt:360-363`

**现象**（截图 2 正是这一幕：对话框盖在无课教室列表上）：

1. 用户在无课教室页点右上角刷新 → 弹出「重新抓取：将进入教务系统重新抓取成绩、GPA、考试安排与学业进度…」。**这个动作与当前页面毫无关系**，文案里的四样东西没有一样出现在屏幕上。
2. 点「继续」→ `startRefresh()` 只把 `showWebView = true`，而 `section` 仍是 `FREE_ROOM` → `gradeSectionActive == false` → WebView 不组合、抓取**根本不会开始**。
3. 用户看到的唯一变化是：**右上角刷新图标消失了**（`if (!state.showWebView)`），其余毫无反应。
4. 之后某一次切到「成绩」，登录页会毫无预兆地弹出来——因为那次被"寄存"的抓取现在才真正执行。

**最小修复**（二选一，建议都做）：

- 刷新按钮只在 `state.section != FREE_ROOM` 时显示（无课教室的刷新是下拉手势，语义已经存在）；
- `startRefresh()` 内同时 `setSection(GradesSection.SCORES)`，保证"确认后跳去抓取页"这一预期一定成立。

---

### C4. 服务器时钟校正是死代码 → 假故障「签名密钥可能已更新」

**位置**：`data/remote/SmartClassKeyProvider.kt:71-75` ↔ `data/remote/SmartClassApi.kt:45-49`

```kotlin
// KeyProvider
suspend fun syncClock() {
    api.serverTimeMillis()?.let { clockOffsetMs = server - System.currentTimeMillis() }  // ← 永远拿到 null
}
// Api
suspend fun serverTimeMillis(signed: SignedRequest? = null): Long? {
    if (signed == null) return null        // ← 无参调用直接返回 null
    ...
}
```

`syncClock()` 用**无参**方式调用 `serverTimeMillis()`，而该函数在 `signed == null` 时立刻返回 `null`。因此 `clockOffsetMs` 恒为 0，`signingTimeMillis()` 实际只是"本机时间 + 2 分钟"。`FreeRoomRepository.loadMeta()` 里那句 `keyProvider.syncClockOrSkip()`（`FreeRoomRepository.kt:30`）从未生效。

**用户可见后果**：

- 设备时钟比服务器**快** 3 分钟以上（手动关掉自动对时、部分 ROM 时间漂移）→ 所有请求 `csrf key validate error`；
- `withSignedRetry` 判定为"key 过期"→ `invalidate()` **丢弃一份本来好好的 key 缓存** → 重新拉 `/config.json`（拿回同一个 key）→ 再失败 → 用户看到 `服务暂时不可用：签名密钥可能已更新，请稍后重试或检查 App 更新`；
- 也就是：**一个几行代码就能修好的时钟问题，被包装成了"服务端换密钥"的假故障**，还会顺带破坏 key 缓存。

**最小修复（推荐方案）**：用 **`/config.json` 的 HTTP `Date` 响应头**做时钟基准。该接口本来就不需要签名（`SmartClassApi.kt:30` 的实测结论），因此不存在"要签名才能校时、要校时才能签名"的鸡生蛋问题，也不依赖 `GettimeDif` 的返回格式：

```kotlin
// SmartClassApi：把 Date 头暴露出来（1 秒粒度，足够覆盖 5 分钟窗口）
suspend fun serverDateMillis(): Long?   // 读 conn.getHeaderFieldDate("Date", 0L)
// SmartClassKeyProvider
suspend fun syncClock() {
    api.serverDateMillis()?.let { clockOffsetMs = it - System.currentTimeMillis() }
}
```

**备选方案**：先取 key（缓存或内置兜底即可）再签一个请求去调 `/Home/GettimeDif`：

```kotlin
suspend fun serverTimeMillis(signed: SignedRequest): Long? { ... }   // 签名参数改必需
suspend fun syncClock() {
    val signed = SmartClassApi.SignedRequest(csrkKey(), signingTimeMillis())
    api.serverTimeMillis(signed)?.let { clockOffsetMs = it - System.currentTimeMillis() }
}
```

**无论走哪条路都必须确认响应格式**：接口名 `GettimeDif` 的语义容易让人以为是"时间差"，而现有代码按"13 位绝对毫秒时间戳"解析（`takeIf { it > 1_000_000_000_000L }`）。

> **已核实（2026-09，站点自身前端源码）**：该接口返回的确实是**绝对毫秒时间戳**，现有校验是对的。`Classroom.aspx` 的内联脚本：
> ```js
> $.ajax({ url: getcsrf('/Home/GettimeDif'), success: function (data) {
>     localStorage.setItem("TimeDif", parseInt(data) - new Date().getTime()) } })
> ```
> 站点自己就是用"服务端时间 − 本机时间"当偏移量（与 App 的 `clockOffsetMs` 同源）。
> 同时实测 `/config.json` **带可用的 `Date` 响应头**（`Wed, 16 Sep 2026 14:04:31 GMT`，与北京时间 22:04 一致），
> 因此首选方案可以直接落地，无需依赖 `GettimeDif`。

---

### C5. 服务端 5xx / 非 JSON 响应被当成「今天没有空教室」

**位置**：`data/remote/SmartClassApi.kt:77-90`（`fetch`）、`116-120`（`code` 只用于选流）、`data/remote/SmartClassParser.kt:79-82`（`errorMessage`）、`data/repo/FreeRoomRepository.kt:59-62`（第二处静默为空）

```kotlin
val text = res.body ?: return Result.failure(SmartClassException("网络请求失败"))
SmartClassParser.errorMessage(text)?.let { return Result.failure(...) }   // 解析不了 JSON → 返回 null
return Result.success(parse(text))                                        // 解析不了 JSON → 返回 emptyList
```

`errorMessage()` 用 `runCatching{...}.getOrNull()` 包裹，**body 不是 JSON 时返回 null**；随后 `parse()` 同样 `runCatching → null → emptyList()`。于是：

> 平台维护页 / 502 / 网关 404 / 校园网认证门户劫持返回一段 HTML → `res.body` 是 HTML → 没有错误 → `Result.success(emptyList())` → 页面显示 **「当前没有查询到无课教室」**，而且**没有「重试」按钮**（`error == null`，走的是空态分支），下拉刷新还会一直"成功"。
> `res.code`（HTTP 状态码）已经被读出来了，却只用来挑 `errorStream`，没有参与判定。

**第二处同类问题**：`loadFreeRooms` 里 `pickCycleType(nodeTypes)` 为 null（服务端返回空列表）时 `?: return emptyList()`，同样把"取不到节次类型"表达成"没有空教室"。

**为什么这条要升级为必修**：这是一个"静默错误"——用户看到的是**貌似正常但错误的事实**（今天没有空教室），而真实情况是服务不可用。信任伤害比直接报错大。

**最小修复**：

```kotlin
if (res.code !in 200..299) return Result.failure(
    SmartClassException("服务暂时不可用（HTTP ${res.code}）", tokenRejected = SmartClassCrypto.isTokenRejected(res.body.orEmpty()))
)
// 解析不了的响应 = 失败，不是空结果
fun errorMessage(body: String): String? = runCatching {
    val root = JSONObject(body)
    if (root.optInt("code", -1) == 0) null else root.optString("msg").ifBlank { "未知错误" }
}.getOrElse { "响应格式异常" }
```

（`errorMessage` 的语义变化与现有单测 `成功响应没有错误消息` / `code 非 0 时…` 不冲突，可安全改。）

---

### C6. 切换楼栋的请求竞态 + 空态闪屏

**位置**：`ui/freeroom/FreeRoomViewModel.kt:93-100`（`selectBuilding`）、`107-152`（`load` / `loadRooms`）

```kotlin
fun selectBuilding(buildingId: String) {
    _state.value = _state.value.copy(selectedBuildingId = buildingId, slots = emptyList(), error = null)
    viewModelScope.launch { settings.setFreeRoomBuilding(buildingId); loadRooms(buildingId) }
}
```

三个独立问题：

**(a) 陈旧响应覆盖（竞态）**：每个 `load`/`loadRooms` 都是独立协程，没有 Job 取消、没有请求序号校验，完成后无条件 `_state.value = copy(slots = ...)`。
场景：网络慢时连点「逸夫楼」→「教学楼」，两个请求并发；若逸夫楼的响应后到，**高亮显示教学楼、列表却是逸夫楼的教室**。下拉刷新与楼栋切换并发时同样会发生；`refreshing` 也会被先返回的那个请求提前清掉。
`HttpURLConnection` 的阻塞读不响应协程取消，所以只加 `job.cancel()` 不够，必须加序号守卫：

```kotlin
private var loadSeq = 0
private fun loadRooms(buildingId: String) {
    val seq = ++loadSeq
    viewModelScope.launch {
        try {
            val slots = repo.loadFreeRooms(buildingId)
            if (seq != loadSeq) return@launch          // 已被更新的请求取代，丢弃
            _state.update { it.copy(slots = slots, loading = false, refreshing = false) }
        } catch (e: Exception) {
            if (seq != loadSeq) return@launch
            _state.update { it.copy(loading = false, refreshing = false, error = friendly(e)) }
        }
    }
}
```

（顺带把 `_state.value = _state.value.copy(...)` 统一换成 `MutableStateFlow.update {}`，避免读改写分散在多处。）

**(b) 切换楼栋时闪一屏「当前没有查询到无课教室」**：`selectBuilding` 清空 `slots` 但没有把 `loading` 置真；`loadRooms` 又只把 `refreshing` 设为 `buildings.isNotEmpty()`。于是 `FreeRoomScreen.kt:70-83` 的 `when` 落到 `!state.hasData` 分支，**在新楼栋数据回来之前显示"当前没有查询到无课教室"**（同时顶部还挂着下拉刷新的转圈）。用户读到的是"这栋楼没有空教室"。
修法：切换时 `loading = true`（列表区显示进度圈，chips 保留），或把"空态"与"加载中"用同一个 `hasData/loading` 状态机表达清楚。

**(c) 切换楼栋不重置展开态**：`copy()` 里没有 `expandedIndex = null`，而 `refresh()` 明确重置了它（`FreeRoomViewModel.kt:110-115`）。用户在 6 个大节的楼里展开了「第六大节」，切到只有 4 个大节的楼 → `effectiveExpandedIndex` 返回越界下标 → **一张卡片都不展开**（与刷新的行为不一致），用户会以为页面坏了。修法：`copy(..., expandedIndex = null)`（跟随当前时间的语义在新楼栋同样成立）。

---

## 3. 🟠 应当修（健壮性 / 一致性）

### C7. 空态与错误态无法下拉刷新；空态没有任何出口

**位置**：`FreeRoomScreen.kt:56-60`（`PullToRefreshBox` 包着 `Column`）、`70-83`（两个非滚动分支）

`PullToRefreshBox` 依赖子内容的嵌套滚动事件；`CenterBox` / `ErrorBox` 都是不可滚动的 `Box`，**这两个状态下拉手势不产生任何事件**，刷新不可用。

- 错误态：还有「重试」按钮，可接受；
- 空态（`!hasData && error == null`，例如接口正常但今天确实没有空教室，或 C5 的静默失败、C6(b) 的闪屏）：**只有一个居中的「当前没有查询到无课教室」，没有任何操作入口**。

**修法**：给空态加一个「重新查询」按钮（最省事），或给两个状态的容器加 `verticalScroll(rememberScrollState())` 让下拉生效；文案补一句「下拉可重新查询」。

**附带的判定修正**：`when` 只把 `state.loading` 当作加载态，而"切换楼栋"这条路径是 `loading = false + refreshing = true`（见 C6b）。建议把加载判定写成
`state.loading || (state.refreshing && !state.hasData) -> CenterBox { CircularProgressIndicator() }`，
这样即使不重构状态机，切换楼栋期间也不会再显示"没有空教室"。

### C8. smartclass 的 DataStore 没有配 corruptionHandler（与项目已记录的教训不一致）

**位置**：`SmartClassKeyProvider.kt:10` ↔ `SettingsStore.kt:20-23`

`SettingsStore` 顶部有一段明确的教训记录：DataStore 文件损坏会进 `CorruptionException` → 收集协程反复崩溃且无法自愈，必须挂 `ReplaceFileCorruptionHandler`。新增的 `smartclass` store 没有挂。虽然它只存一个 `csrkKey`，损坏代价小，但**同类缺陷重复出现**本身就是审查项；而且它的读路径在 `csrkKey()` 里被 `runCatching` 吞掉、写路径也被 `runCatching` 吞掉，损坏时会安静退化成"永远用内置 key"（并触发 C1 的死锁），更难排查。

**修法**：照抄 `SettingsStore` 的 `corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`。

### C9. 展开后的教室行不是懒加载；`indexOf` 每项都在做深比较

**位置**：`FreeRoomScreen.kt:138-153`（`SlotList`）、`184-188`（展开内容是一个 `Column`）

- 展开的整个大节（截图 2：**24 间**空教室）作为**单个 LazyColumn item** 一次性组合。学院楼第一大节这种规模还能接受，但"全部时段"或大教学楼可能上百行，会一次性组合完，掉帧与内存都要付代价。
- `expanded = state.slots.indexOf(slot) == expanded` 与 `onToggle = { onToggle(state.slots.indexOf(slot)) }`：`RoomSlot` 是 data class，`equals` 会**递归比较 `rooms` 列表**，等于每个可见 item 每次重组都做一次 O(n·m) 深比较。用 `itemsIndexed` 直接用下标即可。
- `key = { it.nodeId.ifBlank { it.nodeName } }`：解析层允许 `nodeId` 为空（`SmartClassParser.parseRoomSlots` 只要求 `nodeName` 非空）。若服务端某天不给 `nodeId` 且两个时段重名，**LazyColumn 会因 key 重复直接抛异常**。建议 `itemsIndexed` + `"$index-${nodeId}"` 之类的复合 key。

**建议**：`itemsIndexed` 立刻改；结构性方案是把大节标题与教室行**打平进同一个 LazyColumn**（标题 item + 行 items），彻底懒加载。

### C10. 网络异常被吞成一句话，用户看不到可行动的信息

**位置**：`SmartClassApi.kt:121-123`

```kotlin
} catch (e: Exception) {
    RawResponse(-1, null)      // 异常对象直接丢掉
}
```

超时、DNS 失败、TLS 失败、连接被拒，全部变成 `网络请求失败`。`FreeRoomViewModel.friendly()` 于是只能给出笼统文案；而 `e.message` 分支（`FreeRoomViewModel.kt:165`）在别的异常路径上会把**英文异常原文**直接铺到中文界面上。

**修法**：`RawResponse` 增加 `error: Exception?`，在 `friendly()` 里做分类映射：`UnknownHostException` → 「网络不可用，请检查连接」、`SocketTimeoutException` → 「连接超时，请稍后重试」、其他 → 「加载失败，请下拉重试」；**不要再把 `e.message` 直接展示给用户**。

### C11. 切换楼栋不重置滚动位置；自动展开不随时间推进

**位置**：`FreeRoomScreen.kt:140-152`、`FreeRoomViewModel.kt:44-60`

- 楼栋切换后 `LazyColumn` 的 key（`nodeId` 与楼栋无关）不变，**滚动位置被保留**：用户在教学楼列表滑到第 5 个大节，切到逸夫楼后仍停在第 5 个大节顶部，看不到第一个时段。建议 `LaunchedEffect(selectedBuildingId) { listState.scrollToItem(0) }`（把 `LazyListState` 提到 `SlotList` 上层）。
- `effectiveExpandedIndex` 依赖 `LocalTime.now()` 的**一次性求值**：页面停留跨过大节边界（09:35 → 09:53）时不会重新计算，默认展开停留在旧时段。若要修，用 `LifecycleResumeEffect` 或在页面可见时挂一个 60s 的 ticker 重算。

### C12. 首帧分段与持久化默认值不一致；`setSection` 走磁盘往返

**位置**：`GradesViewModel.kt:49`（`section: GradesSection = GradesSection.SCORES`）、`283`（`stateIn` 初值 `GradesUiState()`）、`370-372`

- `uiState` 初值是 `GradesUiState()`，其 `section = SCORES`；真实分段要等 DataStore 与 Room 的首个发射。冷启动进入「教务」时，会在**最前面几帧渲染"还没有成绩数据 + 去获取"**，然后跳到无课教室。修法：初值改成 `GradesUiState(section = GradesSection.FREE_ROOM)`（与偏好默认值一致），或加一个 `initialized` 标志，未就绪时不渲染分段内容。
- `setSection` 先写 DataStore、再由 Flow 回灌 UI：点击分段按钮的选中态要等一次磁盘往返。低风险（通常 <10ms），但若追求手感，可加一个本地 `MutableStateFlow<GradesSection?>` 做乐观更新，写盘在背后进行。

### C13. 成绩列表行仍未随隐私开关掩码「排名 / 学分」（上一轮隐私修复的漏网之鱼）

**位置**：`ui/grades/GradesScreen.kt:847`、`851`（`GradeRow`）— 对照同一屏已经掩码的位置：`613`（详情弹层排名）、`618`（详情弹层学分）、`741-746`（GPA 卡片排名与学分）、`816`（勾选列表分数）

```kotlin
// GradeRow：分数掩码了，但排名与学分没有
val rankText = if (grade.pm.isNotBlank() && grade.zrs.isNotBlank()) " · 排名 ${grade.pm}/${grade.zrs}" else ""
Text(listOf(grade.kcxz, …).joinToString(" · ") + " · ${grade.xf}学分" + rankText)   // ← 无 hideScores 判断
Text(if (hideScores) "***" else grade.zzcj)                                          // ← 只有分数被掩码
```

提交 `354ccfd` 的说明里明确写了"专业排名与'纳入 N 门 · 共 X 学分'未掩码。排名属隐私信息，隐藏成绩时一并掩码；成绩详情弹层的'课程排名'与'学分'同样处理"——**成绩单列表行这一处漏了**：用户点小眼睛隐藏后，分数变成 `***`，但每行仍然明写「排名 12/120 · 4.0学分」，与同屏其它三处（详情弹层、GPA 卡片、勾选列表）的处理自相矛盾，也与"隐藏即全隐藏"的用户预期不符。

**性质**：**不是本次无课教室改动引入的**（早于 1.1.12 就存在），但因为无课教室把教务 Tab 的默认落点改到了第一段，成绩页的每一次进入都变成了"用户主动选择"的行为，这类漏网更值得一起收掉。

**最小修复**：`val rankText = if (!hideScores && grade.pm.isNotBlank() && grade.zrs.isNotBlank()) … else ""`；学分同理（`creditText = if (hideScores) "" else " · ${grade.xf}学分"`，或与详情弹层一致地显示 `***`）。

### C14. `LazyColumn` item 内的展开标志用 `remember`，滚远即丢

**位置**：`ui/grades/GradesScreen.kt:362`（`CreditProgressCard` 的 `expanded`）、`650`（`ScoreCard` 的 `showCourseSelector`）

`remember` 的生命周期跟着 item 的组合走。成绩列表很长时，把第一屏的「自定义纳入计算的课程」展开后一路下滑到底再回来，item 已被销毁重建，**展开态被重置为收起**（用户会以为自己的操作没生效）。修法：`rememberSaveable`（LazyColumn 按 item key 提供了 SaveableStateHolder，直接换即可）。

---

## 4. 🎨 视觉与交互设计审查

### 4.1 信息架构与导航层级

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| D1 | 底部 Tab 名为「教务」（学士帽图标），进入后默认落在**第三方平台的教室查询** | 标签承诺的是"教务"，首屏内容却是"教室"；「成绩」这个 README 里的旗舰功能被降级到第二段 | 保留结构，但在**页面内**把层级讲清楚：分段控件是教务 Tab 的子导航，页面标题区加一行来源说明「数据来自贝壳教学平台」（也顺带完成合规披露）。若后续统计显示教室使用率确实最高，再考虑升级为独立底部 Tab（4 个 Tab） |
| D2 | 顶栏只有一个**属于成绩抓取**的刷新图标，却在无课教室段可见（截图 2） | 动作与页面不匹配，且点了没有可见效果（C3） | 按段切换顶栏动作：无课教室段→不显示（刷新靠下拉 / 或改为刷新教室数据）；成绩/考试段→显示重新抓取 |
| D3 | 无课教室页没有任何"数据从哪来、什么时候的"信息 | 用户要据此决定"走去哪间教室"，数据时效是决策的必要条件 | 顶部加一行元信息条：`今天 09-16 周三 · 更新于 21:49 · 数据来自贝壳教学平台`，并在超过 N 分钟（建议 5 分钟）未刷新时把"更新于"变成可点的「点击刷新」 |

### 4.2 页面视觉

**截图证据**：截图 1（21:49，逸夫楼，第二大节展开）；截图 2（21:48，学院楼，第一大节展开 24 间，其上覆盖「重新抓取」对话框）。

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| D4 | 大节卡片用 `surfaceVariant.copy(alpha = 0.55f)`（`FreeRoomScreen.kt:161`） | 浅色下卡片与背景的亮度差只有约 4%～6%，**卡片边界几乎不可见**（截图里靠圆角和留白勉强分辨）；深色下 alpha 叠加会进一步压缩色阶 | 用不透明的容器色（`surfaceContainerHigh`/`surfaceContainerHighest`，或 `surfaceVariant` 全不透明），或保留浅底但加 1dp `outlineVariant` 描边 |
| D5 | 同屏三种圆角：卡片 12dp、楼层 chip 16dp、分段控件整圆；间距 4/6/8/10/12/14 混用 | 视觉语言不统一，页面看起来"没有设计系统" | 定一组 token：圆角 12/16/28（卡片/控件/胶囊）、间距 4/8/12/16/24，全页只用这几个值 |
| D6 | 楼层 chip 是手搓的 `Text + clip + background + clickable`（`FreeRoomScreen.kt:117-131`） | 与 M3 分段控件的色调语言不一致；未选中底色过浅；点击高度只有约 36dp（<48dp 最小触控区）；且没有选中语义 | 换成 M3 `FilterChip`（自带 48dp 触控区、selected 语义、ripple、统一色调）；横向滚动容器用 `contentPadding` 保证首尾留白（现在是 padding 全在滚动内容里，滑到两端时留白会跟着走） |
| D7 | 分段控件选中项前多出一个 ✓（M3 `SegmentedButton` 默认行为） | 选中态变化时标签在段内左右位移（截图 1 的「无课教室」明显比「成绩/考试」挤） | 传 `icon = {}`（统一无图标）或三个段都给固定宽度的图标位，避免切换时标签跳动 |
| D8 | 教室行：左名称、右「N 座 / X% 空座」（`FreeRoomScreen.kt:194-221`） | 座位数与空座率是**右对齐的两个自由文本**，座位数位数变化时"空座率"这一列会左右浮动，读起来不成表格；行间没有分隔或交替底色，扫读长列表（24 行）容易串行 | 尾部列固定宽度（如 `Modifier.widthIn(min = 72.dp)` + `textAlign = End`）；行高加大到 40dp 或加极淡分隔线 |
| D9 | 几乎每行都是「100% 空座」（两张截图里 9 行有 8 行是 100%） | 重复信息占据视觉权重，真正有区分度的**座位数**反而更弱 | 100% 时隐藏该列（或把非 100% 的行用一个小徽章标出来），默认突出座位数；排序改为**座位数从多到少**（见 D11） |
| D10 | 「空座率 ≥50%」用主色文字表示 | **颜色是唯一编码通道**（色盲/灰度/深色下失效），且 50% 这个阈值没有依据 | 用文本或图形编码（如非满座显示「剩 80%」），或干脆去掉颜色，把信息交给排序与筛选 |
| D11 | 教室按**名称自然排序**（已实现得很扎实） | 名称序对"找一间能坐下的大教室"这个目标没有帮助；21:48 打开页面时，最重要的一条信息不是"教室叫什么" | 在名称序之外给一个排序切换（名称 / 座位数）或默认按座位数降序；名称序仍保留给"我知道教室号"的场景 |

### 4.3 交互细节

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| D12 | **没有任何"现在"的表达**：数据是"今天"的，页面不写日期、不标当前时段、不淡化已结束时段 | 截图 2 拍于 **21:48**，当天所有大节均已结束，页面却把 07:58 的第一大节当作最相关结果展开给用户看（`currentSlotIndex()` 在时段外返回 -1 → 回退到下标 0）。用户看到的是**已经无法使用的信息**，且没有任何提示 | 三件事：①标题显示「今天 09-16 周三」；②当前时段卡片加「进行中」徽章 + 轻微高亮；③**已结束时段的卡片整体淡化（alpha 0.5）**；全部结束时空态改为「今天的课已全部结束，明天 07:58 起第 1 大节」 |
| D13 | 空态/错误态无出口，且不能下拉（C7） | 死路 | 见 C7 |
| D14 | 切换楼栋闪「当前没有查询到无课教室」（C6b） | 错误信息误导 | 见 C6b |
| D15 | 折叠卡片的「N 间空教室」埋在第二行的句尾 | 这是用户扫视 6 张大节时唯一要读的数字，却是最弱的一级 | 提到右侧做徽章/圆角计数，或让数量用 `titleSmall` + 强调色；已结束时段的计数同步淡化 |
| D16 | 刷新失败保留旧数据 + 顶部一行红字（`FreeRoomScreen.kt:86-93`） | 这个策略是对的（值得保留），但红字没有时间上下文，用户不知道看到的是几分钟前还是昨天的数据 | 与 D3 合并：错误条写「刷新失败，以下为 21:43 的数据」 |
| D24 | **相邻页面同类缺陷（一并收掉）**：`GradesScreen.kt:370-391` 的「学分修读进度」卡片、`769-791` 的「自定义纳入计算的课程」行，箭头固定为 `KeyboardArrowRight`（`contentDescription = null`，永不旋转） | 内容会展开/收起，箭头**永远朝右**——视觉上像"进入下一级"，实际是就地展开，反馈错误；读屏用户也听不到任何可展开/已展开的信息。项目自己在 `ProfileScreen.kt:513` 用的是 `toggleable(role = …)`，规范没有贯彻 | 箭头随状态旋转（`Modifier.rotate(if (expanded) 90f else 0f)`）或换 `ExpandMore/ExpandLess`；整行改 `Modifier.toggleable(value = expanded, role = Role.Button)` + `stateDescription` |
| D25 | **相邻页面同类缺陷**：考试列表里已结束的考试只用 `Modifier.alpha(0.45f)` 淡化（`GradesScreen.kt:541-569`） | ①对比度不足：`onSurfaceVariant` 按 0.45 混到背景后，浅色主题约 **2.2:1**、深色约 3.6:1，低于 4.5:1（甚至低于 3:1 的大字下限），低视力用户读不清已结束考试的课程/时间/地点；②"已结束"只由透明度表达，色觉/灰度场景丢失语义 | 保持正文不透明，另加文字标记「已结束」（或用删除线/徽章）；若坚持淡化，alpha 提到 ≈0.7 并在行首加标记 |

### 4.4 无障碍

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| D17 | 楼层 chip：`Text` + `clickable` | 高度约 36dp（<48dp）；无 `Role`、无选中态语义，TalkBack 只念文本+"双击激活" | 用 `FilterChip`（自带语义）或 `Modifier.selectable(selected, role = Role.RadioButton)` + `minimumInteractiveComponentSize()` |
| D18 | 大节卡片：整行 `clickable`，右侧图标 `contentDescription = "收起"/"展开"` | 展开态只由图标方向表达，读屏用户听到"展开"但不知道"当前是否已展开"，也无法感知"这是第几大节、有几间教室" | 用 `Modifier.toggleable(value = expanded, role = Role.Button)` + `semantics { stateDescription = if (expanded) "已展开" else "已收起" }`；把标题合成为一句话：「第二大节 09:53-11:35，2 间空教室，已展开」 |
| D19 | `noSeatRate == null` 渲染为 `--`（`FreeRoomScreen.kt:209`） | 读屏会念"横杠横杠"，含义不明 | 改为「暂无数据」 |
| D20 | 空座率高亮仅靠颜色（D10） | 色觉障碍用户丢失信息 | 见 D10 |

### 4.5 文案

| # | 现状 | 问题 | 建议 |
|---|---|---|---|
| D21 | 同一屏三种叫法：「无课教室」（分段/空态）、「N 间空教室」（卡片）、「空座率/N% 空座」（行） | 术语不统一，用户要自己推断"空教室"和"无课教室"是不是一回事 | 全站统一：功能名用「无课教室」，计数用「N 间可用」，比率保留「空座率」 |
| D22 | `服务暂时不可用：签名密钥可能已更新，请稍后重试或检查 App 更新` | 把内部机制暴露给用户，行动指引模糊（"检查 App 更新"不是用户能判断的事） | 面向用户：「暂时查不到空教室，请稍后重试」；开发者信息进日志。另加一个**兜底动作**：「在浏览器中打开贝壳教学平台」（见 E6） |
| D23 | 空态「当前没有查询到无课教室」 | 没有下一步 | 「今天没有查询到空教室 · 下拉刷新」+ 刷新按钮 |

### 4.6 设计系统层面

- 页面视觉现在有两套语言：**课表页**（暖色渐变背景 + 十色课程色板 + 圆角色块）与**教务页**（纯色背景 + 默认 M3 容器色）。两个 Tab 之间切换时观感割裂。建议至少在教务 Tab 顶部保留一段渐变过渡，或把 `CourseColors.scheduleGradient` 抽象成"页面背景"token 供两处共用。
- **容器色透明度三处不一致**：`FreeRoomScreen` 用 `surfaceVariant@0.55`、`GradesScreen` 用 `@0.6`、`ProfileScreen` 用 `@0.7`。三张页面在同一个 App 里出现三种"卡片灰"。建议抽一个 `BeikeCardColors`（或统一用 M3 的 `surfaceContainer*` 语义色）后三处共用。
- **区块标题**已经形成事实规范（`titleSmall` + `colorScheme.primary` + `padding(16, 8)`，见 `ProfileScreen` 与 `GradesScreen`），但每处都是手写。建议抽成 `SectionHeader(text)`，无课教室页的"楼栋选择"也就能顺势获得与其他页一致的层级表达。
- `Theme.kt` 目前完全依赖 Material You 动态色（minSdk 34 ⇒ 动态色恒生效），`Color.kt` 里的 `Purple40/PurpleGrey40/Pink40` 与 `Theme.kt:49-51` 的静态分支实际上是**死代码**（`dynamicColor && SDK_INT >= S` 恒为真）。这不影响本功能，但意味着**全站配色随用户壁纸变化**，无课教室页的"空座率高亮=主色""选中 chip=主色"在低饱和壁纸上会明显变弱。建议为功能语义色（成功/警示/选中）定义固定的语义色，不跟动态色走。

---

## 5. ⚙️ 优化建议

### 性能

- **E1**：见 C9（`itemsIndexed` + 长列表懒加载）。
- **E2**：`FreeRoomViewModel` 在 `init` 里就发起网络请求；由于默认段是无课教室，**打开教务 Tab 即产生对校外域名的请求**（即使离线也一样会先等超时）。建议：仅在段真正可见时加载（`FreeRoomScreen` 的 `LaunchedEffect(Unit)`），或先显示缓存/上次结果再刷新。
- **E3**：无跨会话缓存。空教室是实时数据没错，但"10 分钟前的空教室"远好于"断网就白屏"。建议缓存最近一次结果 + 时间戳（内存即可；若做持久化请一并纳入 `data_extraction_rules.xml` 的排除项）。

### 工程与配置

- **E4**：`TOKEN_TIME_SKEW_MS = 2 分钟`是硬编码的实测值，而 `/config.json` 里就带着 `csrkTime: 300000`（=5 分钟，见 `app/src/test/resources/smartclass-config.json:5`）。建议解析 `csrkTime` 并按其一半取余量，常量只作兜底——服务端调整窗口时无需发版。
- **E5**：`SettingsStore.Keys.FREE_ROOM_TAB_INDEX = "free_room_tab_index"`（`SettingsStore.kt:87`）实际存的是**教务分段下标**，且被放在"无课教室"注释块下（`SettingsStore.kt:243-253`）。命名与归属都会误导下一个维护者。改名会丢一次偏好（可接受），建议顺手改成 `jw_tab_index` 并移到教务相关区块。
- **E6**：集成完全依赖逆向出的 AES Key/IV 与签名算法，**一旦平台改动，只能等发版**。建议加一个降级出口：错误态提供「在浏览器中打开贝壳教学平台」外链（`ProfileScreen` 已有 `openExternal` 的成熟写法），并把 `/ustb/ClassRoom.aspx` 记进 `docs/JWXT_API.md`。

### 测试

- **E7**：新增 41 例单测覆盖了 crypto / parser / 自然排序，但**恰好漏掉了本次唯一有真实缺陷的两层**：
  - `SmartClassKeyProvider`：`csrkKey()` 的三层兜底顺序、`forceRefresh` 语义、时钟校正（C4 如果有测试，这个 bug 不会存在）。它依赖 `Context`，建议把 `SmartClassApi` 抽成接口 + 注入 DataStore，或用 Robolectric。
  - `FreeRoomRepository`：`withSignedRetry` 的被拒→`invalidate`→重试成功/失败两条路径、"非 token 错误不重试"，以及 **`loadMeta` 是否也走了重试**（C1 如果有这一条测试，会在 CI 里直接红）。
  - `FreeRoomUiState.currentSlotIndex` / `effectiveExpandedIndex` 是纯函数（`now` 可注入），是最便宜的测试目标：时段内/时段间/时段外/时间串非法/手动收起（-1）三态。
  - `fetch()` 的 HTTP 500 与非 JSON 响应（C5）。
- **E8**：`SmartClassApi` 目前 `HttpURLConnection` 直连、不可注入。为了可测与可替换，建议抽出 `interface SmartClassHttp { suspend fun get/post(...): RawResponse }`，默认实现就是现在这段。

### 文档 / 合规

- **E9**：`README.md`、`docs/TECH_DESIGN.md`、`docs/JWXT_API.md` **完全没有提到无课教室 / smartclass**（`grep -i "smartclass|无课教室"` 在全部 markdown 里 0 命中）。README 第 14 行仍写「**教务（成绩 | 考试）**」，而界面已是三段。这是本次改动最明显的文档欠账。
- **E10**：`docs/samples/` 里没有任何 smartclass 样本。新功能的三个接口（listBuildings / listNodeTypes / freeClassRooms）+ `/config.json` + `/Home/GettimeDif` 都值得按项目惯例存档一份**脱敏**样本，避免下次再"扒站"。
- **E11（合规）**：新功能会在用户打开教务 Tab 时**主动向校外域名 `ustb.smartclass.cn` 发起请求**（携带自定义 UA 与 Referer）。不涉及学号姓名，但属于"未明示的对外通信"。建议：①界面标注数据来源；②README 隐私段补一句"查看无课教室时会访问贝壳教学平台，该请求不包含你的学号与身份信息"；③在正式分发前确认学校/平台对第三方客户端调用开放接口的态度（属产品决策，见 Q5）。

---

## 6. ✅ 已确认正确、不要动的部分

- **签名与解密**（`SmartClassCrypto`）：13 字符 token 的逐位取字符算法、`hexToBytes` 的奇数长度防御、`isTokenRejected` 只认服务端原文（"服务端异常"不被误判为 key 失效）——单测锁定得很到位，指纹测试也防止了误改密钥。**独立复核（第二个审查者）确认 `genToken`/`hexToBytes` 无可达缺陷。**
- **解析层的 `null` / `0` 区分**（`noSeatRate`）：`isNull()` 前置判断是这段代码最容易写错的地方，写对了且有测试守护。
- **`optX` 全容错 + 跳过空时段**：服务端字段缺失不会白屏，符合 `JwParser` 的既有约定。
- **`RoomNameOrder`**：把数字段归一化（前导零）并按位数比较而非 `Long` 解析，避免超长数字退化；比较器满足反对称与传递性（人工验证 Num/Text 混合情形 + 独立复核确认），12 例单测覆盖到边界。
- **`withSignedRetry` 只重试一次**：判断正确——重试仍失败说明不是 key 轮换，继续重试只会拖长等待（但必须让 `loadMeta` 也走它，见 C1）。
- **刷新失败保留旧数据 + 顶部提示**：产品判断正确，比"刷新失败就白屏"好。
- **展开态三态设计**（`null`=跟随时间 / `-1`=全部收起 / 下标=手动）：注释里记录了踩坑原因，实现与语义一致，值得保留（只在楼栋切换时要补一次重置，见 C6c）。
- **隐私开关与备份规则**：`data_extraction_rules.xml` / `backup_rules.xml` 的 `<exclude domain="file" path="datastore/" />` 覆盖了新加的 `datastore/smartclass.preferences_pb`，`csrkKey` 不会进云备份（已核对）。
- **lint**：新增文件 0 warning / 0 error（实跑确认）。
- **`Theme.kt` 的模式/状态栏图标逻辑**：`darkTheme` 与 `isAppearanceLightStatusBars` 的推导经独立复核确认无误（动态色与静态分支的取舍见 4.6，属设计问题而非缺陷）。

---

## 7. 📄 文档与代码不一致清单

| 位置 | 现状 | 应为 |
|---|---|---|
| `README.md:14` | `**教务（成绩 \| 考试）**` | `**教务（无课教室 \| 成绩 \| 考试）**`，并补一段无课教室功能说明（含数据来源与"无需登录"） |
| `README.md:5-27` 功能列表 | 无无课教室条目 | 新增条目：按楼栋查看各大节空教室、教室名自然排序、当前大节自动展开、下拉刷新 |
| `README.md:40-48` 教务接口要点 | 只有教务（byyt）接口 | 补 smartclass 三段式说明（签名要求 `csrkToken`、`/config.json` 加密 key、服务器时间窗口）并指向新文档 |
| `README.md:59-61` 隐私 | "数据仅保存在本机，不上传云端" | 补一句对外域名访问说明（E11） |
| `docs/TECH_DESIGN.md` | v1.0，非目标里写着"成绩查询、考试安排"不做 | 文档已明显滞后（成绩/考试/学分/无课教室均已实现），至少加一节"离线/第三方数据源"并标注版本 |
| `docs/JWXT_API.md` | 无 smartclass | 新增附录：接口表 + 请求/响应样本 + **签名与解密说明**（否则下次改版又要重新逆向） |
| `docs/samples/` | 无 smartclass 样本 | 见 E10 |
| `ProfileScreen.kt:273` 注释 | "课程平台/实践平台地址待补后追加" | 课程平台即贝壳教学平台，现在已原生集成；注释该更新或直接加一个网页入口（E6） |
| `FreeRoomRepository.kt:18` | 注释"每个接口都做一次重试" | 与实现不符（`loadMeta` 没有）——修 C1 后注释才成立 |

---

## 8. 行动清单

### 第一梯队（P0，建议合入下一个包再发）
1. **C1** `loadMeta` 的 `listBuildings` 改走 `withSignedRetry`（一行；不修则平台换 key 后全体老用户功能永久失效）
2. **C2** 分段控件提到 WebView 外层 + 抓取页加「取消」出口（顺带修掉"重启仍被锁在登录页"）
3. **C3** 顶栏刷新按钮按段显示；`startRefresh()` 同步切到成绩段
4. **C4** 时钟校正改为读 `/config.json` 的 `Date` 头（或先取 key 再调 `GettimeDif`）；确认响应格式
5. **C5** `fetch()` 校验 HTTP 状态码；`errorMessage()` 对非 JSON 返回错误而非 null；`pickCycleType == null` 不再静默返回空列表
6. **C6** `loadRooms` 加请求序号守卫；切换楼栋时 `loading = true` 且 `expandedIndex = null`

### 第二梯队（P1，可与 P0 同批或紧随）
7. **C7** 空态加「重新查询」按钮 + 可下拉
8. **C8** smartclass DataStore 加 `ReplaceFileCorruptionHandler`
9. **C9** `itemsIndexed` + 复合 key；评估打平为单层 LazyColumn
10. **C10** `RawResponse` 保留异常并分类映射为中文文案
11. **C11/C12** 切楼栋重置滚动、跨时段重算、`stateIn` 初值改 `FREE_ROOM`
12. **C13** `GradeRow` 的排名/学分补上 `hideScores` 掩码（顺手收掉上一轮隐私修复的漏网）
13. **C14** `showCourseSelector` / `CreditProgressCard.expanded` 改 `rememberSaveable`
14. **E4** 用 `csrkTime` 替代硬编码余量

### 第三梯队（P2，体验升级，建议单独一轮）
13. **D3/D12/D15** 时间基准条（今天/更新于/来源）+ 当前时段徽章 + 已结束时段淡化 + 计数徽章
14. **D11/D9** 座位数排序（或排序切换）+ 100% 空座不再重复显示
15. **D4/D5/D6/D7/D8** 视觉 token 统一、卡片对比度、`FilterChip`、分段去 ✓、尾部列对齐
16. **D17/D18/D19/D24/D25** 无障碍与状态反馈：48dp 触控区、展开行 `toggleable` 语义、`--` 改「暂无数据」、展开箭头随状态旋转、已结束考试加文字标记并提对比度
17. **D21/D22/D23** 文案统一与可行动化；错误态加浏览器兜底入口
18. **E9/E10/E11** README/TECH_DESIGN/JWXT_API 更新 + 样本存档 + 隐私披露
19. **E7/E8** 补 `SmartClassKeyProvider` / `FreeRoomRepository` / `FreeRoomUiState` 单测（配合 HTTP 抽象）

---

## 9. ❓ 待决策问题（需要你回答）

- **Q1｜默认段**：README 把成绩/GPA/学分进度当作旗舰功能，而教务 Tab 现在默认落在无课教室（第三方数据）。这是你有意的产品决定（提交信息里写了），我只想确认：**是否接受"用户点教务先看到校外平台的数据"**，以及是否接受在页面里明示数据来源？若你想更强地区分，我可以把无课教室做成独立底部 Tab。
- **Q2｜时间维度**：接口只接受 `buildingId/cycleTypeId/nodeId`，没有日期参数 ⇒ **只能查"今天"**。21:48 打开时页面展示的是当天已经结束的时段（截图 2）。你希望我怎么做：①只加"今天/更新于"的说明并淡化已结束时段；②还是需要"明天/选星期"的能力（需确认平台是否有对应接口，可能要再逆向一次）？
- **Q3｜空座率列**：两张截图里 9 行有 8 行是「100% 空座」。这个字段还要保留吗？我的建议是"非 100% 才显示"，并把默认排序改成按座位数降序——但这会改变你当前的排序语义（名称序），需要你确认。
- **Q4｜`/Home/GettimeDif`**：✅ **已由站点源码核实**——返回绝对毫秒时间戳（`TimeDif = parseInt(data) - Date.now()`），且 `/config.json` 带可用 `Date` 头。修复采用"Date 头优先 + GettimeDif 兜底"，两条路都不需要你再提供样本。
- **Q5｜合规**：向 `ustb.smartclass.cn` 发起的未明示请求（自定义 UA/Referer）是否需要在校内分发口径上做说明？如果你确认要保留"打开教务即请求"的行为，我会顺手把 E11 的隐私披露写进 README。
- **Q6｜交付方式**：要我现在直接按"第一梯队 + 第二梯队"开工改代码吗？我建议：**P0+P1 一个提交批次**（含单测与 README 更新），P2 的视觉/文案改造单独一个提交（便于你对着真机截图 review）。

---

## 附录 A：本次审查的验证方式

| 手段 | 结果 |
|---|---|
| `./gradlew :app:testDebugUnitTest --rerun` | BUILD SUCCESSFUL；24 套 / 220 例全绿（其中新增 SmartClassCryptoTest 16、SmartClassParserTest 13、RoomNameOrderTest 12） |
| `./gradlew :app:lintDebug` | 0 error / 32 warning / 1 hint；**新增的 7 个文件 0 命中** |
| 源码通读 | 本报告引用的每一处代码均逐行读过（行号可复核） |
| 独立复核 | 第二个审查者只读数据层 7 个文件，独立检出 C1（`loadMeta` 未走重试，高危漏报）与 C6c（`expandedIndex` 未重置）；第三个审查者只读 UI 层 5 个文件，独立复现了 C2/C3/C6b/C7/D17 并新增 C13/C14/D24/D25；两者对 `RoomNameOrder`、`genToken`、`Theme.kt` 的"无缺陷"结论与本轮一致 |
| 真机截图 | 2 张（21:48 / 21:49），用于视觉与交互结论 |
| 未做 | 真机抓包、时区/时钟偏移复现、超长列表性能实测、深色模式截图 —— C4/C5/C9 的结论来自代码推演，均已在文中给出验证方法 |

## 附录 B：关键代码位置索引（行号对应**修复前**的 `fb875c9`）

| 主题 | 位置 |
|---|---|
| 请求签名 / 配置解密 | `data/remote/SmartClassCrypto.kt`（Key/IV: 32-35，skew: 53，genToken: 83-98） |
| key 三层兜底 / 时钟 | `data/remote/SmartClassKeyProvider.kt`（csrkKey: 46-59，signingTimeMillis: 67-68，syncClock: 71-75） |
| HTTP 与错误包装 | `data/remote/SmartClassApi.kt`（serverTimeMillis: 45-49，fetch: 77-90，request: 94-126） |
| 解析 | `data/remote/SmartClassParser.kt`（errorMessage: 79-82，parseRoomSlots: 106-127） |
| 重试与排序 | `data/repo/FreeRoomRepository.kt`（注释: 12-18，loadMeta: 29-37，loadFreeRooms: 57-67，withSignedRetry: 86-95） |
| 页面 | `ui/freeroom/FreeRoomScreen.kt`（PullToRefreshBox: 56-60，空/错态: 70-83，BuildingTabs: 101-134，SlotList: 138-153，SlotCard: 156-190，RoomRow: 194-221） |
| 状态机 | `ui/freeroom/FreeRoomViewModel.kt`（effectiveExpandedIndex: 44-45，currentSlotIndex: 53-60，selectBuilding: 93-100，load: 107-140，loadRooms: 142-152，friendly: 161-166） |
| 教务 Tab 编排 | `ui/grades/GradesScreen.kt`（刷新按钮: 125-129，确认框: 135-148，分支: 149-228） |
| 分段持久化 | `ui/grades/GradesViewModel.kt`（默认段: 49，section Flow: 231-233，init: 285-293，setSection: 370-372）、`data/pref/SettingsStore.kt:243-253` |

## 附录 C：本次修复记录（P0 + P1 + 你点选的两项体验改造）

| 编号 | 状态 | 落地位置 / 做法 |
|---|---|---|
| C1 | ✅ | `FreeRoomRepository.loadMeta` 的 `listBuildings` 改走 `withSignedRetry`；`listNodeTypes` 保持宽松（`runCatching`） |
| C2 | ✅ | `GradesScreen` 把 `SectionTabs` 提到所有分支之上；`WebViewFetch` 加「取消」→ `GradesViewModel.cancelFetch()` |
| C3 | ✅ | 顶栏刷新按钮仅在非无课教室段显示；`startRefresh()` 同时把分段切到成绩（持久化） |
| C4 | ✅ | `syncClock()` 改为「`/config.json` 的 `Date` 头优先 → 签名调 `GettimeDif` 兜底」；`serverTimeMillis(signed)` 去掉了可空默认值，从类型上杜绝再次漏传 |
| C5 | ✅ | `fetch()` 先判 `code < 0` / 非 2xx；`errorMessage()` 对非 JSON 返回"响应格式异常"；`pickCycleType == null` 改为抛错 |
| C6 | ✅ | 新增请求序号 `loadSeq` 守卫（切楼栋/刷新互相作废）；切楼栋进加载态并重置 `expandedIndex`/`loadedAt`；所有状态写入改 `MutableStateFlow.update` |
| C7 | ✅ | 空/错态改用可滚动的 `StateMessage`（下拉手势恢复）+ 显式按钮；加载判定加入 `refreshing && !hasData` |
| C8 | ✅ | smartclass DataStore 补 `ReplaceFileCorruptionHandler` |
| C9 | ✅ | `itemsIndexed` + `"slot_$index"` 复合 key，去掉 `indexOf` 深比较 |
| C10 | ✅ | `RawResponse` 保留异常；DNS/超时/连接/SSL 分类文案；HTTP 状态码分类文案；不再把英文异常原文铺到界面 |
| C11 | ✅ | 楼栋切换后 `scrollToItem(0)`；生命周期感知的 30s ticker 复算"现在" |
| C12 | ✅ | `stateIn` 初值改 `GradesUiState(section = FREE_ROOM)`；`startRefresh` 的分段切换为显式写入 |
| C13 | ✅ | `GradeRow` 的排名/学分补上 `hideScores` 掩码（与详情弹层、GPA 卡片口径一致） |
| C14 | ✅ | `showCourseSelector` / `CreditProgressCard.expanded` 改 `rememberSaveable` |
| D3/D12 | ✅ | 顶部元信息条（今天几号周几 · 更新时间 · 数据来源）+ 数据超过 5 分钟给「刷新」；当前时段标「进行中」、已结束标「已结束」并淡化卡片底色；全天结束时给一行说明 |
| D8 | ✅ | 教室行尾部两列定宽右对齐（座位数 56dp / 空座率 72dp），`--` 改「暂无数据」 |
| E7 | ✅ | 新增 `FreeRoomRepositoryTest`（9 例：被拒→重试、非签名错误不重试、节次类型缺失报错、自然排序、校时失败不阻塞…）与 `FreeRoomUiStateTest`（14 例：时段判定/默认展开/全天结束/加载态）；`SmartClassParserTest` 补非 JSON 响应必须判失败 |
| E8 | ✅ | 抽出 `SmartClassDataSource` / `SmartClassKeySource` 两个接口，Repository 可注入假实现 |
| E9 | ✅ | `README.md` 功能表、教务接口要点、隐私段更新；`JWXT_API.md` 新增第 9 节（smartclass 接口 + 签名 + 只能查今天的事实） |
| E4 | ⏸️ 暂不做 | `csrkTime`（5 分钟窗口）替代硬编码余量：现有 2 分钟余量是实测验证过的，改动的收益（服务端调窗口时免发版）小于改错的代价（全量请求失败），等有真实轮换证据再做 |
| 其余 P2（D4~D7、D9~D11、D13~D25、E1~E3、E5、E6、E10、E11） | ⏳ 未做 | 见上文第三节与行动清单；其中 D6（FilterChip）、D7（分段 ✓ 位移）、D24（箭头不旋转）、D25（已结束考试对比度）建议下一轮一起做 |
