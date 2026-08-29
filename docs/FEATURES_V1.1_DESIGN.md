# V1.1 功能设计：考试安排 · 单科排名 · 学分进度 · 挂科状态 · 外链（待审核）

> 状态：**待用户审核**。接口字段已全部实测实锤（2026-08-29 真实会话抓取，样例入库 `docs/samples/`）；
> 仅"考试真实数据"需等排考后校准（字段名已从列定义 JS 实锤，见 §5.1）。
> 分支：`feat/exam-query`（已建，基于 v1.0.17 的 main）。

## 0. 已拍板的决策（2026-08-29 用户确认）

| 决策点 | 结论 |
|---|---|
| 学分进度的"要求学分"分母 | 教务接口实锤可提供（`queryXflbyq.yqwcxf`），做进度条 |
| 考试提醒 | 做。考前 1 天 20:00 + 开考前 1 小时各提醒一次 |
| 外链接入范围 | 评教系统、大创/SRTP、课程平台（北科学堂/雨课堂）、实践平台，全部做进"我的"页 |
| 接口样例 | ✅ 已由本人会话直接抓取入库（xflbyq/bxkqk/xsxkqk/exams-empty）；考试真实数据排考后补 |

## 1. 目标与范围

本版本做 5 件事，全部收口在现有三个 Tab 内，**不新增底部 Tab**：

1. **考试安排**：教务 Tab 内新增「考试」分段，展示当前学期个人考试（时间/地点/座位号/类型），含考前系统提醒。
2. **单科排名展示**：成绩列表每行显示"排名 pm/zrs"，点击成绩行弹出详情弹层（排名/考核方式/正考补考/学分/开课学院）。
3. **学分修读进度**：成绩页 GPA 卡下方折叠卡片——毕业总进度（要求/已修/未完成）+ 按学分类别的"要求 vs 已完成"进度条。
4. **挂科/学业状态**：纯本地计算，成绩页按学期标红"N 门未通过"，不调学业警示接口。
5. **外部系统链接**："我的"页通用区新增外链组（浏览器跳转）。

**明确不做**（避免范围蔓延）：选课系统一切写操作（退课/购物车）；培养方案 18 接口；欠费/改密码/改联系方式；辅修成绩独立页；AI 助手。

## 2. 信息架构（放哪里）

```
底部 Tab（不变）：课表 | 教务 | 我的

教务 Tab
├── 顶部分段切换：[ 成绩 | 考试 ]        ← 新增 SegmentedButton，置顶栏下方
├── 成绩段（现状 + 增强）
│   ├── GPA/加权卡片（现状）
│   ├── 学分修读进度折叠卡               ← 新增（ScoreCard 下方）
│   ├── 学期组头右侧："N 门未通过"红字   ← 新增（仅有挂科时）
│   └── 成绩行（+排名副标题）→ 点击弹详情 ← 增强
└── 考试段（新页面）
    ├── 顶栏沿用紧凑矮栏 + 刷新按钮（复用成绩抓取会话）
    └── 按日期分组的考试列表（倒计时徽章、座位号）

我的 Tab
└── 通用区新增「外部系统」组：评教 | 大创/SRTP | 课程平台 | 实践平台
```

## 3. 事实依据（全部 2026-08-29 真实会话实测，样例入库 docs/samples/）

| 接口 | 方式 | 样例 | 用途 |
|---|---|---|---|
| `cjgl/grcjcx/grcjcx` | POST JSON | `grcjcx-all.json` | 成绩 + 单科排名（`pm`/`zrs`/`khfs` 字段实锤） |
| `cjgl/cjzhtjcx/cjcx/getXss` | POST form | （响应含大量 null 字段，未入库） | 取 `xh`/`nj`/`pylx`/`xjid`/`fah`（培养方案标识） |
| `cjgl/cjzhtjcx/cjcx/queryBxkqk` | **POST JSON** | `bxkqk.json` | 毕业总进度：要求学分/已修/未完成/要求门数 |
| `cjgl/cjzhtjcx/cjcx/queryXflbyq` | **POST JSON** | `xflbyq.json` | **学分类别要求表**（17 行，与教务网页完全一致） |
| `kscxtj/queryXsksByxhList` | POST form，`pxn`/`pxq`/`ppylx` + `pageNum`/`pageSize` | `exams-empty.json` + `XskscxByXhColumn.js` | 考试安排（字段名从列定义 JS 实锤） |
| `UserManager/queryXsxkqk` | POST form | `xsxkqk.json` | ⚠️ 实为**本学期选课情况**（YXXF=本学期所选学分），非修读进度——**弃用** |

