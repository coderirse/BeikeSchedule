# BeikeSchedule · AI 接手指南

适用于整个仓库，供任何 AI 编程工具或开发者接手使用。遵循工具自身的系统规则、用户当前明确要求及适用的子目录约定。本文件提供项目背景与工作约定，不代表永久的推送或生产部署授权。

如果工具不自动加载 `AGENTS.md`，请先显式读取本文件；接手不能依赖某个 AI 工具独有的会话记忆或插件。

## 1. 接手先做什么

1. 在仓库根目录执行 `git status --short`、`git branch --show-current`、`git log -6 --oneline`，确认分支、最近改动与用户未提交的工作。不要覆盖、清理或混入用户改动。
2. 阅读 [README.md](README.md)，再按任务查下方代码入口。优先局部搜索，不必重读全部历史审查报告。
3. 当前版本读 `app/build.gradle.kts`；发布声明读 `server/src/bs-app-version.json`；数据库版本读 `AppDatabase.kt`；依赖版本读 `gradle/libs.versions.toml`。这些值不能仅凭旧文档判断。
4. 涉及近期交互规则，先看 [交互优化设计](docs/plans/2026-11-08-interaction-flow-design.md)、[实施与验证记录](docs/plans/2026-11-08-interaction-flow.md)、[日程滚轮修复](docs/plans/2026-11-09-todo-wheel-fix.md)。
5. 涉及学校接口，查 [JWXT_API.md](docs/JWXT_API.md)，优先复用现有接口与样本，不重复抓站或索取用户密码。

**文档可信度：** 当前源码、测试及已确认需求优先；README 和近期 `docs/plans/` 次之。`docs/HANDOVER.md`、`docs/onboarding/01-05`、`docs/CODE_REVIEW_*.md` 主要是历史快照，必须核对提交日期和代码。`docs/TECH_DESIGN.md` 已标记为 v1.0 历史设计。不要把历史待办直接当作当前缺陷。

## 2. 项目概况与代码入口

北京科技大学 Android 课表 App，包含周课表、课程隐藏、无课教室、个人日程、成绩/GPA、学分进度、考试、提醒和可选云备份。

- 客户端：Kotlin + Jetpack Compose Material 3，MVVM，Room + DataStore，Flow/StateFlow，OkHttp；没有 Hilt，也没有 navigation-compose。
- 正式包名 `com.caeamer.beikeschedule`；debug 后缀 `.debug`，可与正式版并存。
- 服务端在 `server/`，Express + MySQL，独立进程，提供 `/api/bs/*` 和 `/downloads/beike/*`。修改客户端通常不需要改服务端。
- Kotlin 主代码根目录：`app/src/main/java/com/caeamer/beikeschedule/`。下表 Kotlin 路径均相对此目录；同一格中省略目录的后续文件与前一个文件同目录。

| 工作内容 | 主要入口 |
| --- | --- |
| 顶层页面、返回、主题与会话 | `MainActivity.kt`、`data/pref/AppSession.kt`、`ScorePrivacy.kt` |
| 课表与课程编辑 | `ui/schedule/`，特别是 `ScheduleScreen.kt`、`ScheduleViewModel.kt`、`CourseDetailSheet.kt`、`CourseEditDialog.kt` |
| 学期、显示与提醒设置 | `ui/schedule/SemesterSettingsPage.kt`、`data/pref/SettingsStore.kt` |
| 教务分区、成绩与考试 | `ui/grades/GradesScreen.kt`、`GradesViewModel.kt`；`GradesScreen` 实际承载整个“教务”Tab |
| 个人日程与滚轮 | `ui/todo/TodoScreen.kt`、`TodoViewModel.kt`、`WheelPicker.kt`、`model/TodoPlanner.kt` |
| 课表小宠物 | `ui/pet/`；素材在 `app/src/main/assets/pets/`；独立 `schedule_pet` DataStore，不改变核心设置/云快照 |
| 宠物 AI | `ui/pet/ai/`：`PetAiClient`、`PetAiEngine`、`PetLearningContext`、`PetAiStore`、`PetAiViewModel`；独立设置页 `ui/pet/PetSettingsPage.kt` |
| 无课教室 | `ui/freeroom/`、`data/repo/FreeRoomRepository.kt`、`data/remote/SmartClass*` |
| 登录与同步编排 | `ui/sync/SyncPlan.kt`、`UnifiedSyncViewModel.kt`、`UnifiedSyncScreen.kt` |
| WebView、桥与解析 | `import/JwWebView.kt`、`import/*Bridge.kt`、`import/parser/`；脚本在 `app/src/main/assets/import/` |
| 数据读写与迁移 | `data/repo/ScheduleRepository.kt`、`data/local/`；导出 schema 在 `app/schemas/` |
| 校历、课程合并与隐藏 | `model/WeekResolver.kt`、`WeekUtils.kt`、`WeekLayout.kt`、`CourseMerger.kt`、`CourseVisibility.kt` |
| 成绩统计 | `data/repo/GradeRows.kt`、`GpaCalculator.kt`、`WeightedScoreCalculator.kt`、`CreditAggregator.kt` |
| 提醒落地与恢复 | `reminder/ReminderAlarmScheduler.kt`、三类 `*ReminderScheduler.kt`、`ReminderRecovery.kt`、`ReminderReceiver.kt` |
| 云备份与更新 | `data/backup/`、`data/remote/CloudApi.kt`、`UpdateSignature.kt`、`ui/settings/UpdateInstaller.kt` |
| 服务端与更新声明 | `server/src/routes/beike.js`、`server/src/bs-app-version.json`；部署说明见 [server/README.md](server/README.md) |

