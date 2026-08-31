package com.hulk.manualledger

import android.os.Bundle
import android.app.DatePickerDialog
import android.content.Intent
import android.content.ClipData
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
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
        if (uri != null) executor.execute {
            runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
                SuishouCsvParser.parse(text)
            }.onSuccess { parsed ->
                runOnUiThread { pendingImport = parsed }
            }.onFailure { failure ->
                runOnUiThread { message = "导入失败：${failure.message}" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ManualLedgerRepository.open(this)
        monthlyBudgetCents = getSharedPreferences("manual_settings", MODE_PRIVATE)
            .getLong("monthly_budget_cents", 0L)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFFFDF8)) {
                    ManualLedgerScreen(
                        rows = rows,
                        pendingCount = pendingCount,
                        message = message,
                        onSave = ::save,
                        onImport = { importDocument.launch(arrayOf("text/csv", "text/plain")) },
                        onExport = ::export,
                        onDirectMigration = ::migrateToAutomaticLedger,
                        onDelete = ::delete,
                        onEdit = ::edit,
                        pendingImport = pendingImport,
                        onConfirmImport = ::confirmImport,
                        onCancelImport = { pendingImport = null },
                        monthlyBudgetCents = monthlyBudgetCents,
                        onBudgetChange = ::saveMonthlyBudget,
                    )
                }
            }
        }
        executor.execute { refresh() }
    }

    private fun save(input: NewManualTransaction) {
        message = "正在保存到本地…"
        executor.execute {
            runCatching { repository.add(input) }
                .onSuccess { refresh("已保存到本地；同步将在网络可用时后台进行") }
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
                .onSuccess { refresh("已删除；变更会在联网后同步") }
                .onFailure { runOnUiThread { message = "删除失败：${it.message}" } }
        }
    }

    private fun edit(id: String, input: NewManualTransaction) {
        executor.execute {
            runCatching { repository.update(id, input) }
                .onSuccess { changed -> refresh(if (changed) "修改已保存到本地" else "流水不存在，未修改") }
                .onFailure { runOnUiThread { message = "修改失败：${it.message}" } }
        }
    }

    private fun confirmImport() {
        val import = pendingImport ?: return
        pendingImport = null
        message = "正在导入到本地…"
        executor.execute {
            runCatching { import.rows.count { repository.add(it) } }
                .onSuccess { inserted ->
                    val duplicates = import.rows.size - inserted
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

    private fun refresh(after: String? = null) {
        val latest = repository.list()
        val pending = repository.pendingSyncCount()
        runOnUiThread {
            rows = latest
            pendingCount = pending
            if (after != null) message = after
        }
    }
}

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
) {
    var page by androidx.compose.runtime.remember { mutableStateOf(LedgerPage.RECORD) }
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
    val recentCategories = (rows.asSequence().filter { it.type == type }.map { it.category } +
        defaultCategories(type).asSequence()).distinct().take(8).toList()

    pendingImport?.let { preview ->
        val sample = preview.rows.take(3).joinToString("\n") {
            "${typeLabel(it.type)} ${it.amountText} · ${it.category} · ${it.account}"
        }
        AlertDialog(
            onDismissRequest = onCancelImport,
            title = { Text("确认导入随手记数据") },
            text = {
                Text("可识别 ${preview.rows.size} 条，格式异常 ${preview.rejectedRows} 条。\n\n预览：\n$sample\n\n重复流水会按稳定编号自动跳过。")
            },
            confirmButton = { TextButton(onClick = onConfirmImport) { Text("确认导入") } },
            dismissButton = { TextButton(onClick = onCancelImport) { Text("取消") } },
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("本地账本", style = MaterialTheme.typography.headlineMedium)
                Text("${month.monthValue} 月结余 ${money(income - expense)}")
            }
            Text(if (pendingCount == 0L) "✓ 本地安全保存" else "✓ 本地已存 · 云端待传 $pendingCount")
        }
        Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LedgerPage.entries.forEach { option ->
                FilterChip(
                    modifier = Modifier.weight(1f),
                    selected = page == option,
                    onClick = { page = option },
                    label = { Text(option.title) },
                )
            }
        }
        when (page) {
            LedgerPage.RECORD -> RecordPage(
                type = type,
                amount = amount,
                category = category,
                account = account,
                note = note,
                occurredAtMs = occurredAtMs,
                categories = recentCategories,
                onType = { type = it; category = defaultCategories(it).first() },
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
                income = income,
                expense = expense,
                previousExpense = previousExpense,
                monthlyBudgetCents = monthlyBudgetCents,
                onBudgetChange = onBudgetChange,
                onPreviousMonth = { month = month.minusMonths(1) },
                onNextMonth = { if (month < YearMonth.now()) month = month.plusMonths(1) },
            )
        }
    }
}