**关键口径结论（已验证）**：
- 教务网页"学业完成情况"的**已完成学分是前端按成绩单本地汇总的**：按 `grcjcx.kclb`（课程类别）分组、只计已通过课程的 `xf`。用样本验证：学科平台 23.5 / 实验 5.0 / 基础实习 3.0 / 专业实习 2.0 与网页完全一致（通识差 1 分系样本不全）。**App 用同样本地汇总，离线可用且口径一致。**
- `queryXflbyq` 返回的 `ywcxf` 是另一种口径（仅认定/转移类学分，如创新学分 14.2），**不用它当已完成**，只用它的 `yqwcxf`（要求学分）与 `yzhxf`/`dzhxf`（已/待转移）。
- ⚠️ UA 提示修正：本次实测证明**跨 UA 直连不会作废会话**（此前两次会话失效系人为关闭）；但抓取仍统一走 WebView 同源 fetch（架构不变，避免 Cookie/UA 维护成本）。

## 4. 功能一：单科排名展示（零风险，先行）

**数据层**：`GradeEntity` 增加 3 列（Room **v3 → v4** 迁移，`ALTER TABLE grade ADD COLUMN`，均可空）：

```
pm   TEXT  排名（原始字符串，空=无排名）
zrs  TEXT  该课程总人数
khfs TEXT  考核方式（考试/考查）
```

- `GradesParser.parseGrades` 同步解析。注意 grcjcx 字段顺序为 `kclb → zzcj → … → xf`（xf 在后），解析按字段名取值不受影响。
- 迁移脚本 + `schemas/4.json`（exportSchema 已开启）。

**UI**：
- `GradeRow` 副标题追加 `· 排名 12/128`（pm/zrs 任一为空则不显示该段）。
- 成绩行改为可点击，弹 `CourseGradeDetailSheet`（ModalBottomSheet，复用课表详情弹层样式）：
  课程名 / 成绩（大字，不及格红）/ 排名 pm/zrs / 考核方式 khfs / 课程性质·类别 / 学分 / 正考·补考 / 开课学院 / 学期。
- 等级制成绩（优/良）：正常展示 zzcj 原文，排名段缺省。

## 5. 功能二：考试安排

### 5.1 数据层（字段已从列定义 JS 实锤）

新表（同属 Room v4 迁移）：

```
exam(
  id INTEGER PK AUTOINCREMENT,
  kcdm TEXT   课程代码 (KCDM)
  kcmc TEXT   课程名 (KCMC)
  kslx TEXT   考试类型 (KSSJDMC，如"期末考试")
  kssjms TEXT 考试时间描述原文 (KSSJMS，如"2027-01-15 08:00~09:50")
  ksrq TEXT   考试日期（从 KSSJMS 解析，yyyy-MM-dd；解析失败留空）
  kssj TEXT   开始时间（HH:mm，从 KSSJMS 解析，可能为空）
  jssj TEXT   结束时间（HH:mm，可能为空）
  cdmc TEXT   地点 (CDXX/CDDM，非空者优先 CDXX)
  zwh  TEXT   座位号 (ZWH)
  jkjsbz TEXT 进考场标志/备注 (JKJSBZ)
  kkyxmc TEXT 开课学院 (KKYXMC)
  xnxq TEXT   学期代码（冗余，便于清库与过滤）
)
```

- 请求：`pxn=2026-2027&pxq=1&ppylx=1&pageNum=1&pageSize=100`（**参数名带 p 前缀**，xn/xq 会被忽略——已踩坑验证）。
- 响应：PageHelper 分页结构 `{total, list:[...]}`（`exams-empty.json` 即空态样例）。
- ⚠️ **当前与往期学期均返回空**（往期排考被清理），真实考试数据字段以列定义 JS 为准，排考后（期中 ~10 月底）用真数据核对一次 `KSSJMS` 的具体格式再定解析正则；解析失败走空态不阻塞。
- 存储：覆盖式刷新（`ExamDao.clear()` + `insertAll`，包在 `db.withTransaction`），只保留当前学期。

