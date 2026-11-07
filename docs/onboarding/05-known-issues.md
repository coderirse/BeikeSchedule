# 05 历史审查与 Git 历史审计：已知问题、修复主题与遗留风险

> 审计者：交接勘察小队 history-audit ｜ 审计时间：2026-10-17
> 审计基线：`main @ 260d091`（v1.5.3 / versionCode 58）
> 输入：`docs/CODE_REVIEW_*.md` 6 份（合计约 228KB）、`docs/TECH_DESIGN.md` 等 4 份设计文档、README、全量 `git log`（106 commits / 23 tags / 27 分支），并对当前代码抽查核实。
> 方法：只读。文档结论 → git 修复提交 → 当前代码三向对照；无法在代码中确认的标「未核实」。配套的现状深读文档见同目录 `01-data-model.md` / `02-ui.md` / `03-import-cloud.md` / `04-features-build.md`。

---

## 0. 结论速览（先看这里）

1. **历史问题绝大多数已闭合**：4 轮全量审查 + 1 次无课教室专项共提出 130+ 条，R5 的 28 条合并发现在 v1.4.0 全部修复并留有回归单测；当前 407 个单测（46 个测试文件）已接入 CI 门禁（本次审计未运行 Gradle）。
2. **真正未修的旧账只剩 6 类**（均有意或低优先）：R4 的 DB 二级索引（D9，需迁移 v6）、schema `1/2.json`（D10，原理上无法重建）、WebView 旋转断崖（C6）、R5 的 M7 清缓存残余窗口、`locateWeek` 与 `teachingWeekOf` 口径不一致、无课教室 P2 视觉/交互债。逐条见 §2。
3. **当前最大风险不在旧账，而在「审查盲区」**：最后一轮审查（R5，基线 v1.3.9/50）的修复轮是 v1.4.0/51，之后又发了 **6 个版本（52→58）**，其中 v1.4.3 假期天级校历、v1.5.0 更新体验/学期设置全屏页、v1.5.3 班级实验安排下发都是新功能/新外部数据源，**没有任何正式审查覆盖**。
4. **三条高危历史线各修过 2~3 轮**：提醒丢失、云同步数据安全、导入解析静默丢数据。它们仍有各自的「窄窗口」残余，是本项目最容易回归的地方（§3）。
5. **文档可信度分层明显**：README / `JWXT_API.md` 较新且基本准确（README 仍有一处「32 位周次位图」旧错）；`TECH_DESIGN.md` 是 v1.0 设计，约 1/3 假设已不成立（§5）；审查报告本身存在轮次编号与 versionCode 记录不一致。
6. **分支全部已合并**：27 个本地分支 + 21 个远端分支对 `main` 均为 0 ahead，可一次性清理；**不要按分支名回溯历史**（分支名与内容多处不符，例如 `feat/empty-classroom` 里装着 v1.1.15 的定位功能）（§4）。
7. **建议的接手起手式**：先补「post-R5 增量审查 + 真机端到端三件套（真实扫码登录/提醒重建/弱网更新下载）」，再谈优化。真机验证缺口是文档里反复记录、但至今没有闭环证据的部分。

---

## 1. 六轮审查一览（实际是 4 份全量 + 1 次专项 + 1 份热修记录）

| # | 文档（行数/大小） | 审查日期 | 审查基线 | 规模 | 提出 | 修复落点（commit） | 当前判定 |
|---|---|---|---|---|---|---|---|
| 1 | `CODE_REVIEW_2026-08.md`（770 行 / 54KB） | 2026-08 | v1.1.11 / 30 | 59 源文件 + 20 测试 | 正确性 12 类、性能 10、架构 14、测试 9 | `221fce7`…`21aeb53`（9/15），并入 `971ef48` = v1.1.12 / 31 | 第一梯队全修；C4 导入口径落地；fixture 形态已改真（`CourseMergerTest.kt:29-65`、`CurrentWeekTest.kt:53-90`） |
| 2 | `CODE_REVIEW_2026-09-FULL.md`（109 行） | 2026-09-18 | v1.2.2 / 37 | 全部主源码，4 域并行 | 正确性 9、性能 3、质量/测试 9 | `bffd5df`（9/18）+ `4731241`/`4dc6184`/`19d02fa`（9/19），并入 `feb9503` = v1.2.5 / 40 | A 组全修；**B 组 P2（MutationObserver 节流）仍未做**；P3（`/config.json` 双请求）已被 v1.1.14 校时重构基本消除；两项 a11y 已在 R4/R5 顺带修掉（`TodoScreen.kt:271-291`、`MainActivity.kt:98-100`）——**R5 §8.4 仍把 a11y 列为未做，该说法已过时** |
| 3 | `CODE_REVIEW_2026-09_FREE_ROOM.md`（57KB）+ `CODE_REVIEW_2026-09-FREE-ROOM.md`（12KB） | 2026-09-16 | v1.1.13 / 32 | 无课教室 4 个提交 | C1–C14、视觉 D1–D25、优化 E1–E11 | `d526fb7` = v1.1.14 / 33 | P0/P1 14 项全修（附录 C）；P2 视觉/文案/排序/缓存未做；D24/D25 在后续轮次被顺带修掉（`GradesScreen.kt:405,632`） |
| 4 | `CODE_REVIEW_2026-09-R4.md`（194 行） | 2026-09-23 | v1.3.2 / 43 | 81 文件 ≈1.33 万行 | 高危 5、中危 21、低危 22、测试 5 | `dddf306`（9/25）= v1.3.7 / 48 | R1 当时拍板「保持不动」，v1.3.8 改 Cloudflare HTTPS 后实际消除；R2/R3/R5 两端修复；**D9/D10/C6 明确未修** |
| 5 | `CODE_REVIEW_2026-10-R5.md`（257 行 / 71KB） | 2026-10-01 | v1.3.9 / 50 | 93 个文件 | 35 条 → 合并 28 条 | 7 批 `9423def`…`609194e`，并入 `994eef4` = v1.4.0 / 51 | 28 条全修 + 死代码删净；§8.4 列出残余；§9 是 v1.4.1 当日热修记录 |
| — | `CODE_REVIEW_2026-10-R5.md` §9（热修记录） | 2026-10-05 | v1.4.0 / 51 真机 | — | 1 事故 | `67d6428` = v1.4.1 / 52 | Ed25519 平台实现缺失 → 内置纯 Java 兜底；修复见 §4 事故复盘 |

