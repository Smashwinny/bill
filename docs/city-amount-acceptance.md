# 金额比例日历 0.16.0

## 行为

- 日期直接绘于每个日期格右下角的地面平面，与地图一起旋转和透视；删除悬浮日期牌、背景牌和连接线。
- 使用当月最大单日支出作为统一标尺。建筑高度 = 2.5 × 当日人民币支出 / 当月最大单日人民币支出；每笔色层高度使用相同标尺计算。
- 屋顶、树冠、塔尖都包含在金额高度之内。完整建筑轮廓按每笔金额的累计边界切分和着色，不增加无金额对应的装饰高度。相同日总额即使笔数不同，总高度也相同；同一金额在不同日期的层高相同。
- 建筑样式按金额高度分档，保留小树、亭阁、阶梯、圆顶、宝塔及双球塔。日期决定同一高度档内的稳定样式，不随机变化。
- 删除“ 一日一栋 · 彩色一层一笔 ”、“高度看金额 · 颜色看分类”和日历下方操作提示；保留标题、功能按钮、日期、分类与金额。
- 分类汇总与金额来源沿用现有人民币记账值。没有数据库迁移，不修改汇率、分类身份、回收站或同步协议。

## 产物与复现

- 变体：`manualDebug`，versionCode **29**，versionName **0.16.0-amount-city**。
- APK：`manual/build/outputs/apk/debug/manual-debug.apk`。
- SHA-256：`d783c627e6dd13a4afc6301275f3a2c43b5709c0030cee4ee353895b71be5320`。
- 测试 APK：`manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk`。
- 本机验证文件目录：`tmp/city-amount-20261023/`（Git 忽略）。

本机使用现有 JDK 17 / SDK / Gradle 缓存封装。其他开发机可直接使用 `./gradlew` 执行相同任务，缓存不足时去掉 `--offline`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
adb shell am start -n com.hulk.manualledger/com.hulk.manualledger.MainActivity
adb install -r manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.CalendarPresentationTest com.hulk.manualledger.test/androidx.test.runner.AndroidJUnitRunner
```

已在连接的小米手机覆盖安装并核实 versionCode 29 / versionName 0.16.0-amount-city，测试后恢复正式首页。覆盖安装沿用原签名，不卸载、不清空应用、不创建验收副本。MIUI 若把测试界面切到后台，本机 `tmp/city-amount-20261023/verify_calendar.py` 会恢复测试 Activity 前台；不调整系统安全设置。

## 验证

- 35 项 JVM 测试通过，包括 100:300 的层高比例、400:800 的建筑高度比例、同额不同笔数等高、屋顶包含在总高度内、极小金额不被强行抬高、闰月所有日期的右下角位置、500 笔场景的有限几何。
- Lint：0 errors，3 条现有图标/资源目录警告。
- `build.log` 保存构建结果；`calendar-final.log` 记录 2 项真机日历测试通过（23.011 秒），覆盖地面日期点击、旋转/复位、分类切换、进入指定日流水、平面回退与收支/删除过滤。
- `city.png`、`rotated.png`、`day-categories.png` 使用模拟数据；已查看默认视角和旋转后的手机截图，确认日期贴于平面、色层不等高且解释性口号消失。
- `before-install.sha256` / `after-install.sha256` 正式数据库与 WAL 的校验值均一致，不导出账本内容。

## 手机复验

1. 打开消费日历，查看有支出的月份。日期位于格子右下角，拖动旋转后仍贴在地面，不悬浮到楼顶。
2. 比较同月不同日期的支出及建筑高度；相同视角下，当日支出翻倍，对应的世界坐标高度也翻倍。透视会影响屏幕上的像素长度。
3. 选择日期，核对分类金额与色层；较大金额占更厚的色层，低金额占较薄的色层。纯色继续对应现有一级分类。
4. 点击格子右下角的日期，分类卡切换到该日；点“查看当天流水”进入该日记录。也可使用日期条切换。
5. 切换平面日历仍可查看日期、金额并进入流水；界面不再显示上述口号和提示。

## 边界

- 不使用对数、平方根、每笔最小高度或逐日独立缩放，以免破坏金额比例。某月出现极大单笔支出时，小额层可能薄于一个像素，准确金额由下方明细提供。
- 比例标尺按月计算，不能直接以不同月份的建筑高度比较绝对金额。
- 日期真实绘于平面，低俯角时可能被前方建筑遮住；可旋转视角或用日期条选择。
- 本轮不声称覆盖所有机型、极大字体或高负载帧率，也未重新验收多设备在线同步。