private enum class LedgerPage(val title: String) { RECORD("记一笔"), FLOW("流水"), ANALYSIS("分析") }

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
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ManualTransactionType.entries.forEach { option ->
            FilterChip(selected = type == option, onClick = { onType(option) }, label = { Text(typeLabel(option)) })
        }
    }
    OutlinedTextField(amount, onAmount, label = { Text("金额") }, prefix = { Text("¥ ") }, modifier = Modifier.fillMaxWidth())
    Text("常用分类", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    LazyColumn(modifier = Modifier.height(96.dp)) {
        items(categories.chunked(4)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { item ->
                    FilterChip(selected = category == item, onClick = { onCategory(item) }, label = { Text(item) })
                }
            }
        }
    }
    OutlinedTextField(category, onCategory, label = { Text("分类（可直接新建）") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(account, onAccount, label = { Text("账户") }, modifier = Modifier.fillMaxWidth())
    TransactionDateButton(occurredAtMs, onOccurredAt)
    OutlinedTextField(note, onNote, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
    Button(
        onClick = onSave,
        enabled = ManualLedgerRepository.parseCents(amount) != null && category.isNotBlank() && account.isNotBlank(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Text("保存到本机") }
    Text("点击保存即完成，不等待网络", style = MaterialTheme.typography.bodySmall)
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
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onImport) { Text("导入随手记 CSV") }
        TextButton(onClick = onExport) { Text("迁移/备份") }
    }
    Button(onClick = onDirectMigration, modifier = Modifier.fillMaxWidth()) {
        Text("一键迁移到自动账本")
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(50) },
        label = { Text("搜索分类、账户、备注或金额") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (rows.isEmpty()) {
        Text("还没有流水，去“记一笔”添加第一条吧。", modifier = Modifier.padding(top = 24.dp))
    } else if (visibleRows.isEmpty()) {
        Text("没有匹配“$query”的流水", modifier = Modifier.padding(top = 24.dp))
    } else LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(visibleRows, key = { it.id }) { row ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(row.category, style = MaterialTheme.typography.titleMedium)
                        Text("${row.account} · ${formatTime(row.occurredAtMs)}", style = MaterialTheme.typography.bodySmall)
                        row.note?.let { Text(it) }
                        Row {
                            TextButton(onClick = { editing = row }) { Text("编辑") }
                            TextButton(onClick = { onDelete(row.id) }) { Text("删除") }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text((if (row.type == ManualTransactionType.EXPENSE) "−" else "+") + money(row.amountCents))
                }
            }
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
                        FilterChip(selected = type == option, onClick = { type = option }, label = { Text(typeLabel(option)) })
                    }
                }
                OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("金额") }, singleLine = true)
                OutlinedTextField(category, { category = it.take(40) }, label = { Text("分类") }, singleLine = true)
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
private fun AnalysisPage(
    month: YearMonth,
    rows: List<ManualTransactionEntity>,
    income: Long,
    expense: Long,
    previousExpense: Long,
    monthlyBudgetCents: Long,
    onBudgetChange: (String) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
) {
    val expenses = rows.filter { it.type == ManualTransactionType.EXPENSE }
    val grouped = expenses.groupBy { it.category }.mapValues { entry -> entry.value.sumOf { it.amountCents } }
        .entries.sortedByDescending { it.value }
    val activeDays = rows.map { Instant.ofEpochMilli(it.occurredAtMs).atZone(ZoneId.systemDefault()).toLocalDate() }.distinct().size
    val daysElapsed = if (month == YearMonth.now()) LocalDate.now().dayOfMonth else month.lengthOfMonth()
    val projectedExpense = if (month == YearMonth.now())
        LedgerInsights.projectedExpense(expense, daysElapsed, month.lengthOfMonth()) else expense
    val monthChange = LedgerInsights.monthChangePercent(expense, previousExpense)
    var budgetText by androidx.compose.runtime.remember(monthlyBudgetCents) {
        mutableStateOf(if (monthlyBudgetCents > 0) "%d.%02d".format(monthlyBudgetCents / 100, monthlyBudgetCents % 100) else "")
    }
    Spacer(Modifier.height(8.dp))
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
        Column(modifier = Modifier.padding(12.dp)) {
            Text("月底预测", style = MaterialTheme.typography.bodySmall)
            Text(money(projectedExpense), style = MaterialTheme.typography.titleLarge)
            Text(
                monthChange?.let { if (it >= 0) "比上月多 $it%" else "比上月少 ${-it}%" }
                    ?: "上月暂无可比较支出",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Text("月预算", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = budgetText,
            onValueChange = { budgetText = it.take(14) },
            label = { Text("预算金额") },
            prefix = { Text("¥ ") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            enabled = budgetText.isBlank() || ManualLedgerRepository.parseCents(budgetText) != null,
            onClick = { onBudgetChange(budgetText) },
        ) { Text("保存") }
    }
    if (monthlyBudgetCents > 0) {
        val budgetRatio = (expense.toFloat() / monthlyBudgetCents).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { budgetRatio }, modifier = Modifier.fillMaxWidth())
        val remaining = monthlyBudgetCents - expense
        Text(
            if (remaining >= 0) "还可支出 ${money(remaining)} · 已用 ${(expense * 100 / monthlyBudgetCents)}%"
            else "已超预算 ${money(-remaining)}",
            color = if (remaining >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    Text("支出分类", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    if (grouped.isEmpty()) Text("本月还没有支出数据")
    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        items(grouped.toList(), key = { it.key }) { item ->
            val ratio = if (expense == 0L) 0f else item.value.toFloat() / expense
            Column(modifier = Modifier.padding(vertical = 7.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(item.key)
                    Text("${money(item.value)} · ${(ratio * 100).toInt()}%")
                }
                LinearProgressIndicator(progress = { ratio }, modifier = Modifier.fillMaxWidth())
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

private fun defaultCategories(type: ManualTransactionType): List<String> = when (type) {
    ManualTransactionType.EXPENSE -> listOf("餐饮", "交通", "购物", "居家", "娱乐", "医疗", "人情", "其他")
    ManualTransactionType.INCOME -> listOf("工资", "奖金", "兼职", "理财", "报销", "退款", "礼金", "其他")
    ManualTransactionType.TRANSFER -> listOf("账户互转", "还款", "借出", "收回", "其他")
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
