# 贝壳课表 全量代码审查报告（2026-09 第四轮 · R4）

- **审查时间**：2026-09-23
- **审查对象**：`fix/review-r4` @ 90984d4（versionCode 43 / v1.3.2，云同步 + Ed25519 验签 + 微信授权修复之后）
- **审查范围**：全部主源码（model / import / data / remote / backup / reminder / ui / cloud / 工程配置 / 注入 JS / 签名工具 / 测试清单），81 个源文件 ≈ 1.33 万行
- **审查方式**：4 个并行深读域（A 纯逻辑与解析、B 网络与安全、C UI 与 ViewModel、D 数据与提醒与工程），🔴 级与关键 🟡 结论已由主审逐条回源码复核
- **改动情况**：审查未修改文件；**修复轮**（同日）按用户拍板的范围执行，见文末「第 8 节 · 修复情况（v1.3.3 / versionCode 44）」

标记说明：标 ✅复核 的条目已由主审亲自对照源码确认。

---

## 1. 总体结论

前三轮修复后的客户端基线依然扎实：时间运算全链路 `LocalDate` 无时区隐患、解析器 `contentOrNull` 教训贯彻、UI 状态机与竞态防护成熟、测试文化明显好于平均水平。**本轮新发现几乎全部集中在 v1.3.x 云同步这条新链路上**，且高危项呈现同一个根因：**自有服务端既无 TLS 又无真实认证**（发现 R1–R3），加上客户端在"快照构建 → 上传 → 清脏标记"编排上的两个纯客户端缺陷（R4）。其余问题集中在两类：解析层脏数据防御的"最后一公里"（兜底值本身非法时静默丢课/算错），以及 UI 层 remember vs rememberSaveable 应用不一致（旋转丢状态，含强更弹窗可被旋转绕过）。

共：**正确性/安全 🔴 5 项、🟡 21 项、🔵 22 项、测试盲区 5 项**。无一需要数据迁移。

---

## 2. 🔴 高危（正确性 / 安全）

### R1. 云同步全链路明文 HTTP：token 与整包隐私数据裸奔 ✅复核
`data/remote/CloudApi.kt:36` + `res/xml/network_security_config.xml:10-12`。
`BASE_URL = "http://112.125.88.178"`，该 IP 显式放行明文。而 `getBackup`/`putBackup` 携带 `Authorization: Bearer $token`（CloudApi.kt:95、113），快照含学号、姓名、学院/班级/年级、全部成绩（含排名 `pm`）、考试安排（含座位号 `zwh`）（CloudSnapshot.kt:88-158）。同一网关上的 MITM 可直接截获 token 与全部备份明文；token 被截获后可长期读写受害者云端备份。
**修复**：备案完成前改用带证书固定的自签 HTTPS（OkHttp `CertificatePinner`），或对快照做端到端加密（密钥不出设备）；至少 Bearer token 不走明文。与 R2、R3 同根因，建议作为 v1.4 阻断项一起解决。

### R2. 「登录即注册」无凭证校验：知道学号即可接管他人云账号 ✅复核
`CloudApi.kt:49-51、76-85`（`login(xh, xm)`，注释明言"账号 = 学号，无密码"）。
服务端契约是仅凭学号签发 token；WebView 教务认证只发生在客户端本地，服务端无法验证。任何人拿到同学学号即可 `login` 换 token，再 `getBackup` 拉走对方全部成绩/学籍，或 `putBackup`/`deleteBackup` 破坏对方备份。
**修复**：服务端为云账号增加独立凭证（首次登录设密码 / 设备绑定），或登录接口要求携带仅教务会话可换取的一次性证明。**需要服务端配合，客户端单方面无法修复**——在修复前，`deleteBackup` 建议服务端至少加二次确认。

