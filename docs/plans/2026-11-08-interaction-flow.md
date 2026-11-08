# 页面交互优化 Implementation Plan

> 按已授权设计逐项执行，阶段检查后继续完成验证与推送。

**Goal:** 完成评审前四项，修正日程交互并缩短页面切换及刷新流程。

**Architecture:** 沿用 Compose + Activity ViewModel + Room/DataStore；页面状态使用独立 SaveableStateHolder；范围刷新复用现有同步引擎与登录会话；云端版本时间只作本机同步簿记。

**Tech Stack:** Kotlin, Jetpack Compose Material3, Room, DataStore, JUnit, Compose UI tests, Node.js test runner.

---

### Task 1: 日程规则与所有事项入口
- 修改 ui/todo/TodoScreen.kt、TodoViewModel.kt 和 model/TodoPlanner.kt；新增 TodoPlannerTest 行为覆盖及日程触摸设备测试。
- 校验触发日期及一次性补打卡；分离未来预览勾选；接入星期 toggleable，禁止空星期保存。
- 增加全部列表、摘要及保存定位，处理远期数据存在而近期为空的情况。
- 验证真实指针触摸而非仅 performClick 语义调用，以及远期事项和补打卡跨日行为。

### Task 2: 页面现场与返回
- 修改 MainActivity.kt、GradesScreen.kt、FreeRoomScreen.kt。
- 顶层及分区包裹独立 SaveableStateProvider；仅实际换楼栋重置滚动；声明顶层 BackHandler，保持弹层优先。
- 新增状态容器 Compose 回归：切换后位置保留、退出全屏后保留来源页。

### Task 3: 页面范围刷新与云决策
- 修改 SyncPlan.kt、UnifiedSyncViewModel.kt、UnifiedSyncScreen.kt、GradesScreen.kt、MainActivity.kt、jw_grades.js。
- 新增 GRADES_ONLY 编排、账号一致性门控、脚本模式和成功自动返回结果；去掉刷新前确认，失败留重试。
- 修改 SettingsStore.kt、CloudSync.kt、CloudApi.kt，保存服务端成功版本并用于完整同步免重复确认；首次/冲突/网络异常保持保护。
- 扩展 SyncPlanTest 与 JS 测试覆盖范围、重试、空响应、失败和未确认云状态。

### Task 4: 验证及推送
- 更新 README 与 CI 成绩脚本测试命令。
- 执行 ./gradlew testDebugUnitTest lintDebug assembleDebug minifyReleaseWithR8 assembleDebugAndroidTest --console=plain。
- 执行 node --test app/src/test/js/*.test.cjs；如模拟器可用，运行新增设备测试。
- git diff --check、自审范围和异常路径，提交后 git push -u origin codex/interaction-flow-improvements，核验远端提交。

## 实施记录

- 已完成四项；日程保留一次性事项的补打卡能力，且补打卡完成不会在次日重新出现。保存定位请求消费一次，后续切页继续恢复用户自己的浏览位置。
- 小屏（360dp）实机模拟检查修正星期按钮宽度分配；设备测试用真实触摸验证周六、周日切换，避免仅语义点击通过但手指点击无效。
- JVM 单测：430 项通过；JS 成绩/考试脚本：15 项通过。
- 模拟器（Pixel_10_Pro，Android 17）：7 项通过，覆盖星期触摸、未来预览、远期记录、保存定位、Tab 滚动恢复、教务分区恢复、刷新范围与返回来源分区。
- 全量验证命令：`./gradlew connectedDebugAndroidTest testDebugUnitTest lintDebug assembleDebug minifyReleaseWithR8 -Pandroid.testInstrumentationRunnerArguments.class=com.caeamer.beikeschedule.TodoInteractionTest,com.caeamer.beikeschedule.NavigationInteractionTest --no-configuration-cache --max-workers=4 --console=plain`。
- 未使用真实教务账号登录；教务请求范围、返回格式及失败语义使用 JS/编排测试验证，云端备份版本字段与仓库服务端实现核对。
