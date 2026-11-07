# 04 · 其余功能模块与构建/发布工程（成绩 / 日程 / 无课教室 / 我的 / 提醒 / Build）

> 交接勘察小队 features-build 产出，只读深读，未改任何生产代码、未跑 gradle。
> 基线：`main` @ 106 commits，v1.5.3 / versionCode 58；工作区快照 2026-10-17（本地 `app/build/reports` 为本次构建产物，clean 后会消失）。
> 本文所有结论均带 `文件:行号`；行号以当前工作区文件为准（`app/build.gradle.kts` 等源文件非空行数比任务书给的多约 5%，属空行计法差异）。

## 0. 结论先说（接手优先级）

| 模块 | 成熟度 | 最大风险 / 接手要点 |
|---|---|---|
| 提醒子系统 | 高（三条不变量有单测钉住） | 失败面在系统侧：Doze/OEM 清闹钟、精确闹钟/通知权限被收；考试提醒没有"打开 App 即重排"入口，恢复依赖上课提醒链路重排时顺带排的每日脉冲 |
| 无课教室 | 中高（签名自愈链完整） | 依赖逆向的站点算法与 `csrkKey`，平台改版/轮换只能靠三层兜底 + 一次自愈重试；无任何离线缓存 |
| 成绩 | 高（口径高度集中） | 所有派生值必须走 `GradeRows.bestPerCourse` 单一收敛点，改动任何一条计算链前先读 `GradesUiState` 的 `by lazy` 分支；学分类别口径是"接口要求 × 本机汇总"混合 |
| 日程 | 高（状态机纯函数可测） | 提醒与展示两套时间基准要对齐（触发日 vs 出现日）；跨午夜提前量是唯一已知窄窗 |
| 我的页 | 中高 | 危险操作都二次确认；入口与 README 有一处偏差；更新链是"自有源验签 + GitHub 兜底 + 应用内下载校验"三段 |
| 构建/发布 | 中（能稳定出包） | 版本号/更新元数据/APK 全手工同步，无 release workflow；versionCode、README、latest json、签名脚本四处无自动校验 |
| 仓库卫生 | 低 | 根目录堆 28 个 APK/18 xml/10 png/11 json，52 个分支引用，AI 工具残留 196 文件 2.2MB，无 ktlint、无 instrumented 测试 |

## 1. 教务 Tab 数据流总览（4 分段）

- 分段枚举顺序即持久化序号：`FREE_ROOM, TODO, SCORES, EXAMS`，默认无课教室；`section:null` 表示偏好还没读到（不渲染任何分段页，避免白建 FreeRoomViewModel 发 4 个外网请求）`GradesViewModel.kt:40-49, 230-237`、`GradesScreen.kt:182-221`。
- 抓取已全部搬到「一键同步」：`jw_grades.js` 桥回 8 段 JSON → `GradesParser.parseGrades/parseGpa/parseStudentProfile`、`ExamsParser.parseExams`、`CreditProgressParser.parseCategories/parseProgress`，再写 Room（覆盖式）与 DataStore 缓存 `UnifiedSyncViewModel.kt:452-501`。
- 教务页 VM 只消费 `repo.grades/exams` + `settings.*` 流并 `combine` 成 `GradesUiState`，再 `catch` 兜底坏 JSON（落盘坏数据不能让"每次进教务页都崩"、连清缓存入口都进不去）`GradesViewModel.kt:279-321`。
- `stateIn(WhileSubscribed(5000))` + 派生值全部 `by lazy(NONE)`（此前组合期一次 ScoreCard 读 10+ 次派生值，`failedBySemester` 更是学期数 × 全量 filter）`GradesViewModel.kt:82-218`。
- 关键区分：考试 `null`=没拿到数据（错误体/登录页），空列表=确实没考试；只有非 null 才覆盖本地并重排考试提醒 `ExamsParser.kt:30-40`、`UnifiedSyncViewModel.kt:474-483`。成绩为空是合法的（新生/未评教），不阻断学籍/考试/学业进度落盘 `UnifiedSyncViewModel.kt:466-480`。

## 2. 成绩模块

### 2.1 数据来源与解析

- 接口：`/cjgl/grcjcx/grcjcx`（成绩+单科排名/考核方式）、`/cjgl/grcjcx/getgpa`（GPA 概览）、`/cjgl/cjzhtjcx/cjcx/queryXflbyq`（学分要求）、`queryBxkqk`（毕业总进度）、`/kscxtj/queryXsksByxhList`（考试）；一次会话并发抓，桥参数顺序见 `GradesBridge.kt:7-35`。
- `GradesParser` 一律 `contentOrNull`（显式 JSON null 用 `content` 会得到字面量 `"null"`），解析失败单行跳过 `GradesParser.kt:36-72`；GPA 只有 `BL`/`HDXF` 必需，`PM`/`ZRS` 可缺（学期初未排名）`GradesParser.kt:81-90`；`getgpa.BL` 是平均学分绩/20 口径不可当 4.0 GPA（会超 4.0）`GpaCalculator.kt:6`。
- 考试字段来自列定义 JS，`KSSJMS` 文本用正则防御性拆日期/起止时间，解析不出留空、UI 回退原文（支持"日"后无空格、`(周六) ` 插入、可省秒）`ExamsParser.kt:19-28, 36-79`。

### 2.2 GPA（本地 4.0 制）

