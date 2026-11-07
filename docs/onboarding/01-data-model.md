# 01 · 数据层与核心领域模型（交接深读）

> 面向接手开发：本文只讲"数据怎么存、怎么变、怎么算"，结论优先，每条带证据。
> 版本：v1.5.3 / versionCode 58（`app/build.gradle.kts:26-27`），Room 2.7.2（`gradle/libs.versions.toml` room）。
> 行号说明：`data/…`、`model/…`、`ui/…`、`reminder/…` 相对 `app/src/main/java/com/caeamer/beikeschedule/`；`schemas/…` 相对 `app/schemas/com.caeamer.beikeschedule.data.local.AppDatabase/`；`test/…` 相对 `app/src/test/java/com/caeamer/beikeschedule/`；`docs/…` 相对仓库根。

## 0. TL;DR（先看这 10 条）

1. 持久化只有两块：Room 5 张表（DB v6，迁移链 1→6 完整）与单个 DataStore 文件 `settings`（38 个 key）。没有服务器数据库、没有 DI、没有仓库层内存缓存（`data/local/AppDatabase.kt:10-14`、`data/pref/SettingsStore.kt:21-24`）。
2. 课程四类来源（教务导入/班级实验/自定义/示例）**同表共存**，靠 `CourseEntity.source` 区分，覆盖式写入各自只清自己的 `source`；云恢复才整表替换（`data/local/CourseEntity.kt:27,38-45`、`data/repo/ScheduleRepository.kt:86-177`、`data/backup/CloudSnapshot.kt:250-272`）。
3. DataStore 里**学期/校历**（`firstMonday`、`totalWeeks`、`weekMondays`、`holidays`、`makeups`）是周次与日期正确性的唯一真相；`AppSession`、`ScorePrivacy` 是纯内存会话态，**故意不落盘**（`data/pref/SettingsStore.kt:29-52`、`data/pref/AppSession.kt:7-19`、`data/pref/ScorePrivacy.kt:7-13`）。
4. 教学周↔日期只有一套实现：`WeekResolver` 是门面（显示/严格两种口径），实现留在 `ScheduleRepository.Companion`；校历优先、校历短于总周数时按最后一个校历周一顺延（`model/WeekResolver.kt:17-152`、`data/repo/ScheduleRepository.kt:241-326`）。
5. 某一天的"上课计划"优先级固定为 **补课 > 假期 > 自然星期**（`model/WeekResolver.kt:132-139`）；补课数据 = 内置 `SchoolAdjustments` ∪ 存量 `SemesterConfig.makeups`，内置优先（`model/SchoolAdjustments.kt:15-27`）。
6. "当前周"在进程重建后仍正确，靠三件事而不是持久化：`selectedWeek` 不落盘、`AppSession.epoch` 每次退后台 +1、Pager 的 `drop(1)`+`loaded` 守卫（`ui/schedule/ScheduleViewModel.kt:85-123,163-179`、`ui/schedule/ScheduleScreen.kt:204-240`、`MainActivity.kt:131-139`）。
7. 写入一律走事务（`db.withTransaction`），写入成功后 `CloudSync.markDirty` 置脏 → 8 秒去抖整包上传；开机/启动补传；恢复先验签再整包覆盖（`data/repo/ScheduleRepository.kt:31-32,86-110`、`data/backup/CloudSync.kt:51-80,177-212`）。
8. 已发现 1 个用户可见功能 bug：**开启「隐藏周末」后，内置补课日（如 2026-10-10 周六补周三）的列会被隐藏**，与 README 承诺相反（见 §5 风险 1）。
9. 主要性能隐患：每次用户数据写入都会无条件落一次 `cloud_dirty` DataStore edit（即使云同步没开、且值为 true 时也照写），并读取 `cloudAccount` 触发一次 AndroidKeyStore 解密；`cloud_dirty` 由 false→true 的那次还会让所有设置 Flow 重发射、多个 `combine` 全量重算（见 §5 风险 2）。
10. 单测 46 个文件 / 407 个 `@Test`，纯逻辑（周次、位图、合并、规划、GPA、解析、闹钟编排）覆盖很好；**Room 迁移、DAO、DataStore 映射、CloudSync 编排、ScheduleRepository 写方法零覆盖**（见 §6）。

## 1. Room 落库层

### 1.1 五张表（`data/local/*`，schema 见 `schemas/6.json`）

| 表 | 主键 | 业务键/唯一性 | 关键字段与语义 | 索引 |
|---|---|---|---|---|
| `course` | `id` 自增 | 无唯一约束；身份靠 `source + name`（UI）/`taskId`（导入） | `taskId`（教务 RWH，手动/新增时段为空串）、`dayOfWeek 0..7`（0=无固定时间）、`startSection/endSection`、`weekBitmap`（周位图）、`colorIndex`、`source 0..3`、`hidden`、`note`（`data/local/CourseEntity.kt:15-47`） | 无（`schemas/6.json:9`） |
| `section_time` | `section` | 小节号唯一 | `startTime/endTime` 文本 `HH:mm`，导入时从教务 `queryKbjg` 覆盖（`data/local/SectionTimeEntity.kt:7-12`） | 主键 |
| `grade` | `id` 自增 | 无唯一约束；逻辑身份 `identityKey=kcdm ?: kcmc` | `xnxq/xnxqmc`、`kcxz`（必修/任选）、`kclb`（类别）、`xf`、`zzcj`（原始分，可含"优/通过"）、`bkcx`（正考/补考/重修）、`pm/zrs/khfs`；派生 `numericScore`（非有限/越界→null）、`isFailed`、`isPassed`（`data/local/GradeEntity.kt:26-58`） | 无（`schemas/6.json:129`） |
| `exam` | `id` 自增 | 无 | `ksrq`/`kssj`/`jssj` 从 `kssjms` 解析，失败留空；`hasDate` 派生（`data/local/ExamEntity.kt:10-27`） | 无 |
| `todo` | `id` 自增 | 无 | `repeatMode 0/1/2`、`weekdays` 7 位位图（0=周一…6=周日）、`date`、`time`、`remindMinutes`、`colorIndex`、`lastDoneDate`（打卡=等于今天即完成，次日复活）（`data/local/TodoEntity.kt:22-57`） | 无 |

