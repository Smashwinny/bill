package com.hulk.pillsapp.ui

enum class HomeHealthLevel {
    HEALTHY,
    ATTENTION,
    SETUP_REQUIRED,
}

data class HomeHealthInput(
    val notificationPermission: Boolean,
    val notificationConnected: Boolean,
    val accessibilityPermission: Boolean,
    val accessibilityConnected: Boolean,
    val accessibilityHeartbeatFresh: Boolean,
    val pendingParseCount: Long,
    val openGapCount: Long,
)

data class HomeHealthSummary(
    val level: HomeHealthLevel,
    val title: String,
    val description: String,
    val issues: List<String>,
)

fun deriveHomeHealth(input: HomeHealthInput): HomeHealthSummary {
    val setupIssues = buildList {
        if (!input.notificationPermission) add("通知读取权限未开启")
        if (!input.accessibilityPermission) add("行为学习服务未开启")
    }
    if (setupIssues.isNotEmpty()) {
        return HomeHealthSummary(
            level = HomeHealthLevel.SETUP_REQUIRED,
            title = "还差几项设置",
            description = "完成必要授权后，自动账本才能开始观察付款线索。",
            issues = setupIssues,
        )
    }

    val runtimeIssues = buildList {
        if (!input.notificationConnected) add("通知监听已授权但未连接")
        if (!input.accessibilityConnected || !input.accessibilityHeartbeatFresh) {
            add("行为学习服务当前没有新鲜心跳")
        }
        if (input.pendingParseCount > 0) add("仍有 ${input.pendingParseCount} 条线索等待处理")
        if (input.openGapCount > 0) add("仍有 ${input.openGapCount} 个覆盖缺口")
    }
    if (runtimeIssues.isNotEmpty()) {
        return HomeHealthSummary(
            level = HomeHealthLevel.ATTENTION,
            title = "采集链路需要处理",
            description = "权限存在不代表后台正在工作；解决下列问题前请勿依赖自动记账。",
            issues = runtimeIssues,
        )
    }

    return HomeHealthSummary(
        level = HomeHealthLevel.HEALTHY,
        title = "采集链路正常",
        description = "实时入口在线，当前没有待处理队列或已知覆盖缺口。",
        issues = emptyList(),
    )
}

