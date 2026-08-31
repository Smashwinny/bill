package com.hulk.manualledger

import android.os.Bundle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var repository: ManualLedgerRepository
    private var rows by mutableStateOf<List<ManualTransactionEntity>>(emptyList())
    private var pendingCount by mutableStateOf(0L)
    private var message by mutableStateOf("本地数据库已就绪")
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
                val parsed = SuishouCsvParser.parse(text)
                parsed.rows.forEach(repository::add)
                parsed
            }.onSuccess { parsed ->
                refresh("导入 ${parsed.rows.size} 条，跳过 ${parsed.rejectedRows} 条")
            }.onFailure { failure ->
                runOnUiThread { message = "导入失败：${failure.message}" }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = ManualLedgerRepository.open(this)
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
) {
    var type by androidx.compose.runtime.remember { mutableStateOf(ManualTransactionType.EXPENSE) }
    var amount by androidx.compose.runtime.remember { mutableStateOf("") }
    var category by androidx.compose.runtime.remember { mutableStateOf("餐饮") }
    var account by androidx.compose.runtime.remember { mutableStateOf("现金") }
    var note by androidx.compose.runtime.remember { mutableStateOf("") }
    val expense = rows.filter { it.type == ManualTransactionType.EXPENSE }.sumOf { it.amountCents }
    val income = rows.filter { it.type == ManualTransactionType.INCOME }.sumOf { it.amountCents }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("本地账本", style = MaterialTheme.typography.headlineMedium)
        Text("先写本地，再静默同步。断网不影响记账。")
        Text("收入 ${money(income)} · 支出 ${money(expense)} · 待同步 $pendingCount")
        Text(message, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ManualTransactionType.entries.forEach { option ->
                FilterChip(
                    selected = type == option,
                    onClick = { type = option },
                    label = { Text(typeLabel(option)) },
                )
            }
        }
        OutlinedTextField(amount, { amount = it.take(14) }, label = { Text("金额") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(category, { category = it.take(40) }, label = { Text("分类") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(account, { account = it.take(40) }, label = { Text("账户") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it.take(200) }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth())
        Button(
            onClick = {
                onSave(NewManualTransaction(type = type, amountText = amount, category = category, account = account, note = note))
                amount = ""
                note = ""
            },
            enabled = ManualLedgerRepository.parseCents(amount) != null && category.isNotBlank() && account.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("保存") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onImport) { Text("导入随手记 CSV") }
            TextButton(onClick = onExport) { Text("导出迁移文件") }
        }
        Text("最近流水", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.id }) { row ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${typeLabel(row.type)} ${money(row.amountCents)} · ${row.category}")
                        Text("${row.account} · ${formatTime(row.occurredAtMs)}")
                        row.note?.let { Text(it) }
                    }
                }
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private fun formatTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFormat)
private fun money(cents: Long): String = "¥%d.%02d".format(cents / 100, kotlin.math.abs(cents % 100))
private fun typeLabel(type: ManualTransactionType): String = when (type) {
    ManualTransactionType.EXPENSE -> "支出"
    ManualTransactionType.INCOME -> "收入"
    ManualTransactionType.TRANSFER -> "转账"
}