- DAO 均为 `Flow` 观察 + `suspend` 写；排序口径：课程按 `dayOfWeek,startSection`，成绩按 `xnxq DESC,id`，考试按 `ksrq,kssj`，日程按 `time,id`（`data/local/CourseDao.kt:12`、`GradeDao.kt:11`、`ExamDao.kt:11`、`TodoDao.kt:12`、`SectionTimeDao.kt:12-16`）。
- 表都很小（课程百行级、成绩几十~百行），**没有任何 `@Index`**；当前规模无性能问题，但若做"按周/按学期检索"或数据量放大再补索引。
- 数据库文件 `beike_schedule.db`，单例双重检查（`data/local/AppDatabase.kt:88-114`）。DB 本身**不加密**；只有云 token 用 AndroidKeyStore AES-GCM 单独加密（`data/pref/TokenCipher.kt:12-22`）。

### 1.2 版本与迁移策略

- `@Database(version = 6, exportSchema = true)`，5 条显式迁移 `1→2`（建 grade）、`2→3`（course 加 hidden）、`3→4`（grade 加 pm/zrs/khfs + 建 exam）、`4→5`（建 todo）、`5→6`（course 加 note）（`data/local/AppDatabase.kt:10-14,24-86`）。
- **有 `fallbackToDestructiveMigration(dropAllTables = true)`**（`data/local/AppDatabase.kt:111`）。作者已在注释里说明其真实语义：它只兜"找不到迁移路径"（findMigrationPath==null），**不兜**迁移 SQL 抛异常/迁移后 schema 校验失败——那两种仍是 `IllegalStateException` 启动即崩（`AppDatabase.kt:99-110`）。不要把它当万能兜底。
- `app/schemas/` 只有 **3/4/5/6.json**（v1/v2 早于导出配置，见 `git log -- app/schemas`）；`6.json` 的 `identityHash` 与当前实体一致（`schemas/6.json:5`）。迁移配置在 `app/build.gradle.kts:80-83`（`room.schemaLocation`）。
- 迁移 `4→5` 的 todo 建表带 SQL `DEFAULT`（如 `weekdays` 默认 `'0111110'`，`AppDatabase.kt:72`），与实体默认 `"1111100"`（`data/local/TodoEntity.kt:56`）不一致。已核对 Room 2.7.2 的校验实现：实体侧列未声明 `defaultValue` 时，DB 侧默认值**不参与比较**（`TableInfo.Column.defaultValue` 为 null 会跳过），所以升级不崩；但该 DEFAULT 是死代码且方向相反，将来若给 `weekdays` 加 `@ColumnInfo(defaultValue=…)` 会立刻暴露。
- **降级行为**：`fallbackToDestructiveMigration` 把 `requireMigration` 置 false，因此"DB 版本 > 代码版本"也被当作不需要迁移 → 走破坏性重建：**Room 五表全部清空重建，不崩，但数据全丢；而 DataStore 不在同一个库里会保留**（学期/成绩缓存/云账号还在，课表/成绩/日程没了）。已核对 Room 2.7.2 的 `MigrationUtil.isMigrationRequired`/`RoomOpenHelper.onDowngrade` 实现。若产品不允许这种半状态，需要自己拦截版本或加提示。

### 1.3 四类课程来源如何共存 / 覆盖式写入规则

`source` 常量：0=教务导入、1=自定义、2=示例、3=班级实验安排（`data/local/CourseEntity.kt:38-45`）。四类**同表共存、同网格渲染**，UI 侧只限制"导入/LAB 只能隐藏不能删除，自定义/示例可删"（`ui/schedule/CourseDetailSheet.kt:88-112`）。

| 写入动作 | 影响范围 | 隐藏态处理 | 事务 | 证据 |
|---|---|---|---|---|
| 教务导入 `commitImport(courses, sectionTimes, clearSample=true)` | 删除并重插全部 `source=0`；覆盖 `section_time`；真实导入时顺带删 `source=2` | 按 `(taskId, 课程名)` 记住旧隐藏行并恢复（导入课数据仍以教务为准，用改名/改周会被下次导入覆盖） | 单事务 | `data/repo/ScheduleRepository.kt:86-103` |
| 班级实验 `applyClassSchedule(courses, hideUnscheduledContaining)` | 整组删除并重插 `source=3`（服务端是唯一真相） | 按稳定 `taskId`（= 服务端场次 id）恢复隐藏；命中的教务"无固定时间课程"行**自动隐藏** | 单事务 | `data/repo/ScheduleRepository.kt:123-141` |
| 自定义课程增删改 `replaceCourses(deleteIds, inserts)` | 只动调用方给出 id 的行（编辑=先删旧行再插展开行） | 沿原行的 source/taskId；新时段行 `source=原来源`、`taskId=""` | 单事务 | `data/repo/ScheduleRepository.kt:106-110`、`model/CourseRowBuilder.kt:33-83` |
| 示例课 `loadSampleData` / `clearSampleData` | 只清/插 `source=2`；节次表为空时才补 | 无（示例可整组重置） | 单事务 | `data/repo/ScheduleRepository.kt:169-180` |
| 云恢复 `CloudSnapshotCodec.applyRestore` | 四个 source 全删后按快照整表插入（含 LAB 与 hidden） | 快照携带 `hidden/note` 原样恢复 | 单事务（5 表） | `data/backup/CloudSnapshot.kt:250-272` |
| 云快照上传 `build` | 只读 5 表 + 快照相关设置 | — | Room 只读事务 | `data/backup/CloudSnapshot.kt:192-244` |

