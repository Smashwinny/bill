# 多币种与旋转沙盘 0.12.0

## 交付行为

- 金额框去掉操作说明、算式示例和运算符按钮，保留金额/结果以及必要的错误信息。输入算式仍可直接保存。
- 支持 CNY、USD、EUR、HKD、JPY、GBP、AUD、CAD、SGD、CHF、NZD、KRW、THB、INR、TWD、MYR 共 16 个币种。可用币种选择器，也可输入 `10美元`、`USD 10`、`$10`、`€10`、`HK$10`、`10日元` 等标记；同一算式混合币种会拒绝保存。
- 未选外币时 `$` 按美元解释，选择加元等美元符号币种后 `$` 沿用所选币种；明确的 `USD` / `US$` 等标记优先。`¥` 在已选择日元或明确写“日元”时按日元解释，其余按人民币。
- 原币金额、原币币种、换算汇率、报价日期及来源随记录保存，人民币折算结果沿用 `amount_cents`。统计、预算、流水合计、日历、旧版自动账本迁移接口统一使用人民币金额。
- 编辑已有外币账单沿用该笔保存的汇率，单改分类、备注或日期不重估金额；切换币种才采用所选币种的缓存报价。删除、恢复及新版客户端同步保留原币信息。
- 日历沙盘是一整块有厚度的日期底板，彩色堆叠柱表示当日人民币支出及一级分类。横向拖动旋转、纵向拖动改变俯角、双指缩放/旋转，支持复位、切回平面、点击柱体或日期查看当天流水。屏幕阅读器可直接按日期执行查看流水操作。

## 汇率来源与缓存