**轮次编号的坑（文档可信度问题之一）**：`09-FULL` 自称「第二轮」却在正文写「三轮修复后」；R4 自称「第四轮」并称「前三轮」，但仓库里此前只有 2 份全量报告；R5 为第五轮。**第三轮全量报告在仓库中不存在**（只有无课教室专项，且它被拆成两份文件、内容互相重复）。对外沟通时建议改称「4 轮全量 + 1 次专项」，不要沿用 R1–R5 编号。

---

## 2. 问题分类汇总（正确性 / 安全 / 性能 / 可维护性 / UX）

图例：✅ 已修复（有代码/提交证据）｜◐ 部分遗留｜⏸ 有意不做｜❓ 未核实

### 2.1 正确性（最高频，约 40 条，是历史缺陷的绝对主体）

| 主题 | 典型条目（轮次） | 现状 | 证据 |
|---|---|---|---|
| 提醒链路 | 上课提醒偶发丢失 ×2（v1.1.7 `ed0e7b4`、v1.1.10 `34f6bd3`）；requestCode 段重叠（R2-C4）；重排键缺 `sectionTimes`（R2-C5）；考试提醒漏传 `cancelDueAlarms`（R1-C9） | ✅ | `ReminderAlarmScheduler.kt:13-30` 三条不变量注释、`:65-78` 只取消未来闹钟、`:106-124` 到点记录宽限；`ClassReminderScheduler.kt:52`（段改 7M） |
| 周次/开学边界 | 开学前 6 天误排（R1-C6）；坏日期串下标错位（R5 low）；校历短于总周数整段停排（R5-M5）；`locateWeek` 未同步顺延（R5 §8.4） | ✅/◐ | `ScheduleRepository.kt:241-252`（开学前返回 null）、`:276-283`（坏串整表作废）、`:314-326`（顺延）；`WeekResolver.kt:31-36,99-110`；**口径不一致残余见 §2.6** |
| 成绩/GPA 口径 | 补考重修三处重复计入（R1-C3）；缺失排名丢弃整个概览（R1-C10）；空 `kcdm` 错误合并（R4-A5）；排除键口径不一（R5 low） | ✅ | `GradeRows.kt:38-39`（按 `identityKey` 去重）；`GradeEntity.kt:58`（`kcdm.ifBlank{kcmc}`）；`GpaCalculatorTest.kt:62-76`、`CreditAggregatorTest.kt:93-130` 构造补考/重修行回归 |
| 导入/解析静默丢数据 | `queryzclist` 包装解析错（R1-C11）；ZC 空位图行静默消失（R5 low）；`parseExams` 空列表清空本地考试（R5-M2）；考试分组 `toSortedMap` 吞组（R5-M8）；地点占位死代码（R1-C8）；合并组身份（R5-M4） | ✅ | `jw_import.js:128-137`；`ExamsParser.kt:31-40`（返回可空，null=没拿到）；`GradesScreen.kt:495`（`examDayGroups`）；`CourseMerger.kt:21,24`（`SlotKey` 纳入 source） |
| 状态保存/竞态 | 日程表单旋转全丢（R2-C8）；强更弹窗旋转绕过（R4-C1）；Committing 返回键双触发（R2-C3）；快速双击打卡失效（R2-C6/R5） | ✅ | `TodoScreen.kt:89/351`（rememberSaveable + Saver）；`TodoViewModel.kt:47-48`（Mutex 串行化） |
| 云同步编排 | 撕快照 + 无条件清脏可静默丢数据（R4-R4）；「重试失败项」丢云端半程（R5-M1）；清成绩缓存覆盖云端备份（R5-M7） | ✅/◐ | `CloudSync.kt:103`（`withoutDirtyMarking`）、`:177`（恢复落回滚点）、`:192`（恢复前强制验签）；`SettingsViewModel.kt:171-177`；**M7 残余窗口见 §2.6** |
| Pager / 会话定位 | 头部与 Pager 失步（R1-C5）；进程被杀重进不定位当前周（v1.5.1） | ✅ | `ScheduleScreen.kt:204-240`（`loaded` 前既不回写也不滚动 + `drop(1)`）；`ScheduleViewModel.kt:163-179`（`AppSession.epoch` 打回未选） |
| 状态栏/最近任务 | 成绩隐私在最近任务缩略图泄露（R1-C2c） | ✅ | `MainActivity.kt:150-151`（`setRecentsScreenshotEnabled(hidden)`） |

### 2.2 安全（约 20 条，全部集中在 2026-08 的 WebView 与 R4 的云同步）

