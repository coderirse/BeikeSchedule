# 宠物聊天滚动修复（2026-11-27）

分支：`codex/pet-chat-scroll`（自 main `c7a4675` / v2.1.2）。
用户报障：① 打开宠物聊天默认停最早的对话，要看最新得手动滑很久；② 滑到最底后仍有此前出现过的抖动抽搐。

## 1. 根因

**停在最早**：[PetChatSheet.kt](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetChatSheet.kt) 用 `rememberLazyListState()`（默认索引 0），自动滚底 effect 只在"已靠近底部（`last >= total - 3`）"时触发——首次打开时列表在顶部，条件永远不成立；且消息异步加载，布局完成时也没有二次跳转。

**底部抖动**：复刻了 `5523eb9`（v1.1.9 无固定时间课程弹层）修过的自激回路——消息列表滚到尽头后剩余滑动速度经 nested scroll 传给 ModalBottomSheet 拖动弹层 → 半展开/展开高度变化 → LazyColumn 重新测量 → 再触发；同时自动滚底用 `scrollToItem(最后一条)` 把**最后一条的顶部**锚在视口上沿，流式长回复时每个 token 都把视图拽回上沿，与用户手动下滑互相打架。

## 2. 修复

均为 [PetChatSheet.kt](../../app/src/main/java/com/caeamer/beikeschedule/ui/pet/ai/PetChatSheet.kt) 内改动：

1. **贴真正的底部**：`snapToBottom()` = `scrollToItem(最后一条, 1_000_000)`，测量端 clamp 到内容末尾——最后一条高于视口时也露出末尾，而不是只把顶部锚在视口上沿。
2. **打开/切会话一次性跳底**：`initialJumpDone`（按 conversation 重置）+ 最多 3 帧"滚动-等布局-复查"循环，兼容异步加载与"切会话后列表尚未重排"的时序；切回旧会话也会重新贴底。
3. **跟随状态**：`followBottom` 默认开；`DragInteraction.Start` 时暂停（用户翻旧消息不被拽回），松手回到底部或 `snapshotFlow { reachedBottom() }` 变真时恢复；发送/重试显式置开；视口高度变化（键盘、弹层展开收起）时跟随状态继续贴底。
4. **切断抖动回路**：消息列表 `nestedScroll` 消费 post-scroll/post-fling（余速不再拖弹层）+ `LocalOverscrollFactory provides null` 关闭列表自身回弹（沿用 v1.1.9 验证过的三层修复中的后两层，保留半屏/全屏两种锚点）。

## 3. 测试

- 新增 `openingLongConversationStartsAtLatestMessage`：预置 30 条长消息，断言打开即显示"历史第30条"（旧实现停在顶部，该条不会被组合）。
- 新增 `streamedReplyTailStaysVisibleAtBottom`：流式返回超长回复，断言尾部文本的底边不超出消息列表视口底边（旧实现把顶部锚在视口上沿，尾部在折叠线以下）。
- 现有 4 条聊天用例不变。

## 4. 验证

| 检查 | 结果 |
|---|---|
| `testDebugUnitTest` | **490/490 通过**，0 失败 |
| `node --test app/src/test/js/*.test.cjs` | **15/15 通过** |
| `lintDebug assembleDebug` | 通过，Lint **0 errors / 46 warnings / 4 hints**（与基线一致） |
| 设备回归 9 类（Pixel_10_Pro AVD） | **41/41 通过**（39 基线 + 2 新增），0 失败 |
| `minifyReleaseWithR8` | 通过（BUILD SUCCESSFUL） |

## 5. 边界

- 未改消息存储、AI 请求、弹层高度逻辑与半屏/全屏切换；未新增依赖或设置。
- 抖动修复针对"列表余速拖弹层"这条已验证过的回路；真机手感（尤其快速连续翻旧消息）仍需用户手机实测。
- 本批仅本地提交到 `codex/pet-chat-scroll`，不推送/发版，等用户指令。