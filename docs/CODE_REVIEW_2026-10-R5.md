# 贝壳课表 全量代码审查报告（2026-10 第五轮 · v1.3.9 / versionCode 50）

> 本报告由 zcode「Android 全量代码审查」工作流于 2026-10-01 产出，原存于工作流产物目录
> （仓库外，`~/.zcode/cli/artifacts/sess_85277f4d…/dwfrun-ef8a67ec….md`），2026-10-05 归档入库。
> 修复情况见文末第 8 节。这是**唯一覆盖 v1.3.9「登录并同步」重构（`ui/sync/`）**的一轮审查。

审查范围：全部源码，共 93 个文件。发现 35 条，其中 35 条经独立复核证实。

## 总体结论
门禁与实测均通过：编译 BUILD SUCCESSFUL、Lint 完成（报告在 app/build/reports/），模拟器走查 10 个界面、12530 行 logcat 零崩溃零 ANR，核心链路全部正常，未覆盖真实扫码登录。静态审查 35 条发现按同根因合并后为 28 条（10 medium + 18 low，无 high），其中关键行号经本次抽查核对无误（如 CloudSnapshot.kt:267、SmartClassApi.kt:187、SettingsViewModel.kt:115/:137）。最要紧的是三条数据安全类 medium：一键同步「重试失败项」静默丢云端半程、考试解析失败被折叠成空列表清空本地数据并取消提醒、清除成绩缓存会置脏并在 8 秒后覆盖云端备份。其余集中在课表周次推导边界、提醒排期（缺 MY_PACKAGE_REPLACED、requestCode 基准不一致）、Todo 交互与错误文案链路；另有 11 处死代码可一次性清理。建议修复顺序：medium 的数据安全三条 → 同步/提醒链路 → low 批量清理。

## 构建门禁
- compileDebugKotlin：编译通过（BUILD SUCCESSFUL）
- lintDebug：Lint 完成（报告在 app/build/reports/ 下）

