# 贝壳课表 BeikeSchedule · 接手开发交接手册

> 生成：2026-10-17 ｜ 基线：`main` / v1.5.3 / versionCode 58（工作区实测）
> 配套深度笔记：`docs/onboarding/01-data-model.md`、`02-ui.md`、`03-import-cloud.md`、`04-features-build.md`、`05-known-issues.md`
> （每篇 200–350 行，结论优先、逐条带 `文件:行号` 证据；本手册是入口与摘要，细节以它们为准。）

---

## 0. 一分钟结论

1. **项目成熟度较高**：功能完整（课表 / 教务 / 成绩 / 日程 / 三类提醒 / 云同步 / 应用内更新），**407 个单测全绿**，CI 四道门禁（单测 / lint / assembleDebug / minifyReleaseWithR8）本机全部跑通；历史 4 轮全量审查 + 1 次专项的 130+ 条问题基本闭合（R5 的 28 条在 v1.4.0 全修并有回归测试）。
2. **三条历史高危线**各修过 2~3 轮且仍有窄窗口：**提醒丢失 / 云同步覆盖 / 导入解析静默丢数据**。以后动这三块必须带回归测试。
3. **当前最大风险是"审查盲区"**：最后一轮审查基线是 v1.3.9/versionCode 50，之后又发了 6 个版本（52→58：假期天级校历、学期设置全屏页、更新体验、**班级实验安排下发**这一新外部数据源 + 新同步步骤），**没有任何正式审查覆盖**。
4. **~~已确认 1 个用户可见功能 bug~~（2026-11-07 已修，commit 05a8bf2）**：开启「隐藏周末」后内置补课日整列消失的问题已修复（`WeekResolver.hasWeekendMakeup` 统一口径 + 4 个回归测试）。见 §5.1。
5. **有 3 项产品/安全口径需要你拍板**（§5.2）：首次同步在云端无备份时会自动上传并开启云备份（与 README「云同步 opt-in 默认关闭」不一致）；云恢复回滚点只写不读；快照无时效校验、旧签名快照可重放。
6. **必须一并接手的外部依赖**：
   - **服务端代码已于 2026-10-18 迁入本仓库 `server/`**（独立 `beike.service`，监听 127.0.0.1:3001，提供 `/api/bs/*`，含云备份与更新源）；对外域名与路径不变，由 `api.caeamer.com` 的 nginx 分流器转发。
   - **release 签名与更新签名私钥只在 `keystore/`（gitignore，仅本机）**：`release.jks`、`update_signing_private.pem`、`update_signing_public.pem`。丢了就无法给已装机用户发版，也签不了更新元数据——**请立即离线备份**。

---

## 1. 项目全景

### 1.1 是什么

面向北京科技大学（USTB）学生的原生 Android 课表 App：内置 WebView 完成学校统一身份认证（App 不接触学号密码），一次扫码 / 静默同步导入**课表 + 成绩 + 考试 + 学业进度 + 班级实验安排**，并可选登录云账号整包备份；本地 Room 存储、周视图渲染、AlarmManager 上课/考试/日程三类提醒、Ed25519 验签的自有更新源。

### 1.2 功能地图

| 模块 | 能力 |
|---|---|
| 课表 | 官方教学周↔日期映射、假期天级标记+调休补课、班级实验安排（服务端按班级下发）、周视图（大节排版/单双周/冲突并排/隐藏周末/隐藏本周不上/今日高亮）、手动增删改课程、四类课程可隐藏、深色三态 |
| 教务 | 无课教室（第三方贝壳教学平台）、日程（每天/每周/一次性 + 打卡）、成绩单（排名/考核方式/未通过）、加权成绩（只算必修）、GPA（本地 4.0 制）、学分修读进度（17 类）、考试安排（D-N/座位）、成绩隐私小眼睛 |
| 提醒 | 上课提前 N 分钟、考试前一天 20:00 + 开考前 1 小时、日程按自定义提前量；精确闹钟 + 开机/换时区/重装重排 |
| 我的 | 学籍卡、云同步（换机识别 + 冲突三选一）、检查更新（自有源验签 + GitHub 兜底 + 应用内下载校验安装）、主题、退出登录、清除成绩缓存 |

### 1.3 代码规模与技术栈

