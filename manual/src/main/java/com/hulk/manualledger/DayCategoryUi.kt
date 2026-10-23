package com.hulk.manualledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun DayCategoryPanel(month: YearMonth, days: Map<Int, List<CityFloor>>, selected: Int,
                              onSelect: (Int) -> Unit, onOpen: (LocalDate) -> Unit) {
    val strip = rememberLazyListState()
    LaunchedEffect(month, selected) { strip.animateScrollToItem((selected - 2).coerceAtLeast(0)) }
    LazyRow(state = strip, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().testTag("city-date-strip")) {
        items((1..month.lengthOfMonth()).toList(), key = { it }) { day ->
            val categories = days[day].orEmpty().map { it.category }.distinct()
            FilterChip(selected = selected == day, onClick = { onSelect(day) },
                modifier = Modifier.testTag("city-date-$day").semantics {
                    contentDescription = "${month.monthValue}月${day}日，" +
                        if (categories.isEmpty()) "无支出" else categories.joinToString("、")
                }, label = {
                    Column(Modifier.widthIn(min = 26.dp).padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${day}日", style = MaterialTheme.typography.labelMedium)
                        Row(Modifier.padding(top = 3.dp).height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            categories.take(4).forEach { category ->
                                Box(Modifier.size(5.dp).background(ledgerCategoryColor(category), RoundedCornerShape(2.dp)))
                            }
                        }
                    }
                })
        }
    }
    val floors = days[selected].orEmpty()
    val categories = floors.groupBy { it.category }.entries
        .sortedWith(compareByDescending<Map.Entry<String, List<CityFloor>>> { it.value.size }.thenBy { it.key })
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f),
        modifier = Modifier.fillMaxWidth().testTag("day-category-panel")) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${month.monthValue}月${selected}日 · 当天分类", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("day-category-title"))
                    Text("${floors.size} 笔支出 · ¥${AmountExpression.format(floors.sumOf { it.amountCents })}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = { onOpen(month.atDay(selected)) }, modifier = Modifier.testTag("open-city-day")) { Text("查看当天流水") }
            }
            if (categories.isEmpty()) Text("当天没有支出", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 8.dp))
            categories.forEach { (name, records) ->
                Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("day-category-$name"), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(6.dp).height(30.dp).background(ledgerCategoryColor(name), RoundedCornerShape(3.dp)))
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text("${records.size} 笔", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 12.dp))
                    Text("¥${AmountExpression.format(records.sumOf { it.amountCents })}", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
