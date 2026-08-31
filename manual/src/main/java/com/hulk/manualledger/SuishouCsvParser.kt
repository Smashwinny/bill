package com.hulk.manualledger

import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class SuishouImportResult(
    val rows: List<NewManualTransaction>,
    val rejectedRows: Int,
    val rejectedReasons: List<String> = emptyList(),
    val sourceEncoding: String = "UTF-8",
)

/** 不认识的历史日期必须拒绝，绝不能静默写成今天。 */
object SuishouCsvParser {
    private val dateTimeFormats = listOf(
        "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy/M/d H:mm:ss", "yyyy/M/d H:mm",
        "yyyy年M月d日 H:mm:ss", "yyyy年M月d日 H:mm",
    ).map(DateTimeFormatter::ofPattern)
    private val dateFormats = listOf("yyyy-MM-dd", "yyyy/M/d", "yyyy年M月d日")
        .map(DateTimeFormatter::ofPattern)

    fun parse(bytes: ByteArray): SuishouImportResult {
        val utf8 = bytes.toString(Charsets.UTF_8)
        val replacementRatio = if (utf8.isEmpty()) 0.0 else utf8.count { it == '\uFFFD' }.toDouble() / utf8.length
        val (text, encoding) = if (replacementRatio > 0.002) {
            bytes.toString(Charset.forName("GB18030")) to "GB18030"
        } else utf8 to "UTF-8"
        return parse(text).copy(sourceEncoding = encoding)
    }

    fun parse(content: String): SuishouImportResult {
        val delimiter = detectDelimiter(content)
        val records = csvRecords(content, delimiter).filter { row -> row.any { it.isNotBlank() } }
        if (records.isEmpty()) return SuishouImportResult(emptyList(), 0)
        val header = records.first().map(::normalizeHeader)
        fun index(vararg names: String): Int = header.indexOfFirst { cell -> names.any { normalizeHeader(it) == cell } }

        val dateIndex = index("日期", "时间", "交易时间", "发生时间", "记账时间")
        val typeIndex = index("类型", "收支类型", "交易类型")
        val amountIndex = index("金额", "交易金额", "金额(元)", "金额（元）")
        val expenseIndex = index("支出金额", "支出", "支出(元)", "支出（元）")
        val incomeIndex = index("收入金额", "收入", "收入(元)", "收入（元）")
        val categoryIndex = index("分类", "类别", "支出分类", "收入分类", "一级分类", "主分类", "项目")
        val subcategoryIndex = index("二级分类", "子分类")
        val accountIndex = index("账户", "付款账户", "资金账户", "账户1", "转出账户")
        val targetAccountIndex = index("账户2", "收款账户", "转入账户")
        val noteIndices = listOf(index("备注", "说明"), index("商家", "交易对象"), index("项目"), index("成员"))
            .filter { it >= 0 }.distinct()

        if (dateIndex < 0 || (amountIndex < 0 && expenseIndex < 0 && incomeIndex < 0)) {
            val missing = buildList {
                if (dateIndex < 0) add("日期列")
                if (amountIndex < 0 && expenseIndex < 0 && incomeIndex < 0) add("金额列")
            }.joinToString("、")
            return SuishouImportResult(emptyList(), records.size - 1, listOf("缺少$missing，请选择随手记导出的流水 CSV"))
        }

        var rejected = 0
        val reasons = linkedSetOf<String>()
        val rows = records.drop(1).mapIndexedNotNull { rowIndex, cells ->
            fun cell(at: Int): String = cells.getOrNull(at)?.trim().orEmpty()
            val occurredAt = parseDate(cell(dateIndex))
            if (occurredAt == null) {
                rejected++
                if (reasons.size < 5) reasons += "第 ${rowIndex + 2} 行日期无法识别：${cell(dateIndex).take(24)}"
                return@mapIndexedNotNull null
            }
            val explicitType = cell(typeIndex)
            val expenseRaw = cell(expenseIndex)
            val incomeRaw = cell(incomeIndex)
            val type = when {
                explicitType.contains("转账") || explicitType.contains("转出") || explicitType.contains("转入") -> ManualTransactionType.TRANSFER
                explicitType.contains("收入") || incomeRaw.isNotBlank() -> ManualTransactionType.INCOME
                else -> ManualTransactionType.EXPENSE
            }
            val rawAmount = when {
                amountIndex >= 0 && cell(amountIndex).isNotBlank() -> cell(amountIndex)
                type == ManualTransactionType.INCOME -> incomeRaw
                else -> expenseRaw
            }
            val amount = normalizeAmount(rawAmount)
            if (ManualLedgerRepository.parseCents(amount) == null) {
                rejected++
                if (reasons.size < 5) reasons += "第 ${rowIndex + 2} 行金额无法识别：${rawAmount.take(24)}"
                return@mapIndexedNotNull null
            }
            val mainCategory = cell(categoryIndex)
            val subcategory = cell(subcategoryIndex)
            val category = subcategory.ifBlank { mainCategory }.ifBlank { "未分类" }
            val account = cell(accountIndex).ifBlank { "默认账户" }
            val targetAccount = cell(targetAccountIndex).ifBlank { null }
            val note = noteIndices.map(::cell).filter { it.isNotBlank() && it != mainCategory && it != category }
                .distinct().joinToString(" · ").take(200).ifBlank { null }
            val canonical = cells.joinToString("\u001F") { it.trim() }
            NewManualTransaction(
                stableId = UUID.nameUUIDFromBytes("suishou-v2:$canonical".toByteArray(Charsets.UTF_8)).toString(),
                type = type,
                amountText = amount,
                category = category,
                account = account,
                targetAccount = targetAccount,
                occurredAtMs = occurredAt,
                note = note,
            )
        }
        return SuishouImportResult(rows, rejected, reasons.toList())
    }

    private fun normalizeHeader(raw: String): String = raw.trim().removePrefix("\uFEFF")
        .replace(" ", "").replace("\t", "").lowercase()

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        dateTimeFormats.forEach { formatter ->
            runCatching { return LocalDateTime.parse(raw.trim(), formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        }
        dateFormats.forEach { formatter ->
            runCatching { return LocalDate.parse(raw.trim(), formatter).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        }
        return null
    }

    private fun normalizeAmount(raw: String): String = raw.replace("¥", "").replace("￥", "")
        .replace(",", "").replace("元", "").trim().removePrefix("+").removePrefix("-")

    private fun detectDelimiter(content: String): Char {
        val first = content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return listOf(',', '\t', ';').maxByOrNull { csvLine(first, it).size } ?: ','
    }

    internal fun csvLine(line: String): List<String> = csvLine(line, ',')
    internal fun csvLine(line: String, delimiter: Char): List<String> = csvRecords(line, delimiter).firstOrNull().orEmpty()

    internal fun csvRecords(content: String, delimiter: Char): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        fun finishCell() { row += cell.toString(); cell.clear() }
        fun finishRow() { finishCell(); records += row; row = mutableListOf() }
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && quoted && content.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                char == '"' -> quoted = !quoted
                char == delimiter && !quoted -> finishCell()
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') index++
                    finishRow()
                }
                else -> cell.append(char)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        return records
    }
}
