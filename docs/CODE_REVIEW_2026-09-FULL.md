# 贝壳课表 全量代码审查报告（2026-09 第二轮）

- **审查时间**：2026-09-18
- **审查对象**：`feat/review-optimize`（= main @ b75929f，versionCode 37 / v1.2.2，日程功能发布后）
- **审查范围**：全部主源码（model / import / data / reminder / ui / 工程配置 / 注入 JS / 测试）
- **审查方式**：4 个并行深读域（纯逻辑解析计算、网络与导入、UI 层、数据提醒与工程配置），🔴 级结论逐条回原始代码复核
- **改动情况**：**未修改任何文件**（纯审查）
- **与 2026-08 报告的关系**：上一轮修复项（补考去重、隐私掩码、开学前误排、WebView 加固、防自激去重等）经复查**全部仍正确**，不再列出；本报告只含新发现。

标记说明：所有 🔴 均已由主审亲自对照源码复核。

---

## 1. 总体结论

三轮修复后的基线相当扎实：提醒系统三条不变量（只取消未来闹钟 / NonCancellable 替换 / 先算后换）实现严格；Room v4→v5 迁移逐列核对一致；注入 JS 无凭证红线违规；上一轮修复无回归。

本轮新发现集中在**9 月新增的日程功能**（复刻了一个上轮已修复过的历史 bug：色板溢出）与**三处跨模块一致性**（requestCode 段重叠、重排键缺项、ExamScheduler 缺 Mutex）。共：**正确性 9 项、性能 3 项、质量与测试盲区 9 项**。无一需要数据迁移。

---

## 2. 🔴 正确性缺陷

### C1. TodoScreen 色板圆点溢出屏幕（复刻了已修复的历史 bug）
`TodoScreen.kt` ColorSelector：`Row + repeat(10) { 28.dp 圆点 } + spacedBy(10.dp)` = 固有宽 370dp；表单两侧 24dp padding 后 360dp 屏可用仅 ~312dp，**最后 1~2 个颜色选不到**。同一 bug 已在 `CourseEditDialog.kt:157-160` 有注释并改用 FlowRow 修复，日程新代码用 Row 复刻了它。
**修复**：照抄 CourseEditDialog 的 FlowRow 方案。

### C2. 已打卡事项当天仍会弹提醒（打卡→重排目前是空转）
`TodoPlanner.upcomingReminders` / `TodoReminderScheduler.planTodoReminders` 全程未读 `lastDoneDate`。用户上午打勾"今天 12:00 交材料"，11:45 照样弹"日程提醒"——打卡触发的重排与打卡前计划完全相同（同 requestCode 同时刻），等于白排。
**修复**：`planTodoReminders` 对**今天**的出现日跳过 `todo.isDoneToday(today)`（未来日不受影响），打卡触发的重排随即变得有意义。

### C3. ImportScreen Committing 态可被返回键打断，onDone() 双触发
`ImportScreen.kt:65-70`：BackHandler 的 `else -> onDone()` 覆盖 Committing；而 `ImportViewModel.confirmImport` 完成后还会再调 `onDone()`。写库慢时用户按返回 → 宿主先离开 → 协程跑完后第二次 `onDone()`。
**修复**：BackHandler 对 Committing 吞掉返回（不转发 onDone）。

### C4. 上课提醒 requestCode 段 [0,8M) 与日程段 [7M,8M) 重叠
`ClassReminderScheduler.REQUEST_CODE_RANGE = 8_000_000`，`floorMod(hash, 8M)` 可落入 [7M,8M)，与 `TodoReminderScheduler`（7M + floorMod(hash,1M)）重叠 1/8。闹钟侧因 action 不同互不干扰，但 `ReminderReceiver.notificationId` 用裸 requestCode 作通知 ID → 跨类同码时后弹通知覆盖前弹（约 10⁻⁵/日量级）；且 TodoScheduler 的"7M 段与 [0,8M) 隔离"注释不成立。
**修复**：ClassReminderScheduler `REQUEST_CODE_RANGE` 改 7_000_000（旧码经重排自愈），并修正注释。

### C5. 重排触发键缺 sectionTimes，节次时间变更后当天提醒用旧时刻
`ScheduleViewModel.kt:162-170` 的 ReminderKey = (courses, semester, enabled, minutes)，而 `planClassReminders` 依赖节次时间。重新导入课表只改节次不改课程时，`distinctUntilChanged` 抑制重排——当天提醒按旧时刻触发（次日 4:30 脉冲自愈）。
**修复**：把 `repo.sectionTimes` 纳入 combine（节次表无 DataStore 回写，无自激风险）。

### C6. TodoViewModel.toggleDone 读陈旧缓存，快速双击失效
`toggleDone` 从 `todosCache`（异步收集）读旧行：两次快速点击落在同一次 Room 回写前，读到同一份 `lastDoneDate`，"打卡→立刻取消"变成两次打卡。
**修复**：toggleDone 内改用 `repo.todos.first()` 取最新值，删除 todosCache 及其收集器。

### C7. SmartClassApi 把 CancellationException 当网络失败吞掉
`SmartClassApi.request` 的 `catch (e: Exception)` 未放行取消异常；`FreeRoomRepository` 的两处 `runCatching` 同样吞。破坏结构化取消（UI 层有 loadSeq 兜底，后果有限）。
**修复**：catch 后 `if (e is CancellationException) throw e`；runCatching 处补 onFailure 重抛。