## 合并后的发现
- 【medium·同步】「重试失败项」在 CLOUD_TOKEN 首轮失败（mode 仍为 UNDECIDED）后按 UNDECIDED 步骤表重试，重试成功决策出 UPLOAD/RESTORE 后不再追加 BACKUP/RESTORE 步骤，云端半程静默丢失——汇总提示「将上传本机数据」却不上传；更糟的是用户选「从云端恢复」时 head 里已含的 TIMETABLE/GRADES 照常抓取并 commitImport 本机数据，明确选择的恢复被静默替换。位置：app/src/main/java/com/caeamer/beikeschedule/ui/sync/UnifiedSyncViewModel.kt:263-277（步骤表 ui/sync/SyncPlan.kt:61-63，mode 决策在 UnifiedSyncViewModel.kt:367）。修法：CLOUD_TOKEN 重试成功得出 mode 后，按新 mode 补齐 stepBackup/stepRestore。
- 【medium·解析/数据安全】考试解析失败与「确实无考试」被折叠成空列表：教务各接口均为 {code,msg,content} 包装响应，考试接口返回无 list 字段的错误体（如 {code:500}）或登录页 HTML 时 examsJson 非空但解析为空列表，调用方 replaceExams（clear+insertAll 覆盖式写入）清空已存考试安排、取消未来考试提醒，且「获取失败，已保留上次数据」提示只在 examsJson 为空时才加，用户无感知；下次同步成功可恢复。位置：app/src/main/java/com/caeamer/beikeschedule/import/parser/ExamsParser.kt:32-34 → UnifiedSyncViewModel.kt:463-482（:476-477 注释自认空列表=取消提醒）→ ScheduleRepository.kt:47-49。修法：parseExams 返回可空/带失败信号的结果，失败时跳过覆盖并走失败提示分支。
- 【medium·同步 WebView】onReceivedSslError 未像同文件 onReceivedError/onReceivedHttpError 一样过滤 isForMainFrame，任何子资源（图片/脚本/iframe）SSL 校验失败都会触发 onPageError，把整个一键同步页置为 Failed 态（登录横幅变红要求重试）。位置：app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:275-282（对照 :260/:270），下游 UnifiedSyncViewModel.onPageError:171-177。修法：onReceivedSslError 内先判 request.isForMainFrame 再报错，子资源失败仅记日志。
- 【medium·课表】合并卡片与行组口径不一致：CourseMerger 的 SlotKey 不含 source，跨来源（教务导入 vs 手动）同名同段多行合并成一张卡；但隐藏/删除/编辑按「名字+来源」取行组（groupOf），另一来源的行不在组内——隐藏后同名卡片仍留在网格上、编辑只改一半。触发：手动添加与某导入课程同名同时段的课程（如教务漏排）。位置：app/src/main/java/com/caeamer/beikeschedule/model/CourseMerger.kt:17/20 vs ui/schedule/ScheduleScreen.kt:178-179（groupOf）、:403/:407（删除隐藏）、:185-186（manualNamesInUse 只禁手动-手动重名）、ScheduleViewModel.kt:50。修法：统一身份口径——SlotKey 纳入 source，或 groupOf 改为按合并卡维度取行。
- 【medium·课表/提醒】有官方校历但校历周数少于 totalWeeks 时，teachingWeekOf 对超出校历覆盖的教学周返回 null 不做顺延，导致这些周上画提醒整段停排（ClassReminderScheduler.kt:148 `?: continue`）、「下一节课」图钉消失（ScheduleScreen.kt:195），而展示层 weekMonday 顺延+课程位图仍认为有课——与 WeekResolver.kt:87-89 自身文档「校历没覆盖到时从最后一个已知周一顺延」相悖。可由导入数据直接产生：JwParser.kt:122 的 mondays 只建到 weeks 数组最后一项的 zc，UnifiedSyncViewModel.kt:435 单独保存 totalWeeks，二者可不等；SemesterSettingsDialog.kt:106-116 下拉提供 22/25 周且只强制不小于校历长度。位置：app/src/main/java/com/caeamer/beikeschedule/model/WeekResolver.kt:29-33、data/repo/ScheduleRepository.kt:290-293。修法：teachingWeekOf 仿照 weekMonday 的顺延逻辑覆盖超出校历的周。
- 【medium·提醒】BootReceiver 缺 ACTION_MY_PACKAGE_REPLACED（AndroidManifest.xml:55-68 的 intent-filter 同样没有）：本应用带应用内自更新（Manifest:11 REQUEST_INSTALL_PACKAGES），而 Android 在应用更新后会清空该包全部闹钟（含每日脉冲），更新后考试提醒等要等到用户重新打开 App 并触发某条重排路径才恢复——且 grep 证实 ExamReminderScheduler.reschedule 的 5 个入口（CloudSnapshot.kt:288、SettingsViewModel.kt:178、UnifiedSyncViewModel.kt:476、ReminderReceiver.kt:41、BootReceiver.kt:34）里没有「打开 App 即重排」路径。标准做法是监听 MY_PACKAGE_REPLACED（Android 官方后台启动豁免清单含该广播）。位置：app/src/main/java/com/caeamer/beikeschedule/reminder/BootReceiver.kt:20-27。修法：intent-filter 增加该 action 并在 when 分支重排。
- 【medium·成绩/云同步】「清除成绩缓存」的四个写点（saveGradesMeta/saveCreditMeta 以 noteCloudDirty() 开头，replaceGrades/replaceExams 带 .also{markCloudDirty()}）全部触发云同步置脏，已登录且开启云同步的用户会在 8 秒去抖后把「成绩/考试/GPA 已清空」的状态整包上传，云端备份被无声覆盖，之后从云端恢复也找不回；恢复链路对写入点有 suppressDirty 保护（CloudSync.kt:167），唯独这条用户主动清空路径没有等价保护，确认弹窗文案（ProfileScreen.kt:577-582）也只说「课表与隐藏设置不受影响」。位置：app/src/main/java/com/caeamer/beikeschedule/ui/settings/SettingsViewModel.kt:166-181（SettingsStore.kt:165-166/186-187、ScheduleRepository.kt:41-50、CloudSync.kt:51-59）。修法：clearGradesCache 全程 suppressDirty，或弹窗明示并征得同意。
- 【medium·成绩 UI】考试按日期分组的 toSortedMap 比较器把「时间待定」（ksrq 空串）和 ksrq 非空但解析失败的 key 都映射到 LocalDate.MAX，两个不同 key 比较相等，TreeMap 视为重复 key 用后一组覆盖前一组，一组考试从列表静默消失且无提示。位置：app/src/main/java/com/caeamer/beikeschedule/ui/grades/GradesScreen.kt:540-542（runCatching 兜底分支的存在说明作者预期解析失败会出现）。修法：为冲突 key 追加序号使其可比，或改用「日期字符串+待定桶」两级分组。
- 【medium·Todo】「已过期（未完成）」分区里的一次性事项打卡是无效交互：行渲染硬编码 done=false（TodoScreen.kt:221），而过滤条件 `todo.lastDoneDate != todo.date`（TodoViewModel.kt:47）下今天打卡写入的 lastDoneDate=今天永远不等于过去的 date，条目永远不离开过期列表且勾选框恒显示未勾——但 :222 又把过期行的勾选圈接到了 onToggleDone 上。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:216-224、ui/todo/TodoViewModel.kt:44-48（勾选写入 :72-74）。修法：过期分区按 lastDoneDate 判定勾选态与过滤，或打卡后从过期列表移除并给出反馈。
- 【medium·Todo UI】编辑表单底部弹层内容不可滚动：ModalBottomSheet 内容槽无 verticalScroll，表单含标题、2 个输入框、下拉、星期/日期条件块、时间+提前分钟行、10 色色板、删除/取消/保存行（固有高度约 500dp 量级，估算值），小屏/横屏/键盘展开时底部操作行被裁掉而无法触达。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:354-360（表单内容 :361-470）。修法：Column 加 verticalScroll + rememberScrollState。
- 【low·云恢复校验（3 条合并）】云恢复 applyRestore 对网络快照零校验原样写库（教务导入入口已拦坏串，JwParser.kt:102-107 注释自认后果），三个后果：① 快照 weekMondays 带坏日期串时 WeekResolver 对中间坏项落空后按「最后一个已知周一倒推」，该周及之后所有周的周一映射整体错位一周；② 同样坏串进入 locateWeek/teachingWeekOf 的 mapNotNull 被静默丢弃、后续元素下标整体前移，`i+1` 算出的教学周序号全部错位（丢第 2 周后真实第 3 周被当第 2 周），上课提醒按错误周排期且无任何提示；③ 快照 totalWeeks 为 0/负数时 ScheduleViewModel.kt:110 的 `resolved.coerceIn(1, semester.totalWeeks)` 抛 IllegalArgumentException 且坏值已持久化，之后每次启动都在 stateIn 共享协程同一处崩（持久化崩溃环；应用内写入方均有防护：SemesterSettingsDialog.kt:272、UnifiedSyncViewModel.kt:435、CloudSnapshot.kt:158）。位置：app/src/main/java/com/caeamer/beikeschedule/data/backup/CloudSnapshot.kt:263-269（applyRestore，经查实际写入行为 :267 `weekMondays = s.semester.weekMondays`）+ model/WeekResolver.kt:96-104 + data/repo/ScheduleRepository.kt:267/288 + ui/schedule/ScheduleViewModel.kt:110。修法：applyRestore 复用教务导入的日期串校验，并对 weekMondays/totalWeeks 做与解析层一致的清洗（totalWeeks 取 coerceAtLeast(1)，坏串剔除或整表拒绝恢复）。
- 【low·错误文案链路（2 条合并）】异常 message 被当作用户文案透传，但链路两端失控：① SmartClassApi.request 在签名失败时精心构造的「教务接口签名失败（密钥异常）」永远不会到达用户——被自己的 catch 转成 RawResponse(-1)，再经 fetch→networkMessage 的 else 分支整体替换成通用「网络请求失败，请稍后重试」（原始 IOException 不在 UnknownHost/超时/Connect/SSL 特判之列）；② 反方向，CloudApi.parseOrThrow 把内部接口路径拼进异常文案「/api/bs/backup 响应格式异常」，经 SettingsViewModel 的 "备份失败：${e.message}" / "恢复失败：${e.message}" 直接 Toast 给用户（内部实现泄露）。位置：app/src/main/java/com/caeamer/beikeschedule/data/remote/SmartClassApi.kt:187、:211-212、:224-225（本次抽查确认）+ data/remote/CloudApi.kt:196 + ui/settings/SettingsViewModel.kt:115/:137（原清单记 :101/:131/:140，已按当前代码核对）。修法：为两类 API 各定义用户可读的错误类型（sealed/带 code+文案），Toast 只展示映射后的文案，开发者细节进日志。
- 【low·解析防御（2 条合并）】JwParser 对脏数据的两处防御缺口：① 固定时间课程行 ZC 字段缺失时 weekBitmap 落成空串，整行照常入库但任何周都不渲染——既不跳过也不留日志，与同文件「缺节次宁可整行跳过并留 rowErrorLogger」的策略（:141-143）不一致，空位图行在周网格上「静默消失」（实测样例 32 行均有 ZC，仅脏数据触发）；② parseNoteWeeks 从备注文本解析的周数区间无上限校验，脏数据（如「1-99999999周」）把整个区间逐个加入 Set，内存与耗时无界——同文件 :100-101 对 zc 脏值有 MAX_TOTAL_WEEKS=60 的同类防御，此处缺失（输入正常受控）。位置：app/src/main/java/com/caeamer/beikeschedule/import/parser/JwParser.kt:147-151、:178-188。修法：固定时间行空位图走与缺节次相同的整行跳过+留痕；parseNoteWeeks 区间夹到 1..MAX_TOTAL_WEEKS。
- 【low·解析】SmartClassParser 对字符串字段一律 optString 未做 isNull 防御：org.json 的 optString 在字段显式为 JSON null 时返回字面量 "null"（非空串），isBlank() 判不住——服务端若把 id/name/msg/nodeName/classroomName 下发为 null，会生成名为 "null" 的教学楼/时段/教室或错误文案 "null"。本文件只在 noSeatRate 一处（:136）显式区分了 null，说明作者知晓该坑但未覆盖字符串字段；触发条件未实测出现，属防御缺口。位置：app/src/main/java/com/caeamer/beikeschedule/data/remote/SmartClassParser.kt:97-98（同款 :89、:126-127、:132-133）。修法：抽一个 optStringOrNull（isNull→null）统一替换。
- 【low·成绩】排除身份按裸 kcdm 匹配，而 GradeRows 已承认空 kcdm 行存在（解析兜底为空串，GradeRows.kt:38-39 注释）：多门不同的空 kcdm 课程共享 "" 这一排除键，用户排除其中一门会把全部空 kcdm 课程一起排除；与 GradeRows.kt:57 分组键 `it.kcdm.ifBlank { it.kcmc }` 的口径不一致，下游 GradesViewModel.kt:209-212 勾选列表不过滤空 kcdm、:368-369 按 kcdm 字符串增删。位置：app/src/main/java/com/caeamer/beikeschedule/data/repo/WeightedScoreCalculator.kt:29。修法：排除键与分组键统一改用 kcdm.ifBlank{kcmc}（或行级 id）。
- 【low·同步】onPageFinished 用 view.post 延迟回调 onMainPage，用户在 post 执行前退出页面时该 runnable 仍会在 DisposableEffect 的 viewModel.cancel() 之后执行，在 Activity 级 ViewModel 里启动一次无人消费的「幽灵同步」：runJob 已被 cancel 置 null，post 执行时 onPageReady 判定不活跃且非 Done 就 startRun()，此时界面已销毁、jsRequests（replay=0、extraBufferCapacity=4）的收集者已取消，脚本资产被丢弃，每步只能等 30s 超时；期间再进入本页 resetIfFinished 因 `runJob?.isActive == true` 直接返回，看到的是无人驱动的 Running 态。位置：app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:242（配合 ui/sync/UnifiedSyncViewModel.kt:188-196、:152-156、:97、:658、:136）。修法：onRelease 时撤销 pending post（removeCallbacks），或 onPageReady 先校验 WebView 仍处于活跃会话。
- 【low·文档失实】BRIDGE_ORIGINS 的注释声称覆盖「ustb 其它子域，让登录页上的报错能经桥回传」，但两条规则都是 https-only（byyt.ustb.edu.cn 与 *.ustb.edu.cn），而 jwNavPolicy 明确允许站内 http 页面加载（举例 http://sso.ustb.edu.cn/idp/thirdAuth/...）——http 的 ustb 页面能在 WebView 里加载却拿不到任何桥，注释承诺与实际不符，易误导后续维护者（当前三个注入脚本只注入 https 的 byyt 页面，暂无功能影响）。位置：app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:33-39（对照 :364-368、:374）。修法：改注释如实描述 https-only，或按需把桥判断放宽到站内 http。
- 【low·提醒】两处接收器用单个 runCatching 包住三个 scheduler 的 reschedule，第一个抛异常（如 DataStore/Room IO 错误，SettingsStore 注释自认可能发生）会连带跳过考试与日程的重排；代码库自身惯例是逐个隔离（CloudSnapshot.kt:287-289 三个独立 runCatching，ScheduleViewModel.kt:194、GradesViewModel.kt:332、SettingsViewModel.kt:178 同）。位置：app/src/main/java/com/caeamer/beikeschedule/reminder/BootReceiver.kt:32-36 与 reminder/ReminderReceiver.kt:39-43。修法：拆成三个独立 runCatching。
- 【low·提醒】定向取消（打卡）的 requestCode 以「出现日 today」为基准，而计划/记录中的码以 trigger.toLocalDate()（触发日=计划时刻−提前量）为基准：提前量把触发时刻推过午夜的日程，打卡后 forceCancel 算出的码不命中已排记录，且该记录 triggerAtMillis 已 ≤ now 被「已到点不动」分支保护（ReminderAlarmScheduler.kt:75）→ 打了卡仍收到提醒。窗口窄（需跨午夜且闹钟恰在 Doze 队列未投递）。位置：app/src/main/java/com/caeamer/beikeschedule/reminder/TodoReminderScheduler.kt:79-82（对照 :119）。修法：记录里存实际 requestCode（或统一都用 trigger 日），取消按记录值。
- 【low·提醒】examTimeText 在 kssj 非空而 jssj 为空时（真实数据形态：教务时间描述如 "2027-01-15 09:00"，ExamsParser 的 TIME_REGEX 两组均可选）只显示日期，通知丢掉开考时间——「即将考试」提醒恰是用户最需要开考时间的场景，且与 App 内考试列表回退到 kssjms 原文（含时间）的口径（GradesScreen.kt:626-630）不一致，通知反而比 App 内显示的信息少。位置：app/src/main/java/com/caeamer/beikeschedule/reminder/ExamReminderScheduler.kt:160-164。修法：第二分支拿 ksrq 之外再补 kssjms 原文兜底（对齐 GradesScreen 的回退）。
- 【low·课表 UI（2 条合并）】CourseEditDialog 的 droppedWeeks 警告与实际行为脱节，两处失真：① remember(sessions, unscheduledWeeks, totalWeeks) 的键中 sessions 是同一 SnapshotStateList 实例、元素引用不变，会话内周次增删后计算不重跑，警告文本停留为旧的「有 N 个周次超出…保存后会被丢弃」（对照 :149-153 的 valid 直接读状态所以是实时的，可见 remember 用法与意图不符）；② 混合课程（有时段行+无固定时间行）下警告把无固定时间行的超范围周次也计为「保存后会被丢弃」，实际 CourseRowBuilder 仅在 onlyUnscheduled 时才改写周次，否则原样透传（CourseRowBuilder.kt:48-52）——警告是假的且用户在混合界面里无从修正。位置：app/src/main/java/com/caeamer/beikeschedule/ui/schedule/CourseEditDialog.kt:141-147（警告文案 :289-297）。修法：① 改为派生状态或把会话内编辑操作纳入键；② 仅在 onlyUnscheduled 时计入 unscheduledWeeks 的超范围项。
- 【low·设置 UI】notificationsBlocked/exactAlarmBlocked 用无键 remember 计算，仅在对话框进入组合时算一次：用户点「去开启通知/精确闹钟」跳系统设置开启权限后返回（Activity 通常不重建、组合保留），对话框仍显示「已被系统关闭」的过期诊断，须关掉重开才刷新——注释声称的「每次打开设置页现算」不成立。位置：app/src/main/java/com/caeamer/beikeschedule/ui/schedule/SemesterSettingsDialog.kt:76-80（跳转入口 :189、:203）。修法：用 ON_RESUME 生命周期观察或 disposableEffect 每次回到前台重算。
- 【low·更新】应用内更新下载无进行中去重与清理：force 弹窗保持打开时「前往下载」可反复点、每次都 dm.enqueue 排队同一文件名，两个完成广播各自触发 finishAndInstall 并发跑哈希校验/拉起安装；且无论成功或哈希不符都只 file.delete()、不调 dm.remove(id)，下载通知与 APK 文件留存于应用外部目录。位置：app/src/main/java/com/caeamer/beikeschedule/ui/settings/UpdateInstaller.kt:45-73（配合 ui/settings/ProfileScreen.kt:509-523，force 弹窗 confirm 后不关闭）。修法：enqueue 前按进行中下载去重（记录下载 id、弹窗期间禁用按钮），终态时 dm.remove(id)。
- 【low·Todo】「每周」重复的默认 weekdays 位图 "0111110" 在周一索引制（TodoEntity.kt:28-29 注释 0=周一…6=周日；TodoPlanner.kt:25-26 用 dayOfWeek.value-1 取位；WeekdaySelector index0=一）下实际点亮的是周二~周六，疑似想给「周一~五」（"1111100"）时差了一位；表单沿用同一默认（TodoScreen.kt:337）。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:337。修法：默认值改为 "1111100"（并确认既有数据不迁移）。
- 【low·Todo】toggleDone 的读-改-写仍非原子：两次点击各自 viewModelScope.launch、各自 repo.todos.first()（Room 冷流每次重新查库）后再写库，A 协程挂起等查询时 B 随即启动，两次 first() 读到同一旧行 → 两次都写「打卡」；无 Mutex/单飞行队列串行化，KDoc（:64-69）声称已修复的竞态窗口只是收窄并未关闭。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoViewModel.kt:70-75。修法：viewModel 内串行化（Mutex 或按 todoId 的单飞行队列）。
- 【low·Todo】uiState 的时间基准只在 Room 发射时求值：map 内取 LocalDate.now()，而 map 只在 todo 表发射时执行（Room 失效监听按表触发），stateIn WhileSubscribed(5000) 持续订阅期间不重算——前台跨夜不重建收集时 groups/doneIds/expired 仍是旧 today 的产物（昨日打卡的每日事项今日仍显示已完成），TodoScreen.kt:162-165 注释声称跨午夜已处理但 rememberNow 只让分组标签/淡化跟着变。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoViewModel.kt:40-52（:49）。修法：把 today 作为独立流（如定时翻转或 ON_RESUME 触发）与 todo 流 combine。
- 【low·无障碍】WeekdaySelector 用普通 clickable 且带 onClickLabel，但未向 TalkBack 暴露圆点的选中态；同文件 ColorSelector 用 selectable(selected=...)+Role.RadioButton 能正确朗读，两处做法不一致。位置：app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:542（对照 :582-586）。修法：改用 selectable(selected=on, role=Role.Checkbox)。
- 【low·死代码（3 处合并，全仓含 test 无任何调用方，grep 已核实）】共 11 个不可达成员：① DAO 层 7 个方法——CourseDao.observeByNames/getByIds/count（app/src/main/java/com/caeamer/beikeschedule/data/local/CourseDao.kt:20-21/:23-24/:48-49，本次抽查确认；observeByNames 含 ScheduleRepository.observeCourseByName:158 与 ScheduleViewModel.observeCourseByName:230 两级包装，其注释宣称的「多时段课程分组编辑」用途已被 ScheduleScreen.kt:178-179 的内存分组 filter{name+source} 替代）、GradeDao.count（GradeDao.kt:14-15）、ExamDao.getAll（ExamDao.kt:14-15）、TodoDao.getAll/getById（TodoDao.kt:15-16/:18-19）；② ViewModel 层 3 个方法——ScheduleViewModel.saveCourse/observeCourseByName/deleteCourse（ui/schedule/ScheduleViewModel.kt:213-217/:229-231/:248-250，UI 实际走 saveCourses/setCoursesHidden）；③ JwWebView 单桥便捷重载（import/JwWebView.kt:307-330，startUrl 写死 JW_HOME 无法传 JW_SSO_ENTRY_URL，签名上还挂着无用的 @SuppressLint("SetJavaScriptEnabled")，一键同步合并后的孤儿 API）。修法：直接删除；连同 observeByNames 的过时注释一并清理。