- 导入颜色在插入前统一去重分配：同名课程共享一色、教务原始 XB 在色板内优先、冲突顺延（`data/repo/ScheduleRepository.kt:182-227`，测试 `test/AssignImportColorsTest.kt`）。注意 `paletteSize = 10` 是硬编码，需与 `ui/theme/CourseColors.kt:9-20` 的色板长度保持一致。
- 课程行展开/合并有三层口径，改课程数据前必读：
  - `SessionExpander`：编辑框"周几+大节+该时段周次" ⇄ 存储行（连续大节合并成一行，不连续拆行）（`model/SessionExpander.kt:39-112`）。
  - `CourseRowBuilder`：编辑结果 → 存储行，**每行带自己的周次位图**，无固定时间行原样透传（`model/CourseRowBuilder.kt:33-83`）。
  - `CourseMerger`：渲染/提醒前把同 `(name, source, day, start, end)` 的教务拆行合并成一张卡，周次并集；地点不同的真实行不合并（避免调课换教室指错楼）（`model/CourseMerger.kt:15-79`）。
- 隐藏是**卡片级但按"同名+同源"整组生效**：`groupOf(course) = courses.filter { name==… && source==… }`，隐藏/删除/编辑都传整组 id（`ui/schedule/ScheduleScreen.kt:173-180,420-431`）。副作用：同名同源的不同课（不同教师/地点）会被一起隐藏/删除；手动课程重名被 UI 禁止（`ScheduleScreen.kt:184-190`），导入课不禁止。

## 2. DataStore 配置层（`data/pref/`）

### 2.1 位置与容错

- 单文件 DataStore `settings`，`corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }`：文件损坏时重置为默认配置而不是每次启动崩（`data/pref/SettingsStore.kt:16-24`）。
- `SettingsStore` 可被任意构造（每个 ViewModel/每次调用 `SettingsStore(context)`），但 DataStore 实例由文件级扩展属性保证进程内唯一（`SettingsStore.kt:21-24`）。

### 2.2 全部 38 个 key（`SettingsStore.Keys`，`SettingsStore.kt:71-112`）

| 分组 | key（类型=默认值） | 语义 / 写入时机 |
|---|---|---|
| 学期校历（8） | `semester_xn` / `semester_xq` / `semester_name`（String=""）；`first_monday`（String=""）；`total_weeks`（Int=20）；`week_mondays`（String=""，逗号连接，"下标+1=教学周"）；`semester_holidays`（String=""，逗号连接，工作日级放假日）；`semester_makeups`（String=""，"yyyy-MM-dd:weekday"） | 仅在导入成功、学期设置页、云恢复时写（`SettingsStore.kt:114-139`）；`makeups` 为旧版手动登记遗留，现行口径内置（`SettingsStore.kt:46-51`），设置页已无入口 |
| 上课提醒（3） | `reminder_enabled`（Bool=false）；`reminder_minutes`（Int=15）；`reminder_codes`（String=""，闹钟记录） | 开关/分钟由设置页写；`codes` 由 `ClassReminderScheduler.reschedule` 写（`SettingsStore.kt:141-161`） |
| 考试提醒（1） | `exam_reminder_codes`（String=""） | `ExamReminderScheduler.reschedule` 写（`SettingsStore.kt:163-169`） |
| 日程提醒（1） | `todo_reminder_codes`（String=""） | `TodoReminderScheduler.reschedule` 写（`SettingsStore.kt:171-177`） |
| 主题（1） | `theme_mode`（String=""→SYSTEM） | 设置页/云恢复；非法值回退 SYSTEM（`SettingsStore.kt:191-198`） |
| 成绩缓存（4） | `gpa_cache`（String=""）、`grades_fetched_at`（Long=0）、`xflbyq_json`（String=""）、`bxkqk_json`（String=""） | 同步抓取成功、清缓存、云恢复写；原始 JSON 缓存、展示层懒解析（`SettingsStore.kt:179-210`） |
| 学籍快照（8） | `sp_xm/sp_xh/sp_yxmc/sp_zymc/sp_bjmc/sp_njmc/sp_xjsfzx/sp_xjsfzc`（String=""） | 抓成绩时顺手写 `user/me`；"我的"页离线展示；云恢复写（`SettingsStore.kt:212-238`） |
| 加权偏好（2） | `weight_semester`（String=""）、`weight_excluded`（String=""） | **只被云快照构建/恢复读写，UI 并不使用**（见 §5 风险 3）（`SettingsStore.kt:240-255`、`data/backup/CloudSnapshot.kt:237-238,288`） |
| 课表 UI（2） | `hide_weekend`（Bool=false）、`hide_inactive_courses`（Bool=false） | 设置页即时写；云恢复写（`SettingsStore.kt:257-276`） |
| 教务 Tab / 空教室（2） | `free_room_tab_index`（Int=0，**实际存整个教务 Tab 分段**，键名历史遗留）、`free_room_building`（String=""） | 分段切换、空教室选楼（`SettingsStore.kt:278-300`） |
| 云账号（3） | `cloud_xh`（String=""）、`cloud_name`（String=""）、`cloud_token`（String=""，落盘为 `enc:` + AES-GCM 密文或用旧明文兼容） | 云登录写、退出清；读取时解密失败→按未登录处理（`SettingsStore.kt:302-337`、`TokenCipher.kt:48-61`） |
| 云同步簿记（3） | `cloud_sync_enabled`（Bool=false）、`cloud_dirty`（Bool=false）、`cloud_last_backup_at`（Long=0） | 同步页开关、`CloudSync` 置脏/清脏/记录备份时间（`SettingsStore.kt:339-363`） |

- **哪些 key 属于"云快照内容"**：`saveSemester/setReminder/setThemeMode/saveGradesMeta/saveCreditMeta/saveStudentProfile/saveWeightedFilter/setHideWeekend/setHideInactiveCourses` 都先 `noteCloudDirty()`（`SettingsStore.kt:365-370`）；而闹钟记录、空教室、Tab 下标、云簿记按键不置脏（分别是设备本地状态与上传簿记）。云快照排除项见 `data/backup/CloudSnapshot.kt:31-35`。
- 坑：`SemesterConfig` 的 `weekMondays/holidays/makeups` 都存成逗号串（`SettingsStore.kt:372-376`）。日期本身无逗号，安全；但**不要往这些集合里塞含逗号的字符串**。

### 2.3 辅助对象职责与坑