常规数据流：Compose → ViewModel → Repository → Room/DataStore/网络；持久数据通过 Flow 驱动 UI。保持这一结构，不为小改动引入整套新架构或依赖。

## 3. 修改前必须保留的约束

### 课表、日期与隐藏

- `weekBitmap[i]` 对应第 i 教学周，索引 0 占位；长度以实际数据为准，访问必须防越界。不要把旧注释里的“32 位”当作固定协议。
- 日期与教学周统一走 `WeekResolver`。调休补课优先于假期，再按自然星期；隐藏周末时，周末补课列仍应显示。显示、下一节课、隐藏过滤与提醒必须使用同一日期口径。
- 当前浏览周不跨前台会话持久化：`selectedWeek`、`AppSession.epoch` 和 Pager 的加载/同步闸共同保证“App 内保留翻周，退后台再进入回本周”。`AppSession` 与成绩隐私状态是内存态。
- 四类课程共存：教务、手动、示例、班级实验。按 `CourseEntity.SOURCE_*` 区分，某一来源的导入不能清掉其他来源。
- 整门课的编辑/隐藏/删除按名称与来源取完整行组；卡片可能由多条存储行合并。教务与班级实验课程可隐藏，不能用删除代替隐藏。
- “仅本次”按真实日期和对应课次隐藏，保留原始周次；调休日期与原星期不混用。重新导入、实验安排更新和云恢复要保留匹配的隐藏设置。使用 `CourseVisibility` / `CourseMerger`，不要在 UI 另写一套过滤。

### 日程与页面交互