- 映射：90+=4.0 / 85-89=3.7 / 80-84=3.4 / 75-79=3.0 / 70-74=2.4 / 65-69=2.0 / 60-64=1.0 / <60=0 `GpaCalculator.kt:23-32`。
- 口径：纳入全部有数字成绩课程（不分必修/选修），等级制排除；同课（`identityKey`）有补考/重修行只从中取最高分，且补考行**取代**正考行（因此"正考 58、补考 50"取 50）；等级制已通过的正考行不被挂科重修行取代（防静默丢学分）`GradeRows.kt:38-54`、`GpaCalculator.kt:44-59`。
- 0 学分课程排除（fixture 里 7 门 0 学分课会把"纳入 N 门"虚增）；挂科计 0 分但学分进分母 `GpaCalculator.kt:49-58`。
- 唯一未覆盖边界：正考分高于补考分仍挂时取补考分，属"补考取代正考"口径必然结果，已由 `GradeRowsTest`/`GpaCalculatorTest` 钉住 `GradeRows.kt:26-31`。

### 2.3 加权成绩（只算必修）

- 公式 Σ(成绩×学分)/Σ学分；过滤条件：`kcxz=="必修"`、数字成绩有限、`xf>0`、用户排除集合不含该课 `WeightedScoreCalculator.kt:29-46`。
- 排除键用课程身份 `GradeEntity.identityKey = kcdm.ifBlank{kcmc}`（裸 kcdm 会让多门空 kcdm 课共享 `""` 一起被排除）`GradeEntity.kt:52-58`、`WeightedScoreCalculator.kt:20-28`；勾选列表与计算共用同一收敛集合 `GradesViewModel.kt:190-213`、`GradesScreen.kt:868-931`。

### 2.4 学分修读进度（17 类口径）

- 要求侧来自 `queryXflbyq`，仅保留 `yqwcxf>0` 的行，实测 fixture 恰为 **17 行**（通识 67 / 学科平台 33 / 专业核心 14 / 基础实习 3 / 专业实习 29 / 实验 8 / 劳育 1 / 创新创业 5 / 创新学分 2 / 国防公益 2 / 专业拓展 10+5 / 素质拓展 10+美育·外语·人文·创新创业课各 2）；注意 17 类要求学分之和 ≠ 毕业总要求——毕业总进度另来自 `bxkqk`（YQXF=125.5 / YQMS=58 门 / 已修 89.5）`CreditProgressParser.kt:24-64`、`app/src/test/resources/xflbyq.json`、`bxkqk.json`。
- 已完成侧不看接口的 `ywcxf`，而是**本机按成绩单 `kclb` 本地汇总**：先 `bestPerCourse` 收敛、只计已通过、按 `kcdm.ifBlank{kcmc}` 去重后按类别求和，保证重修不会把 4 学分算成 8 学分 `CreditAggregator.kt:27-33`。
- 类别行匹配顺序：精确同名 → 最长后缀（`素质拓展—美育(素质拓展)` 匹配 `美育(素质拓展)`）→ 父行聚合子项（`行名` 的 `(行名)` 形态，避免父行 0/10 而子行各自 2/2 自相矛盾）`CreditAggregator.kt:36-49`。
- 毕业总进度来自 `queryBxkqk`，`yqmsxf.YQXF/YQMS` 字段缺失/形态异常一律当"没有进度"（不能直接 `.jsonObject` 抛异常）`CreditProgressParser.kt:66-91`；UI 上"毕业总要求"与"17 类"分开缺失处理 `GradesScreen.kt:327-331, 374-449`。
- 口径注意：卡片文案"已完成学分按本地成绩单汇总，口径与教务网一致"`GradesScreen.kt:440-445`；转移学分只做展示（`yzhxf"（含转移 X）"`）`GradesScreen.kt:470`。

### 2.5 考试安排与 D-N 倒计时

- 排序：先有日期在前，再按 `ksrq/kssj/kssjms`；分组两级比较器（日期 + key 本身）——只按日期比较会把"时间待定"和"日期解析失败"当同一 TreeMap key，整组考试静默消失 `GradesViewModel.kt:133-141`、`GradesScreen.kt:486-508`。
- 倒计时以日期为粒度：同一天显示"今天"，未来显示 `D-N`（`ChronoUnit.DAYS.between`），过去不显示徽章；`rememberNow()` 跨午夜刷新 `GradesScreen.kt:519-520, 587-625`。
- 单行：时间回退 `kssj–jssj` → `kssjms` 原文 → "时间待定"；座位号单独徽章；已结束考试用 `onSurfaceVariant` 淡化而非整体 alpha（对比度）`GradesScreen.kt:627-676`。
- 空态区分"从未抓取"（引导去获取）与"抓过但本学期没排考"`GradesScreen.kt:535-560`。

### 2.6 隐私小眼睛

- 状态是**会话级**、不落盘：默认隐藏，点眼睛显示后前台内一直显示，退后台立即复位（持久化会让"显示"跨进程存活，违背退出即隐藏）`ScorePrivacy.kt:7-27`、`GradesViewModel.kt:347-350`、`MainActivity.kt:131-139`。
- 第二道防线：显示分数期间禁止把画面放进最近任务缩略图 `MainActivity.kt:123-153`。
- 掩码范围含所有派生数字（GPA/加权、纳入门数/学分、专业排名、每行排名、详情页学分/排名、勾选列表分数与学分），此前只有分数被掩、排名与学分外泄，已补齐 `GradesScreen.kt:790-817, 822-850, 919-925, 936-981, 678-715`。

## 3. 日程模块（重复规则 + 打卡状态机）

