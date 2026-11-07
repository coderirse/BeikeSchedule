# 03 · 教务导入 / 扫码登录 / 云同步 / 更新通道（交接深读）

> 范围：`import/`（WebView + JS 桥 + 3 个注入脚本 + 4 个解析器）、`data/remote/`（云 REST、二维码、票据、两套 Ed25519、班级实验下发、SmartClass）、`data/backup/`（快照/云编排）、`ui/sync/`（一键同步编排）、`ui/settings/{SettingsViewModel,UpdateInstaller}`、`tools/sign_update.py`。
> 基线：v1.5.3 / versionCode 58（app/build.gradle.kts:24-27），只读勘察，未改任何生产代码。行号以当前工作区为准。
> 结论优先：先给判断，再给证据；失效点/风险都标了严重度。

---

## 0. 一页结论（TL;DR）

1. **只有一个导入入口**：「登录并同步」= `UnifiedSyncScreen`（MainActivity.kt:245-248），一次扫码串行跑 7 类步骤（SyncPlan.kt:10-19）；旧的导入页/成绩页/云登录页已合并删除，文档里若还提到它们是旧版本。
2. **二维码不是 App 画的**：WebView 里微认证 iframe 正常加载，原生在 `shouldInterceptRequest` 里截 `qrpage/qrimg` 的 sid（JwWebView.kt:266-272、QrAuthApi.kt:157-210），App 自己长轮询 `sis.ustb.edu.cn/connect/state`（UnifiedSyncViewModel.kt:597-658），授权后把主框架导航到 SSO 回调。
3. **登录态判定=页面 URL 精确匹配**：`onPageFinished` 里 host 在 `ustb.edu.cn` 且 path 恰好 `/authentication/main` 才触发抓取（JwWebView.kt:244-263、403-409）；不是接口探测。
4. **抓取靠同源 fetch 的 assets 脚本**：`jw_import.js`（课表+校历）、`jw_grades.js`（成绩/GPA/学籍/考试/学业进度）、`jw_identity.js`（学号姓名），结果以 JSON 信封经 `addWebMessageListener` 桥回传（JwBridge.kt:28-39）；三个桥分工见 JwImportBridge.kt:18-28 / GradesBridge.kt:23-35 / CloudLoginBridge.kt:14-20。
5. **步骤严格串行、脚本内部并发**：identity → 云 token（含云端探测与方向决策）→ 课表 → 成绩 → 实验安排 → 上传/恢复（UnifiedSyncViewModel.kt:265-268）；每个脚本内部用 `Promise.all` 并发拉多个接口（jw_import.js:123-129、jw_grades.js:80-107）。
6. **失败项重试是"按当前方向现算"**：只重跑非 OK 步骤，且后半段必须在重试的前半段跑完后按新 `mode` 计算（SyncPlan.kt:77-88；测试 SyncPlanTest.kt 覆盖）。
7. **云同步是"整包快照 + 脏标记 + 8s 去抖"，不是增量**（CloudSync.kt:51-80、229）；冲突只有三选一/后写覆盖，无字段级合并。
8. **首装首次同步会"自动上传并开启云备份"**：云端无备份时默认 `UPLOAD`（SyncPlan.kt:105），`stepBackup` 会 `setCloudSyncEnabled(true)` 再整包上传（UnifiedSyncViewModel.kt:536-549）——这与 README「云同步 opt-in 默认关闭」（README.md:32、79）存在语义差，见 §8 第 1 条。
9. **密码学是四套**：Ed25519 验更新元数据与备份快照（Ed25519.kt:50-81，平台 provider + net.i2p 兜底）、AES-256-GCM 存云 token（TokenCipher.kt:23-79）、RSA-OAEP-SHA256 密封教务 SESSION（JwSessionTicket.kt:35-67）、SHA-256 校验 APK（UpdateInstaller.kt:155-163、209-220）。
10. **更新链路失败关闭**：自有源验签失败→整体忽略并回退 GitHub（SettingsViewModel.kt:240-244），强更不可关弹窗（ProfileScreen.kt:507-548）；下载 5 次断点续传 + 摘要校验后才交系统安装器（UpdateInstaller.kt:93-186）。

---

## 1. 端到端时序

### 1.1 入口、状态机与"静默跑完"

- 入口只有三处，全部指向同一个全屏页：课表页导入按钮、教务 Tab 的重新同步、我的页云同步未登录行（MainActivity.kt:245-248；ScheduleScreen.kt:328-330、488；ProfileScreen.kt:235-248）。
- 状态机：`Login → Running(current) → Done(results)/Failed`，中间可插 `NeedDecision`（换学号/云端方向）（UnifiedSyncUiState 定义 UnifiedSyncViewModel.kt:38-53）。
- **教务会话还在时会静默跑完**：起始地址是 `JW_SSO_ENTRY_URL = https://byyt.ustb.edu.cn/oauth/login/code`（JwWebView.kt:19-25；UnifiedSyncScreen.kt:228），有 SESSION 时 SSO 立即 302 回教务主页 → `onMainPage`（JwWebView.kt:244-263）→ `onPageReady` 自动开跑（UnifiedSyncViewModel.kt:154-158、252-281），用户看不到登录界面。
- ViewModel 是 Activity 级：离开页面只 `cancel()` 在跑的流程（UnifiedSyncScreen.kt:97-103、UnifiedSyncViewModel.kt:188-198），再次进入靠 `resetIfFinished` 清终态（UnifiedSyncViewModel.kt:131-151）。

### 1.2 扫码：原生截获 sid + 自长轮询（不依赖页面轮询）

1. WebView 载入 SSO 移动版扫码页，页面里嵌 `sis.ustb.edu.cn` 微认证 iframe，依次请求 `/connect/qrpage?appid&return_url&rand_token` 与 `/connect/qrimg?sid`（QrAuthApi.kt:149-156）。
2. `JwWebView.shouldInterceptRequest` 对每个子资源请求回调（含 iframe），只观察不改写、返回 null（JwWebView.kt:265-272；UnifiedSyncScreen.kt:234）。
3. `QrTargetTracker.onRequest` 只认 host=`sis.ustb.edu.cn`，把 qrpage 的三个参数与后续 qrimg 的 sid 配对成 `QrTarget`，保留最近 5 个（QrAuthApi.kt:157-210）。
4. ViewModel 对最新目标 `ensurePolling`：`GET /connect/state?sid=…` 每 600ms 一次，单目标最长 5 分钟，读超时 40s（UnifiedSyncViewModel.kt:597-658、690-702；QrAuthApi.kt:33-37、114-118）。
5. 状态码语义：`code=1` 授权通过取 `authCode`（兼容字符串/`data`/对象多形态，QrAuthApi.kt:82-111）；`2` 已扫码待确认；`3/101/102/202/203` 失效；取到码后拼 `authorizeUrl = retUrl + appid + auth_code + rand_token`（QrAuthApi.kt:126-136，参数全部 URL 编码，QrAuthApiUrlTest.kt 锁死）。
6. 界面把主框架 `loadUrl(authorizeUrl)`，15 秒看门狗比对 URL 是否真的变了；跳转被静默丢弃则复位授权地址并 reload（UnifiedSyncScreen.kt:105-125、UnifiedSyncViewModel.kt:664-668）。
7. 关键工程决策：**禁用微认证页自带的轮询**（document-start 脚本覆盖 `window.getAuthState`，UnifiedSyncScreen.kt:235-237、400-418），否则页面轮询与 App 轮询互吃 205，页面还会"失败即 reload 换码"。
8. 同机扫码替代路径：`复制授权链接` = `https://sis.ustb.edu.cn/scan/sync?sid=`（QrAuthApi.kt:138-143；UnifiedSyncScreen.kt:176-182），并 `pinSid` 让该码失效前一直轮询（UnifiedSyncViewModel.kt:591-595）。

