# 北科本研一体化教务系统（byyt）接口全量参考

> 目的：后续开发直接查本文档，**不再重复扒站**。
> 标注说明：✅ 已实测接入 App ｜ 🔬 已实测/字段实锤但未接入 ｜ 📝 来自 2026-08-28 调研，未逐项实测 ｜ ⚠️ 已弃用/有坑
> 实测时间：2026-08-28 / 2026-08-29（真实会话）。样例统一在 `docs/samples/`。

## 0. 通用约定

| 项 | 说明 |
|---|---|
| Base URL | `https://byyt.ustb.edu.cn` |
| 鉴权 | Cookie `SESSION=<base64(uuid)>`（Spring Security 风格），登录后全站通用 |
| UA | 跨 UA 直连**不会**使会话失效（2026-08-29 curl 实测）；App 内仍统一 WebView 同源 fetch，不维护 Cookie/UA |
| 编码 | 全站 UTF-8（Windows 终端显示乱码是 codepage 问题，非接口问题） |
| 请求体 | 两种：**form**（`application/x-www-form-urlencoded`）与 **JSON**（`application/json`，传 form 会被拒 415）。逐接口标注 |
| 响应 | 两种形态：包装 `{code,msg,msg_en,content}` 或**裸** JSON/数组（逐接口标注） |
| 分页 | PageHelper 结构 `{total,list,pageNum,pageSize,size,pages,isFirstPage,...}` |
| 学期代码 | `xnxq`="2025-20262"（xn+xq 拼接）、`xnxqmc`="2025-2026-2"；进度接口的 `jzxnxq` 同为 xn+xq 拼接 |
| 字段命名 | form 系接口字段多为大写（XN/XQ/KCMC），JSON 系接口小写（kcmc/xf）；同一数据两种接口字段名不同 |
| 页面规律 | 功能页 HTML 尾部引 `/pub/<模块>/...js` 与 `/component/inco/...js`，**数据接口路径藏在 JS 里**；表格列定义 JS（`*Column*.js`）= 响应字段名的权威来源 |

## 1. 当前学期与校历

| 接口 | 方式/参数 | 响应 | 状态 |
|---|---|---|---|
| `/component/querydangqianxnxq` | POST form，空体 | 裸 `{XN,XQ,XNXQ,XNXQ_EN}`（XNXQ="2026-2027-1"） | ✅ |
| `/component/queryRlZcSj` | POST form `xn,xq,djz`(周次) | 包装 `content:[{xqj:1..7,rq:"yyyy-MM-dd"}]` 该周 7 天日期 | ✅ |
| `/component/getXnxqByRq` | **GET** `?rq=yyyy-MM-dd` | 裸 学期+教学周 zc | 📝 |
| `/Xiaoli/queryMonthList` | POST form `xn,xq`，**需 header `RoleCode: 01`** | 裸 `{xlList:[...],xnxqList,monlist}`；**每周 7 条**（每天一条，仅一个星期字段非空，周一那条 `MON`=该周周一日期），`XNXQ`=xn+xq 拼接，`ZC` 1..18 教学周、99 假期 | ✅ |
| `/component/queryzclist` | POST form `xn,xq` | 包装 `content:[{ZC}]` 周次列表（1-18+99） | ✅ |
| `/component/querydangqianzc` | POST form | 当前教学周（假期返回空） | 📝 |

**教学周≠日期周**：长假周（国庆）不占教学周序号，一切周映射以 `Xiaoli/queryMonthList` 为准。

## 2. 课表

| 接口 | 方式/参数 | 响应 | 状态 |
|---|---|---|---|
| `/xszykb/querykbsffb` | POST form `xn,xq` | 裸 `"0"`=未发布 | ✅ |
| `/xszykb/queryxszykbzong` | POST form `xn,xq` | 包装 `content:[...]` 总课表；字段 `RWH` 任务号、`KEY`（"xq2_jc1"=周2第1节；"bz"=备注行）、`SKSJ` 多行文本（名/师/周/【校区】地/节）、`ZC` 周位图（index=周次）、`XB` 色号（99999=无固定时间） | ✅ |
| `/xszykb/queryxszykbzhou` | POST form | 单周课表（含备注） | 📝 |
| `/component/queryKbjg` | POST form `xn,xq,pylx` | 包装 `content:[{xj,kssj,jssj}]` 节次时间（1..13 节） | ✅ |
| `/xszykb/queryxszytjkb` | POST form | 推荐课表视图 | 📝 |
| `/component/queryKssjFb` | POST form | 节次时间发布状态 | 📝 |
| `/component/queryxskbbzb` / `queryxskbbzb2` | POST form | 课表备注 | 📝 |
| `/xskb/queryrwbewm` | POST form | 课程任务二维码 | 📝 |