- 实体：3 种重复模式（0 每天 / 1 每周 / 2 一次性）、`weekdays` 7 位位图（索引 0=周一，默认 `1111100`）、`date`、`time`（HH:mm 单时间点）、`remindMinutes`（1..120）、`lastDoneDate` `TodoEntity.kt:21-57`。
- 展示状态机（纯函数，`TodoPlanner` 全可 JVM 测）：
  - `occursOn`：每天恒 true；每周按 `dayOfWeek.value-1` 取位（位图越界/坏数据一律不出现，宁漏不误显）；一次性仅当 `date==今天` `TodoPlanner.kt:22-30`。
  - 窗口 = 今天起 14 天，按日期分组、日内按解析后的时间升序（解析失败沉底）`TodoViewModel.kt:20-21, 50-57`、`TodoPlanner.kt:95-108`。
  - 打卡 = 把 `lastDoneDate` 写成今天/清空；`isDoneToday` 只比等值 → **次日自动复活**，无需定时任务 `TodoEntity.kt:41-42`、`TodoViewModel.kt:74-90`。
  - 打卡读-改-写用 `Mutex` 串行化（只"读最新值"关不掉竞态窗口）`TodoViewModel.kt:47-48, 81-90`；"今天"基准独立成流、由界面 `rememberNow` 在 ON_RESUME/跨分钟推进，避免前台跨夜仍按昨天算 `TodoViewModel.kt:40-63`、`TodoScreen.kt:99-103`。
  - 一次性过期区：日期在过去且没打卡（`lastDoneDate != date && != 今天`）才留在"已过期（未完成）"；补打卡后即离开 `TodoPlanner.kt:72-88`、`TodoScreen.kt:213-243`。
  - 提醒：触发时刻 = 计划时刻 − 提前量，只排仍在未来的；**今天已打卡的不排**、未来日不受影响（重复任务次日复活）`TodoPlanner.kt:51-70`。
- 提醒重排触发：todo 表任何变更（新增/编辑/删除/打卡）→ `TodoReminderScheduler.reschedule`（按值去重防"重排→写 codes→再重排"自激）`GradesViewModel.kt:323-336`；另加每日脉冲/开机/云恢复 `ReminderReceiver.kt:30-47`、`BootReceiver.kt:21-46`、`CloudSnapshot.kt:293-295`。
- 表单：全部 `rememberSaveable` + 内容 `verticalScroll`（此前小屏/横屏底部删除/保存行被裁掉）；日期用 M3 DatePicker 且按 **UTC** 还原毫秒（负偏移时区会前移一天）`TodoScreen.kt:337-541`。
- 滚轮（iOS 风格）：视口正中项即选中；两条硬约束——只在滚动停止后回写父级、定位 effect 只在进入组合执行一次，否则 fling 会在第一个中心项被掐断、120 项分钟轮盘一次手势只走一格 `WheelPicker.kt:35-117`；分钟轮盘 1..120，选中项放大提亮 `TodoScreen.kt:633-666`。
- 已知窄窗（R5 记录、已部分修复）：例如"提前量把触发时刻推过午夜"时，排期码以**触发日**为基准、打卡定向取消必须换算同一基准，否则打了卡仍收到提醒 `TodoReminderScheduler.kt:76-82, 131-157`；本段 requestCode 只有 1e6 槽，8 天 × 20 个每日事项时碰撞概率约 1.3%（后果=后设闹钟覆盖前一个，少弹一条）`TodoReminderScheduler.kt:139-141`。

## 4. 无课教室（贝壳教学平台 smartclass）

数据流：`FreeRoomScreen` → `FreeRoomViewModel`（页面状态机/请求序号）→ `FreeRoomRepository`（key→签名→调用→排序→被拒自愈）→ `SmartClassKeyProvider`（key 三层兜底 + 服务器校时）/ `SmartClassApi`（HttpURLConnection）→ `SmartClassParser`；与教务系统/学号完全无关。

- **签名 `csrkToken`**：把 13 位毫秒时间戳逐位数字当索引，从 `csrkKey` 取字符，结果恒 13 字符 `SmartClassCrypto.kt:77-103`；`csrkKey` 藏在 `/config.json` 的 `domainConfig`（AES-256-CBC/PKCS7，Key/IV 为逆向所得常量）`SmartClassCrypto.kt:18-49, 65-75`。
- **服务器时间依赖**：token 有效窗口实测 `[现在, 现在+5 分钟]`，设备时钟慢 1 分钟即全失败，所以签名时间 = 本机 + 服务器偏差 + 2 分钟余量 `SmartClassCrypto.kt:54-58`、`SmartClassKeyProvider.kt:100-101`；校时首选 `/config.json` 的 HTTP `Date` 头（该请求无需签名，不存在"要签名才能校时"的循环），失败才回退签名的 `/Home/GettimeDif` `SmartClassKeyProvider.kt:103-121`、`SmartClassApi.kt:97-105`。
- **key 缓存三层**：运行时解密 `/config.json`（正常路径，服务端轮换也能跟上）→ 本地 DataStore 缓存（单独 `smartclass` 文件，带 corruptionHandler）→ 内置常量（首启离线兜底）；`forceRefresh` 时也允许回落缓存 `SmartClassKeyProvider.kt:50-92, 19-22`。
- **失败自愈**：服务端轮换 key 后返回 `csrf key validate error` → `invalidate()` + 重新校时 + 强制重拉 + 重试**一次**；**每个接口都必须走 `withSignedRetry`**（此前只有查空教室走，首请求 listBuildings 轮换失败使自愈整条不可达）`FreeRoomRepository.kt:13-27, 116-135`、`SmartClassApi.kt:283-291`。
- 请求细节：固定 UA/Referer、10s/15s 超时、body 手工拼 JSON 并转义、三种失败来源（传输异常/非 2xx/`code!=0` 或非 JSON）全部 failure——不能把网关维护页当"没有空教室"`SmartClassApi.kt:144-171, 175-240, 243-276`；解析一律 `opt` + `str`（对显式 null 防御），`data` 有内容却解析不出任何行时抛错（字段改名不能伪装成"无数据"）`SmartClassParser.kt:70-101, 104-171`。
- **UI 状态机**：`expandedIndex` 三态（null=跟随当前时间 / -1=全部收起 / n=手动）；`slotPhase` 分 PAST/CURRENT/UPCOMING、`allSlotsPast` 明说"今天的时段已全部结束"；`loadSeq` 作废在途请求（HttpURLConnection 阻塞读不响应协程取消）；切楼栋清空旧楼教室并重置展开态；刷新失败保留旧数据只在顶部提示、数据超过 5 分钟给"刷新"入口 `FreeRoomViewModel.kt:25-99, 117-161, 163-244`、`FreeRoomScreen.kt:86-184, 424-435`。
- 排序/展示：时段内教室名自然排序（数字段按数值，1002 排在 503 后）`FreeRoomRepository.kt:106-108`、`RoomNameOrder.kt:18-70`；座位/空座率定宽右对齐，`noSeatRate=null` 显示"暂无数据"而非 0% `FreeRoomScreen.kt:352-390`。
- 维护风险与边界：整套签名参数是逆向站点所得，**平台改版即失效**（服务端换 AES Key/IV 或换 token 算法时客户端只能报"暂时查不到"）；空教室是实时数据不做跨会话缓存、无离线；0% 空座仍按"空教室"列出、展开区不限高、全天结束后默认展开第一个历史大节（UX 待优化）`FreeRoomViewModel.kt:56-57, 91-99`、`FreeRoomScreen.kt:121-141`。