## 全部发现明细
- **app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:275-282** [逻辑/静态审查/medium] onReceivedSslError 没有像同文件另两个错误回调那样过滤 isForMainFrame，任何子资源（图片/脚本/iframe）SSL 校验失败都会触发 onPageError，把整个一键同步页打进 Failed 态。
  证据：onReceivedError 与 onReceivedHttpError 都先判断 `if (request.isForMainFrame)`（JwWebView.kt:260、270）才报错，而 onReceivedSslError 直接 `handler.cancel(); onPageError("SSL 证书校验失败（${error.primaryError}）...")`。onReceivedSslError 对主框架与子资源都会触发，任何一个子资源证书异常都会把同步页置为 Failed 态（UnifiedSyncViewModel.onPageError:171-177），登录横幅变红并要求重试。
- **app/src/main/java/com/caeamer/beikeschedule/import/parser/ExamsParser.kt:32-34** [逻辑/静态审查/medium] parseExams 把「解析失败/错误响应」和「确实无考试」都折叠成空列表，调用方据此清空本地考试数据并取消未来考试提醒，且同步汇总不提示。
  证据：ExamsParser.kt:33 `json.parseToJsonElement(jsonText).jsonObject["list"]?.jsonArray` 包在 runCatching 里，取不到 list 就 `?: return emptyList()`，无任何失败信号。调用方 UnifiedSyncViewModel.kt:463 `val examsFromServer = examsJson.isNotBlank()`，:470 `if (examsFromServer) repo.replaceExams(ExamsParser.parseExams(...))`，:481 `if (examsFromServer) ExamReminderScheduler.reschedule(getApplication())`（:476-477 注释自认「空列表=取消未来提醒」），而 ScheduleRepository.kt:47-49 replaceExams 是 `examDao.clear()` + `insertAll(exams)` 覆盖式写入。教务各接口均为包装响应 {code,msg,content}（CreditProgressParser.kt:14 注释），考试接口返回无 list 字段的错误体（如 {code:500,...}）或登录页 HTML 时 examsJson 非空但解析为空列表 → 已存的考试安排被清空、未来考试提醒被取消，且 :481-482 的「考试安排获取失败，已保留上次数据」提示只在 examsJson 为空时才加，用户无感知。下次同步成功可恢复，故评 medium。
