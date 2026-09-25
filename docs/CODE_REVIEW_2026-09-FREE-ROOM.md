# 无课教室功能 代码审查报告（v1.1.13）

- **审查时间**：2026-09-16
- **审查对象**：无课教室功能全部 4 个提交（`06f4e9b` 签名解密 → `6eaea0b` API 客户端 → `b65069b` Repository → `6ba448b` UI 三段化），至 versionCode 32 / 1.1.13
- **审查范围**：`SmartClassCrypto` / `SmartClassApi` / `SmartClassParser` / `SmartClassKeyProvider` / `FreeRoomRepository` / `RoomNameOrder` / `FreeRoomViewModel` / `FreeRoomScreen` / `GradesScreen·ViewModel` 三段化改动 / `SettingsStore` 新增键，及 3 个测试文件（41 个测试）
- **验证方式**：逐行通读 + 追踪调用链复核 + 实际运行单测（41/41 通过，0 失败）
- **同时附**：基于两张真机截图的视觉与交互设计审查（第 7 节）

标记：🔴 高 / 🟠 中 / 🟡 低 / ⚪ 工程整洁。

---

## 1. 总体结论

分层是教科书级的：Crypto（纯函数）→ Api（IO）→ KeyProvider（状态与兜底）→ Repository（编排与自愈）→ ViewModel → Screen，每层只做一件事，注释全部在解释"为什么"而非复述代码。`noSeatRate` 的 `isNull` 判断（区分"无数据"与"真的 0%"）、刷新失败保留旧数据、key 轮换自愈重试，都是刻意做对的细节。

单测实跑结果：`SmartClassCryptoTest` 16、`SmartClassParserTest` 13、`RoomNameOrderTest` 12，共 **41 通过 0 失败**。

但审查发现 **1 个高危功能缺陷**（时钟校正从未生效）、**2 个中危**（错误页被当成空数据、切楼竞态），以及若干体验问题——其中一个是截图里直接可见的交互错位。

---

## 2. 🔴 高危：服务器时钟校正从未生效（死代码）

**位置**：`SmartClassKeyProvider.kt:71-75` × `SmartClassApi.kt:45-49`

```kotlin
// SmartClassApi —— 第一行就拦截
suspend fun serverTimeMillis(signed: SignedRequest? = null): Long? {
    if (signed == null) return null
    ...
}

// SmartClassKeyProvider —— 调用时没传签名
suspend fun syncClock() {
    api.serverTimeMillis()?.let { server ->        // ← signed 恒为 null，恒返回 null
        clockOffsetMs = server - System.currentTimeMillis()
    }
}
```

`syncClock()` 调用 `serverTimeMillis()` 时未传 `SignedRequest`，该方法 `signed == null` 直接返回 null，所以 **`clockOffsetMs` 从进程启动到结束永远是 0**，`signingTimeMillis()` 退化为"本机时间 + 2 分钟余量"。

**后果**：设备时钟偏慢超过约 3 分钟的用户，所有请求的 token 都落在服务端 `[now, now+5min]` 窗口之外被拒；而 `withSignedRetry` 的自愈路径（invalidate key + forceRefresh）修复不了时间问题，用户最终看到"签名密钥可能已更新，请稍后重试或检查 App 更新"——文案与真实原因（时钟不准）不符，且永远修不好。

**双重错误**：API 的 KDoc 明确写了"必须用一个 key 先签——调用方在拿到 key 之前可以先不校正，拿到后再调"；但 `FreeRoomRepository.loadMeta()` 的顺序是先 `syncClockOrSkip()` **再** `signed()`，顺序也反了。注释预见了这个坑，实现踩了进去。

**为什么真机验证没发现**：测试机时钟是准的。这是典型的"依赖环境状态"缺陷，真机清单里加一条"手动把系统时间调慢 5 分钟再测"即可覆盖。

**修复建议**（三处配套）：
1. 删掉 `serverTimeMillis` 的 `signed` 默认参数——让这类 bug 编译期就暴露；
2. `syncClock()` 改为先 `csrkKey()` 拿 key，再 `serverTimeMillis(SignedRequest(key, System.currentTimeMillis()))`；
3. `withSignedRetry` 遇到 `tokenRejected` 时，重试前除了重取 key 还应重新校正时钟（key 和时间都可能是被拒原因）。

---

## 3. 🟠 中危

### M1 非 2xx / 非 JSON 响应被当成"空数据"成功

**位置**：`SmartClassApi.kt:77-90`（`fetch`）