### R3. 备份内容无完整性校验，恢复即整表覆盖 → 快照投毒 ✅复核
`CloudApi.kt:66-73`（`BackupEnvelope.data` 原样 `JsonObject`）+ `CloudSnapshot.kt:55-61、217-267`。
`getBackup` 响应在明文 HTTP 上传输且无签名/HMAC；`decode` 使用 `ignoreUnknownKeys + 全字段默认值`，被篡改的 JSON（如 `courses: []`）也能通过校验，随后 `applyRestore` **清空三源课程后整表覆盖写入**，课程名/考试地点可被注入钓鱼内容。Room 事务只保证原子性，不保证内容可信。
**修复**：服务端对快照计算 HMAC（或随 R1 改 E2E 加密后自然解决）；decode 时对关键字段做规模合理性校验；恢复前在本机留一份回滚点。

### R4. 云备份"撕快照 + 无条件清脏标记"可静默丢数据 ✅复核（纯客户端，最该先修）
`CloudSnapshot.kt:164-211` + `CloudSync.kt:90-95`。
`CloudSnapshotCodec.build` 用 5 次 `repo.*.first()` 和十几次 `settings.*.first()` 拼快照，各读之间无一致点；备份构建期间若发生并发写（成绩抓取落库、用户编辑日程——两者都会自动触发 `markDirty`），Room 部分与 DataStore 部分取自不同时刻，产生撕裂快照；随后 `uploadIfChanged` 无条件 `clearCloudDirty()`（CloudSync.kt:94），把"没包含最新改动"的状态标记为已同步——**该改动直到下次编辑前都不会再上传**。
**修复**：build 的 Room 读包进一个 `db.withTransaction {}` 只读事务；DataStore 侧一次性 `data.first()` 整快照读取；上传成功前重查一次 dirty，变了就重建快照再传。

### R5. 强更验签的签名不覆盖 APK 本体 ✅复核
`data/remote/UpdateSignature.kt:42-49`（`SignedBody` 仅含 versionCode/versionName/changelog/force/size/url）+ `tools/sign_update.py`。
Ed25519 验签本身实现正确（公钥硬编码、空签名/Base64 非法/算法异常均返回 false；验签失败调用方完全忽略自有源回退 GitHub HTTPS，回退不构成绕过——均复核确认）。但签名只覆盖**元数据**：MITM 拿到合法的 `url` 字符串后，可在 APK 下载阶段替换文件。对已安装用户会被 Android 同签名检查拦下（表现为安装失败），但用户得到的是不可诊断的失败，且 `force=true` 强更会引导用户反复重试明文下载。
**修复**：`SignedBody` 增加 `apkSha256` 字段并入签名，下载完成后校验摘要再交给 PackageInstaller；APK 分发切换到可用 HTTPS 源（如 GitHub Releases 直链）。客户端可独立完成。

---

## 3. 🟡 中危

### 安全与云同步（域 B / D）

**B6. 云 token 明文存 DataStore**（B5 与 D4 合并，复核确认）
`SettingsStore.kt:303-309`。已核实 `backup_rules.xml` 与 `data_extraction_rules.xml` 均排除 `datastore/`，不随系统云备份外流——这点做得对；但 root/同 uid 可明文读取，且 token 即云备份全权凭证（见 R2）。建议 Android Keystore 包裹后再落盘。

**B7. 桥回调只校验 ustb 子域，`onIdentity` 未校验 byyt 页面**（复核确认）
`JwWebView.kt:136-145` 桥回调只校验 `isMainFrame + isJwHost`（= 任意 `*.ustb.edu.cn` 子域）；`CloudLoginScreen.kt:183` 的 `onIdentity` 直通 `viewModel.onIdentity(xh, xm)`（CloudLoginViewModel.kt:170-190），不校验当前主框架是否仍在 byyt 域。对比：手动路径 `fetchIdentity`（CloudLoginScreen.kt:105）是校验了 `isByytHost` 的。任何 ustb 子域页面（含被 XSS 的子域）都能伪造 xh 触发登录——结合 R2，可诱导受害者"登录"进攻击者云账号，此后自动备份把真实数据整包传进攻击者账号。
**修复**：`onIdentity` 回调内校验 `webView.url` 主框架为 byyt；`BRIDGE_ORIGINS` 收窄到实际需要的子域。