- 未来日期仅预览；重复事项只操作当天的完成状态，不能让今天打卡连带勾选明天。过期的一次性事项允许补打卡，完成状态不能次日复活。
- 保留“全部日程”入口，让两周以外、已完成的一次性事项仍可查看和编辑。每周重复至少选择一天。
- **滚轮高亮、确定值、保存值必须一致。** 初始下标直接传给 `LazyListState`；确认读取同一状态的 `centeredItemIndex`。不要恢复“先显示 0 再异步定位”，也不要只靠停止滚动回调里的旧值确认。时间和提醒分钟数都适用。
- 滚轮回调同时观察布局、中心项和滚动状态；不能因父级回写重新定位并截断惯性。取消不修改原值，重建恢复位置。历史错误记录不能靠课程名或备注猜时间后批量改写。
- 顶层 Tab 与教务分区分别使用 `SaveableStateHolder` 保留浏览位置；实际换楼栋才重置教室列表。返回顺序是弹层优先、教务/我的回课表、课表再退出。
- 当前 ViewModel 主要由 Activity 持有；Composable 离开不等于 ViewModel 销毁。处理重复进入同步页的旧终态和取消，不假设重新进入一定是新对象。
- 宠物只在课表页 RESUMED 且无弹层时运行；气泡提醒只读数据，不调用通知调度器。翻周（pageTurning）不卸载宠物，走 PetPresence 进出动画：滑出淡出→新周环境稳定后从分界线/屏幕外走入，入场段落点必须 free、只穿越保护带不穿卡片；弹层/退后台仍整体卸载并恢复位置。全屏触摸观察器只能放在课表共同父节点且不消费事件，不能放在覆盖页面的兄弟浮层；宠物仅自身矩形接收拖动/点击。气泡主体用纯绘制 Box，不能换回会挡下层命中的 Material Surface；关闭按钮独立处理触摸。保留位置恢复、触摸穿透、按钮避让、开关与故障隔离。
- 宠物环境矩形复用 `WeekLayout` / `CourseVisibility` 和 Pager 实测布局；隐藏、调休及冲突分列须与实际卡片一致。停靠仅允许今日真实课程，身体放在上沿之外；自动路径和气泡优先空白处，不能只保证终点安全却穿过卡片。站起/趴下时保留同一个手势父节点，避免拖动中断。
- 欢迎频率与自动闲聊分开。可见性由课表进入/退出及前后台驱动，不能把素材重载或弹层关闭当作重新进入；独立 DataStore 保存访问/欢迎时间，历史写入不应重启整个宠物场景。
- AI 设置、Key、聊天和记忆独立加密保存在 `noBackupFilesDir`，不进入核心设置、云快照或日志；加密失败不能退回明文。模型通过只读工具按需访问分项授权的学习数据，密码、学校会话和云凭证不进入上下文。权限/供应商变更取消旧请求，派生回复和记忆保留数据来源标记，撤权后不得通过历史重新发送。
- 宠物自动联网仅在原前台可见条件下运行，不能新增后台轮询或系统通知；主动网络请求不能阻塞本地课前/考试提示。聊天关闭、退后台或用户停止须取消底层网络请求，不能把取消当普通错误后继续生成。搜索查询只使用公开主题，来源与获取时间保留，失败不冒充实时新闻。核心学习数据首发只读。

### 同步、数据与提醒

- `SyncScope.ALL` 执行完整同步；`GRADES_ONLY` 只更新成绩、学籍和学业进度；`EXAMS_ONLY` 只更新当前学期考试。按页刷新复用登录与身份核对，不触发云账号登录、课表或实验安排更新。
- 完整同步和失败重试都是两段编排：先身份/云账号与方向决策，再按当时的方向计算后续步骤。重试只跑未成功项，不能拿旧方向预先排完整张表。
- 获取/解析失败与“合法空列表”必须区分：失败保留旧数据和提醒，合法空考试可以清空。不要把超时、登录页 HTML、错误响应都折叠成空数组。
- 仅同账号、已开启同步、且服务端备份版本与本机最后成功确认版本相同时跳过云方向询问。首次关联、远端变化、缺乏依据仍需决策；方向判定中，探测失败保留 `NO_UPLOAD` 兜底，不把失败当作云端无备份。比较服务端版本时间，不用客户端当前时间冒充版本；换账号清除旧版本依据。
- 云快照恢复、清除本机成绩缓存等路径遵循 `CloudSync.withoutDirtyMarking`，避免把中间态或清空结果上传覆盖备份。账号 token 继续使用 AndroidKeyStore 加密。
- 提醒修改先读 `ReminderAlarmScheduler.kt` 的注释：默认只取消未来闹钟、先算后换、取消/设置/写记录不可中断。保留完成事项等明确动作的定向取消例外、请求码隔离、开机/更新/时间变化恢复。不要把 AlarmManager 换成旧文档里的 WorkManager。
- Room 当前为 v7（以源码为准）。结构变更增加显式迁移和 schema；新增序列化字段考虑默认值及旧云快照兼容。不要通过清库或 destructive migration 掩盖迁移错误。

### 学校登录与外部接口

- 学号密码只由用户输入给学校 WebView；不要读取、记录或另存密码。保持桥的来源校验及原生回调对 byyt 主框架的检查。
- 保留 `JwWebView.kt` 的站点白名单、页面修正、二维码原生轮询和授权跳转看门狗。学校 SSO 有合法 HTTP 回调，不能未经验证改成“一律只放 HTTPS”。
- 无课教室来自独立的 smartclass 平台，只查今天，不需用户教务账号；其签名与时间基准复用 `SmartClass*`，不要引入学号或姓名。
- 成绩去重、补考/重修、GPA、加权和学分口径复用已有计算器及测试；不要因 UI 展示需要更改统计定义。

## 4. 环境与验证

