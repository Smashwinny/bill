package com.hulk.manualledger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
import org.junit.After
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CurrencyLedgerTest {
    @get:Rule val compose = createComposeRule()
    private val context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("test_currency_$name", mode)
        }
    }
    @After fun removeTestPreferences() {
        context.baseContext.deleteSharedPreferences("test_currency_manual_exchange_rates")
    }
    private fun cache() {
        context.getSharedPreferences("manual_exchange_rates",Context.MODE_PRIVATE).edit()
            .putString("rates", """[{"base":"CNY","quote":"USD","date":"2026-10-22","rate":0.2}]""")
            .putLong("fetched",System.currentTimeMillis()).putLong("attempted",System.currentTimeMillis()).commit()
    }
    @Test fun originalCurrencySnapshotSurvivesDatabaseEditSyncTrashRestoreAndReopen() {
        val name="currency-test-${java.util.UUID.randomUUID()}.db"
        fun open()=Room.databaseBuilder(context,ManualLedgerDatabase::class.java,name).allowMainThreadQueries().build()
        var db=open()
        try {
            var repo=ManualLedgerRepository(db)
            val input=NewManualTransaction(stableId="usd",type=ManualTransactionType.EXPENSE,amountText="10",
                currency="USD",exchangeQuote=ExchangeQuote("USD","7.12345","2026-10-22"),category="饮食",account="卡")
            assertTrue(repo.add(input))
            val row=repo.list().single()
            assertEquals(7123L,row.amountCents);assertEquals("CNY",row.currency)
            assertEquals("10.00",row.originalAmount);assertEquals("USD",row.originalCurrency)
            val decoded=decodeRemoteTransaction(JSONObject(ManualLedgerMigrationCodec.transactionJson(row)))
            assertEquals(row.originalAmount,decoded.originalAmount)
            assertEquals(row.savedQuote(),decoded.savedQuote())
            assertEquals(7123L,decoded.amountCents)
            assertTrue(repo.update("usd",input.copy(note="changed")))
            assertEquals(7123L,repo.list().single().amountCents)
            repo.delete("usd");assertEquals(row.savedQuote(),repo.trash().single().savedQuote())
            assertTrue(repo.restore("usd"))
            db.close();db=open();repo=ManualLedgerRepository(db)
            assertEquals(row.savedQuote(),repo.list().single().savedQuote())
            assertTrue(repo.update("usd",input.copy(currency="CNY",exchangeQuote=null)))
            assertNull(repo.list().single().originalCurrency)
            assertEquals(1000L,repo.list().single().amountCents)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun rateCacheInvertsCnyBaseAndNeverFetchesFreshCache() {
        cache()
        val store=ExchangeRateStore(context)
        assertEquals("5",store.cached().getValue("USD").cnyPerUnit)
        assertEquals(store.cached(),store.refresh())
        assertThrows(IllegalArgumentException::class.java) { ExchangeRateStore.decode("""[{"base":"USD","quote":"USD","rate":1,"date":"2026-10-22"}]""") }
        assertThrows(IllegalArgumentException::class.java) { ExchangeRateStore.decode("""[{"base":"CNY","quote":"USD","rate":0,"date":"2026-10-22"}]""") }
    }
    @Test fun typedCurrencyAndPickerConvertWithoutOperationHints() {
        cache()
        compose.setContent { CompositionLocalProvider(LocalContext provides context) {
            var amount by remember { mutableStateOf("") }
            var currency by remember { mutableStateOf("CNY") }
            val entry=rememberCurrencyEntry(amount,currency)
            MaterialTheme { AmountField(amount,{amount=it},entry,{currency=it}) }
        } }
        compose.onNodeWithText("支持加减乘除、括号；结果四舍五入到分").assertDoesNotExist()
        compose.onNodeWithTag("amount-expression").performTextInput("10美元")
        compose.onNodeWithText("USD 10.00 ≈ ¥50.00").assertIsDisplayed()
        compose.onNodeWithTag("choose-currency").performClick()
        compose.onNodeWithTag("currency-CNY").performClick()
        compose.onNodeWithText("= ¥10.00").assertIsDisplayed()
        compose.onNodeWithTag("choose-currency").performClick()
        compose.onNodeWithTag("currency-USD").performClick()
        compose.onNodeWithText("USD 10.00 ≈ ¥50.00").assertIsDisplayed()
    }
    @Test fun editingNoteKeepsOriginalRateEvenWhenCacheHasNewRate() {
        cache()
        val db=Room.inMemoryDatabaseBuilder(context,ManualLedgerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo=ManualLedgerRepository(db)
            repo.add(NewManualTransaction(stableId="edit-fx",type=ManualTransactionType.EXPENSE,amountText="10",
                currency="USD",exchangeQuote=ExchangeQuote("USD","7","2026-10-20"),category="饮食",account="卡",note="修改外币"))
            compose.setContent { CompositionLocalProvider(LocalContext provides context) { MaterialTheme { Column {
                FlowPage(repo.list(),repo.categories(),{},{},{},{},emptyList(),{},{},{id,input -> repo.update(id,input)},{_,_->})
            } } } }
            compose.onNodeWithText("修改外币").performClick()
            compose.onNodeWithText("USD 10.00 ≈ ¥70.00").assertIsDisplayed()
            compose.onNodeWithText("备注").performScrollTo().performTextReplacement("只改备注")
            compose.onNodeWithText("保存修改").performClick()
            compose.runOnIdle {
                assertEquals(7000L,repo.list().single().amountCents)
                assertEquals("2026-10-20",repo.list().single().exchangeRateDate)
                assertEquals("只改备注",repo.list().single().note)
            }
        } finally { db.close() }
    }
}
