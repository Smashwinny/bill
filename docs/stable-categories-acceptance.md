# 分类一致性与单条彻底删除

版本：手动账本 0.10.0-stable-categories（versionCode 23）。

## 修复内容

- 回收站每条流水提供“彻底删除这条流水”，只提交该条 ID，经过两步确认；其他回收站流水和正常流水不受影响。
- 新记账、编辑、快捷改分类都从数据库分类树选择，保存分类 ID，不再使用旧的别名表或 SharedPreferences 自定义分类列表。
- 编辑非分类字段保留分类 ID；分类改名和移动后按该 ID 解析完整路径，统计按实际分类树的一级分类汇总，移除独立的隐藏报表归类规则。
- 分类选择支持搜索完整路径，同名叶子展示不同上级路径。分类管理增加搜索、改名、新建一级/子分类及明确的移动目标按钮；移动和改名后定位原 ID，显示新完整路径。
- 流水在搜索后改分类或编辑时清除旧搜索条件，定位并突出显示刚修改的流水，避免因为不再符合旧关键词而从视野中消失。
- 拖动监听随分类树更新，离屏分类移除旧命中区域，避免拖动使用过期分类数据。
- 流水导出额外保留完整分类树及空分类，按钮准确命名为“导出流水与分类”；不据此承诺一键完整备份恢复。自动账本迁移仍使用兼容的流水格式。

## 原始数据与边界

本次没有依据别名、金额或备注猜测并批量改写历史分类。原始流水文件、数据库、截图及测试日志不提交 Git。

- 已对正式应用数据库及 WAL 做两次只读快照，均 `PRAGMA integrity_check = ok`，两份快照的流水及分类内容一致。
- 快照含 6,759 条流水、196 个分类；逐条对照数据库分类 ID 的完整路径，路径不一致数为 0。
- 现有导入后历史备份 `tmp/manual-ledger-category-fix.db` 可按同一 ID 对照 6,455 条流水，其中 2 条分类与当前不同。该备份不是原始 XLSX，不能将此比对称为随手记原始分类的独立全量核验，也不能直接覆盖用户后续手动调整。
- 用户在手机文件管理器定位了 `Download/随手记简单账本20260902/随手记简单账本20260902.xlsx`（5.18 KB），确认为此前已读取的同一文件，只有 13 条数据；已逐条唯一匹配到现有流水，13 条分类全部一致、差异 0、歧义 0、缺失 0。项目 `tmp/suishou-export-20260831.xlsx` 实为历史 provider 权限错误文本，不能当作有效表格。完整旧文件名的历史记录为 `随手记简单账本20260901.xlsx`，目前尚未取得这份完整文件。
- 用户要求后，已停止新导出，并停止飞书查找。不能声称已完成每条流水与原始全量文件的分类核对；本次对已找到文件使用稳定 ID 优先、收支类型/金额/时间/账户严格唯一匹配兜底；待找回完整旧文件后仍应只按稳定 ID 或严格唯一匹配对照，歧义记录必须保留并列出，不能自动猜测。
- 本次不清除真实应用数据，不导入测试流水，不删除、合并或重置真实分类。旧的报表映射偏好保留在设备，但新版不再使用。
- 分类树配置（特别是空分类）尚未有独立的多设备同步事件；真实云端、多设备冲突仍未验收。

## 构建与验证

前提：项目根目录、JDK 17、Android SDK 34、已缓存 Gradle 依赖。

```bash
GRADLE_USER_HOME="$PWD/tmp/gradle-home" ./gradlew :manual:testDebugUnitTest :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:lintDebug --offline
```

正式 Debug APK：`manual/build/outputs/apk/debug/manual-debug.apk`。

隔离测试准备：

```bash
python3 tools/category-acceptance/prepare.py tmp/category-check/source
GRADLE_USER_HOME="$PWD/tmp/gradle-home" ./gradlew -p tmp/category-check/source :manual:assembleDebug :manual:assembleDebugAndroidTest --offline
adb install -r tmp/category-check/source/manual/build/outputs/apk/debug/manual-debug.apk
adb install -r -t tmp/category-check/source/manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.ManualLedgerDatabaseTest,com.hulk.manualledger.ManualLedgerMigrationTest,com.hulk.manualledger.RecycleBinUiTest,com.hulk.manualledger.CategoryUiTest,com.hulk.manualledger.ProductCategoryAcceptanceTest com.hulk.manualledger.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

验收包没有 INTERNET 权限，与正式应用隔离。厂商系统可能弹出 USB 安装或应用启动确认，允许此次测试即可，不必更改永久权限。仅在安装成功且测试运行器等待启动时，可从 shell 打开测试框架所需 Activity：

```bash
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10008000 -n com.hulk.manualledger.acceptance/androidx.activity.ComponentActivity
```

正式安装采用覆盖更新：

```bash
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
```

不得卸载正式账本、清除数据或把生产数据库装入可联网测试包。

## 手机验收步骤

1. 全部流水搜索原分类，给一条测试流水改分类：应清除旧搜索词、显示新完整路径，并定位该条流水，金额和日期不变。
2. 编辑流水只改备注：分类 ID 和路径不变。
3. 设置 → 分类管理 → 搜索分类 → 改名或移动：应显示“已定位：新路径”；新记账、编辑、流水和统计使用同一树。
4. 在两个不同上级下建立同名子类，选择时可看到完整路径，避免误选。
5. 在回收站对一条测试流水“彻底删除” → 第一步取消、第二步取消，均应保留；完成两步确认后仅该条消失。不要用真实流水试永久删除。

## 证据位置

- 数据库快照与分类核对统计：`tmp/category-repair-20261020/before-1.tar`、`before-2.tar`、`before-1.db`、`category-audit.json`。
- 正式构建日志：`tmp/category-repair-20261020/final-build.log`。
- 独立副本构建日志：`tmp/category-repair-20261020/acceptance-build.log`。
- 原始 13 条分类对比明细（私有）：`tmp/category-repair-20261020/reference-comparison.json`。
- 真机运行器日志：`tmp/category-repair-20261020/instrumentation.log`。
- 单元测试报告：`manual/build/reports/tests/testDebugUnitTest/index.html`。
- 静态检查：`manual/build/reports/lint-results-debug.html`。

## 2026-10-20 执行结果

- 单元测试 17 项通过；`lintDebug` 为 0 errors；正式 Debug APK 与测试 APK 均构建成功。
- 独立手机验收 22 项全部通过：数据库 9、迁移 2、回收站点击 3、分类点击 3、产品分类合同 5。分类点击覆盖搜索旧分类后改分类仍可见、改名定位、移动后定位。
- 正式包已通过 `adb install -r` 覆盖升级为 versionCode 23、`0.10.0-stable-categories`，未卸载、未清数据。
- 安装前后全表比较：6,759 条流水、196 个分类、1 个永久删除标记逐字段完全一致；数据库完整性为 `ok`。证据：`tmp/category-repair-20261020/installation-data-preservation.json`、`preinstall.tar`、`after-install.tar`。
- 本次独立验收副本 `com.hulk.manualledger.acceptance` 和对应 `.test` 包均已卸载，未触碰正式账本或原有其他测试包。
- APK SHA-256：`5c7c3abbd4c8a092cd62efe4456a18f7cd336b0879572e05ac8c4dfe500fb363`。
