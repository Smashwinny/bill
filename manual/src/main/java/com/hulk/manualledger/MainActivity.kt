package com.hulk.manualledger

import android.os.Bundle
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.io.File

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var repository: ManualLedgerRepository
    private var rows by mutableStateOf<List<ManualTransactionEntity>>(emptyList())
    private var categoryNodes by mutableStateOf<List<LedgerCategoryEntity>>(emptyList())
    private var pendingCount by mutableStateOf(0L)
    private var message by mutableStateOf("本地数据库已就绪")
    private var pendingImport by mutableStateOf<SuishouImportResult?>(null)
    private var monthlyBudgetCents by mutableStateOf(0L)
    private var exportPayload: String? = null
    private var syncStatus by mutableStateOf(ManualSyncStatus(false, "https://bill.geniusqi.com/v1/sync", 0L, null))
    private val ledgerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { executor.execute { refresh("云端流水已更新") } }
    }

    private val exportDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = exportPayload
        exportPayload = null
        if (uri != null && payload != null) executor.execute {
            runCatching { contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) } }
                .onSuccess { runOnUiThread { message = "迁移文件已导出" } }
                .onFailure { runOnUiThread { message = "导出失败：${it.message}" } }
        }
    }

    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) previewImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ManualLedgerRepository.open(this)
        monthlyBudgetCents = getSharedPreferences("manual_settings", MODE_PRIVATE)
            .getLong("monthly_budget_cents", 0L)
        setContent {
            MaterialTheme(colorScheme = LedgerColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ManualLedgerScreen(
                        rows = rows,
                        categoryNodes = categoryNodes,
                        pendingCount = pendingCount,
                        message = message,
                        onSave = ::save,
                        onImport = { importDocument.launch(arrayOf(
                            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                            "application/vnd.ms-excel",
                            "text/csv",
                            "text/plain",
                        )) },
                        onExport = ::export,
                        onDirectMigration = ::migrateToAutomaticLedger,
                        onDelete = ::delete,
                        onEdit = ::edit,
                        pendingImport = pendingImport,
                        onConfirmImport = ::confirmImport,
                        onCancelImport = { pendingImport = null },
                        monthlyBudgetCents = monthlyBudgetCents,
                        onBudgetChange = ::saveMonthlyBudget,
                        syncStatus = syncStatus,
                        onConfigureSync = ::configureSync,
                        onDisconnectSync = ::disconnectSync,
                        onSyncNow = ::syncNowWithFeedback,
                        onMoveCategory = ::moveCategory,
                        onMergeCategories = ::mergeCategories,
                        onDeleteCategory = ::deleteCategory,
                        onChangeTransactionCategory = ::changeTransactionCategory,
                    )
                }
            }
        }
        executor.execute { refresh() }
        contentResolver.registerContentObserver(ManualLedgerProvider.TRANSACTIONS_URI, true, ledgerObserver)
        handleImportIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleImportIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::repository.isInitialized) executor.execute { refresh() }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(ledgerObserver)
        executor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun handleImportIntent(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: incoming.clipData?.getItemAt(0)?.uri
        if (uri == null) {
            message = "没有收到可读取的导出文件"
            return
        }
        incoming.action = null
        previewImport(uri)
    }

    private fun previewImport(uri: Uri) {
        message = "正在读取随手记导出文件…"
        executor.execute {
            runCatching {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("无法读取文件")
                require(bytes.size <= 50 * 1024 * 1024) { "文件超过 50 MiB" }
                SuishouImportParser.parse(bytes)
            }.onSuccess { parsed ->
                runOnUiThread {
                    pendingImport = parsed
                    message = "已读取 ${parsed.rows.size} 条，确认后才会写入本地"
                }
            }.onFailure { failure ->
                runOnUiThread { message = "导入失败：${failure.message}" }
            }
        }
    }

    private fun save(input: NewManualTransaction) {
        message = "正在保存到本地…"
        executor.execute {
            runCatching { repository.add(input) }
                .onSuccess { ManualSyncScheduler.syncNow(this); refresh("已保存到本地；同步将在网络可用时后台进行") }
                .onFailure { runOnUiThread { message = it.message ?: "保存失败" } }
        }
    }

    private fun export() {
        executor.execute {
            exportPayload = ManualLedgerMigrationCodec.exportJson(repository.list())
            runOnUiThread { exportDocument.launch("manual-ledger-${System.currentTimeMillis()}.json") }
        }
    }

    private fun migrateToAutomaticLedger() {
        message = "正在准备迁移预览…"
        executor.execute {
            runCatching {
                val directory = File(cacheDir, "migration").apply { mkdirs() }
                val file = File(directory, "manual-ledger-v1.json")
                file.writeText(ManualLedgerMigrationCodec.exportJson(repository.list()), Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                Intent("com.hulk.pillsapp.IMPORT_MANUAL_LEDGER").apply {
                    setClassName("com.hulk.pillsapp", "com.hulk.pillsapp.MainActivity")
                    data = uri
                    clipData = ClipData.newRawUri("manual-ledger-v1", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.onSuccess { intent ->
                runOnUiThread {
                    runCatching { startActivity(intent) }
                        .onSuccess { message = "已请求打开自动账本；若系统拦截，请允许应用间跳转" }
                        .onFailure { message = "未找到自动账本，请先安装后再试" }
                }
            }.onFailure { failure ->
                runOnUiThread { message = "迁移准备失败：${failure.message}" }
            }
        }
    }

    private fun delete(id: String) {
        executor.execute {
            runCatching { repository.delete(id) }
                .onSuccess { ManualSyncScheduler.syncNow(this); refresh("已删除；变更会在联网后同步") }
                .onFailure { runOnUiThread { message = "删除失败：${it.message}" } }
        }
    }

    private fun edit(id: String, input: NewManualTransaction) {
        executor.execute {
            runCatching { repository.update(id, input) }
                .onSuccess { changed ->
                    if (changed) ManualSyncScheduler.syncNow(this)
                    refresh(if (changed) "修改已保存到本地" else "流水不存在，未修改")
                }
                .onFailure { runOnUiThread { message = "修改失败：${it.message}" } }
        }
    }

    private fun confirmImport() {
        val import = pendingImport ?: return
        pendingImport = null
        message = "正在导入到本地…"
        executor.execute {
            runCatching { repository.import(import.rows) }
                .onSuccess { stats ->
                    ManualSyncScheduler.syncNow(this)
                    refresh("新增 ${stats.inserted} 条；补全层级 ${stats.enriched} 条；未变化 ${stats.unchanged} 条；格式异常 ${import.rejectedRows} 条")
                }
                .onFailure { runOnUiThread { message = "导入失败：${it.message}" } }
        }
    }

    private fun saveMonthlyBudget(raw: String) {
        val cents = if (raw.isBlank()) 0L else ManualLedgerRepository.parseCents(raw) ?: return
        monthlyBudgetCents = cents
        getSharedPreferences("manual_settings", MODE_PRIVATE).edit()
            .putLong("monthly_budget_cents", cents)
            .apply()
        message = if (cents == 0L) "月预算已清除" else "月预算已保存到本机"
    }

    private fun configureSync(endpoint: String, token: String) {
        runCatching { ManualSyncSettings.configure(this, endpoint, token) }
            .onSuccess { syncStatus = ManualSyncSettings.status(this); message = "云端同步已开启，正在上传本地流水" }
            .onFailure { message = it.message ?: "同步配置失败" }
    }

    private fun disconnectSync() {
        ManualSyncSettings.disconnect(this)
        syncStatus = ManualSyncSettings.status(this)
        message = "已断开云端；本地流水不受影响"
    }

    private fun syncNowWithFeedback() {
        message = if (pendingCount > 0) "正在同步，待处理 $pendingCount 条…" else "正在检查云端更新…"
        ManualSyncScheduler.syncNow(this)
        fun scheduleFeedback(delayMs: Long) = Handler(Looper.getMainLooper()).postDelayed({
            if (::repository.isInitialized) executor.execute {
                val pending = repository.pendingSyncCount()
                val status = ManualSyncSettings.status(this)
                val feedback = when {
                    status.lastError != null -> "同步失败：${status.lastError}；待同步 $pending 条"
                    pending == 0L -> "同步完成，云端与本机已对齐"
                    else -> "正在同步，剩余 $pending 条…"
                }
                refresh(feedback)
            }
        }, delayMs)
        scheduleFeedback(1800)
        scheduleFeedback(6000)
    }

    private fun moveCategory(sourceId: String, targetId: String?) = mutateCategories("分类已移动") {
        repository.moveCategory(sourceId, targetId)
    }

    private fun mergeCategories(sourceId: String, targetId: String) = mutateCategories("分类已合并，历史账单已迁移") {
        repository.mergeCategories(sourceId, targetId)
    }

    private fun deleteCategory(categoryId: String) = mutateCategories("分类已删除，受影响账单已归入无分类") {
        repository.deleteCategory(categoryId)
    }

    private fun changeTransactionCategory(transactionId: String, categoryId: String) = mutateCategories("账单分类已修改") {
        repository.changeTransactionCategory(transactionId, categoryId)
    }

    private fun mutateCategories(success: String, operation: () -> Any) {
        message = "正在更新分类…"
        executor.execute {
            runCatching(operation)
                .onSuccess { ManualSyncScheduler.syncNow(this); refresh(success) }
                .onFailure { runOnUiThread { message = it.message ?: "分类操作失败" } }
        }
    }

    private fun refresh(after: String? = null) {
        val latest = repository.list()
        val latestCategories = repository.categories()
        val pending = repository.pendingSyncCount()
        val latestSync = ManualSyncSettings.status(this)
        runOnUiThread {
            rows = latest
            categoryNodes = latestCategories
            pendingCount = pending
            syncStatus = latestSync
            if (after != null) message = after
        }
    }
}

private val LedgerColorScheme = lightColorScheme(
    primary = Color(0xFF0B705F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4E8),
    onPrimaryContainer = Color(0xFF06463C),
    secondary = Color(0xFF4D635D),
    secondaryContainer = Color(0xFFD7F4E8),
    onSecondaryContainer = Color(0xFF06463C),
    tertiary = Color(0xFFE59B24),
    background = Color(0xFFF4F8F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7F0EC),
    outline = Color(0xFFBBC9C3),
    error = Color(0xFFB3261E),
)

@Composable
private fun ManualLedgerScreen(
    rows: List<ManualTransactionEntity>,
    categoryNodes: List<LedgerCategoryEntity>,
    pendingCount: Long,
    message: String,
    onSave: (NewManualTransaction) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDirectMigration: () -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, NewManualTransaction) -> Unit,
    pendingImport: SuishouImportResult?,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    monthlyBudgetCents: Long,
    onBudgetChange: (String) -> Unit,
    syncStatus: ManualSyncStatus,
    onConfigureSync: (String, String) -> Unit,
    onDisconnectSync: () -> Unit,
    onSyncNow: () -> Unit,
    onMoveCategory: (String, String?) -> Unit,
    onMergeCategories: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onChangeTransactionCategory: (String, String) -> Unit,
) {
    val context = LocalContext.current
    var categoryMappings by androidx.compose.runtime.remember { mutableStateOf(loadCategoryMappings(context)) }
    fun resolveCategory(row: ManualTransactionEntity): String {
        val hierarchy = CategoryCatalog.hierarchy(row.category)
        return categoryMappings[categoryMappingKey(row.type, row.category)]
            ?: hierarchy.second?.let { categoryMappings[categoryMappingKey(row.type, it)] }
            ?: CategoryCatalog.normalize(row.type, row.category)
    }
    fun saveCategoryMapping(type: ManualTransactionType, source: String, target: String) {
        categoryMappings = categoryMappings + (categoryMappingKey(type, source) to target)
        persistCategoryMappings(context, categoryMappings)
    }
    var page by androidx.compose.runtime.remember { mutableStateOf(LedgerPage.OVERVIEW) }
    var type by androidx.compose.runtime.remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var category by androidx.compose.runtime.remember { mutableStateOf(CategoryCatalog.defaultPath(ManualTransactionType.EXPENSE)) }
    var account by androidx.compose.runtime.remember { mutableStateOf("现金") }
    var note by androidx.compose.runtime.remember { mutableStateOf("") }
    var occurredAtMs by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    var month by androidx.compose.runtime.remember { mutableStateOf(YearMonth.now()) }
    val monthRows = rows.filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAtMs).atZone(ZoneId.systemDefault())) == month }
    val expense = monthRows.filter { it.type == ManualTransactionType.EXPENSE }.sumOf { it.amountCents }
    val income = monthRows.filter { it.type == ManualTransactionType.INCOME }.sumOf { it.amountCents }
    val previousMonth = month.minusMonths(1)
    val previousExpense = rows.filter {
        it.type == ManualTransactionType.EXPENSE &&
            YearMonth.from(Instant.ofEpochMilli(it.occurredAtMs).atZone(ZoneId.systemDefault())) == previousMonth
    }.sumOf { it.amountCents }

    pendingImport?.let { preview ->
        val sample = preview.rows.take(3).joinToString("\n") {
            "${typeLabel(it.type)} ${it.amountText} · ${it.category} · ${it.account}"
        }
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("确认导入随手记数据") },
            text = {
                val reasons = preview.rejectedReasons.joinToString("\n")
                Text(
                    "编码 ${preview.sourceEncoding} · 可识别 ${preview.rows.size} 条 · 拒绝 ${preview.rejectedRows} 条。" +
                        (if (reasons.isBlank()) "" else "\n\n需要检查：\n$reasons") +
                        "\n\n预览：\n$sample\n\n重复流水会按稳定编号自动跳过；日期不确定的流水不会写入。"
                )
            },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("取消") } },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                LedgerPage.entries.forEach { option ->
                    NavigationBarItem(
                        selected = page == option,
                        onClick = { page = option },
                        icon = { Icon(option.icon, contentDescription = option.title) },
                        label = { Text(option.title) },
                    )
                }
            }
        },
    ) { scaffoldPadding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(scaffoldPadding)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            LedgerHeader(pendingCount = pendingCount, cloudConfigured = syncStatus.configured, message = message)
            when (page) {
            LedgerPage.OVERVIEW -> OverviewPage(
                month = month,
                rows = monthRows,
                income = income,
                expense = expense,
                monthlyBudgetCents = monthlyBudgetCents,
                onAdd = { page = LedgerPage.RECORD },
                onAllFlows = { page = LedgerPage.FLOW },
                onAnalysis = { page = LedgerPage.ANALYSIS },
                resolveCategory = ::resolveCategory,
            )
            LedgerPage.RECORD -> RecordPage(
                type = type,
                amount = amount,
                category = category,
                account = account,
                note = note,
                occurredAtMs = occurredAtMs,
                observedHierarchy = CategoryCatalog.observedHierarchy(rows.filter { it.type == type }.map { it.category }),
                onType = { type = it; category = CategoryCatalog.defaultPath(it) },
                onAmount = { amount = it.take(14) },
                onCategory = { category = it.take(80) },
                onAccount = { account = it.take(40) },
                onNote = { note = it.take(200) },
                onOccurredAt = { occurredAtMs = it },
                onSave = {
                    onSave(NewManualTransaction(
                        type = type,
                        amountText = amount,
                        category = category,
                        account = account,
                        occurredAtMs = occurredAtMs,
                        note = note,
                    ))
                    amount = ""
                    note = ""
                    occurredAtMs = System.currentTimeMillis()
                    page = LedgerPage.FLOW
                },
            )
            LedgerPage.FLOW -> FlowPage(
                rows, categoryNodes, onImport, onExport, onDirectMigration, onDelete, onEdit,
                onChangeTransactionCategory,
            )
            LedgerPage.ANALYSIS -> AnalysisPage(
                month = month,
                rows = monthRows,
                allRows = rows,
                income = income,
                expense = expense,
                previousExpense = previousExpense,
                monthlyBudgetCents = monthlyBudgetCents,
                onBudgetChange = onBudgetChange,
                onPreviousMonth = { month = month.minusMonths(1) },
                onNextMonth = { if (month < YearMonth.now()) month = month.plusMonths(1) },
                resolveCategory = ::resolveCategory,
                onMapCategory = ::saveCategoryMapping,
            )
            LedgerPage.SETTINGS -> SyncSettingsPage(
                syncStatus, categoryNodes, rows, onConfigureSync, onDisconnectSync, onSyncNow,
                onMoveCategory, onMergeCategories, onDeleteCategory,
            )
            }
        }
    }
}

