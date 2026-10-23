package com.hulk.manualledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CategoryFrequencyCard(categories: List<LedgerBrowsing.CategoryFrequency>, onAnalysis: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val total = categories.sumOf { it.count }.coerceAtLeast(1)
    Card(Modifier.fillMaxWidth().padding(top = 18.dp).testTag("category-frequency"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("常用消费分类", style = MaterialTheme.typography.titleMedium)
                    Text("按消费笔数 · 当前月份", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                TextButton(onClick = onAnalysis) { Text("看分析 ›") }
            }
            if (categories.isEmpty()) Text("本月暂无支出", Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.secondary)
            (if (expanded) categories else categories.take(5)).forEachIndexed { index, category ->
                Column(Modifier.fillMaxWidth().padding(top = 16.dp).testTag("frequency-rank-$index")) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CategoryBadge(category.name)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(category.name, style = MaterialTheme.typography.titleSmall)
                            Text("¥${AmountExpression.format(category.amountCents)}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${category.count} 笔", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${category.count * 100 / total}% 笔数", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp)
                        .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(category.count.toFloat() / total).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp)).background(ledgerCategoryColor(category.name)))
                    }
                }
            }
            if (categories.size > 5) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (expanded) "收起分类" else "查看全部 ${categories.size} 类")
            }
        }
    }
}