### C8. 日程表单状态旋转全丢
`TodoScreen` 的 showForm/editing 及表单内 title/note/date/time/colorIndex/repeatMode/showTimePicker/confirmDelete、MinutesWheel 的 show/selected 全是 `remember`；Manifest 无 configChanges → 旋转即重建：长文本输入丢失、表单关闭。
**修复**：基元状态全部 rememberSaveable；editing 改存 Long id（从 state 反查实体）。

### C9. SettingsViewModel 检查更新：连接泄漏 + "null" 字面量
`fetchLatestRelease`（SettingsViewModel.kt:90-120）：非 200 早退（:104）与 catch 路径（:118）均未 `disconnect()`；`obj["body"]?.jsonPrimitive?.content.orEmpty()` 在 GitHub 返回 `body: null` 时得到字符串 `"null"` 直接进更新说明。
**修复**：try/finally disconnect；JsonNull 显式判空。

---

## 3. 🟠 性能

### P1. jw_import.js / jw_grades.js 的 fetch 不检查 r.ok、无超时
会话过期被 302 到登录页时，`r.text()` 照常 resolve，`JSON.parse` 的英文报错直接经 onError 铺到中文界面；连接挂起时 Fetching 态无限等待。
**修复**：`if (!r.ok) throw new Error('HTTP ' + r.status)`；可选 AbortSignal.timeout。

### P2. JwWebView 的 MutationObserver 每次变异强制回流
`fixAll` 内 `getBoundingClientRect()` 在教务 SPA 的频繁 DOM 变异下造成掉帧。
**修复**（可选，风险中）：rAF 去重或 height 无变化时跳过写样式。

### P3. SmartClassKeyProvider 每次刷新最多打两次 /config.json
`syncClock()`（取 Date 头）与缓存未命中时的 `fetchDomainConfig()` 是两次同 URL GET。
**修复**（可选）：合并为一次请求同时取 body + Date 头。

---

## 4. 🟡 质量与测试盲区

1. **零单测函数**：`JwParser.parseFirstMonday` / `parseCurrentSemester`、`GradesParser.parseStudentProfile`（user/me + xsxx 双源合并的复杂兜底）。
2. **fixture 系统性盲区残留**：`grcjcx-all.json` 仍 54 行全正考、0 补考/重修行（上一轮建议未落实；计算器已用代码内构造数据补测，但端到端路径仍测不到补考）。
3. **ExamsParser 中文紧凑格式存疑**：`"2027年1月15日8:00-9:50"`（"日"后无空格）可能解析不出起止时间；无真实样本佐证，**不动代码，留样后验证**。
4. **CreditProgressParser**：`yqmsxf.YQXF` 大小写混用硬编码，服务端字段变异即整块进度消失；缺健壮性用例。
5. **app/schemas 缺 1/2.json**：历史链断裂，MigrationTestHelper 无法覆盖 1→2→3 路径（运行时无影响）。
6. **CourseDao.observeByNames 的 LIKE 通配符未转义**：课程名含 `%`/`_` 时多时段分组编辑会圈进无关课程（影响有界、用户可见）。
7. **无障碍**：TodoRow 打卡圈无可访问语义（建议 `toggleable + Role.Checkbox`，ProfileScreen 有现成写法）；MainActivity TabItem 无 Role.Tab。
8. **MainActivity.importLightPage 用 remember**：导入页旋转时状态栏图标短暂反白。
9. **TodoUiState.loaded 定义未用**：进日程页先闪一帧"还没有日程"再出列表。

---

## 5. 已确认正确、无需改动

- **提醒系统**：三条不变量实现严格；AlarmCodec 边界完备；四类 requestCode 除 C4 外独立；渠道前置创建；goAsync+runCatching+finish 正确；修改 remindMinutes 后旧闹钟双保险取消。
- **Room**：5.json 与实体逐列一致；四条迁移 SQL 与目标 schema 兼容；"迁移默认值不匹配"疑似项已反编译 room-runtime 2.7.2 核实为宽容分支（误报排除）。
- **网络/导入**：SmartClassCrypto 无泄密、无时序攻击面；KeyProvider 三层兜底次序正确；SmartClassApi 错误三来源全部报错化；loadSeq 序号作废正确规避 HttpURLConnection 不可取消；WebView 白名单/销毁/红线全部保持；检查更新仅跳浏览器、版本数值比较正确。
- **逻辑层**：CourseMerger / WeekLayout / SessionExpander / CourseRowBuilder / NextClass / SectionMap / WeekUtils / GpaCalculator / WeightedScoreCalculator / CreditAggregator / GradeRows / ReminderCourses 全部复核通过。
- **UI 层**：GradesScreen 四分段改造、hideScores 掩码无遗漏、GradesUiState 派生值 lazy(NONE)、ScheduleScreen 定位逻辑、CourseEditDialog FlowRow、SemesterSettingsDialog UTC 口径。
- **工程**：backup 双规则排除 beike_schedule.db、keep 规则经 37 个已发版本实证、BootReceiver 清单正确。

---

## 6. 建议行动清单

**A 组（必修，✅ 已于本轮全部修复并附 2 例回归单测）**：C1（FlowRow）、C2（打卡过滤）、C3（BackHandler）、C4（requestCode 7M）、C5（sectionTimes 入键）、C6（toggleDone first()）、C7（CancellationException）、C8（rememberSaveable）、C9（disconnect+JsonNull）、P1（r.ok）、§4.9（loaded）。全部无迁移、无接口变更。

**B 组（可选，待做）**：P2 节流、P3 合并请求、无障碍 §4.7、单测补充 §4.1/§4.2。

**不动**：§4.3（等真实样本）、§4.4/§4.5/§4.6（记录在案）、P 项里的 O(n²)（不构成实际问题）。
