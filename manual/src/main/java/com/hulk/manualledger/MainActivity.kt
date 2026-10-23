package com.hulk.manualledger

import android.os.Bundle
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
    private var trashRows by mutableStateOf<List<ManualTransactionEntity>>(emptyList())
    private var rows by mutableStateOf<List<ManualTransactionEntity>>(emptyList())
    private var categoryNodes by mutableStateOf<List<LedgerCategoryEntity>>(emptyList())
    private var pendingCount by mutableStateOf(0L)
    private var message by mutableStateOf("本地数据库已就绪")
    private var pendingImport by mutableStateOf<SuishouImportResult?>(null)
    private var monthlyBudgetCents by mutableStateOf(0L)
    private var skin by mutableStateOf(LedgerSkin.RED)
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
        skin = LedgerSkinSettings.load(this)
        updateSystemBarColors()
        repository = ManualLedgerRepository.open(this)
        monthlyBudgetCents = getSharedPreferences("manual_settings", MODE_PRIVATE)
            .getLong("monthly_budget_cents", 0L)
        setContent {
            LedgerTheme(skin) {
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
                        trashRows = trashRows,
                        onRestore = ::restore,
                        onEmptyTrash = ::emptyTrash,
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
                        onRenameCategory = ::renameCategory,
                        onCreateCategory = ::createCategory,
                        onMergeCategories = ::mergeCategories,
                        onDeleteCategory = ::deleteCategory,
                        onChangeTransactionCategory = ::changeTransactionCategory,
                        skin = skin,
                        onSkinChange = { selected ->
                            runCatching { LedgerSkinSettings.select(this, selected) }
                                .onSuccess { skin = selected; updateSystemBarColors(); message = "已切换为${selected.label}皮肤" }
                                .onFailure { message = "皮肤切换失败：${it.message}" }
                        },
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

    private fun updateSystemBarColors() {
        val scheme = if (skin == LedgerSkin.RED) RedLedgerColorScheme else LedgerColorScheme
        window.statusBarColor = scheme.background.toArgb()
        window.navigationBarColor = scheme.surface.toArgb()
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
                .onSuccess { inserted ->
                    if (inserted && input.categoryId != null) getSharedPreferences("manual_entry", MODE_PRIVATE)
                        .edit().putString("category_${input.type.name}", input.categoryId).apply()
                    ManualSyncScheduler.syncNow(this)
                    refresh("已保存到本地；同步将在网络可用时后台进行")
                }
                .onFailure { runOnUiThread { message = it.message ?: "保存失败" } }
        }
    }

    private fun export() {
        executor.execute {
            exportPayload = ManualLedgerMigrationCodec.exportJson(repository.list(), repository.categories())
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
                .onSuccess { ManualSyncScheduler.syncNow(this); refresh("已移入回收站，可随时恢复；变更会在联网后同步") }
                .onFailure { runOnUiThread { message = "删除失败：${it.message}" } }
        }
    }

    private fun restore(id: String) {
        executor.execute {
            runCatching { repository.restore(id) }
                .onSuccess { restored ->
                    if (restored) ManualSyncScheduler.syncNow(this)
                    refresh(if (restored) "流水已恢复" else "流水已不在回收站")
                }
                .onFailure { runOnUiThread { message = "恢复失败：${it.message}" } }
        }
    }

    private fun emptyTrash(ids: List<String>) {
        executor.execute {
            runCatching { repository.emptyTrash(ids) }
                .onSuccess { count -> refresh("已永久删除 $count 条回收站流水") }
                .onFailure { runOnUiThread { message = "清空失败：${it.message}" } }
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
        val online = hasValidatedNetwork()
        message = syncStartMessage(pendingCount, online)
        ManualSyncScheduler.syncNow(this)
        if (!online) return
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

    private fun hasValidatedNetwork(): Boolean {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun renameCategory(id: String, name: String) = mutateCategories("分类已重命名，原账单仍绑定同一分类") {
        repository.renameCategory(id, name)
    }

    private fun createCategory(type: ManualTransactionType, name: String, parentId: String?) = mutateCategories("分类已创建") {
        repository.createCategory(type, name, parentId)
        CategoryMutationResult(0)
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
        val latestTrash = repository.trash()
        val latestCategories = repository.categories()
        val pending = repository.pendingSyncCount()
        val latestSync = ManualSyncSettings.status(this)
        runOnUiThread {
            rows = latest
            trashRows = latestTrash
            categoryNodes = latestCategories
            pendingCount = pending
            syncStatus = latestSync
            if (after != null) message = after
        }
    }
}

@Composable
internal fun ManualLedgerScreen(
    rows: List<ManualTransactionEntity>,
    categoryNodes: List<LedgerCategoryEntity>,
    pendingCount: Long,
    message: String,
    onSave: (NewManualTransaction) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDirectMigration: () -> Unit,
    onDelete: (String) -> Unit,
    trashRows: List<ManualTransactionEntity>,
    onRestore: (String) -> Unit,
    onEmptyTrash: (List<String>) -> Unit,
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
    onRenameCategory: (String, String) -> Unit,
    onCreateCategory: (ManualTransactionType, String, String?) -> Unit,
    onMergeCategories: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onChangeTransactionCategory: (String, String) -> Unit,
    skin: LedgerSkin,
    onSkinChange: (LedgerSkin) -> Unit,
) {
    fun resolveCategory(row: ManualTransactionEntity): String = CategoryTree.rootName(row, categoryNodes)
    var page by androidx.compose.runtime.remember { mutableStateOf(LedgerPage.OVERVIEW) }
    var type by androidx.compose.runtime.remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var entryCurrency by remember { mutableStateOf("CNY") }
    val currencyEntry = rememberCurrencyEntry(amount, entryCurrency)
    var categoryId by remember { mutableStateOf<String?>(null) }
    val entryPreferences = LocalContext.current.getSharedPreferences("manual_entry", android.content.Context.MODE_PRIVATE)
    val category = CategoryTree.path(categoryId, categoryNodes)
    androidx.compose.runtime.LaunchedEffect(type, categoryNodes, rows) {
        if (CategoryTree.selectable(type, categoryNodes).none { it.id == categoryId }) {
            categoryId = LedgerBrowsing.defaultCategory(type, categoryNodes, rows,
                entryPreferences.getString("category_${type.name}", null))
        }
    }
    var account by androidx.compose.runtime.remember { mutableStateOf("现金") }
    var note by androidx.compose.runtime.remember { mutableStateOf("") }
    var occurredAtMs by androidx.compose.runtime.remember { mutableStateOf(System.currentTimeMillis()) }
    var month by androidx.compose.runtime.remember { mutableStateOf(YearMonth.now()) }
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }
    var allDates by remember { mutableStateOf(false) }
    fun openDay(day: LocalDate) {
        month = YearMonth.from(day)
        selectedDay = day
        allDates = false
        page = LedgerPage.FLOW
    }
    val monthRows = LedgerBrowsing.filter(rows, month, null)
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
            Surface(shadowElevation = 8.dp, shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = MaterialTheme.colorScheme.surface) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                LedgerPage.entries.forEach { option ->
                    NavigationBarItem(
                        selected = page == option,
                        onClick = { page = option },
                        icon = { Icon(option.icon, contentDescription = option.title, modifier = Modifier.size(23.dp)) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.secondary, unselectedTextColor = MaterialTheme.colorScheme.secondary),
                        label = { Text(option.title) },
                    )
                }
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
            if (page != LedgerPage.RECORD && page != LedgerPage.SETTINGS) {
                DateScopeBar(month, if (page == LedgerPage.FLOW) selectedDay else null,
                    allDates && page == LedgerPage.FLOW,
                    onMonth = { month = it; selectedDay = null; allDates = false },
                    onDay = ::openDay,
                    onAll = if (page == LedgerPage.FLOW) ({ allDates = true; selectedDay = null }) else null)
            }
            when (page) {
            LedgerPage.OVERVIEW -> OverviewPage(
                month = month,
                rows = monthRows,
                categoryNodes = categoryNodes,
                income = income,
                expense = expense,
                monthlyBudgetCents = monthlyBudgetCents,
                onAdd = { page = LedgerPage.RECORD },
                onAllFlows = { selectedDay = null; allDates = false; page = LedgerPage.FLOW },
                onAnalysis = { page = LedgerPage.ANALYSIS },
                onDay = ::openDay,
                resolveCategory = ::resolveCategory,
                onChangeTransactionCategory = onChangeTransactionCategory,
            )
            LedgerPage.RECORD -> RecordPage(
                type = type,
                amount = amount,
                category = category,
                account = account,
                note = note,
                occurredAtMs = occurredAtMs,
                categoryNodes = categoryNodes,
                selectedCategoryId = categoryId,
                currencyEntry = currencyEntry,
                onCurrency = { entryCurrency = it },
                onType = { if (type != it) { type = it; categoryId = null } },
                onAmount = { amount = it.take(120) },
                onCategory = { categoryId = it },
                onAccount = { account = it.take(40) },
                onNote = { note = it.take(200) },
                onOccurredAt = { occurredAtMs = it },
                onSave = {
                    onSave(NewManualTransaction(
                        type = type,
                        amountText = currencyEntry.result.getOrNull()?.originalAmount ?: return@RecordPage,
                        currency = currencyEntry.currency,
                        exchangeQuote = currencyEntry.result.getOrNull()?.quote,
                        category = category,
                        categoryId = categoryId,
                        account = account,
                        occurredAtMs = occurredAtMs,
                        note = note,
                    ))
                    amount = ""
                    note = ""
                    openDay(Instant.ofEpochMilli(occurredAtMs).atZone(ZoneId.systemDefault()).toLocalDate())
                    occurredAtMs = System.currentTimeMillis()
                },
            )
            LedgerPage.FLOW -> FlowPage(
                rows, categoryNodes, onImport, onExport, onDirectMigration, onDelete,
                trashRows, onRestore, onEmptyTrash, onEdit,
                onChangeTransactionCategory,
                filterMonth = if (allDates) null else month,
                filterDay = selectedDay,
                onRevealDate = ::openDay,
            )
            LedgerPage.ANALYSIS -> AnalysisPage(
                month = month,
                rows = monthRows,
                income = income,
                expense = expense,
                previousExpense = previousExpense,
                monthlyBudgetCents = monthlyBudgetCents,
                onBudgetChange = onBudgetChange,
                onDay = ::openDay,
                resolveCategory = ::resolveCategory,
            )
            LedgerPage.SETTINGS -> SyncSettingsPage(
                syncStatus, categoryNodes, rows, onConfigureSync, onDisconnectSync, onSyncNow,
                onMoveCategory, onRenameCategory, onCreateCategory, onMergeCategories, onDeleteCategory,
                skin, onSkinChange,
            )
            }
        }
    }
}

