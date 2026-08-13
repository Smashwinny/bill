package com.hulk.pillsapp.ledger

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

sealed interface StatementImportUiState {
    data object Idle : StatementImportUiState
    data object Reading : StatementImportUiState
    data class PreviewReady(val preview: StatementPreview) : StatementImportUiState
    data object Importing : StatementImportUiState
    data class Imported(val result: StatementCommitResult) : StatementImportUiState
    data class Failed(val issue: StatementPreviewIssue) : StatementImportUiState
}

/** SAF 文件选择与脱敏预览状态；未点击确认前不修改账本数据库。 */
object StatementImportRepository {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "statement-preview")
    }
    private val _state = MutableStateFlow<StatementImportUiState>(StatementImportUiState.Idle)
    val state: StateFlow<StatementImportUiState> = _state.asStateFlow()
    private val selectionGeneration = AtomicLong(0)

    fun preview(context: Context, uri: Uri) {
        if (_state.value is StatementImportUiState.Importing) return
        val generation = selectionGeneration.incrementAndGet()
        _state.value = StatementImportUiState.Reading
        executor.submit {
            val result = runCatching {
                val displayName = queryDisplayName(context, uri)
                val bytes = readLimited(context, uri)
                StatementFileParser.parse(displayName, bytes)
            }
            if (selectionGeneration.get() != generation) return@submit
            _state.value = result.fold(
                onSuccess = { preview ->
                    if (preview.issues.size == 1 && preview.rows.isEmpty()) {
                        StatementImportUiState.Failed(preview.issues.single())
                    } else {
                        StatementImportUiState.PreviewReady(preview)
                    }
                },
                onFailure = { StatementImportUiState.Failed(StatementPreviewIssue.UNSUPPORTED_FORMAT) },
            )
        }
    }

    fun confirmImport() {
        val preview = (_state.value as? StatementImportUiState.PreviewReady)?.preview ?: return
        if (!preview.canImport) return
        val generation = selectionGeneration.get()
        _state.value = StatementImportUiState.Importing
        LedgerKernel.commitStatementPreview(preview) { result ->
            if (selectionGeneration.get() != generation) return@commitStatementPreview
            _state.value = result.fold(
                onSuccess = { StatementImportUiState.Imported(it) },
                onFailure = { StatementImportUiState.Failed(StatementPreviewIssue.IMPORT_FAILED) },
            )
        }
    }

    fun reset() {
        if (_state.value is StatementImportUiState.Importing) return
        selectionGeneration.incrementAndGet()
        _state.value = StatementImportUiState.Idle
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)?.takeIf { it.isNotBlank() }?.take(256) ?: "statement"
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.take(256) ?: "statement"
    }

    private fun readLimited(context: Context, uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("无法打开账单文件")
        input.use {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            var total = 0
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_STATEMENT_FILE_BYTES) {
                    return ByteArray(MAX_STATEMENT_FILE_BYTES + 1)
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