## 5. 我的页（学籍 / 云同步 / 更新 / 危险操作）

- 学籍卡：数据是抓成绩时顺手缓存的 `StudentProfile`（姓名/学号/学院/专业/班级/年级/学籍状态），离线展示；字段缺失只隐藏该半边、不凭空断言（避免"不在校 · 已注册"）`ProfileScreen.kt:165-224`、`GradesParser.kt:92-114`、`SettingsStore.kt:54-66, 212-238`。
- 云同步（opt-in，默认关）：未登录 → 入口跳一键同步登录；已登录 → 开关（开即全量备份一次）+ 立即备份 + 从云端恢复 + 退出云账号（只清本机 token，云端备份保留）`ProfileScreen.kt:228-293`、`SettingsViewModel.kt:91-150`、`SettingsStore.kt:302-370`。
- 检查更新：进页面自动查；自有源 `GET /api/bs/app/latest` 过 Ed25519 验签（无签名/失败整体忽略该源）→ 失败回退 GitHub Releases（版本号逐段数字比较）；自有源失败会在行下显示具体原因，不再静默回退 `SettingsViewModel.kt:193-254, 262-299`、`UpdateSignature.kt:120-151`。
- 更新展示/安装：非 force 走页内卡片；force 为不可点外部/返回键关闭、无"关闭"按钮的阻塞弹窗；有已验签 `apkSha256` 时应用内下载（OkHttp 流式 + `.part` 断点续传 + 摘要校验 + FileProvider 交系统安装器），GitHub 兜底仍开浏览器 `ProfileScreen.kt:304-337, 509-548, 758-848`、`UpdateInstaller.kt:27-45, 73-186`、`UpdateSignature.kt:51-52, 136-151`。
- 通用/外部/外观入口：GitHub、版本号（PackageManager 读，非 BuildConfig）、联系开发者（mailto 兜底无邮件客户端）、清除成绩缓存、退出教务登录、评教/大创外链、隐藏本周不上的课、主题三态（三选一弹窗，不做循环切换）`ProfileScreen.kt:338-491, 550-587, 873-877`。
- **危险操作清单**（都有确认弹窗 + 文案写明后果）：
  - 清除成绩缓存：清成绩/GPA/考试/学业进度 + 取消已排考试提醒，全程 `withoutDirtyMarking`（否则 8 秒后把"全空"整包上传覆盖云端）；弹窗明示"云端备份保持原样"`SettingsViewModel.kt:168-191`、`ProfileScreen.kt:589-613`、`CloudSync.kt:48-52, 103-109, 229`（suppressDirty + 8s 去抖）。
  - 从云端恢复：整包覆盖本机，云端为准 `ProfileScreen.kt:615-633`、`SettingsViewModel.kt:126-145`。
  - 退出教务登录：只清 WebView 会话/Cookie，不删课表成绩 `ProfileScreen.kt:655-675`；退出云账号：只清本机 token `ProfileScreen.kt:635-653`。
- 入口偏差（文档债）：README 说"我的 → 隐藏的课程"，实际在**课表 → 学期设置 → 隐藏的课程**（同页还有上课提醒开关、通知/精确闹钟权限诊断）`README.md:13`、`SemesterSettingsPage.kt:272-343, 345`。

## 6. 提醒子系统（AlarmManager）

### 6.1 落地层三条不变量（改之前必读）

1. **只取消"触发时刻仍在未来"的闹钟**：Doze 下已到点但系统还没投递的闹钟绝不能取消（每日脉冲/开 App 可能恰好踩进维护窗口），已到点记录保留 6 小时宽限 `ReminderAlarmScheduler.kt:13-40, 59-78, 106-124`。
2. **取消 + 设置 + 写回记录是不可中断的一整段**（`NonCancellable`）：旧实现中途取消会停在"旧闹钟全取消、新闹钟没排、记录也清空"`ReminderAlarmScheduler.kt:24-27, 84-126`；`apply` 调用前必须"先算后换"（所有 Room/DataStore 读在取消之前）`ClassReminderScheduler.kt:92-122`。
3. 精确闹钟：`setExactAndAllowWhileIdle(RTC_WAKEUP)`；`canScheduleExactAlarms()==false`（Android 14+ 默认不授予 SCHEDULE_EXACT_ALARM）时退化为 `setAndAllowWhileIdle` `ReminderAlarmScheduler.kt:128-135`。

### 6.2 requestCode / 通知 ID 规则（四段互不重叠）

