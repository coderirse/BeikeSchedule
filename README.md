# BeikeSchedule 贝壳课表

北京科技大学（USTB）课表 App：WebView 登录[本研一体化教务系统](https://byyt.ustb.edu.cn)一键导入课表与成绩，本地周视图展示，上课与考前提醒。

## 功能

**课表**
- **教务一键导入**：内置 WebView 完成统一身份认证登录（App 不接触学号密码），自动抓取学期课表 JSON，预览确认后入库
- **官方教学周日历**：导入时同步教务校历，教学周↔日期精确映射——国庆等长假周不占教学周序号（第 4 周 = 10/5，而非 9/28）；假期中自动提示并定位到假期后第一个教学周
- **周视图课表**：按"大节"排版（1-2 节 = 第一大节 … 第 11-13 节 = 第六大节），支持滑动切周、周次下拉跳转、单双周标注、隐藏周末开关、同周冲突课程并排窄列、今天实心胶囊高亮、顶栏日期+学期状态（未开学/第 N 周/假期中）
- **课程管理**：手动增删改课程（含多时段），教务导入数据与手动课程共存；教务导入课程可隐藏不删除；同名调课/单双周拆分行自动合并
- **深色模式**：跟随系统 / 浅色 / 深色三态切换；浅色暖渐变 + 暗色暗渐变双主题背景

**教务（成绩 | 考试）**
- **成绩**：按学期分组的成绩单，单科排名（如 排名 5/128），点击弹详情（排名/考核方式/学分/开课学院等），不及格标红、学期组头"N 门未通过"
- **加权成绩**：只看必修课的加权平均分，学年/学期双列筛选，可自定义排除课程
- **GPA**：本地 4.0 制计算（90-100=4.0 / 85-89=3.7 / 80-84=3.4 / 75-79=3.0 / 70-74=2.4 / 65-69=2.0 / 60-64=1.0，补考/重修覆盖正考）
- **学分修读进度**：毕业总进度条（已修/要求）+ 17 类课程性质的要求 vs 已完成进度条，口径与教务网一致
- **成绩隐私**：小眼睛一键隐藏/显示全部分数
- **考试安排**：按日期分组、D-N 倒计时、座位号、考点展示

**提醒**
- **上课提醒**：每大节课前 N 分钟通知（默认 15 分钟可调），精确闹钟 + 开机自动重排
- **考前提醒**：考试前一天 20:00 与开考前 1 小时各提醒一次（含地点与座位号）

**我的**
- 学籍信息卡（姓名/学号/学院/专业/班级），主题三态切换，检查更新，外部系统快捷入口（评教/大创），清除成绩缓存，联系开发者

## 下载

[Releases](https://github.com/coderirse/BeikeSchedule/releases) 页面下载最新 APK（release 签名，可直接覆盖安装升级；v1.0.14 及更早版本因包名变更需卸载重装）。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room（课程 / 节次时间 / 成绩 / 考试）+ DataStore（学期配置 / 教学周日历 / 提醒 / 主题 / 隐私开关）
- AlarmManager（上课与考试提醒调度）
- 教务适配层：WebView 注入 JS 复用登录会话，直接调教务结构化 JSON 接口（无需解析 HTML）

### 教务接口要点

- 课表：`/xszykb/queryxszykbzong`（学期总课表，含 32 位周次位图）、`/component/queryKbjg`（节次时间）
- 成绩：`/cjgl/grcjcx/grcjcx`（成绩+排名/考核方式，JSON POST）、`/cjgl/grcjcx/getgpa`（GPA 概览）
- 学业进度：`/cjgl/cjzhtjcx/cjcx/queryXflbyq`（学分类别要求）、`queryBxkqk`（毕业总进度）
- 考试：`/kscxtj/queryXsksByxhList`（pxn/pxq/ppylx 参数带 p 前缀）
- 校历：`/Xiaoli/queryMonthList`（全量教学周↔日期映射，需 `RoleCode` 头），失败时逐周 `/component/queryRlZcSj` 兜底
- 登录页是 PC 布局且其 meta viewport 会覆盖 WebView 宽视口设置，注入脚本改写 `width=1440` 并修补 `100vh` 高度坍缩（详见 `JwWebView.kt` 注释）
- 全量接口参考：[docs/JWXT_API.md](docs/JWXT_API.md)（后续开发勿再扒站）

## 构建

```bash
./gradlew assembleRelease    # 需要 keystore.properties（签名配置，未入库）
./gradlew testDebugUnitTest  # 解析器/周次逻辑单测（fixture 为真实接口样本，见 docs/samples/）
```

更多技术细节见 [docs/TECH_DESIGN.md](docs/TECH_DESIGN.md) 与 [docs/JWXT_API.md](docs/JWXT_API.md)。

## 隐私

学号密码只在系统 WebView 中由本人输入给学校统一认证页面，App 不读取、不存储、不上传任何凭证；课表/成绩数据仅保存在本机，不上传云端；成绩默认隐藏展示需点小眼睛查看。

## 反馈

问题反馈：caeamer@163.com ｜ GitHub Issues

© 2026 caeamer. All rights reserved.