### 1.3 登录态判定与脚本注入

- 判定条件：`isMainPageUrl` = scheme 为 http(s) + host 通过 `ustb.edu.cn` 白名单 + `uri.path == "/authentication/main"`（JwWebView.kt:403-409）；**不能用 `url.contains`**，历史上可被任意域名伪路径触发注入（注释 JwWebView.kt:397-402）。
- `onPageFinished` 用 `view.post` 延迟回调，避免在加载回调里反向驱动流程；runnable 记录在 `pendingMainPage`，新导航/销毁时撤销（JwWebView.kt:148-150、252-262、327-336）。
- 脚本注入时机由 ViewModel 驱动：每步 `awaitScript` 发一个 asset 名到 `SharedFlow`（replay=0），界面收集后先校验 `isByytHost(webView.url)` 再 `evaluateJavascript`（UnifiedSyncViewModel.kt:98-100、298-309；UnifiedSyncScreen.kt:127-145）。
- 每个脚本自带重入标志且**成功/失败路径都复位**（jw_import.js:12-14、160-163、167-170；jw_grades.js:8-10、104-110）；结果可信还有一道界面侧复核：桥回调只有在主框架仍在 `byyt.ustb.edu.cn` 时才送给 ViewModel（UnifiedSyncScreen.kt:199-227）。

### 1.4 三个脚本各自 fetch 的接口（同源、带 SESSION）

| 脚本/资产 | 接口（方法） | 回传参数顺序 |
|---|---|---|
| `jw_identity.js`（assets/import/jw_identity.js） | `POST /user/me`（jw_identity.js:60）、`POST /UserManager/queryxsxx`（:61，失败降级） | `onIdentity [xh, xm]`（:68-71） |
| `jw_import.js`（assets/import/jw_import.js） | `POST /component/querydangqianxnxq`（:119）、`/xszykb/querykbsffb`（:124）、`/xszykb/queryxszykbzong`（:125）、`/component/queryKbjg?pylx=1`（:126）、`/component/queryRlZcSj?djz=1`（:127）、`/component/queryzclist`（:128）；校历优先 `/Xiaoli/queryMonthList`（:70，需 `RoleCode:01`），失败逐周 `queryRlZcSj` 兜底（:102-117） | `onResult [semText, published, courses, sections, weekDates, calendar]`（:163） |
| `jw_grades.js`（assets/import/jw_grades.js） | `POST /component/querydangqianxnxq`（:81）、`/user/me`（:84，失败不阻塞）、`/cjgl/grcjcx/getgpa`（:91）、`/cjgl/grcjcx/grcjcx` JSON（:92-97）、`/UserManager/queryxsxx`（:98）、`/kscxtj/queryXsksByxhList`（:99-101，参数 `pxn/pxq/ppylx`）；学业进度先 `/cjgl/cjzhtjcx/cjcx/getXss`（:66）再并发 `queryXflbyq`/`queryBxkqk` JSON（:74-76） | `onGradesResult [gpa, grades, user, xsxx, sem, exams, xflbyq, bxkqk]`（:106） |

接口字段与坑的权威说明在 docs/JWXT_API.md（课表 :42、成绩 :54-57、GPA :61-63、进度 :66-79、考试 :96-101、校历 :29-35、user/me :113），不再在此重复。脚本共性约束：`credentials:'same-origin'`、20s `AbortSignal.timeout`、非 2xx 直接抛 HTTP 状态、响应首字符是 `<` 就判定"会话已过期"（jw_import.js:28-60；jw_grades.js:37-62）。**这是对教务"过期返回 200 HTML"这一行为的显式适配。**

### 1.5 回传、解析、入库与覆盖规则

- 桥信封 `{"fn","args"}`，`dispatch` 解析失败静默忽略；`fn=onError` 走错误回调（JwBridge.kt:19-39）。桥用 `WebViewCompat.addWebMessageListener`，不用 `addJavascriptInterface`（后者注入每个 frame，第三方 iframe 可伪造抓取结果，JwBridge.kt:9-17）。
- 课表：`JwParser.parseCourses` 逐行 `runCatching`，坏行跳过并经 `rowErrorLogger` 落 logcat（JwParser.kt:36-44；UnifiedSyncViewModel.kt:122-127）；`KEY`/节次/周次缺失的固定课整行跳过而不是"入库但永不显示"（JwParser.kt:148-173）；`KEY="bz"`、无 KEY、`XB=99999` 归为无固定时间课（:146-147）；`ZC` 34 位位图原样入库（:163-167）；校历解析有脏值上限与坏日期整表作废（:99-139，`MAX_TOTAL_WEEKS=60` :196）。
- 节次时间缺失则整步失败、不落库：commitImport 会先清 `section_time` 再写，空表会让所有课算不出上课时间、提醒静默全失效（UnifiedSyncViewModel.kt:420-426）。
- 入库：`ScheduleRepository.commitImport` 在**一个 Room 事务**里删旧导入课 + 插新 + 覆盖节次时间，并按 `(taskId, name)` 保留用户的隐藏状态（ScheduleRepository.kt:86-103；隐藏是产品承诺，只有隐藏能跨导入保留，课程字段本身以教务为准）。
- 学期配置：`firstMonday` 取 queryRlZcSj 周一的回退链，`totalWeeks` 取校历、否则 20，`holidays` = 校历天级放假（offDays），换学期时清空旧 `makeups`（UnifiedSyncViewModel.kt:427-443；`parseWeekCalendar` JwParser.kt:91-139）。
- 成绩：只有解析出非空才覆盖（`replaceGrades` 清空 + 全插，ScheduleRepository.kt:41-44），空成绩是合法态（新生/未评教），不因此丢同批学籍/考试（UnifiedSyncViewModel.kt:466-472）；GPA 只随成绩保存（:469）；学籍快照来自 user/me + queryxsxx 双源（GradesParser.kt:92-114）。
- 考试：**null=没拿到，空列表=确实没有**，只有非 null 才覆盖并重排考前提醒（ExamsParser.kt:30-36；UnifiedSyncViewModel.kt:474-483）；时间文本 `KSSJMS` 用防御性正则解析，失败留空由 UI 回退原文（ExamsParser.kt:19-28、66-79）。
- 学业进度：`queryXflbyq`/`queryBxkqk` 原样存 DataStore，由 `CreditAggregator` 按成绩单本地汇总"已完成学分"（CreditProgressParser.kt:13-18、42-91；JwParser/JWXT_API 口径警告）。
- 班级实验/上机：服务端按班级下发，`ClassScheduleMapper` 转课程行（周位图 32 位与教务同构，ClassSchedule.kt:44-74），`applyClassSchedule` 整组替换 LAB 来源并保留隐藏、命中名单时自动隐藏同名无固定时间课（ScheduleRepository.kt:112-141；只在命中本班时调用，空响应不清空，UnifiedSyncViewModel.kt:519-525）。
- 云端恢复：四类课程源全清 + 五表整包写入（CloudSnapshotCodec.applyRestore，CloudSnapshot.kt:250-296）。

### 1.6 步骤编排：串行、方向决策、失败重试

