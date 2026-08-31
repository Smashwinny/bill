package com.hulk.pillsapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hulk.pillsapp.ledger.LedgerKernel
import com.hulk.pillsapp.ledger.BehaviorCandidateEntity
import com.hulk.pillsapp.ledger.BehaviorCandidateState
import com.hulk.pillsapp.ledger.BehaviorDebugEvidenceStore
import com.hulk.pillsapp.ledger.BehaviorDecision
import com.hulk.pillsapp.ledger.BehaviorKind
import com.hulk.pillsapp.ledger.SmsBackfill
import com.hulk.pillsapp.ledger.DebtAccountStatus
import com.hulk.pillsapp.ledger.DebtEventKind
import com.hulk.pillsapp.ledger.DebtEvidenceStrength
import com.hulk.pillsapp.ledger.StatementAuthority
import com.hulk.pillsapp.ledger.StatementImportRepository
import com.hulk.pillsapp.ledger.StatementImportStatus
import com.hulk.pillsapp.ledger.StatementImportUiState
import com.hulk.pillsapp.ledger.StatementPreviewIssue
import com.hulk.pillsapp.ledger.StatementSourceKind
import com.hulk.pillsapp.ledger.ManualLedgerMigrationRepository
import com.hulk.pillsapp.ledger.ManualLedgerImportState
import com.hulk.pillsapp.ui.AutomaticLedgerTheme
import com.hulk.pillsapp.ui.HomeHealthInput
import com.hulk.pillsapp.ui.HomeHealthLevel
import com.hulk.pillsapp.ui.deriveHomeHealth
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.concurrent.thread

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

private val debtStatusLabels = mapOf(
    DebtAccountStatus.SUSPECTED to "疑似线索",
    DebtAccountStatus.IDENTIFIED to "已识别账户",
    DebtAccountStatus.BASELINED to "已有权威余额基线",
    DebtAccountStatus.RECONCILABLE to "可连续对账",
    DebtAccountStatus.CONFLICTED to "身份冲突",
    DebtAccountStatus.DORMANT to "休眠",
    DebtAccountStatus.EXCLUDED to "已排除",
)

private val debtEventLabels = mapOf(
    DebtEventKind.ACCOUNT_HINT to "账户线索",
    DebtEventKind.PURCHASE_ON_CREDIT to "信用消费线索",
    DebtEventKind.BILL_NOTICE to "账单线索",
    DebtEventKind.REPAYMENT to "还款成功线索",
    DebtEventKind.REFUND_TO_LIABILITY to "退款冲抵线索",
    DebtEventKind.INTEREST_OR_FEE to "息费线索",
    DebtEventKind.OVERDUE to "逾期线索",
)

private val debtStrengthLabels = mapOf(
    DebtEvidenceStrength.HINT to "弱提示",
    DebtEvidenceStrength.OBSERVATIONAL to "观察证据",
    DebtEvidenceStrength.IMPORTED_UNVERIFIED to "导入待校验",
    DebtEvidenceStrength.AUTHORITATIVE to "权威已校验",
)

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshState(this)
    }

    private val refreshTick = kotlinx.coroutines.flow.MutableStateFlow(0)
    private val manualImportNavigationTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[android.Manifest.permission.READ_SMS] == true) {
            LedgerKernel.backfillSms(this)
        }
        refreshState(this)
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshState(this)
    }

    private val statementFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { StatementImportRepository.preview(this, it) }
    }

    private val manualLedgerFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { ManualLedgerMigrationRepository.preview(this, it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleManualLedgerIntent(intent)
        refreshState(this)
        setContent {
            AutomaticLedgerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val tick by refreshTick.collectAsState()
                    val migrationTick by manualImportNavigationTick.collectAsState()
                    AppHome(
                        refreshTick = tick,
                        manualImportNavigationTick = migrationTick,
                        onOpenAccessibilitySettings = {
                            permissionLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onRequestNotificationPermission = {
                            if (android.os.Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                refreshState(this@MainActivity)
                            }
                        },
                        onSafeLaunchSensitive = { packageName ->
                            startActivity(SensitiveAppLaunchActivity.intent(this@MainActivity, packageName))
                        },
                        onOpenUsageAccessSettings = {
                            permissionLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                        onRestoreSensitiveMode = {
                            val expectedSessionId = SensitiveAppMode.currentSession(this@MainActivity)?.id
                            if (expectedSessionId != null) {
                                thread(name = "sensitive-mode-manual-restore") {
                                    SensitiveAppMode.restore(this@MainActivity, expectedSessionId)
                                    runOnUiThread { refreshState(this@MainActivity) }
                                }
                            }
                        },
                        onOpenNotificationAccessPage = {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            permissionLauncher.launch(intent)
                        },
                        onRequestSmsPermissions = {
                            smsPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_SMS,
                                    android.Manifest.permission.RECEIVE_SMS,
                                )
                            )
                        },
                        onOpenAutostartSettings = { openAutostartSettings() },
                        onRequestBatteryUnrestricted = { requestBatteryUnrestricted() },
                        onSelectStatementFile = {
                            statementFileLauncher.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/tab-separated-values",
                                    "text/plain",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/zip",
                                    "application/octet-stream",
                                )
                            )
                        },
                        onSelectManualLedgerFile = {
                            manualLedgerFileLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                        onRefresh = {
                            refreshState(this@MainActivity)
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleManualLedgerIntent(intent)
    }

    private fun handleManualLedgerIntent(intent: Intent?) {
        if (intent?.action != "com.hulk.pillsapp.IMPORT_MANUAL_LEDGER") return
        val uri = intent.data ?: return
        ManualLedgerMigrationRepository.preview(this, uri)
        manualImportNavigationTick.value += 1
    }

    override fun onResume() {
        super.onResume()
        ManualLedgerMigrationRepository.syncFromInstalledManualLedger(this)
        refreshState(this)
    }

    private fun refreshState(context: Context) {
        NotificationListenerState.refreshPermission(context)
        NotificationEventRepository.refreshPermissionPackages(context)
        NotificationEventRepository.refreshEvents(context)
        BehaviorAccessibilityState.refreshPermission(context)
        LedgerKernel.refreshStatusAsync()
        refreshTick.value++
    }

    /** HyperOS 自启动管理页（V1.1 §2 保活设置）；失败回退应用详情页。 */
    private fun openAutostartSettings() {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // 尝试下一个入口
            }
        }
    }

    private fun requestBatteryUnrestricted() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: Exception) {
            // 系统不支持时忽略，用户可按引导手动设置
        }
    }
}

