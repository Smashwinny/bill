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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
                        onSyncNow = { ManualSyncScheduler.syncNow(this) },
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
                .onSuccess { inserted ->
                    val duplicates = import.rows.size - inserted
                    ManualSyncScheduler.syncNow(this)
                    refresh("已导入 $inserted 条；重复 $duplicates 条；格式异常 ${import.rejectedRows} 条")
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

    private fun refresh(after: String? = null) {
        val latest = repository.list()
        val pending = repository.pendingSyncCount()
        val latestSync = ManualSyncSettings.status(this)
        runOnUiThread {
            rows = latest
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
) {
    val context = LocalContext.current
    var categoryMappings by androidx.compose.runtime.remember { mutableStateOf(loadCategoryMappings(context)) }
    fun resolveCategory(row: ManualTransactionEntity): String =
        categoryMappings[categoryMappingKey(row.type, row.category)] ?: CategoryCatalog.normalize(row.type, row.category)
    fun saveCategoryMapping(type: ManualTransactionType, source: String, target: String) {
        categoryMappings = categoryMappings + (categoryMappingKey(type, source) to target)
        persistCategoryMappings(context, categoryMappings)
    }
    var page by androidx.compose.runtime.remember { mutableStateOf(LedgerPage.OVERVIEW) }
    var type by androidx.compose.runtime.remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var category by androidx.compose.runtime.remember { mutableStateOf("餐饮") }
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
                categories = CategoryCatalog.defaults(type),
                onType = { type = it; category = CategoryCatalog.defaults(it).first() },
                onAmount = { amount = it.take(14) },
                onCategory = { category = it.take(40) },
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
            LedgerPage.FLOW -> FlowPage(rows, onImport, onExport, onDirectMigration, onDelete, onEdit)
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
            LedgerPage.SETTINGS -> SyncSettingsPage(syncStatus, onConfigureSync, onDisconnectSync, onSyncNow)
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
    categories: List<String>,
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
                    CategoryPicker(type, category, categories, onCategory)
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
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDirectMigration: () -> Unit,
    onDelete: (String) -> Unit,
    onEdit: (String, NewManualTransaction) -> Unit,
) {
    var query by androidx.compose.runtime.remember { mutableStateOf("") }
    var editing by androidx.compose.runtime.remember { mutableStateOf<ManualTransactionEntity?>(null) }
    val visibleRows = rows.filter { row ->
        query.isBlank() || listOf(row.category, row.account, row.note.orEmpty(), money(row.amountCents))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    editing?.let { row ->
        EditTransactionDialog(
            row = row,
            onDismiss = { editing = null },
            onSave = { input -> onEdit(row.id, input); editing = null },
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
            TransactionRow(row, onEdit = { editing = row }, onDelete = { onDelete(row.id) })
        }
    }
}

@Composable
private fun TransactionRow(
    row: ManualTransactionEntity,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
                if (onEdit != null || onDelete != null) Row {
                    onEdit?.let { TextButton(onClick = it) { Text("编辑") } }
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
                            onClick = { type = option; category = CategoryCatalog.defaults(option).first() },
                            label = { Text(typeLabel(option)) },
                        )
                    }
                }
                OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("金额") }, singleLine = true)
                CategoryPicker(type, category, CategoryCatalog.defaults(type)) { category = it }
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
private fun SyncSettingsPage(
    status: ManualSyncStatus,
    onConfigure: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onSyncNow: () -> Unit,
) {
    var endpoint by androidx.compose.runtime.remember(status.endpoint) { mutableStateOf(status.endpoint) }
    var token by androidx.compose.runtime.remember { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text("安全与同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
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
                            Text("${source.key}  ·  ${source.value.first} 笔  ·  ${money(source.value.second)}  →  ${resolveCategory(allExpenses.first { it.category == source.key })}", modifier = Modifier.fillMaxWidth())
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