- 单模块 `:app`；**87 个主源文件 / 14,283 行**，**47 个测试文件 / 4,825 行**，Kotlin 合计 19,108 行；46 个测试类、**407 个 `@Test`**。
- Kotlin 2.2.10 ｜ Jetpack Compose（BOM 2026.02.01）+ Material 3 ｜ AGP 9.2.1 + Gradle 9.4.1 + KSP 2.2.10-2.0.2（AGP 内置 Kotlin，未应用 `kotlin-android`）。
- Room 2.7.2（KSP、schema 导出 `app/schemas`，DB v6）｜ Preferences DataStore 1.1.7（单文件 `settings`，38 个 key）。
- AlarmManager（精确闹钟）｜ OkHttp 5.5.0 + kotlinx.serialization（无 Retrofit）｜ AndroidX WebKit `addWebMessageListener`。
- 密码学：AndroidKeyStore AES-256-GCM（云 token）、RSA-OAEP-SHA256（密封教务 SESSION）、Ed25519（更新元数据 + 云快照验签；平台 provider + `net.i2p.crypto:eddsa` 兜底）。
- 工具链：`minSdk 34 / targetSdk 37 / compileSdk 37`；R8 + shrinkResources；CI（GitHub Actions）四检查。
- **没有**：DI（手工构造）、navigation-compose（手写状态机）、ktlint / detekt、instrumented 测试（仅 1 个模板）、CHANGELOG。

---

## 2. 本机环境与验证记录（2026-10-17 实测）

| 项 | 值 |
|---|---|
| Android SDK | `C:\Users\shang\AppData\Local\Android\Sdk`（platforms 36 / 36.1 / 37.0，build-tools 36/36.1/37.0） |
| JDK | **`JAVA_HOME` 未配置**；本机 `C:\Users\shang\.jdks\temurin-24` 可驱动 Gradle（toolchain 21 由 Gradle 自动供给；CI 用 JDK 21） |
| Gradle | wrapper 9.4.1（缓存已就绪） |
| 模拟器 | AVD `Pixel_10_Pro`（当前未启动；`adb devices` 无设备） |
| Git | `main` 与 `origin/main` 同步（0 ahead/0 behind）；工作区干净（仅本次新增 `docs/onboarding/` 与历史 AI 残留未跟踪） |

复现命令（PowerShell，本机 JAVA_HOME 未配置时必须前置 `$env:JAVA_HOME`）：

```powershell
$env:JAVA_HOME='C:\Users\shang\.jdks\temurin-24'
.\gradlew.bat testDebugUnitTest assembleDebug --console=plain   # 1m50s，BUILD SUCCESSFUL
.\gradlew.bat lintDebug minifyReleaseWithR8 --console=plain     # 2m08s，BUILD SUCCESSFUL
```

实测结果：

- **单测 407/407 通过**（46 suites，0 failure / 0 error / 0 skip，纯 JVM 约 4 秒）。
- **assembleDebug 通过**，debug APK 64.6 MB（含完整 icons-extended 等；release 约 4.4 MB）。
- **lintDebug 通过**：47 条（40 warning / 7 hint / **0 error**），主要是 `UnusedResources`(8)、`UseKtx`(8)、`AutoboxingStateCreation`(7)、`ObsoleteSdkInt`(6)、`GradleDependency`(6)、**`MissingOnRenderProcessGone`(4，WebView 渲染进程崩溃未处理)**。
- **minifyReleaseWithR8 通过**（UP-TO-DATE，与当前输入一致 → R8 keep 规则链有效）。
- **未执行**：instrumented 测试、真机走查、真实教务扫码、云端联调（见 §6.4）。

---

## 3. 架构地图

### 3.1 分层与装配（无 DI）

```
MainActivity（单 Activity，手写 Tab/全屏状态机）
  ├─ ui/schedule   课表页 + 课程编辑/详情 + 学期设置       ┐
  ├─ ui/grades     教务 Tab（成绩/GPA/学分/考试）          │ Activity 作用域 ViewModel
  ├─ ui/todo       日程                                    │ （切 Tab 不销毁，状态跨 Tab 保留）
  ├─ ui/freeroom   无课教室                                │
  ├─ ui/profile    我的 + 更新入口                         │
  └─ ui/sync       一键同步全屏流程（唯一导入入口）        ┘
        ↓
data/repo（ScheduleRepository 唯一写入口 / GradeRows / GpaCalculator / CreditAggregator / FreeRoomRepository）
        ↓                                   ↘
Room（5 表，AppDatabase.get() 单例）        DataStore（SettingsStore，单文件 settings）
        ↓                                   ↘
reminder/*（AlarmManager 编排）              data/backup/CloudSync（脏标记→8s 去抖整包上传 / 验签恢复）
                                             data/remote/*（CloudApi / QrAuthApi / SmartClass* / Ed25519 / 签名）
import/*（JwWebView + JS 桥 + parser）← assets/import/{jw_import,jw_grades,jw_identity}.js
model/*（纯逻辑：WeekResolver / CourseMerger / SessionExpander / TodoPlanner / ReminderCourses …，全部可单测）
```