- 顺序（SyncPlan.kt:10-19）：`IDENTITY → CLOUD_TOKEN → TIMETABLE → GRADES → CLASS_LAB → BACKUP`；`RESTORE` 分支替换掉抓取+上传（SyncPlan.kt:67-74，测试 SyncPlanTest.kt:42-80）。
- 两段式：前半段 `head` 固定（IDENTITY/CLOUD_TOKEN），后半段 `tail(mode)` 必须等云账号步骤确定了方向**之后**现算（SyncPlan.kt:51-59；UnifiedSyncViewModel.kt:263-268）。
- `executeStep` 跳过已 OK 的步骤（UnifiedSyncViewModel.kt:283-296）；`Done` 按步骤固有顺序展示（:278-280）；RESTORE 模式把未成功的抓取步骤标 SKIPPED 而非谎称成功（:269-277）。
- 单脚本超时 30s（`STEP_TIMEOUT_MS` UnifiedSyncViewModel.kt:697；`awaitScript` :298-309）；脚本自身每条 fetch 限 20s，但 `jw_import.js` 兜底路径最多 25 次顺序请求（jw_import.js:145-150），极端慢网会先撞 30s 原生超时——已知点，见 §8。
- 重试：`retryFailed` 只跑未成功项；`retryHead` 重跑未成功的身份/云账号；`retryTail` 按**重试后**的 mode 计算（SyncPlan.kt:76-88；回归用例 SyncPlanTest.kt:88-175，特别是 :133-161 的"重试决策出上传/恢复"）。
- 云账号步骤内部：学号变化先弹确认（UnifiedSyncViewModel.kt:332-339）→ `CloudApi.login`（:341-351）→ 探测 `getBackup`（:353-360）→ `defaultCloudMode` + 必要时三选一（:361-375）→ 记录本次方向文案（:376-381）。探测失败一律 `NO_UPLOAD`，宁可不备份也不拿本机覆盖云端（SyncPlan.kt:94-106；SyncPlanTest.kt:34-39）。
- 该步还有一个静默契约：**云端登录是无条件的**（不检查 `cloudSyncEnabled`，UnifiedSyncViewModel.kt:329-351），即用户只点"登录并同步"也会把 xh/xm + 密封的教务 SESSION 发到 api.caeamer.com；见 §8 第 1 条。

### 1.7 云侧分支：上传 / 恢复

- 上传：`stepBackup` 先 `setCloudSyncEnabled(true)`，再 `CloudSync.manualBackup`（强制构建上传；UnifiedSyncViewModel.kt:536-549）。
- 恢复：`stepRestore` → `CloudSync.restore`（验签→解码→本地回滚点→整包覆盖→重排提醒），成功后也打开同步开关，但**本次不追加上传**（:551-563、554-557）。
- 恢复模式下的课/成绩抓取不再跑，避免"刚恢复又被本机抓取覆盖"（SyncPlan.kt:5-8、67-74）。

### 1.8 检查更新 → 验签 → 下载 → 摘要 → 安装

1. 设置页初始化即 `checkUpdate()`（SettingsViewModel.kt:160-162、206-214）。
2. 自有源优先：`GET /api/bs/app/latest`（CloudApi.kt:112-117），响应字段 `versionCode/versionName/changelog/force/size/url/apkSha256/sig`（CloudApi.kt:57-69；样本 latest_1.5.3.json）。
3. 验签：`UpdateSignature.verifyDetailed` 先按 7 字段（含 apkSha256）验，再按旧 6 字段验；只有 FULL 才采信 `apkSha256`（UpdateSignature.kt:127-151；SettingsViewModel.kt:238-246）。失败即整体忽略自有源（SettingsViewModel.kt:240-244），界面显示"自有源不可用…已回退 GitHub"（ProfileScreen.kt:328-333）。
4. 版本比较：`latest.versionCode > installedVersionCode` 才算有更新（SettingsViewModel.kt:247-253）；服务器失败回退 GitHub Releases API，HTML 页面走浏览器（:262-299）。
5. 下载：`UpdateInstaller.downloadAndInstall` 仅当 `sha256Hex` 非空且 url 以 `.apk` 结尾才走应用内链路（UpdateInstaller.kt:73-74、80-88）；否则交浏览器（ProfileScreen.kt:832-841）。
6. 下载实现：OkHttp 流式写 `.part`，失败按 `Range` 断点续传最多 5 次（UpdateInstaller.kt:56-57、106-153）；416 丢 `.part` 重来（:118-121）；200 覆盖重来（:134-139）；每 256KB 报进度（:53-54）。
7. 校验：整文件 SHA-256 与签名覆盖的 `apkSha256` 比较，不等即删除拒绝安装（:155-163、209-220）。
8. 安装：改名为成品 → FileProvider `content://` + `ACTION_VIEW` + 读权限（:165-178）；需要 `REQUEST_INSTALL_PACKAGES`（AndroidManifest.xml:11）与 FileProvider 声明（AndroidManifest.xml:76-85；路径只放外部私有目录 app/src/main/res/xml/file_paths.xml:3-5），系统仍弹"允许安装未知应用"。
9. 强更：`force=true` 时不可关闭/无取消按钮的阻塞弹窗（ProfileScreen.kt:507-548；`force` 只在自有源可信时可用，SettingsViewModel.kt:246-250）。

---

## 2. WebView / JS 桥安全与失效风险

**结论：桥与注入的"信任边界"设计是目前全项目最讲究的部分（三层校验），但整条链路对教务页面结构的依赖仍然很重；教务一次改版就可能需要发版。**

### 2.1 已做的防护（均可在代码里确认）

- **失败关闭的导航白名单**（`jwNavPolicy` JwWebView.kt:369-386）：`ustb.edu.cn` 及其子域 http/https 同等放行（历史原因：SSO 回调 302 是 `http://sso.ustb.edu.cn/...`，拒绝 http 会永久卡"授权成功"，JwNavPolicyTest.kt:18-26）；站外 http/https 交系统浏览器；无 host 或非 http(s)（file/content/weixin/blob…）**拒绝且不交接**（JwWebView.kt:204-231、JwNavPolicyTest.kt:28-44）。
- **SSL 一律 cancel**，绝不 proceed；且只有主文档失败才上报页面错误（JwWebView.kt:294-313）。`file://`/content 访问、混合内容、第三方 cookie、自动开窗全部显式关掉（:162-178）。
- **桥的 origin 白名单**：`BRIDGE_ORIGINS = {https://byyt.ustb.edu.cn, https://*.ustb.edu.cn}`，平台级按 origin 注入；回调再校验 `isMainFrame` + `isJwHost`（JwWebView.kt:29-37、181-192）。换成 `addWebMessageListener` 的动机就是防第三方 iframe 调桥（JwBridge.kt:9-17）。
- **第三层**：抓取脚本只在 `isByytHost` 时注入（UnifiedSyncScreen.kt:139-143），桥结果也只有主框架仍在 byyt 时才入 ViewModel（:199-227）；`jw_identity.js` 再自查一遍 host（jw_identity.js:29-34）。
- **WebView 生命周期**：显式 `destroy()`、移除桥、清 pending runnable（JwWebView.kt:327-336）；DevTools 仅 DEBUG 包开启（:155-158）。
- **Cookie 生命周期**：SESSION 由系统 WebView 的 CookieManager 持久化在 `app_webview/`；`clearJwSession` 清 Cookie/WebStorage/HttpAuth（JwWebView.kt:415-435，入口 ProfileScreen.kt:391-394、655-675）；备份规则显式排除 `app_webview/`（app/src/main/res/xml/backup_rules.xml:12-17、data_extraction_rules.xml:7-17）→ 换机必须重登，凭证不随云备份外流。
- **隐私**：两个脚本不读密码/cookie/localStorage（README.md:75；脚本内只有同源 fetch）。

### 2.2 依赖教务页面结构的清单（失效风险由高到低）

