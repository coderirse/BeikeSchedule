# 贝壳课表 代码审查报告

- **审查时间**：2026-08
- **审查对象**：`BeikeSchedule` 工作树（versionCode 30 / v1.1.11）
- **审查范围**：59 个主源码文件、20 个测试文件（149 个测试）、Gradle/Manifest 配置、注入 JS、备份规则、真实 fixture 数据
- **审查方式**：通读核心逻辑 + 3 个并行深读域（成绩/学分域、导入/解析域、课表 UI 域），关键结论逐条回原始代码复核
- **改动情况**：**未修改任何文件**（纯审查）
- **约束前提**：不破坏已发布数据（落盘结构变更需带迁移）；个人/小范围分发，国产 ROM 为主要场景

标记说明：**✅** = 已亲自复核原始代码或真实 fixture；其余为深读结论（附手算/实测过程）。文中凡涉及"更正我自己的判断"处均已标注。

---

## 目录

1. [总体结论](#1-总体结论)
2. [系统性问题：fixture 与真实数据形态不一致](#2-系统性问题fixture-与真实数据形态不一致)
3. [🔴 必须修：正确性](#3--必须修正确性)
4. [🟠 性能](#4--性能)
5. [🟡 架构 / 工程配置 / 体验](#5--架构--工程配置--体验)
6. [⚠️ 测试盲区](#6-️-测试盲区)
7. [安全简报](#7-安全简报)
8. [文档与代码不一致清单](#8-文档与代码不一致清单)
9. [最终行动清单](#9-最终行动清单)
10. [已确认正确、不要动的部分](#10-已确认正确不要动的部分)
11. [待决策问题](#11-待决策问题)

---

## 1. 总体结论

这个项目的**注释质量是同类个人项目中最好的**：`ReminderAlarmScheduler` 的三条不变量、`ScheduleUiState.scheduledCourses` 为什么用 `val` 而非 `get()`、`CourseMerger` 的单双周拆行分析、`JwParser` 关于 `JsonNull.content` 返回字面量 `"null"` 的说明——全部准确且解释了"为什么"，不是复述代码。149 个测试、纯函数抽出、真实 session fixture、备份规则显式排除 `app_webview`——这些都是刻意做对的。

但审查暴露出**一个系统性问题**，它比任何单个 bug 都重要（见第 2 节）。

本次共发现：**正确性缺陷 12 类**（含 4 类用户可直接感知的数据丢失/计算错误）、**性能问题 10 项**、**架构与工程配置 14 项**、**测试盲区 9 项**。其中**绝大多数不需要数据迁移**即可修复。

---

## 2. 系统性问题：fixture 与真实数据形态不一致

> **测试 fixture 的形态与真实数据不一致，导致最实的三类 bug 从原理上无法被测出。**

三个实例，全部经实证：

| # | fixture | 真实数据 | 漏掉的 bug |
|---|---|---|---|
| 1 | `app/src/test/resources/grcjcx-all.json`：54 行成绩**全是"正考"，零条补考/重修** | 真实成绩单里补考/重修是常见情形 | C3 三处重复计算（加权/未通过计数/学分进度） |
| 2 | `CourseMergerTest.kt:31` 用裸 `"-"` | 实测 `assets/sample/courses.json` 里 **5 行的地点是 `【校本部】-`** | C8 地点兜底逻辑是死代码，卡片显示假地点 |
| 3 | `CurrentWeekTest` 只测远端越界日期 | — | C6 开学前 1~6 天误排提醒/误显图钉 |

**修复 fixture 的形态，比修任何单个 bug 的长期收益都高。**

建议的 fixture 改造（一次就锁死多个 bug）：

- 给三个计算器各加"同 `kcdm` 两行（正考挂 + 补考过）"
- 给 `CourseMerger` 加 `【校本部】-`（替换裸 `-`）
- 给 `parseSectionTimes` 加一个坏元素
- 给 `CurrentWeekTest` 加开学前 1~6 天
- 加 `parseGpa` 缺 `PM`/`ZRS` 的用例
- 加 `assignImportColors` 色板耗尽（>10 门不同课程）

---

## 3. 🔴 必须修：正确性

**本节全部不需要数据迁移。** 按严重度排序。

### C1. ✅ 隐藏/删除只作用于一行，而卡片是"合并组"

`ScheduleScreen.kt:362,366`：

```kotlin
onDelete = { viewModel.deleteCourse(course.id); ... }
onHide   = { viewModel.setCourseHidden(course.id, true); ... }
```

`course` 来自 `CourseMerger.mergeSameSlot`，其 `id` 是**基准行的 id**——而合并组可能有 N 行（教务单双周/调课拆行、手动课同天同节次多时段）。

**用户可复现**：对一门被拆行的课程点"隐藏" → **卡片原样还在**（只隐藏了 N 行里的 1 行）→ 用户看到的是"隐藏功能坏了"。删除同理。

对比编辑路径是**对的**（`ScheduleScreen.kt:356-358` 按 name+source 取全部行），所以这是"三个操作里两个漏了"。

**修法**：与编辑路径一致地解析组：

```kotlin
val group = state.courses.filter { it.name == course.name && it.source == course.source }
group.forEach { viewModel.setCourseHidden(it.id, true) }               // 隐藏
viewModel.saveCourses(emptyList(), replaceIds = group.map { it.id })   // 删除
```

顺带考虑把 `source` 加进 `CourseMerger.SlotKey`（`CourseMerger.kt:17`）——目前它只用 `name+day+start+end`，理论上两门不同课程同名同段会被错误合并，与类文档第 9 行的承诺不符。现有 fixture 里无此配对，属潜在而非现存。

**附带**：`CourseDetailSheet.kt:61` 的 `"一二三四五六日"[course.dayOfWeek - 1]` 是无守卫下标，而 `JwParser.parseDayOfWeek` 用 `Regex("^xq(\\d)_jc\\d+$")` 接受 1..9。`xq8`/`xq9` 会让详情页抛 `IndexOutOfBoundsException`（课表网格只画 7 天所以不显示，但详情页会崩）。改 `getOrNull(course.dayOfWeek - 1)`。

---

### C2. ✅ 成绩隐私开关被三处绕过

**(a) 加权成绩的课程勾选列表明文打印分数** ✅ `GradesScreen.kt:787`：

```kotlin
state.weightEligible.forEach { (grade, included) ->
    Text("${grade.xnxqmc} · ${grade.xf}学分 · ${grade.zzcj}分", ...)   // 无 hideScores 判断
```

对比同文件 `:681` 和 `:821` 都正确掩码。用户点小眼睛隐藏后，展开勾选列表 → 所有分数原样显示。

**(b) 专业排名与学分数未掩码** ✅ `GradesScreen.kt:719-721`。

**(c) 最近任务缩略图**：`MainActivity.kt:119-122` 在 `onStop` 复位，但 Recents 缩略图取**最后一帧已绘制画面**，而 Compose 在 `onStop` 后不保证再绘帧 → 缩略图里分数可见。另外**分屏**下本 Activity 只到 `STARTED`，`onStop` 不触发，完全不复位。

**修法**：(a)(b) 补掩码；(c) 显示期间用 `setRecentsScreenshotEnabled(false)`（`minSdk 34` 可用）——复位逻辑只是第二道防线，堵不住缩略图。

**结构性建议**：在 `GradesUiState` 做统一的"隐私投影"（`Hidden` / `Visible(value)` 类型），让所有需要分数的组件**必须**显式解包。当前"每个组件自己记得判断 `hideScores`"的模式已经漏了三个地方，迟早还会漏第四个。

---

### C3. ✅ 补考/重修的三处重复计算（同一根因）

子代理手算，代码路径经复核：

> 课程 `kcdm=1060122`，3 学分，必修，正考 55（挂）+ 补考 75。
>
> - **加权**：`Σ(score·xf) = 55×3 + 75×3 = 390`，`Σxf = 6` → **65.00 分**，UI 显示"纳入 2 门 · 共 6.0 学分"（`GradesScreen.kt:713`）
> - **GPA**（有去重）：`gradePoint(75)=3.0`，**3 学分，1 门**
> - 同一门课，两个口径给出 `65分/6学分/2门` 与 `3.0/3学分/1门`

三个可见后果：

| 后果 | 位置 | 说明 |
|---|---|---|
| 加权成绩重复计算 | `WeightedScoreCalculator.kt:21-31` | 无去重；`GradeTriple`（`:34`）连 `kcdm` 都没有，所以计算器内部无法去重 |
| "N 门未通过"永不消失 | `GradesViewModel.kt:68-69` | 按**行**计数；正考挂科行在补考通过后依然存在 → 学期组头永远显示"1 门未通过"，分数永远红色。**直接违反项目自己的设计文档** `docs/FEATURES_V1.1_DESIGN.md:162`（"补考通过后自然消失"） |
| 学分进度重复计学分 | `CreditAggregator.kt:14-17` | 对每个通过行求和 → 同一门课 4 学分重修通过后计入 **8.0**，该类别进度条可能**超过它上面显示的"毕业总进度-已修学分"** |
| 勾选列表重复显示 | `GradesScreen.kt:775-793` | 同一门课出现两次，共享一个 `kcdm` 复选框 |

**修法**：抽共用去重函数，三条路径都过它：

```kotlin
// data/repo/GradeRows.kt
fun bestPerCourse(grades: List<GradeEntity>): List<GradeEntity> =
    grades.groupBy { it.kcdm }.map { (_, rows) ->
        val retakes = rows.filter { it.bkcx.isNotBlank() && it.bkcx != "正考" }
        (retakes.ifEmpty { rows }).maxByOrNull { it.numericScore ?: Double.NEGATIVE_INFINITY }
            ?: rows.first()
    }
```

同时把排除机制**从下标改成 `kcdm`**：`GradesViewModel.kt:139-141` 用 `mapIndexedNotNull` 生成下标集合传给计算器，计算器内部再 filter 一遍同一列表——这是"用位置当身份"，任何一侧顺序变化就静默排除错的课。`GradeTriple` 带上 `kcdm` 即可消除。

`WeightedScoreCalculatorTest.kt:53-64` 用的是位置下标，需同步更新。

---

### C4. ✅ 重新导入摧毁隐藏状态**和用户的编辑**

`ScheduleRepository.kt:50-51`：

```kotlin
courseDao.deleteBySource(CourseEntity.SOURCE_IMPORT)
courseDao.insertAll(assignImportColors(courses))
```

- `deleteBySource` 无 `hidden` 过滤（`CourseDao.kt:40-41`）→ 隐藏行一并删除
- 重新插入的行取 `CourseEntity` 默认 `hidden = false`（`CourseEntity.kt:23`），`assignImportColors`（`:108-145`）只拷贝 `colorIndex`

**比"仅丢隐藏状态"更严重的是**：`CourseRowBuilder.kt:38,61-67` 显示**编辑过的导入课仍带 `SOURCE_IMPORT`**（`template?.copy(...)`，新时段用 `defaultSource = SOURCE_IMPORT`）。所以重新导入会**抹掉用户对导入课的所有修改**——改过的名字、修正过的地点、调整过的周次，全部回退。

预览页（`ImportScreen.kt:186-191`）只有一句"覆盖已有的教务导入数据"，**没有说明编辑也会丢**。而 `CourseDetailSheet.kt:83-84` 的注释"下次导入会原样回来"说明作者意识到这个往返，但 `hidden` 标志没被纳入往返。

**修法**（同事务内保留）：

```kotlin
suspend fun replaceImportedData(courses: List<CourseEntity>, sectionTimes: List<SectionTimeEntity>) =
    db.withTransaction {
        val hiddenBefore = courseDao.getBySource(CourseEntity.SOURCE_IMPORT)
            .filter { it.hidden }
            .map { it.taskId to it.name }            // 或更稳的键
        courseDao.deleteBySource(CourseEntity.SOURCE_IMPORT)
        courseDao.insertAll(
            assignImportColors(courses).map { c ->
                if (hiddenBefore.any { it.first == c.taskId && it.second == c.name }) c.copy(hidden = true)
                else c
            },
        )
        sectionTimeDao.clear()
        sectionTimeDao.insertAll(sectionTimes)
    }
```

用户编辑的处理有两种口径，**需决策**（见第 11 节）：要么禁止编辑导入课，要么引入 `SOURCE_IMPORT_EDITED` 使其免于清空，并在预览页显示"自定义修改 N 门将保留"。**最低限度**要在预览页明说会丢什么。

---

### C5. ✅ 课表头部与 Pager 会失步

`ScheduleViewModel.kt:150-155` 用 `selectedWeek.value == 1` 当"用户还没选过"的哨兵，而该文件自己的注释（`:156-159`）就说明了**任何 DataStore 写入都会让 `semester` 流重发射**。

**用户可复现**：

> 滑到第 1 周 → 点进学期设置 → 切换"隐藏周末"并保存
> → `selectedWeek` 被改成当前周（如第 8 周）
> → 但 `ScheduleScreen.kt:182` 的 `LaunchedEffect(state.currentWeek)` 只以 `currentWeek` 为键，**Pager 不会跟着跳**
> → **头部显示"第 8 周"，网格里是第 1 周的卡片和日期**

**修法**：`selectedWeek` 改成可空（`null` = 未设置），用显式标志替代 `== 1` 哨兵；Pager 由 `state.selectedWeek` 驱动而非 `currentWeek`。

**相关**：`ScheduleScreen.kt:172-175` 的 `initialPage` 用 `currentWeek` 而非 `selectedWeek` → 在第 5 周旋转屏幕会被甩到第 8 周。另外本层**没有任何 `rememberSaveable`**（只有 `MainActivity.kt:136-137` 用），旋转会丢失对话框与填了一半的表单。

---

### C6. ✅ 开学前 6 天会误排提醒 + 误显"下一节课"图钉

`ScheduleRepository.kt:159`：

```kotlin
val week = (ChronoUnit.DAYS.between(monday, today) / 7 + 1).toInt()
```

`Int` 除法向零截断：开学前 1~6 天时 `DAYS.between` 为 `-3`，`-3/7 == 0`，`0+1 == 1` → **返回"第 1 周"**。

此路径只在 `weekMondays` 为空（校历抓取失败）时走。后果：

- `ClassReminderScheduler.kt:158-163` 的 `teachingWeekOf` 同样兜底到这里 → **开学前 6 天就给第 1 周的课排提醒**
- `ScheduleScreen.kt:107-112` 同款逻辑 → "下一节课"图钉提前点亮

`CurrentWeekTest` 只测远端越界日期，这个窗口未覆盖。

**修法**：`if (today.isBefore(monday)) return null`，或改用 `Math.floorDiv`。

**顺带**：这段逻辑在项目里**存在三份拷贝**（`ScheduleRepository.kt`、`ClassReminderScheduler.kt:158-163`、`ScheduleScreen.kt:107-112`），应统一成一处。

---

### C7. ✅ 抓取成功后"手动抓取"永久失效

`jw_import.js` 的重入保护标志**只在错误路径复位**：

```javascript
// jw_import.js:12-13
if (window.__beikeRunning) return;
window.__beikeRunning = true;
// jw_import.js:93-96 —— 只有 catch 复位；成功路径(:89) 不复位
```

> ⚠️ **更正**：早先"Import 页还有手动抓取按钮可以补救"的说法**只在首次失败时成立**。
> 抓到课表 → 进预览 → 点"重新抓取" → 再点"手动抓取" → **静默无反应**。

对比 `jw_grades.js:68` 成功路径**有**复位，两套脚本行为不一致，说明是漏改。

叠加 `ImportScreen.kt:77-79` 的 `webView?.`（null 时静默无操作，而 `loadUrl` 在 `onCreated` 之前调用，见 `JwWebView.kt:151-152`），**导入页失败后可能完全无法重试，只能退出重进。**

**修法**：`jw_import.js:89` 在 `window.BeikeImport.onResult(...)` 之前加 `window.__beikeRunning = false;`；`runScript` 里 `webView ?: run { onError("页面尚未就绪，请稍候"); return }`。

**同类死代码**：`ImportViewModel.onFetchStart()`（`:51`）在整个 `app/src` **零调用**，`ImportUiState.Fetching` 是死状态，`ImportScreen.kt:112` 的进度条分支永不成立。而 `jw_import.js:43-57` 的兜底路径最多发 25 次顺序请求——用户全程看到的是静止的 WebView。**修法**：在 `runScript` 开头调 `viewModel.onFetchStart()`（对齐 `GradesScreen.kt:237`）。

---

### C8. ✅ `CourseMerger` 的地点在真实数据上是死代码

`CourseMerger.kt:21-22`：

```kotlin
// 基准行取地点信息最完整的（调课行地点常为"-"）
val base = rows.firstOrNull { it.location.isNotBlank() && it.location != "-" } ?: rows.first()
```

实测 `assets/sample/courses.json`（32 行 `SKSJ`）：**5 行的地点行是 `【校本部】-`**——既非空白也不等于裸 `-`，所以这个判断一行都拦不住。

**可复现后果**：`机电传动控制` 的两个第 8 周行（`KEY=xq3_jc1` / `xq3_jc2`，均 `KSJC=1/JSJC=4`）合并后，卡片显示的是**幽灵地点 `【校本部】-`**，而其他行保留 `机械楼720`。

`CourseMergerTest.kt:31` 用裸 `"-"` 作 fixture，**这正是 bug 通过 CI 的原因**。

**修法**：

```kotlin
private fun plausible(loc: String) =
    loc.substringAfter("】", loc).trim().let { it.isNotEmpty() && it != "-" }
val base = rows.firstOrNull { plausible(it.location) } ?: rows.first()
```

**顺带**：`ReminderReceiver.kt:53` 也在剥 `【…】` 前缀（`Regex("【[^】]*】")`），剥完会得到 `"-"` 显示在通知里。两处应共用一个 helper。

---

### C9. ✅ 考试缓存会被静默清空 + 提醒漏传 `cancelDueAlarms`

**(a) 数据侧** — `jw_grades.js:62-65` 的考试请求有 `.catch(() => '')`，而 `GradesViewModel.kt:262-263` **无条件**：

```kotlin
val exams = ExamsParser.parseExams(examsJson, semXn + semXq)
repo.replaceExams(exams)      // examsJson 为 '' → 解析出空表 → clear() 清库
```

成绩侧有守卫（`:250` `if (grades.isEmpty()) 报错并 return`），学业进度侧也有（`:265-267`），**考试侧没有**。一次子请求失败就会清空已有考试安排与考前提醒。`GradesViewModel.kt:268-272` 的注释修掉了**闹钟**侧，漏了**数据**侧。

**修法**：让 JS 用哨兵值区分"请求失败"与"真的没有考试"（如返回 `'__FAILED__'`），失败时跳过 `replaceExams`。

**(b) 闹钟侧** — `ExamReminderScheduler.kt:63-75` 的 `ReminderAlarmScheduler.apply(...)` **没传 `cancelDueAlarms`**（对比 `ClassReminderScheduler.kt:109` 传了 `!enabled`）。所以 `clearGradesCache()` 清完考试后，那条"已到点未投递"的考试闹钟仍会带着过期地点和座位号弹出——这正是注释里说要不惜代价避免的场景，但 `apply` 的默认值 `false` 让保证失效了。

**修法**：`ExamReminderScheduler.reschedule(context, cancelDueAlarms: Boolean = false)`，在"考试数据被显式清空"的调用点传 `true`。

---

### C10. ✅ 一个缺失的排名会丢弃整个 GPA 概览

`GradesParser.kt:68-72` 全是 `?: return null` 链：

```kotlin
val gpa = num("BL") ?: return null
val earned = num("HDXF") ?: return null
val passed = num("TGKC")?.toInt() ?: return null
val rank = num("PM")?.toInt() ?: return null       // ← 任一缺失 → GpaInfo = null
val total = num("ZRS")?.toInt() ?: return null
```

`PM`/`ZRS`（专业排名/总人数）恰是最可能缺失的字段（未排名专业、第一学期、转专业）。学期初排名未生成时，`BL`（平均学分绩）与 `HDXF`（已获学分）明明有值，却因排名缺失整体返回 null → **GPA 卡片变成"—"，且无任何解释**。

这与"排名 `7/0` 无守卫"（`GradesScreen.kt:719`，`GradesParser.kt:72` 接受 `ZRS=0`）是同一字段的两端：一边缺字段就全丢，一边零值照显。

**修法**：只要求 `BL`/`HDXF`；`rank`/`totalStudents` 改 `Int?`，UI 在缺排名时隐藏排名行、保留绩点与学分；`totalStudents > 0` 才显示排名。

---

### C11. ✅ `queryzclist` 解析错误，可能导致校历周数长期走退化路径

`jw_import.js:71-75`：

```javascript
try {
    zcList = (JSON.parse(rs[4]) || [])
        .map(function (e) { return e.ZC; })      // ← 包装响应下 .map 不是函数 → 抛异常
        .filter(function (z) { return z >= 1 && z <= 90; });
} catch (e) { /* 周次列表异常时由校历自行推断 */ }   // ← 被静默吞掉，zcList 恒为 []
```

而 `docs/JWXT_API.md:30` 明确写该接口返回**包装 `content:[{ZC}]`** 周次列表（1-18+99）。所以 `zcList` **恒为空**，连锁后果：

1. `calendarByWeekLoop` 退化成硬编码的 **25 周顺序请求**（`jw_import.js:79-81`），而真实范围是 1-18 + 99 假期 → 兜底路径要发 25 次请求
2. `totalWeeks = max(0, weeks.length, 16)` → 取到校历实际周数（探针 18；文档示例同为 18），而 `ImportViewModel.kt:91` 的兜底是 20 —— **两边都是魔法数且不一致**
3. **下游全部跟着这个值走**：
   - `CourseEditDialog` 的周次 chips 只渲染 `1..totalWeeks` → **19、20 周及以后的课无法通过编辑界面调整**
   - `SemesterSettingsDialog.kt:99-104` 的总周数下拉只列 `{16,18,20,22,25}` → 非枚举值渲染空白（18 恰好命中所以目前不显形）
   - `SessionExpander.buildWeekBitmap(weeks, totalWeeks)` → 编辑时静默截断超出周次

**这一条把"下拉渲染空白"和"编辑静默丢周"两个症状串到了同一个根因上**，也意味着 README 的招牌功能"官方教学周日历"的**周次总数**很可能一直来自退化路径。

**修法**（一行）：

```javascript
var raw = JSON.parse(rs[4]);
var arr = Array.isArray(raw) ? raw : (raw && raw.content) || [];
zcList = arr.map(function (e) { return e.ZC; }).filter(function (z) { return z >= 1 && z <= 90; });
// 99 = 假期，必须排除
```

**相关**：`ImportViewModel.kt:91` 的 `?: 20` 与 `jw_import.js:86` 的 `, 16` 是两处魔法数，应统一为具名常量，且与课程位图的实际最大周次取 `max`。

---

### C12. ✅ 导入流程：非原子、无守卫、可双击

`ImportViewModel.kt:109-127`：

```kotlin
fun confirmImport(onDone: () -> Unit) {
    val preview = _state.value as? ImportUiState.Preview ?: return
    viewModelScope.launch {
        repo.replaceImportedData(preview.courses, preview.sectionTimes)   // Room 事务 A
        repo.clearSampleData()                                            // 事务 B
        val previous = repo.settings.semester.first()
        repo.settings.saveSemester(previous.copy(...))                    // DataStore 写入 C
        onDone()
    }
}
```

三处问题：

1. **非原子**：若在 A 与 C 之间进程被杀，DB 里是新学期的课程，而 `firstMonday`/`totalWeeks`/`weekMondays` 仍描述旧学期 → `locateWeek`/`currentWeek` 把整张课表放到错误的周次上
2. **无异常处理**：任何异常（磁盘满、Room 错误、DataStore IOException）逃出 `viewModelScope.launch`，无 `CoroutineExceptionHandler` → **崩溃**，且 `onDone()` 不执行
3. **可双击**：`_state` 在 `onDone()` 前保持 `Preview`（`:110`），`ImportScreen.kt:196` 的按钮不禁用 → 双击启动两次并发导入，第二次会清空并重插第一次的行，与 `clearSampleData`/`saveSemester` 交错

**修法**：引入 `ImportUiState.Committing`；`confirmImport` 首句置该状态；按钮 `enabled = state is Preview`；`try/catch` 包住并落到 `ImportUiState.Error("保存失败：…")`；把 `replaceImportedData` + `clearSampleData` 合成一个 Room 事务（两者都是 Room），`saveSemester` 因跨存储无法原子，但应**先写 DataStore 再执行破坏性 DB 写入**，或用 `pendingImport` 标记在下次启动时补完。

---

## 4. 🟠 性能

### ⚠️ 更正：lambda 稳定性不是本项目的问题

早期判断曾把"三个页面的 lambda 不稳定导致整屏无法跳过重组"列为性能问题，并建议用 `remember(viewModel) { { … } }` 包住所有回调。

**这是错的**：Kotlin 2.2.10 的 Compose 强跳过（strong skipping）已自动 memoize 不捕获不稳定值的 lambda，因此这类包装在本项目里**不构成重组屏障**，该建议作废。

真正成立的是**派生状态重算**（下条）。

---

### P1. ✅ `GradesUiState` 的派生状态零缓存，每次读取全量重算

`GradesViewModel.kt:62-152` 有 **11 个 `get()` 属性，没有一个缓存**：`localGpa` / `grouped` / `failedBySemester` / `localCategorySums` / `creditRows` / `examsSorted` / `semesters` / `schoolYears` / `semestersOfSchoolYear` / `weightedResult` / `weightEligible`。

实测单次 `ScoreCard` 组合的读取次数：

| 属性 | 读取次数 | 位置 |
|---|---|---|
| `weightedResult` | **2** | `GradesScreen.kt:682, 711` |
| `weightEligible` | **3** | `:741, 755`（count + size） |
| `localGpa` | **3**（GPA 模式） | `:683, 718, 721` |
| `schoolYears` | 1（但**每次调用重新编译 `Regex`**） | `:663` → `GradesViewModel.kt:97` |
| `semestersOfSchoolYear` | 1（内部重建 `semesters` 的 distinct + sort） | `:667` |

最严重的是 `failedBySemester` 在**每个学期分组项内部**读取（`GradesScreen.kt:290`）：

```kotlin
state.grouped.forEach { (semester, grades) ->
    item(key = "header_$semester") {
        val failed = state.failedBySemester[semester] ?: 0    // ← 每个学期头都重算整个 Map
```

→ **学期数 × 全量 filter + groupBy**。300 门成绩 8 个学期 = 8 次全表遍历。且第一个 `item` 没有 key，滚出屏幕再滚回来会重新走一遍。

**最小改动修法**（保留现有 API）：

```kotlin
val grouped: List<Pair<String, List<GradeEntity>>> by lazy(LazyThreadSafetyMode.NONE) { ... }
```

**更好的修法**：把所有派生值搬进 `combine` 的 transform 里一次性算好（照抄 `ScheduleUiState.scheduledCourses` 用 `val` 而非 `get()` 的写法——那个文件已经懂这个道理了，成绩页没跟上）。这也**让这一层第一次变得可测**——目前没有 `GradesViewModel` 的测试文件。

**顺带**：`ScoreCard` 是 178 行的 god-composable（`GradesScreen.kt:622-799`），建议拆成 `ScoreModeSwitch` / `ScoreValueRow` / `ScoreSubtitle` / `CourseSelector`。

---

### P2. ✅ 每分钟无界 ticker + 无生命周期感知的收集

`ScheduleScreen.kt:152-159`：

```kotlin
val now by remember {
    flow { while (true) { emit(java.time.LocalDateTime.now()); delay(60_000) } }
}.collectAsState(initial = java.time.LocalDateTime.now())
```

- `while(true)` **永不停止**
- `now` 变化 → **整个 `ScheduleScreen` 重组**（含 Pager 全部 7 列 × 6 大节）
- 而它实际只喂给 `nextClassId`（`:164-171` 的 `remember` 键）

配合 6 个 `collectAsState`（`:121-126`）：`collectAsState` 不感知 `STOPPED`，**App 退到后台后屏幕仍每分钟重组**，`SharingStarted.WhileSubscribed(5000)` 也永远等不到订阅者离开。

**修法**：加 `lifecycle-runtime-compose` 依赖（版本目录里 `lifecycleRuntimeKtx = "2.11.0"` 已有，补一个 library 条目即可），UI 层换 `collectAsStateWithLifecycle`；ticker 改 `produceState` 且只在"下一节课"那个小组件里读 `now`。

**同类（相反极端）**：`GradesScreen.kt:473` 的 `LocalDate.now()` 在**组合期**读取，喂 D-N 倒计时徽标与"已考过变暗"判断 → **跨午夜不刷新**。App 挂前台过午夜，考试倒计时不变；从后台长期返回也不重算。考试倒计时错一天，对"明天考试"这种场景是实打实的误导。

**同类**：`ScheduleScreen.kt:328` 的 `teachingWeekOf` 每次组合重新解析最多 25 个日期（而 `:164-171` 每分钟已算 2 次）。

---

### P3. 其余性能项

| 项 | 位置 | 说明 |
|---|---|---|
| ✅ 闹钟重排在主线程做 N 次 binder 调用 | `ScheduleViewModel.kt:160-171` + `ReminderAlarmScheduler.kt:84-97` | `NonCancellable` **不改变调度器**，仍在 Main。每次编辑课程都会卡 |
| ✅ 全项目零索引 | `data/local/*Entity.kt` | `course` / `grade` / `exam` 都没有 `@Index` |
| `combine` 每次滑动重跑 `locateWeek` + 4 次列表过滤 | `ScheduleViewModel.kt:71-90` | 周次滑动时逐帧执行 |
| `Regex("【[^】]*】")` 每卡片每重组重新编译 | `ScheduleScreen.kt:687` | 提到 top-level `val` |
| `consumeAllScroll()` 每次重组新对象 | `GradesScreen.kt:93-101` | 且无条件吃掉内层列表到边后的剩余滚动 → **死滚动区**（`:389`, `:772`）。提到 `remember`，且只在内层到边时消费 |
| 主线程 JSON 解析 | `GradesViewModel.kt:212-213, 247`；`ImportViewModel.kt:56-98` | 量小但 `flowOn(Dispatchers.Default)` 是免费的 |
| `loadAssetScript` 主线程读 assets | `ImportScreen.kt:78`、`GradesScreen.kt:238` | 每次注入都读（约 4KB），可 `by lazy` 缓存 |
| `items()` 未设 `contentType` | `GradesScreen.kt:310, 506` | 便宜的复用优化 |
| `fmt2` 在组合内 `String.format` | `GradesScreen.kt:361, 433, 598, 682-683, 721` | 非重复定义（模块内唯一的 `"%.2f"`），但每次分配 |
| 死代码 | `GradeDao.count()`、`ExamDao.getAll()`、`observeCourseByName` 链、`CourseColors.userPalette`、`defaultColorIndex` | `observeCourseByName` **零 UI 调用点**（UI 在 `ScheduleScreen.kt:356` 内存过滤） |

**潜在陷阱**：`CourseDao.kt:19` 的 `name LIKE :name` 未转义 `%`/`_`，而返回的行会变成 `replaceCourses` 的 `deleteIds`（破坏性替换）。目前**不可达**（无调用点），但改 `name = :name` 或删除该方法可拆掉这颗雷。

---

## 5. 🟡 架构 / 工程配置 / 体验

### 架构

| 项 | 位置 | 说明 |
|---|---|---|
| **提醒重排挂在 Tab 的 ViewModel 上** | `ScheduleViewModel.kt:160-171` | 这是唯一的重排入口（另一处是每日脉冲）。冷启动后用户直接点"教务"Tab → `MainActivity.kt:205-209` 的 `when` 不挂载 `ScheduleViewModel` → **那轮重排不发生**。这是"提醒偶尔不响"修过两次都没根治的根因。应抽 `ReminderCoordinator` 到 Application 级 |
| **分层反向** | `ScheduleRepository.kt:11` | 依赖 `ui.theme.CourseColors`。颜色分配是表现层关注点，却在数据仓库里 |
| **`ScheduleViewModel` 职责过载** | 同上 | 同时管课表 UI 状态、周次选择、学期配置读写、课程 CRUD、示例数据加载、**全局提醒重排** |
| KDoc 复制粘贴 | `ScheduleRepository.kt:63-69` | 是 `assignImportColors` 的注释，却贴在 `addManualCourse` 上 |
| UI 文案由 ViewModel 生成 | `GradesViewModel.kt:251, 276, 287` | 拼中文串 + 泄露 `e.message` 原文，无法本地化、无法测试。应改 sealed error 类型 |

### 工程配置

| 项 | 说明 |
|---|---|
| ✅ **没有 CI** | 149 个测试但**没有任何自动化在跑**（`.github/` 不存在，`testDebugUnitTest` 只写在 README 里）。无 ktlint / detekt / `.editorconfig`，`lintOptions` 未配置，`lint { checkDependencies }` 未开。**投入产出比最高的基建项** |
| ✅ **`app/schemas/` 只有 `3.json` 和 `4.json`** | `build.gradle.kts:57-60` 注释说"迁移可入库版本化，配合 MigrationTestHelper 可测"，**但缺 v1/v2 schema，1→2 与 2→3 迁移路径原理上无法测**，且 `androidTest/` 只有模板文件 |
| ✅ **无 `fallbackToDestructiveMigration`** | `AppDatabase.kt:72` 只 `addMigrations`。用户从 v1 直跳 v4 需三条迁移全成功，任一失败 → **启动即崩且无法自愈**。已选"不能破坏已发布数据"，这是保险丝 |
| `material-icons-extended = "1.7.8"` vs BOM `2026.02.01` | 该 artifact **不受 BOM 管理**，版本跨度大，为约 5 个图标引入上千个图标（APK 体积主因之一）。官方已停止随新版本发布，中期需规划迁移 |
| ✅ `strings.xml` 只有 `app_name` | 全部文案硬编码（约 70 处）。中文单语 App 是合理取舍，**不建议现在抽 string 资源**；但通知文案（`ReminderReceiver.kt:59, 83`）建议走资源（会被系统通知历史、无障碍服务、车机以不同 locale 渲染） |
| `minSdk 34` / `targetSdk 37` | 确认为有意决定。`USE_EXACT_ALARM` + `SCHEDULE_EXACT_ALARM` 双声明**正确**（前者安装即授予，后者兜底），注释亦写清原因 |

### 体验 / 无障碍

| 项 | 位置 | 说明 |
|---|---|---|
| ✅ **颜色选择器装不下手机** | `CourseEditDialog.kt:157-187` | **非换行非滚动**的 `Row`，10 × 32dp + 9 × 8dp = **392dp 固有宽度**，而 M3 AlertDialog 在 360dp 手机上内容区约 250-310dp → **约 4 个颜色被裁掉且无法触及**，且 32dp < 48dp 最小触摸目标。改 `FlowRow` 或 `horizontalScroll` |
| ✅ **FAB 压在网格上** | `ScheduleScreen.kt:279-288` | Scaffold padding 不含它 → 遮住最后一天最后一个大节 |
| `LazyColumn` 内的 `remember` 会被回收 | `GradesScreen.kt:343`（学分进度卡展开态）、`:630`（勾选列表展开态） | **展开后滚出屏幕再回来，展开状态丢失**。学分进度卡已有 `key = "credit_progress"`，改 `rememberSaveable` 即可 |
| 全层无 `rememberSaveable` | Schedule 全部文件 | 旋转丢失对话框与填了一半的表单 |
| 无守卫的显示 | `GradesScreen.kt:719` | 可能渲染 `专业排名 7/0` |
| 无障碍 | `ScheduleScreen.kt:566-593` 等 | 42 个无标签可点格子/周；"本周不上"仅用 alpha 区分（违反 WCAG 1.4.1）；今天仅用颜色标记；固定 58dp/36dp 在放大字体下裁切 |
| 无障碍：一个开关两个控件 | `ProfileScreen.kt:320-326` + `:461` | `Switch` 套在 `clickable` 行内 → TalkBack 报两个控件。应为 `Modifier.toggleable(value, role = Role.Switch, onValueChange)` + `Switch(onCheckedChange = null)`。功能没错（Switch 吃掉手势，不会双触发） |
| **"清除成绩缓存"文案低估范围** | `ProfileScreen.kt:256, 430` | 只说"删除本地成绩与 GPA"，实际 `SettingsViewModel.clearGradesCache()` **还清空考试安排与学业进度，并取消考试提醒** |
| 会话行缺少 `key` | `CourseEditDialog.kt:210` | 列表可变且在 `:221` `removeAt` → 删除一行后瞬态状态（下拉展开）错位 |
| 空位图膨胀 + 静默丢周 | `CourseEditDialog.kt:113, 123` | `weeks.ifEmpty { allWeeks }` 把空位图膨胀成"每周"；调低总周数会在保存时**静默丢弃超出周次**（chips 只渲染 `1..totalWeeks`）——真实数据丢失且无警告 |
| 周数校验缺失 | `SemesterSettingsDialog.kt:254-257` | 不校验 `totalWeeks >= weekMondays.size` → `currentWeek` 可能超过 `totalWeeks`，周次菜单里"（本周）"永不出现 |
| 下拉渲染空白 | `SemesterSettingsDialog.kt:99-104` + `CourseEditDialog.kt:361` | 总周数下拉对 `{16,18,20,22,25}` 之外的值渲染空串，而导入会写入校历自己的值（见 C11） |
| 死状态 | `ScheduleScreen.kt:141` | `editingCourse` **只被赋 null**，`:374` 的 `listOfNotNull(editingCourse)` 是死代码；FAB（`:281-285`）与空态按钮（`:295-298`）重置对话框输入的方式不对称 → FAB 配上残留 `editCourseGroup` 会以"替换模式"打开新课程框 |
| 删除无确认无撤销 | `CourseDetailSheet.kt:86-96` | — |
| 小学期筛选陷阱 | `GradesViewModel.kt:112-116` | 选"小学期"时返回**全部**学期，用户选中普通学期后结果为空、卡片显示"—"且无解释 |
| 学年筛选静默消失 | `GradesViewModel.kt:95-109` | 每次调用重新编译 `Regex`；若学期名不符合 `\d{4}-\d{4}-\d`，整个双列筛选器消失（`GradesScreen.kt:663`），尽管单学期筛选仍可用 |
| 排除计数与筛选不一致 | `GradesScreen.kt:714` | "已排除 N 门"用全部 `excludedKcdm.size`，但可见列表是筛选后的 |
| `Uri.parse(u.url)` 未校验 scheme | `ProfileScreen.kt:379` | 网络返回的 URL 直接给 `ACTION_VIEW`。加 `https://` 校验（对比 `openExternal` 的 `runCatching` 写得很好） |
| 重复代码 | `ProfileScreen.kt:213-350` | 7 行"尾随箭头"块重复 5 次；`"未找到可打开网页的应用"` 重复 4 次 |
| 过期注释 | `ProfileScreen.kt:69-70` | 注释说"上面 6-17 行已 import 过…原先这 12 行是重复粘贴"，但重复早已删除，行号对不上 |

---

## 6. ⚠️ 测试盲区

149 个测试覆盖纯函数逻辑（`WeekLayout`、`SessionExpander`、`NextClass`、解析器、提醒排期），质量确实高。但：

| 缺口 | 为什么重要 |
|---|---|
| **三份 fixture 与真实数据形态不一致** | 见第 2 节。直接导致 C3、C8、C6 三类 bug 不可见 |
| 无"某行解析失败被静默丢弃"的测试 | `JwParser.kt:32`、`GradesParser.kt:41`、`ExamsParser.kt:32` 逐行 `runCatching` → 一门课静默消失；而 `parseSectionTimes`（`:42-45`）**没有**逐元素保护 → 一个坏元素**中止整个导入**。宽容与严格并存，两边都没测 |
| 无 `{code:401}` 这类无 content 错误包装的测试 | 会话过期时 `JSON.parse` 抛 `SyntaxError`，用户看到"抓取失败：Unexpected token '<'" |
| 无 HTML 响应测试 | SSO 过期返回 HTTP 200 的 HTML 登录页（`jw_import.js:23` 不检查 `r.ok`） |
| `assignImportColors` 色板耗尽分支未测 | `ScheduleRepository.kt:128-133` 的兜底无条件覆盖 `usedColors`，第 11 门不同课程必然走到；注释说"理论上不可能"是比较对象搞错了（色板大小 vs **不同课程名**数） |
| `parseExamTime` 无非法值测试 | 只覆盖了"时间待定"；`2027-13-45 99:99~99:99` 会返回看似合法的三元组，而 `hasDate` 只判 `ksrq.isNotBlank()` |
| `SchoolYearFilterTest` 未覆盖双筛选同时生效 | 也未覆盖"小学期 + 1/2 学期"空结果陷阱、非标准学期名 |
| 无 `GradesViewModel` 测试 | 筛选/排除/派生层完全未测。把聚合搬进 ViewModel（P1）也正是让它变可测的前提 |
| 无重新导入契约测试 | 断言 `hidden` 存活、编辑被保留或被明确丢弃。**这是本子系统最高价值的缺失测试** |

**最便宜的补测顺序**：多行同 `kcdm` fixture（三个计算器 + 未通过计数）→ 低分补考的 GPA → `xf > 0` 计数 → 按 `kcdm` 排除 → `completedCreditsFor` 反向匹配。

---

## 7. 安全简报

### 已确认正确（明确记录，因为这几处很容易做错）

- ✅ **域名白名单用前导点**：`host == "ustb.edu.cn" || host.endsWith(".ustb.edu.cn")`（`JwWebView.kt:94`）**正确**，`evilustb.edu.cn` 无法冒充
- ✅ **SSL 错误硬失败**：`JwWebView.kt:137-144` → `handler.cancel()`，全项目无 `handler.proceed()`
- ✅ **DevTools 门禁正确**：`JwWebView.kt:71-74` 用 `FLAG_DEBUGGABLE` 判断，release 不可远程调试；Manifest 未设 `debuggable`
- ✅ **失败关闭的交接**：`JwWebView.kt:95-100` 的 `runCatching { ... }.getOrDefault(true)`——无浏览器时返回 `true`（中止加载）而非 `false`
- ✅ **无明文、无放宽网络配置**：Manifest 无 `usesCleartextTraffic`、无 `networkSecurityConfig`，`targetSdk 37` 下明文默认禁止；mixed content 默认 `NEVER_ALLOW`
- ✅ **无危险的 WebChromeClient 面**：只覆盖 `onProgressChanged`——无 `onShowFileChooser`、无 `onJsAlert/onJsConfirm`、无 `onPermissionRequest`、无 `onCreateWindow`
- ✅ **代码确实不读密码**：`app/src/main/assets` 里对 `cookie|localStorage|sessionStorage|password|pwd|密码` 的 grep **零命中**；唯一的 `document.` 是 `JwWebView.kt:30-44` 的视口修补
- ✅ **全部主源码零日志**：对 `android.util.Log|println|System.out|printStackTrace` 的 grep **零命中** → 抓取到的成绩/学号不可能进 logcat
- ✅ **无学生数据外发**：全项目唯一的 HTTP 客户端是同源 GitHub Releases 检查（`SettingsViewModel.kt:96`），OkHttp 甚至不是依赖
- ✅ **桥对页面是只写的**：`JwImportBridge.kt:17-32`、`GradesBridge.kt:22-39` 的四个 `@JavascriptInterface` 方法**全部返回 `Unit`** → 恶意页面可**注入**但无法经桥**外泄**
- ✅ **落盘敏感数据已排除备份**：`backup_rules.xml:8-17`、`data_extraction_rules.xml:9-17` 排除 `beike_schedule.db*`、`datastore/`、`app_webview/`（root 域），注释还说明了 `app_webview` 存着 SESSION cookie。**本项目最专业的一处**
- ✅ **密钥未入库**：`git ls-files` 确认 `keystore.properties` 与 `keystore/` 已正确 gitignore

### 值得修的两点

**S1 — 桥没有 origin 限定（HIGH）**

`JwWebView.kt:85` 的 `addJavascriptInterface` 把桥注入**每一个 frame**（含跨源 iframe），而唯一的守卫 `shouldOverrideUrlLoading`（`:89-101`）**不覆盖**：服务端 3xx 重定向（Chromium 内部跟随）、iframe 导航、子资源请求。

所以任何嵌在 `*.ustb.edu.cn` 页面里的第三方 iframe（第三方 cookie 还是开着的）或任何重定向目标，都能调用 `window.BeikeImport` / `window.BeikeGrades` 把伪造 JSON 直接灌进 Room（`ImportViewModel.kt:56`、`GradesViewModel.kt:253`）。

**影响定性**：完整性/DoS，**不是凭证窃取**（桥方法返回 `Unit`）。但这个结论依赖"当前代码恰好不读返回值"——**一旦将来有人给桥加一个返回取值的方法，性质立刻升级为数据外泄。**

**修法（首选，origin 限定消息）**：加 `androidx.webkit:webkit`，改用

```kotlin
WebViewCompat.addWebMessageListener(
    webView, "BeikeImport", setOf("https://byyt.ustb.edu.cn"),
) { _, message, sourceOrigin, isMainFrame, replyProxy ->
    if (!isMainFrame || sourceOrigin.toString() != "https://byyt.ustb.edu.cn") return@addWebMessageListener
    JwImportBridge.dispatch(message.data!!)
}
```

并把注入脚本从 `window.BeikeImport.onResult(...)` 改成 `window.postMessage(...)`。`minSdk 34` 完全可用，平台层面按 origin 限定。参考 [OWASP MASTG-BEST-0035](https://mas.owasp.org/MASTG/best-practices/MASTG-BEST-0035/)。

**轻量过渡方案**：在 `doUpdateVisitedHistory`/`onPageFinished` 里记录最后提交的主 frame host，非白名单则拒绝桥调用；并注入每会话随机 nonce 要求脚本回传（跨源 frame 读不到顶层 nonce）。

**S2 — 导航守卫失败开放（MEDIUM）**

```kotlin
// JwWebView.kt:93
val host = request.url.host ?: return false    // ← 返回 false = 允许在 WebView 内加载
```

`host` 为 null 的 URL（`file:` / `content:` / `data:` / `blob:` / `intent:`）**直接放行**。叠加"没有 scheme 检查"（`http://` 校内地址也能过白名单），以及主页面判定是**路径子串匹配**：

```kotlin
// JwWebView.kt:112
if (url.contains(MAIN_PAGE_MARK)) { ... }      // MAIN_PAGE_MARK = "/authentication/main"
```

→ 形如 `https://attacker.example/authentication/main` 的 URL（经 S1 的 302 可达）会触发抓取脚本注入。再加上 `:106` 的 `onPageStarted` 往**每个**页面注入 `PAGE_FIX_JS`，App 在非预期上下文执行 JS。

**修法**：

```kotlin
val url = request.url
if (url.scheme != "https") return true                      // 拒绝，不交接
val host = url.host?.lowercase() ?: return true
if (host == "ustb.edu.cn" || host.endsWith(".ustb.edu.cn")) return false
return runCatching { view.context.startActivity(Intent(ACTION_VIEW, url)); true }.getOrDefault(true)
```

主页面判定改为解析 host + path 精确比较。

**S3 — WebView 加固依赖平台默认值（MEDIUM）**

`:78-84` 只设了 `javaScriptEnabled` / `domStorageEnabled` / `useWideViewPort` / `loadWithOverviewMode` / `setAcceptCookie`。未设：`allowFileAccess`、`allowContentAccess`、`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs`、`mixedContentMode`、`setSavePassword`。这些在 `targetSdk 37` 下都是安全默认值，但**这是对平台默认值的未文档化依赖**。

**建议显式写出**（纵深防御）：

```kotlin
settings.allowFileAccess = false
settings.allowContentAccess = false
settings.allowFileAccessFromFileURLs = false
settings.allowUniversalAccessFromFileURLs = false
settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
settings.setGeolocationEnabled(false)
settings.javaScriptCanOpenWindowsAutomatically = false
```

**S4 — 没有任何"退出登录"，教务 SESSION 永久留盘（HIGH/MEDIUM）**

`CookieManager` 在 `app/src/main` 里**只出现两次**，都是开权限（`JwWebView.kt:83-84`）。**没有** `removeAllCookies` / `flush` / `WebStorage.deleteAllData` / `clearCache` / `WebView.destroy`。App 里也**没有退出登录入口**（"我的"页只有"清除成绩缓存"）。

统一身份认证的 SESSION cookie 会一直躺在 `app_webview/` 里，直到用户卸载或清除应用数据。手机借人、二手机转卖时 WebView 仍是登录态。

**公平地说**：备份侧做得**很好**（已排除 `app_webview/`），丢的不是"泄露到云端"，而是"本地长期留存 + 无退出手段"。

**修法**：

```kotlin
fun clearJwSession(ctx: Context) {
    CookieManager.getInstance().apply { removeAllCookies(null); flush() }
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(ctx).clearHttpAuthUsernamePassword()
}
```

在"我的"里暴露为「退出教务登录」。另外 `setAcceptThirdPartyCookies(this, true)`（`:84`）是**不必要的**——所有 `fetch` 都是 `credentials: 'same-origin'`，关掉它还顺带收窄 S1 的攻击面。

---

## 8. 文档与代码不一致清单

| 文档 | 实际 |
|---|---|
| `TECH_DESIGN.md:43`、`JWXT_API.md:40` 写周次位图是 **"32 位"** | ✅ 实测 `ZC` **全部长度 34**，索引 *i* = 第 *i* 周，索引 0 未用。逐条验证："1-8周" → `011111111000…`，"5-8周" → `0000011110…`，"9-16周" → `000000000111111110…`。**代码对，文档错** |
| `README.md:10` "第 11-13 节 = 第六大节" | `SectionMap.kt:6` 是 `11..12`，13 节由 `bigIndexOf` 归入第六大节。表述不准 |
| `README.md:11` "教务导入课只能隐藏不能删除" | **C4**：重新导入会丢失隐藏状态与用户编辑 |
| `README.md:23` "每大节课前 N 分钟通知" | 实际是**每门课程块**排一个闹钟（`planClassReminders` 按 course 循环）。同一大节两门课会各响一次（这是对的，但描述不准确） |
| `README.md:46` "失败时逐周 `queryRlZcSj` 兜底" | 未提 `jw_import.js:79-81` 的 25 周上限 |
| `README.md:61` "App 不读取、不存储、不上传任何凭证" | **密码部分属实**（grep 零命中）。但**学号会被读取并持久化**（`SettingsStore.kt:42-52`、`GradesViewModel.kt:257-259`），SESSION cookie 按设计留盘。建议改为：「不读取、不存储学号密码；登录会话由系统 WebView 保存，可在『我的』中清除」。第 8 行的"App 不接触学号密码"是准确的 |
| `TECH_DESIGN.md:157, 190` 承诺"远程可更新的解析脚本" | **未实现**（仅 assets）。⚠️ 若照文档实现，等于给一个已登录教务 SESSION 的 WebView 开远程代码执行通道——**必须带签名校验，或明确放弃该计划** |
| `JWXT_API.md:30` `queryzclist` 返回"包装 `content:[{ZC}]`" | `jw_import.js:72` 按裸数组解析 → 见 C11。**两者必有一错** |
| `docs/FEATURES_V1.1_DESIGN.md:162` "补考通过后自然消失" | **C3**：未实现（按行计数） |
| `GpaCalculator.kt:11` + `JWXT_API.md:62` "多行取最高分" | `GpaCalculator.kt:37-38` 实际取的是**补考行的最高分**，不是所有行的最高分。见第 11 节待决策问题 2 |
| `ScheduleRepository.kt:63-69` KDoc | 是 `assignImportColors` 的注释复制粘贴到 `addManualCourse` 上 |
| `CourseMerger.kt:9` "不同课程占用同一时段不在此合并" | `SlotKey`（`:14`）只用 `name+day+start+end`，不含 `taskId` → 同名不同课程会被合并，掩盖真冲突。现有 fixture 无此配对，属潜在 |

---

## 9. 最终行动清单

### 第一梯队：半天内可完成，**全部不需要数据迁移**

| # | 事项 | 位置 |
|---|---|---|
| 1 | 隐私：3 处掩码 + `setRecentsScreenshotEnabled(false)` | `GradesScreen.kt:787, 719`；`MainActivity.kt` |
| 2 | 隐藏/删除改为作用于整个合并组 | `ScheduleScreen.kt:362, 366` |
| 3 | 抽 `bestPerCourse`，三条计算路径共用；排除改按 `kcdm` | `GpaCalculator` / `WeightedScoreCalculator` / `CreditAggregator` |
| 4 | 抽 `plausibleLocation()`（剥 `【…】` 再判空），`CourseMerger` + 通知共用；**测试 fixture 改真实形态** | `CourseMerger.kt:22`；`ReminderReceiver.kt:53` |
| 5 | 修 `queryzclist` 包装解析；统一 `totalWeeks` 魔法数 | `jw_import.js:71-75`；`ImportViewModel.kt:91` |
| 6 | `jw_import.js` 成功路径复位标志；接上 `onFetchStart()` + 按钮禁用态 | `jw_import.js:89`；`ImportScreen.kt:77` |
| 7 | `JwWebView` 白名单反转为失败关闭（`host ?: return true` + https + 精确 host/path） | `JwWebView.kt:93, 112` |
| 8 | 考试缓存加空值守卫；`ExamReminderScheduler` 传 `cancelDueAlarms` | `GradesViewModel.kt:262`；`ExamReminderScheduler.kt:63` |
| 9 | `currentWeek` 加 `today.isBefore(monday)` 守卫；三份拷贝合一 | `ScheduleRepository.kt:159` |
| 10 | `selectedWeek` 改可空；Pager 由 `state.selectedWeek` 驱动 | `ScheduleViewModel.kt:150`；`ScheduleScreen.kt:172-184` |
| 11 | `CourseEditDialog` 颜色选择器改 `FlowRow`/可滚动，触摸目标 ≥40dp | `CourseEditDialog.kt:157` |
| 12 | WebView 加 `onRelease { destroy() }`（当前**从无销毁**） | `JwWebView.kt`；`ImportScreen.kt`；`GradesScreen.kt` |
| 13 | 导入：`Committing` 状态 + 按钮禁用 + try/catch + `replaceImportedData`/`clearSampleData` 合并为一个事务 | `ImportViewModel.kt:109-127` |
| 14 | 加「退出教务登录」；关掉 `setAcceptThirdPartyCookies` | 新增；`JwWebView.kt:84` |
| 15 | `GpaInfo.rank`/`totalStudents` 改可空；排名加 `totalStudents > 0` 守卫 | `GradesParser.kt:71-72`；`GradesScreen.kt:719` |
| 16 | `CourseDetailSheet.kt:61` 下标改 `getOrNull` | 一行 |

### 第二梯队：需要决策或涉及迁移

- **导入保留 `hidden` 与用户编辑**（C4）—— 需先定口径（见第 11 节）
- **提醒重排抽到 Application 级** —— "提醒偶尔不响"的根因
- **测试 fixture 全面对齐真实数据** —— 最高杠杆
- **迁移测试 + `fallbackToDestructiveMigration`** —— `app/schemas/` 缺 v1/v2
- `GradesUiState` 派生状态缓存（`by lazy(LazyThreadSafetyMode.NONE)` 即可起步）

### 第三梯队：工程基建

CI（`testDebugUnitTest` + `lintDebug` + `assembleDebug`）、ktlint、`.editorconfig`、`lifecycle-runtime-compose` 依赖、`rememberSaveable`、`contentType`、死代码清理。

### 建议的长期结构改造

1. 一个「隐私投影」类型，让需要分数的组件必须显式解包（`Hidden` / `Visible(value)`）
2. `ReminderCoordinator`（Application 级）
3. `GradeRows`（共用去重）与统一的 `teachingWeekOf`
4. `GradesUiState` 派生值进 `combine` transform（顺带可测）

---

## 10. 已确认正确、不要动的部分

- ✅ **解析层零 `JSONObject.getX()` 抛异常风险**：全部 `contentOrNull` / `intOrNull` / `doubleOrNull` / `booleanOrNull`，且 `JwParser.kt:20-22`、`GradesParser.kt:25-29` 都记录了 `JsonNull` 本身是 `JsonPrimitive`、其 `content` 返回字面量 `"null"` 这个坑，并有回归测试（`GradesParserTest.kt:44-61`）。**本项目最值得称赞的防御**
- ✅ **周次位图位序与边界正确**（见第 8 节实测）
- ✅ **GPA 换算表与 README / `JWXT_API.md:62` 完全一致**，7 个分数段边界（90/85/80/75/70/65/60）**每个都有测试钉住**
- ✅ **全链路 `Double`，无 Float 累加**：唯一的 `.toFloat()` 是 `GradesScreen.kt:416` 的显示用进度比例，正确
- ✅ **每一处除法都有守卫**：`GpaCalculator.kt:47`（`<= 0.0`）、`WeightedScoreCalculator.kt:30`、`GradesScreen.kt:416`（`if (required > 0)`）、`CreditProgressParser.kt:52, 73`
- ✅ **`String.format(Locale.US, …)`**（`GradesScreen.kt:90`）：没有掉进 `DecimalFormat` 跟随 locale 的坑
- ✅ **`Math.floorMod` 而非 `abs()`**（`ScheduleRepository.kt:130`）：正确处理 `Int.MIN_VALUE` 溢出
- ✅ **`replaceGrades`/`replaceExams`/`replaceImportedData`/`replaceCourses` 全部 `db.withTransaction`**；`currentWeek`/`locateWeek`/`teachingWeekOf` 全部 `runCatching` 解析日期并返回 `null` 而非抛异常
- ✅ **`ScorePrivacy.kt` 逻辑正确**：进程内存对象 → 进程死亡即复位；跨 Tab 切换保持；不落盘的取舍有文档说明
- ✅ **`ProfileScreen.kt:524-527` 的 `openExternal`**（`runCatching` + Toast 兜底）写得对
- ✅ **`WeekLayout` / `CourseMerger` / `SessionExpander` / `CourseRowBuilder` 都是纯函数且有单测**；这一层所有非显然注释经核对**全部准确**
- ✅ 备份规则、零日志、无凭证访问、无数据外发（详见第 7 节）
- ✅ `CourseDetailSheet` 对紧邻标签的图标正确使用 `contentDescription = null`
- ✅ 热路径的 memoization 键结构稳定；`ScheduleUiState` 的 `scheduledCourses` 用 `val` 而非 `get()` 的取舍正确
- ✅ `JwParser.kt:119-120`（优先 `KSJC/JSJC`，为 `null` 时回退 `KEY` 推导）是**文档化且正确**的优先级
- ✅ 第 8 周行 `KSJC=1/JSJC=4` 而非 `KEY` 暗示的 3-4，是**学校的数据**，两行合并为一个 1-4 块是正确解读

---

## 11. 待决策问题

1. **导入口径**：重新导入时如何处理导入课的**用户编辑**（改过的名字 / 地点 / 周次）？
   - 选项 A：全部保留（按稳定键匹配，用户改什么保什么）
   - 选项 B：隐藏状态保留，编辑回退（并明确禁止编辑导入课）
   - 选项 C：全部重置，但在预览页**逐项说明会丢什么**
   - 无论选哪个，当前预览页（`ImportScreen.kt:186-191`）的说明都不够。

2. **`bkcx` 的实际取值域**：能否区分「补考」（按实际分记）与「刷分重修」（按最高分记）？
   - 现有 fixture 54 行**全是"正考"**，无法从代码推断
   - `GpaCalculator.kt:37-38` 保留的是"补考行的最高分"，与它自己的注释 `:11` 和 `docs/JWXT_API.md:62`（都写"多行取最高分"）**矛盾** → 需按学校规则裁定改哪边
   - 请确认教务成绩页是否有"重修"/"补考"字样，或 `bkcx` 的枚举值

3. **加权成绩去重后数值会变**（补考/重修课程），是否需要在成绩页加一行口径说明文字？

---

## 附：审查方法与局限

**方法**：通读全部主源码 + 配置 + JS + fixture；三个并行深读域（成绩/学分域、导入/解析域、课表 UI 域）交叉验证；关键结论回原始代码复核。

**局限**：

- **静态审查**，未编译、未运行、未插桩。布局算术（对话框溢出 392dp、FAB 遮挡、`weight` vs 单位排版漂移）由代码推导，未在真机确认
- 无法验证 `bkcx` 的真实取值域（见待决策 2）
- 无法确认 Compose 当前版本是否校验 `Arrangement.spacedBy` 的非负性（`CourseEditDialog.kt:228, 330` 用了 `(-4).dp`，仅列为 NIT）
- 真实教务接口的 schema 漂移风险无法离线评估；`docs/JWXT_API.md` 中标记为 📝 的接口（`getXnxqByRq`、`querydangqianzc`）未在本次审查中验证

**本文档未伴随任何代码改动。**
