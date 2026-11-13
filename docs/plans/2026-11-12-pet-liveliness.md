# 宠物活力优化 · 实施与验证记录（2026-11-12）

分支：`codex/pet-liveliness`（自 main `8d8f880` / v2.1.1）。设计见 [2026-11-12-pet-liveliness-design.md](2026-11-12-pet-liveliness-design.md)。
范围：用户实测反馈的三项优化，本地提交，不合并/推送/发版。

## 1. 落地内容

### 1.1 话语多样性

- `PetDialogue.kt`：新增 `proactiveTopic`（安排 3 / 建议 2 / 热点 2 / 闲聊 3 / 记忆 1 加权轮换，不连续重复；无授权数据时安排+建议权重并入闲聊，无联网/记忆时对应主题不参选）、`proactivePrompt` / `hotspotPrompt`（按主题生成差异化 prompt）、`tooSimilar`（去标点字符 bigram Jaccard ≥ 0.6）。
- `PetWelcomeLines.message` 增加 `recentIds`，按 `welcome-line:<index>` 去重，池空回退全池；本地欢迎产生后 `rememberLine`。
- `PetAiEngine.kt`：新增 `recentBubbleLines`，proactive 分支把最近 ≤6 条 assistant 文本（各截 200 字）作为"最近已说过（禁止重复内容或相似句式）"清单注入 user prompt；清单只含本机已生成文本。
- `PetOverlay.kt` `casual()`：主题轮换驱动 prompt；AI 回复与最近 6 条气泡做相似度检测，命中重试一次（仍相似则弃用）；AI 失败/超时落本地去重池，不再固定一句话兜底。

### 1.2 热点专用触发

- `PetAiModels.kt`：`hotspotAvailable(state)` —— OFF 关；PROVIDER 下 MiMo/DeepSeek 可用、CUSTOM 排除；独立 DEEPSEEK 搜索需 searchKey。
- `PetOverlay.kt`：主 5s tick 增加热点分支。进入课表后 90–180s 随机首播，之后按 `hotspotMinutes`（5–1440，默认 30）间隔；条件：chatter 开、AI configured、hotspotAvailable、无气泡、无更高优先级课程/考试提示、无进行中 casualJob、presence==VISIBLE。热点 prompt 强制走搜索工具、失败坦白不编造；成功失败都重置计时。
- AI 气泡统一 `withTimeoutOrNull(25_000)`（此前无外层超时，引擎内部 150s 会长时间占位）。
- 隐私不变量未动：公共主题查询、学习数据不进搜索、来源与获取时间保留、仅前台可见运行、退后台/开聊天/配置变更取消。

### 1.3 走动与翻页动画

- 走动循环 key 改为 `(width, height, environment, dragging)`：气泡出现/消失、每次触摸不再重启计时（`bubble`/`quietUntil`/`presence` 经 `rememberUpdatedState` 读取）；idle 10–22s → 6–14s；路径失败换目标重试 ≤2；约 1/3 概率原地微动作（PERCH/边缘 LEAN 需 free 校验，或转向）。
- `PetSpace.choose`：约 1/3 概率全空闲落点均匀随机，其余保持"远离今日课程取最远 8 个"；停靠仍仅今日真实课程。
- `ScheduleScreen.kt`：`paused` 移出 `pagerState.isScrollInProgress`，新增 `pageTurning` 参数；弹层/退后台仍整体卸载，位置恢复语义不变。
- `PetOverlay.kt` `PetPresence { VISIBLE, EXITING, AWAY, ENTERING }` 状态机：
  - 翻页开始：220ms 滑向最近水平屏幕边缘 + 淡出 → AWAY（停在屏幕外，alpha 0，不接收触摸）。
  - 新页稳定（environment 更新）：选目标；左半区目标从节次分界线（x = sectionWidth − petSize）探出，右半区从右屏幕外；240ms 滑到边界空闲落点（逐行找 free，首选目标行，本侧无落点换另一侧，两侧都没有则直接放置），再沿 `PetSpace.route` 避障走到目标。入场段只穿越保护带，不穿卡片。
  - 动画中途被拖动：`onDragStart` 立即落位接管，状态机协程按 presence/dragging 守卫自行收尾；连续快速翻页由 effect key 取消旧动画、从中断位置继续。
  - 动画期间禁气泡/闲聊/欢迎；自动重定位 effect 在非 VISIBLE 时跳过。