| 依赖 | 位置 | 失效表现 | 严重度 |
|---|---|---|---|
| 登录后主页面路径 = `/authentication/main` | JwWebView.kt:403-409 | 不触发 `onMainPage`，同步不自动开始（可点"手动获取"救回） | 高 |
| 登录页 PC 布局：meta viewport 被改 `width=1440`、`#app` 高度用 innerHeight 补、MutationObserver | JwWebView.kt:39-94、PAGE_FIX_JS:57-94 | 白屏/元素缩成小图；该脚本有幂等+条件重试回归测试（JwPageFixScriptTest.kt:16-39） | 高 |
| 微认证 iframe 请求序列 `/connect/qrpage`→`/connect/qrimg`、状态接口 `/connect/state`、状态码 1/2/3/101/102/202/203 | QrAuthApi.kt:149-210；UnifiedSyncViewModel.kt:626-651 | 二维码不出现或授权不前进；**无页面结构兜底，只能适配发版** | 高 |
| 页面自身轮询函数名 `window.getAuthState` | UnifiedSyncScreen.kt:404-418 | 覆盖不到→双轮询 205 冲突，慢网换码；仅一行脚本可修 | 中 |
| SSO 授权回调 302 → 站内 http | JwWebView.kt:369-377；QrAuthApi.kt:120-136 | 授权后卡住（历史踩坑，白名单已为其放宽） | 中（已缓解） |
| 三个脚本的运行前提（byyt 域、相对路径接口、会话 Cookie） | jw_import.js/jw_grades.js/jw_identity.js 头部注释 | 404/HTML→"会话已过期"文案；接口改路径需改脚本+解析器发版 | 中 |
| `AbortSignal.timeout`、`document-start script` 能力 | jw_import.js:38、jw_grades.js:39；JwWebView.kt:195-199 | 老 WebView 上脚本直接抛错/被 `runCatching` 静默跳过（后者更隐蔽） | 中 |
| `evaluateJavascript` 执行结果不回传 | UnifiedSyncScreen.kt:143 | 脚本在 `send` 前抛错时只能等 30s 超时（UnifiedSyncViewModel.kt:304-305），用户看到"超时"而非根因 | 中 |
| 校历 `Xiaoli/queryMonthList` 需 `RoleCode:01`、天级 `*1` 放假标记 | jw_import.js:69-100；docs/JWXT_API.md:29-35 | 校历失败退回逐周兜底但**丢失 offDays**；若整段失败（cal=null）还会把 weekMondays 清空、totalWeeks 回落 20、holidays 清空 → 假期标记丢失、网格/提醒错排 | 中 |

**维护性判断**：脚本是 assets 内置、随 APK 发布（app/src/main/assets/import/），而 v1.0 设计曾计划"脚本远程热更新"（docs/TECH_DESIGN.md:157）——**该能力并未实现**。因此教务改版 = 必须发版；建议接手后把"脚本可远程更新（带签名）+ 失败自检上报"列入 P1。

---

## 3. 密码学实现与攻击面

**结论：算法选型与参数对齐都正确且有回归测试锁死；真正的风险不在算法，而在"凭证有效期/新鲜度、密钥轮换、以及服务端是否严格校验"。**

### 3.1 四套用途

| 用途 | 实现 | 关键参数 |
|---|---|---|
| 云登录身份证明 | `JwSessionTicket`：RSA-OAEP-**SHA256** 密封教务 SESSION cookie，Base64 上传（JwSessionTicket.kt:35-67） | 显式 `OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,…)`，MGF1 必须也是 SHA-256 才与 Node 服务端 `oaepHash:'sha256'` 对齐（:28-33、54-59）；测试 JwSessionTicketTest.kt:38-44 |
| 云 token 落盘 | `TokenCipher`：AndroidKeyStore 内 AES-256-GCM 密钥，落盘 `"enc:"+Base64(iv12‖ct)`（TokenCipher.kt:23-79） | 密钥别名 `beike_cloud_token`、GCM 128 tag；无用户认证因子；解密失败返回空串=未登录（:47-61）；兼容历史明文并下次迁移（SettingsStore.kt:313-328） |
| 更新元数据验签 | `UpdateSignature` + `Ed25519`（Ed25519.kt:24-81） | 发布公钥硬编码 SPKI（UpdateSignature.kt:40-47）；先平台 `Signature.getInstance("Ed25519")`，失败/不可用回落 `net.i2p.crypto.eddsa`（Ed25519.kt:50-80；依赖 app/build.gradle.kts:105-107）；覆盖范围 FULL/METADATA_ONLY（UpdateSignature.kt:51-52、136-151） |
| 备份快照验签 | `BackupSignature`：Ed25519 签 `snapshot` 原始字符串的 UTF-8 字节（BackupSignature.kt:6-41） | 另一把硬编码公钥（:23-24）；验签失败/空签名一律拒绝恢复（:36-41） |
| APK 完整性 | `UpdateInstaller.sha256HexOf` 流式 SHA-256（UpdateInstaller.kt:209-220） | 与签名覆盖的 `apkSha256` 比较，忽略大小写；不一致删包拒装（:155-163） |

平台/兜底实现与 RSA-OAEP 参数都有真机踩坑注释支撑（Ed25519.kt:13-22、JwSessionTicket.kt:13-33），更新/备份两条链路共用 `Ed25519`（Ed25519.kt:22）。

### 3.2 签名覆盖的确切字段与 canonical 形式

- **更新元数据**：待签消息 = `SignedBody` 的紧凑 JSON UTF-8 字节，键序固定
  `{"versionCode","versionName","changelog","force","size","url","apkSha256"}`，无 `sig`、无空格、非 ASCII 不转义（UpdateSignature.kt:18-33、55-92；工具 tools/sign_update.py:7-16、40-47）。
  当前样本 latest_1.5.3.json 是 7 字段 + `sig`；测试用 OpenSSL 参考向量锁死"旧 6 字段"也可验（UpdateSignatureTest.kt:103-120），并验证"旧签名 + 响应里带 apkSha256"必须按 METADATA_ONLY、摘要不可信（:140-154）。
- **备份快照**：`sig = Ed25519(UTF-8 bytes of snapshot 原始文本)`，客户端验签后直接 `CloudSnapshot.decode(snapshot)`，**不经过对象重序列化**（BackupSignature.kt:16-18；CloudApi.kt:79-82；CloudSync.kt:190-196）。服务端若重新序列化快照，签名必然失败——这是服务端实现的关键约束。
- 服务端错误文案 `{message}` 会被拼进中文 Toast/日志，但路径等实现细节只进 Log（CloudApi.kt:214-228、246-251）。

### 3.3 可被攻击/误用的点