### 3.2 包职责

| 包 | 关键类 | 职责 |
|---|---|---|
| `data/local` | `AppDatabase`(v6)、5 个 Entity/DAO | Room 持久化（course/section_time/grade/exam/todo） |
| `data/pref` | `SettingsStore`(38 key)、`AppSession`、`ScorePrivacy`、`TokenCipher`、`AlarmCodec` | DataStore 配置 + **故意不落盘**的会话态 + token 加解密 + 闹钟记录编解码 |
| `data/repo` | `ScheduleRepository`、`GradeRows`、`GpaCalculator`、`WeightedScoreCalculator`、`CreditAggregator`、`FreeRoomRepository` | 写规则集中点、成绩口径收敛点 |
| `data/remote` | `CloudApi`、`QrAuthApi`、`JwSessionTicket`、`SmartClass*`、`Ed25519`、`UpdateSignature`/`BackupSignature`、`ClassSchedule` | 网络 + 签名验签 + 班级实验数据契约 |
| `data/backup` | `CloudSnapshot`、`CloudSync` | 整包快照编解码、去抖上传、验签恢复、回滚点 |
| `model` | `WeekResolver`、`WeekUtils`、`WeekLayout`、`SectionMap`、`SessionExpander`、`CourseMerger`、`CourseRowBuilder`、`NextClass`、`ReminderCourses`、`SchoolAdjustments`、`TodoPlanner` | 领域纯逻辑（本项目最有价值、最该保测试的部分） |
| `import` | `JwWebView`、三个 Bridge、`parser/*` | WebView 登录/扫码/注入脚本/解析 |
| `reminder` | `ReminderAlarmScheduler`、三个 Scheduler、`ReminderReceiver`、`BootReceiver` | 精确闹钟三条不变量、开机/换时区/重装重排 |
| `ui/*` | 各 Screen + ViewModel | Compose UI + 状态编排 |

### 3.3 三条主干数据流

1. **读**：Room Flow + DataStore Flow → `combine` → `StateFlow<UiState>` → `collectAsStateWithLifecycle`。课表页额外有 `rememberNow()` 每分钟 tick 驱动"下一节课"。
2. **写**：`db.withTransaction` → Room/DataStore → `CloudSync.markDirty`（云同步开启才会上传，8 秒去抖整包）→ 需要时重排提醒。
3. **导入（唯一入口）**：`UnifiedSyncScreen` 一次扫码 → 三个 assets JS 在教务同源环境 `fetch` → `addWebMessageListener` 回传 JSON → `parser` 解析 → 覆盖式写库（按来源）→ 重排提醒 → 可选云上传/恢复。

### 3.4 页面导航

MainActivity 内手写状态机：`tab`（schedule/jw/mine）+ `showSync` + `showSemesterSettings`，均为 `rememberSaveable`；`syncLightPage` 是唯一非 saveable 状态。两个全屏页替换掉底部 Tab 框架；**三个 Tab 没有 BackHandler（在"教务/我的"按返回会直接退出 App）**——已列入 UX 待修项。

---

## 4. 必须背下来的领域口径（10 条）