- **app/src/main/java/com/caeamer/beikeschedule/model/WeekResolver.kt:29-33** [逻辑/静态审查/medium] 有官方校历但校历周数少于 totalWeeks 时，teachingWeekOf 对超出校历覆盖的教学周返回 null，导致这些周的上画提醒整段停排、「下一节课」图钉消失，而展示层（weekMonday 顺延 + 课程位图点亮）仍认为这些周有课
  证据：WeekResolver.kt:29-33 只要 weekMondays 非空就全走 ScheduleRepository.teachingWeekOf；ScheduleRepository.kt:290-293 该函数遍历完校历后 `return null`——超出最后一个校历周一+6 天即 null，不做顺延。而同文件 WeekResolver.kt:87-89 的 weekMonday 文档明确写着「校历没覆盖到（用户把总周数调大、或本来就没有校历）时从最后一个已知周一顺延」。分歧可由导入数据直接产生：JwParser.kt:122 的 mondays 只建到 weeks 数组最后一项的 zc，而 UnifiedSyncViewModel.kt:435 单独保存 `totalWeeks = weekCalendar.totalWeeks`，二者可不等；SemesterSettingsDialog.kt:106-116 下拉提供 22/25 周且只强制不小于校历长度。下游影响：ClassReminderScheduler.kt:148 `val week = teachingWeekOf(semester, date) ?: continue` 整天跳过不排闹钟；ScheduleScreen.kt:195 图钉因 todayTeachingWeek=null 不显示
- **app/src/main/java/com/caeamer/beikeschedule/model/CourseMerger.kt:17** [逻辑/静态审查/medium] SlotKey 不含 source，跨来源（教务导入 vs 手动）同名同段的多行会合并成一张卡；但卡片的隐藏/删除/编辑按「名字+来源」取行组（groupOf），另一来源的行不在组内——隐藏后同名卡片仍留在网格上、编辑只改一半
  证据：CourseMerger.kt:17 `groupBy { SlotKey(it.name, it.dayOfWeek, it.startSection, it.endSection) }`（无 source 字段，:20）；ScheduleViewModel.kt:50 scheduledCourses 混含全部来源；ScheduleScreen.kt:581+608 网格渲染合并后课程；而 ScheduleScreen.kt:178-179 `groupOf` 按 `name + source` 过滤，:185-186 manualNamesInUse 只禁止手动-手动重名（手动课程可以用与导入课程相同的名字），:403/:407 删除与隐藏都用 groupOf 的 id。触发路径：用户手动添加与某导入课程同名、同时段（如同名节次教务漏排）的课程后，两行合并为一张卡，按卡隐藏只隐藏基准行所在来源，另一来源的行重新合并成卡继续显示
- **app/src/main/java/com/caeamer/beikeschedule/reminder/BootReceiver.kt:20-27（配合 AndroidManifest.xml:55-68）** [逻辑/静态审查/medium] BootReceiver 缺 ACTION_MY_PACKAGE_REPLACED：本应用带应用内自更新，而 Android 在应用更新后会清空该应用全部闹钟（含每日脉冲），更新后考试提醒等要等到用户重新打开 App 并触发某条重排路径才恢复。
  证据：BootReceiver.kt:20-27 的 when 只覆盖 BOOT_COMPLETED/TIME_CHANGED/TIMEZONE_CHANGED/DATE_CHANGED；AndroidManifest.xml:58-67 的 intent-filter 同样没有 MY_PACKAGE_REPLACED。而本应用带应用内自更新（Manifest:11 REQUEST_INSTALL_PACKAGES）。grep 证实 ExamReminderScheduler.reschedule 的入口只有 5 个：CloudSnapshot.kt:288（云恢复）、SettingsViewModel.kt:178（清缓存）、UnifiedSyncViewModel.kt:476（抓取成功）、ReminderReceiver.kt:41（每日脉冲）、BootReceiver.kt:34（开机）——没有"打开 App 即重排"的路径；上课提醒有（ScheduleViewModel.kt:184-196 的收集器在打开 App 时触发），且 ClassReminderScheduler.reschedule:124 会重新武装每日脉冲。平台行为：应用更新后该包全部闹钟被系统清空，标准做法是监听 ACTION_MY_PACKAGE_REPLACED 重排（Android 官方后台启动豁免清单含该广播；cordova-plugin-local-notifications README 明确记载"Android removes all alarms when the app is updated"并以该广播恢复）。
- **app/src/main/java/com/caeamer/beikeschedule/ui/sync/UnifiedSyncViewModel.kt:263-277** [逻辑/静态审查/medium] 「重试失败项」在 CLOUD_TOKEN 首轮失败（mode 仍为 UNDECIDED）后，重试计划按 UNDECIDED 步骤表执行，CLOUD_TOKEN 重试成功并决策出 UPLOAD/RESTORE 后不再追加 BACKUP/RESTORE 步骤，云端那一半流程静默丢失。
  证据：UnifiedSyncViewModel.kt:263-264 `val head = if (retryOnlyFailed) { SyncPlanner.retrySteps(mode, results) }`，而 SyncPlan.kt:61-63 中 UNDECIDED 的计划只含 IDENTITY/CLOUD_TOKEN/TIMETABLE/GRADES；mode 在 stepCloudToken 内才被改为 UPLOAD/RESTORE（UnifiedSyncViewModel.kt:367），但尾部步骤只在 `if (!retryOnlyFailed)` 分支追加（269-277 行），重试路径永不补跑 stepBackup/stepRestore。后果：汇总提示「将上传本机数据」却不上传（setCloudSyncEnabled(true) 也不会执行）；更糟的是重试中用户选「从云端恢复」时，head 里已包含的 TIMETABLE/GRADES 会照常抓取并 commitImport 本机数据，而 stepRestore 一次都不跑——用户明确选择的恢复被静默替换成本机抓取。
- **app/src/main/java/com/caeamer/beikeschedule/ui/grades/GradesScreen.kt:540-542** [逻辑/静态审查/medium] 考试按日期分组的 toSortedMap 比较器把「时间待定」和无法解析日期的 key 都映射到 LocalDate.MAX，两个不同 key 比较相等，TreeMap 视为重复 key 用后一个组覆盖前一个，一组考试被静默丢弃。
  证据：GradesScreen.kt:540-542 `exams.groupBy { it.ksrq.ifBlank { "时间待定" } }.toSortedMap(compareBy { key -> if (key == "时间待定") LocalDate.MAX else runCatching { LocalDate.parse(key) }.getOrNull() ?: LocalDate.MAX })`——ksrq 为空串的考试归入「时间待定」，ksrq 非空但解析失败的考试（runCatching 兜底分支的存在说明作者预期这种情况会出现）生成独立 key，两者比较值都是 LocalDate.MAX；TreeMap 对比较相等的 key 执行 put 是替换 value，先放入的整组考试从列表消失且无任何提示。
- **app/src/main/java/com/caeamer/beikeschedule/ui/settings/SettingsViewModel.kt:166-181** [逻辑/静态审查/medium] 「清除成绩缓存」的四个写点全部触发云同步置脏，开启云同步的用户会在 8 秒去抖后把「成绩/考试/GPA 已清空」的状态整包上传，云端备份的成绩数据被无声覆盖，之后从云端恢复也找不回。
  证据：clearGradesCache 调 settings.saveGradesMeta("", 0L)、settings.saveCreditMeta("", "")（SettingsStore.kt:165-166、186-187 均以 noteCloudDirty() 开头）及 repo.replaceGrades(emptyList())、repo.replaceExams(emptyList())（ScheduleRepository.kt:41-50 的 `.also { markCloudDirty() }`）→ CloudSync.markDirty → 已登录且开启云同步时排 8 秒去抖整包上传（CloudSync.kt:51-59），新快照 grades/exams 为空、gpaJson 为空串，覆盖云端旧备份。恢复链路对写入点有 suppressDirty 保护（CloudSync.kt:167），唯独这条用户主动清空路径没有等价保护；确认弹窗文案（ProfileScreen.kt:577-582）只说「课表与隐藏设置不受影响」，未提云端备份会一并清掉。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:216-224（配合 TodoViewModel.kt:44-48）** [逻辑/静态审查/medium] 「已过期（未完成）」分区里的一次性事项，打卡是无效交互：勾选后界面无任何变化，且该事项永远不会离开此列表，只能删除。
  证据：TodoScreen.kt:221 对过期行硬编码 `done = false`；TodoViewModel.kt:47 过滤条件为 `todo.lastDoneDate != todo.date`——勾选写入 lastDoneDate=今天（TodoViewModel.kt:72-74），今天永远不等于过去的 date，条目仍留在过期列表且勾选框恒显示未勾；而 TodoScreen.kt:222 明确把过期行的勾选圈接到了 onToggleDone 上。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:354-360** [UI/静态审查/medium] 编辑表单底部弹层内容不可滚动：小屏、横屏或键盘展开时，底部「删除/取消/保存」行会被裁掉而无法触达。
  证据：TodoScreen.kt:354-360：`ModalBottomSheet { Column(Modifier.fillMaxWidth().padding(...)) }`，无 `verticalScroll`；ModalBottomSheet 内容槽不会自动滚动，溢出部分直接裁掉。表单含标题、2 个输入框、下拉、星期/日期条件块、时间+提前分钟行、10 色色板、删除/取消/保存行（:361-470），固有高度合计约 500dp 量级（估算值）。