### 5.2 抓取流程（一次会话抓全）

`jw_grades.js` 扩展为一次并发抓取：现有 4 项（querydangqianxnxq / user-me / getgpa / grcjcx / queryxsxx）+ 新增 3 项：

1. `cjgl/cjzhtjcx/cjcx/getXss`（form）→ 取 `xh/nj/pylx/xjid/fah`
2. `cjgl/cjzhtjcx/cjcx/queryXflbyq`（**JSON POST**，参数 `{xh, pylx, nj, fah, xjid, jzxnxq: xn+xq}`）→ 学分类别要求
3. `cjgl/cjzhtjcx/cjcx/queryBxkqk`（**JSON POST**，参数同上 + `sfcxxfj:"0"`）→ 毕业总进度
4. `kscxtj/queryXsksByxhList`（form，pxn/pxq/ppylx）→ 考试

- `GradesBridge.onGradesResult` 签名扩展为 `(gpaJson, gradesJson, userJson, xsxxJson, examsJson, xflbyqJson, bxkqkJson)`（App 内部接口直接改签名，JS 侧同步改）。
- 任一新接口失败不阻塞成绩主流程：JS 端单独 `.catch` 返回空串，parser 空串 → 空列表。
- 抓取时机沿用现状：教务 Tab 首次进入自动抓；刷新按钮（成绩/考试两段共用同一入口与确认弹窗）。

### 5.3 考试页 UI

- 分段切换：「成绩 | 考试」`SegmentedButton`，状态存 ViewModel（不持久化）。
- 考试列表 `LazyColumn`：
  - 按日期分组（组头：`1月15日 周五`，日期来自解析后的 `ksrq`）；
  - 行：课程名（粗体）/ 时间段（kssj-jssj 或 kssjms 原文）/ 地点 / 座位号徽章 / 考试类型；
  - **倒计时徽章**：距今 N 天（`D-3`），已结束置灰；
  - 空态：「本学期暂无考试安排」（排考后刷新即出）。

### 5.4 考试提醒（用户已确认：考前 1 天 + 1 小时）

新增 `ExamReminderScheduler`，模式完全复用 `ClassReminderScheduler`：

- **触发点**：考前 1 天 20:00（"明天 8:00 高等数学 考试，地点 机械楼314，座位 12"）；开考前 1 小时（`kssj` 解析失败则此条跳过，只发前一天）。
- **排期窗口**：未来 30 天内所有考试全量重排；requestCode 使用 **8_000_000 段**（与课程提醒 hash、每日脉冲 9_000_000 隔离）；已排 code 集合存 DataStore `exam_reminder_codes`。
- **通知渠道**：新建 `exam_reminder`（IMPORTANCE_HIGH）。
- **重排时机**：考试数据刷新成功后、`BOOT_COMPLETED`、每日脉冲（脉冲 handler 里同时重排课程+考试提醒）。
- **开关**：不做独立开关，跟随通知权限；若用户反馈吵再加设置项。

## 6. 功能三：学分修读进度（设计定稿）

**数据来源（三层）**：
1. **毕业总进度**：`queryBxkqk` → `YQXF` 要求学分 125.5 / `ywcxf` 已修 89.5 / `wwcxf` 未完成 36.0 / `YQMS` 要求 58 门 / `ywcms` 已过 43 门。
2. **分类别要求学分**：`queryXflbyq`（17 行）→ `kclbmc` 类别 / `kcxzmc` 性质 / `yqwcxf` 要求学分 / `yzhxf` 已转移 / `dzhxf` 待转移。
3. **分类别已完成学分**：**本地按成绩汇总**（与教务网页口径一致，已验证）——`grade` 表按 `kclb` 分组、只计已通过（数字 ≥60 或 优/良/中/及格/合格）课程的 `xf` 之和。

**数据层**：轻量缓存进 DataStore（无独立表）：`xflbyq_json` + `bxkqk_json` 两份原始 JSON；分类别"已完成"实时从 grade 表 Flow 计算（Room 已有观察流，天然联动成绩刷新）。