**B8. 同一 sid 可能启动双轮询，互相触发 205 并发冲突**（复核确认代码形态）
`CloudLoginViewModel.kt:96-99`：`containsKey` → `put` 非原子，且 `ensurePolling` 会被 IO 线程（onSubresource）与主线程（pinSidForWeChat）并发调用。竞态下双轮询并存，恰好复现刚修过的"授权无响应"。**修复**：`putIfAbsent`，未放入的 job 立即 cancel。

**B9. sid 轮询无总时长上限**：`CloudLoginViewModel.kt:102` 的 `while (isActive && authorizedUrl == null)` 对 code=4/205 无限 continue。pinned 会话可永续轮询。**修复**：记录起始时间，超窗口（如 3 分钟）break 并提示失效。

**B10. authCode 候选键过宽**：`QrAuthApi.kt:105` 的 `AUTH_CODE_KEYS` 含 `"code"/"token"/"data"` 等常见业务字段名，可能把业务响应误当授权码，导航到必失败的 SSO 回调。**修复**：收窄到 `authCode/auth_code/authcode` 三键。

**D6. 恢复原子性只覆盖 Room，DataStore 半写无回滚 + 置脏竞态**（B10 与 D6 合并，复核确认）
`CloudSnapshot.kt:222-261`：五表写入在 `db.withTransaction` 内（正确），但随后十余个 `settings.saveXxx` 逐键在事务外执行，进程中途被杀会留下"课程已是云端版、设置半新半旧"的不一致；且每个设置写都异步触发 `noteCloudDirty()`，与 `CloudSync.restore:126` 的 `clearCloudDirty()` 先后不确定，恢复完可能立刻残留 dirty 做一次冗余整包上传。**修复**：恢复前落本地临时备份供回滚；恢复期间提供 `CloudSync.suppressDirty {}` 抑制标记。

**D3. CloudSync 去抖 Job 跨线程竞态 + appContext 死代码**（B11/B12/D3 合并，复核确认）
`CloudSync.kt:63-69` 的 `debounceJob?.cancel(); debounceJob = scope.launch{}` 在 IO 多线程下无同步；`@Volatile appContext`（:35）五处赋值全项目零读取。**修复**：簿记收敛到单线程调度器或加锁；删除 appContext。

### 解析层脏数据防御（域 A）

**A1. `parseWeekCalendar` 未校验 monday 原始串**：`JwParser.kt:96-99` 直接存 JSON 里的 `monday` 字符串；混入 `"2026/09/07"` 之类时 `WeekResolver.weekMonday` parse 失败落入倒推分支，其后所有周编号整体错一位。**修复**：存入前 `LocalDate.parse` 校验，非法则整个周历回退为空（与 `zc != 1` 防御一致）。

**A2. `parseDayOfWeek` 不校验 1..7**：`JwParser.kt:167-171` 对 `xq8`/`xq9` 照常返回，这类行在 `WeekLayout` 中**整门课静默消失**。**修复**：`takeIf { it in 1..7 }`，否则抛出让 mapNotNull 跳过该行。

**A3. 节次全缺时 `startSection` 兜底 0**：`JwParser.kt:119-120` 产出 `startSection=0` 的行；编辑时 `SectionMap.bigIndexOf(0)` 映射成第六大节（11-12 节），用户一进编辑框再保存，课程被永久搬到错误时段。**修复**：`startSection <= 0` 时抛出跳过该行。

**A4. `CourseMerger.mergeSameSlot` 丢弃"同时段不同周次真实地点不同"的信息**：`CourseMerger.kt:29-33` 调课换教室（1-6 周 A 楼、7 周起 B 楼）合并后 B 楼丢失，`ReminderCourses` 按合并结果排提醒，用户被指到错误教室。**修复**：仅当某行地点为占位符（`plausibleLocation==false`）时才并入基准行。

