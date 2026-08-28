# BeikeSchedule 贝壳课表

北京科技大学（USTB）课表 App：WebView 登录[本研一体化教务系统](https://byyt.ustb.edu.cn)一键导入课表，本地周视图展示，上课提醒。

## 功能

- **教务一键导入**：内置 WebView 完成统一身份认证登录（App 不接触学号密码），自动抓取学期课表 JSON，预览确认后入库
- **官方教学周日历**：导入时同步教务校历，教学周↔日期精确映射——国庆等长假周不占教学周序号（第 4 周 = 10/5，而非 9/28）；假期中自动提示并定位到假期后第一个教学周
- **周视图课表**：按"大节"排版（1-2 节 = 第一大节 … 第 11-13 节 = 第六大节），支持滑动切周、周次下拉跳转、单双周标注、无固定时间课程（实验周/网课）单独列表、地点楼名/房间号分行显示
- **课程管理**：手动增删改课程，教务导入数据与手动课程共存
- **上课提醒**：每大节课前 N 分钟系统通知（默认 15 分钟可调），支持精确闹钟，开机自动重排
- **深色模式**：跟随系统 / 浅色 / 深色三态切换
- **当前周定位**：杀进程重启也能正确定位当前教学周

## 下载

[Releases](https://github.com/coderirse/BeikeSchedule/releases) 页面下载最新 APK（release 签名，可直接覆盖安装升级）。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- Room（课程 / 节次时间）+ DataStore（学期配置 / 教学周日历 / 提醒 / 主题）
- AlarmManager（上课提醒调度）
- 教务适配层：WebView 注入 JS 复用登录会话，直接调教务结构化 JSON 接口（无需解析 HTML）

### 教务接口要点

- 课表：`/xszykb/queryxszykbzong`（学期总课表，含 32 位周次位图）、`/component/queryKbjg`（节次时间）
- 校历：`/Xiaoli/queryMonthList`（全量教学周↔日期映射，需 `RoleCode` 头），失败时逐周 `/component/queryRlZcSj` 兜底
- 登录页是 PC 布局且其 meta viewport 会覆盖 WebView 宽视口设置，注入脚本改写 `width=1440` 并修补 `100vh` 高度坍缩（详见 `ImportScreen.kt` 注释）

## 构建

```bash
./gradlew assembleRelease    # 需要 keystore.properties（签名配置，未入库）
./gradlew testDebugUnitTest  # 解析器/周次逻辑单测（fixture 为真实接口样本，见 docs/samples/）
```

更多技术细节见 [docs/TECH_DESIGN.md](docs/TECH_DESIGN.md)。

## 隐私

学号密码只在系统 WebView 中由本人输入给学校统一认证页面，App 不读取、不存储、不上传任何凭证；课表数据仅保存在本机。