**UI**：成绩页 ScoreCard 下方折叠卡（入口行样式与"自定义纳入计算的课程"一致）：
- 顶部：毕业总进度条 `已修 89.5 / 125.5 学分（71%）`，副行 `已过 43/58 门`；
- 下方：每类别一行——类别名 + 进度条（本地已修/接口要求）+ `54.0/67.0`；要求>0 才显示该行；
- 超过 100% 钳制并变 `tertiary` 色；有转移学分的类别加角标（`含转移 8.0`）；
- 数据为空（未抓到）整个卡片不显示；解析失败不阻塞成绩页。

## 7. 功能四：挂科/学业状态（纯本地）

- 数据源：现有 `grade` 表 `isFailed`（数字成绩 <60），**不调** `xjgl/xyyj` 接口。
- 展示：学期组头右侧追加红色 `N 门未通过`（N>0 才显示）；补考通过后自然消失（覆盖刷新语义）。

## 8. 功能五：外部系统链接（"我的"页）

通用区（检查更新下方）新增一组 `SettingsItemRow`，点击 `Intent(ACTION_VIEW)` 跳浏览器：

| 名称 | URL | 状态 |
|---|---|---|
| 评教系统 | `https://pingjiao.ustb.edu.cn` | ✅ 已知 |
| 大创/SRTP | `https://srtp.ustb.edu.cn` | ✅ 已知 |
| 课程平台（北科学堂/雨课堂） | 待补 | ⚠️ 需要准确地址 |
| 实践教学平台 | 待补 | ⚠️ 需要准确地址 |

- 每行右侧 `KeyboardArrowRight`，无浏览器时 Toast 提示；URL 集中在 companion object 常量。

## 9. 数据层与版本汇总

- Room **v3 → v4**（一次迁移同时完成）：
  - `grade` 加列：`pm TEXT`、`zrs TEXT`、`khfs TEXT`
  - 建表 `exam`
- DataStore 新 key：`xflbyq_json`、`bxkqk_json`、`exam_reminder_codes`
- Room schema `app/schemas/.../4.json` 入库
- 版本：`versionCode 19 / versionName 1.1.0`（首个 minor bump，出包前可改）

## 10. 测试计划

- **Parser 单测**：`ExamsParserTest`（空态 fixture + KSSJMS 时间解析用例）、`CreditProgressParserTest`（xflbyq/bxkqk fixture）、`GradesParserTest` 增补 pm/zrs/khfs 断言、本地按 kclb 汇总已通过学分的纯函数单测（用 grcjcx-all 样本断言 学科平台=23.5/实验=5.0）。
- **迁移测试**：`MigrationTestHelper` 验证 3→4（schema JSON 已入库）。
- **提醒排期单测**：考前 1 天/1 小时触发点、KSSJMS 解析退化、requestCode 段隔离。
- **手工验收**：登录抓取一次全通（成绩+考试空态+学分卡）→ 考试页 → 排名/详情弹层 → 学分进度条与教务网页数值一致 → 外链四行 → 挂科红标 → 暗色走查。

## 11. 样例状态

✅ 已抓取入库（2026-08-29 真实会话，均无个人标识信息）：
`xflbyq.json`（学分类别要求 17 行）、`bxkqk.json`（毕业总进度）、`xsxkqk.json`（弃用留档）、`exams-empty.json`（考试空态）、`XskscxByXhColumn.js`（考试列定义=字段名依据）。

⏳ 待排考后校准：一次真实 `queryXsksByxhList` 返回（核对 `KSSJMS` 时间格式），不影响先行开发。

## 12. 实施顺序（审核通过后）

1. Room v4 + GradeEntity 扩展 + 排名 UI/详情弹层（零依赖，先行）
2. 挂科红标（同上）
3. 学分进度（抓取链 getXss→Xflbyq/Bxkqk + 本地汇总 + 折叠卡）
4. 考试：抓取 + 考试页（字段已实锤，空态先行）+ 考试提醒调度
5. 外链组（差两个 URL，不阻塞）
6. 出包 v1.1.0（递增 versionCode/name，release 构建）