- `AppSession`（`data/pref/AppSession.kt:20-31`）：进程内自增会话序号，`MainActivity.onStop && !isChangingConfigurations` 时 +1（`MainActivity.kt:131-139`）。用途：课表把"用户选的周"打回未选、重新定位当前周（`ui/schedule/ScheduleViewModel.kt:85-96,163-179`）。坑：**绝不能落盘**——落盘会让"上次看的周"跨进程存活，与"退出即回本周"相反；用序号而非布尔是为了订阅方 diff 幂等。
- `ScorePrivacy`（`data/pref/ScorePrivacy.kt:14-27`）：成绩默认隐藏，点小眼睛显示，退后台复位隐藏。同样是会话级；`MainActivity` 另用 `setRecentsScreenshotEnabled` 防最近任务缩略图泄露（`MainActivity.kt:146-153`）。测试：`test/ScorePrivacyTest.kt`。
- `AlarmCodec` + `ScheduledAlarm`（`data/pref/AlarmCodec.kt:10-22`、`ScheduledAlarm.kt:13-16`）：DataStore 里闹钟记录编解码，格式 `code:triggerMillis,...`。旧版本只存 code，解码后 `triggerAtMillis=null`，上层据此走保守分支"仍在本轮计划里的先留着"，不会因升级丢已到点未投递的提醒。请求码空间互不重叠：上课 `[0,7e6)`、日程 `[7e6,8e6)`、考试 `8e6` 段、每日脉冲 `9e6`（`reminder/ClassReminderScheduler.kt:40-52`、`ExamReminderScheduler.kt:37`、`TodoReminderScheduler.kt:39-40`、`ReminderReceiver.kt:120-132`）。坑：记录只是"已排线索"，`ReminderAlarmScheduler.apply` 里"取消+设置+写回"必须 `NonCancellable` 且只取消未来闹钟（`reminder/ReminderAlarmScheduler.kt:16-30,59-126`）。
- `TokenCipher`（`data/pref/TokenCipher.kt:23-78`）：Keystore AES-256-GCM 包裹云 token；`encrypt` 失败**返回明文**（功能优先），`decrypt` 失败返回空串（视为未登录）。坑：`cloudAccount` 每次发射都会解密，属于 KeyStore 调用，见 §5 风险 2。

## 3. 周次 / 日期 / 假期 / 补课不变式

### 3.1 数据口径（背下来）

- **周位图**：`weekBitmap[i]` 对应第 i 教学周，索引 0 恒为 `'0'` 占位；长度不统一——教务 `ZC` 实测 34 字符（`docs/JWXT_API.md:42`）、LAB 固定 32（`data/remote/ClassSchedule.kt:47`）、备注周数 34（`import/parser/JwParser.kt:199-214`）、手动课程 `totalWeeks+1`（`model/WeekUtils.kt:11-23`）。所有消费方都按"下标=周次、越界安全"处理（`data/local/CourseEntity.kt:32-33`、`model/WeekUtils.kt:26-27`），**不要假设固定长度**。
- **教学周↔日期**：以 `weekMondays`（下标+1=教学周）为准；校历未导入时回退 `firstMonday + (week-1)*7`，且 `firstMonday` 会归一化到周一（`model/WeekResolver.kt:99-110`）。校历允许"某周不占序号"（如国庆整周），因此**不能**用 `(today-firstMonday)/7` 硬推（`ScheduleRepository.kt:289-303`）。
- **放假**：校历天级标记，只存工作日级休息日（`SemesterConfig.holidays`，`SettingsStore.kt:40-45`）；周末天然无课不占存储。放假列整列清空 + 「休」水印 + 不排提醒 + 无"下一节课"图钉。
- **补课**：教务查不到，口径内置在 `SchoolAdjustments`（键 `xn-xq`，如 2026-2027-1 的 10/10 补周三；`model/SchoolAdjustments.kt:15-27`），与存量 `SemesterConfig.makeups` 合并、内置优先（`WeekResolver.kt:132-133`）。

### 3.2 关键函数速查

| 函数 | 职责 | 位置 |
|---|---|---|
| `WeekResolver.teachingWeekOf` | **严格口径**：开学前/校历内假期跳周/超出总周数→null；校历短于总周数时按最后校历周一顺延 | `model/WeekResolver.kt:31-36` → `data/repo/ScheduleRepository.kt:314-326` |
| `WeekResolver.locateWeek` | **显示口径**：未开学=`week=1,beforeStart`、假期=`week=下个教学周,isHoliday`、学期后=`week=null,afterEnd` | `WeekResolver.kt:65-84` → `ScheduleRepository.kt:289-303` |
| `WeekResolver.defaultWeek` | 无用户选择时的落位：教学周内=当前周、假期=下个教学周、已放假=最后一周、其余=1 | `WeekResolver.kt:59-63` |
| `WeekResolver.weekMonday` | 周→周一映射的唯一实现（校历优先，超界顺延） | `WeekResolver.kt:99-110` |
| `WeekResolver.daySchedule` | 某日上课计划：周次+按周几上课+是否假期/补课 | `WeekResolver.kt:122-139` |
| `WeekResolver.parseHolidays / parseMakeups` | 集合口径，坏项逐条丢弃（不整表作废） | `WeekResolver.kt:142-152` |
| `ScheduleRepository.parseWeekMondays` | 校历解析：**任一坏串整表作废**（否则下标错位一周） | `ScheduleRepository.kt:276-283` |
| `ScheduleRepository.currentWeek` | 无校历时的兜底推算；开学前返回 null（Int 除法向零截断曾被误判成第 1 周） | `ScheduleRepository.kt:241-252` |
| `WeekUtils` | 位图构造、周列表、单双周标签、人类可读描述 | `model/WeekUtils.kt:4-58` |
| `SectionMap` | 6 大节（1-2…11-12）、13 节归第六大节 | `model/SectionMap.kt:4-29` |

### 3.3 一天的渲染/提醒优先级