private enum class AppDestination(
    val label: String,
    val symbol: String,
    val title: String,
    val subtitle: String,
) {
    OVERVIEW("首页", "账", "自动账本", "今天需要关注什么"),
    REVIEW("待核验", "核", "待核验", "付款、退款与自动记录"),
    RECONCILE("对账", "对", "对账中心", "来源覆盖、账单与负债"),
    SETTINGS("设置", "设", "设置与诊断", "权限、保活和开发工具"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppHome(
    refreshTick: Int,
    manualImportNavigationTick: Int,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSafeLaunchSensitive: (String) -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onRestoreSensitiveMode: () -> Unit,
    onOpenNotificationAccessPage: () -> Unit,
    onRequestSmsPermissions: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
    onSelectStatementFile: () -> Unit,
    onSelectManualLedgerFile: () -> Unit,
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
    var showDeveloperTools by rememberSaveable { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(AppDestination.OVERVIEW) }

    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(manualImportNavigationTick) {
        if (manualImportNavigationTick > 0) destination = AppDestination.RECONCILE
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(destination.title, style = MaterialTheme.typography.titleLarge)
                        Text(
                            destination.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) { Text("刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                AppDestination.entries.forEach { item ->
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = {
                            Text(
                                item.symbol,
                                fontWeight = if (destination == item) FontWeight.Bold else FontWeight.Medium,
                            )
                        },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { contentPadding ->
        key(destination) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                when (destination) {
                    AppDestination.OVERVIEW -> LedgerOverviewPage(
                        listenerState = state,
                        onNavigate = { destination = it },
                    )

                    AppDestination.REVIEW -> {
                        PageIntro(
                            eyebrow = "INBOX",
                            title = "先确认，再入账",
                            description = "系统发现的行为只是一条线索。金额和用途由你确认后，才会成为正式成功记录。",
                        )
                        LedgerPanel {
                            BehaviorLearningSection(
                                refreshTick = refreshTick,
                                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onSafeLaunchSensitive = onSafeLaunchSensitive,
                                onOpenUsageAccessSettings = onOpenUsageAccessSettings,
                                onRestoreSensitiveMode = onRestoreSensitiveMode,
                                onRefresh = onRefresh,
                            )
                        }
                    }

                    AppDestination.RECONCILE -> {
                        PageIntro(
                            eyebrow = "RECONCILE",
                            title = "用权威来源查漏补缺",
                            description = "通知和行为用于及时发现，账单与账户余额用于确认有没有漏记、多记或重复还款。",
                        )
                        LedgerPanel {
                            SourceCoverageAndImportSection(onSelectStatementFile, onSelectManualLedgerFile)
                        }
                        LedgerPanel { DebtDiscoverySection() }
                        LedgerPanel { KernelStatusSection() }
                    }

                    AppDestination.SETTINGS -> {
                        PageIntro(
                            eyebrow = "SETTINGS",
                            title = "让后台状态一眼可见",
                            description = "授权、真实连接、保活和诊断分开显示；已授权不再等同于正在采集。",
                        )
                        LedgerPanel { AppHomeHeaderSection(state) }
                        LedgerPanel {
                            M2ChannelSection(
                                refreshTick = refreshTick,
                                onRequestSmsPermissions = onRequestSmsPermissions,
                                onOpenAutostartSettings = onOpenAutostartSettings,
                                onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                            )
                        }
                        LedgerPanel {
                            Button(
                                onClick = { showDeveloperTools = !showDeveloperTools },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(if (showDeveloperTools) "收起开发测试工具" else "展开开发测试工具")
                            }
                            Text(
                                "通知探针、原始事件和测试数据清理默认收起，不影响日常记账。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (showDeveloperTools) {
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
                    }
                }
            }
        }
    }
}

@Composable
private fun LedgerOverviewPage(
    listenerState: ListenerStatusState,
    onNavigate: (AppDestination) -> Unit,
) {
    val accessibility by BehaviorAccessibilityState.state.collectAsState()
    val status by LedgerKernel.status.collectAsState()
    val health = deriveHomeHealth(
        HomeHealthInput(
            notificationPermission = listenerState.permissionEnabled,
            notificationConnected = listenerState.isConnected,
            accessibilityPermission = accessibility.permissionEnabled,
            accessibilityConnected = accessibility.serviceConnected,
            accessibilityHeartbeatFresh = status.a11yHeartbeatFresh,
            pendingParseCount = status.pendingParseCount,
            openGapCount = status.openGapCount,
        )
    )
    val healthColors = when (health.level) {
        HomeHealthLevel.HEALTHY -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        HomeHealthLevel.ATTENTION -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        HomeHealthLevel.SETUP_REQUIRED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    }

    PageIntro(
        eyebrow = "PERSONAL LEDGER",
        title = "每一笔，都先有证据",
        description = "自动发现付款和退款；不确定的内容交给你确认，最后再用账单完成对账。",
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = healthColors.first),
    ) {
        Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(health.title, style = MaterialTheme.typography.headlineSmall, color = healthColors.second)
            Text(health.description, color = healthColors.second)
            health.issues.take(4).forEach { issue ->
                Text("• $issue", style = MaterialTheme.typography.bodyMedium, color = healthColors.second)
            }
            if (health.level != HomeHealthLevel.HEALTHY) {
                FilledTonalButton(onClick = { onNavigate(AppDestination.SETTINGS) }) {
                    Text("查看设置与诊断")
                }
            }
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricCard("待核验", (status.behaviorPendingCount + status.behaviorAutoRecordedCount).toString(), Modifier.weight(1f))
        MetricCard("已确认", status.behaviorConfirmedCount.toString(), Modifier.weight(1f))
        MetricCard("覆盖缺口", status.openGapCount.toString(), Modifier.weight(1f))
    }

    LedgerPanel(title = "现在可以做什么", subtitle = "把高频动作放在首页，复杂设置留在后面") {
        NavigationActionCard(
            title = "处理待核验线索",
            description = "确认金额、用途，或排除不是付款的行为",
            badge = status.behaviorPendingCount + status.behaviorAutoRecordedCount,
            onClick = { onNavigate(AppDestination.REVIEW) },
        )
        NavigationActionCard(
            title = "检查来源覆盖",
            description = "查看哪些来源只有实时线索、还没有账单兜底",
            badge = status.sourcesWithoutStatementCount,
            onClick = { onNavigate(AppDestination.RECONCILE) },
        )
    }

    LedgerPanel(title = "后续开发路线", subtitle = "前端骨架先稳定，数据能力按可信度逐层接入") {
        RoadmapRow("1", "采集健康", "真实连接、队列和覆盖缺口")
        RoadmapRow("2", "统一待核验", "行为、通知、短信进入同一个收件箱")
        RoadmapRow("3", "正式账本与统计", "日/月账目、分类、退款和负债")
        RoadmapRow("4", "备份与同步", "加密导出、换机恢复，再考虑远端同步")
    }
}

@Composable
private fun PageIntro(eyebrow: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            eyebrow,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LedgerPanel(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun RowScope.MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NavigationActionCard(
    title: String,
    description: String,
    badge: Long,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    badge.toString(),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun RoadmapRow(number: String, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val statementSourceLabels = mapOf(
    StatementSourceKind.ALIPAY to "疑似支付宝格式",
    StatementSourceKind.WECHAT to "疑似微信格式",
    StatementSourceKind.BANK to "疑似银行/银联格式",
    StatementSourceKind.UNKNOWN to "未识别来源",
)

private val statementAuthorityLabels = mapOf(
    StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED to "格式已识别，来源/完整性待校验",
    StatementAuthority.PERIOD_VALIDATED to "账期已校验",
    StatementAuthority.AUTHORITATIVE to "权威来源",
)

private val statementImportStatusLabels = mapOf(
    StatementImportStatus.IMPORTING to "未完成/写入中（重新选择同一文件可续传）",
    StatementImportStatus.IMPORT_FAILED to "写入中断，重新选择同一文件可续传",
    StatementImportStatus.IMPORTED_UNVERIFIED to "证据完整，来源待校验",
    StatementImportStatus.PERIOD_VALIDATED to "账期已校验",
    StatementImportStatus.RECONCILED to "已对账",
)

private val statementIssueLabels = mapOf(
    StatementPreviewIssue.EMPTY_FILE to "文件为空",
    StatementPreviewIssue.FILE_TOO_LARGE to "文件超过 25 MiB 安全上限",
    StatementPreviewIssue.ENCRYPTED_OR_UNREADABLE_ARCHIVE to "压缩包已加密或无法读取，请先用官方密码解压后选择 CSV/XLSX",
    StatementPreviewIssue.AMBIGUOUS_CONTAINER to "文件含多个账单表或多个候选文件；为避免漏读，请分别导出或拆分后逐个选择",
    StatementPreviewIssue.UNSUPPORTED_FORMAT to "尚不支持该文件，请选择 CSV、TSV、XLSX 或包含它们的未加密 ZIP",
    StatementPreviewIssue.UNRECOGNIZED_STATEMENT_SOURCE to "表格有时间和金额列，但无法确认是支付宝、微信或银行账单，已拒绝冒充官方来源",
    StatementPreviewIssue.HEADER_NOT_FOUND to "未找到交易时间和金额列",
    StatementPreviewIssue.NO_VALID_ROWS to "没有可安全导入的账单行",
    StatementPreviewIssue.INVALID_ROWS_PRESENT to "存在无法解析的数据行，本次禁止部分导入",
    StatementPreviewIssue.MALFORMED_TABLE to "表格引号或行列结构损坏，已拒绝部分解析",
    StatementPreviewIssue.CELL_TOO_LARGE to "表格单元格超过 4096 字符安全上限",
    StatementPreviewIssue.UNSUPPORTED_CURRENCY to "存在未明确标注为人民币的金额；当前版本为避免币种错记而拒绝导入",
    StatementPreviewIssue.TOO_MANY_ROWS to "账单超过 100000 行安全上限",
    StatementPreviewIssue.IMPORT_FAILED to "账单入库失败，已保留原数据不变",
)

@Composable
private fun SourceCoverageAndImportSection(
    onSelectStatementFile: () -> Unit,
    onSelectManualLedgerFile: () -> Unit,
) {
    val status by LedgerKernel.status.collectAsState()
    val importState by StatementImportRepository.state.collectAsState()
    val manualImportState by ManualLedgerMigrationRepository.state.collectAsState()
    var showAllImports by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(20.dp))
    Text(text = stringResource(R.string.m4_section_title))
    Text(
        text = stringResource(
            R.string.m4_coverage_counts,
            status.coverageSourceCount,
            status.sourcesWithoutStatementCount,
            status.statementImportCount,
            status.statementRowCount,
            status.statementIncompleteImportCount,
            status.statementIntegrityFailureCount,
        )
    )
    Text(text = stringResource(R.string.m4_authority_warning))
    status.sourceCoverage.forEach { item ->
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            colors = CardDefaults.cardColors(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = item.label)
                Text(text = "${item.channel} · ${item.observationCount} 条观察")
                if (item.firstSeenAtMs != null && item.lastSeenAtMs != null) {
                    Text(text = "观察区间：${formatEventTime(item.firstSeenAtMs)} 至 ${formatEventTime(item.lastSeenAtMs)}")
                }
                if (item.statementObservedRowFromMs != null && item.statementObservedRowToMs != null) {
                    Text(text = "文件内交易行时间：${formatEventTime(item.statementObservedRowFromMs)} 至 ${formatEventTime(item.statementObservedRowToMs)}")
                }
                item.authority?.let { Text(text = statementAuthorityLabels[it] ?: it.name) }
                Text(text = "覆盖结论：${item.gapLabel}")
            }
        }
    }
    if (status.sourceCoverage.isEmpty()) {
        Text(text = stringResource(R.string.m4_no_sources))
    }

    Spacer(modifier = Modifier.height(12.dp))
    Button(
        onClick = onSelectStatementFile,
        enabled = importState !is StatementImportUiState.Importing,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.m4_select_statement))
    }
    when (val state = importState) {
        StatementImportUiState.Idle -> Text(text = stringResource(R.string.m4_supported_formats))
        StatementImportUiState.Reading -> Text(text = stringResource(R.string.m4_reading))
        StatementImportUiState.Importing -> Text(text = stringResource(R.string.m4_importing))
        is StatementImportUiState.Failed -> {
            Text(text = "无法导入：${statementIssueLabels[state.issue] ?: state.issue.name}")
            Button(
                onClick = { StatementImportRepository.reset() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(text = stringResource(R.string.m4_close_result)) }
        }
        is StatementImportUiState.PreviewReady -> {
            val preview = state.preview
            Text(text = "文件：${preview.displayName}")
            Text(text = "识别：${statementSourceLabels[preview.sourceKind]} · ${preview.format.name}")
            Text(text = "行数：表头后非空 ${preview.rawRowCount} · 有效 ${preview.validRowCount} · 标准尾注 ${preview.ignoredFooterRowCount} · 失败 ${preview.invalidRowCount}")
            if (preview.observedRowFromMs != null && preview.observedRowToMs != null) {
                Text(text = "交易行时间范围（不是完整账期）：${formatEventTime(preview.observedRowFromMs)} 至 ${formatEventTime(preview.observedRowToMs)}")
            }
            preview.issues.forEach { issue -> Text(text = "检查：${statementIssueLabels[issue] ?: issue.name}") }
            Text(text = stringResource(R.string.m4_preview_privacy))
            Button(
                onClick = { StatementImportRepository.confirmImport() },
                enabled = preview.canImport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(text = stringResource(R.string.m4_confirm_import)) }
        }
        is StatementImportUiState.Imported -> {
            Text(
                text = if (state.result.duplicateFile) {
                    stringResource(R.string.m4_duplicate_file)
                } else {
                    stringResource(
                        R.string.m4_import_success,
                        state.result.insertedRows,
                    )
                }
            )
            Button(
                onClick = { StatementImportRepository.reset() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(text = stringResource(R.string.m4_close_result)) }
        }
    }

    if (status.statementImports.isNotEmpty()) {
        Text(text = stringResource(R.string.m4_import_history))
        val visibleImports = if (showAllImports) status.statementImports else status.statementImports.take(20)
        visibleImports.forEach { imported ->
            Text(
                text = "${imported.displayName} · ${statementSourceLabels[imported.sourceKind]} · " +
                    "${imported.linkedRowCount} 行 · ${statementImportStatusLabels[imported.status]} · " +
                    "${statementAuthorityLabels[imported.authority]}"
            )
        }
        if (!showAllImports && status.statementImports.size > visibleImports.size) {
            Button(
                onClick = { showAllImports = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(text = "展开其余 ${status.statementImports.size - visibleImports.size} 个解析批次") }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    Text(text = "手工账本迁移")
    Text(text = "接收“本地账本”的 manual-ledger-v1 文件；用户手工确认的数据不会冒充官方账单。")
    Button(
        onClick = onSelectManualLedgerFile,
        enabled = manualImportState !is ManualLedgerImportState.Importing,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(text = "选择本地账本迁移文件") }
    when (val state = manualImportState) {
        ManualLedgerImportState.Idle -> Text(text = "确认前只预览，不修改自动账本。")
        ManualLedgerImportState.Reading -> Text(text = "正在读取迁移文件…")
        ManualLedgerImportState.Importing -> Text(text = "正在写入加密账本…")
        is ManualLedgerImportState.Failed -> {
            Text(text = "无法迁移：${state.message}")
            TextButton(onClick = { ManualLedgerMigrationRepository.reset() }) { Text("关闭") }
        }
        is ManualLedgerImportState.PreviewReady -> {
            val preview = state.preview
            Text(text = "可迁移 ${preview.rows.size} 条 · 异常 ${preview.invalidRows} 条")
            preview.rows.take(3).forEach { row ->
                Text(text = "${row.category} · ¥%d.%02d · ${formatEventTime(row.occurredAtMs)}".format(
                    row.amountCents / 100,
                    kotlin.math.abs(row.amountCents % 100),
                ))
            }
            Text(text = "相同流水编号会自动跳过，重复选择不会重复记账。")
            Button(
                onClick = { ManualLedgerMigrationRepository.confirm() },
                enabled = preview.canImport,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("确认迁移") }
        }
        is ManualLedgerImportState.Imported -> {
            Text(
                text = "本机账本已同步：新增 ${state.result.inserted} 条，更新 ${state.result.updated} 条，" +
                    "未变化 ${state.result.duplicates} 条，移除 ${state.result.discarded} 条"
            )
            TextButton(onClick = { ManualLedgerMigrationRepository.reset() }) { Text("完成") }
        }
    }
}

@Composable
private fun DebtDiscoverySection() {
    val status by LedgerKernel.status.collectAsState()
    var showAllCandidates by remember { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.m3_debt_section_title))
    Text(
        text = stringResource(
            R.string.m3_debt_counts_format,
            status.debtAccountCount,
            status.suspectedDebtCount,
            status.identifiedDebtCount,
            status.baselinedDebtCount,
        )
    )
    Text(
        text = stringResource(
            R.string.m3_discovery_progress_format,
            status.discoveryScannedCount,
            status.eligibleRevisionCount,
            status.debtDiscoveryPendingCount,
            status.discoveryFailedCount,
        )
    )
    Text(
        text = stringResource(
            R.string.m3_repayment_pending_format,
            status.repaymentsAwaitingBaselineCount,
        )
    )
    Text(text = stringResource(R.string.m3_candidate_notice))
    Text(text = stringResource(R.string.m3_source_limit_notice))
    Button(
        onClick = { LedgerKernel.runDebtDiscovery() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = stringResource(R.string.m3_rescan))
    }
    if (status.debtAccounts.isEmpty()) {
        Text(text = stringResource(R.string.m3_no_candidates))
    } else {
        val displayedAccounts = if (showAllCandidates) {
            status.debtAccounts
        } else {
            status.debtAccounts.take(20)
        }
        displayedAccounts.forEach { account ->
            Card(
                colors = CardDefaults.cardColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = account.displayLabel)
                    Text(text = "状态：${debtStatusLabels[account.status] ?: account.status.name}")
                    Text(text = "最近线索：${debtEventLabels[account.lastEventKind] ?: account.lastEventKind.name}")
                    Text(text = "证据级别：${debtStrengthLabels[account.lastEvidenceStrength] ?: account.lastEvidenceStrength.name}")
                    account.dueDayOfMonth?.let { Text(text = "检测到还款日：每月 $it 日（待账单确认）") }
                    Text(text = "最近发现：${formatEventTime(account.lastSeenAtMs)}")
                }
            }
        }
        if (!showAllCandidates && status.debtAccounts.size > 20) {
            Button(
                onClick = { showAllCandidates = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.m3_more_candidates, status.debtAccounts.size - 20))
            }
        }
    }
}

@Composable
private fun AppHomeHeaderSection(state: ListenerStatusState) {
    Text(text = "应用与通知监听", style = MaterialTheme.typography.titleLarge)
    Text(
        text = "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "构建 ${BuildConfig.BUILD_TIME}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "通知读取授权：${NotificationListenerState.permissionLabel(state.permissionEnabled)}",
        fontWeight = FontWeight.SemiBold,
    )
    Text(
        text = "实时监听：${NotificationListenerState.serviceConnectionLabel(state.isConnected)}",
        color = if (state.isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        fontWeight = FontWeight.SemiBold,
    )
    Text(text = "${stringResource(R.string.notification_last_connected_label)}：${formatEventTime(state.lastConnectedAtMs)}")
    Text(text = "${stringResource(R.string.notification_last_disconnected_label)}：${formatEventTime(state.lastDisconnectedAtMs)}")
}

private val behaviorStateLabels = mapOf(
    BehaviorCandidateState.PENDING to "待确认（尚未记为成功）",
    BehaviorCandidateState.CONFIRMED to "已由你确认",
    BehaviorCandidateState.REJECTED to "已排除",
    BehaviorCandidateState.AUTO_RECORDED to "已自动记账（可撤销）",
    BehaviorCandidateState.UNDONE to "自动记账已撤销",
)

private data class SensitiveRegistryUiState(
    val profiles: List<SensitiveProfileView> = emptyList(),
    val suggestion: SensitiveAppSuggestion? = null,
    val recentExternalApp: LaunchableApp? = null,
    val storageHealthy: Boolean = true,
    val loading: Boolean = true,
)

@Composable
private fun BehaviorLearningSection(
    refreshTick: Int,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onSafeLaunchSensitive: (String) -> Unit,
    onOpenUsageAccessSettings: () -> Unit,
    onRestoreSensitiveMode: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accessibility by BehaviorAccessibilityState.state.collectAsState()
    val status by LedgerKernel.status.collectAsState()
    val notificationGranted = remember(refreshTick) {
        SensitiveModeNotifier.canNotify(context)
    }
    val sensitiveControlGranted = remember(refreshTick) {
        SensitiveAppMode.hasControlPermission(context)
    }
    val usageAccessGranted = remember(refreshTick) { UsageAccessState.isGranted(context) }
    var debugEvidenceEnabled by remember(refreshTick) {
        mutableStateOf(BehaviorDebugEvidenceStore.isEnabled(context))
    }
    var registryUi by remember { mutableStateOf(SensitiveRegistryUiState()) }
    LaunchedEffect(refreshTick, usageAccessGranted) {
        registryUi = withContext(Dispatchers.IO) {
            SensitiveRegistryUiState(
                profiles = SensitiveAppRegistry.profiles(context),
                suggestion = SensitiveAppRegistry.suggestion(context),
                recentExternalApp = if (usageAccessGranted) {
                    UsageAccessState.recentExternalApp(context)
                } else {
                    null
                },
                storageHealthy = SensitiveAppRegistry.storageHealthy(context),
                loading = false,
            )
        }
    }
    val profiles = registryUi.profiles
    val suggestion = registryUi.suggestion
    val recentExternalApp = registryUi.recentExternalApp
    val registryHealthy = registryUi.storageHealthy
    val sensitiveSession = remember(refreshTick) {
        SensitiveAppMode.currentSession(context)
    }
    val sensitiveModeActive = sensitiveSession != null
    var showSensitivePicker by remember { mutableStateOf(false) }
    var sensitivePickerSearch by remember { mutableStateOf("") }
    var showResolvedHistory by rememberSaveable { mutableStateOf(false) }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "行为学习记账（M5 第一版）")
    Text(
        text = "待确认 ${status.behaviorPendingCount} · 人工确认 ${status.behaviorConfirmedCount} · " +
            "自动记账 ${status.behaviorAutoRecordedCount} · 自动模板 ${status.behaviorAutoTemplateCount}"
    )
    Text(
        text = "无障碍后台采集：${if (accessibility.permissionEnabled) "已启用（心跳由内核监控）" else "未启用"} · " +
            "确认通知：${if (notificationGranted) "可发送" else "未授权"}"
    )
    Text(text = "只保留 10 秒内存事件滑窗，不保存页面原文。调试截图关闭时不截图；连续 5 次确认且零否定的同类行为才允许自动记账。")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("调试证据截图")
            Text("候选触发时本地加密保存，7 天自动删除；不上传、不参与自动记账。")
        }
        Switch(
            checked = debugEvidenceEnabled,
            onCheckedChange = { enabled ->
                BehaviorDebugEvidenceStore.setEnabled(context, enabled)
                debugEvidenceEnabled = enabled
            },
        )
    }
    Text(
        text = when {
            sensitiveSession?.phase == SensitiveLaunchPhase.RECOVERING ->
                "${sensitiveSession.targetLabel} 安全模式：正在等待行为监视真正连接"
            sensitiveModeActive ->
                "${sensitiveSession?.targetLabel} 安全模式：行为监视已暂停；离开后需同会话返回或点通知确认"
            sensitiveControlGranted -> "敏感应用安全模式：已获得个人设备控制权限"
            else -> "敏感应用安全模式：待 ADB 授权，未授权时不会冒险启动"
        }
    )
    Text(
        "长期注册表加密保存包身份/签名；活动会话仅暂存在 App 私有存储，恢复后清除。不会保存页面原文。"
    )
    Text(
        "会话观察：${if (usageAccessGranted) "已授权，仅用于提示疑似离开" else "未授权，离开后依赖返回页或通知"}"
    )
    if (!usageAccessGranted) {
        Button(onClick = onOpenUsageAccessSettings, modifier = Modifier.fillMaxWidth()) {
            Text("允许敏感会话前后台判断")
        }
    }
    if (!registryHealthy) {
        Text("敏感应用加密注册表不可读：已停止自动识别，请勿覆盖，需先恢复数据。")
    }
    if (registryUi.loading) {
        Text("正在后台读取敏感应用注册表…")
    }
    suggestion?.let { pending ->
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("检测到可能需要安全模式：${pending.identity.label}")
                Text("${pending.identity.packageName} · 仅保存风险类别，未保存提示原文")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val confirmed = withContext(Dispatchers.IO) {
                                SensitiveAppRegistry.confirmSuggestion(context, pending)
                            }
                            if (confirmed) SensitiveModeNotifier.cancelSuggestion(context)
                            onRefresh()
                        }
                    }) { Text("确认学习") }
                    TextButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { SensitiveAppRegistry.dismissSuggestion(context) }
                            SensitiveModeNotifier.cancelSuggestion(context)
                            onRefresh()
                        }
                    }) { Text("忽略") }
                }
            }
        }
    }
    recentExternalApp?.takeIf { recent -> profiles.none { it.profile.identity.packageName == recent.packageName } }
        ?.let { recent ->
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            SensitiveAppRegistry.add(
                                context,
                                recent.identity,
                                SensitiveProfileOrigin.USER_SELECTED,
                            )
                        }
                        onRefresh()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("将最近使用的 ${recent.label} 加入敏感应用")
            }
        }
    Button(
        onClick = { showSensitivePicker = true },
        enabled = registryHealthy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("从已安装应用中选择敏感应用")
    }
    profiles.forEach { view ->
        SensitiveProfileCard(
            view = view,
            activeSessionPackage = sensitiveSession?.targetPackage,
            canLaunch = sensitiveControlGranted && notificationGranted && !sensitiveModeActive,
            onSafeLaunch = { onSafeLaunchSensitive(view.profile.identity.packageName) },
            onRemove = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        SensitiveAppRegistry.remove(context, view.profile.identity.packageName)
                    }
                    onRefresh()
                }
            },
        )
    }
    if (sensitiveModeActive) {
        Button(
            onClick = onRestoreSensitiveMode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (sensitiveSession?.phase == SensitiveLaunchPhase.RECOVERING) {
                    "重试恢复监视"
                } else {
                    "已结束敏感应用流程，立即恢复监视"
                }
            )
        }
    }
    if (!accessibility.permissionEnabled && !sensitiveModeActive) {
        Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text("开启行为学习无障碍服务")
        }
    }
    if (!notificationGranted) {
        Button(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
            Text("允许弹出付款确认通知")
        }
    }
    if (showSensitivePicker) {
        SensitiveAppPickerDialog(
            search = sensitivePickerSearch,
            onSearchChange = { sensitivePickerSearch = it },
            existingPackages = profiles.map { it.profile.identity.packageName }.toSet(),
            onSelect = { selected ->
                scope.launch {
                    val added = withContext(Dispatchers.IO) {
                        SensitiveAppRegistry.add(
                            context,
                            selected.identity,
                            SensitiveProfileOrigin.USER_SELECTED,
                        )
                    }
                    if (added) {
                        showSensitivePicker = false
                        sensitivePickerSearch = ""
                        onRefresh()
                    }
                }
            },
            onDismiss = {
                showSensitivePicker = false
                sensitivePickerSearch = ""
            },
        )
    }
    if (BuildConfig.DEBUG) {
        Button(
            onClick = { LedgerKernel.createDebugBehaviorCandidate() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("生成 0.01 元本地测试候选")
        }
        Text(text = "测试候选默认只进入待确认，不会自行记成成功；其模板与真实 App 隔离。")
    }
    val actionableCandidates = status.behaviorCandidates.filter {
        it.state == BehaviorCandidateState.PENDING || it.state == BehaviorCandidateState.AUTO_RECORDED
    }
    val resolvedCandidates = status.behaviorCandidates.filterNot { it in actionableCandidates }
    val totalActionableCount = status.behaviorPendingCount + status.behaviorAutoRecordedCount
    if (status.behaviorCandidates.isEmpty()) {
        Text(text = "尚无行为候选。开启服务后，明确成功终态会出现在这里。")
    } else {
        Text("需要你处理（共 $totalActionableCount 条，已加载 ${actionableCandidates.size} 条）")
        if (actionableCandidates.isEmpty()) {
            Text("当前没有待确认或可撤销记录。")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
            ) {
                items(actionableCandidates, key = { it.id }) { candidate ->
                    BehaviorCandidateCard(candidate)
                }
            }
        }
        if (totalActionableCount > actionableCandidates.size) {
            val remaining = totalActionableCount - actionableCandidates.size
            Button(
                onClick = { LedgerKernel.showMoreBehaviorCandidates() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("再显示 10 条（剩余 $remaining 条）")
            }
            TextButton(
                onClick = { LedgerKernel.showAllBehaviorCandidates() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("展开全部待处理记录")
            }
        }
        if (resolvedCandidates.isNotEmpty()) {
            TextButton(onClick = { showResolvedHistory = !showResolvedHistory }) {
                Text(
                    if (showResolvedHistory) {
                        "收起最近处理记录"
                    } else {
                        "展开最近处理记录（${resolvedCandidates.size} 条）"
                    }
                )
            }
            if (showResolvedHistory) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 600.dp),
                ) {
                    items(resolvedCandidates, key = { it.id }) { candidate ->
                        BehaviorCandidateCard(candidate)
                    }
                }
            }
        }
    }
}

