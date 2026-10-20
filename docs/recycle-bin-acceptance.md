# 流水回收站验收

适用：手动账本 `com.hulk.manualledger`，0.9.0（versionCode 22）。

## 行为

- 全部流水 → 删除 → 继续确认 → 确认移入回收站，才执行删除。两步均可取消，点弹窗外或返回也可取消。
- 全部流水上方提供回收站入口。移入后不计入流水、统计、预算、普通导出及自动账本迁移。
- 回收站支持逐条恢复；金额、账户、日期、备注等原始内容保留。分类若已被移除，恢复时按保存的分类路径重新绑定。
- 无自动过期或自动清空。清空回收站需两次确认，永久删除确认时列出的、仍在回收站的流水。确认期间恢复或新进入回收站的其他流水不会被误删。
- Room 数据库 2 → 3 为增加字段和标记表，不清空已有数据；版本 1 可经 1 → 2 → 3 升级。
- 移入回收站同步删除事件，恢复同步完整流水。旧事件不能覆盖更新的恢复结果；待上传变更和清空后的 ID 标记防止同步回放复活流水。
- 清空删除本机流水正文及已同步的本机 outbox 历史；待上传的删除事件继续保留，避免离线清空取消云端删除。最小 ID 标记保留，同一稳定 ID 的重复导入不会重建已永久删除的流水。

## 构建与安装

前提：JDK 17、Android SDK 34、Gradle 所需依赖已缓存或可下载。临时目录使用项目 `tmp/`。

```bash
GRADLE_USER_HOME="$PWD/tmp/gradle-home" ./gradlew :manual:testDebugUnitTest :manual:lintDebug :manual:assembleDebug :manual:assembleDebugAndroidTest
adb install -r manual/build/outputs/apk/debug/manual-debug.apk
```

Debug APK：`manual/build/outputs/apk/debug/manual-debug.apk`。覆盖安装保留应用数据，不卸载、不执行 `pm clear`。如果手机上现有版本签名不同，停止安装，先处理签名兼容性，不卸载真实账本。

## 真机界面检查

建议先用独立验收包，或用一条自己创建的测试流水；不要用真实流水测试永久清空。

1. 在全部流水点删除，在第一步取消：流水及统计不变。
2. 再点删除，点继续确认，在第二步取消：流水及统计不变。
3. 完成两次确认：流水消失，统计扣除，回收站数量增加。
4. 重开应用，在回收站恢复：原流水和统计恢复，原始金额、日期、账户、备注一致。
5. 再移入回收站，清空时在第二步取消：仍可恢复。
6. 完成清空的两次确认：回收站为空，重开后也不存在。
7. 若已配置云同步，在断网下分别试删除、恢复、清空，联网后确认最终状态一致。多设备时钟应正确。

## 隔离自动化真机验收

独立包不访问正式账本，不配置云端密钥，并移除网络权限。下面的源目录必须尚不存在；若已经存在，请换一个项目 `tmp/` 子目录。

```bash
python3 tools/category-acceptance/prepare.py tmp/recycle-acceptance/source
GRADLE_USER_HOME="$PWD/tmp/gradle-home" ./gradlew -p tmp/recycle-acceptance/source :manual:assembleDebug :manual:assembleDebugAndroidTest
adb install -r tmp/recycle-acceptance/source/manual/build/outputs/apk/debug/manual-debug.apk
adb install -r -t tmp/recycle-acceptance/source/manual/build/outputs/apk/androidTest/debug/manual-debug-androidTest.apk
adb shell am instrument -w -e class com.hulk.manualledger.ManualLedgerDatabaseTest,com.hulk.manualledger.ManualLedgerMigrationTest,com.hulk.manualledger.RecycleBinUiTest com.hulk.manualledger.acceptance.test/androidx.test.runner.AndroidJUnitRunner
```

测试覆盖真实 Compose 按钮点击和两步取消、删除/恢复/清空、数据库重开持久化、迁移数据保留、旧云端事件回放、重复导入不覆盖回收站。

## 边界

- 本功能只针对流水；分类删除仍走原分类管理流程。
- 已经被旧版本真正删除、且本机没有留存的记录，不能凭空找回。
- 回收站正文保存在本机数据库；现有云端删除事件只含 ID 和时间。另一台此前没有该流水的新设备，不能仅凭删除事件恢复正文。普通导出不包含回收站，卸载或清数据会丢失本机回收站。
- 清空回收站不是云端历史事件日志的物理擦除；服务器的历史事件保留策略不在本次修改范围内。
- 两设备冲突按更新时间比较，依赖设备时间；真实云端离线重试及多设备冲突仍需按上面的真机清单补充验证。

## 本次执行记录（2026-10-20）

- 正式 Debug APK、测试 APK 构建成功；单元测试 16 项通过；`lintDebug` 为 0 errors、14 warnings。
- 独立验收副本在已连接手机执行数据库 7 项、迁移 2 项、真实 Compose 点击 2 项，共 `OK (11 tests)`。
- 安装先被手机 USB 安装限制拦截；用户允许后重试成功。UI 启动被厂商确认弹窗拦截后，以测试的完整 Intent 打开测试 Activity，并在“验收副本打开测试包”弹窗点击“本次允许”；未修改永久权限。若复现时停在同类弹窗，需允许本次启动。
- 仅安装和测试 `com.hulk.manualledger.acceptance` 及其测试包，未安装正式包、未清除正式数据。真机 UI 用例使用独立测试界面；真实云端及多设备冲突尚未验收。
- 构建日志：`tmp/recycle-final-build.log`；单元测试报告：`manual/build/reports/tests/testDebugUnitTest/index.html`；静态检查：`manual/build/reports/lint-results-debug.html`。
- 真机日志：`tmp/recycle-acceptance/instrumentation.log`；隔离构建日志：`tmp/recycle-acceptance/build.log`。
- APK SHA-256：`cd7acab49175ceb02e3402885234239445d19b80647af9a6cac030ff945f94bf`。
- 本机 Gradle 下载依赖需显式配置 Java HTTP/HTTPS 代理；本次构建使用环境中已有代理，未记录凭据。后续依赖缓存齐全时可追加 `--offline`。首次 Kotlin 增量编译缓存异常由编译器自动回退完整编译，最终构建成功。
