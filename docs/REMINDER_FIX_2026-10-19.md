# 课前提醒漏发修复与真机验证

## 验收范围

用户确认：应用保留在后台时，包括锁屏后，正常发送课前通知；手动划掉后台后不要求继续提醒。沿用现有通知样式与提前分钟数，不引入常驻服务或系统闹钟标记。

## 真机证据

- 设备：vivo V2366GA，Android 16 / API 36；正式包 `com.caeamer.beikeschedule`，1.5.3 / versionCode 58。
- 排查时系统通知权限、上课通知渠道（importance=4）、精确闹钟权限均已开启。
- `dumpsys alarm` 的移除历史中，UID 10601 的课前提醒在 2026-10-19 21:15:01 因 `Reason=frozen` 被移除；最初查询不到该包的活动课前闹钟。
- 应用原处于 vivo「智能控制后台耗电」，自启动未开启，且不在 Android 电池优化白名单中。仅在最近任务保留卡片不能阻止厂商冻结。
- 原实现仅根据课程/设置值变化重排。Activity / ViewModel 保留期间，从后台或系统设置返回时，`distinctUntilChanged` 不会再次发射，因此不能主动修复被移除的系统闹钟。
- 原设置页「已排」仅来自 DataStore 排期记录，不能证明 AlarmManager 中仍有相应闹钟。

## 修改

1. `MainActivity.onResume` 独立触发恢复，不依赖课程或设置变化。
2. 新增 `ReminderRecovery`：串行恢复三类提醒，一类失败不会阻断另外两类，记录错误并保留协程取消语义；开机等广播与每日续期共用该入口。
3. 监听精确闹钟权限授予广播，确认权限后重新安排提醒。
4. 提醒设置页展示标准电池优化/后台限制，提供由用户点击的后台运行授权入口，并在返回页面时刷新状态。
5. vivo / iQOO 展示后台耗电与自启动引导。自启动入口已在真机验证；后台耗电页受厂商签名权限保护，使用公开的应用详情入口，指引进入「电量 → 后台耗电管理」。
6. 排期文案改为「提醒计划」，不把持久化记录当作系统投递保证；精确权限缺失时不再承诺只延迟几分钟。

## 验证结果

- `:app:testDebugUnitTest`：407 项通过，0 失败。
- Debug / AndroidTest / Release 构建通过，Release 必需 lint 通过，`git diff --check` 通过。
- vivo 真机 `ReminderRecoveryTest`：保持课程和设置不变，进入后台并等待 Activity STOPPED；手动移除系统闹钟，确认本地记录还在；重新进入 RESUMED 后，活动系统闹钟恢复。通过。
- vivo 真机 `ReminderDeliveryTest`：经生产调度器安排系统闹钟，由真实接收器发出通知，并验证通知标题。通过。
- 再跑上述两项，在通知用例开始时锁屏：两项均通过，实际通知在锁屏状态下投递。未测试强制停止/划掉后台后的投递，也不作为本次保证。
- 测试仅在独立 `.debug` 包写入临时课程与节次，并在 finally 恢复；正式包的真实课程没有被测试改动。
- 修复版已通过 `adb install -r` 覆盖正式包；已为正式应用允许后台耗电、开启自启动并确认标准电池优化豁免。临时测试包在测试后卸载。
- 正式包当前活动课前闹钟 14 条，并有每日续期闹钟；设置页显示最近一次为 **2026-10-20 09:50**，用户原设置提前 **5 分钟**。

锁屏短时投递验证不能替代整夜待机或未来真实课程的观察；厂商仍可在用户重新收紧后台权限时限制通知。

## 复测

```powershell
$env:JAVA_HOME = 'D:\Android-studio_\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb install -r -t app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell pm grant com.caeamer.beikeschedule.debug android.permission.POST_NOTIFICATIONS
# 在 vivo 设置中也为独立 debug 包允许后台运行，避免系统冻结测试进程。
adb shell am instrument -w -r -e class com.caeamer.beikeschedule.ReminderRecoveryTest,com.caeamer.beikeschedule.ReminderDeliveryTest com.caeamer.beikeschedule.debug.test/androidx.test.runner.AndroidJUnitRunner
```

真机日志：`out/reminder-device-tests.log`、`out/reminder-locked-tests.log`；构建日志：`out/reminder-build.log`。

## 官方参考

- [vivo 关于后台通知、自启动及后台耗电的说明](https://kefu.vivo.com.cn/robot/imgmsgData/3dfcdb616d0542b4baa4e9ead6d75c5e/index_1.html)
- [Android 闹钟及精确权限恢复](https://developer.android.com/develop/background-work/services/alarms)
- [Android 电池优化与后台限制](https://developer.android.com/training/monitoring-device-state/doze-standby)