- 优先级链：`daySchedule` 先查补课（命中→`holiday=false, makeup=true`，**补课优先于假期标记**），再查放假，最后自然星期（`WeekResolver.kt:132-139`）。测试钉住：`test/WeekResolverDayScheduleTest.kt`。
- 课表网格：先按 `weekMonday(week)+day-1` 得到每日 `DaySchedule`，`WeekLayout.layoutDay(coursesDayOfWeek=生效星期, holiday=…)` 决定清列/按补课星期取课/冲突簇并排/非本周淡化（`ui/schedule/ScheduleScreen.kt:112-122,637-643`、`model/WeekLayout.kt:24-59`）。放假日整列清空并画「休」，补课日列有「补周X」角标（`ScheduleScreen.kt:568-580,689-697`）。
- 上课提醒：未来 8 天逐日 `daySchedule`，`week=null`/`holiday` 跳过，补课日按生效星期匹配课程（`reminder/ClassReminderScheduler.kt:138-170`）；课程集合先排除无固定时间/已隐藏并合并拆行（`model/ReminderCourses.kt:14-15`）。
- "下一节课"图钉：只看今天、严格教学周、非放假日、补课按生效星期、已开始的课跳过（`model/NextClass.kt:34-55`）；仅在 `page+1 == todayPlan.week` 时标记（`ScheduleScreen.kt:191-203,389`）。

### 3.4 "当前周"在进程重建后为什么仍然正确

链路（缺一不可）：

1. `selectedWeek` 是 `MutableStateFlow<Int?>`，**不落盘**，null=未选（`ui/schedule/ScheduleViewModel.kt:74-85`），因此进程重建后必然重新解析。
2. `uiState = combine(courses, sectionTimes, semester, selectedWeek, sessionEpoch)`；`week ?: WeekResolver.defaultWeek(locateWeek(semester), totalWeeks)`（`ScheduleViewModel.kt:98-123`）。冷启动 selectedWeek 为 null、`locateWeek` 用今天求值 → 落到当前周（或假期后第一周/最后一周）。
3. 前台内"退后台再回"：`MainActivity.onStop` 让 `AppSession.epoch+1`（`MainActivity.kt:131-139`）→ VM 的 collector 把 `selectedWeek=null` 并推进 `sessionEpoch`。后者是必须的：`selectedWeek` 等值写入不发射，只写 null 不会让 `combine` 重跑，跨天重进会停在旧周（`ScheduleViewModel.kt:87-96,163-179`）。
4. UI 侧防"程序性回写打断重定位"：Pager 初值用 `selectedWeek`、`snapshotFlow{currentPage}.drop(1)` 丢弃首帧、且 `state.loaded` 为真才回写用户选周（`ScheduleScreen.kt:204-240`）。
5. 日期行/网格一律用 `WeekResolver.weekMonday` 而不是从今天倒推，所以"第 N 周对哪 7 天"与进程存活无关（`ScheduleScreen.kt:509-520`、`WeekResolver.kt:99-110`）。

### 3.5 边界规则汇总

- 开学前：严格口径 `week=null`（不排提醒、无图钉）；显示口径 `week=1,beforeStart=true`（顶栏显示"未开学"）（`WeekResolver.kt:65-84`、`ScheduleScreen.kt:495-503`）。
- 校历内部跳周（假期整周）：严格口径 null、显示口径 `isHoliday + nextWeekMonday`；假期结束自动回到下一教学周（`ScheduleRepository.kt:297-300`）。
- 学期结束：显示 `week=null, afterEnd=true`，默认落位到最后一周；严格口径 null（`WeekResolver.kt:59-63`、`ScheduleRepository.kt:302`）。
- 校历短于 `totalWeeks`：`teachingWeekOf`/`weekMonday` 都从最后校历周一顺延，超过 totalWeeks 才 null（`ScheduleRepository.kt:305-326`、`WeekResolver.kt:99-110`）。UI 的总周数下拉不允许低于校历周数（`ui/schedule/SemesterSettingsPage.kt:108-126`）。
- 坏数据：校历日期串非法→整表作废回退 firstMonday（错位风险不可接受）；假期/补课/快照里的集合项→逐条丢弃；快照还会清洗 `totalWeeks∈[1,60]`（`data/backup/CloudSnapshot.kt:324-347`、`import/parser/JwParser.kt:99-139`）。

## 4. 实际数据流（UI → ViewModel → Repository → Room/DataStore）

### 4.1 装配方式（无 DI）

- 单例：`AppDatabase.get()`（双重检查）、DataStore（文件级扩展属性）——见 §1.2、§2.1。
- 每个 ViewModel 各自 `ScheduleRepository(app)`（`ui/schedule/ScheduleViewModel.kt:72`、`ui/grades/GradesViewModel.kt:223`、`ui/todo/TodoViewModel.kt:38`、`ui/settings/SettingsViewModel.kt:176`）；`FreeRoomViewModel` 另外直接 `SettingsStore`+`FreeRoomRepository`（`ui/freeroom/FreeRoomViewModel.kt:111-112`）。Repository 无缓存，仅转发 DAO Flow + 提供写方法（`data/repo/ScheduleRepository.kt:20-38`）。
- 结果：同一份 DB 被多个 VM 的 Flow 各自观察；设置 Flow 也被各自收集（DataStore 单实例）。

### 4.2 读链路与线程

- UI：`collectAsStateWithLifecycle()`（生命周期感知）；VM：`stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 初值)`（`ScheduleViewModel.kt:123-145`、`GradesViewModel.kt:315-321`、`TodoViewModel.kt:50-57`）。
- Room：`Flow` 查询由 Room 失效跟踪驱动，查询/写入跑 Room 自己的执行器；DAO 全 `suspend`/`Flow`，未开 `allowMainThreadQueries`，不会主线程查库。
- DataStore：`data`/`edit` 内部在自己的 IO 线程；VM 侧不额外 `withContext`。
- 计算层：`GradesUiState` 的派生值用 `by lazy(NONE)` 在首读时算并缓存（`ui/grades/GradesViewModel.kt:76-146`），避免组合期重复计算；`creditParsed` 单独 `distinctUntilChanged`，避免 60KB JSON 因任意 DataStore 写入重解析（`GradesViewModel.kt:265-277`）。
- 空教室：`SmartClassApi` 用阻塞 `HttpURLConnection` + `withContext(Dispatchers.IO)`（`data/remote/SmartClassApi.kt:175-191`），VM 不用 Job 取消而用请求序号丢弃过期响应（阻塞读不响应取消），取消异常在 Repository 内显式放行（`ui/freeroom/FreeRoomViewModel.kt:117-123`、`data/repo/FreeRoomRepository.kt:37-54,116-135`）。