| 主题 | 典型条目 | 现状 | 证据 |
|---|---|---|---|
| 云同步传输/身份/完整性 | 明文 HTTP（R4-R1）；「登录即注册」无凭证（R4-R2）；快照无签名可投毒（R4-R3）；强更签名不覆盖 APK（R4-R5） | ✅ | `CloudApi.kt:38`（HTTPS base）、`network_security_config.xml`（全量禁明文）；`JwSessionTicket.kt`（教务 SESSION 密封票据换 token）；`BackupSignature.kt`（恢复前验签）；`UpdateSignature.kt`（`apkSha256` 入签） |
| WebView 桥/导航 | 桥注入所有 frame、可被第三方 iframe 调用（2026-08-S1）；导航守卫失败开放（S2）；主页面判定用 `contains`（S2） | ✅ | `JwWebView.kt:33-39,100-190`（`addWebMessageListener` + platform origin 限定 + 主框架校验）、`:378-408`（`jwNavPolicy`、host/path 精确比对） |
| 登录态残留 | 无退出登录入口、SESSION 永久留盘（2026-08-S4） | ✅ | `JwWebView.kt:416-432`（`clearJwSession`）+「我的 → 退出教务登录」`ProfileScreen.kt:391,658-670` |
| 成绩隐私 | 勾选列表明文分/排名/最近任务三处绕过（R1-C2）；成绩列表行排名/学分漏网（FREE_ROOM-C13） | ✅（实现层面） | `GradesScreen.kt` 25 处 `hideScores` 掩码；`ScorePrivacy.kt`（会话级、不落盘）；**结构性「隐私投影」类型未做**（2026-08 §5 建议，仍靠每个组件自觉判断） |
| token/日志 | token 明文落盘（R4-B6）；敏感日志（H3） | ✅/◐ | `TokenCipher.kt`（Keystore AES-GCM，兼容历史明文）；token/学号 未见日志输出。但 **2026-08「全源码零日志」的结论已不成立**：R4-A7 为可诊断性刻意加了 8 处 `Log.*`（`JwWebView.kt:216,311`、`FreeRoomRepository.kt:122`、`CloudApi.kt:225`、`UnifiedSyncViewModel.kt:125,295,579,622`），其中 QR sid 与同步步骤仅 `BuildConfig.DEBUG` 输出，但 `UnifiedSyncViewModel.kt:125` 会在 release 打印解析失败的原始课表行 JSON。改日志前请确认不引入 token/学号/成绩 |
| 服务端/第三方 | 云账号=学号（R4-R2 模型未变，靠「教务会话校验」硬化）；smartclass 逆向 AES Key/IV 为公开常量 | ◐/❓ | README:81 声称服务端拿密封票据向 `/user/me` 核实；**服务端代码不在本仓库（`D:/git_/showwe`），无法审计**；`SmartClassCrypto` 注释自认「非安全机制」 |

### 2.3 性能（约 20 条，当前规模下多可接受）

- ✅ 已修：`GradesUiState` 派生状态 `by lazy(NONE)`（`GradesViewModel.kt:87-166`，2026-08-P1）；每分钟无界 ticker → 生命周期感知（`ui/common/Now.kt:30`，性能 P2）；注入 JS `fetch` 补 `r.ok`（`jw_import.js:42`，R2-P1）；`CourseDao` LIKE 注入隐患随死代码清理消失（R4-D5 → R5 删净）。
- ◐ 未修（R5 §8.4 明确「不在本轮范围」）：`PAGE_FIX_JS` 的 `MutationObserver` 每次 DOM 变异仍读 `getBoundingClientRect()`（`JwWebView.kt:61-82`）；无课教室无跨会话缓存、打开教务 Tab 即对外网发请求（FREE_ROOM-E2/E3）；Room 全表扫描、`grade`/`exam` 无 `@Index`（R4-D9）。
- 性能不是当前瓶颈：数据规模小（课程 ≤ 数十行/学期），历史审查也未发现 ANR/OOM 证据。

### 2.4 可维护性 / 工程

- ✅ CI 从 0 到有：CI workflow 在 v1.1.12 批次（`21aeb53`，2026-09-15）落地，`27d7327`（9/19）补上 R8 覆盖。当前 `.github/workflows/ci.yml` 跑「单测 + lintDebug + assembleDebug + `minifyReleaseWithR8`」（2026-08 §5 把它列为「投入产出比最高基建」）。
- ✅ R8/proguard 显式化：`app/build.gradle.kts:48-53` + `app/proguard-rules.pro`（R4-D2；v1.4.1 又补 `-dontwarn sun.security.x509.X509Key`）。
- ✅ 死代码一次清 11+6 处（R5 §8.2）；测试从 149（2026-08）→ 331（R4）→ 352→382（R5）→ **407（当前）**。
- ◐ `app/schemas/` 只有 `3/4/5/6.json`，`1/2.json` 缺失，`androidTest/` 只有 `ExampleInstrumentedTest.kt` → **Room 迁移与仪器测试仍是盲区**（R4-D10 + §5 测试盲区；未修）。
- ◐ `AppDatabase.kt:111` 新增了 `.fallbackToDestructiveMigration(dropAllTables = true)`（v1.2.x 起）。注意方向变了：2026-08 审查时项目**没有** destructive fallback，当时把「迁移失败就崩、绝不静默清库」当作保护已发布数据的保险丝；现在这个 fallback 只兜「找不到迁移路径」一种情况（`AppDatabase.kt:99-111` 注释已写明迁移 SQL 抛异常/校验失败仍会崩），但**一旦触发即用户数据全丢**。接手新增迁移时必须同时补 schema 与完整迁移路径。
- ◐ 无 ktlint/detekt、lint 未开 `warningsAsErrors`、无 CHANGELOG（`docs/` 下无 changelog 类文件）。

### 2.5 UX / 无障碍（约 40 条，多为「功能对但体验粗糙」）

- ✅ 已修的代表项：颜色选择器溢出屏幕 ×2（2026-08 + R2-C1，改 `FlowRow`）；FAB 遮挡网格；旋转丢表单/弹窗；强更弹窗可被旋转绕过（R4-C1）；无课教室「时间基准条 + 进行中/已结束 + 空态出口 + 下拉刷新 + 数据来源」（`FreeRoomScreen.kt:103,135,148-162,323-342`）；展开箭头随状态旋转与考试「已结束」对比度（`GradesScreen.kt:405,632`）；Tab `Role.Tab`（`MainActivity.kt:98-100`）与周几圆点无障碍（R5 用 uiautomator 实测后改为单语义节点）。
- ◐ 未做：无课教室视觉系统化（D4/D5/D6/D7/D9/D10/D11）、错误文案口径（D22/D23）、座位数排序；R2 的 P2（`MutationObserver` 节流）。这些属于「产品打磨 backlog」，不影响正确性。
- ❓ 真机无障碍/大字体实测：仅模拟器 uiautomator 走过一轮（R5 §8.5），无留档证据。

### 2.6 三条「已知残余」的精确状态（R5 §8.4 + 本次核实）