| 类 | 规则（内容寻址，跨轮次稳定） | 区间 |
|---|---|---|
| 上课 | `floorMod(hash("courseId@日期"), 7_000_000)` | [0, 7M) `ClassReminderScheduler.kt:43-52, 172-181` |
| 日程 | `7_000_000 + floorMod(hash("todoId@触发日"), 1_000_000)`（同一事项不同出现日各一个码） | [7M, 8M) `TodoReminderScheduler.kt:39-40, 131-157` |
| 考试 | `8_000_000 + (examId*2).mod(500_000) + 0/1`（考前一天 / 考前 1h；id 自增要钳制防越段） | [8M, 8.5M) `ExamReminderScheduler.kt:37-41, 153-158` |
| 每日脉冲 | 固定 `9_000_000` | 9M `ClassReminderScheduler.kt:40` |

通知 ID = 该闹钟的 requestCode（跨类不覆盖；旧实现用 `currentTimeMillis()%Int.MAX` 会把同毫秒投递的两条课覆盖成一条）`ReminderReceiver.kt:120-132`。取消用 `FLAG_NO_CREATE + FLAG_IMMUTABLE` 同 action 取回 PendingIntent `ReminderAlarmScheduler.kt:98-104`。

### 6.3 排期窗口与触发点

- 上课：未来 **8 天**，逐日过 `WeekResolver.daySchedule`——开学前/学期后/校历内放假日不排；调休补课日按"生效星期"匹配（补周六=第 N 周周三的课）；触发 = 节次开始 − 提前分钟；只排未来 `ClassReminderScheduler.kt:128-170`。
- 考试：未来 **30 天**，每场最多两条——考前一天 20:00、开考前 1 小时（无开始时间则只排前者；时间文案回退 `kssjms` 原文）`ExamReminderScheduler.kt:20-27, 112-171`。
- 日程：未来 **8 天**，触发 = 计划时刻 − 事项自定义提前量 `TodoReminderScheduler.kt:104-129`。
- 每日脉冲：次日 04:30 `setInexactRepeating(RTC_WAKEUP, INTERVAL_DAY)` 让 8 天窗口永远向前滚；刻意用重复闹钟而非"一次性自续期"（一次没送达就永久失效）`ClassReminderScheduler.kt:200-221`。**考试/日程的全量重排依赖它**（见 6.4 矩阵）。

### 6.4 重排触发矩阵

| 触发源 | 覆盖 | 位置 |
|---|---|---|
| 开机 / 改时间 / 改时区 / 改日期 / **应用被覆盖安装** | 三类全量重排（每类独立 `runCatching`，一个失败不连坐） | `BootReceiver.kt:21-46`、`AndroidManifest.xml:55-74` |
| 每日 04:30 脉冲 | 三类全量重排 | `ReminderReceiver.kt:30-47` |
| 课程 / 节次 / 学期 / 提醒设置变化 | 上课重排（`ReminderKey` 含 sectionTimes，防重新导入只改节次时被去重抑制） | `ScheduleViewModel.kt:180-212` |
| 日程表任何变更 | 日程重排（按值去重防自激） | `GradesViewModel.kt:323-336` |
| 抓取到考试数据 | 考试重排（失败时不动闹钟） | `UnifiedSyncViewModel.kt:481-483` |
| 清除成绩缓存 | 考试重排 + `cancelDueAlarms=true`（用户明确不要） | `SettingsViewModel.kt:186-188` |
| 云恢复 | 三类重排 | `CloudSnapshot.kt:293-295` |

假期/补课口径唯一实现：`WeekResolver.daySchedule`（补课优先于假期；内置补课表 `SchoolAdjustments` 随版本发布，当前只含 2026-10-10 补周三）`WeekResolver.kt:112-152`、`SchoolAdjustments.kt:15-27`。

### 6.5 权限、渠道与失败模式

- 清单：`POST_NOTIFICATIONS`（运行时）、`USE_EXACT_ALARM`（安装即授予、不可撤销，为上课提醒准点，注释解释了为何必须从 SCHEDULE_EXACT_ALARM 换成它）、`SCHEDULE_EXACT_ALARM`（旧语义兜底）、`RECEIVE_BOOT_COMPLETED`、`REQUEST_INSTALL_PACKAGES` `AndroidManifest.xml:5-20`。
- 权限入口在**学期设置页**：打开上课提醒开关时才申请通知权限；精确闹钟被收时显示诊断并给"去开启精确闹钟"跳系统页 `SemesterSettingsPage.kt:272-341, 310-339, 514-520`。
- 通知渠道三个，均 `IMPORTANCE_HIGH`：`class_reminder` / `exam_reminder` / `todo_reminder`；接收器先查通知权限，拒绝则静默返回（无日志）`ClassReminderScheduler.kt:60-65`、`ExamReminderScheduler.kt:52-57`、`TodoReminderScheduler.kt:46-51`、`ReminderReceiver.kt:50-52, 134-137`。
- 失败模式清单（系统侧，代码无法根治）：
  1. Doze 投递延迟：靠"已到点不取消 + 6h 宽限 + 先算后换"兜住，不会丢；
  2. 精确闹钟权限被收：退化为非精确，国产 ROM 省电管控下可能严重延迟；
  3. 通知权限被拒：静默不弹（提醒记录仍正常排）；
  4. force-stop / OEM 清理：系统清空该包全部闹钟（含每日脉冲）——恢复依赖"打开 App 触发上课重排 → 重排里重新武装每日脉冲 → 脉冲再重排考试/日程"；**考试提醒本身没有"打开 App 即重排"入口**（只有抓取成功/清缓存/云恢复/脉冲/开机 5 个入口），若这条链断（例如上课提醒链路异常）考试提醒会缺失到下次脉冲；
  5. 应用自更新后闹钟被清：已由 `MY_PACKAGE_REPLACED` 覆盖（v1.4.0 修复）；
  6. 无 WorkManager/前台服务兜底，也没有"精确闹钟不可用"的降级 UI 之外的补偿。

## 7. 构建与发布工程