## 3. 成绩与学业

### 3.1 成绩单

`/cjgl/grcjcx/grcjcx` — **POST JSON** `{xn,xq,kcmc:null,cxbj:"-1",pylx,current:1,pageSize:500,xscjlb:null,sffx:null,yhdm}`（yhdm/pylx 取自 `/user/me`）→ 包装 `content:{total,list:[...]}` ✅

list 字段（实测，`docs/samples/grcjcx-all.json`）：`kcdm` `kcmc` `xnxq` `xnxqmc` `kcxz`(必修/任选) `kclb`(通识课程/实验/美育(素质拓展)…) `xf` 学分 `zzcj` 总评(可能为"优/良") `bkcx`(正考/补考) `yxmc` 开课学院 `sffx` 辅修 **`pm` 排名 `zrs` 该课总人数 `khfs` 考核方式** `xs` 学时 `zpcj` `xscjlb`(主修) `rwh`。
⚠️ 字段顺序：`kclb → zzcj → … → xf`（xf 在 zzcj 之后），按名取值不受影响。

### 3.2 GPA

`/cjgl/grcjcx/getgpa` — POST form 空体 → 裸 `{BL:GPA值, HDXF:已获学分, TGKC:通过门数, PM:专业排名, ZRS:专业总人数, PJXFJ_PM:平均学分绩排名, PJXFJ_PM_FW}` ✅

### 3.3 学业完成情况（修读进度链）🔬

调用链：先 `getXss` 拿标识，再 JSON POST 查询。

| 接口 | 请求 | 响应 |
|---|---|---|
| `/cjgl/cjzhtjcx/cjcx/getXss` | POST form 空体 | 包装 `content:[{xh,nj,pylx,xjid,zyfxdm,fah,…}]`（fah=培养方案号） |
| `/cjgl/cjzhtjcx/cjcx/queryqxnxq` | POST form 空体 | 裸 `{XN,XQ}` 当前学年学期 |
| `/cjgl/cjzhtjcx/cjcx/queryBxkqk` | **POST JSON** `{xh,pylx,nj,jzxnxq:xn+xq,xjid,fah,sfcxxfj:"0"}` | 包装 `content:{yqmsxf:{YQXF要求学分,YQMS要求门数}, ywcxf已修学分, wwcxf未完成学分, ywcms已过门数, wwcms未过门数}`（实测 125.5/89.5/36.0/58/43） |
| `/cjgl/cjzhtjcx/cjcx/queryXflbyq` | **POST JSON** 同上（可加 `zyfxdm:"0"`） | 包装 `content:{total:17,list:[{kclbmc学分类别,kcxzmc课程性质,kclbdm,kcxzdm,yqwcxf要求学分,yzhxf已转移,dzhxf待转移,moochdxf,moocsjhdxf}]}`（17 行与教务网页"学分类别要求"表一致） |

**口径警告（重要）**：
- `queryXflbyq` 的 `ywcxf` 是**转移/认定口径**（如创新学分 14.2），≠已完成学分，**勿用作已完成**；网页"已完成学分"实为**前端按成绩单 `kclb` 分组汇总已通过课程 `xf`**（已数值验证：学科平台 23.5、实验 5.0、基础实习 3.0、专业实习 2.0 与网页一致）。App 内同样本地汇总，可离线。
- 本地 kclb → Xflbyq 类别行的匹配规则：全等 或 行名以本地名结尾（如 "素质拓展—美育(素质拓展)".endsWith("美育(素质拓展)")）。

| 其他同模块接口 | 说明 | 状态 |
|---|---|---|
| `/cjgl/cjzhtjcx/cjcx/queryMkyq` | **JSON** `{xjid,zyfxdm,fah,pylx}` 模块要求树（含每模块要求/完成学分门数学时） | 🔬 |
| `/cjgl/cjzhtjcx/cjcx/querybyyq` | **JSON** `{xh,zyfx,pylx,fah}` 毕业要求 | 🔬 |
| `/cjgl/cjzhtjcx/cjcx/queryFaKzkc` | 方案可修课程 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/querysfxsbyyq` | 是否显示毕业要求 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/queryZyfxTjinfo` | **JSON** 专业方向统计 | 📝 |
| `/cjgl/cjzhtjcx/cjcx/queryInfo` | **JSON**（页面注释代码中出现，勿依赖） | 📝 |
| `/cjgl/grcjcx/bkcxbjList` | 补考/重修班级 | 📝 |
| `/cjgl/grcjcx/seefx` | 辅修成绩 | 📝 |
| `/xjgl/xyyj/*`（如 `queryXyyjXshd`） | 学业警示/学期成绩/挂科情况（菜单"学业警示核查"）——App 选择本地算挂科，不依赖 | 📝 |
| `/UserManager/queryXsxkqk` | ⚠️ **实为本学期选课情况**（YXXF=本学期所选学分，YXMS=所选门数，另有 kxfsobj），**不是修读进度，弃用** | ⚠️ |