1. **M7 残余窗口（有意保留）**：`withoutDirtyMarking` 只保护「清缓存」这一刻；清完再生效的其它编辑仍会带着空成绩整包上传，覆盖云端。彻底修复需要给「缓存已清」立墓碑（结构性改动）。证据：R5 §8.4 第一条；`CloudSync.kt:103`。
2. **`locateWeek` 未随 `teachingWeekOf` 顺延（未修）**：校历短于 totalWeeks 时，提醒/图钉用顺延口径，而顶栏「学期结束」与默认落位用 `afterEnd`（`ScheduleRepository.kt:289-303` 最后一天即 afterEnd；`teachingWeekOf:320-324` 顺延到 totalWeeks）。R5 拍板「改 afterEnd 风险大于收益，留待观察」。
3. **无课教室 P2 未做**：附录 C 明列「D4~D7、D9~D11、D13~D23、E1~E3、E5、E6、E10、E11 未做」；其中 E10（`docs/samples/` 缺 smartclass 样本）至今仍缺。

---

## 3. 接手后必须知道的坑（反复出现的缺陷主题）

> 每条给出「当初根因 → 现行对策 → 接手注意」。这些主题在历史上都至少复发过一次，改动时优先看对应不变量注释。

**坑 1｜上课/考试/日程提醒「偶发不弹」**。根因链：v1.1.7 前「提醒触发即重排」把正在投递的闹钟取消（`ed0e7b4`）；v1.1.10 修「已到点未投递的闹钟被取消」（`34f6bd3`）；剩下的是「重排入口只挂在课表 ViewModel + 每日脉冲」以及**应用自更新后系统清空全部闹钟**（R5-M6）。对策：`ReminderAlarmScheduler` 三条不变量（只取消未来 / `NonCancellable` 原子替换 / 先算后换）+ 6 小时到点记录宽限 + `MY_PACKAGE_REPLACED` 重排（`AndroidManifest.xml:72`、`BootReceiver.kt:28`）。注意：**R5 §8.5 明确「MY_PACKAGE_REPLACED 后 dumpsys alarm 三类提醒重建」还是真机待办**，没有实测证据；且重排入口仍分散在 5 处，没有 Application 级 `ReminderCoordinator`（2026-08 §5 建议未落地）。

**坑 2｜requestCode 是「隐式契约」**。三段手工分配：上课 `[0, 7M)`、日程 `[7M, 8M)`、考试 `8M+`（`ClassReminderScheduler.kt:52`、`TodoReminderScheduler.kt:39-40`、`ExamReminderScheduler.kt:37`）。历史上撞过一次（R2-C4）。改任何一段都必须同步 `ReminderReceiver.notificationId` 的口径与旧闹钟迁移（旧码靠重排自愈）。

**坑 3｜周次/开学日期/校历边界**。历史 6 次缺陷都出在这里：开学前被算成第 1 周（整除截断）、坏日期串导致整学期错位、校历短于总周数停排、假期周跳号、调休补课、Pager 恢复页误当用户选周。对策：`WeekResolver` 是唯一实现（`WeekResolver.kt:7-16` 注释），严格口径/显示口径分离；`teachingWeekOf` 顺延；坏串整表作废。注意：**`locateWeek` 与 `teachingWeekOf` 的口径不一致是已知残余**（§2.6-2）；`SchoolAdjustments.kt` 的调休补课是**编译进 App 的硬编码数据**，新学期/临时调课必须发版，别在运行时找它。

**坑 4｜Pager 恢复页 vs 用户选周（国产 ROM 杀进程必现）**。Activity 带 `savedInstanceState` 重建时 `rememberPagerState` 恢复旧页，而首帧 `uiState` 还是默认值，程序性滚动被 `snapshotFlow` 回写成 `selectWeek(1)`，定位永久失效，v1.5.1 `008571a` 修。对策：`state.loaded` 之前 Pager 既不回写也不滚动（`ScheduleScreen.kt:213-240`）；会话语义由 `AppSession.epoch` 表达（每次退后台 +1，不落盘，`AppSession.kt`）。注意：**不要在 `loaded=false` 阶段做任何「把 Pager 当前页写回状态」的操作**。

**坑 5｜WebView 导航与登录态**。历史 3 类问题：桥注入所有 frame（S1）、导航守卫失败开放 + `contains` 判定主页面（S2）、无退出登录（S4）。现行：`addWebMessageListener` 平台级 origin 限定 + 主框架/域名双校验；`jwNavPolicy` 对 host 缺失/非 http(s) 一律拒绝，站内 **http 与 https 同等放行**（SSO 回调是 http，收紧会卡「授权成功」）；`clearJwSession` 提供退出。注意：**统一认证页填一半旋转 → WebView 重建回首页**（R4-C6 未修，Manifest 无 `configChanges`，`UnifiedSyncScreen.kt:90` 用 `remember` 持有 WebView）；v1.3.9 后三个流程合并为「登录并同步」，任何新增抓取步骤都要进 `SyncPlan`。

**坑 6｜云同步覆盖与恢复**。历史 5 个高危：明文 HTTP、登录即注册、快照可投毒、撕快照+无条件清脏、签名不覆盖 APK。现行：HTTPS + 教务会话密封票据 + 快照 Ed25519 验签 + 回滚点 + `suppressDirty`。注意：8 秒去抖上传是「整包覆盖」，**任何新增「清空本机数据」的入口都必须走 `CloudSync.withoutDirtyMarking`**（R5-M7；清成绩缓存就是踩过这个坑）；恢复是全表覆盖（先清三源课程再写），云恢复入口的校验在 `CloudSnapshot.sanitizeSemester`（`CloudSnapshot.kt:334-340`），新增快照字段要同时考虑旧客户端/旧快照的默认值。

**坑 7｜成绩隐私与最近任务缩略图**。历史：勾选列表明文分、排名/学分未掩码、最近任务缩略图可见。现行：全页 25 处 `hideScores`、会话级 `ScorePrivacy`（不落盘，退后台即复位）、`setRecentsScreenshotEnabled(hidden)`。注意：这是「每个组件自觉判断」的模式，2026-08 建议的「隐私投影类型（Hidden/Visible）」未实现——**新增任何展示分数/排名/学分的组件，必须自己接 `hideScores`**。