- **app/src/main/java/com/caeamer/beikeschedule/data/local/CourseDao.kt:20（另见同文件 23-24、48-49；GradeDao.kt:14-15；ExamDao.kt:14-15；TodoDao.kt:15-16、18-19）** [代码/静态审查/low] 7 个 DAO 方法在全项目（含 test）无任何调用方，属死代码：CourseDao.observeByNames（含 ScheduleRepository.observeCourseByName:158 与 ScheduleViewModel.observeCourseByName:230 两级包装）、CourseDao.getByIds(:23)、CourseDao.count(:48)、GradeDao.count(:14)、ExamDao.getAll(:14)、TodoDao.getAll(:15)、TodoDao.getById(:18)；其中 observeByNames 注释宣称的「多时段课程分组编辑」用途已被内存分组实现取代。
  证据：grep -rn --include=*.kt "observeByNames|getByIds|getById|.count()|.getAll()" app/src（排除 data/local 自身）仅命中定义处与 ScheduleRepository.kt:159 的传递调用；CourseDao.kt:18-19 注释宣称用于「多时段课程分组编辑」，但实际由 ScheduleScreen.kt:178-179 的内存分组替代：`fun groupOf(course: CourseEntity): List<CourseEntity> = state.courses.filter { it.name == course.name && it.source == course.source }`
- **app/src/main/java/com/caeamer/beikeschedule/data/remote/SmartClassApi.kt:187** [逻辑/静态审查/low] request() 在签名生成失败时精心构造的文案"教务接口签名失败（密钥异常）"永远不会到达用户——它被自己的 catch 转成 RawResponse(-1)，再经 fetch→networkMessage 的 else 分支替换成通用"网络请求失败，请稍后重试"。
  证据：SmartClassApi.kt:187 `?: throw java.io.IOException("教务接口签名失败（密钥异常），请稍后重试")`；:211-212 `catch (e: Exception) { … RawResponse(-1, null, null, e) }`；:152-153 `networkMessage(res.error)`；:224-225 `else -> "网络请求失败，请稍后重试"`（普通 IOException 不在 UnknownHost/超时/Connect/SSL 特判之列，原始 message 被整体替换）。
- **app/src/main/java/com/caeamer/beikeschedule/data/remote/CloudApi.kt:196** [UI/静态审查/low] parseOrThrow 把内部接口路径拼进异常文案，最终经 SettingsViewModel 的 "备份失败：${e.message}" / "恢复失败：${e.message}" 以 Toast 呈现，用户会看到 "/api/bs/backup 响应格式异常" 这类开发者信息。
  证据：CloudApi.kt:196 `throw IOException("$path 响应格式异常")`；SettingsViewModel.kt:101 `"备份失败：${e.message}"`、:131/:140 `"恢复失败：${e.message}"` 直接展示。
- **app/src/main/java/com/caeamer/beikeschedule/data/remote/SmartClassParser.kt:97-98** [逻辑/静态审查/low] 对字符串字段一律 optString 未做 isNull 防御：org.json 的 optString 在字段显式为 JSON null 时返回字面量 "null"（非空串），isBlank() 判不住，服务端若把 id/name/msg/nodeName/classroomName 下发为 null，会生成名为 "null" 的教学楼/时段/教室或错误文案 "null"。本文件只在 noSeatRate 一处做了 isNull 区分，说明作者知晓该坑但未覆盖字符串字段。
  证据：SmartClassParser.kt:97-98 `val id = o.optString("id"); val name = o.optString("name")`（无 isNull 判断），:89/:126-127/:132-133 同款；对照 :136 `if (c.isNull("noSeatRate")) null else c.optDouble(…)` 显式区分了 null。触发条件（服务端对字符串字段下发 null）未实测出现，属防御缺口而非已复现 bug。
- **app/src/main/java/com/caeamer/beikeschedule/data/repo/ScheduleRepository.kt:267-294** [逻辑/静态审查/low] locateWeek/teachingWeekOf 用 mapNotNull 丢弃解析失败的周一，后续元素下标整体前移，导致 `i + 1` 计算的教学周序号全部错位（丢第 2 周后真实第 3 周被当成第 2 周），上课提醒会按错误周排期。
  证据：ScheduleRepository.kt:267 `val mondays = weekMondays.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }`，随后行 273 `return WeekLocation(i + 1, false, null)`、行 291 `return i + 1` 直接用数组下标当周号；weekMondays 的契约是"下标+1 = 教学周"（SettingsStore.kt:36-39）。teachingWeekOf（行 288-291）同样模式。仅在校历数据含解析失败项时触发，但一旦触发是周号/提醒整体错乱且无任何提示。
- **app/src/main/java/com/caeamer/beikeschedule/data/repo/WeightedScoreCalculator.kt:29** [逻辑/静态审查/low] 排除身份按裸 kcdm 匹配，而 GradeRows 已承认空 kcdm 行存在（解析兜底为空串）：多门不同的空 kcdm 课程共享 "" 这一排除键，用户排除其中一门会把全部空 kcdm 课程一起排除；与 GradeRows.kt:57 用 kcdm.ifBlank{kcmc} 做分组身份的口径不一致。
  证据：WeightedScoreCalculator.kt:29 `c.kcdm !in excludedKcdm &&` 以裸 kcdm 为键；GradeRows.kt:38-39 注释明确"kcdm 缺失（解析兜底为空串）的行"真实存在，行 57 分组键却是 `it.kcdm.ifBlank { it.kcmc }`。下游 GradesViewModel.kt:209-212 的勾选列表不过滤空 kcdm、行 368-369 按 kcdm 字符串增删，两门空 kcdm 课共享同一勾选状态。
- **app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:242** [逻辑/静态审查/low] onPageFinished 用 view.post 延迟回调 onMainPage，用户在 post 执行前退出页面时，该 runnable 仍会在 DisposableEffect 的 viewModel.cancel() 之后执行，在 Activity 级 ViewModel 里启动一次无人消费的"幽灵同步"。
  证据：JwWebView.kt:242 `view.post { onMainPage() }` 是排入主线程队列的普通 runnable，onRelease 销毁 WebView 不会撤销它。时序上若返回键的 input 消息先于该 post 入队：cancel() 把 runJob 置 null（UnifiedSyncViewModel.kt:188-196）→ post 执行 → onPageReady 判定 runJob 不活跃且状态非 Done → startRun()（UnifiedSyncViewModel.kt:152-156）。此时界面已销毁、jsRequests 的收集者已取消，而 `_jsRequests = MutableSharedFlow<String>(extraBufferCapacity = 4)` replay=0（UnifiedSyncViewModel.kt:97），脚本资产被丢弃，每个步骤只能等 STEP_TIMEOUT_MS=30s 超时（UnifiedSyncViewModel.kt:658）；期间用户再进入本页时 resetIfFinished 因 `runJob?.isActive == true` 直接返回（UnifiedSyncViewModel.kt:136），看到的是无人驱动的 Running 态。
- **app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:307-330** [代码/静态审查/low] 单桥便捷重载 JwWebView(bridge, bridgeName, ...) 在整个仓库（含测试）没有任何调用方，是死代码。
  证据：全仓 grep `JwWebView(` 仅命中两处定义（JwWebView.kt:109、310）与 UnifiedSyncScreen.kt:198 的一处调用（列表重载）。该重载 `startUrl = JW_HOME` 写死、无法传 JW_SSO_ENTRY_URL，签名上还挂着无用的 `@SuppressLint("SetJavaScriptEnabled")`（内部并未触碰 settings），是一键同步合并后遗留的孤儿 API。
- **app/src/main/java/com/caeamer/beikeschedule/import/JwWebView.kt:33-39** [代码/静态审查/low] BRIDGE_ORIGINS 的注释声称覆盖"ustb 其它子域，让登录页上的报错能经桥回传"，但两条规则都是 https-only，而 jwNavPolicy 明确允许站内 http 页面加载——这类页面能进 WebView 却拿不到桥，注释承诺与实际不符。
  证据：`BRIDGE_ORIGINS = setOf("https://byyt.ustb.edu.cn", "https://*.ustb.edu.cn")`（JwWebView.kt:39），而 jwNavPolicy 的文档与实现明确"站内（ustb.edu.cn 及其子域）的 http 与 https 同等放行"并举例 `http://sso.ustb.edu.cn/idp/thirdAuth/...`（JwWebView.kt:364-368、374）。两者叠成的实际行为：http 的 ustb 页面可以在 WebView 里加载，却拿不到任何桥。当前三个注入脚本只被注入到 byyt（https）页面，故暂无功能影响，但注释承诺的容错路径与真实规则不一致，容易误导后续维护者。
- **app/src/main/java/com/caeamer/beikeschedule/import/parser/JwParser.kt:147-151** [逻辑/静态审查/low] 固定时间课程行 ZC 字段缺失时 weekBitmap 落成空串，整行照常入库但任何周都不渲染，既不跳过也不留日志，与该文件「缺节次宁可整行跳过并留日志」的策略（:141-143）不一致。
  证据：JwParser.kt:147 `var weekBitmap = obj["ZC"]?.jsonPrimitive?.contentOrNull.orEmpty()`；:149 仅 `if (unscheduled && weekBitmap.isEmpty())` 才从备注文本兜底构造位图——固定时间行不在此列，ZC 为 null 时 weekBitmap="" 直接进 CourseEntity；对比 :141-143 缺节次时 `throw IllegalArgumentException(...)` 由 parseCourses 捕获后整行跳过并经 rowErrorLogger 留痕。空位图行在周网格上不可见，正是本文件注释里反复要避免的「静默消失」。实测样例 queryxszykbzong-2026-2027-1.json 的 32 行均有 ZC，此问题仅在脏数据下触发，评 low。
