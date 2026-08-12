package com.hulk.pillsapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val eventTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private val probeScenarioLabels = mapOf(
    ProbeScenario.FOREGROUND to "前台",
    ProbeScenario.BACKGROUND to "后台",
    ProbeScenario.LOCK_SCREEN to "锁屏",
)

private val probeActionLabels = mapOf(
    ProbeAction.SUCCESS_PAYMENT to "成功支付",
    ProbeAction.FULL_REFUND to "全额退款",
    ProbeAction.PARTIAL_REFUND to "部分退款",
)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppHome(
                        onOpenNotificationAccessPage = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            permissionLauncher.launch(intent)
                        },
                        onRefresh = {
                            refreshState(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState(this)
    }

    private fun refreshState(context: Context) {
        NotificationListenerState.refreshPermission(context)
        NotificationEventRepository.refreshPermissionPackages(context)
        NotificationEventRepository.refreshEvents(context)
    }
}

@Composable
private fun AppHome(
    onOpenNotificationAccessPage: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val state by NotificationListenerState.state.collectAsState()
    val events by NotificationEventRepository.events.collectAsState()
    val enabledPackages by NotificationEventRepository.enabledPackages.collectAsState()
    val probeSessions by ProbeSessionRepository.completedSessions.collectAsState()
    val activeProbeSession by ProbeSessionRepository.activeSessionConfig.collectAsState()

    var packageInput by remember { mutableStateOf("") }
    var showClearDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    var channelName by remember { mutableStateOf("") }
    var scenario by remember { mutableStateOf(ProbeScenario.FOREGROUND) }
    var action by remember { mutableStateOf(ProbeAction.SUCCESS_PAYMENT) }
    var packageInputError by remember { mutableStateOf<String?>(null) }
    var packageHint by remember { mutableStateOf<String?>(null) }
    var reportExportPath by remember { mutableStateOf<String?>(null) }
    var reportText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start,
    ) {
        AppHomeHeaderSection(state)

        Spacer(modifier = Modifier.height(20.dp))
        ProbeConfigurationSection(
            activeProbeSession = activeProbeSession,
            channelName = channelName,
            packageInput = packageInput,
            packageInputError = packageInputError,
            packageHint = packageHint,
            scenario = scenario,
            action = action,
            onChannelNameChange = { channelName = it },
            onPackageInputChange = {
                packageInput = it
                val trimmed = it.trim()
                packageInputError = if (trimmed.isNotBlank() && !isValidAndroidPackageName(trimmed)) {
                    context.getString(R.string.package_name_format_error)
                } else {
                    null
                }
                packageHint = if (packageInputError == null) packageAvailabilityHint(context, trimmed) else null
            },
            onPreset = { presetChannel, presetPackage ->
                channelName = presetChannel
                packageInput = presetPackage
                packageInputError = null
                packageHint = packageAvailabilityHint(context, packageInput)
            },
            onScenarioChange = { scenario = it },
            onActionChange = { action = it },
            onOpenNotificationPermissionPage = onOpenNotificationAccessPage,
            onStartProbeSession = {
                val trimmedPackage = packageInput.trim()
                if (trimmedPackage.isBlank() || !isValidAndroidPackageName(trimmedPackage)) {
                    packageInputError = if (trimmedPackage.isBlank()) {
                        context.getString(R.string.package_name_required_error)
                    } else {
                        context.getString(R.string.package_name_format_error)
                    }
                } else {
                    val trimmedChannel = channelName.trim().ifBlank { trimmedPackage }
                    val started = ProbeSessionRepository.startSession(
                        channelName = trimmedChannel,
                        packageName = trimmedPackage,
                        scenario = scenario,
                        action = action,
                    )
                    if (started) {
                        NotificationEventRepository.addEnabledPackage(context, trimmedPackage)
                    }
                }
            },
            onEndProbeSession = {
                val result = ProbeSessionRepository.endSession()
                if (result == null) return@ProbeConfigurationSection
                val bundle = ProbeSessionRepository.completedSessions.value
                if (bundle.isNotEmpty()) {
                    reportText = buildProbeReportText(
                        ProbeReportBundle(
                            sessions = bundle,
                            appVersionName = BuildConfig.VERSION_NAME,
                            appVersionCode = BuildConfig.VERSION_CODE,
                        )
                    )
                }
            },
            needPermissionHint = { pkg ->
                packageInput.isNotBlank() && !NotificationEventRepository.isPackageEnabled(context, pkg)
            },
        )

        Spacer(modifier = Modifier.height(12.dp))
        ProbeReportSection(
            probeSessions = probeSessions,
            context = context,
            reportText = reportText,
            reportExportPath = reportExportPath,
            onReportTextChange = { reportText = it },
            onReportExportPathChange = { reportExportPath = it },
            onRefresh = onRefresh,
        )

        Spacer(modifier = Modifier.height(20.dp))
        ProbeWhitelistSection(
            context = context,
            enabledPackages = enabledPackages,
            packageInput = packageInput,
            packageInputError = packageInputError,
            packageHint = packageHint,
            onPackageInputChange = {
                packageInput = it
                val trimmed = it.trim()
                packageInputError = if (trimmed.isNotEmpty() && !isValidAndroidPackageName(trimmed)) {
                    context.getString(R.string.package_name_format_error)
                } else {
                    null
                }
                packageHint = if (packageInputError == null) packageAvailabilityHint(context, trimmed) else null
            },
            onAddPackage = {
                val trimmed = packageInput.trim()
                val canAdd = trimmed.isNotBlank() && isValidAndroidPackageName(trimmed)
                if (!canAdd) {
                    packageInputError = if (trimmed.isBlank()) {
                        context.getString(R.string.package_name_required_error)
                    } else {
                        context.getString(R.string.package_name_format_error)
                    }
                    return@ProbeWhitelistSection
                }
                NotificationEventRepository.addEnabledPackage(context, trimmed)
                packageInput = ""
                packageHint = null
                packageInputError = null
            },
        )

        Spacer(modifier = Modifier.height(20.dp))
        RawEventsSection(events = events)

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = { showClearDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.clear_test_data_label))
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(stringResource(R.string.t03_privacy_notice))

        if (showClearDialog) {
            ClearDataDialog(
                onDismiss = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    coroutineScope.launch {
                        NotificationEventRepository.clearAll(context)
                        ProbeSessionRepository.clearCompletedSessions()
                        onRefresh()
                    }
                },
            )
        }
    }
}