1. **周次位图**：`weekBitmap[i]` 的 `i` = 教学周序号，索引 0 为占位符；教务 `ZC` 为 34 位（README 写"32 位"是旧错）。读位一律做越界保护。
2. **教学周↔日期只有一套实现**：`WeekResolver` 是门面，实现在 `ScheduleRepository.Companion`；校历优先，校历短于 `totalWeeks` 时按最后一个校历周一顺延；校历内假期空隙返回 null。**`locateWeek`（显示）与 `teachingWeekOf`（提醒）口径仍有一处已知不一致**（R5 遗留观察项）。
3. **某天上课计划优先级固定**：补课 > 假期 > 自然星期（`WeekResolver.daySchedule`）；补课数据 = 内置 `SchoolAdjustments` ∪ 存量 `SemesterConfig.makeups`，**内置优先**；`SchoolAdjustments` 是编译进 App 的硬编码数据，新学期/临时调课须发版更新。
4. **"当前周"不落盘**：`selectedWeek`（null=未选）+ `AppSession.epoch`（退后台 +1）+ Pager 的 `initialPage / drop(1) / loaded` 三道闸共同保证"重进定位当前周、App 内翻周不被覆盖"。这是历史 bug（008571a）的修复结构，**别改成持久化**。
5. **四类课程来源同表共存**：教务导入(0)/手动(1)/示例(2)/班级实验(3)，靠 `CourseEntity.source` 区分；各类覆盖式写入只清自己的 source，并保留隐藏态；云恢复才会四源全清整表替换。
6. **Room DB v6**：5 条显式迁移 1→6，`schemas/` 只有 3–6.json（1/2 缺失，迁移测试无法做）。`fallbackToDestructiveMigration(dropAllTables=true)` **只兜"找不到迁移路径"**，不兜迁移 SQL/校验失败；降级会清空 Room 五表但保留 DataStore（半状态）。新增字段一律带默认值。
7. **DataStore 是口径唯一真相**：学期 + 校历（`firstMonday/totalWeeks/weekMondays/holidays/makeups`）全在 `settings`；`AppSession`、`ScorePrivacy` 是纯内存会话态，**绝不持久化**。
8. **提醒三条不变量**（`ReminderAlarmScheduler.kt` 顶部注释，改之前必读）：只取消未来闹钟 / 用 `NonCancellable` 原子替换记录 / 先算后换。requestCode 四段互斥（[0,7M)/[7M,8M)/[8M,8.5M)/9M），每日 04:30 有"每日脉冲"补排。
9. **一键同步两段式编排**：`IDENTITY → CLOUD_TOKEN`（内含云端探测与方向决策）→ 再按**当时**的 mode 现算后半段（TIMETABLE/GRADES/CLASS_LAB + BACKUP/RESTORE）；失败重试只跑非 OK 项、且必须"重试前半段后再现算后半段"（`SyncPlan.kt`，已单测覆盖）。
10. **更新验签 canonical 七字段**：`versionCode, versionName, changelog, force, size, url, apkSha256`（紧凑 JSON、固定键序、不含 sig）；验签失败**整体忽略自有源**并回退 GitHub；下载用 5 次 Range 断点续传，装包前再核 APK SHA-256。

---

## 5. 已确认的问题与待拍板事项

### 5.1 ~~已确认功能 bug：隐藏周末会吞掉内置补课列~~（2026-11-07 已修复）

> 已修（commit 05a8bf2）：`WeekResolver.hasWeekendMakeup` 用与 `daySchedule` 相同的"内置 ∪ 存量"口径，`ScheduleScreen.visibleDays` 复用；4 个回归测试。以下为原始记录。

- **现象**：开启「隐藏周末」后，2026-10-10（周六，第 4 周，补周三的课）**整列消失**；但 `daySchedule` 仍按补课排提醒 → "看不见却会响"，与 README:10「隐藏周末时周末补课列仍显示」相反。
- **根因**：`ui/schedule/ScheduleScreen.kt:162-171` 的 `visibleDays` 只解析了存量 `semester.makeups`，**没有合并内置 `SchoolAdjustments.makeupsFor(...)`**；而实际生效口径是 `model/WeekResolver.kt:132-139`（内置 ∪ 存量）。新装用户 `makeups` 恒为空（导入时 `UnifiedSyncViewModel.kt:441` 只保留同学期旧值），所以必现。
- **修法**：`visibleDays` 改为复用 `WeekResolver.daySchedule` 或把 `SchoolAdjustments.makeupsFor(semester.xn, semester.xq)` 并入判断；抽纯函数 + 单测（"隐藏周末 + 内置周六补课 → 周六列仍显示"）。约 5 行改动。
- 附带确认：提醒侧不受影响（走 `daySchedule`），所以只修显示即可闭环。

### 5.2 待你拍板的产品/安全口径