**A5. `GradeRows.bestPerCourse` 对空 kcdm 错误合并**：`GradeRows.kt:35` + `GradesParser.kt:55`，所有空课程代码的不同课程行被归为一组只留一行，GPA 学分/门数**静默少算**。**修复**：分组键 `it.kcdm.ifBlank { it.kcmc }` 兜底。

**A6. `ExamsParser.TIME_REGEX` 日期与时间间隔容忍仅 3 字符**：`ExamsParser.kt:20-24`，`"2027-01-15(周六) 08:00"`（间隔 4~5 字符）开始/结束时间双双留空。**修复**：放宽到 `{0,6}`，分钟组后追加 `(?::\d{2})?` 容忍秒。

### UI 层（域 C）

**C1. 强更弹窗旋转即被绕过**（复核确认对话框开关为裸 remember）
`ProfileScreen.kt:108-113`：六个对话框开关全是裸 `remember`。旋转重建后 `showUpdateDialog` 归 false 且无重开逻辑（checkUpdate 只在 VM init 自动跑一次）——`force=true` 的"不可跳过"弹窗旋转一次就甩掉，force 门禁形同虚设。**修复**：`showUpdateDialog` 改 rememberSaveable，且组合时若 `update is Available && force` 强制重开；其余开关一并 saveable。

**C2. 学期设置对话框"假恢复"丢用户编辑**：`ScheduleScreen.kt:147-148` 注释声称旋转安全（对话框重开），但 `SemesterSettingsDialog.kt:67-69` 的 name/firstMonday/totalWeeks 是内部裸 remember——对话框回来了、输入全丢，正是 CourseEditDialog 明言要避免的模式。**修复**：三字段改 rememberSaveable，或 showSettings 改回普通 remember，二选一保持一致。

**C3. 课程详情弹层旋转即关**：`ScheduleScreen.kt:140` 的 `detailCourse` 裸 remember，而 CourseDetailSheet 无内部可变状态、实体可序列化，本可无成本保存。**修复**：rememberSaveable + Saver（复用 TodoScreen.kt:77 的 JSON Saver）。

**C4. 过期一次性日程成"幽灵数据"**：`TodoViewModel.kt:35-42` 列表只展示今天起 14 天，`occursOn` 对 REPEAT_ONCE 仅当天出现——日期选在过去的事项永远不可见、不可编辑、不可删，但仍参与提醒重排。**修复**：列表尾部追加"更早/已过期"分组，或表单禁选过去日期，至少做一个。

**C5. CloudLogin 换 token 期间返回键可逃逸**：`CloudLoginScreen.kt:72` 的 `BackHandler(enabled = state !is Done)` 在 SigningIn 态仍放行，与 ：207 "防乱点"全屏遮罩矛盾；token 请求后台跑完落盘，界面与实际登录态脱节。**修复**：`enabled = state !is Done && state !is SigningIn`，顶栏返回键同样按态禁用（ImportScreen.kt:110 是正确示范）。

**C6. WebView 三流程旋转断崖 + `pendingEnableReminder` 未保存**：JwWebView 每次组合新建 WebView 并 `loadUrl(JW_HOME)`（`JwWebView.kt:106-113`），统一身份认证页填到一半旋转即回到首页（Cookie 持久化使已登录场景可自愈）；`ScheduleScreen.kt:131-137` 的 `pendingEnableReminder` 裸 remember，权限弹窗期间旋转后"权限给了但提醒没开"。**修复**：pendingEnableReminder 改 saveable（低成本必修）；WebView 流程评估 Activity 级保留或对该流程声明 configChanges。

**C7. `collectAsState` 与 `collectAsStateWithLifecycle` 混用**：TodoScreen.kt:88、ImportScreen.kt:56、CloudLoginScreen.kt:63-66 用裸 collect，后台仍持续驱动重组，项目其余处统一 WithLifecycle。**修复**：统一。

### 工程配置（域 D）