@Composable
private fun AppHomeHeaderSection(state: ListenerStatusState) {
    Text(text = "${stringResource(R.string.app_name_label)}：${stringResource(R.string.app_name)}")
    Text(text = "${stringResource(R.string.version_name_label)}：${BuildConfig.VERSION_NAME}")
    Text(text = "${stringResource(R.string.version_code_label)}：${BuildConfig.VERSION_CODE}")
    Text(text = "${stringResource(R.string.build_time_label)}：${BuildConfig.BUILD_TIME}")
    Text(text = stringResource(R.string.app_running_label))

    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.notification_permission_label))
    Text(
        text = stringResource(
            R.string.notification_permission_status_format,
            NotificationListenerState.permissionLabel(state.permissionEnabled),
        )
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(text = stringResource(R.string.notification_service_label))
    Text(
        text = stringResource(
            R.string.notification_service_connection_format,
            NotificationListenerState.serviceConnectionLabel(state.isConnected),
        )
    )
    Text(text = "${stringResource(R.string.notification_last_connected_label)}：${formatEventTime(state.lastConnectedAtMs)}")
    Text(text = "${stringResource(R.string.notification_last_disconnected_label)}：${formatEventTime(state.lastDisconnectedAtMs)}")
}

@Composable
private fun ProbeConfigurationSection(
    activeProbeSession: ProbeSessionConfig?,
    channelName: String,
    packageInput: String,
    packageInputError: String?,
    packageHint: String?,
    scenario: ProbeScenario,
    action: ProbeAction,
    onChannelNameChange: (String) -> Unit,
    onPackageInputChange: (String) -> Unit,
    onPreset: (String, String) -> Unit,
    onScenarioChange: (ProbeScenario) -> Unit,
    onActionChange: (ProbeAction) -> Unit,
    onOpenNotificationPermissionPage: () -> Unit,
    onStartProbeSession: () -> Unit,
    onEndProbeSession: () -> Unit,
    needPermissionHint: (String) -> Boolean,
) {
    Spacer(modifier = Modifier.height(12.dp))
    Text(text = stringResource(R.string.t04_probe_title))
    Text(text = if (activeProbeSession == null) "未开始" else "运行中：${activeProbeSession.channelName}")

    Button(onClick = onOpenNotificationPermissionPage, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.go_to_access_settings_label))
    }
    OutlinedTextField(
        value = channelName,
        onValueChange = onChannelNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.t04_channel_name_label)) },
    )
    Spacer(modifier = Modifier.height(8.dp))
    OutlinedTextField(
        value = packageInput,
        onValueChange = onPackageInputChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.package_name_label)) },
        isError = packageInputError != null,
        supportingText = {
            val tip = packageInputError ?: packageHint
            if (tip != null) {
                Text(text = tip)
            }
        },
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { onPreset("微信", "com.tencent.mm") },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.t04_preset_wechat))
        }
        Button(
            onClick = { onPreset("支付宝", "com.eg.android.Alipay") },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.t04_preset_alipay))
        }
    }

    Text(text = "${stringResource(R.string.t04_scenario_label)}：")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeScenario.values().forEach { item ->
            FilterChip(
                selected = scenario == item,
                onClick = { onScenarioChange(item) },
                label = { Text(probeScenarioLabels[item] ?: item.name) },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(text = "${stringResource(R.string.t04_action_label)}：")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProbeAction.values().forEach { item ->
            FilterChip(
                selected = action == item,
                onClick = { onActionChange(item) },
                label = { Text(probeActionLabels[item] ?: item.name) },
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    if (activeProbeSession == null) {
        Button(onClick = onStartProbeSession, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.t04_start_probe))
        }
        if (needPermissionHint(packageInput)) {
            Text(text = stringResource(R.string.t04_target_package_required_permission))
        }
    } else {
        Button(onClick = onEndProbeSession, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.t04_end_probe))
        }
    }
}