- **版本号唯一来源**：`app/build.gradle.kts:26-27`（versionCode=58 / versionName="1.5.3"）；同步点三处均为手工：`README.md:37`、服务端 `/api/bs/app/latest` 响应（本机快照 `latest_1.5.3.json` 未入库）、`tools/sign_update.py` 的 `--version-code/--version-name` 参数 `tools/sign_update.py:18-26`。无脚本/CI 校验四处一致。
- 包名：`com.caeamer.beikeschedule`（2026-08 起，旧 `com.example` 包的安装无法覆盖升级）；debug 用 `applicationIdSuffix=".debug"` 与正式包并存，便于真机排查不丢数据 `app/build.gradle.kts:16, 22-27, 41-45`。
- SDK/工具链：`compileSdk { version = release(37) }`（AGP 9 新 DSL）、`minSdk 34`、`targetSdk 37`、Java 11 字节码 `app/build.gradle.kts:17-25, 61-64`；Gradle 9.4.1 `gradle/wrapper/gradle-wrapper.properties:4`；JDK toolchain 21 `gradle/gradle-daemon-jvm.properties:12`、`ci.yml:33-38`；AGP 9.2.1 `libs.versions.toml:2`；**未应用 `org.jetbrains.kotlin.android`**，走 AGP 9 内置 Kotlin（`build.gradle.kts:3-8`、`gradle.properties:20-21`）。
- R8/资源裁剪：release `isMinifyEnabled=true` + `isShrinkResources=true` + `proguard-android-optimize.txt` + 项目最小 keep 规则（kotlinx.serialization 序列化器、`eddsa` 的 JDK 内部类 `dontwarn`）`app/build.gradle.kts:46-59`、`app/proguard-rules.pro:1-23`。历史上未声明 proguardFiles 靠 consumer rules 裸跑，现已显式化。
- 签名：`keystore.properties`（未入库，`.gitignore:17-19`）在配置阶段读入，缺失时 `storeFile/storePassword=null` 不报错 → 直接 `assembleRelease` 会得到不可用/失败产物，**没有显式校验**（footgun）`app/build.gradle.kts:10-13, 32-39, 58`。磁盘上存在 `keystore.properties`（4 个键：storeFile/storePassword/keyAlias/keyPassword）与 `keystore/`（release.jks + update_signing_private.pem + update_signing_public.pem）；内容未读取、不在此泄露；私钥仅存本机，轮换需同步服务端。
- CI 只有一个 workflow（`.github/workflows/ci.yml`），无 release job：
  1. 触发：push/PR 到 main + 手动（14-19），并发取消旧跑（21-23），30 分钟超时（28）；
  2. JDK 21 + setup-gradle（33-41）；
  3. `testDebugUnitTest`（43-44）→ `lintDebug`（46-47）→ `assembleDebug`（51-52）→ `minifyReleaseWithR8`（57-58，不需要签名，专门覆盖只在 R8 下暴露的问题）；
  4. 始终上传 test report / lint html（60-74）；
  5. 明确不接 ktlint（注释说明会产生淹没真实改动的巨型 diff，11-12），无 instrumented 测试、无 release 打包、无版本一致性校验。
- **发布手工流程（由产物反推，v1.5.3 闭环成立）**：`assembleRelease` → 根目录 `BeikeSchedule-1.5.3-release.apk`（4,455,632 B，未入库 `.gitignore:21-22`）→ 上传 `https://api.caeamer.com/downloads/beike/...` → `tools/sign_update.py --apk ...` 规范化 7 字段 JSON（键序固定、紧凑、无 sig）并 Ed25519 签名，输出为 `GET /api/bs/app/latest` 响应体（`tools/sign_update.py:7-16, 40-47, 105-120`；客户端约定见 `UpdateSignature.kt:18-32`）→ 客户端验签（覆盖范围 FULL 含 APK SHA-256，旧约定 METADATA_ONLY 时摘要不可信）`UpdateSignature.kt:51-52, 136-151` → 比较 versionCode → 应用内下载后再核对 SHA-256 `UpdateInstaller.kt:155-170`。
  - 现场一致性证据：`latest_1.5.3.json` 的 size=4455632、apkSha256=`c085ccf7…` 与根目录 APK 实测 SHA-256 完全一致，说明当前发布链可用；但 `latest_*.json` 全部未入库（`.gitignore:37`），元数据丢失只能从服务器取回或重签。
- 网络安全：`network_security_config` 全量禁明文（`app/src/main/res/xml/network_security_config.xml:10`）；Manifest 里"应用内更新走明文 HTTP"的注释已过时（1.3.8 起全 HTTPS）`AndroidManifest.xml:7-11`、`README.md:39, 47`。

## 8. 依赖与工具链风险

