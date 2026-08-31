package com.hulk.pillsapp.ledger

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.Executors

private const val MANUAL_LEDGER_SCHEMA = "manual-ledger-v1"
private const val MAX_MANUAL_LEDGER_BYTES = 20 * 1024 * 1024
private const val MAX_MANUAL_LEDGER_ROWS = 100_000

data class ManualLedgerImportRow(
    val id: String,
    val txType: TxType,
    val amountCents: Long,
    val category: String,
    val account: String,
    val targetAccount: String?,
    val occurredAtMs: Long,
    val note: String?,
)

data class ManualLedgerPreview(
    val rows: List<ManualLedgerImportRow>,
    val invalidRows: Int,
    val error: String?,
) {
    val canImport: Boolean get() = rows.isNotEmpty() && invalidRows == 0 && error == null
}

data class ManualLedgerCommitResult(val inserted: Int, val duplicates: Int)

object ManualLedgerMigrationParser {
    fun parse(bytes: ByteArray): ManualLedgerPreview {
        if (bytes.isEmpty()) return failed("文件为空")
        if (bytes.size > MAX_MANUAL_LEDGER_BYTES) return failed("文件超过 20 MiB")
        return runCatching {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.optString("schema") == MANUAL_LEDGER_SCHEMA) { "不是 manual-ledger-v1 迁移文件" }
            val array = root.getJSONArray("transactions")
            require(array.length() <= MAX_MANUAL_LEDGER_ROWS) { "流水超过 100000 条" }
            val rows = ArrayList<ManualLedgerImportRow>(array.length())
            val ids = HashSet<String>()
            var invalid = 0
            repeat(array.length()) { index ->
                val parsed = runCatching {
                    val item = array.getJSONObject(index)
                    val id = item.getString("id").trim().take(128)
                    require(id.isNotBlank() && ids.add(id))
                    val currency = item.optString("currency", "CNY")
                    require(currency == "CNY")
                    val amount = item.getLong("amount_cents")
                    require(amount > 0)
                    val category = item.getString("category").trim().take(40)
                    val account = item.getString("account").trim().take(40)
                    require(category.isNotBlank() && account.isNotBlank())
                    ManualLedgerImportRow(
                        id = id,
                        txType = when (item.getString("type")) {
                            "EXPENSE" -> TxType.PAYMENT
                            "INCOME" -> TxType.INCOME
                            "TRANSFER" -> TxType.TRANSFER
                            else -> error("未知类型")
                        },
                        amountCents = amount,
                        category = category,
                        account = account,
                        targetAccount = item.optNullableString("target_account")?.trim()?.take(40)?.ifBlank { null },
                        occurredAtMs = item.getLong("occurred_at_ms").also { require(it > 0) },
                        note = item.optNullableString("note")?.trim()?.take(200)?.ifBlank { null },
                    )
                }.getOrNull()
                if (parsed == null) invalid++ else rows += parsed
            }
            ManualLedgerPreview(rows, invalid, null)
        }.getOrElse { failed(it.message ?: "文件解析失败") }
    }

    private fun failed(message: String) = ManualLedgerPreview(emptyList(), 0, message)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)
}

sealed interface ManualLedgerImportState {
    data object Idle : ManualLedgerImportState
    data object Reading : ManualLedgerImportState
    data class PreviewReady(val preview: ManualLedgerPreview) : ManualLedgerImportState
    data object Importing : ManualLedgerImportState
    data class Imported(val result: ManualLedgerCommitResult) : ManualLedgerImportState
    data class Failed(val message: String) : ManualLedgerImportState
}

object ManualLedgerMigrationRepository {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "manual-ledger-import") }
    private val _state = MutableStateFlow<ManualLedgerImportState>(ManualLedgerImportState.Idle)
    val state: StateFlow<ManualLedgerImportState> = _state.asStateFlow()

    fun preview(context: Context, uri: Uri) {
        if (_state.value is ManualLedgerImportState.Importing) return
        _state.value = ManualLedgerImportState.Reading
        executor.submit {
            val result = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_MANUAL_LEDGER_BYTES) { "文件超过 20 MiB" }
                        output.write(buffer, 0, read)
                    }
                    ManualLedgerMigrationParser.parse(output.toByteArray())
                } ?: error("无法打开文件")
            }
            _state.value = result.fold(
                onSuccess = { preview ->
                    if (preview.error != null) ManualLedgerImportState.Failed(preview.error)
                    else ManualLedgerImportState.PreviewReady(preview)
                },
                onFailure = { ManualLedgerImportState.Failed(it.message ?: "读取失败") },
            )
        }
    }

    fun confirm() {
        val preview = (_state.value as? ManualLedgerImportState.PreviewReady)?.preview ?: return
        if (!preview.canImport) return
        _state.value = ManualLedgerImportState.Importing
        LedgerKernel.commitManualLedgerPreview(preview) { result ->
            _state.value = result.fold(
                onSuccess = { ManualLedgerImportState.Imported(it) },
                onFailure = { ManualLedgerImportState.Failed(it.message ?: "导入失败") },
            )
        }
    }

    fun reset() { if (_state.value !is ManualLedgerImportState.Importing) _state.value = ManualLedgerImportState.Idle }
}