## 2. 行为契约变更（测试同步）

- `PetSpaceTest`：旧断言"20 个种子全部远离今日课程"改为新契约——100 个种子全部不与卡片/保护带重叠，且 ≥70% 远离今日课程（1/3 随机漫游是计划内变更）。
- `PetInteractionTest` 新增：`weekFlipAnimatesPetBackIntoContent`（翻周后 15s 内宠物完整回到屏幕内）、`petMovesOnItsOwnWithoutInteraction`（无交互 45s 内位置/姿态变化）。
- 新增 `PetLivelinessTest`（6 项）：主题轮换、相似度、欢迎去重、hotspotAvailable 矩阵、recentBubbleLines、prompt 差异化。

## 3. 验证结果

| 检查 | 结果 |
|---|---|
| `testDebugUnitTest` | **490/490 通过**（484 基线 + 6 新增），0 失败 |
| `node --test app/src/test/js/*.test.cjs` | **15/15 通过** |
| `lintDebug assembleDebug` | 通过，Lint **0 errors / 46 warnings / 4 hints**（warnings 与基线一致） |
| 设备回归 9 类（Pixel_10_Pro AVD，API 17 镜像） | **39/39 通过**（37 基线 + 2 新增），0 失败 |
| `minifyReleaseWithR8` | 通过（BUILD SUCCESSFUL，1m39s） |

过程中修复：Kotlin 字符串模板 `$address听` 被解析为标识符（汉字是合法标识符字符），统一改 `${address}`；管道 `| tail` 会掩盖 gradle 退出码，改为重定向 + 显式 `GRADLE_EXIT` 核验。

## 4. 未验证 / 边界

- 真实供应商 Key、真实搜索接口与用户手机实测仍未完成；热点与多样性逻辑经 JVM 纯函数测试与模拟路径验证，不能表述为真实模型联调。
- 模拟器回归不能代替真机；入场动画的视觉节奏（220/240ms）建议用户手机实测后按需微调。
- 未改：服务端、更新声明、数据库、核心设置/云快照、提醒调度、宠物素材、加密存储格式；未新增设置项。

## 5. 发布交付（2026-11-13，用户授权）

- 快进合入 main 并推送；发布提交 `ceafe0a`（v2.1.2 / versionCode 69），tag `v2.1.2`。
- 发布重跑：490 项 JVM、15 项 JS、Lint 0 errors / 46 warnings、正式签名 assembleRelease 通过；aapt2 核对包名/版本，apksigner 证书延续 v2.1.1（SHA-256 `53acb30e…`）。
- APK 8,336,361 字节，SHA-256 `3443515d9be331c0807926310c55e9ad7ae26ce5698ccfaec4e77771345e8aeb`；更新声明 596 字节，App 内公钥验签通过。
- GitHub Release：https://github.com/coderirse/BeikeSchedule/releases/tag/v2.1.2 （同一份 APK）。
- 服务器：APK 与声明经 Workbench 上传，服务器端 SHA-256 与本地一致；旧声明备份 `/opt/beike/bs-app-version.v2.1.1.bak.json`；beike 重启后 active、/health db:true。
- 公网：`/api/bs/app/latest`（okhttp UA）与本地声明逐字段一致；公网 APK 完整下载大小与 SHA-256 一致。
- 未验证：真实供应商 Key、真实搜索接口与用户手机实测。

## 6. 后续

发版已完成。后续改动按 AGENTS §6 走完整流程并递增版本。