**D2. minify 开启但全工程没有任何 proguard 规则文件**（复核确认：`app/build.gradle.kts:46-52` 无 `proguardFiles`，磁盘上无任何 `.pro`）
R8 仅靠依赖库 consumer rules 运行；kotlinx.serialization/Room 目前自带规则能过，但将来引入的反射/序列化多态只会在 release 运行时炸，CI 的 `minifyReleaseWithR8` 只验证编译不验证运行。**修复**：显式声明 `proguardFiles(...)` 并建 `proguard-rules.pro`，release 包加冒烟路径。

**D5. `observeByNames` 裸 LIKE 通配符注入**：`CourseDao.kt:19-20`，课程名含 `%`/`_`（如"100%课堂"）时同名分组编辑找不到全部行。**修复**：改 `= :name` 或 LIKE + ESCAPE。

---

## 4. 🔵 低危与工程质量（摘要）

**解析/逻辑（域 A）**
- A7 `JwParser` 各 `runCatching` 吞异常无日志（JwParser.kt:32 等）：教务改字段格式 → 全部课程行静默消失且不可排查。至少 catch 分支记 Log。
- A8 等级制通过行可被数字挂科重修行覆盖（GradeRows.kt:36-38）：正考"中"+重修 59 分时已获学分被剔除。建议正考等级制且已通过时重修行不取代。
- A9 `splitSksj` 教师按固定行号取值、地点只认"【"前缀（JwParser.kt:195-197）：缺行时教师栏显示周数。
- A10 `parseWeekCalendar` 脏 zc 无上限（JwParser.kt:93）：`zc=1e5` 生成十万级列表。钳制 ≤60。
- A11 `TodoPlanner.groupByDate` 按原始字符串排序时间（TodoPlanner.kt:85）：非零填充 `"9:00"` 排序错位，应同 upcomingReminders 一样先 parse。

**UI（域 C）**
- C8 TodoScreen 日期选择器时区口径与 SemesterSettingsDialog 不一致（TodoScreen.kt:382-383 用系统时区，后者正确用 UTC），负时区差一天。
- C9 ExamListContent `grouped` 组合期每帧重算未 remember（GradesScreen.kt:606-607）。
- C10 FreeRoomScreen 自建 30 秒时钟与共享 rememberNow 重复（FreeRoomScreen.kt:89-98），应合并。
- C11 ProfileScreen 残留"重复 import"过时注释（ProfileScreen.kt:79-81）。
- C12 无障碍：TodoScreen 周选择圆点 36dp 无 minimumInteractiveComponentSize（TodoScreen.kt:498-509）；色板圆点 28dp 无语义无扩触达（:534-553），CourseEditDialog 同款已修，这是旧拷贝。
- C13 GradesScreen/ScheduleScreen 轻量状态未 saveable（detailGrade、showRefreshConfirm、weekMenuExpanded 等），建议随 C1–C3 一次收口。

**数据/提醒/工程（域 D）**
- D7 Exam 段 requestCode 无范围钳制（ExamReminderScheduler.kt:150-152）：id ≥ 500,000 越段（增长极慢，长期卫生）。建议 floorMod 钳进段内。
- D8 `apply` 记录保留已取消的"已到点"闹钟最长 6 小时（ReminderAlarmScheduler.kt:112-117）：`dueKept` 应排除 forceCancelCodes/cancelDueAlarms。
- D9 Room 全部 Flow 查询全表扫描无二级索引：当前规模可接受；grade/exam 增长后优先给 `grade.xnxq`、`exam.ksrq` 加索引。
- D10 Room schema 1.json/2.json 缺失：1→2 迁移无法用 MigrationTestHelper 回归。
- D11 TodoEntity "不参与网络传输"注释已与事实相悖：云同步已把它原样上云（CloudSnapshot.kt:41），从此是网络契约，加字段必须带默认值。
- B13 SmartClassCrypto `sign()` 失败静默发无签名请求（SmartClassCrypto.kt:104-108），多耗一次 RTT 且自愈逻辑误判。失败应短路返回。
- B14 SmartClassCrypto 固定 IV + 硬编码密钥应注释明示"非安全机制"，避免被误用于真实凭证。
- B15 授权 URL 加载重试 1.5 秒后静默放弃（CloudLoginScreen.kt:82-92）：authCode 一次性，丢弃后用户只能重扫且无提示。应报错并复位。