| 依赖/配置 | 现状 | 风险 | 建议 |
|---|---|---|---|
| AGP 9.2.1 + Gradle 9.4.1 + 内置 Kotlin 2.2.10 | 未应用 kotlin-android 插件，`android.disallowKotlinSourceSets=false` `gradle.properties:17-21` | 新机制：Kotlin 版本随 AGP 锁步、`kotlin {}` 编译选项 DSL 与旧文档不同，升级 AGP 可能被动升 Kotlin | 升级前查 AGP 9 内置 Kotlin 矩阵；把 `jvmTarget/语言特性` 显式写进 DSL |
| KSP 2.2.10-2.0.2 | 必须与 Kotlin 精确匹配 `libs.versions.toml:12` | lint 提示已有 KSP 2.3.11；Kotlin/Compose 插件/KSP 三者需同批升级 | 用版本目录一次性升级并跑 `minifyReleaseWithR8` |
| compileSdk/targetSdk 37、minSdk 34 | 非常激进的 SDK 组合 `app/build.gradle.kts:17-25` | targetSdk 37 行为变更集中（edge-to-edge、通知/闹钟权限）；minSdk 34 直接放弃 Android 13 及以下设备 | 确认用户设备分布；升级 SDK 前先看 Android 17 行为变更清单 |
| Compose BOM 2026.02.01 + material-icons-extended 1.7.8 | icons 被单独钉在旧线 `libs.versions.toml:11, 17` | 图标库已停止演进、与 BOM 版本脱节；APK 中 `classes.dex` 3.27MB（原始 4.35MB 的 75%）是体积主因 `BeikeSchedule-1.5.3-release.apk` | 评估替换为按需引入的图标子集/本地 SVG，避免整库依赖 |
| Room 2.7.2 + KSP | schema 只导出 3/4/5/6（缺 1/2），DB version=6、5 段手写迁移 `AppDatabase.kt:12, 25-98`、`app/schemas/...` | 无 instrumented migration test；lint 提示可升 2.8.5 | 补 MigrationTestHelper（至少 5→6），升级 Room 前先锁定 schema |
| okhttp 5.5.0 | 仅云 API / 扫码 / 应用内下载用 OkHttp；SmartClass 与更新检查仍用 `HttpURLConnection` `CloudApi.kt:47`、`QrAuthApi.kt:33`、`UpdateInstaller.kt:99` | 三套网络栈并存（OkHttp、HttpURLConnection、WebView fetch），排障面大；5.x 主版本 API 变动 | 新代码统一 OkHttp；长期可把 smartclass 迁到 OkHttp |
| eddsa 0.3.0 (net.i2p.crypto) | 仅平台缺 Ed25519 provider 时兜底验签 | 老库（非 AndroidX，无更新）；R8 需 `dontwarn` `proguard-rules.pro:19-23` | 可接受；若担心供应链，改自实现 Ed25519 verify 或换维护中的库 |
| configuration-cache=true | `gradle.properties:17` | 配置阶段读 `keystore.properties`、foojay toolchain 解析在 CC 下的可重放性问题；CI 首跑需联网下 toolchain | 保留但掌握 `--no-configuration-cache` 逃生；keystore 改为显式存在性校验 |
| Lint 现状 | 0 errors / 40 warnings / 7 hints `app/build/reports/lint-results-debug.txt` | 无 baseline、CI 未 warningsAsErrors；UseKtx 9、UnusedResources 8、ObsoleteSdkInt 7、新版本提示 8 | 先接 baseline 防新增，再逐类清 |
| 未引入项 | TECH_DESIGN 提到的 WorkManager/Glance/navigation 均未引入 `docs/TECH_DESIGN.md:75-80, 103` | 文档与现状脱节，新人按文档找代码会扑空 | 更新文档或标 OUTDATED |

## 9. 仓库与工程债务（量化 + 清理优先级）

**根目录未入库产物（均被 `.gitignore` 覆盖但占磁盘 / 干扰 git status）**：28 个 APK（1.1.11 → 1.5.3，含 1.3.0-r4 debug/release）、18 个 `*.xml`（uiautomator dump e1-e7 等）、11 个 `latest_*.json`、10 个 png（emu/cloud 截图）、2 个 log、1 个 `nul`（Windows 保留设备名，普通方式删不掉）；`out/` 19 个文件（截图 + latest.json + plan.md）**未被忽略**、常驻 `git status`；`.v2c/`、`.zcode/`、`.zcodeignore` 未跟踪，`.kilo/`、`.workbuddy/` 已忽略但仍在磁盘——四者合计 196 文件 / 2.2MB。忽略规则见 `.gitignore:21-39`（`*.apk`、`/e*.xml`、`/emu*.png`、`/latest_*.json`、`.workbuddy/` 等）。

**分支/历史**：28 个本地分支 + 24 个远程分支 = 52 个 refs（feat/fix 各约半），24 个 tag，106 commits；旧功能分支全部未清理，无 release 分支策略。

**文档**：`docs/` 共约 2009 行（不含 `docs/samples/` 的巨型 JSON fixture），其中 6 份审查报告 1503 行（2026-08、09-FULL、09-FREE-ROOM、09_FREE_ROOM、09-R4、10-R5），设计文档 506 行；`TECH_DESIGN.md` 仍是 v1.0（写着"非目标：成绩/考试"、`com.example` 包名、WorkManager 提醒），与现状严重滞后，新人极易误读；onboarding 目录原不存在，本轮由小队并行创建。

**质量门禁缺口**：无 ktlint/detekt/`.editorconfig`（CI 注释自认会在单独一轮接，`ci.yml:11-12`）；无 instrumented 测试（`app/src/androidTest` 仅 `ExampleInstrumentedTest.kt` 占位）；无 Dependabot/Renovate；无 release workflow；无 Gradle 版本一致性校验脚本。

清理优先级：
- **P0（半天内）**：根目录 APK/xml/png/json 归档到 `build/` 或 GitHub Releases（只留当前 1.5.3 一份），`out/` 与临时 log 删除，处理 `nul`；把 `.v2c/ .zcode/ .zcodeignore out/` 纳入 `.gitignore`（或直接删）。
- **P1**：删除已合并分支（或 `git branch --merged main` 批量清）、确认 tag 与 GitHub Release 对应；旧审查报告挪到 `docs/reviews/`，`TECH_DESIGN.md` 标注 OUTDATED 或重写；补发版 checklist（versionCode/README/latest json/sign 脚本四处同步）。
- **P2**：接 ktlint（单独一轮）、lint baseline + `warningsAsErrors`（先 baseline）、补 Room 迁移 instrumented test、把发版流程脚本化（bump version → assembleRelease → sign_update.py → 四字段一致性断言）。

## 10. 单测覆盖地图与缺口

现状：**46 个测试类 / 407 个用例 / 0 失败**（`app/build/reports/tests/testDebugUnitTest/index.html`，本次构建产物；任务书说的 46 类与磁盘一致）。覆盖分布（用例数）：