| # | 事项 | 现状与证据 | 建议 |
|---|---|---|---|
| 1 | **首次同步的"自动上传"语义** | 云端无备份时默认 `UPLOAD`（`SyncPlan.kt:99-108`），`stepBackup` 会 `setCloudSyncEnabled(true)` 并整包上传（`UnifiedSyncViewModel.kt:536-549`）；而该流程**无条件**登录云账号，与 README「云同步 opt-in 默认关闭」不一致 | 首次同步前弹一次明确同意（或提供"仅导入、不登录云"），并统一 README 措辞 |
| 2 | **云恢复回滚点只写不读** | `CloudSync.kt:197/214-220/231` 恢复前写 `cloud-restore-rollback.json`，全仓无任何读取入口 | 增加"撤销本次恢复"入口或导出回滚文件后清理 |
| 3 | **快照无时效校验** | `updatedAt` / `appVersionCode` 字段存在但未参与恢复判断（`CloudSync.kt:183-202`）→ 签名有效的旧快照可被重放 | 恢复前与本机 `cloudLastBackupAt` 比较，更旧则提示/拒绝 |

### 5.3 其它已确认缺陷与技术债（分级）

| 级别 | 问题 | 证据 |
|---|---|---|
| ~~P1~~ 已修 | ~~考试/日程提醒没有"打开 App 即重排"入口~~（2026-10-19 修复：MainActivity.onResume 独立走 ReminderRecovery，三类串行恢复；真机测试通过，见 REMINDER_FIX_2026-10-19.md） | `MainActivity.onResume`、`ReminderRecovery.kt` |
| P1 | **每次写库都无条件落 `cloud_dirty` DataStore + AndroidKeyStore 解密**（即使云同步关闭/已脏），翻转那次还会让所有设置 Flow 重发射、`uiState`/成绩 combine 全量重算 | `CloudSync.kt:51-60`、`ScheduleRepository.kt` 各写点、`ScheduleViewModel.kt:98-123` |
| P1 | **课表页每分钟全屏重组**并重跑 `CourseMerger` + `NextClass`；还订阅了 3 条从未使用的提醒 Flow | `ScheduleScreen.kt:157/193-203`、`Now.kt:30-48`、`ScheduleScreen.kt:134-138` |
| P1 | **网格不可垂直滚动**：横屏/分屏/大字号/小屏时第 5-6 大节被裁；`CourseDetailSheet` 也不可滚动 | `ScheduleScreen.kt:610-611/815-825`、`CourseDetailSheet.kt:49-50` |
| P2 | 加权成绩"排除课程/学期筛选"只在内存 → 进程重建即丢；DataStore 的 `weight_semester/weight_excluded` 只被云快照往返（伪持久化死键） | `GradesViewModel.kt:226-228,366-370`、`SettingsStore.kt:240-255` |
| P2 | 班级实验自动隐藏的教务"无固定时间课程"**只隐藏不恢复**，下次导入保留 hidden → 安排变更后永久隐藏且与手动隐藏不可区分 | `ScheduleRepository.kt:91-98,123-141` |
| P2 | 前台跨午夜时课表顶栏周次/状态不刷新（日程页已修，课表页未修） | `ScheduleViewModel.kt:105` |
| P2 | 无障碍缺口：空白网格格无 label/长按提示、课程卡无 `onClickLabel`、Tab 图标+文字重复朗读；`WeekdaySelector` 已修可参考 | `ScheduleScreen.kt:646-674/760`、`MainActivity.kt:105-117` |
| P2 | 深色模式课程卡仍是浅底色板；课程详情/学期设置/课表三个超大文件（893/543/529 行）未拆 | `CourseColors.kt:9-20`、`02-ui.md` §7 |
| P3 | 死代码/过期注释若干（`CloudApi.deleteBackup`、`BackupEnvelope.sizeBytes/appVersionCode` 未用、注释"四条迁移"等）；周位图长度四套口径（34/32/totalWeeks+1） | `01-data-model.md` §5、`03-import-cloud.md` §8 |

---

## 6. 构建 / 发版 / 外部依赖

### 6.1 构建

- 版本号**唯一来源**：`app/build.gradle.kts:26-27`（当前 58 / "1.5.3"）。
- debug 包名后缀 `.debug`（可与正式包并存）；release 开 R8 + shrinkResources + 自有 keystore 签名。
- **footgun**：`keystore.properties` 于配置期读取，缺失时签名配置静默为空（`app/build.gradle.kts:10-13,32-39`）——CI 上 `assembleRelease` 必然失败，属预期；本机存在时无感。
- CI（`.github/workflows/ci.yml`）：JDK 21 → `testDebugUnitTest` → `lintDebug` → `assembleDebug` → `minifyReleaseWithR8` → 上传测试/lint 报告；**无 release job、无 ktlint、无 instrumented**。