- **app/src/main/java/com/caeamer/beikeschedule/import/parser/JwParser.kt:184** [bug/静态审查/low] parseNoteWeeks 从备注文本解析出的周数区间没有上限校验，脏数据（如「1-99999999周」）会把整个区间逐个加入 Set，内存与耗时无界；同文件 :100-101 对 zc 脏值有 MAX_TOTAL_WEEKS=60 的同类防御，此处缺失。
  证据：JwParser.kt:178 `Regex("([\\d,\\-]+)周")` 提取自由数字串，:183-184 `val b = range.getOrNull(1)?.toIntOrNull() ?: a; weeks += a..b` 对 IntRange 逐元素展开，区间上限仅取决于文本里出现的数字；:186-188 才用 `for (w in 1..33)` 收敛到位图，但 Set 构造已先付出代价。对比 :100-101 注释「脏数据防御 1：zc 是教务/脚本产出的自由数字，脏值（如 1e5）会生成十万级列表」——同一威胁模型在备注周数上未设防。输入来自教务备注行、正常受控，评 low。
- **app/src/main/java/com/caeamer/beikeschedule/model/WeekResolver.kt:96-104** [逻辑/静态审查/low] weekMonday 对校历列表中间某个日期串解析失败的处理是错的：该周与之后所有周的周一映射整体错位一周；教务导入入口已拦坏串，但云恢复入口原样写库未复用校验
  证据：WeekResolver.kt:96-98 第 week-1 项若 parse 失败则落空，顺延到 :99-103 的 `known` 分支：`extra = week - known.size`、`known.last().plusWeeks(extra)` 假设 known 从第 1 周起连续——中间一项坏掉时 known.last() 已是更晚的周一且 size 少计，映射整体后移。JwParser.kt:102-107 注释原话承认此后果（「WeekResolver 对坏串 parse 失败会‘用最后一个已知周一倒推’，其后所有周整体错位——日期串必须先验证合法」）但只在教务导入拦截；CloudSnapshot.kt:263-269 applyRestore 直接 `weekMondays = s.semester.weekMondays` 从网络快照写入 DataStore，无同样校验（旧版本客户端上传的快照可能带坏串）
- **app/src/main/java/com/caeamer/beikeschedule/reminder/BootReceiver.kt:32-36 与 ReminderReceiver.kt:39-43** [逻辑/静态审查/low] 两处接收器用单个 runCatching 包住三个 scheduler 的 reschedule，第一个抛异常（如 DataStore/Room IO 错误）会连带跳过考试与日程的重排。
  证据：BootReceiver.kt:32-36 `runCatching { ClassReminderScheduler.reschedule(...); ExamReminderScheduler.reschedule(...); TodoReminderScheduler.reschedule(...) }`——一次 DataStore IOException（SettingsStore 注释里自认可能发生）会让后两个 reschedule 被整段跳过。代码库自身惯例是逐个隔离：CloudSnapshot.kt:287-289 是三个独立 runCatching，ScheduleViewModel.kt:194、GradesViewModel.kt:332、SettingsViewModel.kt:178 也都是每个调用点单独 runCatching。两处接收器与惯例不一致。
- **app/src/main/java/com/caeamer/beikeschedule/reminder/TodoReminderScheduler.kt:79-82（对照 :119）** [逻辑/静态审查/low] 定向取消（打卡）的 requestCode 以"出现日 today"为基准，而计划/记录中的码以 trigger.toLocalDate()（触发日）为基准；提前量把触发时刻推过午夜的日程，打卡后取消不到那条已到点未投递的闹钟。
  证据：TodoReminderScheduler.kt:119 `requestCode = requestCodeOf(todo, trigger.toLocalDate())`（trigger = 计划时刻 − remindMinutes，可落在出现日前一天）；而 TodoReminderScheduler.kt:79-82 forceCancel 用 `requestCodeOf(it, today)`（出现日）。例：当天 00:10 开始、提前 30 分钟的事项，其闹钟码基于昨天，昨天排的记录里存的是"昨天码"；今天打卡后 forceCancel 算出"今天码"→ 不命中；同时该记录 triggerAtMillis 已 ≤ now，被 alarmsToCancel 的"已到点不动"分支保护（ReminderAlarmScheduler.kt:75）→ 打了卡仍收到提醒。窗口窄（需提前量跨午夜且闹钟恰在 Doze 队列未投递），故仅 low。
- **app/src/main/java/com/caeamer/beikeschedule/reminder/ExamReminderScheduler.kt:160-164** [UI/静态审查/low] examTimeText 在 kssj 非空而 jssj 为空时（真实数据形态，教务时间描述如 "2027-01-15 09:00"）只显示日期，通知丢掉开考时间，与 App 内考试列表的回退口径不一致。
  证据：ExamReminderScheduler.kt:160-164 `when { kssj.isNotBlank() && jssj.isNotBlank() -> ...; exam.ksrq.isNotBlank() -> exam.ksrq; else -> exam.kssjms }`——第二分支拿 ksrq 挡在 kssjms 前面。ExamsParser.kt:24-27 的 TIME_REGEX 中起、止时间两组均为可选（`(?:...)?(?:...)?`），"2027-01-15 09:00"解析出 kssj="09:00"、jssj=""。对照组 GradesScreen.kt:626-630 同形态回退到 `exam.kssjms`（原文，含时间），通知反而比 App 内显示的信息少——"即将考试"提醒恰是用户最需要开考时间的场景。
- **app/src/main/java/com/caeamer/beikeschedule/ui/schedule/CourseEditDialog.kt:141-147** [逻辑/静态审查/low] droppedWeeks 的 remember 键不感知会话周次变化：会话内周次增删后"保存后会被丢弃"警告不刷新
  证据：L141-147 `val droppedWeeks = remember(sessions, unscheduledWeeks, totalWeeks) { ... sessions.forEach { s -> s.weeks.filterTo(this) { it > totalWeeks } } ... }`：计算 lambda 内读取的 s.weeks 是 SnapshotState 元素属性，写它只会让外层重组作用域失效；而 remember 的三个键中 sessions 是同一个 SnapshotStateList 实例、元素引用不变（equals 相等），unscheduledWeeks/totalWeeks 也不变 → remember 返回缓存值、计算不重跑。chips 只渲染 1..totalWeeks（L411），用户只能通过"全选/清空"等（L420-423）替换掉超范围周次，替换后警告文本（L289-297）仍停留为旧的"有 N 个周次超出…保存后会被丢弃"。对照组：L149-153 的 valid 直接读 session 状态所以是实时的，可见此处的 remember 用法与意图不符
- **app/src/main/java/com/caeamer/beikeschedule/ui/schedule/CourseEditDialog.kt:141-147** [逻辑/静态审查/low] 混合课程（有时段行 + 无固定时间行）下，droppedWeeks 警告声称无固定时间行的超范围周次"保存后会被丢弃"，实际保存路径原样保留，提示与行为矛盾
  证据：CourseEditDialog.kt:141-147 把 unscheduledWeeks.filterTo(this) { it > totalWeeks } 计入 droppedWeeks 并在 L289-297 提示"保存后会被丢弃；如需保留请…调大总周数"；但 unscheduledWeeks 在混合场景（scheduledRows 非空，L129-133）从无固定时间行的位图播种，而 CourseRowBuilder.kt:48-52 仅在 onlyUnscheduled 时才改写周次，否则 `weekBitmap = row.weekBitmap`（原样透传）——即混合课程的无固定时间行超范围周次保存后并不会被丢弃，警告是假的且用户在混合界面里也无从修正
- **app/src/main/java/com/caeamer/beikeschedule/ui/schedule/SemesterSettingsDialog.kt:76-80** [UI/静态审查/low] notificationsBlocked/exactAlarmBlocked 用无键 remember 计算，从系统设置页返回后诊断状态不刷新，仍显示过期的"已被系统关闭"
  证据：L76-80 `val notificationsBlocked = remember { notificationsBlocked(context) }` / `val exactAlarmBlocked = remember { Build.VERSION.SDK_INT >= S && !...canScheduleExactAlarms() }` 无任何键。注释声称"每次打开设置页现算"，但仅在对话框进入组合时算一次：用户点 L189"去开启通知"或 L203"去开启精确闹钟"跳系统设置并开启权限后返回（Activity 通常不重建、组合保留），对话框仍显示"通知已被系统关闭，上课提醒不会弹出"（L184-189）或精确闹钟缺失提示，须关掉重开对话框才会刷新