1. **jwTicket 无新鲜度/一次性**：RSA 只提供机密性，不提供防重放。任何拿到密文的人可反复提交；服务端必须每次解封后即时调教务 `/user/me` 校验、用完丢弃（README.md:81；JwSessionTicket.kt:12-27）。若服务端日志落明文 cookie/票据，即等于泄露该生全部教务权限。
2. **云 token 是长期 Bearer、无过期字段**：`LoginResult(token,xh,name)`（CloudApi.kt:53-55）无 expiry/refresh；客户端仅靠 401→`CloudAuthException`→清账号（CloudApi.kt:230-244、CloudSync.kt:156-162）。token 泄露=可读写全部备份；落盘只保护到 AndroidKeyStore（root/同 uid 风险仍在，TokenCipher.kt:12-21 已说明）。
3. **无证书 pinning**：客户端仅依赖系统 CA + Cloudflare（README.md:47；CloudApi.kt:34-38）。MITM 虽被 `network_security_config` 禁止明文（app/src/main/res/xml/network_security_config.xml:8-10），但 CA 级劫持仍可拿到 token 明文（jwTicket 本身是 RSA 密文，泄露面较小）。建议对 `api.caeamer.com` 评估 pinning 或 CT 监控。
4. **降级面（更新）**：`verifyDetailed` 先 FULL 后 LEGACY，且 METADATA_ONLY 永远被接受（UpdateSignature.kt:144-150）——服务端一旦回旧格式，`apkSha256` 即失效，更新退回"浏览器打开 url"链路（ProfileScreen.kt:832-841，url 来自已签名的旧元数据，可能是 http，浏览器不受 App 的明文禁令约束），只剩系统同签名检查。建议灰度完成后服务端固定 v2、客户端删除 LEGACY。
5. **密钥轮换不可在线完成**：更新公钥、备份公钥、JW 验票 RSA 公钥、SmartClass 硬编码 key 全部编译进 APK（UpdateSignature.kt:40-41、BackupSignature.kt:23-24、JwSessionTicket.kt:38-45、SmartClassCrypto.kt:36-49）；私钥换新必须发版。需要一份"密钥保管/轮换 runbook"（现有注释只说私钥在 gitignore 目录）。
6. **快照重放/回滚**：签名覆盖内容但不覆盖时效；`BackupEnvelope.updatedAt` 未与本机 `cloudLastBackupAt` 比较（CloudSync.restore：CloudSync.kt:183-202；`appVersionCode` 也未使用，CloudApi.kt:76），网络位置攻击者可重放一份旧的已签名快照导致数据回退。影响取决于恢复是否用户主动，建议恢复前比较 `updatedAt` 并提示。
7. **Keystore 不可用时的明文回退**：`TokenCipher.encrypt` 失败返回原文（TokenCipher.kt:31、42-44）——"功能优先"的显式取舍，但也意味着极端设备上 token 明文落 DataStore；可接受但要在安全评审里记录。
8. **同步/加密在主线程**：`stepCloudToken` 中 `jwTicket()` 作为实参在主线程执行（UnifiedSyncViewModel.kt:342、674-679：`CookieManager.getCookie` + RSA 加密），有轻微卡顿/ANR 风险（见 §8）。
9. **SmartClass 的"加密"不是安全机制**：AES-256-CBC 固定 Key/IV、`csrkKey` 与 token 算法均编译期常量（SmartClassCrypto.kt:29-32 已明确"只有防滥用价值"）。这条边界写得对，接手后**不要**把该模式复制到云同步/凭证场景。

---

## 4. 云同步

**结论：结构化程度高（快照 DTO 独立演进、验签、回滚点、撕裂防护），但"整包后写覆盖"语义仍是数据丢失的主要来源；回滚点目前是只写不读的死文件。**

### 4.1 快照结构与版本兼容

- `CloudSnapshot(schemaVersion=1, exportedAt, courses, sectionTimes, grades, exams, todos, settings)`（CloudSnapshot.kt:36-46），全部 DTO 带默认值、`ignoreUnknownKeys`（:50-56）。
- 兼容规则：旧快照可读（未知字段忽略、缺失字段取默认）；`schemaVersion > 1` 直接拒绝并提示升级（:57-62、测试 CloudSnapshotTest.kt:74-77）；规模合理性校验（课程≤5000、日程≤20000、成绩≤20000、考试≤2000、节次≤100 且 1..30）防"损坏/投毒快照整表覆盖"（:63-77、测试 :38-52）。
- 排除项：三类已排闹钟记录（设备本机概念，恢复后重排）、无课教室 UI 记忆（:31-35）；`TodoEntity` 已入快照，是网络契约，加字段必须带默认值（注释 :44，CloudSnapshotTest.kt:16-36 有字段保真）。
- 写入/读取：`CloudSnapshot.encode` 紧凑 JSON；服务端存原始 JSON 文本、下发 `snapshot` 原串 + `sig`（CloudSnapshot.kt:55-56；CloudApi.kt:71-83、150-168）。

### 4.2 脏标记、8s 去抖与并发

- 数据变更点调 `CloudSync.markDirty`：落 DataStore 脏标记（进程被杀不丢）→ 已登录且开启同步则排 8 秒去抖上传（CloudSync.kt:51-60、74-80、229）；App 启动时 `uploadIfDirty` 补传（:63-72；MainActivity.kt:154-157）。
- 簿记收敛到单线程调度器（`Dispatchers.IO.limitedParallelism(1)`，CloudSync.kt:39-42），去抖 Job 的 cancel/赋值无竞态；上传/恢复由 `uploadMutex` 全局互斥（:42、130、177）。
- 撕裂防护：上传成功后复查脏标记，仍脏就重建再传，最多 3 轮；轮次耗尽**不 clear 脏标记**，留给下一个去抖周期（:139-153、122-129、230）。快照构建时五张 Room 表在同一只读事务（CloudSnapshot.kt:186-196），DataStore 无多键事务由复查兜底（:185-190）。
- `withoutDirtyMarking`：恢复/清除成绩缓存期间抑制置脏并撤销在排的去抖任务，避免"半写状态被上传"（CloudSync.kt:90-111；恢复 :198-202；清缓存 SettingsViewModel.kt:174-191）。
- 401 统一映射 `CloudAuthException` → 清本地账号、停自动同步（CloudApi.kt:230-244；CloudSync.kt:156-162；SettingsViewModel.kt:312-314）。

### 4.3 换机识别与冲突三选一

- 方向决策是纯函数：探测失败→`NO_UPLOAD`；云端无备份→`UPLOAD`；云端有备份且本机无数据→`RESTORE`（换机/重装）；两边都有→`UNDECIDED` 弹三选一（SyncPlan.kt:91-111；测试 SyncPlanTest.kt:16-39）。
- 三选一文案与按钮：从云端恢复 / 用本机覆盖云端 / 暂不上传（UnifiedSyncViewModel.kt:393-407；UnifiedSyncScreen.kt:268-289）。
- **换机识别的口径只有 courses + grades**（`localHasData = courses.isNotEmpty() || grades.isNotEmpty()`，UnifiedSyncViewModel.kt:361）：只在本机建过日程/改过设置的新设备会被判为"无数据"→自动 RESTORE，本机新增内容会被云端覆盖。属边界风险，建议把 todos/自定义课/设置纳入判断或至少弹确认。
- 冲突策略本身是"整包后写覆盖、谁备份谁生效"，没有字段级/时间戳合并（CloudSync.kt:31-33）；手动"立即备份"会无条件覆盖云端（:118-120）。

### 4.4 恢复流程与恢复后提醒重排

1. `CloudSync.restore`：未登录直接抛；`getBackup` 后 `envelope.exists/data` 校验（CloudSync.kt:177-187）。
2. **强制验签**：`snapshot` 非空且 `BackupSignature.verify(snapshot, sig)` 通过才继续（:188-195）；失败抛"云端备份校验失败，已拒绝恢复"。
3. `CloudSnapshot.decode` 做 schema/规模校验；`sanitizeSemester` 清洗网络来的学期配置（坏日期整表丢弃、totalWeeks 夹 1..60、非法 firstMonday 置空、holidays/makeups 逐条剔除）（CloudSnapshot.kt:324-347；SemesterSanitizeTest.kt:18-76）。
4. 恢复前写回滚点 `filesDir/cloud-restore-rollback.json`（CloudSync.kt:197、214-220）。
5. `applyRestore`：Room 五表在一个事务里整包覆盖（四类课程源全删再插、节次/成绩/考试/日程 replace），随后十多个 DataStore 键逐个写（无多键事务），最后 `ClassReminderScheduler/ExamReminderScheduler/TodoReminderScheduler.reschedule` 各用独立 `runCatching`（CloudSnapshot.kt:250-296，重排在 :292-295）。
6. 恢复成功后清脏标记、`lastBackupAt = envelope.updatedAt`（CloudSync.kt:198-202）。