**坑 8｜R8 / 序列化 / 加密 API 的平台可用性**。R4-D2 时全工程无 proguard 规则；v1.4.1 真机事故更典型：`KeyFactory.getInstance("Ed25519")` 在部分 ROM 上不存在，异常逃出 `runCatching` 被当成「自有源不可用」**静默回退 GitHub**，导致自 v1.3.3 引入验签以来真机从未真正生效。对策：`Ed25519.kt` 平台优先 + 内置纯 Java 兜底；`verifyDetailed` 公钥解析失败不再抛；「我的」页显示具体失败原因。注意：**「静默回退」必须留可观测痕迹**；CI 已加 `minifyReleaseWithR8`，但 R8 只在编译期暴露问题，运行时反射/序列化仍要靠真机冒烟。

**坑 9｜弱网下载/更新通道**。更新直链走 Cloudflare Tunnel，实测只有 2–20KB/s 且会整段停滞，原实现 30s 读超时一次即失败。v1.5.0 改 `.part` + `Range` 断点续传 + 最多 5 次重试 + 60s readTimeout + 摘要兜底（`UpdateInstaller.kt:40-41,51,56,111,146`）。注意：**v1.5.0 的端到端「检查→下载→校验→安装」只用模拟器冒烟过，且 R5 记录「模拟器上 Downloads 疑似不工作、未观察到落盘」，真机闭环仍缺**；强更弹窗下载中不要重复 `enqueue`。

**坑 10｜导入解析「静默丢数据」**。历史上最危险的一类：`parseExams` 把「解析失败」折叠成空列表 → 清空本地考试 + 取消提醒；`queryzclist` 包装结构解析错 → 校历长期走退化路径；ZC 空位图行静默入库不渲染；考试分组比较器把「时间待定/解析失败」当同一 key 吞掉一组。对策：失败与「确实为空」必须用可空类型区分（`ExamsParser.kt:31-40`）；坏数据「整行跳过并留痕」或「整表拒绝」，禁止逐条丢弃（会错位）；同一威胁模型在 `JwParser` 的 zc/备注周数上已加钳制。注意：**改解析器时先看 `JwParser` 顶部注释的「脏数据防御」清单**。

**坑 11｜课程身份/合并组口径**。R1-C1 的「隐藏只隐藏一行」、R5-M4 的「跨来源同名同段合并成一张卡」是同一根因：**合并键与行组键不是同一个口径**。现行：`CourseMerger.SlotKey = (name, source, day, start, end)`（`CourseMerger.kt:21,24`），隐藏/删除/编辑按「名字+来源」取行组。注意：班级实验安排（`SOURCE_LAB`）加入后 source 有 4 类（教务/手动/示例/班级实验），任何新来源都必须想清楚「合并身份」与「隐藏保留」两件事（`ScheduleRepository.kt:127-139` 整组替换时按 taskId 保留 hidden）。

**坑 12｜第三方 smartclass 平台耦合**。无课教室依赖逆向出的 AES Key/IV、`csrkToken` 签名、服务器时间窗口，且**只能查「今天」**（无日期参数）。历史：时钟校正死代码导致假故障（FREE_ROOM-C4）、key 轮换后永久失效（C1）、5xx 被当「没有空教室」（C5）、切楼栋竞态（C6）。对策：三层 key 兜底 + `withSignedRetry` 全接口覆盖（`FreeRoomRepository.kt:24,46-49`）+ `Date` 头优先校时（`SmartClassKeyProvider.kt:103-121`）+ 请求序号作废（`FreeRoomViewModel.kt:123-240`）。注意：这是**无 SLA 的第三方接口**，平台改版只能发版；`docs/samples/` 至今没有 smartclass 样本（FREE_ROOM-E10 未做）。

**坑 13｜进程死亡 / Tab 恢复 / 状态保存**。`rememberSaveable` 与裸 `remember` 混用是历史高发区（R4-C1/C2/C3/C6/C13、R2-C8）。注意：`tab` 是 `rememberSaveable`（`MainActivity.kt:168`）——进程被杀后恢复到「教务/我的」Tab 时，课表 `ScheduleViewModel` 不会被创建，**上课提醒的全量重排不会在本次启动发生**（重排入口仍挂在课表 ViewModel 上，见坑 1）；`AppSession` 与 `ScorePrivacy` 刻意不落盘，别「顺手」持久化。

---

## 4. 版本发布与回归历史

**节奏**：2026-08-28 → 2026-10-08，42 天，106 个 commit，versionCode 4 → 58（提交信息中出现 34 个发版号）。23 个 tag 中 **7 个是虚指同一 commit 的假 tag**（`v1.0.5/1.0.6/1.0.7/1.0.9/1.0.10/1.0.11/1.0.14` 全部指向 `44bcaee`，其真实版本是 versionCode 6 / v1.0.5），另有约 19 个在提交信息中出现过的发版号从未打 tag。**tag 不能当发布史用**。