使用仓库 Gradle Wrapper，Gradle JVM 为 JDK 21，Java 字节码目标为 11。Android SDK 路径配置在未入库的 `local.properties`。SDK、AGP、Kotlin 等具体版本查构建文件，不盲目升级或额外应用 `org.jetbrains.kotlin.android`（本项目用 AGP 内置 Kotlin）。

Windows 工具不在 PATH 时先探测安装位置；Gradle 下载的 JDK 通常在用户目录 `.gradle/jdks/`。区分 PowerShell、Git Bash 和 WSL，不能把系统 `bash` 默认当成 Git Bash。

以下命令在根目录执行；Windows PowerShell 将 `./gradlew` 换成 `.\gradlew.bat`。

```bash
./gradlew testDebugUnitTest --console=plain
node --test app/src/test/js/*.test.cjs
./gradlew lintDebug assembleDebug --console=plain
./gradlew minifyReleaseWithR8 --console=plain
```

- 新增或修复逻辑，跑相关测试；影响持久数据、同步或提醒时补有意义的回归。只改文档时检查内容、路径和 diff 即可。
- UI 行为不能只靠 JVM 测试证明。涉及点击/滚轮时，至少覆盖实际触摸、初始化、快速确定、保存重开及取消；语义 `performClick` 通过不代表手指点击一定有效。
- 设备测试检查 DataStore 设置保存时，等待新值回写后再断言；弹窗/键盘收起可能改变列表可视区，需要滚动到目标行后检查显示，不能把异步保存或列表位置当成同步不变。
- 不在同一工作目录同时启动多份 Gradle 构建。遇到本机配置缓存/并行问题，可加 `--no-configuration-cache --max-workers=4`；只停止本次任务启动的进程。
- 本机内存紧张时模拟器与构建并行会互相挤掉（Gradle daemon 和模拟器都可能被系统杀掉）；可给模拟器 `-memory 2048`、构建降 `--max-workers=2`，崩溃后先查进程和内存再重试。
- 当前功能分支交互设备回归共 9 个测试类，需已启动的模拟器或连接的设备：

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.caeamer.beikeschedule.PetBubbleTouchTest,com.caeamer.beikeschedule.PetInteractionTest,com.caeamer.beikeschedule.PetWelcomeInteractionTest,com.caeamer.beikeschedule.PetPoseRenderTest,com.caeamer.beikeschedule.TodoInteractionTest,com.caeamer.beikeschedule.WheelPickerInteractionTest,com.caeamer.beikeschedule.NavigationInteractionTest,com.caeamer.beikeschedule.PetAiSettingsInteractionTest,com.caeamer.beikeschedule.PetAiChatInteractionTest --console=plain
```

- JVM 样本在 `app/src/test/resources/`，JS 测试在 `app/src/test/js/`，设备测试在 `app/src/androidTest/`。
- CI 定义见 [.github/workflows/ci.yml](.github/workflows/ci.yml)：main 推送/PR 检查单测、JS、Lint、debug、release R8；CI 不持有正式签名密钥，不发布正式 APK，也不自动跑设备测试。
- 交付时报告实际运行的检查及未验证部分；模拟器通过不能表述成用户真机或真实教务登录通过。

## 5. Git、变更范围与密钥

- 默认继续当前任务分支；用户要求新分支时，从已更新的主干或指定基线创建。现有命名采用 `codex/<topic>`，用户指定其他名称时按用户要求。
- 提交、推送、合并、发版按当前请求范围执行。用户已明确授权的步骤，验证后直接完成，不重复索要同一确认。不能把历史发布请求视作以后每次编辑都要上线。
- 不擅自 force-push、移动已发布 tag、重写共享提交或删除其他工作区。提交前检查 `git diff --check` 和暂存内容，不夹带本地文件。
- `keystore.properties`、`keystore/`、服务端 `.env` 与私钥都不入库；不在回复、日志或命令输出中打印密码、token、cookie、私钥。不要生成替代密钥覆盖现有正式签名。
- APK、截图、日志、临时验证脚本放 `out/`、`app/build/` 或规定的未入库目录；不要在仓库根目录堆构建和取证产物。`out/prepare_release_*.py` 是本机临时文件，不能假设其他机器有它们。

## 6. 用户要求发布时的完整流程

发布工具：JDK/Android build-tools、Node.js、Python 3 + OpenSSL、Git/GitHub CLI；服务器部署使用已有 Workbench CLI 配置。平台相关路径在本机探测，不把开发者电脑的绝对路径写进业务代码。

1. 确认用户指定的发布分支及工作区。若要求先合并主干，更新 main、保留所有改动、处理冲突后验证；不要只发一个未合并的旧 APK。
2. 递增 `app/build.gradle.kts` 的 `versionCode` 和 `versionName`，同步 README。仅改文档不需要自动改版本。
3. 运行所需检查及 `./gradlew assembleRelease`。正式签名来自本机 `keystore.properties`；CI 的 `minifyReleaseWithR8` 不能代替签名正式包。
4. 用 Android build-tools 的 `aapt2 dump badging` 核对包名/版本，`apksigner verify --print-certs` 比对现有正式 APK 证书，确认能覆盖升级。
5. APK 存为 `server/public/downloads/beike/BeikeSchedule-<version>-release.apk`。用仓库 [签名工具](tools/sign_update.py) 生成 `server/src/bs-app-version.json`，示例：

```bash
python tools/sign_update.py --key keystore/update_signing_private.pem --version-code <code> --version-name <version> --changelog "<更新说明>" --url "https://api.caeamer.com/downloads/beike/BeikeSchedule-<version>-release.apk" --apk "server/public/downloads/beike/BeikeSchedule-<version>-release.apk"
```

该脚本输出 JSON 到 stdout，需以 UTF-8、无 BOM 写入上述文件。中文说明不要通过 Git Bash/GBK 命令行往返，可从 UTF-8 文件读取后用 Python `subprocess` 参数列表传递。普通版本不传 `--force`。

6. 更新签名按固定顺序覆盖 `versionCode, versionName, changelog, force, size, url, apkSha256`，紧凑 UTF-8 JSON、不含 `sig`。用 App 中的发布公钥核验，不能只检查“签名不为空”。同时核对版本、文件大小及 SHA-256。
7. 提交并推送发布代码，按请求完成 CI；tag 指向对应发布提交，保留已有 tag。GitHub Release 上传同一份已核验 APK，版本说明写入 UTF-8 文件，用 `gh release create --notes-file` 等文件参数保留中文和换行。
8. 服务器拓扑、实例与目录参数读取 [server/README.md](server/README.md)、[deploy.sh](server/scripts/deploy.sh)。当前服务为 `beike`，应用目录 `/opt/beike`，监听 `127.0.0.1:3001`；外部域名 `https://api.caeamer.com` 经 Cloudflare Tunnel/nginx 转发。
9. APK 单独上传到 `/opt/beike/public/downloads/beike/`。只更新版本时可仅替换版本 JSON 并重启；完整服务端部署才使用部署脚本。保留 `.env`、`secrets/`、历史 APK、数据及可回退的旧声明；先校验上传文件，再原子切换 JSON。
10. 更新声明由服务启动时载入，需要重启 `beike`。核对 `/health` 返回服务和数据库正常，再核对公网 `/api/bs/app/latest`，完整下载 APK 比较大小与 SHA-256，核对 GitHub Release 附件。部署脚本部分检查会容忍失败，不能只凭“部署完成”字样判断上线成功。
11. 公网 Python 默认 UA 曾被 Cloudflare 返回 403；可按真实客户端 `User-Agent: okhttp/5.5.0`（以当前依赖为准）复核，不为排查探针问题关闭安全校验。发布结束报告版本、提交、Release 链接、更新源验证及当前分支。