### 4.5 失败与竞态

- 恢复的 Room 部分单事务=全成全败；**DataStore 部分无事务**，进程中途被杀会留下"课程已换、设置半新半旧"——回滚文件本是为此准备，但全仓 `grep cloud-restore-rollback` 只有写没有读（CloudSync.kt:218、231），**没有任何 UI/代码入口能把回滚点用回来**。这是明确的可改进项（§8）。
- 恢复期间 `uploadMutex` 拦住并发上传，`withoutDirtyMarking` 抑制恢复自身置脏；但恢复后用户一旦编辑，下一次去抖上传即整包覆盖云端（符合"以本机为准"语义，只提醒一次）。
- 自动恢复（`RESTORE` 方向）不弹确认即整包覆盖本机（SyncPlan.kt:104-106）；仅当两边都有数据才询问，属于产品取舍，写交接时需保留该判断。
- 服务端 `exists=true` 但 `snapshot` 缺失/为空 → 按校验失败拒绝（CloudSync.kt:190-195），旧服务端若只返回 `data`（无 `snapshot`/`sig`）会导致所有恢复失败——升级服务端时必须同时上线 `snapshot+sig`。

---

## 5. 更新通道

**结论：客户端链路完整（验签→下载→摘要→安装），设计目标"验签失败整体作废、装包前再校验一次"已实现；剩余风险集中在服务端签名格式的严格约定、旧签名兼容期和密钥轮换。**

### 5.1 latest JSON 字段与签名覆盖

- 客户端模型 `LatestVersion`：`versionCode(int) / versionName / changelog / force(bool) / size(long) / url / sig / apkSha256`（CloudApi.kt:57-69），后端模块 `/api/bs/app/latest`（CloudApi.kt:39）。
- 仓库根 `latest_*.json` 是各版本响应样本（如 latest_1.5.3.json：versionCode 58、size 4455632、https 直链、apkSha256 64 hex、sig Base64；早期 latest_136.json 的 url 还是明文 http IP 直链——**旧客户端兼容遗留，现行客户端已无明文通道**）。
- 签名覆盖：见 §3.2（7 字段 canonical JSON；`apkSha256` 从 v2 起进入签名，由 sign_update.py 生成，tools/sign_update.py:7-16、90-120）。
- 客户端判定：`versionCode <= 本机` → UpToDate；只有 FULL 覆盖才把 `apkSha256` 传给下载器（SettingsViewModel.kt:246-253）；`force` 不可在自有源被篡改（验签失败整体忽略，:240-244）。

### 5.2 下载 / 续传 / 校验 / 安装

- 触发：`UpdateState.Available` 渲染页内卡片；`force` 额外弹不可关闭阻塞弹窗（ProfileScreen.kt:758-826、507-548）。
- 下载器：OkHttp（connect 15s / read 60s，UpdateInstaller.kt:99-103），写 `getExternalFilesDir(DOWNLOADS)/beikeschedule-update.apk.part`（:90-95）。
- 弱网：失败后按 `.part` 长度发 `Range: bytes=N-` 续传，共 5 次、每次退避 1s（:56-57、106-153）；服务端返回 200（忽略 Range）则删 `.part` 从头下（:134-139）；416 视作续传错位重来（:118-121）。
- 摘要：下载完成后流式计算 SHA-256 与签名覆盖值比对；不一致删包拒装（:155-163、209-220）；续传拼接错误由摘要兜住（注释 :40-42）。
- 安装：`tmp.renameTo(file)` 后 `FileProvider.getUriForFile("${applicationId}.fileprovider")`，`ACTION_VIEW` + `FLAG_GRANT_READ_URI_PERMISSION|NEW_TASK`（:165-178）；失败 Toast "请确认已允许本应用安装应用"（:176-178）。
- 权限/配置：`REQUEST_INSTALL_PACKAGES`（AndroidManifest.xml:11）、FileProvider `exported=false`+`grantUriPermissions=true`（:76-85）、`file_paths.xml` 只暴露 `external-files-path "."`（file_paths.xml:3-5）。
- 已知缺口：`canInstallInApp` 只要求 sha 非空 + url 以 `.apk` 结尾（UpdateInstaller.kt:73-74），不校验 url 必须与签名后的 https 域一致、也不校验 `size`；安装包签名一致性完全交给系统（同签名检查）。`.part` 跨版本残留只靠摘要/416 兜（:96-98、118-121）。

### 5.3 服务端必须保证的事项

1. 待签 canonical 字节与客户端**逐字一致**：键序、紧凑分隔符、无空格、`ensure_ascii=false`、布尔小写、数字无多余格式（UpdateSignature.kt:20-24、78-92；sign_update.py:43-47）。
2. `apkSha256` = APK 文件 SHA-256 的**小写 hex**；`size` 为字节数；`url` 为直链 `.apk`，且支持 `Range`（返回 206 + Content-Range）或至少 200 时客户端可从零重下。
3. 发布流程必须"先算摘要→再签名→再上传 APK"，顺序错一个字节都会导致客户端拒装。
4. 旧 6 字段签名只在迁移期可用；一旦服务端回旧格式，客户端会走浏览器链路且强更不可用（摘要不可信）。建议服务端固定 v2。
5. `force`/`changelog` 的可信来源只有包内公钥签名；服务端私钥必须离线（keystore/ 已 gitignore，UpdateSignature.kt:25）。

---

## 6. 服务端接口契约与客户端耦合点

**结论：`api.caeamer.com` 是 `/api/bs/*` 模块化 REST（无 Retrofit，手写封装，CloudApi.kt:26-31），客户端对字段极其宽容（`ignoreUnknownKeys`+默认值），所以"加字段"通常安全；但"改签名格式/改路径/删字段"会静默降级，必须服务端配合。**

| 接口 | 客户端 | 关键字段/语义 | 服务端必须配合的点 |
|---|---|---|---|
| `POST /api/bs/auth/login` | CloudApi.kt:92-110；调用 UnifiedSyncViewModel.kt:341-351 | 请求 `{xh,xm,jwTicket?}`（空票据不写字段，CloudApiLoginBodyTest.kt:15-29）；响应 `{token,xh,name?}` | 解 RSA-OAEP-SHA256 票据 → 调教务 `/user/me` 核实学号并即时丢弃；对旧版"仅学号+姓名"不再放行（CloudApi.kt:86-91）；token 需可撤销/过期策略（客户端无刷新） |
| `GET /api/bs/backup` | CloudApi.kt:119-124；CloudSync.kt:184 | 响应 `envelope{exists,updatedAt,sizeBytes,appVersionCode,data,snapshot,sig}`（CloudApi.kt:71-83；`schemaVersion` 在快照内部） | 必须返回**原始快照文本 `snapshot` + 覆盖它的 `sig`**；不得重新序列化；无备份时 `exists=false`；401 触发客户端清登录态 |
| `PUT /api/bs/backup` | CloudApi.kt:150-168 | 体 `{appVersionCode, data:<snapshot JSON>}` | 原样持久化 `data` 的字节/规范形式，供之后签名；返回非 2xx 且 `{message}` 会被客户端拼进提示 |
| `DELETE /api/bs/backup` | CloudApi.kt:170-178 | — | 客户端**当前无调用方**（死 API），服务端可保留；若下线不会影响 App |
| `GET /api/bs/class-schedule?xn&xq&cls` | CloudApi.kt:126-148；stepClassLab UnifiedSyncViewModel.kt:510-534 | `ClassSchedule{xn,xq,class,sessions[],hideUnscheduledContaining[]}`（ClassSchedule.kt:15-42） | 按"学年-学期+班级名"匹配；未命中返回空 `class`（客户端**不会**因此清空本地 LAB 行）；`sessions[].id` 变化会丢用户对该行的隐藏状态（taskId 即 id）；无 `id` 时客户端用内容合成稳定键（ClassSchedule.kt:44-74） |
| `GET /api/bs/app/latest` | CloudApi.kt:112-117；SettingsViewModel.kt:222-254 | `LatestVersion` + `sig`（§5.1） | 见 §5.3；该接口失败或验签失败 → 客户端回退 GitHub，不影响主功能 |
| `GET api.github.com/.../releases/latest` | SettingsViewModel.kt:262-299 | `tag_name/body/html_url` | 只是兜底展示，无强更、无摘要校验（APK 签名由系统兜底） |