### 6.2 发版 SOP（现状全手工，四处需同步）

1. 改 `app/build.gradle.kts` 的 `versionCode` / `versionName`（惯例：每个 fix 提交也递增 versionCode）。
2. `./gradlew :app:assembleRelease` → 产物复制到 `server/public/downloads/beike/BeikeSchedule-<version>-release.apk`（`*.apk` 已 gitignore；**根目录不再放发版产物**，2026-11-07 起）。
3. `apksigner verify --print-certs <apk>` 校验（证书 CN=BeikeSchedule；SHA-256 前缀 53acb30e…，与旧包一致才能覆盖升级）。
4. 生成更新元数据到 `server/src/bs-app-version.json`：
   `python -X utf8 tools/sign_update.py --key keystore/update_signing_private.pem --version-code N --version-name X --changelog "…" --url "https://api.caeamer.com/downloads/beike/…apk" --apk <apk>`
   （canonical 规则见 `tools/sign_update.py:40-47`；中文 changelog 不能走 bash 命令行——Git Bash→GBK 乱码，用 UTF-8 文件读入后传入；服务端 `/api/bs/app/latest` 原样返回该 JSON。）
5. APK 用 workbench 单独上传到 `/opt/beike/public/downloads/beike/`（deploy 不打包 public，它是服务器持久状态），然后 `bash server/scripts/deploy.sh` 让版本 json 原子生效；线上核对 latest 版本/签名与 APK SHA-256。
6. 同步 README 版本行、必要时更新 `SchoolAdjustments` 新学期补课数据。
7. **真机验证**（当前缺闭环）：覆盖安装升级、应用内检查更新→下载→校验→安装、扫码登录、提醒重建。

### 6.3 外部依赖清单

| 依赖 | 位置 / 形态 | 接手要点 |
|---|---|---|
| **云服务端** | 本仓库 `server/`（2026-10-18 并入，独立 `beike.service`，监听 127.0.0.1:3001）；Express + MySQL；`server/src/routes/beike.js`：`POST /auth/login`、`GET/PUT/DELETE /backup`、`GET /class-schedule`、`GET /app/latest`；表 `bs_users`/`bs_backup`；密钥在服务器 `secrets/` 与 `.env` | 部署走 `scripts/deploy.sh`（workbench 链路）；改动云同步/更新契约必须客户端与服务端一起改 |
| **更新/备份签名私钥** | `keystore/update_signing_private.pem`（+ public pem），gitignore，仅本机 | 丢失=无法给已装机用户签新版本；请离线备份 |
| **release keystore** | `keystore/release.jks` + `keystore.properties`（4 个字段），gitignore | 丢失=无法覆盖升级；口令走私下渠道交接 |
| 教务系统 | `byyt.ustb.edu.cn` JSON 接口 + 页面结构 | 依赖页面结构（登录态判定、二维码 sid、三份注入脚本随 APK 发布；v1.0 计划的脚本远程热更新**未实现**） |
| 贝壳教学平台 | `ustb.smartclass.cn`（无课教室，逆向 `csrkToken`/`csrkKey`） | 平台改版即失效，无离线缓存、无 SLA |
| 学期数据 | `SchoolAdjustments`（内置补课映射）、教务校历 | 新学期、临时调课须发版；当前只内置 2026-2027-1 |

### 6.4 真机验证缺口（接手后必须补的"三件套"）

1. **真实扫码登录全链路**（R5 起文档一直标注"未覆盖真实扫码"；v1.5.3 又新增 `CLASS_LAB` 步骤）。
2. **`ACTION_MY_PACKAGE_REPLACED` 后三类提醒重建**（`dumpsys alarm` 核对）。
3. **应用内更新下载在真机上的完整闭环**（模拟器上曾观察不到落盘）。
4. 弱网/断网下的同步与续传、WebView 旋转（Activity 级旋转会导致登录页断崖，已知 `R4-C6`）。

---

## 7. 接手风险 Top 10（按严重度，详表见 `05-known-issues.md` §7）