private enum class LedgerPage(val title: String, val icon: ImageVector) {
    OVERVIEW("首页", Icons.Default.Home),
    FLOW("流水", Icons.AutoMirrored.Filled.ReceiptLong),
    RECORD("记一笔", Icons.Default.AddCircle),
    ANALYSIS("分析", Icons.Default.Analytics),
    SETTINGS("设置", Icons.Default.Settings),
}

@Composable
private fun LedgerHeader(pendingCount: Long, cloudConfigured: Boolean, message: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column {
            Text("本地账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("每一笔，都稳稳留在自己手里", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Surface(
            color = if (!cloudConfigured || pendingCount == 0L) MaterialTheme.colorScheme.primaryContainer else Color(0xFFFFEBC8),
            shape = RoundedCornerShape(18.dp),
        ) {
            Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Icon(
                    if (!cloudConfigured) Icons.Default.AccountBalanceWallet else if (pendingCount == 0L) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (!cloudConfigured || pendingCount == 0L) MaterialTheme.colorScheme.primary else Color(0xFF9A6200),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    if (!cloudConfigured) "仅本地" else if (pendingCount == 0L) "已同步" else "待同步 $pendingCount",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp, bottom = 8.dp))
}

@Composable
private fun OverviewPage(
    month: YearMonth,
    rows: List<ManualTransactionEntity>,
    income: Long,
    expense: Long,
    monthlyBudgetCents: Long,
    onAdd: () -> Unit,
    onAllFlows: () -> Unit,
    onAnalysis: () -> Unit,
    resolveCategory: (ManualTransactionEntity) -> String,
) {
    val grouped = rows.asSequence().filter { it.type == ManualTransactionType.EXPENSE }
        .groupBy(resolveCategory).mapValues { (_, value) -> value.sumOf { it.amountCents } }
        .entries.sortedByDescending { it.value }
    val balance = income - expense
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0B705F)),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(22.dp)) {
                    Text("${month.monthValue} 月结余", color = Color(0xFFC8EFE4), style = MaterialTheme.typography.labelLarge)
                    Text(money(balance), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricWithIcon(Icons.Default.ArrowDownward, "收入", money(income), Color(0xFFBFF5D4))
                        MetricWithIcon(Icons.Default.ArrowUpward, "支出", money(expense), Color(0xFFFFD59C))
                    }
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("记一笔", fontWeight = FontWeight.Bold)
            }
        }
        if (monthlyBudgetCents > 0) item {
            val used = (expense.toFloat() / monthlyBudgetCents).coerceIn(0f, 1f)
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row {
                            Icon(Icons.Default.Savings, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(7.dp))
                            Text("本月预算", fontWeight = FontWeight.SemiBold)
                        }
                        Text("${(used * 100).toInt()}%")
                    }
                    LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Text(
                        if (expense <= monthlyBudgetCents) "还可安心支出 ${money(monthlyBudgetCents - expense)}"
                        else "已超预算 ${money(expense - monthlyBudgetCents)}",
                        modifier = Modifier.padding(top = 7.dp),
                        color = if (expense <= monthlyBudgetCents) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("支出去向", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAnalysis) { Text("看分析") }
            }
            if (grouped.isEmpty()) EmptyHint("记下第一笔后，这里会自动长出消费结构")
        }
        items(grouped.take(3), key = { it.key }) { item ->
            val ratio = if (expense == 0L) 0f else item.value.toFloat() / expense
            CategoryProgress(item.key, item.value, ratio)
        }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近流水", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAllFlows) { Text("全部") }
            }
            if (rows.isEmpty()) EmptyHint("还没有流水，点“记一笔”开始你的新账本")
        }
        items(rows.take(4), key = { it.id }) { row -> TransactionRow(row) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun MetricWithIcon(icon: ImageVector, label: String, value: String, tint: Color) {
    Row {
        Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.13f)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(6.dp).size(18.dp))
        }
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(label, color = Color(0xFFC8EFE4), style = MaterialTheme.typography.bodySmall)
            Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun CategoryProgress(name: String, amount: Long, ratio: Float) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = categoryColor(name).copy(alpha = 0.16f)) {
                Text(name.take(1), modifier = Modifier.padding(10.dp), color = categoryColor(name), fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name, fontWeight = FontWeight.Medium)
                    Text(money(amount), fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth().padding(top = 7.dp), color = categoryColor(name))
            }
            Text("${(ratio * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(18.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(18.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun RecordPage(
    type: ManualTransactionType,
    amount: String,
    category: String,
    account: String,
    note: String,
    occurredAtMs: Long,
    observedHierarchy: Map<String, List<String>>,
    onType: (ManualTransactionType) -> Unit,
    onAmount: (String) -> Unit,
    onCategory: (String) -> Unit,
    onAccount: (String) -> Unit,
    onNote: (String) -> Unit,
    onOccurredAt: (Long) -> Unit,
    onSave: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text("记一笔", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ManualTransactionType.entries.forEach { option ->
                    FilterChip(
                        modifier = Modifier.weight(1f),
                        selected = type == option,
                        onClick = { onType(option) },
                        label = { Text(typeLabel(option)) },
                    )
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(22.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("金额", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(
                        amount,
                        onAmount,
                        prefix = { Text("¥ ", style = MaterialTheme.typography.headlineSmall) },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        placeholder = { Text("0.00") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Text("消费分类", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                    HierarchicalCategoryPicker(type, category, observedHierarchy, onCategory)
                    OutlinedTextField(
                        account,
                        onAccount,
                        label = { Text("账户") },
                        leadingIcon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    TransactionDateButton(occurredAtMs, onOccurredAt)
                    OutlinedTextField(note, onNote, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
                }
            }
            Button(
                onClick = onSave,
                enabled = ManualLedgerRepository.parseCents(amount) != null && category.isNotBlank() && account.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("保存到本机", fontWeight = FontWeight.Bold) }
            Text("离线也能保存，网络恢复后自动同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FlowPage(
    rows: List<ManualTransactionEntity>,
    categoryNodes: List<LedgerCategoryEntity>,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDirectMigration: () -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, NewManualTransaction) -> Unit,
    onChangeTransactionCategory: (String, String) -> Unit,
) {
    var query by androidx.compose.runtime.remember { mutableStateOf("") }
    var editing by androidx.compose.runtime.remember { mutableStateOf<ManualTransactionEntity?>(null) }
    var changingCategory by androidx.compose.runtime.remember { mutableStateOf<ManualTransactionEntity?>(null) }
    val visibleRows = rows.filter { row ->
        query.isBlank() || listOf(row.category, row.account, row.note.orEmpty(), money(row.amountCents))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    editing?.let { row ->
        EditTransactionDialog(
            row = row,
            allRows = rows,
            onDismiss = { editing = null },
            onSave = { input -> onEdit(row.id, input); editing = null },
        )
    }
    changingCategory?.let { row ->
        LeafCategoryDialog(
            type = row.type,
            categories = categoryNodes,
            selectedId = row.categoryId,
            onDismiss = { changingCategory = null },
            onSelect = { categoryId -> onChangeTransactionCategory(row.id, categoryId); changingCategory = null },
        )
    }
    Text("全部流水", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入随手记") }
        TextButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("备份") }
        TextButton(onClick = onDirectMigration, modifier = Modifier.weight(1f)) { Text("自动账本") }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(50) },
        placeholder = { Text("搜索分类、账户、备注或金额") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
    )
    if (rows.isEmpty()) {
        Text("还没有流水，去“记一笔”添加第一条吧。", modifier = Modifier.padding(top = 24.dp))
    } else if (visibleRows.isEmpty()) {
        Text("没有匹配“$query”的流水", modifier = Modifier.padding(top = 24.dp))
    } else LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(visibleRows, key = { it.id }) { row ->
            TransactionRow(
                row,
                onEdit = { editing = row },
                onChangeCategory = { changingCategory = row },
                onDelete = { onDelete(row.id) },
            )
        }
    }
}

@Composable
private fun TransactionRow(
    row: ManualTransactionEntity,
    onEdit: (() -> Unit)? = null,
    onChangeCategory: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(if (onChangeCategory != null) Modifier.clickable(onClick = onChangeCategory) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = categoryColor(row.category).copy(alpha = 0.16f)) {
                Text(row.category.take(1), modifier = Modifier.padding(10.dp), color = categoryColor(row.category), fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.category, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${row.account} · ${formatTime(row.occurredAtMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (onEdit != null || onChangeCategory != null || onDelete != null) Row {
                    onEdit?.let { TextButton(onClick = it) { Text("编辑") } }
                    onChangeCategory?.let { TextButton(onClick = it) { Text("改分类") } }
                    onDelete?.let { TextButton(onClick = it) { Text("删除", color = MaterialTheme.colorScheme.error) } }
                }
            }
            Text(
                (if (row.type == ManualTransactionType.EXPENSE) "−" else "+") + money(row.amountCents),
                fontWeight = FontWeight.Bold,
                color = if (row.type == ManualTransactionType.EXPENSE) Color(0xFF2F3B37) else MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EditTransactionDialog(
    row: ManualTransactionEntity,
    allRows: List<ManualTransactionEntity>,
    onDismiss: () -> Unit,
    onSave: (NewManualTransaction) -> Unit,
) {
    var type by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.type) }
    var amount by androidx.compose.runtime.remember(row.id) { mutableStateOf("%d.%02d".format(row.amountCents / 100, row.amountCents % 100)) }
    var category by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.category) }
    var account by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.account) }
    var note by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.note.orEmpty()) }
    var occurredAtMs by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.occurredAtMs) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑流水") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ManualTransactionType.entries.forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option; category = CategoryCatalog.defaultPath(option) },
                            label = { Text(typeLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("金额") }, singleLine = true)
                HierarchicalCategoryPicker(
                    type,
                    category,
                    CategoryCatalog.observedHierarchy(allRows.filter { it.type == type }.map { it.category }),
                ) { category = it }
                OutlinedTextField(account, { account = it.take(40) }, label = { Text("账户") }, singleLine = true)
                TransactionDateButton(occurredAtMs) { occurredAtMs = it }
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = ManualLedgerRepository.parseCents(amount) != null && category.isNotBlank() && account.isNotBlank(),
                onClick = {
                    onSave(NewManualTransaction(
                        type = type,
                        amountText = amount,
                        category = category,
                        account = account,
                        occurredAtMs = occurredAtMs,
                        note = note,
                    ))
                },
            ) { Text("保存修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HierarchicalCategoryPicker(
    type: ManualTransactionType,
    selected: String,
    observedHierarchy: Map<String, List<String>> = emptyMap(),
    onSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val hierarchy = linkedMapOf<String, List<String>>().apply {
        CategoryCatalog.hierarchyOptions(type).forEach { (primary, children) ->
            put(primary, (children + observedHierarchy[primary].orEmpty()).distinct())
        }
        observedHierarchy.forEach { (primary, children) ->
            if (primary !in this) put(primary, children)
        }
    }
    val parsed = CategoryCatalog.hierarchy(selected)
    val selectedPrimary = when {
        parsed.first in hierarchy -> parsed.first
        type == ManualTransactionType.EXPENSE -> CategoryCatalog.normalize(type, selected).takeIf { it in hierarchy } ?: hierarchy.keys.first()
        else -> parsed.first.takeIf { it in hierarchy } ?: hierarchy.keys.first()
    }
    val customKey = "custom_subcategories_${type.name.lowercase()}_$selectedPrimary"
    var customChildren by androidx.compose.runtime.remember(type, selectedPrimary) {
        mutableStateOf(context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
            .getStringSet(customKey, emptySet()).orEmpty().toSet())
    }
    val children = (hierarchy.getValue(selectedPrimary) + customChildren).distinct()
    val selectedSecondary = parsed.second ?: parsed.first.takeIf { it in children } ?: children.first()
    var adding by androidx.compose.runtime.remember(type, selectedPrimary) { mutableStateOf(false) }
    var draft by androidx.compose.runtime.remember(type, selectedPrimary) { mutableStateOf("") }

    Text("一级分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    hierarchy.keys.chunked(4).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { primary ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selectedPrimary == primary,
                    onClick = { onSelected(CategoryCatalog.sourcePath(primary, hierarchy.getValue(primary).first())) },
                    label = { Text(primary, maxLines = 1) },
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    Text("二级分类 · $selectedPrimary", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    children.chunked(4).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { secondary ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selectedSecondary == secondary,
                    onClick = { onSelected(CategoryCatalog.sourcePath(selectedPrimary, secondary)) },
                    label = { Text(secondary, maxLines = 1) },
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    TextButton(onClick = { draft = ""; adding = true }) { Text("＋ 在“$selectedPrimary”下添加二级分类") }
    if (adding) AlertDialog(
        onDismissRequest = { adding = false },
        title = { Text("添加到“$selectedPrimary”") },
        text = { OutlinedTextField(draft, { draft = it.take(40) }, label = { Text("二级分类名称") }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = draft.isNotBlank(), onClick = {
                val child = draft.trim()
                customChildren = customChildren + child
                context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
                    .edit().putStringSet(customKey, customChildren).apply()
                onSelected(CategoryCatalog.sourcePath(selectedPrimary, child))
                adding = false
            }) { Text("添加并使用") }
        },
        dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
    )
}

@Composable
private fun CategoryPicker(
    type: ManualTransactionType,
    selected: String,
    builtIn: List<String>,
    onSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    val key = "custom_categories_${type.name.lowercase()}"
    var custom by androidx.compose.runtime.remember(type) {
        mutableStateOf(context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
            .getStringSet(key, emptySet()).orEmpty().toSet())
    }
    var adding by androidx.compose.runtime.remember(type) { mutableStateOf(false) }
    var draft by androidx.compose.runtime.remember(type) { mutableStateOf("") }
    val options = (CategoryCatalog.options(type, custom) + selected).filter { it.isNotBlank() }.distinct()
    options.chunked(4).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { item ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = selected == item,
                    onClick = { onSelected(item) },
                    label = { Text(item, maxLines = 1) },
                )
            }
            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    TextButton(onClick = { draft = ""; adding = true }) { Text("＋ 添加自定义分类") }
    if (adding) {
        val normalized = CategoryCatalog.normalize(type, draft)
        val isMerged = draft.isNotBlank() && normalized != draft.trim()
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("添加分类") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it.take(40) },
                        label = { Text("分类名称") },
                        singleLine = true,
                    )
                    Text(
                        if (isMerged) "为避免重复，建议归入“$normalized”" else "相同含义请优先使用已有分类",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isMerged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.isNotBlank(),
                    onClick = {
                        if (!isMerged && normalized !in builtIn) {
                            custom = custom + normalized
                            context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
                                .edit().putStringSet(key, custom).apply()
                        }
                        onSelected(normalized)
                        adding = false
                    },
                ) { Text(if (isMerged) "使用“$normalized”" else "添加并使用") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun TransactionDateButton(valueMs: Long, onValueChange: (Long) -> Unit) {
    val context = LocalContext.current
    val date = Instant.ofEpochMilli(valueMs).atZone(ZoneId.systemDefault()).toLocalDate()
    TextButton(
        onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day -> onValueChange(changeLocalDate(valueMs, year, month + 1, day)) },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth,
            ).show()
        },
    ) { Text("日期：${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}  ›") }
}

@Composable
private fun LeafCategoryDialog(
    type: ManualTransactionType,
    categories: List<LedgerCategoryEntity>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val typed = categories.filter { it.type == type }
    val parentIds = typed.mapNotNull { it.parentId }.toSet()
    val leaves = typed.filter { it.id !in parentIds }.sortedBy { categoryNodePath(it.id, typed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更改账单分类") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(leaves, key = { it.id }) { node ->
                    FilterChip(
                        selected = node.id == selectedId,
                        onClick = { onSelect(node.id) },
                        label = { Text(categoryNodePath(node.id, typed)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private data class CategoryTreeRow(val node: LedgerCategoryEntity, val depth: Int)

private fun flattenedCategoryTree(categories: List<LedgerCategoryEntity>): List<CategoryTreeRow> {
    val children = categories.groupBy { it.parentId }
    val result = mutableListOf<CategoryTreeRow>()
    fun visit(node: LedgerCategoryEntity, depth: Int) {
        result += CategoryTreeRow(node, depth)
        children[node.id].orEmpty().sortedWith(compareBy<LedgerCategoryEntity> { it.sortOrder }.thenBy { it.name })
            .forEach { visit(it, depth + 1) }
    }
    children[null].orEmpty().sortedWith(compareBy<LedgerCategoryEntity> { it.sortOrder }.thenBy { it.name })
        .forEach { visit(it, 0) }
    return result
}

private fun categoryNodePath(id: String, categories: List<LedgerCategoryEntity>): String {
    val byId = categories.associateBy { it.id }
    val names = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var node = byId[id]
    while (node != null && seen.add(node.id)) {
        names += node.name
        node = node.parentId?.let(byId::get)
    }
    return names.asReversed().joinToString(CategoryCatalog.HIERARCHY_SEPARATOR)
}

private fun categoryDescendantIds(id: String, categories: List<LedgerCategoryEntity>): Set<String> {
    val children = categories.groupBy { it.parentId }
    val result = linkedSetOf<String>()
    fun visit(current: String) { result += current; children[current].orEmpty().forEach { visit(it.id) } }
    visit(id)
    return result
}

@Composable
private fun CategoryTreeManagerDialog(
    categories: List<LedgerCategoryEntity>,
    rows: List<ManualTransactionEntity>,
    onDismiss: () -> Unit,
    onMove: (String, String?) -> Unit,
    onMerge: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var type by remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var pendingMerge by remember { mutableStateOf<Pair<LedgerCategoryEntity, LedgerCategoryEntity>?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    var dragHint by remember { mutableStateOf<String?>(null) }
    val bounds = remember(type, categories) { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val typed = categories.filter { it.type == type }
    val treeRows = flattenedCategoryTree(typed)
    val rootTarget = "__root__"

    pendingMerge?.let { (source, target) ->
        AlertDialog(
            onDismissRequest = { pendingMerge = null },
            title = { Text("合并同名分类？") },
            text = { Text("“${categoryNodePath(source.id, typed)}”将合并到“${categoryNodePath(target.id, typed)}”。来源分类的账单和子分类都会迁移，此操作会同步到云端。") },
            confirmButton = { TextButton(onClick = { onMerge(source.id, target.id); pendingMerge = null }) { Text("确认合并") } },
            dismissButton = { TextButton(onClick = { pendingMerge = null }) { Text("取消") } },
        )
    }
    pendingDelete?.let { node ->
        val ids = categoryDescendantIds(node.id, typed)
        val affectedRows = rows.filter { it.categoryId in ids }
        val preview = affectedRows.take(6).joinToString("\n") { row ->
            "${formatTime(row.occurredAtMs)}  ${money(row.amountCents)}  ${row.category}" +
                row.note?.let { "  ·  ${it.take(24)}" }.orEmpty()
        }
        val remaining = (affectedRows.size - 6).coerceAtLeast(0)
        val affectedSummary = buildString {
            append("将删除 ${ids.size} 个分类节点。${affectedRows.size} 条历史账单将转入“无分类”。")
            if (preview.isBlank()) append("\n\n当前没有受影响账单。")
            else {
                append("\n\n受影响账单：\n").append(preview)
                if (remaining > 0) append("\n……另有 $remaining 条")
            }
            append("\n\n是否确定？")
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除“${node.name}”？") },
            text = { Text(affectedSummary) },
            confirmButton = {
                TextButton(onClick = { onDelete(node.id); pendingDelete = null }) { Text("确定删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类树管理") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ManualTransactionType.entries.forEach { option ->
                        FilterChip(selected = type == option, onClick = {
                            type = option
                            draggedId = null
                            dragPosition = null
                            dragHint = null
                        }, label = { Text(typeLabel(option)) })
                    }
                }
                Text("长按分类后拖到另一分类：成为其子分类；同名则询问合并。", style = MaterialTheme.typography.bodySmall)
                dragHint?.let {
                    Text(
                        it,
                        color = if (it.startsWith("已提交")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .onGloballyPositioned { bounds[rootTarget] = it.boundsInRoot() },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) { Text("拖到这里成为一级分类", modifier = Modifier.padding(10.dp), textAlign = TextAlign.Center) }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(treeRows, key = { it.node.id }) { item ->
                        val node = item.node
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .onGloballyPositioned { bounds[node.id] = it.boundsInRoot() }
                                .pointerInput(node.id, type) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { local ->
                                            draggedId = node.id
                                            dragPosition = (bounds[node.id]?.topLeft ?: Offset.Zero) + local
                                            dragHint = null
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragPosition = (dragPosition ?: Offset.Zero) + amount
                                        },
                                        onDragCancel = { draggedId = null; dragPosition = null },
                                        onDragEnd = {
                                            val sourceId = draggedId
                                            val position = dragPosition
                                            val targetKey = if (position == null) null else bounds.entries
                                                .firstOrNull { (key, rect) -> key != sourceId && rect.contains(position) }?.key
                                            val source = typed.firstOrNull { it.id == sourceId }
                                            val target = typed.firstOrNull { it.id == targetKey }
                                            if (source != null && targetKey == rootTarget) {
                                                onMove(source.id, null)
                                                dragHint = "已提交移动，正在刷新分类树…"
                                            }
                                            else if (source != null && target != null && !target.isSystem) {
                                                if (source.name == target.name) pendingMerge = source to target
                                                else onMove(source.id, target.id)
                                                if (source.name != target.name) dragHint = "已提交移动，正在刷新分类树…"
                                            }
                                            else dragHint = if (target?.isSystem == true) "“无分类”不能包含子分类"
                                                else "没有落到有效分类上，请长按后拖到目标分类行"
                                            draggedId = null
                                            dragPosition = null
                                        },
                                    )
                                }
                                .background(if (draggedId == node.id) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .padding(vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width((item.depth * 18).dp))
                            Icon(Icons.Default.DragIndicator, contentDescription = "拖动 ${node.name}", tint = MaterialTheme.colorScheme.secondary)
                            Text(node.name, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            val directCount = rows.count { it.categoryId == node.id }
                            if (directCount > 0) Text("$directCount 笔", style = MaterialTheme.typography.labelSmall)
                            if (!node.isSystem) IconButton(onClick = { pendingDelete = node }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除 ${node.name}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun SyncSettingsPage(
    status: ManualSyncStatus,
    categories: List<LedgerCategoryEntity>,
    rows: List<ManualTransactionEntity>,
    onConfigure: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
    onMoveCategory: (String, String?) -> Unit,
    onMergeCategories: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
) {
    var endpoint by androidx.compose.runtime.remember(status.endpoint) { mutableStateOf(status.endpoint) }
    var token by androidx.compose.runtime.remember { mutableStateOf("") }
    var managingCategories by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (managingCategories) CategoryTreeManagerDialog(
        categories = categories,
        rows = rows,
        onDismiss = { managingCategories = false },
        onMove = onMoveCategory,
        onMerge = onMergeCategories,
        onDelete = onDeleteCategory,
    )
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text("安全与同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Button(onClick = { managingCategories = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Icon(Icons.Default.DragIndicator, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("分类管理 · 长按拖动")
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (status.configured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Icon(
                        if (status.configured) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        if (status.configured) "云端保护已开启" else "先存在本机，需要时再开启云端",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        when {
                            status.lastError != null -> "最近同步：${status.lastError}"
                            status.lastSuccessAtMs > 0 -> "上次成功：${formatTime(status.lastSuccessAtMs)}"
                            status.configured -> "配置完成，等待首次同步"
                            else -> "没网也能正常记账；开启后自动补传，不需要手动盯着。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.lastError == null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                }
            }
            OutlinedTextField(
                endpoint,
                { endpoint = it.take(200) },
                label = { Text("同步地址") },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                singleLine = true,
            )
            OutlinedTextField(
                token,
                { token = it.take(200) },
                label = { Text(if (status.configured) "替换同步密钥" else "同步密钥") },
                supportingText = { Text("密钥使用 Android Keystore 加密，只保存在这台手机") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(
                onClick = { onConfigure(endpoint, token); token = "" },
                enabled = endpoint.startsWith("https://") && token.length >= 20,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (status.configured) "更新配置并同步" else "开启云端同步")
            }
            if (status.configured) {
                TextButton(onClick = onSyncNow, modifier = Modifier.fillMaxWidth()) { Text("立即同步") }
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("断开云端（不删除本地数据）") }
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("同步原则", fontWeight = FontWeight.Bold)
                    Text("• 保存永远先写本地，不等待网络\n• 同一事件重复上传不会重复记账\n• 修改和删除也会同步\n• 服务器不可用时保留待传队列", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AnalysisPage(
    month: YearMonth,
    rows: List<ManualTransactionEntity>,
    allRows: List<ManualTransactionEntity>,
    income: Long,
    expense: Long,
    previousExpense: Long,
    monthlyBudgetCents: Long,
    onBudgetChange: (String) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    resolveCategory: (ManualTransactionEntity) -> String,
    onMapCategory: (ManualTransactionType, String, String) -> Unit,
) {
    val expenses = rows.filter { it.type == ManualTransactionType.EXPENSE }
    val grouped = expenses.groupBy(resolveCategory)
        .mapValues { entry -> entry.value.sumOf { it.amountCents } }
        .entries.sortedByDescending { it.value }
    val zone = ZoneId.systemDefault()
    val activeDays = rows.map { Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).toLocalDate() }.distinct().size
    val daysElapsed = if (month == YearMonth.now()) LocalDate.now().dayOfMonth else month.lengthOfMonth()
    val projectedExpense = if (month == YearMonth.now())
        LedgerInsights.projectedExpense(expense, daysElapsed, month.lengthOfMonth()) else expense
    val monthChange = LedgerInsights.monthChangePercent(expense, previousExpense)
    var budgetText by androidx.compose.runtime.remember(monthlyBudgetCents) {
        mutableStateOf(if (monthlyBudgetCents > 0) "%d.%02d".format(monthlyBudgetCents / 100, monthlyBudgetCents % 100) else "")
    }
    val peak = LedgerInsights.peakTime(expenses, zone)
    val weekendShare = LedgerInsights.weekendSharePercent(expenses, zone)
    val highestDay = expenses.groupBy { Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).toLocalDate() }
        .mapValues { (_, dayRows) -> dayRows.sumOf { it.amountCents } }.maxByOrNull { it.value }
    val largest = expenses.maxByOrNull { it.amountCents }
    val dailySpending = LedgerInsights.dailySpending(expenses, month, zone)
    val mergedCount = expenses.count { resolveCategory(it) != it.category }
    var managingCategories by androidx.compose.runtime.remember { mutableStateOf(false) }
    var mappingSource by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val allExpenses = allRows.filter { it.type == ManualTransactionType.EXPENSE }
    val sourceCategories = allExpenses.groupBy { it.category }.mapValues { (_, sourceRows) ->
        sourceRows.size to sourceRows.sumOf { it.amountCents }
    }.entries.sortedByDescending { it.value.second }
    if (managingCategories) {
        AlertDialog(
            onDismissRequest = { managingCategories = false },
            title = { Text("整理随手记分类") },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    item { Text("选择原分类，再归入一个规范分类。只保存归类规则，不改写原始流水。", style = MaterialTheme.typography.bodySmall) }
                    items(sourceCategories, key = { it.key }) { source ->
                        TextButton(onClick = { managingCategories = false; mappingSource = source.key }, modifier = Modifier.fillMaxWidth()) {
                            val hierarchy = CategoryCatalog.hierarchy(source.key)
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("一级：${hierarchy.first}" + (hierarchy.second?.let { "  ·  二级：$it" } ?: "  ·  二级：—"), fontWeight = FontWeight.SemiBold)
                                Text("${source.value.first} 笔  ·  ${money(source.value.second)}  →  ${resolveCategory(allExpenses.first { it.category == source.key })}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { managingCategories = false }) { Text("完成") } },
        )
    }
    mappingSource?.let { source ->
        var target by androidx.compose.runtime.remember(source) {
            mutableStateOf(resolveCategory(allExpenses.first { it.category == source }))
        }
        AlertDialog(
            onDismissRequest = { mappingSource = null },
            title = { Text("“$source”归入") },
            text = { Column { CategoryPicker(ManualTransactionType.EXPENSE, target, CategoryCatalog.defaults(ManualTransactionType.EXPENSE)) { target = it } } },
            confirmButton = {
                TextButton(onClick = { onMapCategory(ManualTransactionType.EXPENSE, source, target); mappingSource = null; managingCategories = true }) { Text("保存归类") }
            },
            dismissButton = { TextButton(onClick = { mappingSource = null }) { Text("取消") } },
        )
    }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onPreviousMonth) { Text("‹ 上月") }
                Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                TextButton(onClick = onNextMonth, enabled = month < YearMonth.now()) { Text("下月 ›") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("本月收入", money(income), Modifier.weight(1f))
                SummaryCard("本月支出", money(expense), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SummaryCard("日均支出", money(expense / daysElapsed), Modifier.weight(1f))
                SummaryCard("记账天数", "$activeDays 天", Modifier.weight(1f))
            }
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(if (month == YearMonth.now()) "预计本月消费" else "本月实际消费", style = MaterialTheme.typography.bodySmall)
                    Text(money(projectedExpense), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        monthChange?.let { if (it >= 0) "当前支出比上月多 $it%" else "当前支出比上月少 ${-it}%" }
                            ?: "上月暂无可比较支出",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (month == YearMonth.now()) Text("按本月前 $daysElapsed 天平均消费速度估算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    if (monthlyBudgetCents > 0 && projectedExpense > monthlyBudgetCents) {
                        Text("照此速度预计超预算 ${money(projectedExpense - monthlyBudgetCents)}", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("消费习惯", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryCard("消费高峰", peak?.label ?: "暂无数据", Modifier.weight(1f))
                SummaryCard("周末消费占比", "$weekendShare%", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                SummaryCard("最高消费日", highestDay?.let { "${it.key.monthValue}/${it.key.dayOfMonth}  ${money(it.value)}" } ?: "暂无", Modifier.weight(1f))
                SummaryCard("最大单笔", largest?.let { money(it.amountCents) } ?: "暂无", Modifier.weight(1f))
            }
            peak?.let { Text("${it.label} 共 ${it.count} 笔，合计 ${money(it.amountCents)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 6.dp)) }
            Text("每日消费日历", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            SpendingCalendar(month, dailySpending)
            Text("每日消费柱状图", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            DailySpendingBarChart(dailySpending)
            Text("分类占比", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            CategoryPieChart(grouped.map { it.key to it.value })
            Text("月预算", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 14.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(budgetText, { budgetText = it.take(14) }, label = { Text("预算金额") }, prefix = { Text("¥ ") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = budgetText.isBlank() || ManualLedgerRepository.parseCents(budgetText) != null, onClick = { onBudgetChange(budgetText) }) { Text("保存") }
            }
            if (monthlyBudgetCents > 0) {
                val ratio = (expense.toFloat() / monthlyBudgetCents).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
                Text(if (expense <= monthlyBudgetCents) "还可支出 ${money(monthlyBudgetCents - expense)}" else "已超预算 ${money(expense - monthlyBudgetCents)}")
            }
            Text("支出分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            TextButton(onClick = { managingCategories = true }, enabled = sourceCategories.isNotEmpty()) { Text("整理随手记分类（${sourceCategories.size} 类）") }
            if (mergedCount > 0) Text("已自动将 $mergedCount 笔相近分类归并统计（不会改动原始流水）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            if (grouped.isEmpty()) Text("本月还没有支出数据")
        }
        items(grouped, key = { it.key }) { item ->
            val ratio = if (expense == 0L) 0f else item.value.toFloat() / expense
            Column(modifier = Modifier.padding(vertical = 7.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.key)
                    Text("${money(item.value)} · ${(ratio * 100).toInt()}%")
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun categoryMappingKey(type: ManualTransactionType, source: String): String = "${type.name}\t${source.trim()}"

private fun loadCategoryMappings(context: android.content.Context): Map<String, String> =
    context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
        .getStringSet("category_mappings", emptySet()).orEmpty().mapNotNull { row ->
            val fields = row.split('\t', limit = 3)
            if (fields.size == 3) "${fields[0]}\t${fields[1]}" to fields[2] else null
        }.toMap()

private fun persistCategoryMappings(context: android.content.Context, mappings: Map<String, String>) {
    val encoded = mappings.map { (key, target) -> "$key\t$target" }.toSet()
    context.getSharedPreferences("manual_settings", android.content.Context.MODE_PRIVATE)
        .edit().putStringSet("category_mappings", encoded).apply()
}

@Composable
private fun SpendingCalendar(month: YearMonth, daily: List<LedgerInsights.DailySpend>) {
    val firstOffset = month.atDay(1).dayOfWeek.value - 1
    val maximum = daily.maxOfOrNull { it.amountCents }?.coerceAtLeast(1L) ?: 1L
    val cells: List<LedgerInsights.DailySpend?> = List(firstOffset) { null } + daily
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall)
            }
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    val intensity = day?.amountCents?.toFloat()?.div(maximum)?.coerceIn(0f, 1f) ?: 0f
                    Surface(
                        modifier = Modifier.weight(1f).padding(2.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = if (day == null) Color.Transparent else MaterialTheme.colorScheme.primary.copy(alpha = 0.07f + intensity * 0.45f),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text(day?.dayOfMonth?.toString().orEmpty(), style = MaterialTheme.typography.labelMedium)
                            Text(
                                day?.takeIf { it.amountCents > 0 }?.let { "%.0f".format(it.amountCents / 100.0) }.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DailySpendingBarChart(daily: List<LedgerInsights.DailySpend>) {
    val maximum = daily.maxOfOrNull { it.amountCents }?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(170.dp)) {
        val baseline = size.height - 20.dp.toPx()
        drawLine(Color(0xFFBBC9C3), Offset(0f, baseline), Offset(size.width, baseline), strokeWidth = 1.dp.toPx())
        val slot = size.width / daily.size.coerceAtLeast(1)
        daily.forEachIndexed { index, day ->
            val height = (baseline - 8.dp.toPx()) * day.amountCents.toFloat() / maximum
            drawRect(
                color = barColor,
                topLeft = Offset(index * slot + slot * 0.18f, baseline - height),
                size = Size(slot * 0.64f, height.coerceAtLeast(if (day.amountCents > 0) 2.dp.toPx() else 0f)),
            )
        }
    }
    Text("横轴为日期，柱高代表当天支出", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun CategoryPieChart(categories: List<Pair<String, Long>>) {
    val positive = categories.filter { it.second > 0 }
    val top = positive.take(5)
    val remainder = positive.drop(5).sumOf { it.second }
    val visible = top + if (remainder > 0) listOf("其他分类" to remainder) else emptyList()
    val total = visible.sumOf { it.second }
    if (total <= 0) {
        EmptyHint("本月暂无支出，分类饼图会在记账后生成")
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(150.dp)) {
            var start = -90f
            visible.forEach { (name, amount) ->
                val sweep = amount.toFloat() * 360f / total
                drawArc(categoryColor(name), start, sweep, useCenter = false, style = Stroke(width = 28.dp.toPx()))
                start += sweep
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            visible.forEach { (name, amount) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(categoryColor(name), CircleShape))
                        Text(name, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${amount * 100 / total}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun categoryColor(category: String): Color {
    val palette = listOf(
        Color(0xFF0B705F), Color(0xFFE18A26), Color(0xFF4D6FC4), Color(0xFF9A5CB4),
        Color(0xFFCC5A71), Color(0xFF4A8B8A), Color(0xFF8B6A45), Color(0xFF65736D),
    )
    return palette[(category.hashCode() and Int.MAX_VALUE) % palette.size]
}

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)
internal fun changeLocalDate(originalMs: Long, year: Int, month: Int, day: Int): Long {
    val original = Instant.ofEpochMilli(originalMs).atZone(ZoneId.systemDefault())
    return LocalDate.of(year, month, day).atTime(original.toLocalTime())
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
private fun money(cents: Long): String = "¥%d.%02d".format(cents / 100, kotlin.math.abs(cents % 100))
private fun typeLabel(type: ManualTransactionType): String = when (type) {
    ManualTransactionType.EXPENSE -> "支出"
    ManualTransactionType.INCOME -> "收入"
    ManualTransactionType.TRANSFER -> "转账"
}
