package com.hulk.manualledger

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ManualLedgerProvider : ContentProvider() {
    private lateinit var repository: ManualLedgerRepository

    override fun onCreate(): Boolean {
        repository = ManualLedgerRepository.open(requireNotNull(context))
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        require(uri == TRANSACTIONS_URI) { "Unsupported URI" }
        require(selection == null && selectionArgs == null) { "Selection is not supported" }
        val appContext = requireNotNull(context)
        val rows = repository.list()
        return MatrixCursor(COLUMNS, rows.size).apply {
            rows.forEach { row ->
                addRow(arrayOf(
                    row.id, row.type.name, row.amountCents, row.currency, row.category,
                    row.account, row.targetAccount, row.occurredAtMs, row.note, row.updatedAtMs,
                ))
            }
            setNotificationUri(appContext.contentResolver, TRANSACTIONS_URI)
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.com.hulk.manualledger.transaction"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("read-only")

    companion object {
        val TRANSACTIONS_URI: Uri = Uri.parse("content://com.hulk.manualledger.sync/transactions")
        val COLUMNS = arrayOf("id", "type", "amount_cents", "currency", "category", "account", "target_account", "occurred_at_ms", "note", "updated_at_ms")
    }
}
