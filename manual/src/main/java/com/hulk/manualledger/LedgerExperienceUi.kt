package com.hulk.manualledger

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth

/** Only siblings are shown together. Entering a branch never silently selects its first leaf. */
@Composable
internal fun LeafCategoryDialog(type: ManualTransactionType, categories: List<LedgerCategoryEntity>,
                                selectedId: String?, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val typed = categories.filter { it.type == type }
    val byId = typed.associateBy { it.id }
    val parents = typed.mapNotNull { it.parentId }.toSet()
    var parentId by remember(type) { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val ancestors = remember(parentId, typed) {
        val list = mutableListOf<LedgerCategoryEntity>()
        var node = byId[parentId]
        val seen = mutableSetOf<String>()
        while (node != null && seen.add(node.id)) { list += node; node = byId[node.parentId] }
        list.asReversed()
    }
    val options = if (query.isNotBlank()) CategoryTree.selectable(type, typed)
        .filter { CategoryTree.path(it.id, typed).contains(query.trim(), ignoreCase = true) }
    else typed.filter { it.parentId == parentId }.sortedWith(compareBy<LedgerCategoryEntity> { it.sortOrder }.thenBy { it.name })
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(parentId, query) { listState.scrollToItem(0) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择分类") }, text = {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(query, { query = it.take(60) }, label = { Text("搜索完整分类路径") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isBlank()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { parentId = null }) { Text("全部一级") }
                    ancestors.forEach { node ->
                        TextButton(onClick = { parentId = node.id }) { Text("› ${node.name}") }
                    }
                }
                Text("第 ${ancestors.size + 1} 级分类", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
            } else Text("搜索结果 · 显示完整层级", modifier = Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 330.dp)) {
                items(options, key = { it.id }) { node ->
                    val branch = node.id in parents
                    Surface(shape = RoundedCornerShape(12.dp),
                        color = if (node.id == selectedId) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                            .testTag("category-option-${node.id}").clickable {
                                if (branch) parentId = node.id else onSelect(node.id)
                            }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (query.isBlank()) node.name else CategoryTree.path(node.id, typed), fontWeight = FontWeight.Medium)
                                Text(if (branch) "${typed.count { it.parentId == node.id }} 个下级分类" else
                                    "第 ${CategoryTree.path(node.id, typed).split(CategoryCatalog.HIERARCHY_SEPARATOR).size} 级 · 点击选用",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(if (branch) "›" else if (node.id == selectedId) "✓" else "选择", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (options.isEmpty()) item { Text("没有匹配分类", modifier = Modifier.padding(16.dp)) }
            }
            if (selectedId != null) Text("当前：${CategoryTree.path(selectedId, typed)}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }
    }, confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun DateScopeBar(month: YearMonth, day: LocalDate?, allDates: Boolean,
                          onMonth: (YearMonth) -> Unit, onDay: (LocalDate) -> Unit, onAll: (() -> Unit)?) {
    val context = LocalContext.current
    val date = day ?: month.atDay(1)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { if (day != null) onDay(day.minusDays(1)) else onMonth(month.minusMonths(1)) },
                modifier = Modifier.testTag("previous-period")) { Text(if (day != null) "‹ 前一天" else "‹ 上月") }
            TextButton(onClick = {
                DatePickerDialog(context, { _, year, zeroMonth, d -> onDay(LocalDate.of(year, zeroMonth + 1, d)) },
                    date.year, date.monthValue - 1, date.dayOfMonth).show()
            }, modifier = Modifier.testTag("pick-history-date")) {
                Text(if (allDates) "全部日期 ▾" else day?.toString() ?: "${month.year} 年 ${month.monthValue} 月 ▾", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { if (day != null) onDay(day.plusDays(1)) else onMonth(month.plusMonths(1)) },
                modifier = Modifier.testTag("next-period")) { Text(if (day != null) "后一天 ›" else "下月 ›") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (day != null) TextButton(onClick = { onMonth(month) }) { Text("看整月") }
            TextButton(onClick = { onMonth(YearMonth.now()) }) { Text("本月") }
            TextButton(onClick = { onDay(LocalDate.now()) }) { Text("今天") }
            if (onAll != null) TextButton(onClick = onAll) { Text("全部") }
        }
    }
}

internal fun ledgerCategoryColor(name: String): Color = when {
    listOf("饮食", "食品", "餐饮", "早餐", "午餐", "晚餐").any(name::contains) -> Color(0xFFE9A34F)
    listOf("交通", "行车", "出行").any(name::contains) -> Color(0xFF5B8DEF)
    listOf("购物", "服饰", "衣服").any(name::contains) -> Color(0xFF9673DA)
    listOf("居家", "住房", "房租").any(name::contains) -> Color(0xFF4FAE9D)
    listOf("礼", "人情", "社交").any(name::contains) -> Color(0xFFE17F91)
    listOf("医疗", "健康").any(name::contains) -> Color(0xFFCF7563)
    else -> Color.hsl(Math.floorMod(name.hashCode() * 137, 360).toFloat(), 0.46f, 0.59f)
}

@Composable
internal fun SpendingCalendar(month: YearMonth, rows: List<ManualTransactionEntity>,
                               resolveCategory: (ManualTransactionEntity) -> String, onDay: (LocalDate) -> Unit) {
    var perspective by remember { mutableStateOf(true) }
    var fullLegend by remember { mutableStateOf(false) }
    val expenses = rows.filter { it.type == ManualTransactionType.EXPENSE && it.deletedAtMs == null }
    val dayGroups = remember(month, expenses, resolveCategory) {
        (1..month.lengthOfMonth()).associateWith { day ->
            LedgerBrowsing.filter(expenses, month, month.atDay(day)).groupBy(resolveCategory)
                .mapValues { (_, records) -> records.sumOf { it.amountCents } }.toSortedMap()
        }
    }
    val maximum = dayGroups.values.maxOfOrNull { it.values.sum() }?.coerceAtLeast(1) ?: 1L
    val legend = dayGroups.values.flatMap { it.keys }.distinct().sorted()
    Card(Modifier.fillMaxWidth().padding(top = 16.dp), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("消费日历", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("高度看金额 · 颜色看分类", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                FilterChip(selected = perspective, onClick = { perspective = !perspective },
                    label = { Text(if (perspective) "3D 沙盘" else "平面") }, modifier = Modifier.testTag("calendar-perspective"))
            }
            if (perspective) {
                CalendarSandtable(month, dayGroups, maximum, onDay)
            } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val cells: List<Int?> = List(offset) { null } + (1..month.lengthOfMonth()).toList()
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f)) else {
                            val segments = dayGroups.getValue(day)
                            val total = segments.values.sum()
                            val date = month.atDay(day)
                            Column(Modifier.weight(1f).padding(1.dp)
                                .background(if (date == LocalDate.now()) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else Color(0xFFF5F7FA), RoundedCornerShape(8.dp))
                                .testTag("calendar-day-$day")
                                .semantics { contentDescription = "$date 支出 ${AmountExpression.format(total)} 元，${segments.entries.joinToString { "${it.key} ${AmountExpression.format(it.value)} 元" }}，点击查看当天全部流水" }
                                .clickable { onDay(date) }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(day.toString(), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                                    val left = size.width * 0.22f
                                    val width = size.width * 0.46f
                                    val dx = if (perspective) size.width * 0.16f else 0f
                                    val dy = if (perspective) 4.dp.toPx() else 0f
                                    val base = size.height - 3.dp.toPx()
                                    drawOval(Color(0xFFDDE3EB), Offset(left - 2, base - 2), Size(width + dx + 4, 5.dp.toPx()))
                                    var bottom = base
                                    segments.forEach { (name, cents) ->
                                        val height = (size.height - 12.dp.toPx()) * (cents.toDouble() / maximum).toFloat()
                                        val top = bottom - height
                                        val color = ledgerCategoryColor(name)
                                        drawRect(color, Offset(left, top), Size(width, height))
                                        if (perspective) {
                                            drawPath(Path().apply { moveTo(left + width, bottom); lineTo(left + width + dx, bottom - dy)
                                                lineTo(left + width + dx, top - dy); lineTo(left + width, top); close() }, lerp(color, Color.Black, 0.18f))
                                        }
                                        bottom = top
                                    }
                                    if (total > 0 && perspective) {
                                        val color = ledgerCategoryColor(segments.lastKey())
                                        drawPath(Path().apply { moveTo(left, bottom); lineTo(left + dx, bottom - dy)
                                            lineTo(left + width + dx, bottom - dy); lineTo(left + width, bottom); close() }, lerp(color, Color.White, 0.26f))
                                    }
                                }
                                Text(if (total == 0L) "—" else calendarCellAmount(total), maxLines = 1, fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                    repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            }
            Text(if (perspective) "拖动旋转与俯仰 · 双指缩放 · 点日期看流水" else "点日期查看当天全部收支", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(top = 10.dp))
            if (legend.isEmpty()) Text("本月暂无支出", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            else {
                val visible = if (fullLegend) legend else legend.take(4)
                visible.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        pair.forEach { name -> Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(ledgerCategoryColor(name), RoundedCornerShape(3.dp)))
                            Text(name, modifier = Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall)
                        } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (legend.size > 4) TextButton(onClick = { fullLegend = !fullLegend }) { Text(if (fullLegend) "收起图例" else "全部 ${legend.size} 类图例") }
            }
        }
    }
}
