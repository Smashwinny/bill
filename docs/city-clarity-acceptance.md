# 纯色楼层与每日分类 0.15.0

## 完成内容

- 移除所有窗户，删除窗格几何及绘制逻辑。每层只保留分类色，减弱明暗差；去掉白色腰线，檐口和屋顶沿用所在楼层/顶层分类色。
- 1–2 笔支出改为树形：一层树冠对应一笔支出，树冠按分类着色，只有细树干，没有额外台座。
- 删除装饰树、空地上的小台子和建筑基座；没有支出的日期保留空地，不出现容易误认为消费的装饰物。日历地块仍是整个地图的平面底图。
- 保留中高建筑的亭阁、坡顶、圆顶、宝塔及双球塔造型；颜色用于阅读类别，造型用于区别消费笔数档位。
- 点建筑或日期牌后选中当天，在地图下方直接显示“当天分类”：每类名称、笔数、人民币金额，以及当天支出合计。点击“查看当天流水”进入完整收支。
- 新增可滑动的日期条，每天附分类色点。日期牌被挡住时仍能准确选择日期；空日期显示“当天没有支出”，仍能进入流水查看收入等其他记录。
- 延续现有一级分类汇总，颜色、当天分类卡、月度图例一致；完整分类路径仍在流水中查看。图例/榜单继续按笔数排序。
- 数据库、同步协议、分类 ID、金额计算、汇率及真实记录均无修改，无数据库迁移。

## APK 与安装

- 变体：`manualDebug`；versionCode **28**；versionName **0.15.0-clear-city-categories**。
- APK：`manual/build/outputs/apk/debug/manual-debug.apk`。
- SHA-256：`8d39f115cc61e440ea0a17e16defb25147149add7a696c3dc575c43f28f77995`。
- 测试组件：`manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk`。

本机已配置 JDK 17、Android SDK、Gradle 缓存。`tmp/build-recycle.py` 是现有代理/缓存封装；其他开发机可直接用 `./gradlew` 执行相同 task，缓存不足时去掉 `--offline`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
adb shell am start -n com.hulk.manualledger/com.hulk.manualledger.MainActivity
adb shell dumpsys package com.hulk.manualledger
```

沿用现有签名覆盖安装，不卸载、不清空数据，不额外安装验收副本。
已在连接的小米手机覆盖安装并核实 versionCode 28 / versionName 0.15.0-clear-city-categories，测试结束后恢复正式首页。

## 手机复验

1. 首页选择有消费的月份，查看“3D 小城”：建筑没有窗户，彩色层是连续色面；1–2 笔的日期是分类色树冠，没有树旁台子；无支出的日期为空地。
2. 点建筑/日期牌，地图高亮该日；下方“当天分类”应切到同一天。核对每类的笔数和金额，总和应等于当天支出合计。
3. 滑动日期条切换其他日期，分类明细同步切换；没有支出的日期显示空态，不混入其他天、收入或回收站记录。
4. 点“查看当天流水”，进入该日全部收支；平面日历仍可直接点日期进入流水。
5. 拖动地图旋转、俯仰、缩放并复位；分类文字无需依赖建筑是否被挡住。

## 验证与证据

本机忽略目录：`tmp/city-clarity-20261023/`。截图全部使用模拟数据，不将真实账本或手机文件提交到 Git。

- `final-build.log`：构建和静态检查，Lint 0 errors、3 条已有图标/目录警告。
- `manual/build/test-results/testDebugUnitTest/`：32 项单元测试通过；新增空日期无装饰物、树形没有额外平台/屋顶的模型检查。
- `instrumentation.log`：10 项真机测试通过，包含每日分类名称/笔数/金额核对、排除其他日期/收入/回收站、选择空日期、进入当天流水、地图旋转/复位、平面回退及现有记账操作。
- `calendar-final.log`：最终小树和日期牌调整后，2 项日历测试再次通过；包括从默认 28 日点击 27 日建筑，核对面板切换和进入 27 日流水。
- `city.png` / `day-categories.png`：纯色地图与当天分类面板的手机截图。
- `before-install.sha256` / `after-install.sha256`：正式数据库和 WAL 文件校验值均与更新前一致；只读取校验值，不重新导出账本数据。

```bash
adb install -r manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.CalendarPresentationTest,com.hulk.manualledger.VisualPolishTest,com.hulk.manualledger.LedgerExperienceUiTest com.hulk.manualledger.test/androidx.test.runner.AndroidJUnitRunner
```

本机 `tmp/city-clarity-20261023/run_device_tests.py` 处理 MIUI 测试界面前台切换；不修改系统安全设置。测试仅使用模拟 UI、内存/专用测试数据。

## 限制

- 多笔同类别的楼层会有同色，使用极细边界区分；准确笔数可以直接看当天分类卡。
- 低角度仍有自然遮挡，超多笔数时楼层会较薄；日期条和文字明细始终可用。
- 未全面覆盖其他机型、极大字体和高负载帧率；本轮未修改或重新验收多设备在线同步。