@Composable
private fun SensitiveProfileCard(
    view: SensitiveProfileView,
    activeSessionPackage: String?,
    canLaunch: Boolean,
    onSafeLaunch: () -> Unit,
    onRemove: () -> Unit,
) {
    val identity = view.profile.identity
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SensitiveAppIcon(identity.packageName)
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.fillMaxWidth(0.78f)) {
                    Text(identity.label)
                    Text(identity.packageName)
                    Text(
                        when {
                            !view.installed -> "应用已卸载"
                            !view.identityStillValid -> "签名身份已变化，请移除后重新确认"
                            else -> "身份已确认 · ${view.profile.origin.name}"
                        }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSafeLaunch,
                    enabled = canLaunch && view.installed && view.identityStillValid,
                ) { Text("安全打开") }
                TextButton(
                    onClick = onRemove,
                    enabled = activeSessionPackage != identity.packageName,
                ) { Text("移除") }
            }
        }
    }
}

@Composable
private fun SensitiveAppPickerDialog(
    search: String,
    onSearchChange: (String) -> Unit,
    existingPackages: Set<String>,
    onSelect: (LaunchableApp) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { PackageIdentityResolver.launchableApps(context) }
    }
    val filtered = remember(apps, search, existingPackages) {
        apps.orEmpty().filter { it.packageName !in existingPackages }
            .filter {
                search.isBlank() || it.label.contains(search, ignoreCase = true) ||
                    it.packageName.contains(search, ignoreCase = true)
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择敏感应用") },
        text = {
            Column {
                if (apps == null) Text("正在后台读取已安装应用…")
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    label = { Text("搜索名称或包名") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                    items(filtered, key = { it.packageName }) { app ->
                        TextButton(
                            onClick = { onSelect(app) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                SensitiveAppIcon(app.packageName)
                                Spacer(modifier = Modifier.size(8.dp))
                                Column(horizontalAlignment = Alignment.Start) {
                                    Text(app.label)
                                    Text(app.packageName)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun SensitiveAppIcon(packageName: String) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(packageName) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(48, 48).asImageBitmap()
            }.getOrNull()
        }
    }
    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
        Image(bitmap = loadedBitmap, contentDescription = null, modifier = Modifier.size(36.dp))
    } else {
        Spacer(modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun BehaviorCandidateCard(candidate: BehaviorCandidateEntity) {
    val context = LocalContext.current
    var amountText by remember(candidate.id, candidate.amountCents) {
        mutableStateOf(candidate.amountCents?.let { "%d.%02d".format(it / 100, kotlin.math.abs(it % 100)) }.orEmpty())
    }
    var purpose by remember(candidate.id, candidate.purpose) { mutableStateOf(candidate.purpose.orEmpty()) }
    var rejectReason by remember(candidate.id) { mutableStateOf("广告或无关页面文字") }
    val evidenceBitmap by produceState<android.graphics.Bitmap?>(null, candidate.publicId) {
        value = withContext(Dispatchers.IO) {
            BehaviorDebugEvidenceStore.load(context, candidate.publicId)
        }
    }
    val parsedAmount = remember(amountText) { parseAmountCents(amountText) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "${if (candidate.kind == BehaviorKind.REFUND) "退款" else "付款"} · ${behaviorStateLabels[candidate.state]}")
            Text(text = "来源：${candidate.packageName} · 置信度 ${candidate.confidence}%")
            Text(text = "发生时间：${formatEventTime(candidate.occurredAtMs)}")
            evidenceBitmap?.let { bitmap ->
                Text("加密调试截图（仅用于人工判断）")
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "候选触发时的调试截图",
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (candidate.ambiguousRepeatCount > 0) {
                Text(text = "警告：又收到 ${candidate.ambiguousRepeatCount} 次无法区分的相同成功终态；已保留审计缺口，请核对是否存在另一笔同额交易。")
            }
            if (candidate.state == BehaviorCandidateState.PENDING) {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.take(12) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("金额（元）") },
                    isError = amountText.isNotBlank() && parsedAmount == null,
                )
                OutlinedTextField(
                    value = purpose,
                    onValueChange = { purpose = it.take(80) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("用途（可选）") },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            LedgerKernel.applyBehaviorDecision(
                                candidate.id,
                                BehaviorDecision.CONFIRM_PAYMENT,
                                parsedAmount,
                                purpose,
                            )
                        },
                        enabled = parsedAmount != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("确认付款") }
                    Button(
                        onClick = {
                            LedgerKernel.applyBehaviorDecision(
                                candidate.id,
                                BehaviorDecision.CONFIRM_REFUND,
                                parsedAmount,
                                purpose,
                            )
                        },
                        enabled = parsedAmount != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("确认退款") }
                }
                TextButton(
                    onClick = {
                        LedgerKernel.applyBehaviorDecision(
                            candidate.id,
                            BehaviorDecision.REJECT,
                            purpose = "误报：$rejectReason",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("不是付款/退款，排除并降低模板置信度") }
                Text("误报原因")
                Column {
                    listOf("广告或无关页面文字", "历史订单", "金额含义错误").forEach { reason ->
                        FilterChip(
                            selected = rejectReason == reason,
                            onClick = { rejectReason = reason },
                            label = { Text(reason) },
                        )
                    }
                }
            } else {
                candidate.amountCents?.let { Text(text = "金额：¥%d.%02d".format(it / 100, kotlin.math.abs(it % 100))) }
                candidate.purpose?.let { Text(text = "用途：$it") }
                if (candidate.state == BehaviorCandidateState.AUTO_RECORDED) {
                    Button(
                        onClick = { LedgerKernel.applyBehaviorDecision(candidate.id, BehaviorDecision.UNDO_AUTO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("撤销这笔自动记账并停用该模板") }
                }
            }
        }
    }
}

private fun parseAmountCents(input: String): Long? = runCatching {
    BigDecimal(input.trim())
        .setScale(2, RoundingMode.UNNECESSARY)
        .movePointRight(2)
        .longValueExact()
        .takeIf { it > 0 }
}.getOrNull()

@Composable
private fun KernelStatusSection() {
    val status by LedgerKernel.status.collectAsState()
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.kernel_section_title))
    Text(text = stringResource(R.string.kernel_counts_format, status.observationCount, status.candidateCount, status.pendingParseCount))
    Text(
        text = stringResource(
            R.string.kernel_migration_format,
            stringResource(if (status.t03Migrated) R.string.kernel_migration_done else R.string.kernel_migration_pending),
        )
    )
    Text(text = "${stringResource(R.string.kernel_last_sweep_label)}：${formatEventTime(status.lastSweepAtMs)}")
    Text(text = stringResource(R.string.kernel_open_gaps_format, status.openGapCount))
    status.openGaps.take(5).forEach { gap ->
        val endLabel = gap.endedAtMs?.let { formatEventTime(it) } ?: stringResource(R.string.kernel_gap_ongoing)
        Text(
            text = stringResource(
                R.string.kernel_gap_item_format,
                gap.detector,
                formatEventTime(gap.startedAtMs),
                endLabel,
            )
        )
    }
}

@Composable
private fun M2ChannelSection(
    refreshTick: Int,
    onRequestSmsPermissions: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onRequestBatteryUnrestricted: () -> Unit,
) {
    val context = LocalContext.current
    val smsGranted = remember(refreshTick) { SmsBackfill.hasPermission(context) }
    val batteryWhitelisted = remember(refreshTick) {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)
    }
    Spacer(modifier = Modifier.height(16.dp))
    Text(text = stringResource(R.string.m2_section_title))
    Text(
        text = stringResource(
            R.string.m2_sms_permission_format,
            stringResource(if (smsGranted) R.string.m2_granted else R.string.m2_not_granted),
        )
    )
    if (!smsGranted) {
        Button(onClick = onRequestSmsPermissions, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.m2_request_sms))
        }
    }
    Text(
        text = stringResource(
            R.string.m2_battery_format,
            stringResource(if (batteryWhitelisted) R.string.m2_granted else R.string.m2_not_granted),
        )
    )
    if (!batteryWhitelisted) {
        Button(onClick = onRequestBatteryUnrestricted, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.m2_request_battery))
        }
    }
    Button(onClick = onOpenAutostartSettings, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.m2_autostart))
    }
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
            onClick = { onPreset("支付宝", "com.eg.android.AlipayGphone") },
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
