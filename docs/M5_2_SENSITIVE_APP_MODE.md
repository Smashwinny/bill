# M5.2 敏感应用安全模式

## 已证实问题

2026-08-14 在当前真机做了单变量 A/B：保留 Pick记账和小米截屏无障碍服务，只关闭自动账本行为学习服务后，招商银行的“正在共享/录制屏幕”提示消失。

当时系统 `Media Projection` 为 `null`，本 App 不声明录屏服务，无障碍 `capabilities=0`、`canRetrieveWindowContent=false`，且不读取 `event.source`。因此该提示是招商银行对本 App 无障碍启用状态的泛化风险文案，不是本 App 真的录屏。

## 第一版边界

1. 直接打开 `cmb.pb` 时，服务不处理其事件文本，只提示用户使用安全入口。
2. “安全打开招商银行”必须先从 `enabled_accessibility_services` 中仅移除本 App 组件，读回确认成功后才启动银行。
3. 从银行返回安全入口时立即恢复；用户转到桌面或其他 App 时，可从常驻通知或主界面明确恢复。
4. 只有运行时通知权限、应用通知总开关和“敏感应用安全模式”渠道都可用，并确认恢复通知实际发布后，才允许启动银行。
5. 15 分钟 WorkManager 任务只把通知升级为“请确认已离开再恢复”，不会在银行仍可能前台时盲目重启无障碍。
6. 每次增删都在串行状态转换内重新读取现有列表，仅修改本 App 组件，并校验其他组件集合没有变化。
7. 每个会话有持久 generation 与 `PREPARING/PAUSED/LAUNCHED/RECOVERING` 阶段；只有移除读回、缺口落盘、通知发布全部成功才从 `PREPARING` 进入 `PAUSED`。
8. 写回无障碍设置只进入 `RECOVERING`；每次恢复重试还有独立 attempt UUID。只有同一 attempt 创建的 Service 收到 `onServiceConnected`，且再次读回系统列表确认组件仍启用，才清会话、撤通知和关闭缺口；旧实例和失败重试的迟到回调均拒绝。
9. 暂停读回成功后同步清零旧心跳并打开 `A11Y_SERVICE` 覆盖缺口。通知和短信观察不受影响，但暂停时间不得宣称无障碍覆盖完整。
10. 银行启动是阶段 CAS：仅同一 generation、严格 `PAUSED` 且系统列表中本服务确实缺席时允许一次；`PREPARING/LAUNCHED/RECOVERING` 均拒绝。
11. 系统监听器重连/断连在回调当场记录时间再异步落 gap；通知与 SMS 原始观察先写入加密 fsync outbox，数据库超过 3 秒时不再让系统回调崩溃，并由待办继续提交或下次启动重放。数据库提交后先安排幂等解析再删除 outbox，且 outbox 与 `PENDING_PARSE` 同时清空前不关闭恢复缺口。
12. outbox 完成加密、文件 fsync、原子 rename 和目录 fsync 后立即归还系统通知回调；不再为数据库等待 3 秒，避免通知风暴阻塞 Android 主线程和主界面首帧。只有 outbox 落盘失败时才同步等待数据库兜底。

## 个人设备权限

安装 APK 后需由设备所有者执行一次：

```bash
adb shell pm grant com.hulk.pillsapp android.permission.WRITE_SECURE_SETTINGS
```

若未授权、提醒任务无法持久化、覆盖缺口无法落盘或系统写入后读回不一致，安全入口必须 fail-closed：不启动银行，并立即尝试恢复本服务。

## 已知限制

- Android 普通应用不能在其他 App 启动前无条件拦截它。因此严格的“先暂停、后启动”只能通过本 App 的安全入口保证。
- 无法在本服务关闭期间可靠获知招商银行是否仍在前台，因此 15 分钟只提醒，不自动恢复。若从桌面离开银行而没有返回安全入口，需点通知或主界面的“已离开银行，立即恢复监视”。
- `WRITE_SECURE_SETTINGS` 是高权限的个人侧载方案，不适用于未来的通用上架版本。