1. **post-R5 增量无审查**（v1.4.3→v1.5.3，占当前发布面约 40%，含班级实验这一新外部数据源）。
2. **提醒可靠性架构**：重排入口仍挂课表 ViewModel，无 Application 级协调；考试/日程无"打开即重排"。
3. **云同步数据安全残余**：M7 清缓存窗口、快照只签名不加密（服务端可读全部成绩/学籍）、token 全权凭证。
4. **周次/校历/调休边界**：`locateWeek` 与 `teachingWeekOf` 口径不一致；补课数据硬编码随版本。
5. **WebView 登录态/旋转/真机扫码未闭环**：一键同步是唯一数据入口，失败即全功能不可用。
6. **数据库/备份演进**：DB v6 新列、destructive fallback、schema 1/2 缺失、快照字段增删兼容。
7. **第三方 smartclass 平台耦合**：逆向签名 + 服务器时间窗口，改版即失效。
8. **成绩计算口径**：自建 4.0 GPA / 补考重修覆盖 / 空 kcdm / 17 类学分口径，错算影响保研评优。
9. **构建/发布链路**：keystore 单点、更新源单点（Cloudflare Tunnel + Ed25519，v1.4.1 出过平台兼容事故）、全手工同步。
10. **工程可维护性**：0 仪器测试、0 迁移测试、文档漂移、无 CHANGELOG、仓库根目录与 AI 工具残留。

---

## 8. 优化路线图（按投入产出排序）

### 阶段 0：低风险高收益（1–2 天）
- 修 §5.1「隐藏周末吞补课列」bug（含单测）。
- MainActivity `onCreate` 补一次考试/日程 `reschedule`（消除"打开 App 不重排"的隐式依赖），并把每日脉冲武装与上课重排解耦。
- ~~仓库卫生 P0~~（2026-11-07 已大扫除：AI 残留/取证文件已删、28 个已合并本地分支已删、gitignore 增补防再生；2026-11-08 历史 APK 归档完成：6 个有源码提交的版本补 tag + GitHub Release，8 个无提交孤儿包与 latest_*.json 归档到 GitHub `archive-2026-11-08` Release，根目录与本地均无留存。**剩余**：远端已合并分支未清、`docs/TECH_DESIGN.md` 历史标注未加）。
- 发版脚本化：一个脚本校验 versionCode/README/latest json/签名脚本四处一致并产出元数据（当前最高频人为事故点）。

### 阶段 1：接手必须补的"门禁"（约 1 周）
- **post-R5 增量审查**（重点：`ClassSchedule` 服务端契约、`applyClassSchedule` 整组替换 + 自动隐藏、与校历周次一致性）。
- **真机三件套**（§6.4）+ 把"扫码/提醒/更新"写进发版门禁。
- 隐私/安全三项拍板落地（§5.2）：首次同步同意、回滚入口、快照时效校验。
- 测试补强：Room 迁移（Robolectric）、`CloudSync` 编排状态机、`UpdateInstaller` 续传、清缓存/云恢复路径。

### 阶段 2：体验与性能（2–4 周）
- 性能：`rememberNow` 下沉、删死订阅、`cloud_dirty` 先读后写 + 关闭云同步时跳过、token 解密进程内缓存、设置 Flow `distinctUntilChanged`、`CloudSnapshotCodec.build` 移出 Room 事务。
- UI：网格纵向滚动（或按可用高度压缩大节高度）、`CourseDetailSheet` 可滚动、Tab 返回键、冷启动定位闪跳、跨午夜顶栏刷新、冲突≥3 列的窄列可读性。
- 无障碍：空白格/课程卡/Tab 语义补齐；`strings.xml` 抽离（当前全部硬编码中文、`supportsRtl` 名存实亡）。
- 拆分 `ScheduleScreen/SemesterSettingsPage/CourseEditDialog` 三个大文件（纯移动不改行为）。

### 阶段 3：长期
- 依赖升级批处理（Room/KSP/Compose BOM）+ APK 体积（`material-icons-extended` 替换；release ~4.4MB 中 dex 3.27MB）。
- 教务适配韧性：注入脚本远程热更新（签名保护 + assets 兜底）或能力探测/心跳；恢复 JS 桥 origin 收紧到 `https://byyt.ustb.edu.cn`。
- smartclass 失效演练与用户提示；学期数据（校历/补课）改为服务端下发或可编辑，避免每学期发版。
- 把服务端 `showwe` 纳入正式交接/文档范围；`keystore` 离线备份与轮换 runbook。