**向后兼容细则**：所有 JSON 解析都是 `ignoreUnknownKeys`（CloudApi.kt:45；CloudSnapshot.kt:50-53；其余 DTO 均带默认值），所以服务端**新增字段**无需发版；**删除/重命名**字段会被默认值吞掉：latest 少 `versionCode/versionName` → 忽略自有源；envelope 少 `snapshot/sig` → 恢复失败；class-schedule 少 class → 视为未命中；login 少 token → 解析异常（"服务器响应格式异常"）。`BackupEnvelope.appVersionCode`、`sizeBytes` 客户端目前不使用（只展示），可空/可缺。

**模块约定**：`/api/bs` 与 showwe 社区同域不同模块（CloudApi.kt:26-31）；全站经 Cloudflare Tunnel 承接、源站无公网端口（README.md:47、CloudApi.kt:34-38）；旧版客户端仍走 `http://112.125.88.178` 的兼容入口（服务端保留，客户端已不再使用）。客户端 HTTP 层统一 `Authorization: Bearer`、401→`CloudAuthException`（CloudApi.kt:189-244）。

**客户端 / 服务端职责边界**：教务接口字段解析、校历/放假语义、课表网格、提醒排期全在客户端；服务端只做 ① 令牌与身份核验、② 快照存取与签名、③ 班级实验数据、④ 更新元数据与 APK 分发。因此**教务改版只需发客户端版**，但**更新/快照签名格式改动必须双端同步**。

---

## 7. 单测覆盖与缺口

已覆盖（纯 JVM，`app/src/test`，`isReturnDefaultValues=true` 见 app/build.gradle.kts:65-72；org.json 用真实实现 :109-111）：

- 二维码与 SSO：URL 拼接/编码（QrAuthApiUrlTest.kt:19-79）、sid 配对与保留（QrTargetTrackerTest.kt:16-81）、授权码多形态抽取（QrAuthCodeExtractTest.kt:15-67）。
- WebView 策略：导航白名单（JwNavPolicyTest.kt:18-53）、PAGE_FIX_JS 不变式（JwPageFixScriptTest.kt:18-39）、byyt 域判定（UnifiedSyncHostTest.kt:14-33）。
- 验签与密码学：更新 canonical/篡改/新旧覆盖/兜底实现参考向量（UpdateSignatureTest.kt:52-204）、备份验签（BackupSignatureTest.kt:23-47）、RSA-OAEP 参数与内置公钥（JwSessionTicketTest.kt:38-59）。
- 云契约：登录体字段（CloudApiLoginBodyTest.kt:15-29）、网络异常文案（CloudApiNetworkFailureTest.kt:21-66）、401 映射（CloudAuthExceptionTest.kt）、快照往返/规模/未知字段/超前 schema（CloudSnapshotTest.kt:16-77）、学期清洗（SemesterSanitizeTest.kt:18-76）。
- 编排与解析：同步方向/顺序/重试（SyncPlanTest.kt 全 176 行）、班级实验映射（ClassScheduleMapperTest.kt:29-80）、课表/成绩/考试/进度解析（JwParserTest 248 行、GradesParserTest 143、ExamsParserTest 71、CreditProgressParserTest 60）、SmartClass（SmartClassCryptoTest 154、SmartClassParserTest 185、FreeRoomRepositoryTest 188）。

**缺口（按风险排序）**：

1. **CloudSync 状态机零测试**：脏标记、8s 去抖、上传重建轮次、401 清账号、恢复失败路径都只能在真机上验证（需注入 api/clock 才能纯 JVM 测）。
2. **更新下载器零测试**：Range 续传/416/200/摘要不符/rename 失败、`canInstallInApp` 边界；可抽 `Downloader`+`DigestVerifier` 注入 MockWebServer 覆盖。
3. **WebView 与桥的真实行为无 instrumentation 测试**：origin 白名单、document-start、`shouldInterceptRequest` 截 sid、导航策略都只在 JVM 测了纯逻辑，平台行为靠人工。
4. **二维码长轮询循环无测试**：只测了 URL 与配对；`ensurePolling` 的状态码分支/超时/pin 逻辑无回归（可抽纯函数状态机）。
5. **TokenCipher 无测试**（依赖 AndroidKeyStore；需 Robolectric/仪器测试，或抽接口 mock）。
6. **CloudApi HTTP 层无 MockWebServer 契约测试**：错误体 `{message}`、401、非 JSON 响应、超时映射只有纯函数测试。
7. **备份签名缺"发布公钥 ↔ 服务端私钥"参考向量**（UpdateSignatureTest 有 OpenSSL 向量，BackupSignatureTest 只测自造密钥），密钥配错要等真机恢复才发现。
8. **无服务端契约测试/夹具**：latest/backup envelope 的字段兼容、签名格式漂移无自动化防线。
9. `JwWebView`/`UnifiedSyncViewModel` 的"幽灵同步""旋转重建"等时序问题只能靠代码审查（历史 R5 已修，但无回归）。

---

## 8. 维护性风险与优化建议（按影响排序）