### 4.3 写链路、事务与副作用

- 统一模式：UI 事件 → `viewModelScope.launch`（Main.immediate）→ `repo.xxx`（suspend）→ Room `withTransaction` / DAO 写 → `.also { CloudSync.markDirty(context) }`。没有乐观更新，界面等 Flow 回推。
- 事务点：`commitImport`、`replaceCourses`、`applyClassSchedule`、`loadSampleData`、`replaceGrades`、`replaceExams`、`replaceSectionTimes`、`replaceAllTodos`、云恢复五表（`data/repo/ScheduleRepository.kt:40-110,123-180`、`data/backup/CloudSnapshot.kt:255-272`）。
- 写后副作用（三处，别遗漏）：
  1. **云脏标记**：`CloudSync.markDirty` → 写 `cloud_dirty=true`；已登录且开同步时排 8 秒去抖上传（`data/backup/CloudSync.kt:51-60`）。
  2. **提醒重排**：数据层自己不排；上课提醒靠 `ScheduleViewModel.init` 收集 `(courses, sectionTimes, semester, enabled, minutes)` 并 `distinctUntilChanged()` 后重排（`ScheduleViewModel.kt:180-202`，`ReminderKey` 定义 `:205-212`）；日程提醒靠 `GradesViewModel.init` 收集 `repo.todos`（`GradesViewModel.kt:323-336`）；考试提醒由同步成功/清缓存/云恢复/开机/每日脉冲触发（`ui/sync/UnifiedSyncViewModel.kt:483`、`ui/settings/SettingsViewModel.kt:187`、`data/backup/CloudSnapshot.kt:292-295`、`reminder/BootReceiver.kt:39-41`、`reminder/ReminderReceiver.kt:41-43`）。⚠️ 日程提醒的重排目前挂在 GradesViewModel 上（数据流耦合点），迁移页面时不能删。
  3. 失败处理：写操作异常沿 `viewModelScope` 传播会崩进程，调用点普遍 `runCatching`（如重排）或转为 UI 错误态（同步页/日程页）。
- 启动补传：`MainActivity.onCreate` 里 `CloudSync.uploadIfDirty`，补上次进程死亡漏掉的上传（`MainActivity.kt:154-157`）。

### 4.4 云同步 / 云恢复链路

- 上传：脏标记存在 → `backupNow` 有脏才传；构建快照（Room 只读事务 + 逐个 DataStore.first）→ 编码 → `PUT /backup` → **上传成功后复查脏标记**，仍脏说明构建期间有新写入，重建再传（最多 3 轮），轮次耗尽保留脏标记绝不标"已同步"；`uploadMutex` 全局互斥，簿记协程固定单线程（`data/backup/CloudSync.kt:39-48,122-165`）。
- 恢复：`restore` 先 Ed25519 验签（无签名/验签失败直接拒绝）→ `decode` 规模校验（课程≤5000/日程≤20000/成绩≤20000/考试≤2000/节次≤100 且 section∈1..30，`CloudSnapshot.kt:58-78`）→ 写回滚点 `filesDir/cloud-restore-rollback.json` → `withoutDirtyMarking { applyRestore + clearDirty }` → 按新数据重排三类提醒（`CloudSync.kt:167-212`、`CloudSnapshot.kt:250-296`）。
- 写本机但不触发上传的场景走 `withoutDirtyMarking`：云恢复、清除成绩缓存（后者的置脏曾会把"成绩全空"整包传上去覆盖云端，已被显式修掉：`ui/settings/SettingsViewModel.kt:168-191`）。

### 4.5 缓存与失效

- 有缓存：GPA 概览 JSON + 抓取时间、学分类别要求/毕业进度原始 JSON、学籍快照（均在 DataStore，展示层懒解析）；节次时间（Room 表）；空教室 key `cycleTypeId`（`FreeRoomRepository.kt:65-70`，`@Volatile`，进程内）；云同步去抖 Job（进程内）。
- 不缓存：课程/成绩/考试/日程本体（Room 即真相）；空教室结果（实时数据，每次查询，`FreeRoomRepository.kt:99-108` 注释）。
- 失效：全部靠 Room/DataStore 的 Flow 自动失效，没有任何手动失效代码；`clearGradesCache` 是唯一的"手工清缓存"入口（清 `grade`/`exam` 两表 + 4 个 DataStore key）。

## 5. 风险 / 优化点（按影响排序，附证据与建议）