## 4. 考试

`/kscxtj/queryXsksByxhList` — POST form：**`pxn`/`pxq`/`ppylx`（⚠️ p 前缀，传 xn/xq 会被忽略返回空）** + `pageNum`/`pageSize` → **裸** PageHelper 分页 `{total,list:[...]}` 🔬

list 字段（权威来源：列定义 JS `/pub/gly/ksgl/cxtj/XskscxByXhColumn-*.js`，已存 `docs/samples/XskscxByXhColumn.js`）：
`XH` `XM` `KCDM` `KCMC` 课程名、`KSSJDMC` 考试类型（"期末考试"）、`KSSJMS` **考试时间描述文本**（需解析出日期/起止时间）、`ZWH` 座位号、`CDDM`/`CDXX` 地点、`JKJSBZ` 进考场标志、`KKYXMC` 开课学院、`ZYMC`/`ZYFXMC`/`NJMC`。

⚠️ 当前学期与往期学期均返回空（历史排考被清理）；空态样例 `docs/samples/exams-empty.json`。**排考后需用真数据核对 `KSSJMS` 格式一次**。
考试查询页面：GET `/kscxtj/queryXskscxByXh`（HTML）。

## 5. 选课（全模块 📝 未逐项实测，App 不做写操作）

`/Xsxk/*`：`queryXkdqXnxq` 当前选课学期、`queryKkxqList` 开课学期列表、`queryKxrw` 可选任务、`addXuanke` 选课、`tuike` 退课、`queryXkgwc` 购物车、`queryYxkc` 已选课程、`queryXsxkrzList` 选课日志、`queryJiaofei` 缴费查询；`/Xsxktz/queryXlctzList` 休复学；页面 `/Xsxk/query/1`。
课表查询（选课系统内）：`/Xskbcx/queryXskbcxList`(个人) / `queryBjkbcxList`(班级) / `queryGwckbcxList`(公选课)。

## 6. 学籍与个人

| 接口 | 说明 | 状态 |
|---|---|---|
| `/user/me` | POST form；完整档案+25 权限码+`roleAuth['01']` 完整功能菜单树（qxmc/url/fqxdm）+学籍快照 | ✅ |
| `/UserManager/queryxsxx` | 学籍信息（学院/专业/班级/年级） | ✅ |
| `/UserManager/querywhbsxsxx` | 含身份证号 ⚠️ 敏感勿存 | 📝 |
| `/UserManager/queryArrears` | 欠费查询 | 📝 |
| `/UserManager/xgmm` | 改密码 | 📝 |
| `/UserManager/upddzyx` / `updlxdh` | 改邮箱/电话 | 📝 |
| `/common/queryGjlist` / `queryMzlist` | 国籍/民族代码表（页面下拉用） | 📝 |

## 7. 培养方案与其他

- `/Zdpyfa/*` 18 个接口（基本信息/课程要求/毕业要求/实践环节/专业方向统计等）📝——**部分需页面上下文，直接调用 403**；页面 `/xspyyjsxsjh/xsjhBgd/1`。
- `/Jkgrjhpp/qeuryXspyfaList` 📝（注意原拼写 qeury）。
- 交流生业务 `/j1jh/gnjls/*` 📝；AI 助手 `/incoai/go` 📝；收藏 `/shouCang/qxshouCang` 📝。
- 外部系统：评教 `https://pingjiao.ustb.edu.cn`、大创/SRTP `https://srtp.ustb.edu.cn`、北科学堂/雨课堂/毕业/实践教学平台（URL 待补）。

## 8. 样例文件索引（docs/samples/）

| 文件 | 内容 |
|---|---|
| `grcjcx-all.json` | 全量成绩单（含 pm/zrs/khfs） |
| `getgpa.json` | GPA 概览 |
| `xflbyq.json` | 学分类别要求 17 行（要求学分实锤） |
| `bxkqk.json` | 毕业总进度 |
| `xsxkqk.json` | 本学期选课情况（弃用留档） |
| `exams-empty.json` | 考试分页空态（参数正确性验证） |
| `XskscxByXhColumn.js` | 考试列定义（响应字段名依据） |
| `queryxszykbzong-2026-2027-1.json` | 总课表 |
| `queryKbjg-section-times.json` | 节次时间 |
| `queryRlZcSj-week1-dates.json` | 第 1 周日期 |
