# 宠物活力优化设计（2026-11-12）

分支：`codex/pet-liveliness`（自 main `8d8f880` / v2.1.1）。
状态：设计待确认，未动代码。

## 1. 背景与目标

用户手机实测 v2.1.1 后反馈三点：

1. 宠物读取 App 信息后的主动话语**重复度高**。
2. 宣传过的**热点播报从未出现**。
3. 宠物**走动太少**；翻周时宠物**闪现消失、闪现出现**，希望翻到新页后从屏幕外或界面分界线处**动画入场**。

目标：在不改动核心课表/同步/提醒语义、不新增设置项、不扩大隐私面的前提下，让宠物"说得多样、报得出热点、走得自然、翻页有进出动画"。

## 2. 现状诊断（证据）

### 2.1 重复度

- AI 主动气泡每次用同一条 prompt（[PetOverlay.kt:136-137](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetOverlay.kt#L136)）："对X主动说一句……避免与本机聊天记录重复"。没有主题区分，小模型倾向反复输出同类鼓励句。
- 引擎 proactive 分支只带 `history().takeLast(6)`（[PetAiEngine.kt:89](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetAiEngine.kt#L89)）。气泡虽写入历史（[PetAiViewModel.kt:94-95](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetAiViewModel.kt#L94)），但"避免重复"只是软指令，没有显式禁说清单，也没有生成后的相似度兜底。
- 本地欢迎池每个时段仅 3–4 条，`pool.random()` 无去重（[PetDialogue.kt:78-95](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetDialogue.kt#L78)）；欢迎气泡也不走 `rememberLine`，`recentLines` 对欢迎完全失效。
- 本地闲聊池有 12 条 ID 去重（[PetDialogue.kt:132-137](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetDialogue.kt#L132)、[PetViewModel.kt:57](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetViewModel.kt#L57)），这条路径尚可；问题集中在 AI 路径与欢迎。

### 2.2 热点 never

- `searchMode` 默认 `OFF`（[PetAiModels.kt:63](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetAiModels.kt#L63)）→ 工具列表里根本没有搜索工具（[PetAiEngine.kt:56-60](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetAiEngine.kt#L56)）。
- 即便用户开了联网，主动 prompt 只说"按需选择……公开热点"，是否调用完全由模型决定；欢迎 prompt 还明确"不要每次都查新闻"。小模型在"随便说一句"的指令下几乎不会主动调搜索。**没有任何专用热点触发路径**，`hotspotMinutes`（默认 30）只用作缓存 TTL，从未驱动主动播报。
- 独立搜索 Key 未配置时工具返回提示文案（[PetAiEngine.kt:146](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetAiEngine.kt#L146)），模型多半转回闲聊，用户无感知。

### 2.3 走动与翻页

- 走动循环 `LaunchedEffect(width, height, dragging, bubble, interactionEpoch, environment)`（[PetOverlay.kt:216](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetOverlay.kt#L216)）：**气泡出现/消失、每次触摸、每次翻周都会重启循环**，10–22s 的等待计时不断被清零。`petInteractionEpoch` 在父节点任何按下时自增（[ScheduleScreen.kt:370](../../app/src/main/java/com/caeamer/beikeschedule/ui/schedule/ScheduleScreen.kt#L370)），用户正常操作课表就足以让宠物几乎永远走不起来。
- 气泡显示期间（8s）直接 `return`，不走路；路径规划失败后再等整轮 10–22s。
- `PetSpace.choose` 原本按"远离今日课程"排序取最远 8 个随机（[PetEnvironment.kt:102-106](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetEnvironment.kt#L102)），活动范围偏小。
- 翻页闪现：`paused` 含 `pagerState.isScrollInProgress`（[ScheduleScreen.kt:441](../../app/src/main/java/com/caeamer/beikeschedule/ui/schedule/ScheduleScreen.kt#L441)），滑动开始整个 PetScene 卸载（[PetOverlay.kt:89](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/PetOverlay.kt#L89)），滑动结束重新挂载 → 原地闪现。位置 `rememberSaveable` 恢复，无过渡。

## 3. 技术方案

### 3.1 话语多样性（问题 1）

**a. 主题轮换。** 新增纯函数 `proactiveTopic(last: String?, searchEnabled: Boolean, hasMemories: Boolean, random: Random): String`，候选与权重：

| 主题 | 权重 | 条件 | prompt 要点 |
|---|---|---|---|
| arrangement 近期安排 | 3 | 有已授权学习数据 | 读一项授权数据，结合今天/明天安排说一句 |
| study 学习建议 | 2 | 有已授权学习数据 | 结合数据给一条小建议，不说教 |
| hotspot 公开热点 | 2 | 联网可用（见 3.2） | 走专用热点路径 |
| casual 闲聊 | 3 | 始终 | 轻松搭话，可结合人格/记忆 |
| memory 记忆相关 | 1 | 有长期记忆 | 自然提起一条记忆，不暴露原文清单 |

规则：不与上一次主题相同；无授权数据时 arrangement/study 权重并入 casual。`lastTopic` 存 PetScene `remember`（退后台重置可接受）。prompt 按主题生成，替换现有固定文案；欢迎 prompt 保持独立。

**b. 显式禁说清单。** 引擎 proactive 分支从已选历史中提取最近 ≤6 条 assistant 文本，纯函数 `recentBubbleLines(history, limit)` 生成清单，追加到 user prompt 末尾："最近已说过（禁止重复内容或相似句式）：……"。清单只含本机已生成文本，不引入新数据面。

**c. 相似度兜底。** 纯函数 `tooSimilar(a: String, b: String): Boolean`：去标点空白后按字符 bigram Jaccard ≥ 0.6 判相似。`casual()` 拿到 AI 回复后与最近 6 条气泡文本比较；命中则带"换个完全不同的说法"重试一次；仍命中或重试失败 → 落本地池。阈值与算法 JVM 可测。

**d. 欢迎去重。** `PetWelcomeLines.message` 增加 `recentIds: Set<String>` 参数，按 `welcome-line:<index>` 过滤（与本地池同机制），池空时回退全池。欢迎气泡产生后调用 `vm.rememberLine(greeting.id)`（AI 欢迎与本地欢迎都记）。

### 3.2 热点专用触发（问题 2）

**可用性判定。** 纯函数 `hotspotAvailable(state: PetAiState): Boolean`：

- `searchMode == OFF` → false；
- `PROVIDER + MIMO` → true（原生 web_search）；
- `PROVIDER + DEEPSEEK` → true（用 provider Key）；
- `PROVIDER + CUSTOM` → false（引擎本就拒绝该组合）；
- `DEEPSEEK` 独立搜索 → `searchKey.isNotBlank()`。

**触发器。** PetScene 主 5s tick 循环内新增热点分支，条件全部满足才触发：`prefs.chatter`、AI configured、`hotspotAvailable`、`bubble == null`、无更高优先级课程/考试提示、无进行中 casualJob、`tick >= nextHotspot`、非拖动/入场动画中。

- 首次延迟：进入课表后 90–180s 随机（让用户尽快见到一次），之后间隔 `hotspotMinutes * 60_000`（沿用现有设置，5–1440 分钟）。
- 触发时调用 `casual(hotspot = true)`：prompt 明确要求"调用 search_public_news（MiMo 用原生搜索）取一条公开热点，用一两句话讲给同学，必须基于返回来源，失败就坦白说没查到并转闲聊"。主题轮换中的 hotspot 权重也走同一 prompt。
- 成功/失败都重置 `nextHotspot`；失败不编造（引擎现有约束不变），气泡可落回普通闲聊。
- 优先级低于 COURSE/EXAM 提示；热点请求与聊天共用引擎 gate，排队可接受。

**超时兜底。** AI 气泡调用包 `withTimeoutOrNull(25_000)`（现状无超时，引擎内部 150s 会长时间占位）；超时/异常落本地池。取消语义不变：退后台、开聊天、配置变更随现有 LaunchedEffect/引擎 guard 取消。

**隐私不变量。** 搜索查询仍只用公共主题词（引擎现有 `"${LocalDate.now()} $topic 热点 新闻"`）；学习数据不进搜索；来源与获取时间保留；联网仅在原前台可见条件下运行，不新增后台轮询或通知。

### 3.3 走动与翻页动画（问题 3）

**a. 走动循环重构。**

- key 改为 `(width, height, environment)`；`dragging`、`bubble`、`quietUntil` 经 `rememberUpdatedState` 在循环内读取。气泡期间**跳过本轮**（短 delay 后继续），不重启计时；`interactionEpoch` 不再参与 key。
-  idle 间隔 10–22s → **6–14s**；路径失败立即换一个目标重试（至多 2 次），再进入短等待。
- **原地微动作**：每轮约 1/3 概率不位移，改为原地换姿态（PERCH/LEAN，需 `PetSpace.free` 校验新姿态）或转向；素材复用现有 rest/walk 行，无新资源。
- `PetSpace.choose` 增加约 1/3 概率从全部空闲落点随机（而非总取远离今日课程的最远 8 个），扩大活动范围；**停靠（PERCH on card）仍只允许今日真实课程**，规则不变。

**b. 翻页进出动画。**

- `SchedulePetHost` 参数拆分：`paused`（弹层/后台，维持整体卸载）与新增 `pageTurning: Boolean`（= `pagerState.isScrollInProgress`，从 paused 移出）。`suppressWelcome` 不变（翻页本就不触发欢迎）。
- PetScene 在 pageTurning 期间**保持挂载**，新增状态机 `PetPresence { VISIBLE, EXITING, AWAY, ENTERING }`：
  - `VISIBLE → EXITING`（pageTurning=true）：220ms 内滑向最近水平屏幕边缘并淡出，walking 帧；结束置 AWAY（alpha 0，不接收手势）。
  - `AWAY → ENTERING`（pageTurning=false 且 environment 已更新到新周）：`PetSpace.choose` 选目标；按目标位置选入场口——目标在左半区 → 从**左侧节次分界线**（x = sectionWidth − petSize，时间列后）探出；右半区 → 从右屏幕外。入场段先 200ms 滑到边界处的"落点"（该点必须 `free`，y 取目标行或最近空闲行），随后沿 `PetSpace.route` 正常避障走到目标，walking 帧。入场段只穿越保护带（时间列/屏幕边缘），**不穿越课程卡片**。
  - `ENTERING → VISIBLE`：写回 normalizedX/Y，恢复 idle/气泡逻辑。
- 动画期间用 `animOffset: Offset?` 覆盖渲染位置；`LaunchedEffect(environment,…)` 的自动重定位在非 VISIBLE 时跳过，避免吸附打断动画。连续快速翻页：单一 LaunchedEffect(pageTurning, environment) 取消旧动画，从当前渲染位置继续退出/入场。
- 首次放置（placed=false）可选复用同一入场动画（低成本加分项，实现时若复杂度可控则做）。
- 弹层/退后台仍走 paused 卸载老路径，**位置恢复语义不变**（现有 `pausingStopsPetAndResumingRestoresItsPosition` 测试继续通过）。

**c. 对现有测试的影响。**

- `PetInteractionTest.petDoesNotTakeOverCourseCreationOrWeekSwipe`：只断言弹层时宠物不存在、翻周后课程可点——兼容（翻周不再卸载宠物，但该测试未断言翻周中宠物不存在）。
- `pausingStopsPetAndResumingRestoresItsPosition`：STARTED/RESUMED 路径不变——兼容。
- 新增设备断言：翻周后宠物在限时内重新可见且落在内容区（入场完成）；无交互等待 ≤30s 位置/姿态发生变化（走动/微动作生效）。

## 4. 改动清单

| 文件 | 改动 |
|---|---|
| `ui/pet/PetDialogue.kt` | 欢迎池去重；新增 `proactiveTopic`、`tooSimilar`、主题 prompt 生成（纯函数） |
| `ui/pet/ai/PetAiEngine.kt` | proactive 注入 `recentBubbleLines` 禁说清单 |
| `ui/pet/ai/PetAiModels.kt` | `hotspotAvailable` 纯函数（或放引擎文件，随实现定） |
| `ui/pet/PetOverlay.kt` | casual 主题轮换+相似度重试+25s 超时；热点计时分支；走动循环重构+微动作；PetPresence 进出动画；pageTurning 参数 |
| `ui/schedule/ScheduleScreen.kt` | paused / pageTurning 拆分 |
| `app/src/test/.../PetLivelinessTest.kt`（新） | 主题轮换、相似度、欢迎去重、hotspotAvailable、recentBubbleLines |
| `app/src/test/.../PetHintsTest.kt` | 欢迎去重回归补充 |
| `app/src/androidTest/.../PetInteractionTest.kt` | 翻页入场与走动新断言（或新增 PetEntranceTest） |
| `docs/plans/2026-11-12-pet-liveliness.md`（新） | 实施与验证记录（实现后写） |
| `AGENTS.md` §7 | 完成后同步快照 |

不改：服务端、更新声明、数据库、核心设置/云快照、提醒调度、宠物素材、加密存储格式。不新增设置项（热点开关复用 searchMode，频率复用 hotspotMinutes）。

## 5. 约束与风险

- 宠物仅在 RESUMED 且无弹层运行；翻页动画不是弹层，不违反该约束。气泡纯绘制、触摸穿透、关闭按钮独立处理——全部保留。
- 自动路径不穿卡片；入场段落点必须 free；停靠仅今日真实课程。
- AI 联网仅前台可见；聊天关闭/退后台/停止取消底层请求；搜索失败不冒充实时；公共主题查询；密钥/学号/会话不进上下文与日志。
- 风险：小模型对"禁说清单"遵循度有限 → 相似度兜底+本地池保证下限；热点依赖用户已配置联网与 Key → 未配置时静默走闲聊，不提示骚扰；入场动画与 environment 更新时序 → 以 environment key 驱动 ENTERING，测试覆盖快速连续翻页。
- 低内存机器：模拟器 `-memory 2048`、构建 `--max-workers=2`，构建与模拟器错峰。

## 6. 验证计划

1. `./gradlew testDebugUnitTest --console=plain`（含新 PetLivelinessTest）
2. `node --test app/src/test/js/*.test.cjs`
3. `./gradlew lintDebug assembleDebug --console=plain`
4. 设备回归 9 类 + 新增断言：`connectedDebugAndroidTest`（PetBubbleTouchTest、PetInteractionTest、PetWelcomeInteractionTest、PetPoseRenderTest、TodoInteractionTest、WheelPickerInteractionTest、NavigationInteractionTest、PetAiSettingsInteractionTest、PetAiChatInteractionTest）
5. `./gradlew minifyReleaseWithR8 --console=plain`
6. 热点/多样性用模拟供应商响应验证（沿用现有 fake 手段）；**真实 Key 与用户手机实测仍由用户完成**，不声称为真实联调。

## 7. 范围边界

本批只做上述三项优化与回归，本地提交到 `codex/pet-liveliness`；**不自动合并/推送/发版**，等用户明确指令。