## 7. 当前接手快照（2026-11-12）

- 主干基线：`v2.1.2` / `versionCode 69`（2026-11-13 宠物活力优化从 main 发布，发布提交 `ceafe0a`）。此前 `v2.1.1` / 68（2026-11-11 小修批次）、`v2.1.0` / 67（合并提交 `af26115`）；更早主干为 `v2.0.2` / 66。实际线上版本仍以更新声明和 Release 为准。
- `codex/pet-liveliness`（2026-11-12，自 v2.1.1 建）：宠物活力三项优化——主动话语主题轮换+禁说清单+相似度兜底+欢迎去重；热点专用计时触发（hotspotAvailable，首播 90–180s，之后 hotspotMinutes）与 AI 气泡 25s 超时；走动循环不再被气泡/触摸重启（6–14s + 原地微动作 + 1/3 随机漫游），翻周改 PetPresence 进出动画（paused 与 pageTurning 拆分）。已快进合入 main 并随 v2.1.2 发布；分支保留。设计与实施记录见 `docs/plans/2026-11-12-pet-liveliness-design.md`、`docs/plans/2026-11-12-pet-liveliness.md`。
- v2.1.2 发布验证：490 项 JVM、15 项 JS、9 类共 39 项模拟器交互、Lint 0 errors / 46 warnings、debug 与正式签名 release/R8 构建通过；证书延续 v2.1.1（SHA-256 `53acb30e…`），更新声明已用 App 内公钥验签；公网声明逐字段一致、公网 APK 完整下载 SHA-256 一致（`3443515d…`）。真实供应商 Key 与用户手机实测仍未完成。
- `codex/pet-ai-polish` 小修批次（气泡句末截断、记忆显式意图、多主题热点缓存、历史总量预算、工具超限错误类型）已快进合入 main 并随 v2.1.1 发布；发布分支 `codex/pet-llm-integration` 保留。宠物原有交互设计见 `docs/plans/2026-11-10-pet-surfaces-design.md`。
- 2026-11-11 小修批次验证：484 项 JVM、15 项 JS、9 类共 37 项模拟器交互通过，Lint 0 errors / 46 warnings，debug 与 release R8 构建通过；随后经用户授权推送并发布。真实供应商 Key 与用户手机实测仍未完成。
- v2.1.1 发布重跑 484 项 JVM、15 项 JS、Lint 和正式签名 release 构建通过；证书延续 v2.1.0（SHA-256 `53acb30e…`），更新声明已用 App 内公钥验签。CI、GitHub 附件与公网下载校验以本次交付记录为准。
- 2026-11-11 用户已逐项确认 AI 首发范围并授权实现，随后明确授权直接发布供手机测试：MiMo、DeepSeek、自定义 OpenAI 兼容供应商，独立搜索来源，分项授权与只读学习分析，自定义人格、本机聊天和可管理长期记忆，点击进入可展开的半屏聊天，自动请求仅课表可见时运行。设计、实施及发布记录见 `docs/plans/2026-11-11-pet-ai-design.md` 与 `docs/plans/2026-11-11-pet-ai.md`。
- 本轮本地 477 项 JVM、15 项 JS、9 类共 37 项模拟器交互通过，Lint 0 errors / 46 warnings，debug 与 release R8 构建通过。AI 联网流程通过模拟供应商响应验证，未使用真实供应商 Key、用户真机或真实教务登录；不能将其表述为真实模型联调完成。
- v2.1.0 发布重跑 477 项 JVM、15 项 JS、Lint 和正式签名 release 构建通过；正式包在模拟器覆盖安装、冷启动、加密设置保存重开及半屏聊天通过，证书延续 v2.0.2，更新声明已用 App 内公钥验签。真实模型及用户手机测试尚未完成；CI 和公网下载校验以发布交付记录为准。
- 已合并的关键修复：按日期隐藏课程与考试独立刷新、日程全部入口与日期打卡、导航状态保留、成绩独立刷新、云版本判断，以及时间/提醒滚轮显示与保存一致性。
- v1.5.8 历史基线验证过 430 项 JVM、15 项 JS、18 项设备交互测试；当时主干 CI 通过，用户已反馈滚轮修复实测正常。这是历史验证记录，不代表未来修改无需再测。
- v2.0.2 合并发布重新通过 458 项 JVM、15 项 JS、Lint（0 errors、46 warnings）、debug、正式签名 release/R8 构建；APK 证书与 v2.0.1 一致，更新声明已用 App 内公钥验签。发布后的 CI、模拟器及公网下载验证以本次交付记录为准。
- v2.0.1 本地 458 项 JVM、15 项 JS、29 项设备交互/渲染通过，详情见本轮设计文档；模拟器回归不能代替用户手机实测。低内存模拟器可能在冷启动/安装时结束 instrumentation，需区分系统杀进程与应用异常，并保留日志。
- 后续趣味性改动应保留课程/地点可读性、时间与提醒准确性、现有隐私及同步规则，不顺带改动核心数据口径。运行交互回归时连同欢迎、气泡和姿态测试检查。

维护本文件时优先保留准确入口和关键约束；新修复只记录可复用的原因与回归点。已过期的版本/分支信息标注日期或更新，不把它扩成另一份巨型历史报告。