| # | 优先级 | 问题 | 证据 | 建议 |
|---|---|---|---|---|
| 1 | **P0 隐私/合规** | 首次"登录并同步"在云端无备份时自动 `UPLOAD` 并 `setCloudSyncEnabled(true)`，上传前无单独同意；且该流程**无条件**登录云账号，即使用户从没开过云同步 | SyncPlan.kt:99-108（`!cloudHasBackup -> UPLOAD`）、UnifiedSyncViewModel.kt:536-549、:329-351；README.md:32、79 | 首次同步前弹一次"是否把数据备份到开发者服务器"（或至少把云端方向写进同步页横幅）；把 README 措辞与实现对齐；给"仅导入、不登录云"的选项 |
| 2 | **P0 数据安全** | 云恢复回滚点只写不读，进程在 DataStore 半写阶段被杀后无 UI 恢复入口 | CloudSync.kt:197、214-220、231（全仓无读取方） | 恢复失败/异常时提供"撤销本次恢复"入口；或在设置页提供回滚文件导出；恢复成功后清理 |
| 3 | **P1 数据安全** | 恢复不校验快照时效：`updatedAt`/`appVersionCode` 未参与判断，签名有效的旧快照可被重放 | CloudSync.kt:183-202；CloudApi.kt:71-83（字段存在但未用） | 恢复前比较 `updatedAt` 与本机 `cloudLastBackupAt`，更旧时提示；`appVersionCode` 用于"新版本快照"提示 |
| 4 | **P1 隐私** | 云快照含学号/姓名/学院/全部成绩（含排名）/考试座位号，服务端明文可读；无 E2E 加密 | CloudSnapshot.kt:36-46、107-138；README.md:79-81 | 至少在文档/隐私政策明确"服务端可读"；长期评估客户端加密快照（会牺牲服务端校验能力） |
| 5 | **P1 可用性** | 全链路强依赖教务页面结构，脚本随 APK 发布，v1.0 计划的"脚本远程热更新"未实现 | app/src/main/assets/import/*；docs/TECH_DESIGN.md:157；§2.2 表 | 实现"签名保护的远程脚本 + assets 兜底"；或至少在脚本里做兼容版本探测 + 失败自检上报（当前失败只有 logcat 与中文 Toast） |
| 6 | **P1 性能** | 解析（`JwParser.parseCourses/parseWeekCalendar`、`GradesParser`、`ExamsParser`）在 ViewModel 默认调度器（主线程）执行；`jwTicket()` 也在主线程做 CookieManager+RSA | UnifiedSyncViewModel.kt:409-450、452-501、674-679 | 解析搬 `Dispatchers.Default`，票据生成搬 IO；顺带把 30s 超时改成"脚本心跳/进度"驱动 |
| 7 | **P1 更新安全** | 旧 6 字段签名永久兼容 + 浏览器兜底链路可打开 http url，`apkSha256` 不生效 | UpdateSignature.kt:144-150；ProfileScreen.kt:832-841 | 服务端固定 v2 后删除 LEGACY；`canInstallInApp` 增加 https+域校验；无摘要时不再提供"应用内获取" |
| 8 | **P2 健壮性** | `AbortSignal.timeout`/document-start 等 WebView 能力失败被静默吞（runCatching/老 WebView），`evaluateJavascript` 无返回 | JwWebView.kt:193-199；jw_import.js:38；UnifiedSyncScreen.kt:143 | 注入前做能力探测并给用户"系统 WebView 需更新"提示；脚本首行先 `send('onStarted')` 心跳 |
| 9 | **P2 一致性** | 换机识别只看 courses/grades，本机仅有日程/设置时会被自动 RESTORE 覆盖 | UnifiedSyncViewModel.kt:361；SyncPlan.kt:104-106 | 把 todos/自定义课/设置纳入 `localHasData`，或该场景降级为三选一 |
| 10 | **P2 安全加固** | 桥 origin 白名单放到整个 `*.ustb.edu.cn`（三个脚本实际只在 byyt 跑） | JwWebView.kt:37、341-342 | 收紧到 `https://byyt.ustb.edu.cn`，降低被 XSS 子域伪造结果的概率（改前确认无其它桥使用方） |
| 11 | **P2 密钥运维** | 四把公钥/密钥硬编码进 APK，轮换必须发版；无 key id/过期 | UpdateSignature.kt:40-47；BackupSignature.kt:23-24；JwSessionTicket.kt:38-45 | 写 runbook（离线私钥保管、轮换发版窗口、双钥并行期）；latest/backup 响应加 `keyId` 便于灰度 |
| 12 | **P3 清理** | `CloudApi.deleteBackup` 无调用方；`BackupEnvelope.sizeBytes/appVersionCode` 未用；`JwParser.rowErrorLogger` 只在 logcat | CloudApi.kt:170-178；CloudApi.kt:71-83；UnifiedSyncViewModel.kt:122-127 | 删除死 API；把快照元数据用于 UI（如"云端备份于…"）；解析失败计数上报 |
| 13 | **P3 文案/文档** | 多处注释/README 与实现有细微出入（如"opt-in"、脚本远程更新计划、旧页面已合并） | README.md:32、79；docs/TECH_DESIGN.md:157、95 | 交接后统一校正；本文件已标注主要差异 |

**给接手的第一个迭代建议**：① 做 #1 的同意弹窗（产品+合规风险最高、改动最小）；② #2 回滚入口；③ #3 恢复时效；④ 抽 CloudSync/UpdateInstaller 纯逻辑补单测；⑤ 把"脚本能力探测+心跳"做了，之后教务改版排障成本会大幅下降。

---

## 9. 速查：文件 → 职责

| 文件 | 职责 | 关键行 |
|---|---|---|
| `import/JwWebView.kt` | WebView 装配、PAGE_FIX_JS、导航白名单、桥注册、sid 截获、错误上报、清 session | 37、57、181-192、244-272、327-336、369-409、425-435 |
| `import/JwBridge.kt` + 三个 Bridge | JSON 信封分发与三个脚本的参数映射 | JwBridge.kt:28-39；JwImportBridge.kt:18-28；GradesBridge.kt:23-35；CloudLoginBridge.kt:14-20 |
| `assets/import/jw_import.js` | 课表/节次/校历（含天级 offDays）抓取 | 22-26、69-117、119-170 |
| `assets/import/jw_grades.js` | 成绩/GPA/学籍/考试/学业进度并发抓取 | 64-78、80-107 |
| `assets/import/jw_identity.js` | 学号姓名（user/me + queryxsxx 兜底） | 29-34、59-74 |
| `import/parser/JwParser.kt` | 课表/节次/学期/校历 JSON→Entity，脏数据防御 | 36-58、91-139、141-187 |
| `import/parser/GradesParser.kt` | 成绩/GPA/学籍快照 | 46-114 |
| `import/parser/ExamsParser.kt` | 考试 + KSSJMS 时间解析 | 30-79 |
| `import/parser/CreditProgressParser.kt` | 学分类别要求/毕业总进度 | 42-91 |
| `data/remote/QrAuthApi.kt` | 微认证状态轮询、授权 URL、sid 跟踪器 | 62-147、157-210 |
| `data/remote/CloudApi.kt` | `/api/bs/*` 手写 REST、401 映射、用户文案 | 38-42、92-178、205-244 |
| `data/remote/JwSessionTicket.kt` / `TokenCipher.kt` | RSA-OAEP 票据 / Keystore AES-GCM token | 35-67 / 23-79 |
| `data/remote/Ed25519.kt` / `UpdateSignature.kt` / `BackupSignature.kt` | 双实现验签与两条签名链路 | 50-80 / 136-151 / 27-41 |
| `data/remote/ClassSchedule.kt` | 班级实验/上机下发→课程行 | 15-42、44-74 |
| `data/remote/SmartClass*.kt` | 无课教室（独立站点、签名与 AES-CBC 配置解密） | Crypto:29-32、88-121；Api:81-131 |
| `data/backup/CloudSnapshot.kt` | 快照 schema DTO + build/applyRestore + 清洗 | 36-80、183-296、324-347 |
| `data/backup/CloudSync.kt` | 脏标记/去抖/上传/恢复/回滚点 | 51-111、130-165、177-220、229-231 |
| `ui/sync/UnifiedSyncViewModel.kt` | 全流程编排、扫码轮询、7 个 step | 252-281、283-309、329-563、572-658 |
| `ui/sync/UnifiedSyncScreen.kt` | WebView 宿主、桥→VM、授权导航看门狗、QR 抑制脚本 | 106-145、198-238、254-292、400-418 |
| `ui/sync/SyncPlan.kt` | 步骤/方向/重试纯逻辑（有完整单测） | 10-19、51-88、91-111 |
| `ui/settings/SettingsViewModel.kt` | 更新检查（自有源验签→GitHub 回退）、云同步手动操作、清缓存 | 202-299、91-191 |
| `ui/settings/UpdateInstaller.kt` | 应用内下载/续传/摘要/安装器 | 73-74、93-186、209-220 |
| `tools/sign_update.py` | 服务端签名参考实现（canonical 规范） | 40-47、90-120 |
| `app/src/main/res/xml/*.xml` + `AndroidManifest.xml` | 明文禁令、备份排除 app_webview、FileProvider、安装权限 | network_security_config:8-10；backup_rules:7-18；data_extraction_rules:7-17；Manifest:11、76-85 |