---

## 5. 测试盲区（最该补的 5 个）

1. **Room 迁移**：schemas 已导出却零迁移测试，是数据层最大敞口（配合 D10 补齐历史 schema）。
2. **CloudSync 编排**：去抖、脏标记、CloudAuthException 清 token 的状态机完全无测试（纯 JVM 可注入 api/clock）。
3. **ReminderAlarmScheduler.apply 的记录拼装**：`plannedRecords + dueKept` 去重与 6h 宽限边界无用例（现有 28 用例全围绕 alarmsToCancel）。
4. **CloudSnapshotCodec 的 toDto/toEntity 映射**：CloudSnapshotTest 只测 JSON 往返，hidden/source/id=0 重排等字段保真无断言。
5. **ScheduleViewModel 会话/重排触发**：epoch→selectedWeek 推进与 ReminderKey 去重防自激（历史 bug 高发区）无 ViewModel 级测试。

---

## 6. 修复优先级建议

1. **v1.4 阻断项（服务端+客户端联动）**：R1（TLS/E2E 加密）+ R2（云账号凭证）+ R3（备份完整性）——同一根因，一起解决。
2. **纯客户端可立即修的高危**：R4（撕快照+清脏标记）、R5（apkSha256 并入签名）、B7（onIdentity 域校验）。
3. **低成本高价值一揽子**：C1（强更弹窗 saveable）、B8（putIfAbsent）、A2/A3/A5（解析防御三处）、D2（proguard 显式化）、C5（SigningIn 吞返回）。
4. **一次性主题收口**：UI 全局 remember/rememberSaveable 规则化（C1–C3、C6、C13 按统一规则过一遍）；解析层"非法即整行跳过 + 记日志"统一出口（A1–A3、A7）。

---

## 7. 值得肯定的点

- 前三轮全部修复项经抽查仍成立，无一回归；提醒三条不变量严格，requestCode 四段隔离有用例钉住。
- CancellationException 处理在云同步链路全面正确（CloudApi/SmartClassApi/CloudSync/CloudLoginViewModel/SmartClassKeyProvider 均放行取消）。
- 401 → CloudAuthException 分类清晰，上层清登录态停自动同步，无死循环重试。
- WebView 桥 addWebMessageListener + 主框架/域双校验、shouldOverrideUrlLoading 失败关闭、onReceivedSslError 一律 cancel；连接均 use/disconnect 收口；备份规则正确排除 datastore/ 与 app_webview/。
- token/sid 无 release 日志输出；依赖版本新，无已知 CVE 残留；versionCode/versionName 内部一致；keystore.properties 未被 git 跟踪。

---

## 8. 修复情况（2026-09-23 同日修复轮 · v1.3.3 / versionCode 44）

用户拍板范围：R1（明文 HTTP）**保持不动**（域名未备案、阿里云拦截域名流量，已接受的威胁模型）；R2 走**轻加固**（姓名校验+限流，不改"学号即账号"模型）；C4 加"已过期"分组；showwe 服务端代码一起改；出 release 44/1.3.3 实测包。

**客户端已修**（R4/R5/全部 🟡 除注明外、🔵 大部分）：