| 版本（versionCode） | 日期 | 类型 | 关键内容 / 事故 |
|---|---|---|---|
| v1.0.17（18） | 08-29 | 功能+重构 | 事务化、GPA 竞态、安全加固、包名迁移 `com.caeamer` |
| v1.1.0（19） | 08-29 | 新功能 | 考试/排名/学分进度/挂科红标；GPA 改本地 4.0 制（教务 `BL` 口径会超满绩） |
| v1.1.1（20）→ v1.1.2（21） | 08-30 | 修复 | 教务单周调课拆行解析错位（「课表分裂细条」）+ 开学前误显示「第 1 周」 |
| v1.1.5（24） | 09-08 | 修复 | 精确闹钟权限 + 成绩隐私会话级复位 |
| v1.1.7（26）→ v1.1.10（29） | 09-09~09-14 | 纯修复×3 | 上课提醒偶发丢失 ×2、无固定时间弹层抖动 |
| v1.1.12（31） | 09-16 | 修复（第一轮审查） | 补考去重、隐私绕过、隐藏/周次、导入原子性、WebView 加固 + CI |
| v1.1.14（33） | 09-16 | 修复（无课教室专项） | key 轮换永久失效、导航锁死、时钟死代码等 14 项 |
| v1.2.2（37） | 09-18 | 新功能 | 日程功能（每天/每周/一次性 + 打卡） |
| v1.2.5（40） | 09-19~09-22 | 修复（第二轮审查） | A 组 11 项 + 发版阻塞四件 + WebView 桥改 `addWebMessageListener` |
| v1.3.0（41）→ v1.3.2（43）→ v1.3.7（48） | 09-22~09-25 | 新功能 + 3 连修 | 云同步接入；微信授权无响应；R4 高危修复 + 教务会话校验 + 两端验签 |
| v1.3.8（49） | 09-25 | 安全 | 云同步全链路 HTTPS（Cloudflare Tunnel） |
| v1.3.9（50） | 09-25 | 重构 | 三处扫码合并为「登录并同步」 |
| v1.4.0（51） | 10-05 | 修复（第五轮审查） | 28 项全修 + 死代码清理 |
| v1.4.1（52） | 10-05 | 热修复 | Ed25519 平台缺失 → 内置兜底 + 失败原因可见（§4 事故 a） |
| v1.4.3（54） | 10-05 | 新功能 | 假期天级校历 + 调休补课内置（**无审查**） |
| v1.5.0（55） | 10-06 | 功能+修复 | 学期设置全屏页、课程编辑卡片化、更新卡片；断点续传下载（弱网事故，§4 事故 c） |
| v1.5.1（56） | 10-07 | 纯修复 | Pager 恢复页被当用户选周（§4 事故 d） |
| v1.5.2（57） | 10-08 | 纯修复 | 日期行「10/20」折行 |
| v1.5.3（58） | 10-08 | 新功能 | 班级实验/上机安排按班级下发（**新外部数据源，无审查**） |

**规律：每个大功能发布后 0~2 天内必有一轮修复**——v1.1.0→1.1.1/2、v1.2.2→1.2.5、v1.3.0→1.3.2→1.3.7、v1.4.0→1.4.1、v1.5.0→1.5.1→1.5.2。纯修复版本：v1.1.7、v1.1.10、v1.1.12、v1.1.14、v1.4.0、v1.4.1、v1.5.1、v1.5.2。

**值得记住的 4 个事故复盘**：
- **a) v1.4.1 验签静默回退（R5 §9）**：自 v1.3.3 引入的自有源验签在真机上从未生效（Ed25519 KeyFactory 平台实现缺失 → 异常逃逸 → 静默回退 GitHub）。暴露两个系统性教训：加密 API 平台可用性必须当「可能缺失」处理；静默回退必须可观测（当时服务端零访问日志，是排障最大障碍）。
- **b) 提醒丢失两次（v1.1.7 / v1.1.10）**：都是「取消已到点/正在投递的闹钟」；修复后固化为 `ReminderAlarmScheduler` 三条不变量，**改提醒逻辑前必须读那段注释**。
- **c) v1.5.0 弱网下载**：Cloudflare 隧道 2–20KB/s + 整段停滞，30s 超时一次即失败；改断点续传 + 重试。注意真机闭环仍缺。
- **d) v1.5.1 Pager 恢复页**：国产 ROM 后台回收是常态，Activity 重建 + Pager 恢复页 + 首帧默认 `uiState` 三者叠加，把程序性滚动回写成用户选周。修复点在 `ScheduleScreen.kt:220-240`。

**post-R5 审查盲区**：最后一轮全量审查基线是 v1.3.9/50（10-01），R5 的修复轮是 v1.4.0/51，之后又发了 6 个版本（52→58）；其中只有 v1.4.1/52 的事故被 R5 §9 记录，**v1.4.3 的校历/调休、v1.5.0 的更新与学期设置、v1.5.3 的班级实验下发与新增同步步骤 `CLASS_LAB` 均无审查记录**。

---

## 5. 分支状况与清理建议

- 本地 28 个 ref（`main` + 27 分支），远端 22 个 ref（`origin/main` + 21 分支，`origin/HEAD` 为符号引用）。**全部已合并**：`git branch --merged main` 全命中，逐分支 `git rev-list --count main..<branch>` = 0。
- 有 6 个本地分支从未推到远端：`feat/hide-inactive-courses`、`feat/review-fixes`、`feat/review-optimize`、`fix/class-reminder-loss`、`fix/p1-p7-a1-a3`、`refactor/architecture-optimization`。
- 结论：**没有未合并/过时分支需要保留**；`refactor/architecture-optimization` 也已并入（v1.3.7 基线）。
- 清理建议（未执行，仅建议）：保留 `main`，删除 27 个本地分支与 21 个远端分支；先 `git branch --merged main` 复核再删。远端删除需仓库权限，建议一次 PR 后统一清。
- **不要按分支名读历史**（命名与内容多处不符，已核实）：
  - `feat/empty-classroom`：含无课教室 4 提交 + v1.1.14 修复 + **v1.1.15「重进定位当前周」**；
  - `feat/ui-ux-optimization`：名字是 feat，实际含 **v1.5.1 Pager 修复**；
  - `feat/class-lab-schedule`：含 v1.5.3 新功能 + **v1.5.2 日期行修复**；
  - `feat/ux-optimization` 与 `feat/ui-ux-optimization` 是两个不同分支（前者 v1.3.8），极易混淆；
  - `refactor/architecture-optimization` 实为 v1.3.7 云同步/安全批次。
- 回溯历史请用 `git log --grep`/tag/commit message；**branch 名不可靠**。
- 工作树有未跟踪目录 `.v2c/`、`.zcode/`、`out/`、`nul` 等外部工具产物（非本次审计产生），建议确认后加入 `.gitignore`，不要误删。

---

## 6. 文档可信度：TECH_DESIGN v1.0 与现状的漂移