@Composable
private fun ProbeReportSection(
    probeSessions: List<ProbeSessionResult>,
    context: Context,
    reportText: String,
    reportExportPath: String?,
    onReportTextChange: (String) -> Unit,
    onReportExportPathChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    Text(text = stringResource(R.string.t04_probe_report_title))
    Text(text = stringResource(R.string.t04_coverage_matrix_title, probeSessions.size))
    if (probeSessions.isEmpty()) {
        Text(text = stringResource(R.string.t04_no_sessions))
    } else {
        probeSessions.forEachIndexed { index, session ->
            val reportIdx = index + 1
            Card(
                colors = CardDefaults.cardColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = "$reportIdx. ${session.config.channelName} / ${session.config.packageName}")
                    Text(text = "场景=${probeScenarioLabels[session.config.scenario]} 动作=${probeActionLabels[session.config.action]}")
                    Text(text = "开始=${formatEventTime(session.config.startedAtMs)} 结束=${formatEventTime(session.endedAtMs)}")
                    Text(text = "覆盖结果：${session.coverageText()}")
                    Text(text = "字段覆盖(金额/商户/订单/语义): ${session.amountCoverage()} / ${session.merchantCoverage()} / ${session.orderCoverage()} / ${session.semanticCoverage()}")
                    Text(text = "同 key 复用次数：${session.observations.count { it.reusedNotificationKey }}")
                    if (session.observations.isNotEmpty()) {
                        Text(text = "通知更新标记:")
                        session.observations.forEachIndexed { obsIndex, obs ->
                            val prefix = if (obs.reusedNotificationKey) "复用" else "首次"
                            Text(text = "${obsIndex + 1}. key=${obs.notificationKeyHash} $prefix posted=${formatEventTime(obs.postedAtMs)}")
                        }
                    } else {
                        Text(text = "未收到通知（已记录会话）")
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = {
                val report = buildProbeReportText(
                    ProbeReportBundle(
                        sessions = probeSessions,
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE,
                    )
                )
                onReportTextChange(report)
                val clipData = ClipData.newPlainText("t04_probe_report", report)
                clipboardManager.setPrimaryClip(clipData)
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.t04_copy_report))
        }
        Button(
            onClick = {
                val report = buildProbeReportText(
                    ProbeReportBundle(
                        sessions = probeSessions,
                        appVersionName = BuildConfig.VERSION_NAME,
                        appVersionCode = BuildConfig.VERSION_CODE,
                    )
                )
                onReportTextChange(report)
                onReportExportPathChange(writeProbeReportToPrivateFile(context = context, reportText = report))
                onRefresh()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(text = stringResource(R.string.t04_export_report))
        }
    }
    reportExportPath?.let {
        Text(text = stringResource(R.string.t04_saved_report_path) + it)
    }
    if (reportText.isNotBlank()) {
        SelectionContainer {
            Text(text = reportText)
        }
    }
}

@Composable
private fun ProbeWhitelistSection(
    context: Context,
    enabledPackages: List<String>,
    packageInput: String,
    packageInputError: String?,
    packageHint: String?,
    onPackageInputChange: (String) -> Unit,
    onAddPackage: () -> Unit,
) {
    Text(text = stringResource(R.string.enabled_packages_title))
    OutlinedTextField(
        value = packageInput,
        onValueChange = onPackageInputChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.package_name_label)) },
        isError = packageInputError != null,
        supportingText = {
            val tip = packageInputError ?: packageHint
            if (tip != null) {
                Text(text = tip)
            }
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
    Button(onClick = onAddPackage, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.add_package_label))
    }

    Spacer(modifier = Modifier.height(8.dp))
    if (enabledPackages.isEmpty()) {
        Text(text = stringResource(R.string.no_enabled_packages))
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            enabledPackages.forEach { packageName ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = packageName, modifier = Modifier.weight(1f))
                    TextButton(onClick = { NotificationEventRepository.removeEnabledPackage(context, packageName) }) {
                        Text(text = stringResource(R.string.remove_package_label))
                    }
                }
            }
        }
    }
}

