# 隔离分类验收

工作目录：`/home/hulk/androidapp/pills_app`。工具复制工作区源码到项目内 `tmp/`，不修改生产包代码、不读取手机密钥。

## 准备和构建

```bash
python3 tools/category-acceptance/prepare.py tmp/category-acceptance-new/isolated-source
./gradlew -p tmp/category-acceptance-new/isolated-source :manual:assembleDebug :manual:assembleDebugAndroidTest :manual:testDebugUnitTest :manual:lintDebug
```

前提：JDK 17、Android SDK 34、已配置的 `local.properties` 和 Gradle 8.7 依赖。目的目录必须是新的项目 `tmp/` 子目录。`prepare.py` 会核对替换目标；源码结构变化时需人工检查隔离转换。副本包名是 `com.hulk.manualledger.acceptance`，启动入口、provider、图标 alias 均独立，移除了 INTERNET 权限。

本机已验证的 Gradle 可执行文件为：
`tmp/gradle-home/wrapper/dists/gradle-8.7-bin/bhs2wmbdwecv87pi65oeuq5iu/gradle-8.7/bin/gradle`，缓存参数使用 `-g /home/hulk/.gradle --offline --no-daemon`。

## 安装并执行已知分类缺陷断言

```bash
adb install -r tmp/category-acceptance-new/isolated-source/manual/build/outputs/apk/debug/manual-debug.apk
adb install -r -t tmp/category-acceptance-new/isolated-source/manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.ManualLedgerDatabaseTest,com.hulk.manualledger.ManualLedgerMigrationTest,com.hulk.manualledger.ProductCategoryAcceptanceTest com.hulk.manualledger.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

当前基线结果：`Tests run: 10, Failures: 4`。4 个失败是仍未满足的产品合同，不是预期通过的回归测试；不要修改断言去迎合当前错误行为。该文件独立存放，由 prepare 脚本只加入验收副本，不加入生产默认测试套件。

不指定全部测试类：项目另有云同步配置用例，不属于本次离线验收。

## 皮肤回归

```bash
adb shell am instrument -w -e class com.hulk.manualledger.LedgerSkinSettingsTest com.hulk.manualledger.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

期望 `OK (1 test)`，验证红绿皮肤持久化、每次只有一个 launcher 及其图标资源。测试只在验收包执行，会恢复原先皮肤，且不打开账本数据库。

手机手工检查：从桌面打开“分类验收副本” → 设置 → 外观与皮肤 → 经典绿 → 醒目红。每次切换后页面配色和桌面图标对应，设置页仍保持打开；重开后保留选择。不要把只读截图或 PackageManager 图标资源检查当作所有桌面厂商的显示结论。

真机控件脚本（会重开验收副本，不能同时手动操作手机）：

```bash
python3 tools/category-acceptance/verify_skins.py tmp/category-acceptance-new/ui
```

脚本按实际文字定位设置项，只接受验收包的界面；界面不匹配就停止。期望依次输出 green、green-reopened、red、red-reopened 的 verified，并留下四组 XML 与截图。

## 保护真实记录

- 不要在生产包执行测试、清除数据、卸载或导入虚构记录。
- 本次数据库备份、截图和原始测试日志在项目 `tmp/category-acceptance-20260912/`，不提交 Git。
- 向副本提供账本数据时，只拷贝一致的数据库快照；在电脑上清空副本 outbox，不复制同步偏好和密钥。
- 导出单个主数据库文件不能保证 WAL 中的记录完整；必须验证快照完整性，并比较全表内容，不能只对比记录总数。