| # | `TECH_DESIGN.md` 位置 | 文档声称（v1.0，2026-08-27） | 现状（已核实） | 判定 |
|---|---|---|---|---|
| 1 | §1 L13 | 「本地优先：无账号体系、无服务器依赖、离线可看」 | 有云账号（=学号）、自建服务端 `api.caeamer.com`、Cloudflare Tunnel、整包备份 | **假设失效**（opt-in） |
| 2 | §1 L15 | 「非目标：成绩查询、考试安排、多端同步」 | 成绩/考试/GPA/学分进度/云同步全部已实现，且是旗舰功能 | **假设失效** |
| 3 | §4 L89 / §9 L197 | 包名 `com.example.beikeschedule` | `com.caeamer.beikeschedule`（v1.0.17 迁移） | 失效 |
| 4 | §4 L89-104 | 包结构仅 data/local、pref、repo、import、model、ui/schedule、ui/course、remind | 实际多出 `data/backup`、`data/remote`、`ui/sync`、`ui/freeroom`、`ui/todo`、`ui/grades`、`ui/profile`、`ui/common`；`remind` 实为 `reminder` | 失效 |
| 5 | §5 L112-136 | Room 只有 `course` + `section_time`（DB v1） | DB **v6**：+grade/exam/todo/note；`source` 4 类（教务/手动/示例/班级实验） | 严重滞后 |
| 6 | §6.1 L154-155 | 结果经 `@JavascriptInterface` 回传；远程脚本可更新（GitHub raw） | v1.2.5 改 `addWebMessageListener`（平台 origin 限定）；**远程脚本热更新从未实现**（仅 assets，2026-08 §8 还警告它=给已登录会话开 RCE 通道） | 一半失效 |
| 7 | §6.3 L168 | WorkManager 周期任务排提醒 | 实际 AlarmManager 精确闹钟 + 每日脉冲 + Boot/更新广播重排，无 WorkManager | 失效 |
| 8 | §7 L180 | M5 Glance 桌面小组件 | **未实现**（无 glance/workmanager 依赖） | 未交付 |
| 9 | §2.1 L43 | ZC「34 位周次位图」 | 实测 34（代码按 34 处理）——**该行正确**；但 `README.md:54` 仍写「32 位周次位图」 | 文档间冲突 |
| 10 | §8 L188-190 | 「解析脚本远程热更新，应对后续改版」 | 未实现，且属安全反模式 | 不成立 |

**其他文档**：
- `README.md`：已更新到 v1.5.3，功能/隐私/云账号身份校验/HTTPS 叙述与代码一致度最高（**最可信的入口文档**）。残余错误：L54「32 位周次位图」（实为 34）；未提 `docs/onboarding/`。
- `docs/JWXT_API.md`：2026-10-05 更新，含成绩口径纠正（`BL` 不是 4.0 GPA）、34 字符位图、第 9 节 smartclass。**可作为接口契约读**；仍缺 smartclass 脱敏样本（E10）。
- `docs/FEATURES_V1.1_DESIGN.md` / `docs/GRADES_DESIGN.md`：历史设计稿；`FEATURES` L162「补考通过后自然消失」在 2026-08 被判与实际不符，现已通过 `GradeRows.bestPerCourse` 间接成立；`GRADES_DESIGN` 的「字段语义待实抓确认」已被 `JWXT_API.md` 解决。**别再按设计稿实现新功能**。
- **审查文档自身也会过时**：2026-08 §7「全源码零日志」已被 R4-A7 的诊断日志推翻；R5 §8.4 把「两项 a11y」列为未做，但代码已修（见 §1 表）。引用审查文档结论前先回代码抽查。
- **版本史可信度**：R4 文档称修复发版 v1.3.3 / versionCode 44，但 `git log -S "versionCode = 44"` **零命中**——44 从未存在于本仓库；R4 修复实际落在 `dddf306`（v1.3.7/48）。引用历史版本号前先 `git log -S` 验证。

---

## 7. 接手风险 Top 10（按严重度）