---

## 9. 接手第一周清单

- [ ] 备份 `keystore/`（release.jks + 两把 Ed25519 pem）与 `keystore.properties` 到离线位置；确认服务端 `server/` 可构建/可读。
- [ ] 起 JDK/env：`$env:JAVA_HOME='C:\Users\shang\.jdks\temurin-24'`；跑通 `testDebugUnitTest` + `assembleDebug` + `lintDebug`。
- [ ] 通读本手册 §4 十条口径 + `docs/onboarding/01..05` 的 TL;DR 与风险章节。
- [x] ~~修 §5.1 bug 并提交（含中文反引号名单测）~~（2026-11-07，05a8bf2）。
- [ ] 真机装 v1.5.3 → 走一遍"扫码登录/课表/成绩/日程/无课教室/云同步/检查更新"。
- [ ] 决定 §5.2 三项产品/安全口径，排入 backlog。
- [x] 清理仓库卫生（2026-11-07/08：AI 残留/本地已合并分支/根目录 APK 已清，历史包全部归档 GitHub（含 `archive-2026-11-08` 孤儿包容器）；TECH_DESIGN 历史标注仍待做）。

---

## 10. 开发约定（沿用现有习惯）

- **提交粒度**：小步提交；每个 fix 提交末尾带 `+ versionCode N / x.y.z` 并同步 `app/build.gradle.kts`（历史 106 个 commit 均如此，发版节奏密集）。
- **注释**：写"为什么"而不是"是什么"，尤其把踩过的坑/竞态/误判边界写清楚（现有代码大量此类注释，是重要资产）。
- **单测**：JUnit4 + 反引号中文方法名（如 `` `官方校历 国庆周识别为假期` ``），纯逻辑抽到 `model/`/`data/repo` 后测；fixture 用 `docs/samples/` 的真实接口样本。当前 `testDebugUnitTest` 约 4 秒，**每次改动都应跑**。
- **文档**：设计/审查文档均为中文；新功能先写设计文档到 `docs/`（参见 `FEATURES_V1.1_DESIGN.md`/`GRADES_DESIGN.md`）。
- **不要**：持久化会话级 UI 状态（`selectedWeek`/成绩隐私）；把覆盖式写入改成 silent clear；在 R8 下依赖反射而不加 keep 规则。
- **外部契约**：改 `/api/bs/*` 需同步改服务端；快照字段只增不改且有默认值；更新元数据 canonical 顺序/字段不可动，否则老客户端验签失败。

---

## 11. 文档索引

| 文件 | 内容 |
|---|---|
| `docs/onboarding/01-data-model.md` | Room 5 表与迁移、DataStore 38 key 全表、周次/假期不变式、数据流、11 条数据层风险、单测覆盖地图 |
| `docs/onboarding/02-ui.md` | 手写导航状态机、ScheduleScreen 渲染/Pager 同步、状态管理、主题/无障碍、性能热点、UX 缺陷与拆分点 |
| `docs/onboarding/03-import-cloud.md` | 扫码/登录/注入脚本/解析入库时序、WebView 桥安全、四套密码学与攻击面、云同步、更新通道、服务端契约、13 条优化建议 |
| `docs/onboarding/04-features-build.md` | 成绩/日程/无课教室/我的、提醒子系统（不变量/requestCode/排期/失败模式）、构建与发布、依赖风险、仓库债务、10 条优化项 |
| `docs/onboarding/05-known-issues.md` | 六轮审查分类汇总与修复核实、反复出现的缺陷主题、版本/分支/文档漂移、接手风险 Top 10、未核实清单与证据索引 |
| `docs/CODE_REVIEW_2026-10-R5.md` | 最新一轮全量审查（含 §8 修复记录与 §8.4 已知残余、§9 v1.4.1 热修复盘） |
| `docs/JWXT_API.md` | 教务/教学平台全量接口参考（勿再扒站） |
| `docs/TECH_DESIGN.md` | ⚠️ v1.0 设计文档，约 1/3 假设已失效（"本地优先/无服务器/非目标成绩考试/M5 小组件"等），仅作历史参考 |
| `README.md` | 面向用户的功能说明与隐私口径（有一处"32 位位图"旧错） |