`fetch()` 只用 `errorMessage(text)`（JSON `code != 0`）判失败；`RawResponse.code` 字段**全程未被使用**。当服务端返回 500/502 的 HTML 错误页（或校园网网关、WAF 拦截页）时：

```
HTML body → errorMessage() 解析失败返回 null → parseRoomSlots(HTML) 返回空列表
          → Result.success(emptyList())
```

**后果**：`loadFreeRooms` 返回空 → UI 显示**"当前没有查询到无课教室"**——服务器故障被翻译成"真的没空教室"，用户会信以为真白跑一趟。`loadMeta` 侧稍好，显示"没有获取到教学楼列表"。

**修复**：`fetch()` 对 `res.code !in 200..299` 直接 `Result.failure`；并且 body 非 JSON（`errorMessage` 为 null 且 `dataArray` 也解析不出）时同样视为失败而非空成功。

### M2 快速切换楼栋的竞态：后完成的旧响应覆盖新选择

**位置**：`FreeRoomViewModel.kt:93-100, 142-152`

`selectBuilding` 每次点击都 `launch` 新协程，既不取消前一个，回写结果前也不校验楼栋。快速点 A→B：若 A 的响应比 B 慢，最终 `slots` 是 **A 的数据 + B 的选中态**。`refresh()` 与切楼并发时同理（last-writer-wins）。

**修复**（最小改动）：`loadRooms` 回写前加守卫：

```kotlin
val slots = repo.loadFreeRooms(buildingId)
if (buildingId != _state.value.selectedBuildingId) return@launch  // ← 加这一行
```

更彻底的做法是持有当前 Job，新选择时 `cancel()` 旧请求（省流量，也消除 refresh 与切楼的并发）。

---

## 4. 🟡 低危 / 体验

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| L1 | `FreeRoomScreen.kt:77-83` | 切楼栋时 `!state.hasData` 分支未排除 `refreshing`，加载中闪现"当前没有查询到无课教室"空态文案 | 条件改 `!state.hasData && !state.refreshing`，加载中显示进度圈 |
| L2 | `GradesScreen.kt:125-129` | 首次使用（从未抓取成绩）时 `showWebView=true` 但 WebView 因默认段是无课教室而不渲染，**顶栏刷新按钮却被隐藏**；切到成绩段时 WebView"突然"弹出 | 显示条件改 `!state.showWebView \|\| state.section == FREE_ROOM` |
| L3 | `GradesScreen.kt` 顶栏 | **（截图 2 场景）** 在无课教室段，顶栏刷新图标打开的却是"重新抓取成绩"确认弹窗——图标语义像刷新当前页，用户预期是刷新空教室 | 无课教室段顶栏改为"刷新空教室"（直连 `FreeRoomViewModel.refresh`），"重新抓取"入口移进成绩/考试段 |
| L4 | `SmartClassApi.kt:121-123` 等处 | `catch (e: Exception)` / `runCatching` 吞掉 `CancellationException`，取消信号延迟到下个挂起点才生效 | catch 中 `if (e is CancellationException) throw e` |
| L5 | `FreeRoomScreen.kt:145-149` | key 为 `nodeId.ifBlank { nodeName }`：服务端若返回两个空 nodeId 且同名时段 → LazyColumn 重复 key **直接崩溃**；`state.slots.indexOf(slot)` 每项 O(n)，且 RoomSlot 为 data class，两行全等时 indexOf 恒指第一项 | 用 `itemsIndexed`，key 含 index |
| L6 | `FreeRoomRepository.kt:57-67` | 节次类型拿不到时静默 `return emptyList()`，接口故障被显示成"没有空教室" | 抛 `SmartClassException` 给出明确文案 |
| L7 | `FreeRoomViewModel.kt:165` | `friendly()` 的 `e.message!!.takeIf { it.isNotBlank() } ?: "…"` 在 else 分支永不触发，死代码 | 直接 `e.message!!` 或简化 when |
| L8 | 仓库 `withSignedRetry` | tokenRejected 重试只重取 key 不重校时钟（与第 2 节关联，修 H 后补） | 见第 2 节修复建议 3 |

## 5. ⚪ 工程整洁

- **测试文件位置与包声明不一致**：`app/src/test/java/com/caeamer/beikeschedule/SmartClassCryptoTest.kt`（及 ParserTest）文件在 `beikeschedule/` 目录下，包声明却是 `com.caeamer.beikeschedule.data.remote`。Kotlin 不强制目录=包所以能跑，但与项目其余 22 个测试"目录=包"的约定不符，IDE 导航会困惑。移文件即可。
- `FreeRoomViewModel` 里 `FreeRoomRepository(SmartClassKeyProvider(app, SmartClassApi()))` 与 Repository 默认参数各自 new 了一个 `SmartClassApi`——无状态类无碍，但共享一个实例更干净。