| # | 风险 | 为什么严重 | 如何验证 | 建议动作 |
|---|---|---|---|---|
| 1 | **post-R5 代码无审查**（v1.4.3→v1.5.3 共 5 个发版，含新外部数据源与同步步骤重构） | 占当前发布面约 40%；班级实验下发引入服务端按班级推数据 + 整组替换 + 自动隐藏教务课，逻辑复杂且无回归文档 | 通读 `ClassSchedule.kt`/`ClassScheduleMapper`/`UnifiedSyncViewModel` 的 `CLASS_LAB` 步骤；对 `applyClassSchedule`（`ScheduleRepository.kt:123-139`）做脏数据/重复同步/跨学期用例 | 单独立项做一轮增量审查（重点：服务端数据契约、隐藏保留、与校历周次一致性） |
| 2 | **提醒可靠性架构**（重排入口仍挂课表 ViewModel，无 Application 级协调；MY_PACKAGE_REPLACED 真机未验证） | 「提醒不响」是历史上修过 3 次的主题；Tab 恢复到「教务/我的」时课表 VM 不创建 → 本次启动不重排 | 真机：装旧包→升级→`dumpsys alarm` 看三类提醒是否重建；杀进程后从「我的」Tab 重进，检查当日课程闹钟 | 抽 `ReminderCoordinator` 到 Application 级；补真机验证清单 |
| 3 | **云同步数据安全残余**（M7 窗口、快照只签名不加密、账号=学号、服务端不可审计） | 云端有全部成绩/学籍/课表/日程；token=全权凭证；M7 可静默覆盖云端成绩 | 复现：登录云同步→清除成绩缓存→再编辑任意数据→看 8 秒后快照 payload 是否带空成绩；检查恢复回滚点是否生成 | 补「缓存已清」墓碑；把服务端代码纳入交接范围（当前不在本仓库）或在 README 记录其边界 |
| 4 | **周次/校历/调休边界**（`locateWeek` vs `teachingWeekOf` 不一致；补课数据硬编码随版本；校历短于 totalWeeks） | 直接影响「今天上什么课/提醒哪天响」；新学期/临时调课必须发版；一旦口径分叉，顶栏说放假而提醒照排 | 给学期配置一个「校历只到第 16 周、totalWeeks=20」的夹具，对比顶栏状态与 `ClassReminderScheduler` 排期；再抓一份 2026-2027-2 校历样本跑 `WeekResolverDayScheduleTest` | 让 `locateWeek` 复用顺延口径（R5 已留观察项）；把 `SchoolAdjustments` 改为服务端下发或学期设置可编辑 |
| 5 | **WebView 登录态/旋转/真机扫码未闭环** | 一键同步是唯一数据入口；旋转断崖会让用户填一半的登录页回首页；文档明确「未覆盖真实扫码登录」 | 真机完整跑「扫码登录→课表→成绩→云同步」，中途旋转一次；断网/慢网各一次 | 评估 Activity 级 WebView 保留或 `configChanges`；把「扫码+旋转+弱网」纳入发版清单 |
| 6 | **数据库/备份演进**（DB v6 新列、`fallbackToDestructiveMigration`、schema 1/2 缺失、快照字段增删） | 数据丢失不可逆；新增迁移若缺 schema 会在升级时走 destructive fallback 清库 | 从 v1.4.3 包升到当前包，检查课表/成绩/日程是否完整；检查 `app/schemas/6.json` 与实体逐列一致 | 补迁移测试（Robolectric 或 `MigrationTestHelper` 需先重建 1/2 schema）；新增字段一律给默认值（快照兼容） |
| 7 | **第三方 smartclass 平台耦合**（逆向密钥、时钟窗口、只能查今天、无 SLA、无样本存档） | 平台前端改版即功能失效，且只能发版修复；请求会主动访问校外域名（合规已披露） | 调系统时间慢 5 分钟、断网、服务端 5xx 各跑一次空教室；检查错误文案是否可行动 | 存档 smartclass 脱敏样本（E10）；加浏览器兜底入口（E6）；评估 `csrkTime` 动态余量（E4） |
| 8 | **成绩计算口径**（自建 4.0 GPA、补考/重修取最高、空 kcdm、排名 0 守卫） | 口径与教务/学院规则绑定，规则变化无感知；错算影响保研/评优判断 | 用真实成绩单回归：加权/GPA/未通过门数/学分进度与教务网页逐项对照；补考/重修样例 | 把计算口径写进 README 或成绩页脚注；补 `grcjcx-all.json` 的真实补考/重修行 fixture |
| 9 | **构建/发布链路**（keystore 未入库、更新通道单点、无发布自动化） | 没有签名文件无法出正式包；更新依赖 Cloudflare Tunnel + Ed25519，v1.4.1 已出过平台兼容事故；GitHub 回退是「静默」的 | 用现有 keystore 构建 release 包并验签；断掉自有源看是否回退 GitHub 且提示可读；真机走一次应用内下载闭环 | 交接 keystore 与签名口令（私密渠道）；把「真机更新闭环」列为发布门禁；考虑 APK 摘要/签名 CI 化 |
| 10 | **工程可维护性**（单模块、0 仪器测试、无迁移测试、文档漂移、无 CHANGELOG） | 不影响当前功能，但决定后续迭代成本；文档漂移会诱导新人按错误假设改代码 | 数一下 `androidTest` 文件数（目前 1 个模板）；对照本文件 §6 漂移表抽查 | 先补 onboarding 文档（本系列）；把 §6 漂移点同步进 `TECH_DESIGN.md` 或标注「v1.0 历史文档，勿作现状依据」 |

---

## 8. 未核实清单与证据索引

**未核实（有文档承诺但无代码/实测证据）**
1. 真实扫码登录全链路（R5 §8.4 明说「未覆盖真实扫码登录」；v1.5.3 又新增 `CLASS_LAB` 同步步骤）。
2. `MY_PACKAGE_REPLACED` → 三类提醒真机重建（R5 §8.5 列为真机待办）。
3. 应用内更新下载在真机上的完整闭环（R5 §8.5：模拟器未观察到落盘）。
4. 服务端行为：限流、教务会话校验、备份签名私钥管理、Cloudflare 隧道配置（服务端代码在 `D:/git_/showwe`，不在本仓库）。
5. `bkcx` 真实取值域（「补考」vs「刷分重修」）——2026-08 待决策 2 仍未闭环；`jw_import.js`/解析器只做保守处理。
6. 2026-2027-2 学期校历/调休数据（`SchoolAdjustments` 目前只内置 `2026-2027-1`）。
7. 无课教室平台对第三方客户端的正式态度（FREE_ROOM-Q5），属产品/合规决策。

**证据索引**
- 审查文档：`docs/CODE_REVIEW_2026-08.md`（§3-§9）、`docs/CODE_REVIEW_2026-09-FULL.md`（§2-§6）、`docs/CODE_REVIEW_2026-09_FREE_ROOM.md`（附录 C 修复记录 + §4 视觉清单）、`docs/CODE_REVIEW_2026-09-FREE-ROOM.md`（§2-§4）、`docs/CODE_REVIEW_2026-09-R4.md`（§2-§8）、`docs/CODE_REVIEW_2026-10-R5.md`（§8.4 残余、§8.5 验证、§9 热修）。
- 关键提交：`221fce7`/`971ef48`（第一轮）、`bffd5df`/`feb9503`（第二轮）、`d526fb7`（无课教室）、`dddf306`/`ad866bd`（R4+云安全）、`9423def`…`994eef4`（R5）、`67d6428`（v1.4.1）、`3e105ce`（v1.4.3）、`5caaeea`（v1.5.0）、`008571a`（v1.5.1）、`9d968da`/`260d091`（v1.5.3）。
- 当前代码关键不变量：`reminder/ReminderAlarmScheduler.kt:13-30`、`model/WeekResolver.kt:7-16`、`data/repo/ScheduleRepository.kt:270-326`、`data/repo/GradeRows.kt:8-38`、`import/JwWebView.kt:20-110`、`data/backup/CloudSync.kt:29-46,103,170-198`、`model/CourseMerger.kt:9-24`、`ui/schedule/ScheduleScreen.kt:204-240`、`data/local/AppDatabase.kt:12,98-111`、`.github/workflows/ci.yml`。
- 命令备查：`git branch --merged main`、`git rev-list --count main..<branch>`、`git log -S "versionCode = N" -- app/build.gradle.kts`、`git show <sha> --stat`。
