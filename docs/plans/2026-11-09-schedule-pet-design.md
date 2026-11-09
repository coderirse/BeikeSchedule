# v2.0.0 课表小宠物设计与调研

## 已确认需求

- 仅课表页常驻，默认开启，可在设置关闭。复用用户本机 Codex 正在使用的 deepseek酱（蓝发鲸尾 Q 版女仆）精灵图，后续可以增加形象。
- 允许在课表内容区域自由拖动、随机走动；避开顶栏、导航栏和添加按钮。漫画气泡有指向角色的尾巴。
- 默认适中：闲聊 60–120 秒一次、约 8 秒消失；设置提供安静 180–300 秒、适中、活泼 30–60 秒；点击宠物可主动换一句。
- 默认称呼“同学”，设置可自定义。文案可爱、俏皮，包含课程、考试提示、经典名句、原创打气/生活短句。
- 课程/考试气泡与系统通知独立，设置可分别关闭；已有通知、提醒排期、同步、数据及隐私口径保持现状。
- 本版离线，不接模型、不搜索热点；对白提供者与展示、提醒规则分离，为以后接入预留接口。

## 调研与 ADR

| 方案 | 来源与适用性 | 决策 |
| --- | --- | --- |
| Pets KMP | [项目](https://github.com/yet300/Pets) 支持 Codex V1/V2、独立渲染/宿主模块；[构建版本](https://github.com/yet300/Pets/blob/main/gradle/libs.versions.toml) 使用 Kotlin 2.4.20，当前项目为 2.2.10 | 参考其格式分离与生命周期边界，不为一个宠物升级整个工具链 |
| SpriteView-KMP | [项目](https://github.com/stevdza-san/SpriteView-KMP)，Apache-2.0，Compose 精灵图播放、速度与停止控制 | 参考裁绘方法；用已有 Compose Canvas 小范围实现，不新增依赖 |
| CoPet | [架构](https://github.com/ChanceYu/CoPet/blob/main/docs/architecture.md)，MIT，事件驱动行为、形象资源包与运行时分离 | 借鉴事件优先级、包结构、故障不阻塞主功能；不移植桌面宿主 |
| Lottie / Rive | [Lottie Compose](https://github.com/airbnb/lottie/blob/master/android-compose.md)、[Rive Android](https://github.com/rive-app/rive-android)，有各自的动画素材格式与运行时 | 当前已有 WebP 精灵图，首版不增加格式转换或 native 动画依赖 |

素材本地核对：`deepseek/pet.json` 与 `spritesheet.webp`，1536×1872，8×9 格；原样复用，不把库的代码许可证误写成图像授权。资源定义与动画状态独立，以后新增内置形象只增加定义与资源。

## 稳定性边界

- 新建独立 DataStore `schedule_pet`，开关、频率、称呼不触发核心 SettingsStore、云备份或提醒重排。
- 宠物 ViewModel 仅组合 Repository 的只读 Flow。前台提示复用 `NextClass` / `WeekResolver` / `CourseVisibility`，尊重假期、调休和隐藏课次，不写数据库。
- 精灵图后台解码，最多缓存当前一个 atlas；不逐帧解码或复制整图。动画状态只使宠物局部绘制更新。
- 只有课表内容可见且 Activity RESUMED 才运行。弹层/编辑期间暂停；关闭时不创建图像、动画或对白计时任务。
- 仅角色实际矩形接收宠物手势；气泡主体不消费课表触摸，关闭按钮单独响应。随机移动避开添加按钮，气泡出现时停止走动；翻周/课程操作暂停互动。
- 本地闲聊故障不阻塞主功能；提醒优先且去重，未知时间不编造倒计时。新增设置不改 Room schema、快照格式、Manifest 权限或系统通知。

## 实现计划

1. 新增 `ui/pet/PetPreferences.kt`、`PetDialogue.kt`：独立设置、可替换对白接口、提示纯函数与优先级；测试隐藏/假期/时间边界、去重及频率。
2. 新增 `PetViewModel.kt`、`PetAssets.kt`、`PetOverlay.kt`：后台素材加载、前台生命周期、局部动画、拖动/走动与气泡；在 ScheduleScreen 增加一个独立宿主。
3. 新增 `PetSettingsSection.kt` 并在 ProfileScreen 接入：开关、三档频率、自定义称呼、课程/考试/闲聊开关、形象信息。
4. 设备回归覆盖启停、拖动、气泡、翻周、课程操作及原有 18 项交互；跑 JVM/JS、Lint、debug 与签名 release，版本改为 2.0.0 / 64。

名句使用核对过的古典原文并标出处；现代打气文案原创，不把网络流行句冒称名人名言。出处参考《论语·学而》《道德经·六十四章》，随文案记录来源。

## 实施结果与发布范围

- 当前 v2.0.0 / 64 实现采用原生 Compose + 本地 WebP，未增加动画依赖、Manifest 权限、数据库迁移或系统提醒写入。
- 441 项 JVM、15 项 JS、22 项设备交互测试通过；Lint 无错误；debug 和正式签名 release（含 R8）构建通过。
- 设备验证包含自由拖动、气泡、角色启停、设置及称呼、翻周、课程点击/添加、前后台停用与位置恢复，并复测原有日程/导航/滚轮。
- 用户查看真实预览并明确选择“直接发布 v2.0.0 到应用内更新源”。保留旧安装包及旧更新声明，完成签名和下载校验后发布；用户手机体验另行反馈。