- **app/src/main/java/com/caeamer/beikeschedule/ui/schedule/ScheduleViewModel.kt:110** [逻辑/静态审查/low] uiState 组合里 resolved.coerceIn(1, semester.totalWeeks) 未防 totalWeeks<1，coerceIn 会抛 IllegalArgumentException；应用内写入方均有防护但云恢复路径原样写入快照值
  证据：L110 `selectedWeek = resolved.coerceIn(1, semester.totalWeeks)`——totalWeeks 为 0 或负数时 coerceIn 抛异常，位于 stateIn 的共享协程中，且坏值已持久化在 DataStore，之后每次启动都在同一处崩（对照 WeekResolver.kt:60 就用了 coerceAtLeast(1) 防护）。现状核查：对话框写入 coerceAtLeast ≥1（SemesterSettingsDialog.kt:272）、导入写入 takeIf{it>0}?:20（UnifiedSyncViewModel.kt:435）、快照缺失字段默认 20（CloudSnapshot.kt:158），因此当前无应用内路径可触发；唯一未校验入口是云恢复 CloudSnapshot.kt:263-268 把快照里的 totalWeeks 原样 saveSemester，异常/被篡改的快照可造成持久化崩溃环
- **app/src/main/java/com/caeamer/beikeschedule/ui/schedule/ScheduleViewModel.kt:213-250** [代码/静态审查/low] saveCourse / deleteCourse / observeCourseByName 三个 ViewModel 方法无任何调用方，为死代码
  证据：grep -rn "\.saveCourse(|\.deleteCourse(|\.observeCourseByName(" --include=*.kt app/src/main/java app/src/test 仅命中 ScheduleViewModel.kt:231/249（自身实现转调 repo），全工程无 UI 或测试调用；L213-217 的 saveCourse（含 id==0L 分支判断）、L229-231 observeCourseByName、L248-250 deleteCourse 均不可达（UI 实际走 saveCourses/setCoursesHidden 等）
- **app/src/main/java/com/caeamer/beikeschedule/ui/settings/UpdateInstaller.kt:45-73（配合 ProfileScreen.kt:509-523）** [代码/静态审查/low] 应用内更新下载无进行中去重：force 弹窗保持打开时连点「前往下载」会重复排队下载同一文件名，两个完成广播各自触发 finishAndInstall 并发跑哈希校验/拉起安装；且无论成功或校验失败都不调 dm.remove(id)，成功安装后 APK 也一直留在应用外部目录。
  证据：UpdateInstaller.kt:58-72 `dm.enqueue(request)` 后仅按 id 注册一次性 receiver，无「下载中」状态跟踪；ProfileScreen.kt:510-523 force 弹窗 confirmButton 点击后 `if (!u.force) showUpdateDialog = false`——强更场景弹窗不关，按钮可反复点、每次都 enqueue；finishAndInstall 中哈希不符只 `file.delete()`（92-97 行），成功路径也不调 dm.remove(id)，下载通知与 APK 文件留存。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:337（配合 TodoEntity.kt:28-29、TodoPlanner.kt:25-26、TodoScreen.kt:528-531）** [逻辑/静态审查/low] 「每周」重复的默认 weekdays 位图 "0111110" 在周一索引制下实际点亮的是周二~周六，疑似想给「周一~五」默认值时差了一位。
  证据：TodoEntity.kt:28-29 注释明确「索引 0=周一 … 6=周日」且默认值为 "0111110"；TodoPlanner.kt:25-26 用 `date.dayOfWeek.value - 1`（周一=0）取位；WeekdaySelector（TodoScreen.kt:528-531）index0=一。"0111110" 逐位 = 一(0)/二(1)/三(1)/四(1)/五(1)/六(1)/日(0)，即二~六点亮；若按作者可能以为的周日索引制，本意应是周一~五（"1111100"）。TodoScreen.kt:337 表单沿用同一默认。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoViewModel.kt:70-75** [逻辑/静态审查/low] toggleDone 的读-改-写仍非原子：KDoc（:64-69）声称修复的"快速双击两次都变成打卡"竞态窗口并未真正关闭，只是收窄。
  证据：TodoViewModel.kt:71-74：两次点击各自 `viewModelScope.launch`，各自 `repo.todos.first()`（Room 冷流每次收集重新查库）后再调 `repo.setTodoDone`。A 协程挂起等查询、B 协程随即启动时，A 的写库（再经一次挂起调度）尚未落地，两次 first() 读到同一旧行 → 两次都写"打卡"。无 Mutex/单飞行队列串行化，读-改-写不原子。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoViewModel.kt:40-52** [逻辑/静态审查/low] uiState 的时间基准只在 Room 发射时求值：持续订阅（如前台跨夜不重建收集）时 groups/doneIds/expired 不随日期翻转重算，跨午夜后展示陈旧。
  证据：TodoViewModel.kt:41 在 `map` 内取 `LocalDate.now()`，map 只在 todo 表发射时执行（Room 失效监听按表触发），`.stateIn(..., WhileSubscribed(5000), ...)` 持续订阅期间不重算；对比 TodoScreen.kt:162-165 注释声称跨午夜已处理，但 rememberNow 只让分组标签/淡化跟着变，groups/doneIds/expired 仍是旧 today 的产物：昨日打卡的每日事项今日仍显示已完成（doneIds 按昨日判定，TodoViewModel.kt:49）。
- **app/src/main/java/com/caeamer/beikeschedule/ui/todo/TodoScreen.kt:542（对比 582-586）** [UI/静态审查/low] WeekdaySelector 用普通 clickable，未向 TalkBack 暴露圆点的选中态；同文件 ColorSelector 用了 selectable(selected=...)+Role.RadioButton，两处做法不一致。
  证据：TodoScreen.kt:542：`.clickable(onClickLabel = if (on) "取消周$label" else "选择周$label")` —— 无 selected 语义；对比 TodoScreen.kt:582-586 色板用 `.selectable(selected = i == ..., role = Role.RadioButton)` 能正确朗读选中态。

## UI 实测截图
- build/ui-review/01_home_schedule.png
- build/ui-review/02_home_sample_schedule.png
- build/ui-review/03_course_detail.png
- build/ui-review/04_jiaowu.png
- build/ui-review/05_jw_login.png
- build/ui-review/06_free_classroom.png
- build/ui-review/07_mine.png
- build/ui-review/08_cloud_sync.png
- build/ui-review/09_schedule_menu.png
- build/ui-review/10_term_switcher.png
- build/ui-review/logcat_full.txt

（截图与 logcat 在 `build/` 下，不入库，`gradlew clean` 后即消失；此处仅存目录。）

---

## 8. 修复情况（2026-10-05 修复轮 · v1.4.0 / versionCode 51）

28 条合并发现**全部修复**，11 处死代码删净。分 7 批提交在 `fix/review-r5`，每批独立可编译、
单测全绿（352 → 382 条，新增 30 条回归用例）。

**用户拍板的三个方向**（后续讨论别再翻案）：
1. 课程身份口径 = `CourseMerger.SlotKey` 纳入 `source`（跨来源不再合并，落到网格既有的并排窄列渲染）；
2. 清除成绩缓存 = 全程 suppressDirty（**云端备份不动**）+ 弹窗明示；
3. 修完直接发 v1.4.0 / versionCode 51。

### 8.1 十条 medium

| # | 发现 | 修法 | 落点 |
|---|---|---|---|
| M1 | 同步「重试失败项」丢云端半程 | `SyncPlanner` 拆 `head`/`tail` 两段，`retrySteps` → `retryHead` + `retryTail`（**按重试后的 mode 现算后半段**）；ViewModel 首轮与重试统一走两段式 | `ui/sync/SyncPlan.kt`、`UnifiedSyncViewModel.kt` |
| M2 | 考试解析失败被折叠成空列表 → 清空本地考试并取消提醒 | `parseExams` 返回 `List<ExamEntity>?`：`null`=没拿到数据，空列表=确实没考试；只有非 null 才覆盖与重排 | `import/parser/ExamsParser.kt`、`UnifiedSyncViewModel.stepGrades` |
| M3 | 子资源 SSL 失败把整个同步页判为 Failed | `onReceivedSslError` 与另两个错误回调对齐口径：该回调不带 request，改为与 `onPageStarted` 记下的主框架 URL 比对；未开始加载时按主文档处理（宁可多报不可漏报） | `import/JwWebView.kt` |
| M4 | 合并卡片与行组口径不一致（跨来源同名课隐藏/编辑只作用一半） | `SlotKey` 纳入 `source`（拍板方向 1） | `model/CourseMerger.kt` |
| M5 | 校历周数 < totalWeeks 时那些周整段停排提醒、图钉消失 | `teachingWeekOf` 增 `totalWeeks` 参数，超出校历覆盖的周按最后一个校历周一顺延（与 `weekMonday` 同口径）；**校历内部的假期空隙仍返回 null** | `data/repo/ScheduleRepository.kt`、`model/WeekResolver.kt` |
| M6 | 缺 `ACTION_MY_PACKAGE_REPLACED`：应用内自更新后全部闹钟被系统清空 | Manifest 增该 action + `BootReceiver` 增分支 | `AndroidManifest.xml`、`reminder/BootReceiver.kt` |
| M7 | 清除成绩缓存置脏 → 8 秒后把「成绩全空」整包上传覆盖云端 | `CloudSync.withoutDirtyMarking`（进块先撤在排的去抖上传 + 全程 suppressDirty，DataStore 脏标记不动），云恢复与清缓存共用；弹窗文案明示 | `data/backup/CloudSync.kt`、`ui/settings/SettingsViewModel.kt`、`ui/profile/ProfileScreen.kt` |
| M8 | 考试分组 `toSortedMap` 比较器把「时间待定」与「解析失败」当同一 key，一组考试静默消失 | 抽 `examDayGroups`，比较器改两级（日期 + key 本身） | `ui/grades/GradesScreen.kt` |
| M9 | 「已过期（未完成）」分区打卡是无效交互 | 过滤规则抽到 `TodoPlanner.expiredOnce`（打过卡即离开过期区），行渲染改读 `state.doneIds` | `model/TodoPlanner.kt`、`ui/todo/*` |
| M10 | 编辑表单底部弹层内容不可滚动，小屏/横屏/键盘展开时操作行被裁掉 | `Column` 加 `verticalScroll`（与 `CourseEditDialog` 同款） | `ui/todo/TodoScreen.kt` |