## 6. 安全简报

- 硬编码 AES Key/IV 与内置 `csrkKey`：均为**站点前端公开常量**（注释写明逆向自站点 JS），不涉及用户凭据，且 `configKeyFingerprint()` 用单测锁住防误改。风险≈0，可接受。
- 全程 HTTPS、无登录、无个人数据上行；`sign()` 失败降级为无签名请求会被服务端拒，且"验证不通过"不被 `isTokenRejected` 误判（单测专门断言了）→ 不触发无意义重试，设计合理。
- 手工拼 JSON 体有完整转义（`quote()`），`buildJsonBody` 只含服务端下发的 hex ID，无注入面。

## 7. 视觉与交互设计审查（基于两张截图）

### 做得好的

- 信息架构清晰：三段式 Tab → 楼栋 chips → 6 大节卡片，层级准确；折叠态头部"N 间空教室"摘要扫视效率高。
- 空座率 ≥50% 主题色高亮，一眼挑出最空的教室；`noSeatRate` 无数据显示 `--` 而非 0%，细节到位。
- 弹窗文案交代了后果（"本地数据会保留到抓取成功"），降低用户顾虑。

### 问题与建议（按优先级）

| # | 问题（截图证据） | 建议 |
|---|---|---|
| V1 | **顶栏刷新语义错位**（截图 2）：在无课教室页点刷新，弹出的是"重新抓取成绩/GPA/考试"确认框——图标看起来是刷新当前页 | 同 L3：分段感知的顶栏动作 |
| V2 | **切楼栋无加载反馈**：列表先清空再闪空态文案 | 同 L1；或旧数据降透明度 + 顶部细进度条 |
| V3 | **默认展开策略在晚间失效**（截图 1）：21:49 所有大节已结束，默认展开的是已过期的第一大节；两个大节之间的间隙同理（展开"上一个"而非"下一个"） | `currentSlotIndex` 改为"当前**或下一个即将开始**的时段"；全天结束后可收起全部并提示 |
| V4 | **"274 座 100% 空座"有歧义**：274 是总座位数，易误读为"274 个空位" | 改"274 座 · 空座率 100%"，或直接给空座数（seatCount × noSeatRate） |
| V5 | **0% 空座的教室仍按"空教室"列出**（数据滞后时满座教室混入） | 空座率 0 的排列表尾/弱化显示 |
| V6 | **展开区过长**（截图 2：24 间一列到底） | 展开区限高（约 8 行）+"查看全部"，或两列网格 |
| V7 | **无障碍**：楼栋 chips 与时段卡片是裸 `clickable`，TalkBack 读不出"已选中/已展开" | `Modifier.selectable(selected=…, role=Role.Tab)`；卡片加 `Role.Expandable` + `stateDescription`；行级 `onClickLabel`，图标 desc 置 null |
| V8 | 小项：SegmentedButton 的 ✓ 使三段窄屏偏挤，可去 icon 只留颜色区分；列表无"更新于 HH:mm"数据新鲜度提示；楼栋 chips 选中项无自动滚动（截图 2 左缘被裁半个"楼"字即为痕迹） | 按需采纳 |

## 8. 行动清单（按序）

1. 🔴 修 `syncClock`（第 2 节三处配套）+ 真机加"时钟调慢 5 分钟"用例
2. 🟠 `fetch()` 校验 HTTP 状态码与 body 形态（M1）
3. 🟠 `loadRooms` 回写前校验 buildingId（M2，一行）
4. 🟡 L1/L2/L3（三个都是几行的小改，L3 需要一点交互决策）
5. 🟡 L5（重复 key 崩溃防御）
6. 设计侧：V3（默认展开"下一个"）与 V4（座位数语义）性价比最高

## 9. 已确认正确、不要动的部分

- `genToken` 逐位取字符算法与 13 位长度保证、key 前缀 10 位特性（单测手算对照锁定）
- `noSeatRate` 的 `isNull` 先判 + `coerceIn(0.0, 1.0)` 防御
- `RoomNameOrder` 的前导零归一化与十进制串比较（无 Long 溢出面）
- key 三层兜底与 `forceRefresh` 的边界（forceRefresh 时也允许回落缓存，注释解释了为什么）
- `expandedIndex` 的三态设计（null=跟随时间 / -1=全部收起 / n=手动指定）及 `toggleSlot` 的边界
- 教务 Tab 用 ordinal 持久化分段的取舍论证（KDoc 已写明反对字符串的理由）