1. **[功能 bug·中高] 开启「隐藏周末」后内置补课列被吞掉。** 计算"本周是否有周末补课"时只解析了存量 `semester.makeups`，没合并内置 `SchoolAdjustments`：`ui/schedule/ScheduleScreen.kt:160-171`（`:167` 只 `parseMakeups(state.semester.makeups)`）vs 真正的生效口径 `model/WeekResolver.kt:132-139`、`model/SchoolAdjustments.kt:17-22`。新装用户 `makeups` 恒为空（导入时 `UnifiedSyncViewModel.kt:441` 只保留同学期旧值），于是 2026-10-10（周六补周三）在开启隐藏周末的用户界面上**整列不存在**，与 README 承诺"隐藏周末时周末补课列仍显示"（`README.md:10`）相反；提醒侧不受影响（走 `daySchedule`）会造成"不显示但会提醒"的观感分裂。建议：`visibleDays` 用 `WeekResolver.daySchedule` 或把内置表并入判断；补一个纯函数 + 单测。
2. **[性能·中] 每次用户数据写入都放大 DataStore 写 + KeyStore 解密。** 每个 repo 写点都 `markCloudDirty`（`data/repo/ScheduleRepository.kt:44,50,103,110,141,145,151,155,159,163,167,177,180`）→ `CloudSync.markDirty` 无条件 `settings.markCloudDirty()`（即使云同步关闭/已脏也照写，`data/backup/CloudSync.kt:51-60`、`SettingsStore.kt:346-352`），并 `cloudAccount.first()` 触发一次 `TokenCipher.decrypt`（`SettingsStore.kt:313-320`、`TokenCipher.kt:48-61`，AndroidKeyStore 调用）。`cloud_dirty` 由 false→true 的那次（典型：一次云备份清脏后的下一次编辑）还会让所有设置 Flow 重发射，`ScheduleViewModel.uiState` 的 `combine` 重算 `locateWeek/daySchedule`（`ScheduleViewModel.kt:98-123`）、`GradesViewModel` 的 combine 链重算（`GradesViewModel.kt:279-321`；`creditParsed` 已单独去重）。当前规模下表现为"每次藏课/打卡都多一次落盘与 Keystore 操作"的隐性卡顿/耗电。建议：脏标记先读后写（已脏则不写），云同步关闭时跳过置脏；token 解密结果做进程内缓存；给各设置 Flow 加 `distinctUntilChanged`（或拆一个低频"内容版本号"流驱动上传）。
3. **[功能缺口/伪持久化·中低] 加权成绩的"排除课程/学期筛选"不落盘。** UI 侧只在 `MutableStateFlow` 里（`ui/grades/GradesViewModel.kt:226-228,366-370`），进程重建即丢；DataStore 的 `weight_semester/weight_excluded` 却只被云快照读写（`SettingsStore.kt:240-255`、`CloudSnapshot.kt:237-238,288`），等于一段"只走云、不走本机"的死键。建议：要么接上 DataStore 持久化（顺手让云快照字段有意义），要么删键并在 README 说明会话级。
4. **[数据正确性·中低] LAB 自动隐藏的教务"无固定时间课程"不可逆。** `applyClassSchedule` 只隐藏、从不恢复（`data/repo/ScheduleRepository.kt:133-140`）；而下次导入按 `(taskId, name)` 保留 hidden（`ScheduleRepository.kt:91-98`）→ 一旦本班实验安排调整/移除，对应无固定时间课程会永久隐藏，且与用户手动隐藏无法区分（只能去设置页逐条恢复）。建议：给"自动隐藏"单独记来源（如 `hiddenBy=auto`/单独字段），在 `hideUnscheduledContaining` 不再命中时自动恢复。
5. **[迁移/可测性·中低] 迁移链测试与 schema 缺口。** `schemas/` 缺 1/2.json（v1→v2→v3 两个迁移无法用 `MigrationTestHelper` 验证）；全仓无任何 migration/DAO 测试（`app/src/androidTest` 仅 `ExampleInstrumentedTest.kt`）；降级会静默破坏性重建、丢 Room 五表而保留 DataStore（见 §1.2，半状态很难向用户解释）；`4→5` 的 SQL DEFAULT 与实体默认相反（死代码+方向错，`AppDatabase.kt:72` vs `TodoEntity.kt:56`）；`AppDatabase.kt:107` 注释"四条迁移"已过时（现为 5 条）。建议：补 1/2 的 schema（可从历史 tag 生成）与 Robolectric/instrumented 迁移测试；降级要么显式拦截并提示，要么把"丢五表"写进发版说明。
6. **[口径漂移·低] 周位图长度四种写法**：教务 34（`docs/JWXT_API.md:42`）、LAB 32（`data/remote/ClassSchedule.kt:47`）、手动 `totalWeeks+1`（`model/WeekUtils.kt:11-23`）、备注 34（`JwParser.kt:212`）；而 `CourseEntity.weekBitmap` 注释写"32 位"（`data/local/CourseEntity.kt:25`）。消费方都做了越界保护所以当前无 bug，但编辑框会把超过 totalWeeks 的高位当成"保存后丢弃"警告（`ui/schedule/CourseEditDialog.kt:311-336`）。建议：抽一个 `WeekBitmap` 工具/常量统一长度语义（或明确"长度不承诺、按位读"）。
7. **[状态新鲜度·低] 前台跨午夜时顶栏周次/状态不更新。** `uiState` 的 combine 里 `LocalDate.now()` 只在上游 Flow 发射时求值（`ScheduleViewModel.kt:105`），`todayStatusLine` 用 `state.currentWeek`（`ScheduleScreen.kt:495-503`）；日程页已用独立 `today` 流 + ON_RESUME 修复同类问题（`ui/todo/TodoViewModel.kt:40-63`），课表页没有。网格的"下一节课"因 `rememberNow` 每分钟 tick 不受影响（`ui/common/Now.kt:29-47`、`ScheduleScreen.kt:191-203`）。建议：把 `today` 做成流（或 ON_RESUME 推一次）并入 combine。
8. **[身份口径·低] 隐藏/删除按 `name+source` 整组，同名同源不同课会连坐。** `ScheduleScreen.kt:173-180` 的 `groupOf` 与 `CourseMerger` 的 `SlotKey(name,source,day,start,end)`（`model/CourseMerger.kt:20-24`）粒度不同：合并是"时段级"，隐藏/编辑是"课程级"。手动课程重名被 UI 拦住（`ScheduleScreen.kt:184-190`），但导入课同名不同教师/地点时无法只操作其中一张卡。建议：若产品允许，把身份键下沉到实体（如稳定 `courseKey`）。
9. **[文档/死代码·低] 若干过期注释与轻量死代码**：`CourseEntity.kt:28` 说手动/示例课程不可用 hidden，实际四类都能隐藏（`CourseDetailSheet.kt:88-97`、`data/repo/ScheduleRepository.kt:161-167`）；`ScheduleRepository.kt:229-240` 同一段 KDoc 重复两遍；`WeekResolver.kt:7-16` 自称"唯一实现"但实现仍在 `ScheduleRepository.Companion`；`SettingsStore.kt:288-296` 的 Tab 语义注释与 `GradesViewModel.kt:40-49` 不一致；`SemesterConfig.makeups`、`weight_*` 两组 key 在实际 UI 中无人写入；`setCourseHidden` 仅剩一处兜底调用（`SemesterSettingsPage.kt:164`）。
10. **[微观性能·低] 其他可优化点**：`WeekResolver.daySchedule` 每次调用都重建假期/补课集合（`WeekResolver.kt:132-139`，课表每页 7 列 ×2 次 + 提醒 8 天循环）；`ScheduleViewModel.loadSampleData` 在主线程读 assets + `JwParser` 解析（`ScheduleViewModel.kt:244-261`）；`CloudSnapshotCodec.build` 在 Room 只读事务内逐个 `DataStore.first()`（`CloudSnapshot.kt:196-243`），拉长事务窗口；`assignImportColors` 硬编码 `paletteSize=10`（`ScheduleRepository.kt:190-191`）。
11. **[安全/数据·低] 云恢复期间 `suppressDirty` 是全局 `@Volatile` 开关**（`CloudSync.kt:47-48,103-111`）：恢复/清缓存窗口内的其它写入不会置脏，可能漏一次云备份；当前窗口短且恢复页全屏，风险低。

