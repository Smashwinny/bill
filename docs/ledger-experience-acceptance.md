# 账本体验改进 0.11.0 验收记录

## 四项改动

1. 分类选择逐层显示同级节点，明确标出第一级、第二级、第三级；点击上级进入下一级，面包屑返回上级。搜索结果显示完整路径。分类身份仍由持久化 ID 决定，不批量改写历史分类。
2. 新记账按支出、收入、转账分别记住上次成功保存的分类 ID。首次使用参考最近创建的有效流水；失效、已删除或已成为父类的 ID 不会被自动选择；没有有效记录时要求主动选择，不再按字母排序选第一个。
3. 首页、分析和流水共用月份。顶部日期入口可跨年选一天，直接打开当天全部流水；支持前一天、后一天、整月、本月、今天、全部。日历和趋势图点击日期也打开同一筛选后的流水；搜索在所选日期范围内进行，切换日期清除旧搜索词。
4. 首页新增立体彩色堆叠消费日历与渐变结余卡。每个日期一根柱，柱高按本月最大单日支出线性缩放，颜色代表实际一级分类。可切换平面视图、展开全部图例；收入不计入支出柱，但点日期后的流水包含当天全部收支和转账。
5. 新增、编辑金额均支持 `+ - × ÷ * /`、括号、负数中间项和运算优先级。实时预览计算结果，按 `=` 可替换为金额，也可以直接保存。最终按十进制四舍五入到分；零、负数、除零、超出整数分范围、不完整算式不可保存。原始文件导入的严格金额规则保持独立。

## 产物和构建

- 正式应用：`com.hulk.manualledger`，Debug，versionCode 24，`0.11.0-ledger-experience`。
- APK：`manual/build/outputs/apk/debug/manual-debug.apk`。
- 单元报告：`manual/build/reports/tests/testDebugUnitTest/index.html`。
- 静态检查：`manual/build/reports/lint-results-debug.html`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
```

使用已配置的 JDK 17、Android SDK 34 和项目 Gradle 缓存。`tmp/build-recycle.py` 是本机已有构建环境助手；通常可直接使用 `./gradlew` 执行同一组任务。

## 可重复的手机操作

1. 记一笔 → 点击分类 → 进入一级、二级、三级，当前页只能看到同一上级下的分类。搜索同名末级应看到各自完整路径。
2. 选择一个非“礼金”的分类，输入 `3+4`，预览应为 `¥7.00`，直接保存应记为 7 元。关闭再打开应用，记一笔应默认保留刚才成功保存的分类。收入与支出分别记忆。
3. 顶部点年月文字，选择历史某天；流水标题应显示该日期，所有流水、收入和支出合计都同步改变。前一天、后一天、看整月、全部各自切换范围。
4. 首页日历观察高低不同的堆叠柱和颜色图例，切换 3D/平面，点任意日期应显示该日全部流水，包括超过五条的情况；没有流水的日期应明确显示空状态。
5. 编辑一条测试流水的金额为 `(3+4)*2`，应保存为 14 元；输入 `3+` 或 `1/0` 应不能保存。修改日期后应切换到该日期并定位修改后的流水。

金额写入、删除和恢复测试应使用隔离模拟数据。正式应用覆盖更新，不卸载、不清除数据。本版本不修改数据库结构。

## 验证边界

- 立体视图采用固定视角柱体，可切换平面；不提供自由旋转镜头。
- 柱高在单个月份内统一比例，不直接比较不同月份的绝对柱高。
- 分类默认采用上次成功保存记录，不包含基于时段的机器学习预测。
- 历史源文件只覆盖部分原始记录，本次没有猜测或批量重分历史流水。
- 云端多设备同步冲突和所有品牌手机、大字号布局未覆盖。

## 2026-10-23 执行结果

- 单元测试 22 项全部通过，覆盖算式优先级、十进制精度、四舍五入、除零、不完整输入、金额上限、闰日和本地时区日期边界、默认分类失效回退。
- `lintDebug`：0 错误、4 警告；正式 APK 与测试 APK 构建成功。
- 真机隔离测试：`OK (28 tests)`。包括原有数据库/迁移/回收站/分类回归 22 项，新增体验交互 5 项、日历视觉/切换/日期语义 1 项。
- 首轮发现测试导航节点查找和懒加载列表定位方式不正确，修正后全部通过；一次测试 Activity 生命周期异常在调整前台启动流程后回归通过。应用未出现崩溃。
- 实际独立应用另行验证：选择“饮食 › 早餐”、输入 `3+4`、预览 7 元并直接保存；强制停止进程后重开，记账页默认仍为“饮食 › 早餐”。证据 `tmp/experience-20261023/entry-restart-result.txt`。
- 正式手机已 `adb install -r` 覆盖升级成功，读取版本为 versionCode 24 / `0.11.0-ledger-experience`，已启动并截图核验。没有卸载或清空正式应用。
- 正式应用安装和启动前后，6,774 条流水、196 个分类、1 个永久删除标记全字段一致，数据库完整性 `ok`。校验记录：`tmp/experience-20261023/installation-data-preservation.json`。
- 独立验收主包 `com.hulk.manualledger.acceptance` 与 `.test` 均已卸载。原有正式包及其测试组件保留。
- 本次验收安装首次被系统拦截；用户要求重试后安装成功，后续两次同包覆盖更新直接成功。没有修改整机 USB 安全设置，不能承诺新增包以后永久免确认。
- APK SHA-256：`758edaeafbf367d859c5d351ed3273f70a1db05467fe27bc9529d51896de62d1`。

私有验收材料位于忽略目录 `tmp/experience-20261023/`：最终构建日志 `final-build.log`、隔离测试日志 `instrumentation.log`、模拟数据视觉截图 `calendar-3d.png`、正式启动截图 `official-home.png`。财务快照、手机截图和真实流水不进入 Git。

隔离验收方法沿用 `tools/category-acceptance/prepare.py` 和 `docs/stable-categories-acceptance.md`；本次运行器选用以下测试类：

```bash
adb shell am instrument -w -e class com.hulk.manualledger.ManualLedgerDatabaseTest,com.hulk.manualledger.ManualLedgerMigrationTest,com.hulk.manualledger.RecycleBinUiTest,com.hulk.manualledger.CategoryUiTest,com.hulk.manualledger.ProductCategoryAcceptanceTest,com.hulk.manualledger.LedgerExperienceUiTest,com.hulk.manualledger.CalendarPresentationTest com.hulk.manualledger.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```