| 域 | 主要测试类（用例） | 评价 |
|---|---|---|
| 成绩 | `GradesParserTest`(13)、`GradeRowsTest`(13)、`GpaCalculatorTest`(7)、`WeightedScoreCalculatorTest`(8)、`CreditAggregatorTest`(10)、`CreditProgressParserTest`(4)、`SchoolYearFilterTest`(5)、`ExamDayGroupsTest`(3)、`ScorePrivacyTest`(3) | 计算/解析/分组口径覆盖较全，补考/重修有代码构造用例（fixture 54 行仍全正考，端到端补考路径测不到） |
| 日程 | `TodoPlannerTest`(21)、`ReminderSchedulingTest`(25) 中日程/考试排期用例 | 纯函数覆盖好；无 UI/表单/滚轮测试 |
| 无课教室 | `SmartClassCryptoTest`(16)、`SmartClassParserTest`(15)、`FreeRoomRepositoryTest`(9)、`FreeRoomUiStateTest`(14)、`RoomNameOrderTest`(12) | 签名/解析/自愈/状态机覆盖好 |
| 提醒 | `ReminderSchedulingTest`(25)、`ReminderCoursesTest`(6)、`WeekResolverDayScheduleTest`(11)、`CurrentWeekTest`(31) | 排期纯函数与假期/补课覆盖好；AlarmManager 真实行为不可测 |
| 导入/云/更新 | `JwParserTest`(24)、`UpdateSignatureTest`(13)、`SyncPlanTest`(14)、`CloudApiNetworkFailureTest`(7)、`CloudSnapshotTest`(4)、`SemesterSanitizeTest`(6) 等 | 解析/验签/同步计划覆盖好 |
| 其它 | `CourseMergerTest`(10)、`SessionExpanderTest`(10)、`NextClassTest`(12)、`WeekLayoutTest`(8)、`WeekUtilsTest`(6)、`SectionMapTest`(2)、`AppSessionTest`(2)、`AssignImportColorsTest`(3) 等 | 课表模型层完整 |

缺口（按影响排序）：
1. **UI 层零测试**：我的页（`ProfileScreen` 886 行）、`SettingsViewModel.clearGradesCache`、日程表单、WheelPicker、扫描式更新卡片——这些正是"危险操作/易回归"的地方。
2. **Room 迁移无 instrumented 测试**（schema 1/2 缺失，5→6 只能靠代码审查）。
3. **无 Compose UI/旋转恢复测试**：`rememberSaveable` 大量使用但无验证。
4. **无网络契约测试**：smartclass/教务响应字段变化无 fixture 回归（当前靠解析器单测 + 线上报错）。
5. **无 release/R8 运行验证**：CI 只跑 `minifyReleaseWithR8`，不安装运行。
6. **性能无基准**：成绩页 `by lazy` 派生、列表滚动、`rememberNow` 每分钟重组都无 benchmark。

## 11. 按影响排序的可优化项（建议接手顺序）

1. **发版流程脚本化 + 一致性校验**（versionCode/README/latest json/签名脚本四处手工同步是最高频的人为事故点；当前靠记忆，错一次就是线上更新不可用）。
2. **提醒恢复链补一个"打开 App 即重排考试/日程"入口**（MainActivity onCreate 里各加一次 `runCatching{...reschedule}`），消除"force-stop/清数据后考试提醒只剩脉冲兜底"的隐式依赖；同时给 `ReminderReceiver` 通知权限拒绝留一条日志。
3. **考试提醒/日程提醒的直接自愈**：把每日脉冲的武装独立于上课重排（或 BootReceiver 每次同时确保脉冲），避免"上课链路异常 → 脉冲没排 → 考试/日程 8 天后静默失效"。
4. **smartclass 失效演练**：把"平台改版"写成已知失效清单 + 用户提示文案优化；评估把 4 个接口迁到 OkHttp 并合并 `/config.json` 两次请求（R5 P3 遗留）。
5. **仓库卫生 P0/P1**（见 §9）：清理根目录与 AI 残留、删分支、归档旧审查、给 TECH_DESIGN 标 OUTDATED。
6. **补 UI/迁移测试**：至少覆盖清缓存、云恢复、日程打卡、目标包 `ProfileScreen` 危险操作二次确认。
7. **接 ktlint + lint baseline**（CI 已预留说明，单独一轮做）。
8. **无课教室 UX**：0% 空座排尾/弱化、展开区限高、默认展开"当前或下一个"大节（V3/V5/V6 遗留）。
9. **APK 体积**：评估 `material-icons-extended` 替换（R8 已裁剪，但 dex 仍 3.27MB），并对 release 加 `--release` 基线对比。
10. **依赖升级批处理**：Room 2.8.5 / KSP 2.3.x / Compose BOM → 一次升级 + `minifyReleaseWithR8` + 真机走查（本仓历史教训：升级类改动真机验证必须覆盖正路径）。

## 附：交接速查入口

- 教务 Tab/分段：`ui/grades/GradesScreen.kt:127-229`、`ui/grades/GradesViewModel.kt:221-371`
- 成绩口径三件套：`data/repo/GpaCalculator.kt`、`WeightedScoreCalculator.kt`、`CreditAggregator.kt`（共同前置 `GradeRows.kt`）
- 日程：`ui/todo/TodoScreen.kt`、`model/TodoPlanner.kt`、`reminder/TodoReminderScheduler.kt`
- 无课教室：`ui/freeroom/*` → `data/repo/FreeRoomRepository.kt` → `data/remote/SmartClass*.kt`
- 提醒：`reminder/*.kt`（先读 `ReminderAlarmScheduler.kt` 顶部注释）
- 构建/发布：`app/build.gradle.kts`、`gradle/libs.versions.toml`、`.github/workflows/ci.yml`、`tools/sign_update.py`、`docs/CODE_REVIEW_2026-10-R5.md`（最新一轮审查，含修复记录与残余项）