> 已修复的旧坑（R5 审查后修掉，别踩回去）：考试解析失败不再折叠成空列表（`UnifiedSyncViewModel.kt:474-483`）；清成绩缓存全程 `withoutDirtyMarking`（`SettingsViewModel.kt:168-191`）；云恢复清洗 semester（`CloudSnapshot.kt:324-347`）；`teachingWeekOf` 超出校历顺延（`ScheduleRepository.kt:305-326`）；`CourseMerger` 合并键纳入 source（`CourseMerger.kt:20-24`）；`BootReceiver` 监听 `MY_PACKAGE_REPLACED` 且逐个 `runCatching`（`reminder/BootReceiver.kt:18-45`）；日程 `today` 独立流 + 打卡 Mutex（`ui/todo/TodoViewModel.kt:40-90`）。

## 6. 单测覆盖地图与缺口

- 现状：`app/src/test/` 46 个文件、407 个 `@Test`；纯 JVM（`testImplementation` 只有 junit + 真实 org.json，见 `app/build.gradle.kts:108-111`）；fixture 为真实接口样本（`docs/samples/`）。跑法 `./gradlew testDebugUnitTest`（`README.md:68`）。

| 模块 | 覆盖情况 | 代表测试 |
|---|---|---|
| 周次/日期/校历（核心） | **强**：约 31 用例 + 11 用例，含开学前截断、假期跳周、校历短于总周数顺延、坏串整表作废、defaultWeek 四分支 | `test/CurrentWeekTest.kt`、`test/WeekResolverDayScheduleTest.kt` |
| 位图/布局/合并/展开 | **强**：`WeekUtils`(6)、`WeekLayout`(8)、`CourseMerger`(10)、`SessionExpander`(10)、`CourseRowBuilder`(6)、`NextClass`(12)、`ReminderCourses`(6)、`RoomNameOrder`(12)、`SectionMap`(2) | 同名测试类 |
| 成绩计算 | **强**：`GradeRows`(13)、`GpaCalculator`(7)、`CreditAggregator`(10)、`WeightedScoreCalculator`(8)、`GradeEntity` 语义通过解析/聚合用例间接覆盖 | `test/GradeRowsTest.kt` 等 |
| 日程规划 | **强**：`TodoPlanner`(21，含默认周一位图、过期区、打卡不排提醒) | `test/TodoPlannerTest.kt` |
| 提醒编排 | **强**：`ReminderSchedulingTest`(24：已到点不取消、Doze、补课/放假、requestCode 分段、AlarmCodec 往返/旧格式) | `test/ReminderSchedulingTest.kt` |
| 解析器 | **强**：`JwParser`(23)、`GradesParser`(13)、`ExamsParser`(5)、`CreditProgressParser`(4)、`SmartClassParser`(15)、`ClassScheduleMapper`(5) | `test/` 与 `import/parser` 相关 |
| 云/同步纯逻辑 | 中：`SyncPlan`(14)、`CloudSnapshot` 编解码(4)、`SemesterSanitize`(6)、签名/验签(4+)、`CloudSnapshotCodec.sanitizeSemester` | `test/ui/sync/SyncPlanTest.kt`、`test/data/backup/*` |
| 会话态 | 有：`AppSession`(2)、`ScorePrivacy`(4) | `test/AppSessionTest.kt` |
| 空教室 | 有：Repository(9)、UiState(14) | `test/FreeRoomRepositoryTest.kt`、`test/FreeRoomUiStateTest.kt` |

**零覆盖（接手第一优先级补）**：

1. **Room**：无 DAO/迁移测试（没有 Robolectric 依赖，`app/build.gradle.kts:108-111`）；`AppDatabase` 迁移链、`fallback` 行为、降级行为都没回归网。
2. **DataStore**：`SettingsStore` 的 38 个 key 映射/默认值/逗号串编解码/脏标记语义无测试；损坏回退策略无测试。
3. **CloudSync 编排**：去抖、`uploadMutex`、撕裂重建循环（最多 3 轮）、`withoutDirtyMarking`、恢复回滚点、验签拒绝路径——全部无测试（只有 `CloudSnapshot` DTO 与 sanitize 有）。
4. **ScheduleRepository 写方法**：`commitImport`（隐藏态保留）、`applyClassSchedule`（整组替换 + 自动隐藏）、`replaceCourses`、`loadSampleData` 均无测试；只有 `assignImportColors`/`currentWeek`/`locateWeek`/`teachingWeekOf` 这些纯函数被覆盖。
5. **ViewModel 行为**：`ScheduleViewModel` 的 `ReminderKey` 去重（防自激）、AppSession 重定位链路；`GradesViewModel` 的筛选/派生状态；`TodoViewModel.toggleDone` 串行化——均无测试（逻辑被抽到纯函数的除外）。
6. **TokenCipher**：Keystore 加解密/历史明文兼容/失败回退无测试（可考虑用 Robolectric 或抽 `Cipher` 接口注入）。

> 交接建议顺序：先读 `model/`（纯逻辑，齐且可测）→ `data/repo/ScheduleRepository.kt`（写规则集中）→ `data/backup/CloudSnapshot.kt`（整包语义）→ `data/pref/SettingsStore.kt`（口径来源）→ `ui/schedule/ScheduleViewModel.kt`+`ScheduleScreen.kt`（周次链路 UI 侧）→ 最后看 `reminder/*`（闹钟与数据的耦合）。