### 8.2 十八条 low（含死代码）

- **云恢复零校验（3 条合并）**：新增 `CloudSnapshotCodec.sanitizeSemester`（坏日期串 → 整表丢弃退回 firstMonday 推算；`totalWeeks` 夹到 1..60；非法开学日期置空）；读侧再兜一层 `coerceAtLeast(1)`，因为坏值可能已持久化，只校验恢复端救不了 `stateIn` 里的崩溃环。
- **坏日期串下标错位**：`ScheduleRepository.parseWeekMondays` 统一「任一串非法即整表作废」，`locateWeek`/`teachingWeekOf`/`weekMonday` 全部改走它——**不再逐条 mapNotNull 丢弃**（那正是错位根因），与 `JwParser` 导入时的整表拒绝同口径。
- **错误文案链路（2 条合并）**：口径统一为「`Exception.message` 必须是可直接展示的用户文案，实现细节进日志」。`SmartClassApi` 签名失败改抛 `SmartClassException`（普通 IOException 会被 `networkMessage` 的 else 分支整体替换）；`CloudApi.parseOrThrow` 不再把接口路径拼进文案，路径改 `Log.w`。
- **解析防御（2 条合并）**：`JwParser` 固定时间行缺 ZC → 整行跳过并经 `rowErrorLogger` 留痕（与缺节次同策略）；`parseNoteWeeks` 区间两端夹到 `1..MAX_TOTAL_WEEKS`。
- **SmartClassParser `optString`**：字符串字段统一走 `str()`（`isNull` → null）。org.json 对显式 JSON null 返回字面量 `"null"`，`isBlank()` 判不住。
- **成绩排除键**：新增 `GradeEntity.identityKey`（kcdm 缺失退回课程名），收敛分组 / 加权排除 / 勾选列表三处统一走它；`GradeTriple.kcdm` 随之改名 `identity`。
- **幽灵同步**：主页面回调的 `view.post` 记入 `AtomicReference`，`onRelease` 与下次导航时 `removeCallbacks`。
- **注释失实**：`BRIDGE_ORIGINS` 改实（仅 https；站内 http 页面能加载但拿不到桥），并清掉 `JwWebView` 里指向已删除页面（导入页/成绩页/云登录页）的描述。
- **两接收器单个 runCatching 包三个 scheduler**：各拆成三个独立 `runCatching`（与 `CloudSnapshot` 既有惯例一致）。按计划**未抽公共函数**——接收器无共享基类，三行重复优于多一层壳。
- **日程 requestCode 基准不一致**：新增 `requestCodeForOccurrence`，取消码与计划码统一以**触发日**为基准。
- **examTimeText 丢开考时间**：第二分支改回退 `kssjms` 原文（对齐 GradesScreen 口径）。
- **CourseEditDialog droppedWeeks 两处失真**：去掉 `remember`（键里的 `sessions` 是同一 `SnapshotStateList` 实例、元素引用不变，永不失效），并只在整门课都无固定时间时计入（混合场景 `CourseRowBuilder` 原样透传，那条警告是假的）。
- **权限诊断不刷新**：改 `mutableStateOf` + `LifecycleEventEffect(ON_RESUME)` 重算。
- **更新下载无去重/无清理**：`UpdateInstaller` 增在跑判定（按 DownloadManager 真实状态，不只看标志位，避免广播丢失后永久卡在"下载中"）+ 入队前清掉上次更新的已完成条目 + 失败终态 `dm.remove`；「前往下载」按钮由 `downloading` StateFlow 驱动禁用。**成功路径刻意不 remove**（它会连文件一起删，而安装器是异步读取的），残留留给下一次更新清理。
- **Todo 默认 weekdays**：`"0111110"` → `"1111100"`，提为 `TodoEntity.DEFAULT_WEEKDAYS`（表单与实体同源）；既有数据不迁移。
- **toggleDone 非原子**：加 `Mutex` 串行化——只"读最新值"关不掉窗口，读与写之间仍有挂起点。
- **uiState 时间基准**：`today` 独立成流与 `repo.todos` combine，由页面已有的 `rememberNow`（每分钟、仅 RESUMED）推进；不引后台时钟，也不新造第二套时钟（时钟上提到 `TodoScreen`，`TodoList` 改为接收 `now`）。
- **WeekdaySelector a11y**：圆点改为单个 `semantics(mergeDescendants = true)` 块承载角色/勾选态/
  动作（`Role.Checkbox` + `selected` + 带标签的 `onClick`），名字挂在子 Text 上被合并上提。
  实测（模拟器 uiautomator）：clickable / selectable / toggleable 三种写法都会把"勾选态"与
  "名字"拆成两个无障碍节点（外层 48dp 有态无名、内层 36dp 有名无态），TalkBack 聚焦读不全；
  同文件 ColorSelector 的单节点形态是靠"交互语义与 semantics 在同一裸 Box 上"得到的，
  而周几圆点外面还套了 `minimumInteractiveComponentSize`，只能显式写语义块。
- **死代码 11 处删净**，并级联清掉因此失去唯一调用方的 6 个成员：`CourseDao.observeByNames/getByIds/count/insert/update`、`GradeDao.count`、`ExamDao.getAll`、`TodoDao.getAll/getById`、`ScheduleViewModel.saveCourse/observeCourseByName/deleteCourse`、`ScheduleRepository.addManualCourse/updateCourse/deleteCourse/observeCourseByName`、`JwWebView` 单桥重载。

### 8.3 顺手清掉的（报告未列）

`GradeRows.bestPerCourse` 上重复的一整段 KDoc、`ScheduleRepository` 里挂在 `addManualCourse`
上却描述 `assignImportColors` 的重复 KDoc、清缓存弹窗里已失效的「下次进入教务 Tab 需重新抓取」
（内嵌抓取在 v1.3.9 已合并到一键同步）、`isExactAlarmBlocked` 里 minSdk 34 下恒真的 SDK_INT 判断。

### 8.4 未修 / 已知残余

- **M7 的残余窗口（有意保留）**：`withoutDirtyMarking` 只保护清缓存这一刻——若用户清完又编辑了
  别的数据，那次上传仍会带上空成绩。彻底关闭需要给"缓存已清"立墓碑（快照构建时保留云端成绩），
  属结构性改动，本轮按拍板只做 suppressDirty + 弹窗明示。
- **`locateWeek` 未随 `teachingWeekOf` 顺延**：校历短于 totalWeeks 时，显示口径仍把校历之后判为
  `afterEnd`（顶栏"学期结束"、默认落位最后一周），而严格口径已按顺延排提醒。两者不一致，但改
  `afterEnd` 会牵动顶栏文案与默认定位，风险大于收益，留待观察。
- **不在本轮范围**：R4 遗留的 D9（二级索引，需 DB 迁移 v6）、D10（历史 schema 1/2.json 无法重建）、
  C6（WebView Activity 级旋转保留）；R4 §5 的 5 个测试盲区（Room 迁移 / CloudSync 编排状态机 /
  闹钟记录拼装 / 快照字段保真 / ScheduleViewModel 会话推进）；`CODE_REVIEW_2026-09-FULL.md` §6 的
  B 组（P2 MutationObserver 重排、P3 双次 `/config.json`、两项 a11y）。
- **未覆盖真实扫码登录**：本轮验证与 10-01 的审查都只到"未覆盖真实扫码登录"为止，
  M1/M2/M3 的同步链路改动需要在真机上跑一次完整扫码。

### 8.5 验证记录（2026-10-05）

- 单测 352 → **382 条全绿**（新增 30 条：SyncPlan 重试两段式 3、ExamsParser null/空分野 2、
  校历顺延与坏串 5、学期清洗 6、日程码基准与考试文案 3、课程身份/分组/排除 4、过期打卡与默认位图 4、
  JwParser 防御 3、SmartClassParser null 1、加权排除改名 1 等）。
- `lintDebug` 通过；本轮改动未引入新告警（顺手消掉一条 ObsoleteSdkInt）。
- 模拟器（Pixel_10_Pro）走查：日程编辑弹层可滚动到底（删除/取消/保存可触达）；「每周」默认点亮
  周一~周五；周几圆点无障碍为单节点（名字+复选框+勾选态）；「清除成绩缓存」弹窗含"云端备份保持
  原样（只清本机缓存，不会上传覆盖）"；全程 logcat 无崩溃。截图存 `build/ui-review-r5/`（不入库）。
- 真机待办：MY_PACKAGE_REPLACED 广播后 `dumpsys alarm` 三类提醒重建；完整扫码跑一遍 M1/M2/M3。
