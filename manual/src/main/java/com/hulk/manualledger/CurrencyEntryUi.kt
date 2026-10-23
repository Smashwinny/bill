package com.hulk.manualledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CurrencyEntryState(val currency: String, val expression: String, val result: Result<BookedAmount>,
    val loading: Boolean, val stale: Boolean, val retry: () -> Unit)

@Composable
internal fun rememberCurrencyEntry(value: String, selectedCurrency: String, lockedQuote: ExchangeQuote? = null): CurrencyEntryState {
    val context = LocalContext.current
    val store = remember(context) { ExchangeRateStore(context) }
    var rates by remember { mutableStateOf(store.cached()) }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var stale by remember { mutableStateOf(store.stale()) }
    LaunchedEffect(store, refresh) {
        loading = true
        rates = withContext(Dispatchers.IO) { runCatching { store.refresh(refresh > 0) }.getOrElse { store.cached() } }
        stale = store.stale()
        loading = false
    }
    val parsed = LedgerCurrencies.parse(value, selectedCurrency)
    val currency = parsed.getOrNull()?.currency ?: selectedCurrency
    val quote = lockedQuote?.takeIf { it.currency == currency } ?: rates[currency]
    val result = parsed.mapCatching {
        val original = AmountExpression.format(AmountExpression.evaluate(it.expression).getOrThrow())
        CurrencyAmounts.convert(original, it.currency, quote)
    }
    return CurrencyEntryState(currency, parsed.getOrNull()?.expression ?: value, result, loading, stale) { refresh++ }
}

@Composable
internal fun AmountField(value: String, onValue: (String) -> Unit,
                         entry: CurrencyEntryState = rememberCurrencyEntry(value, "CNY"),
                         onCurrency: (String) -> Unit = {}) {
    var choosingCurrency by remember { mutableStateOf(false) }
    val booked = entry.result.getOrNull()
    if (choosingCurrency) AlertDialog(onDismissRequest = { choosingCurrency = false }, title = { Text("选择币种") }, text = {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(LedgerCurrencies.names.entries.toList(), key = { it.key }) { (code, name) ->
                TextButton(onClick = {
                    onValue(entry.expression)
                    onCurrency(code)
                    choosingCurrency = false
                }, modifier = Modifier.fillMaxWidth().testTag("currency-$code")) {
                    Text("$code · $name", Modifier.fillMaxWidth())
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = { choosingCurrency = false }) { Text("取消") } })
    Column {
        TextButton(onClick = { choosingCurrency = true }, modifier = Modifier.testTag("choose-currency")) {
            Text("${entry.currency} · ${LedgerCurrencies.names[entry.currency]} ▾")
        }
        TextField(value, { onValue(it.take(120)) }, label = { Text("金额") }, placeholder = { Text("0.00") },
            singleLine = true, textStyle = MaterialTheme.typography.headlineLarge,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
            isError = value.isNotBlank() && booked == null && !entry.loading,
            supportingText = if (value.isBlank()) null else ({
                Text(booked?.let { if (it.originalCurrency == "CNY") "= ¥${AmountExpression.format(it.cnyCents)}"
                    else "${it.originalCurrency} ${it.originalAmount} ≈ ¥${AmountExpression.format(it.cnyCents)}" }
                    ?: if (entry.loading && entry.currency != "CNY") "正在获取汇率…"
                    else entry.result.exceptionOrNull()?.message ?: "金额无效")
            }), modifier = Modifier.fillMaxWidth().testTag("amount-expression"), shape = RoundedCornerShape(16.dp))
        booked?.quote?.let { quote ->
            Text("1 ${quote.currency} ≈ ¥${quote.cnyPerUnit.toBigDecimal().setScale(4, java.math.RoundingMode.HALF_UP)} · ${quote.date} · ${quote.source}" +
                if (entry.stale) " · 缓存汇率" else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (entry.currency != "CNY" && booked == null && !entry.loading) {
            TextButton(onClick = entry.retry) { Text("重新获取汇率") }
        }
    }
}