- R4 撕快照+清脏标记：`CloudSnapshotCodec.build` Room 读包进只读事务；`uploadIfChanged` 上传后复查脏标记，仍脏则重建再传（≤3 轮，耗尽不清脏、留给下个去抖周期）。
- R5 APK 摘要：`SignedBody` 增加 `apkSha256` 并入签名；`verifyDetailed` 区分 FULL/METADATA_ONLY（旧约定签名仍兼容）；新增 `UpdateInstaller`（DownloadManager 下载 → SHA-256 校验 → FileProvider 交系统安装器）；Manifest 增 REQUEST_INSTALL_PACKAGES + FileProvider；`tools/sign_update.py` 升级 v2 约定（--apk 自动算 size+sha256）。
- R3 客户端：`CloudSnapshot.decode` 规模/节次合理性校验；恢复前落本地回滚点（filesDir/cloud-restore-rollback.json）。
- R2 客户端配合：`onIdentity` 桥回调校验当前主框架为 byyt 域（B7）。
- B6 token Keystore AES-GCM 加密落盘（兼容历史明文，读取即迁移）；B8 轮询 check-then-put 加锁；B9 轮询 5 分钟总上限；B10 authCode 候选键收窄（去掉 code/token）；B15 授权回调重试耗尽报错+复位；B13 signOrNull 短路失败请求；B14 安全边界注释；D6 suppressDirty + 恢复置脏抑制；D3 CloudSync 簿记单线程化 + 删 appContext。
- 解析层：A1 monday 日期校验（非法整表回退）、A2 星期 1..7 校验、A3 节次≤0 整行跳过、A4 地点不同不合并（调课换教室保地点）、A5 空 kcdm 按课程名分组（含 CreditAggregator distinctBy）、A6 考试时间正则放宽+容忍秒、A7 行级失败日志钩子（JwImportBridge 接 logcat）、A8 等级制通过行不被数字挂科重修覆盖、A9 教师按语义行提取、A10 zc≤60 钳制、A11 groupByDate 先 parse 再排序。
- UI：C1 强更弹窗 saveable+force 重开、C2 学期对话框字段 saveable、C3 课程详情弹层 JSON Saver、C4 "已过期（未完成）"分组、C5 SigningIn 吞返回+顶栏禁用、C6 pendingEnableReminder saveable、C7 三处改 collectAsStateWithLifecycle、C8 日期选择器 UTC 口径、C9 grouped remember(exams)、C10 FreeRoom 并入 rememberNow(tickMillis)、C11 删过时注释、C12/C13 色板与周选择圆点扩触达+语义、GradesScreen detailGrade/showRefreshConfirm saveable。
- 数据/工程：D2 proguardFiles 显式化 + 新建 proguard-rules.pro、D5 observeByNames LIKE→=、D7 Exam 段 requestCode floorMod 钳制、D8 dueKept 排除已取消闹钟、D11 TodoEntity 注释更新为"网络契约"。
- 未修（说明）：D9 二级索引（需 DB 迁移 v6，当前规模不需要）；D10 历史 schema 1/2.json 无法重建；C6 的 WebView Activity 级保留（大改，另行评估）。

**showwe 服务端已修**（D:/git_/showwe，master，未提交）：

- R2：login 增加姓名一致性校验（已注册账号 xm 必须与档案一致，空姓名拒绝）；登录加每 IP 限流（60/15min，原有每学号 20/15min 保留）；put/delete backup 每学号 30/15min 限流。
- R3：GET /backup 返回 `snapshot`（原始字节串）+ `sig`（服务端 Ed25519 签名，私钥 `server/src/bs_backup_signing_private.pem` 已 gitignore）；客户端恢复前强制验签，无签名/验签失败拒绝恢复。**两端需一起发**：旧客户端不带验签，仍读 data 字段不受影响。
- R5 配套：GET /app/latest 透传 `sig`/`apkSha256`（此前响应漏掉 sig，1.3.2 的自有源验签实际恒失败回退 GitHub——本次顺带修复）；bs-app-version.json 由新版 tools/sign_update.py 重新生成。

**测试**：新增 BackupSignatureTest(4)、UpdateSignature 签名覆盖范围(3)、QrAuthCodeExtract 业务字段拒绝(1)、CloudSnapshot 超规模拒绝(1)、JwParser 脏数据(4)；全量 331 个单测通过。
