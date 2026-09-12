# 可切换图标与界面皮肤交付

版本：`0.8.1-selectable-skins`，versionCode `21`，Debug。

## 完成内容

- 默认“醒目红”：红底白色账本与人民币符号的自适应桌面图标，红色主按钮、首页卡片、导航选中态、状态栏与浅红背景。
- “设置 → 外观与皮肤”可切换“经典绿”，桌面图标与界面配色一起变化；本机保存，重开保留选择。
- 采用独立启动入口转入账本页面，避免部分手机在更换当前图标时关闭账本任务。真机已从两个图标入口分别冷启动后切换，设置页继续可见。
- 本次没有改写分类与账单逻辑；图表布局沿用原版。分类验收问题见 [产品验收报告](CATEGORY_PRODUCT_ACCEPTANCE_20260912.md)。

## 安装产物

已覆盖安装到连接手机，包名 `com.hulk.manualledger`，安装返回 `Success`，未卸载、未清数据。

APK：`/home/hulk/androidapp/pills_app/manual/build/outputs/apk/debug/manual-debug.apk`

SHA-256：`99329b8eca05631eafc7c41915a8f02ee33009fd250feafbbff6c1be94154f5d`

重复覆盖安装命令：

```bash
adb install -r /home/hulk/androidapp/pills_app/manual/build/outputs/apk/debug/manual-debug.apk
```

必须使用同一签名覆盖安装；如果系统拒绝签名，不要通过卸载绕过。运行皮肤验收可使用下面独立包，生产数据不参与写入式测试。

验收 APK：`/home/hulk/androidapp/pills_app/tmp/category-acceptance-20260912/skin-final-source/manual/build/outputs/apk/debug/manual-debug.apk`

## 验证证据

- 正常包与隔离包均完成 `:manual:assembleDebug :manual:testDebugUnitTest :manual:lintDebug`；16 项单元测试通过，Lint 无错误。仍有 4 项 warning：旧 SDK 条件、未使用的旧图标、旧圆形图标素材检查和未提供 monochrome 资源，不将其描述为零警告。
- `LedgerSkinSettingsTest` 真机通过：持久化选项、红绿两种 icon resource、每次只有一个 launcher；Android 实际图标渲染已目视检查。
- 真机 UI 通过四个状态：切绿、从绿图标重开、切红、从红图标重开；可见配色与“已选”一致，最终保持红色。
- 原 App 的红色新图标已在手机“金融理财”文件夹中目视确认，原位置保留；本机实际入口只有 `com.hulk.manualledger/.launcher.Red`。
- 真实数据库的账单、分类、完整同步队列及元数据逐表 SHA-256 一致，6,523 条账单、196 个分类保持不变。完整性检查 `ok`。本次没有用恢复去掩盖更改，因为原始数据没有变化。

证据目录：`/home/hulk/androidapp/pills_app/tmp/category-acceptance-20260912/`

- `verified-red-reopened.png`、`verified-green-reopened.png`：重开后实际设置页。
- `icon-red.png`、`icon-green.png`：Android 加载 launcher icon 后绘制的图标。
- `skin-instrumentation-final.txt`：最终入口版本的皮肤测试结果。
- `production-build.log`：正常包构建、单元测试与 Lint 输出。
- `data-preservation.json`：安装前后的全表校验摘要，不包含账单明细。
- `production-finance-folder.png`：原 App 覆盖安装后在桌面文件夹中的真实图标。

复现命令与前提见 [隔离验收工具](../tools/category-acceptance/README.md)。

## 手机复验步骤与限制

打开账本 → 设置 → 外观与皮肤 → 经典绿 → 醒目红；返回桌面，查看对应颜色的图标；再打开，所选皮肤应保留。需要避免任何真实同步时，请在“分类验收副本”中执行。

已测试设备为 2407FRK8EC；其他桌面启动器的缓存刷新时长、Android 26–32 的系统差异及系统主题图标模式没有逐一验证。当前支持两套预设皮肤，不包含自定义图片或任意调色。首次升级从旧启动入口迁移到皮肤入口时，桌面图标位置由启动器管理。

全量备份恢复、多设备同步及已报告分类缺陷不属于皮肤通过结论。
