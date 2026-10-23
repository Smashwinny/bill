# 按频率排序与界面更新 0.13.0

## 完成内容

- 首页日历下方改为“常用消费分类”：按所选月份的有效支出笔数降序，显示笔数、笔数占比及人民币金额；前 5 类可展开全部。金额不参与排序；笔数相同时按分类名称稳定排列。
- 日历图例采用同一频率统计，默认前 4 类，可展开全部。图例和榜单沿用现有一级分类汇总，分类仍通过持久化分类 ID 解析，不更改历史账单分类。
- 收入、转账、回收站记录、其他月份不计入支出频率。一条有效支出记录计一笔，并非“消费天数”。沙盘柱高继续表示金额，分析页的金额占比继续表示金额。
- 抽出统一主题：暖灰背景、柔白卡片、深色月结余卡、细边框、统一圆角与文字层级，金额使用等宽数字特性；红绿主题均保留。
- 分类使用线条图标与一致的柔和分类色；统一日期栏、导航栏、记账金额输入区、流水卡片、分析摘要；沙盘采用柔和底板与阴影，复位按钮移入画布角落。
- 数据库、同步协议、金额计算及汇率逻辑不变；数据库仍为 v4，无新迁移。

## 设计参考

查看了两个产品官方公开界面，仅借鉴信息层级、留白、分类色和数字排版，没有复用品牌素材、截图或字体：

- [Copilot Money 官方界面](https://www.copilot.money/)：主指标优先、图表及分类颜色协调。
- [Monarch 官方预算界面](https://www.monarch.com/features/budgeting)：中性底色、卡片分组、金额与标签的层次。

本次为原生 Compose 界面，无新增 UI 库、网络字体或图片下载依赖。

## 构建与安装

- 变体：`manualDebug`，versionCode **26**，versionName **0.13.0-refined-ledger**。
- 正式 APK：`manual/build/outputs/apk/debug/manual-debug.apk`。
- APK SHA-256：`0e109666d8bb2b6b7b657273d02b6921ea94de01c6d58522bdf1a7a7690b3d59`。
- 测试 APK：`manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk`，已有测试组件，不带额外主界面图标。
- 使用现有 Android SDK、JDK 17、Gradle 缓存及 debug 签名；项目本机 SDK/缓存位于忽略目录 `tmp/`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
adb shell am start -n com.hulk.manualledger/com.hulk.manualledger.MainActivity
adb shell dumpsys package com.hulk.manualledger
```

`tmp/build-recycle.py` 是本机既有代理配置封装；其他开发机配置 SDK/JDK 后可直接使用 `./gradlew` 执行同样的 task，缓存不完整时去掉 `--offline`。覆盖安装必须沿用现有签名，不卸载、不清除数据。

## 可重复验收

1. 首页选择有支出的月份，滑到日历下面。“常用消费分类”按笔数从多到少排列，金额高但次数少的类别不会置顶。切换月份，榜单和图例同时变化。
2. 查看图例中的笔数，与榜单对应分类核对；展开全部，能看到低频类别。没有支出的月份显示空态。
3. 拖动沙盘、双指缩放、复位，点日期进入对应流水；柱高仍按金额。
4. 进入记账页输入 `3+4`，预览为 `¥7.00`；输入 `10美元` 或选择 USD，按已缓存汇率显示换算结果。仅检查界面时无须保存。
5. 在设置中切换红/绿皮肤，查看首页、流水、分析和记账页；原有数据保持一致。较大字体时内容可纵向滚动。
6. 分类改名/选择、账单编辑、回收站恢复及彻底删除继续按原流程工作；如需操作测试，使用可删除的自建样例或测试组件，不拿真实流水试删。

真机自动化仅使用模拟 UI、内存/专用测试数据库，新增视觉测试的 SharedPreferences 有 `test_visual_` 前缀，结束后删除对应测试偏好，不触碰真实账本。可运行以下已审查的测试集合：

```bash
adb install -r manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.VisualPolishTest,com.hulk.manualledger.CalendarPresentationTest,com.hulk.manualledger.LedgerExperienceUiTest,com.hulk.manualledger.CurrencyLedgerTest,com.hulk.manualledger.CategoryUiTest,com.hulk.manualledger.RecycleBinUiTest com.hulk.manualledger.test/androidx.test.runner.AndroidJUnitRunner
```

若 MIUI 阻止后台测试界面启动，先把正式账本放前台；本机使用 `tmp/visual-polish-20261023/run_device_tests.py` 处理前台切换。没有关闭手机安装确认或修改系统安全设置。

## 验证证据

本机证据目录：`tmp/visual-polish-20261023/`，全部忽略，不提交个人账本或设备截图到 Git。

- `final-build.log` / `final-test-build.log`：构建和静态检查。
- Lint：0 errors、4 条已有图标/资源警告。
- 单元测试：`manual/build/test-results/testDebugUnitTest/`，28 项通过；新增频率用例覆盖金额与频率相反、相同频率稳定顺序、跨月、删除、收入、转账、分类解析及合并后的统计。
- 真机 UI 日志：`instrumentation.log`；初版 19 项通过，最终包首轮 18 项通过、1 项分类移动测试因 `Activity has been destroyed already` 生命周期异常失败；该异常不含业务断言失败，crash buffer 为空，准确触发原因未证实。分类测试单独复跑 3/3 通过，日志为 `category-retry.log`，最终 19 个用例均有通过记录，首次失败结果完整保留。
- 模拟数据截图：`overview-green.png`、`overview-red.png`、`frequency.png`、`record.png`、`flow.png`、`analysis.png`、`analysis-large-font.png`、`sandtable.png`。
- `before-install.sha256` / `after-install.sha256`：手机本地数据库与 WAL 文件哈希完全一致，只读取校验值，不重新导出账本内容。数据库 SHA-256 为 `f8717c68bd2eab4ef7bfa656a236d3b74071029f42bbf76780ba4fad6b3f7c2d`，WAL 均为空。
- 已在当前手机原位安装并启动，`dumpsys package` 回读 versionCode 26 / `0.13.0-refined-ledger`。没有新增验收 App 图标。

## 限制

- 本轮面向现有手机的浅色红绿主题，没有新增系统深色模式。
- 已检查当前手机和 1.3 倍字体的关键页面，其他屏幕尺寸、极端大字体尚未全面覆盖。
- 本次未更改云端，不把 UI 测试视作多设备在线同步验收。
