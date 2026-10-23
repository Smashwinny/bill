# 消费小城与矢量图标 0.14.0

## 设计和数据含义

按本次确认的方案：每天一栋建筑，每笔支出是一层彩色楼层，楼层颜色对应原有分类。造型随消费笔数变化，不是随机生成：

| 当日支出笔数 | 建筑造型 |
| --- | --- |
| 0 | 小绿地、树木和步道 |
| 1–2 | 低平台 |
| 3–4 | 坡顶小屋或重檐亭阁 |
| 5–7 | 错层建筑或圆顶馆 |
| 8–11 | 六角宝塔 |
| 12 及以上 | 东方明珠风格的双球体塔楼 |

同一档的变体由日期固定选择；同一月份内楼层使用相同厚度，笔数较多时整体缩小楼层厚度以适应画布。彩色层才代表记录，屋顶、针尖、平台基座、檐口不计数。各建筑的装饰高度不同，因此应以彩色楼层与流水笔数为准，不用整栋外轮廓比较金额。

- 一笔不漏地按 `occurredAtMs`、稳定 ID 顺序从下向上排列；同一分类的连续记录仍保留为独立楼层。
- 只包含所选月份未删除的支出；收入、转账、回收站记录不计入。
- **小城中的楼层/高度现在表达笔数，金额点日期查看**；“平面”日历继续按金额显示柱高。日历图例、分类频率榜仍按笔数排列。
- 道路、绿地、树木、建筑共同旋转。可拖动旋转/俯仰、双指缩放、复位；点击建筑、日期牌或地块进入对应日期流水。
- 日期牌在自己的建筑顶部；如果被更近的其他建筑遮挡，或与近处日期牌重叠，则隐藏该日期牌以免错认。旋转、切换平面或使用无障碍日期操作均可访问。

## 图标

- 新绘制 25 个 Compose 矢量图标，覆盖五个导航入口、分类和已有图形操作按钮。统一圆角线条、双层色调；分类增加学习、旅行、宠物的独立图形。
- 红/绿桌面图标改为“账本 + 城市天际线”，仍在 adaptive icon 安全区域内；增加 Android 单色主题图标资源。
- 图标为矢量路径，在设备密度下重新绘制，没有放大低清位图，也不依赖网络字体、素材 CDN 或新增 UI 库。
- 数据库、同步协议、分类 ID、金额与汇率逻辑均未改动，无数据库迁移，无真实账本写入测试。

## APK 与复现

- `manualDebug`：versionCode **27**，versionName **0.14.0-calendar-city**。
- 更新包：`manual/build/outputs/apk/debug/manual-debug.apk`。
- SHA-256：`d045ffa452bd40f84c6a43fde7d7469b0e3653fcd37307d222373fe06ced4862`。
- 测试组件：`manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk`。

本机已配置 JDK 17、Android SDK 和离线 Gradle 缓存；封装脚本只增加既有代理/缓存参数。其他开发机可用 `./gradlew` 执行相同任务，缓存不完整时去掉 `--offline`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
adb shell am start -n com.hulk.manualledger/com.hulk.manualledger.MainActivity
adb shell dumpsys package com.hulk.manualledger
```

使用原签名覆盖安装，不卸载、不清空数据；不额外安装验收副本。

## 手机检查步骤

1. 首页选择一个有历史消费的月份，下滑查看“3D 小城”：少笔日期低、多笔日期高，达到对应档位出现不同造型；无支出的日期显示绿地。
2. 点击日期/建筑，核对当天支出笔数和金额。连续两笔相同分类仍为两层，同日收入不增加楼层。
3. 拖动旋转、俯仰、双指放大后再点日期；复位返回初始视角。切到“平面”仍可选择每一天，平面柱高表示金额。
4. 观察底部导航、流水分类图标、搜索和预算图标；切换原有红绿主题，确认风格一致。桌面图标由系统启动器缓存，部分启动器可能稍后刷新；不需要清除账本数据。

## 自动化与证据

证据位于忽略目录 `tmp/city-icons-20261023/`，不提交个人内容到 Git。

- `final-build.log`：APK、单元测试与 Lint；Lint 为 0 errors、3 条既有未使用图片/资源目录警告。
- `manual/build/test-results/testDebugUnitTest/`：31 项单元测试；新增测试覆盖每笔独立楼层、排序稳定、跨月/闰日、回收站/收入/转账过滤、建筑档位、单日 500 笔有限几何。
- `instrumentation.log`：10 项真机检查通过，覆盖城市手势与日期点击、平面回退、矢量图标、两套主题、频率榜、记账及历史浏览；最后一项日期牌避让调整另用 `calendar-final.log` 单独复验，1/1 通过。
- `city-default.png`、`city-rotated.png`：使用模拟数据展示所有建筑档位。
- `icons.png`：25 个图标、桌面图标及放大细节的手机渲染。
- `before-install.sha256`、`after-install.sha256`：更新前后手机数据库与 WAL 校验值完全一致，只读取校验值，不重新导出账本。
- 已原位安装并启动正式账本，手机回读 `versionCode=27` / `versionName=0.14.0-calendar-city`，没有新增验收副本。

运行已审查的模拟数据测试集合：

```bash
adb install -r manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.CalendarPresentationTest,com.hulk.manualledger.CityIconPresentationTest,com.hulk.manualledger.VisualPolishTest,com.hulk.manualledger.LedgerExperienceUiTest com.hulk.manualledger.test/androidx.test.runner.AndroidJUnitRunner
```

本机 `tmp/city-icons-20261023/run_device_tests.py` 只辅助 MIUI 测试界面前台切换；没有关闭安装确认或系统安全设置。UI 测试使用模拟数据、内存或专用测试偏好。

## 边界

- 这是原生绘制的低多边形城市示意，东方明珠为风格化轮廓，不是建筑实景模型。
- 极低俯角有自然遮挡；极多笔数时楼层会变薄，可放大并进入流水核对，不截断或丢弃记录。
- 高负载场景已做模型验证，未完成所有手机的帧率测试；其他屏幕尺寸、系统单色桌面主题尚未全面验收。
- 本轮未修改云端，也未重新执行多设备在线同步测试。