使用 [Frankfurter 官方 API](https://frankfurter.dev/) 的日频公开参考汇率：`https://api.frankfurter.dev/v2/rates?base=CNY&quotes=...`。只发送币种列表，不发送账单、金额、账户或标识符。

打开应用/金额界面时检查缓存：距离上次成功获取不足 24 小时直接复用；失败后自动重试间隔至少 6 小时。报价日期显示在换算金额下，网络不可用时继续使用已有缓存，并标记缓存状态；首次没有缓存且网络不可用时，外币保存被禁用并提供重试按钮。人民币记账不依赖网络。

API 返回“每 1 人民币对应多少外币”，本地使用十进制除法反算“每 1 外币对应多少人民币”，最终四舍五入到分。历史账单固定保存时的报价，不随之后的缓存刷新改变。报价是参考值，可能与银行卡实际结算金额不同。

## 数据迁移与兼容

Room schema 3 → 4，只新增五个可空文本字段：`original_amount`、`original_currency`、`exchange_rate`、`exchange_rate_date`、`exchange_rate_source`。既有记录的所有列、分类及永久删除标记保持不变。新字段在旧记录上为 NULL。

JSON 同步及导出追加原币字段，保留原有人民币字段语义。只读 Provider 同样追加列。旧版客户端编辑后可能不保留新增原币字段，因此记录外币的设备应统一升级；实际多设备在线同步未作为本次现场验收结论。

## 构建和产物

- 包名：`com.hulk.manualledger`。
- Debug：versionCode 25，`0.12.0-currency-sandtable`。
- 正式 APK：`manual/build/outputs/apk/debug/manual-debug.apk`。
- 测试 APK：`manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk`。
- 单元报告：`manual/build/reports/tests/testDebugUnitTest/index.html`。
- 静态检查：`manual/build/reports/lint-results-debug.html`。

```bash
python3 tmp/build-recycle.py :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
TMPDIR="$PWD/tmp/currency-sandtable-20261023" python3 -m unittest discover -s ledger-server/tests -v
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
```

本机使用 JDK 17、Android SDK 34 和已缓存的 Gradle 依赖；构建助手是项目已有环境脚本，也可直接使用 `./gradlew` 执行相同任务。

## 可复现手机检查

1. 记一笔：金额框为空时没有“支持加减乘除”提示；输入 `3+4`，结果为人民币 7 元。
2. 选择 USD，输入 `10`；或直接输入 `10美元` / `USD 10`，应显示原币及人民币折算值、报价日期和来源。只看预览即可，不必写入测试流水。
3. 在独立测试数据中保存外币账单，重新打开编辑，应显示原币金额；只改备注后人民币金额及汇率日期不变。离线使用已有缓存仍可换算。
4. 首页或分析 → 消费日历：在沙盘内部左右、上下拖动，整块底板、日期位置和柱体一起转动；双指可缩放，复位恢复初始角度。页面滚动可从沙盘两侧留白操作。
5. 点击可见日期地面或柱体，应跳到对应当天全部流水。平面视图仍保留全部日期及点击功能。

新增验收包曾被手机 USB 策略拦截，最终改用手机已有的 `.test` 组件覆盖更新。测试数据库使用内存或专用名称；汇率测试缓存统一使用 `test_currency_` 前缀并在测试后删除，模拟流水不写入正式 `manual-ledger.db`。没有关闭手机安全设置，也没有保留新增验收主包。

私有原始快照、旧 APK、构建与验收日志保存于忽略目录 `tmp/currency-sandtable-20261023/`，不提交真实流水或手机截图到 Git。

## 2026-10-23 验证结果

- 单元测试 26 项通过；`lintDebug` 0 错误、4 警告。同步服务本地合同测试 5 项通过，包含外币快照原样重放。
- 真机测试 `OK (28 tests)`：数据库 9、迁移 3、回收站 3、分类 3、记账/日期交互 5、沙盘手势/点击/截图 1、外币存储/缓存/输入/编辑 4。
- 首轮测试 APK 缺少 Compose 测试运行类，使用 `:manual:assembleDebugAndroidTest --offline --no-build-cache --rerun-tasks` 完整重建后修复；正式应用无此依赖问题。初始日志保留为 `instrumentation-initial.log`。
- 实机截图曾发现大底座顶面遮挡远侧日期，已将底座放在所有日期单元下绘制；修正后的初始与旋转截图均已目视检查。手势后的视角改变、复位、点击可见柱顶打开正确日期全部通过。
- 正式应用已覆盖安装并启动，versionCode 25 / `0.12.0-currency-sandtable`。手机实际获取了 2026-10-23 的 15 条外币报价；正式记账页输入 `10USD`，显示 `USD 10.00 ≈ ¥66.70`。此检查只预览，未保存，草稿随后清除。
- 迁移后及验收结束后再次比较：6,774 条正式流水、196 个分类、1 个永久删除标记的旧字段均完全一致；新字段均为 NULL；数据库版本 4、完整性 `ok`。证据：`installation-data-preservation.json`。
- 最终手机仅保留原有的正式应用及 `.test` 组件，没有新增验收主包；未关闭整机安装安全设置。标准安装界面尝试未完成的临时 APK 文件已删除。
- 正式 APK SHA-256：`91640aa83904a09f7cd4d0a6522536b11bec38be89e01b54b2c4be536f167855`。

最终证据位于 `tmp/currency-sandtable-20261023/`：`final-build.log`、`server-tests.log`、`instrumentation.log`、`sandtable-default.png`、`sandtable-rotated.png`、`live-currency-preview.txt`、`device-rates.xml`。`previous-official.apk` 和迁移前快照仅作为回退保障保留。

已有测试组件的复现命令如下；测试预先保证使用内存/专用测试数据库和隔离汇率缓存，不能随意替换为其他可能写正式数据的测试类：

```bash
adb install -r -t manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.ManualLedgerDatabaseTest,com.hulk.manualledger.ManualLedgerMigrationTest,com.hulk.manualledger.RecycleBinUiTest,com.hulk.manualledger.CategoryUiTest,com.hulk.manualledger.LedgerExperienceUiTest,com.hulk.manualledger.CalendarPresentationTest,com.hulk.manualledger.CurrencyLedgerTest com.hulk.manualledger.test/androidx.test.runner.AndroidJUnitRunner
```

尚未全面覆盖其他品牌、大字号布局、实际多设备在线同步；低俯角下柱体会自然遮挡后排，可旋转、提高俯角或切到平面查看。