@Composable
private fun RawEventsSection(events: List<NotificationEvent>) {
    Text(text = stringResource(R.string.notification_events_title))
    if (events.isEmpty()) {
        Text(text = stringResource(R.string.no_events))
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            events.forEach { event ->
                Card(
                    colors = CardDefaults.cardColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "${event.packageName} · ${event.notificationKey}")
                        Text(text = "标题：${event.title}")
                        Text(text = "正文：${event.body}")
                        Text(text = "发布：${formatEventTime(event.postedAtMs)}")
                        Text(text = "接收：${formatEventTime(event.receivedAtMs)}")
                        Text(text = "内容哈希：${event.contentHash}")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClearDataDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clear_data_confirm_title)) },
        text = { Text(stringResource(R.string.clear_data_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun formatEventTime(timestampMs: Long?, zoneId: ZoneId = ZoneId.systemDefault()): String {
    return timestampMs?.let {
        Instant.ofEpochMilli(it).atZone(zoneId).format(eventTimeFormatter)
    } ?: "未发生"
}

private fun packageAvailabilityHint(context: Context, value: String): String? {
    val pkg = value.trim()
    if (pkg.isBlank() || !isValidAndroidPackageName(pkg)) return null
    return try {
        context.packageManager.getPackageInfo(pkg, 0)
        null
    } catch (_: PackageManager.NameNotFoundException) {
        context.getString(R.string.package_not_found_warning)
    }
}
