# 成绩 GPA 页 技术执行方案（待审核）

> 状态：**待用户审核**。审核通过后才动手写代码。
> 分支：`feat/grades-gpa`（与添加课程升级共用，或按用户要求拆分）。

## 1. 目标

在 App 内新增"成绩"页，展示从教务系统抓取的成绩单与 GPA 概览：
- 顶部 GPA 卡片：GPA、专业排名/总人数、已获学分、通过课程门数
- 成绩列表：按学期分组，含课程名/学分/成绩/课程性质（必修/任选）/课程类别
- 下拉或按钮手动刷新（重新走教务抓取流程）
- 成绩入库缓存，离线可看

## 2. 事实依据（已用真实会话实测，2026-08-28）

| 接口 | 方式 | 实测返回 |
|---|---|---|
| `cjgl/grcjcx/grcjcx` | **POST + JSON body**：`{xn,xq,kcmc,cxbj,pylx,current,pageSize,xscjlb,sffx,yhdm}` | 全量成绩 54 门（`content.list`，字段见下）|
| `cjgl/grcjcx/getgpa` | POST form | `HDXF`(已获学分 112.5)、`TGKC`(通过 54 门)、`PM`(排名 7)、`ZRS`(总人数 166)、`BL`(4.22，疑似 GPA 值)、`PJXFJ_PM`(1)、`PJXFJ_PM_FW`(0.6)、`show_config` |
| `component/querydangqianxnxq` | POST form | 当前学期（复用现有逻辑） |

成绩条目字段（实测）：`kcmc` 课程名、`kcdm` 课程代码、`xnxqmc` 学期（如 2025-2026-2）、`kcxz` 课程性质（必修/任选）、`kclb` 课程类别（通识课程/实验…）、`xf` 学分、`zzcj` 成绩、`bkcx` 正考/补考、`yxmc` 开课学院、`pm`/`zrs` 该课排名/人数、`sffx` 是否辅修。

**字段语义待首次实抓确认**：`BL`=4.22 按值判断是 GPA；`PJXFJ_PM`=1 是平均学分绩排名；`PJXFJ_PM_FW`=0.6 是排名范围（前 60%？）。UI 先做映射表，实抓后校准，不把字段名直接暴露给用户。

## 3. 抓取方案（复用导入页模式）

成绩抓取与课表导入共用同一套 WebView 登录机制：
- 新增 `assets/import/jw_grades.js`：在已登录页面注入，同源 fetch `grcjcx`（JSON POST）+ `getgpa`，通过 `BeikeImport` JsBridge 回传（bridge 加新方法 `onGradesResult(gpaJson, gradesJson)`，与课表抓取互不干扰）
- WebView 组件复用：`JwWebView` 从 ImportScreen 抽为共享组件（含 PAGE_FIX_JS 注入、错误上报），成绩页与导入页各自实例化
- 会话复用：CookieManager 进程内共享，已登录过则成绩页打开即抓，无需重复登录
- 登录页 URL 与课表导入相同（byyt 首页），SSO 跳转逻辑完全一致

## 4. 数据层

新增 Room 表（数据库 version 1 → 2，需 migration 或 fallbackToDestructiveMigration——成绩可重抓，倾向直接重建，**待确认**）：

```
grade(
  id, kcdm 课程代码, kcmc 课程名, xnxq 学期代码, xnxqmc 学期名,
  kcxz 课程性质, kclb 课程类别, xf 学分, zzcj 成绩, bkcx 正考/补考,
  yxmc 开课学院, sffx 是否辅修
)
```
- 刷新策略：覆盖式全量替换（与课表导入一致）
- GPA 卡片数据量小，存 DataStore（JSON 原样缓存 + 抓取时间戳）

## 5. UI 设计

- 入口：课表页顶栏新增成绩图标（CloudDownload 与 Settings 之间），切到成绩页（沿用现有 screen 状态切换，不引导航库）
- 页面结构：
  - 未缓存过成绩 → 内嵌 WebView 登录 + 自动抓取（与导入页一致的浏览体验）
  - 已缓存 → GPA 卡片 + 学期分组成绩列表（LazyColumn，学期 sticky header 或分组标题），右上角"刷新"按钮重新进入抓取流程
- 成绩展示：课程名（主）、学期/学分/性质（次）、右侧成绩大字；不及格（<60）标红
- GPA 卡片：GPA 大字 + 排名/总人数 + 已获学分 + 通过门数，底部小字标注数据更新时间

## 6. 单测

- `GradesParserTest`：grcjcx 响应解析（学期分组、学分数字解析、不及格判定）、getgpa 字段映射（以 2026-08-28 实测 JSON 为 fixture）

## 7. 里程碑拆解

1. 数据层：grade 表 + DAO + GPA DataStore + 解析器 + 单测
2. 抓取链路：jw_grades.js + bridge 扩展 + WebView 组件抽取共享
3. 成绩页 UI + 顶栏入口 + 刷新流程
4. 联调真机验证（登录→抓取→展示→离线缓存→刷新），出 1.0.5

## 8. 风险与对策

- **grcjcx 是 JSON POST**：WebView 内 fetch 无障碍；但与课表接口的 form 提交不同，注入脚本需带 `Content-Type: application/json`，已实测可行
- **字段语义不确定（BL/PJXFJ_PM）**：首版 UI 展示为"GPA 4.22 · 排名 7/166"，实抓后若语义偏差只改映射表
- **成绩隐私**：与课表同级本地存储，不上传任何服务器（README 隐私节已覆盖）
- **教务成绩页可能有反爬/频控**：刷新做手动触发，不做自动轮询

## 9. 待用户确认的决策点

1. 数据库升级策略：成绩可重抓，建议 `fallbackToDestructiveMigration`（简单）；若要保留手动添加的课程则必须写 migration（**建议写 migration**，手动课程不能丢）
2. 入口位置：顶栏第三图标（建议）vs 底部 Tab
3. 成绩缓存策略：入库离线可看（建议）vs 每次在线抓
4. 是否需要"学分修读进度"（`queryXsxkqk`，按课程性质统计已修学分）一并做进本页？（建议：本次不做，下版本再说）