private enum class LedgerPage(val title: String, val icon: ImageVector) {
    OVERVIEW("首页", Icons.Outlined.Home),
    FLOW("流水", Icons.AutoMirrored.Outlined.ReceiptLong),
    RECORD("记一笔", Icons.Default.AddCircle),
    ANALYSIS("分析", Icons.Outlined.Analytics),
    SETTINGS("设置", Icons.Outlined.Settings),
}

@Composable
private fun LedgerHeader(pendingCount: Long, cloudConfigured: Boolean, message: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text("本地账本", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("让每一笔生活，清晰有序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
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
    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
}

@Composable
private fun OverviewPage(
    month: YearMonth,
    rows: List<ManualTransactionEntity>,
    categoryNodes: List<LedgerCategoryEntity>,
    income: Long,
    expense: Long,
    monthlyBudgetCents: Long,
    onAdd: () -> Unit,
    onAllFlows: () -> Unit,
    onAnalysis: () -> Unit,
    onDay: (LocalDate) -> Unit,
    resolveCategory: (ManualTransactionEntity) -> String,
    onChangeTransactionCategory: (String, String) -> Unit,
) {
    var changingCategory by remember { mutableStateOf<ManualTransactionEntity?>(null) }
    changingCategory?.let { row ->
        LeafCategoryDialog(
            type = row.type,
            categories = categoryNodes,
            selectedId = row.categoryId,
            onDismiss = { changingCategory = null },
            onSelect = { categoryId ->
                onChangeTransactionCategory(row.id, categoryId)
                changingCategory = null
            },
        )
    }
    val frequency = remember(month, rows, categoryNodes) { LedgerBrowsing.expenseFrequency(rows, month, resolveCategory) }
    val balance = income - expense
    LazyColumn(modifier = Modifier.fillMaxWidth().testTag("overview-list")) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth().background(
                    androidx.compose.ui.graphics.Brush.linearGradient(listOf(
                        androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, Color(0xFF111C18), .64f),
                        androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.primary, Color(0xFF111C18), .28f))))
                    .padding(22.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${month.monthValue} 月结余", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelLarge)
                        Text("人民币 · CNY", color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(money(balance), modifier = Modifier.padding(top = 8.dp), color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(top = 18.dp), color = Color.White.copy(alpha = .14f))
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        MetricWithIcon(Icons.Default.ArrowDownward, "收入", money(income), Color(0xFFBFF5D4))
                        MetricWithIcon(Icons.Default.ArrowUpward, "支出", money(expense), Color(0xFFFFD59C))
                    }
                }
            }
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.AddCircle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("记一笔", fontWeight = FontWeight.Bold)
            }
        }
        item {
            SpendingCalendar(month, rows, resolveCategory, onDay)
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
        item { CategoryFrequencyCard(frequency, onAnalysis) }
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("最近流水", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                TextButton(onClick = onAllFlows) { Text("全部") }
            }
            if (rows.isEmpty()) EmptyHint("还没有流水，点“记一笔”开始你的新账本")
        }
        items(rows.take(4), key = { it.id }) { row ->
            TransactionRow(row, onChangeCategory = { changingCategory = row })
        }
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
            Text(label, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall)
            Text(value, color = Color.White, fontWeight = FontWeight.SemiBold)
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
internal fun RecordPage(
    type: ManualTransactionType,
    amount: String,
    category: String,
    account: String,
    note: String,
    occurredAtMs: Long,
    categoryNodes: List<LedgerCategoryEntity>,
    selectedCategoryId: String?,
    onType: (ManualTransactionType) -> Unit,
    onAmount: (String) -> Unit,
    onCategory: (String) -> Unit,
    onAccount: (String) -> Unit,
    onNote: (String) -> Unit,
    onOccurredAt: (Long) -> Unit,
    onSave: () -> Unit,
    currencyEntry: CurrencyEntryState = rememberCurrencyEntry(amount, "CNY"),
    onCurrency: (String) -> Unit = {},
) {
    var choosingCategory by remember { mutableStateOf(false) }
    if (choosingCategory) LeafCategoryDialog(type, categoryNodes, selectedCategoryId,
        onDismiss = { choosingCategory = false },
        onSelect = { onCategory(it); choosingCategory = false })
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
                    AmountField(amount, onAmount, currencyEntry, onCurrency)
                    Text("消费分类", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp))
                    TextButton(onClick = { choosingCategory = true }) {
                        Text(category.ifBlank { "请选择分类" })
                    }
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
                enabled = currencyEntry.result.isSuccess && category.isNotBlank() && account.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) { Text("保存到本机", fontWeight = FontWeight.Bold) }
            Text("离线也能保存，网络恢复后自动同步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun FlowPage(
    rows: List<ManualTransactionEntity>,
    categoryNodes: List<LedgerCategoryEntity>,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDirectMigration: () -> Unit,
    onDelete: (String) -> Unit,
    trashRows: List<ManualTransactionEntity>,
    onRestore: (String) -> Unit,
    onEmptyTrash: (List<String>) -> Unit,
    onEdit: (String, NewManualTransaction) -> Unit,
    onChangeTransactionCategory: (String, String) -> Unit,
    filterMonth: YearMonth? = null,
    filterDay: LocalDate? = null,
    onRevealDate: (LocalDate) -> Unit = {},
) {
    var showingTrash by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ManualTransactionEntity?>(null) }
    var pendingPurge by remember { mutableStateOf<List<String>?>(null) }
    pendingDelete?.let { row ->
        TwoStepDeleteDialog(
            title = "移入回收站？",
            detail = "${row.category} · ${money(row.amountCents)}\n${row.account} · ${formatTime(row.occurredAtMs)}\n移入后不再计入统计，可在回收站恢复。",
            finalTitle = "再次确认移入回收站",
            finalAction = "确认移入回收站",
            onDismiss = { pendingDelete = null },
            onConfirm = { onDelete(row.id); pendingDelete = null },
        )
    }
    pendingPurge?.let { ids ->
        TwoStepDeleteDialog(
            title = if (ids.size == 1) "彻底删除这条流水？" else "清空回收站？",
            detail = "将永久删除这 ${ids.size} 条流水，无法恢复。回收站不会自动清空。",
            finalTitle = "最后确认：永久删除 ${ids.size} 条流水",
            finalAction = "永久删除，无法恢复",
            onDismiss = { pendingPurge = null },
            onConfirm = { onEmptyTrash(ids); pendingPurge = null },
        )
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { showingTrash = !showingTrash }) {
            Text(if (showingTrash) "返回全部流水" else "回收站（${trashRows.size}）")
        }
        if (showingTrash && trashRows.isNotEmpty()) TextButton(onClick = { pendingPurge = trashRows.map { it.id } }) {
            Text("清空回收站", color = MaterialTheme.colorScheme.error)
        }
    }
    if (showingTrash) {
        Text("回收站", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("流水会一直保留，恢复后重新计入统计；仅彻底删除或清空回收站才会永久删除。", style = MaterialTheme.typography.bodySmall)
        if (trashRows.isEmpty()) EmptyHint("回收站是空的")
        else LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(trashRows, key = { it.id }) { row ->
                Column {
                    TransactionRow(row)
                    Row {
                        TextButton(onClick = { onRestore(row.id) }) { Text("恢复这条流水") }
                        TextButton(onClick = { pendingPurge = listOf(row.id) }) {
                            Text("彻底删除这条流水", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        return
    }
    var query by androidx.compose.runtime.remember { mutableStateOf("") }
    var lastChangedId by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    var editing by androidx.compose.runtime.remember { mutableStateOf<ManualTransactionEntity?>(null) }
    var changingCategory by androidx.compose.runtime.remember { mutableStateOf<ManualTransactionEntity?>(null) }
    val datedRows = LedgerBrowsing.filter(rows, filterMonth, filterDay)
    val visibleRows = datedRows.filter { row ->
        query.isBlank() || listOf(row.category, row.account, row.note.orEmpty(), money(row.amountCents))
            .any { it.contains(query.trim(), ignoreCase = true) }
    }
    editing?.let { row ->
        EditTransactionDialog(
            row = row,
            categoryNodes = categoryNodes,
            onDismiss = { editing = null },
            onSave = { input ->
                lastChangedId = row.id; query = ""
                onRevealDate(Instant.ofEpochMilli(input.occurredAtMs).atZone(ZoneId.systemDefault()).toLocalDate())
                onEdit(row.id, input); editing = null
            },
        )
    }
    changingCategory?.let { row ->
        LeafCategoryDialog(
            type = row.type,
            categories = categoryNodes,
            selectedId = row.categoryId,
            onDismiss = { changingCategory = null },
            onSelect = { categoryId -> lastChangedId = row.id; query = ""; onChangeTransactionCategory(row.id, categoryId); changingCategory = null },
        )
    }
    Text(when { filterDay != null -> "$filterDay 的流水"; filterMonth != null -> "$filterMonth 的流水"; else -> "全部流水" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TextButton(onClick = onImport, modifier = Modifier.weight(1f)) { Text("导入随手记") }
        TextButton(onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出流水与分类") }
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
    lastChangedId?.let { id ->
        visibleRows.firstOrNull { it.id == id }?.let { row ->
            Text("已定位流水：${row.category} · ${money(row.amountCents)}", color = MaterialTheme.colorScheme.primary)
        }
    }
    Text("${visibleRows.size} 笔 · 支出 ${money(visibleRows.filter { it.type == ManualTransactionType.EXPENSE }.sumOf { it.amountCents })} · 收入 ${money(visibleRows.filter { it.type == ManualTransactionType.INCOME }.sumOf { it.amountCents })}",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    androidx.compose.runtime.LaunchedEffect(filterMonth, filterDay) { query = ""; listState.scrollToItem(0) }
    androidx.compose.runtime.LaunchedEffect(rows, lastChangedId, query) {
        val index = visibleRows.indexOfFirst { it.id == lastChangedId }
        if (index >= 0) listState.scrollToItem(index)
    }
    if (rows.isEmpty()) {
        Text("还没有流水，去“记一笔”添加第一条吧。", modifier = Modifier.padding(top = 24.dp))
    } else if (visibleRows.isEmpty()) {
        Text(if (query.isBlank()) "这个日期范围没有流水，可切换日期或查看全部" else "当前日期范围没有匹配“$query”的流水", modifier = Modifier.padding(top = 24.dp))
    } else LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().testTag("flow-list")) {
        items(visibleRows, key = { it.id }) { row ->
            TransactionRow(
                row,
                highlighted = row.id == lastChangedId,
                onEdit = { editing = row },
                onChangeCategory = { changingCategory = row },
                onDelete = { pendingDelete = row },
            )
        }
    }
}

@Composable
private fun TwoStepDeleteDialog(
    title: String,
    detail: String,
    finalTitle: String,
    finalAction: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var finalStep by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (finalStep) finalTitle else title) },
        text = { Text(detail) },
        confirmButton = {
            TextButton(onClick = { if (finalStep) onConfirm() else finalStep = true }) {
                Text(if (finalStep) finalAction else "继续确认", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TransactionRow(
    row: ManualTransactionEntity,
    onEdit: (() -> Unit)? = null,
    onChangeCategory: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    highlighted: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier),
        colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CategoryBadge(row.category)
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(row.category, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${row.account} · ${formatTime(row.occurredAtMs)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                row.originalAmount?.let { original ->
                    Text("${row.originalCurrency} $original · 汇率 ${row.exchangeRateDate}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (onEdit != null || onChangeCategory != null || onDelete != null) Row {
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
    categoryNodes: List<LedgerCategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (NewManualTransaction) -> Unit,
) {
    var type by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.type) }
    var amount by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.originalAmount ?: AmountExpression.format(row.amountCents)) }
    var entryCurrency by remember(row.id) { mutableStateOf(row.originalCurrency ?: "CNY") }
    val currencyEntry = rememberCurrencyEntry(amount, entryCurrency, row.savedQuote())
    var selectedCategoryId by remember(row.id) { mutableStateOf(row.categoryId) }
    var category by remember(row.id) { mutableStateOf(row.category) }
    var account by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.account) }
    var note by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.note.orEmpty()) }
    var occurredAtMs by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.occurredAtMs) }
    var choosingType by androidx.compose.runtime.remember(row.id) { mutableStateOf(false) }
    var choosingCategory by androidx.compose.runtime.remember(row.id) { mutableStateOf(false) }
    if (choosingType) AlertDialog(
        onDismissRequest = { choosingType = false },
        title = { Text("选择账单类型") },
        text = {
            Column {
                ManualTransactionType.entries.forEach { option ->
                    TextButton(
                        onClick = {
                            type = option
                            selectedCategoryId = null
                            category = CategoryTree.path(selectedCategoryId, categoryNodes)
                            choosingType = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text((if (type == option) "✓  " else "") + typeLabel(option), modifier = Modifier.fillMaxWidth()) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { choosingType = false }) { Text("取消") } },
    )
    if (choosingCategory) {
        val typed = categoryNodes.filter { it.type == type }
        val selectedId = selectedCategoryId?.takeIf { id -> typed.any { it.id == id } }
            ?: typed.firstOrNull { categoryNodePath(it.id, typed) == category }?.id
        LeafCategoryDialog(
            type = type,
            categories = categoryNodes,
            selectedId = selectedId,
            onDismiss = { choosingCategory = false },
            onSelect = { id ->
                selectedCategoryId = id
                category = categoryNodePath(id, typed)
                choosingCategory = false
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑流水") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
              item {
                TextButton(onClick = { choosingType = true }, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("账单类型", color = MaterialTheme.colorScheme.secondary)
                        Text("${typeLabel(type)}  ›", fontWeight = FontWeight.SemiBold)
                    }
                }
                AmountField(amount, { amount = it.take(120) }, currencyEntry, { entryCurrency = it })
                TextButton(onClick = { choosingCategory = true }, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text("分类", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        Text("$category  ›", fontWeight = FontWeight.SemiBold)
                    }
                }
                OutlinedTextField(account, { account = it.take(40) }, label = { Text("账户") }, singleLine = true)
                TransactionDateButton(occurredAtMs) { occurredAtMs = it }
                OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注") })
              }
            }
        },
        confirmButton = {
            TextButton(
                enabled = currencyEntry.result.isSuccess && category.isNotBlank() && account.isNotBlank(),
                onClick = {
                    onSave(NewManualTransaction(
                        type = type,
                        amountText = currencyEntry.result.getOrNull()?.originalAmount ?: return@TextButton,
                        currency = currencyEntry.currency,
                        exchangeQuote = currencyEntry.result.getOrNull()?.quote,
                        category = category,
                        categoryId = selectedCategoryId,
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

private fun categoryNodePath(id: String, categories: List<LedgerCategoryEntity>): String = CategoryTree.path(id, categories)

private fun categoryDescendantIds(id: String, categories: List<LedgerCategoryEntity>): Set<String> {
    val children = categories.groupBy { it.parentId }
    val result = linkedSetOf<String>()
    fun visit(current: String) { result += current; children[current].orEmpty().forEach { visit(it.id) } }
    visit(id)
    return result
}

@Composable
internal fun CategoryTreeManagerDialog(
    categories: List<LedgerCategoryEntity>,
    rows: List<ManualTransactionEntity>,
    onDismiss: () -> Unit,
    onMove: (String, String?) -> Unit,
    onRename: (String, String) -> Unit,
    onCreate: (ManualTransactionType, String, String?) -> Unit,
    onMerge: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var createParent by remember { mutableStateOf<String?>(null) }
    var draftName by remember { mutableStateOf("") }
    var moving by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    var type by remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    var pendingMerge by remember { mutableStateOf<Pair<LedgerCategoryEntity, LedgerCategoryEntity>?>(null) }
    var pendingDelete by remember { mutableStateOf<LedgerCategoryEntity?>(null) }
    var dragHint by remember { mutableStateOf<String?>(null) }
    val bounds = remember(type, categories) { mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>() }
    val typed = categories.filter { it.type == type }
    val treeRows = flattenedCategoryTree(typed).filter {
        query.isBlank() || it.node.id == focusedId || categoryNodePath(it.node.id, typed).contains(query.trim(), true)
    }
    androidx.compose.runtime.LaunchedEffect(categories, focusedId) {
        val index = treeRows.indexOfFirst { it.node.id == focusedId }
        if (index >= 0) listState.scrollToItem(index)
    }
    val rootTarget = "__root__"

    if (naming != null || creating) AlertDialog(
        onDismissRequest = { naming = null; creating = false },
        title = { Text(if (creating) "新建分类" else "重命名分类") },
        text = { Column {
            Text(if (creating) createParent?.let { "上级：${categoryNodePath(it, typed)}" } ?: "一级分类" else "${categoryNodePath(naming!!.id, typed)}（保留分类 ID）")
            OutlinedTextField(draftName, { draftName = it.take(40) }, label = { Text("分类名称") }, singleLine = true)
        } },
        confirmButton = { TextButton(enabled = draftName.isNotBlank(), onClick = {
            if (creating) { onCreate(type, draftName, createParent); query = draftName.trim() }
            else naming?.let { focusedId = it.id; query = ""; onRename(it.id, draftName) }
            naming = null; creating = false
        }) { Text("保存分类") } },
        dismissButton = { TextButton(onClick = { naming = null; creating = false }) { Text("取消") } },
    )
    moving?.let { source ->
        val invalid = categoryDescendantIds(source.id, typed)
        var targetQuery by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("移动“${source.name}”到") },
            text = { Column {
                OutlinedTextField(targetQuery, { targetQuery = it }, label = { Text("搜索目标完整路径") })
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    item { TextButton(onClick = { focusedId = source.id; query = ""; onMove(source.id, null); moving = null }) { Text("设为一级分类") } }
                    items(typed.filter { it.id !in invalid && !it.isSystem && categoryNodePath(it.id, typed).contains(targetQuery.trim(), true) }, key = { it.id }) { target ->
                        TextButton(modifier = Modifier.testTag("move-target-${target.id}"), onClick = {
                            focusedId = source.id; query = ""
                            if (source.name == target.name) pendingMerge = source to target
                            else onMove(source.id, target.id)
                            moving = null
                        }) { Text(categoryNodePath(target.id, typed)) }
                    }
                }
            } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moving = null }) { Text("取消") } },
        )
    }
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
                OutlinedTextField(query, { query = it; focusedId = null }, label = { Text("搜索分类完整路径") }, singleLine = true)
                TextButton(onClick = { createParent = null; draftName = ""; creating = true }) { Text("＋ 新建一级分类") }
                focusedId?.let { id -> Text("已定位：${categoryNodePath(id, typed)}", color = MaterialTheme.colorScheme.primary) }
                Text("分类路径与流水、记账和统计一致。可用移动按钮选择目标，也可长按拖动。", style = MaterialTheme.typography.bodySmall)
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
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(treeRows, key = { it.node.id }) { item ->
                        val node = item.node
                        androidx.compose.runtime.DisposableEffect(node.id, categories) {
                            onDispose { bounds.remove(node.id) }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .onGloballyPositioned { bounds[node.id] = it.boundsInRoot() }
                                .pointerInput(node.id, type, categories) {
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
                                            if (source != null && targetKey == rootTarget && source.parentId == null) {
                                                dragHint = "该分类已经是一级分类"
                                            }
                                            else if (source != null && target != null && source.parentId == target.id) {
                                                dragHint = "该分类已经属于“${target.name}”"
                                            }
                                            else if (source != null && targetKey == rootTarget) {
                                                focusedId = source.id
                                                query = ""
                                                onMove(source.id, null)
                                                dragHint = "已提交移动，正在刷新分类树…"
                                            }
                                            else if (source != null && target != null && !target.isSystem) {
                                                if (source.name == target.name) pendingMerge = source to target
                                                else { focusedId = source.id; query = ""; onMove(source.id, target.id) }
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
                            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(categoryNodePath(node.id, typed), fontWeight = if (focusedId == node.id) FontWeight.Bold else FontWeight.Normal)
                                if (!node.isSystem) Row {
                                    TextButton(modifier = Modifier.testTag("rename-${node.id}"), onClick = { naming = node; draftName = node.name }) { Text("改名") }
                                    TextButton(modifier = Modifier.testTag("move-${node.id}"), onClick = { moving = node }) { Text("移动") }
                                    TextButton(onClick = { createParent = node.id; draftName = ""; creating = true }) { Text("加子类") }
                                }
                            }
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
    onRenameCategory: (String, String) -> Unit,
    onCreateCategory: (ManualTransactionType, String, String?) -> Unit,
    onMergeCategories: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    skin: LedgerSkin,
    onSkinChange: (LedgerSkin) -> Unit,
) {
    var endpoint by androidx.compose.runtime.remember(status.endpoint) { mutableStateOf(status.endpoint) }
    var token by androidx.compose.runtime.remember { mutableStateOf("") }
    var managingCategories by androidx.compose.runtime.remember { mutableStateOf(false) }
    if (managingCategories) CategoryTreeManagerDialog(
        categories = categories,
        rows = rows,
        onDismiss = { managingCategories = false },
        onMove = onMoveCategory,
        onRename = onRenameCategory,
        onCreate = onCreateCategory,
        onMerge = onMergeCategories,
        onDelete = onDeleteCategory,
    )
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Text("外观与皮肤", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("桌面图标与 App 配色一起切换", style = MaterialTheme.typography.bodyMedium)
                    Text("已选：${skin.label}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                    LedgerSkin.entries.forEach { option ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { onSkinChange(option) }.padding(vertical = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                                .background(if (option == LedgerSkin.RED) Color(0xFFE32636) else Color(0xFF0B705F))) {
                                Image(painterResource(if (option == LedgerSkin.RED) R.drawable.ic_launcher_foreground
                                    else R.drawable.ic_launcher_foreground_green), contentDescription = null,
                                    modifier = Modifier.fillMaxSize())
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(option.label, fontWeight = FontWeight.SemiBold)
                                Text(if (option == LedgerSkin.RED) "红色图标 · 温暖红色界面" else "绿色图标 · 清爽绿色界面",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            RadioButton(selected = skin == option, onClick = { onSkinChange(option) })
                        }
                    }
                    Text("选择会自动保存；桌面图标可能稍后刷新。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        item {
            Text("安全与同步", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            Button(onClick = { managingCategories = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Icon(Icons.Default.DragIndicator, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("分类管理 · 搜索与整理")
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
    income: Long,
    expense: Long,
    previousExpense: Long,
    monthlyBudgetCents: Long,
    onBudgetChange: (String) -> Unit,
    onDay: (LocalDate) -> Unit,
    resolveCategory: (ManualTransactionEntity) -> String,
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
    var analysisView by remember(month) { mutableStateOf(AnalysisView.TREND) }
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
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
            Text("想看什么？", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisView.entries.forEach { view ->
                    FilterChip(
                        selected = analysisView == view,
                        onClick = { analysisView = view },
                        label = { Text(view.label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (analysisView == AnalysisView.TREND) {
            Text("消费习惯", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
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
            SpendingCalendar(month, rows, resolveCategory, onDay)
            Text("每日消费柱状图", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            DailySpendingBarChart(month, dailySpending, onDay)
            }
            if (analysisView == AnalysisView.CATEGORY) {
            Text("分类占比", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            CategoryPieChart(grouped.map { it.key to it.value })
            Text("支出分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            Text("按分类管理中的一级分类汇总，与流水中的完整分类路径一致。", style = MaterialTheme.typography.bodySmall)
            if (grouped.isEmpty()) Text("本月还没有支出数据")
            }
            if (analysisView == AnalysisView.BUDGET) {
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
            if (monthlyBudgetCents <= 0) EmptyHint("设置一个月预算后，这里会持续显示可用额度和超支风险")
            }
        }
        if (analysisView == AnalysisView.CATEGORY) items(grouped, key = { it.key }) { item ->
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

private enum class AnalysisView(val label: String) {
    TREND("趋势"), CATEGORY("分类"), BUDGET("预算")
}


@Composable
private fun DailySpendingBarChart(month: YearMonth, daily: List<LedgerInsights.DailySpend>, onDay: (LocalDate) -> Unit) {
    val maximum = daily.maxOfOrNull { it.amountCents }?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(170.dp).pointerInput(month, daily, onDay) {
        detectTapGestures { offset ->
            if (daily.isNotEmpty()) {
                val index = (offset.x / (size.width / daily.size)).toInt().coerceIn(0, daily.lastIndex)
                onDay(month.atDay(daily[index].dayOfMonth))
            }
        }
    }) {
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
    var selected by remember(categories) { mutableStateOf<Pair<String, Long>?>(null) }
    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(item.first) },
            text = { Text("本月支出 ${money(item.second)}，占总支出 ${item.second * 100 / total}%。") },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("知道了") } },
        )
    }
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
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { selected = name to amount }.padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
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
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text(value, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
        }
    }
}

private fun categoryColor(category: String): Color = ledgerCategoryColor(category.substringBefore(CategoryCatalog.HIERARCHY_SEPARATOR))

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)
internal fun changeLocalDate(originalMs: Long, year: Int, month: Int, day: Int): Long {
    val original = Instant.ofEpochMilli(originalMs).atZone(ZoneId.systemDefault())
    return LocalDate.of(year, month, day).atTime(original.toLocalTime())
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
internal fun syncStartMessage(pendingCount: Long, online: Boolean): String = when {
    !online && pendingCount > 0 -> "当前无网络，$pendingCount 条流水已排队，联网后自动同步"
    !online -> "当前无网络，已排队等待联网后检查云端更新"
    pendingCount > 0 -> "正在同步，待处理 $pendingCount 条…"
    else -> "正在检查云端更新…"
}
internal fun calendarCellAmount(cents: Long): String = when {
    cents <= 0 -> ""
    cents % 100L == 0L -> (cents / 100L).toString()
    else -> "%d.%02d".format(cents / 100L, kotlin.math.abs(cents % 100L))
}
private fun money(cents: Long): String = "¥%d.%02d".format(cents / 100, kotlin.math.abs(cents % 100))
private fun typeLabel(type: ManualTransactionType): String = when (type) {
    ManualTransactionType.EXPENSE -> "支出"
    ManualTransactionType.INCOME -> "收入"
    ManualTransactionType.TRANSFER -> "转账"
}
