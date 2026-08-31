package com.hulk.manualledger

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

data class SuishouImportResult(
    val rows: List<NewManualTransaction>,
    val rejectedRows: Int,
)

object SuishouCsvParser {
    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
    )

    fun parse(content: String): SuishouImportResult {
        val lines = content.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return SuishouImportResult(emptyList(), 0)
        val header = csvLine(lines.first()).map { it.trim().removePrefix("\uFEFF") }
        fun index(vararg names: String): Int = header.indexOfFirst { cell ->
            names.any { it.equals(cell, ignoreCase = true) }
        }
        val dateIndex = index("日期", "时间", "交易时间")
        val typeIndex = index("类型", "收支类型", "交易类型")
        val amountIndex = index("金额", "交易金额")
        val categoryIndex = index("分类", "类别", "支出分类", "收入分类", "项目")
        val accountIndex = index("账户", "付款账户", "资金账户")
        val noteIndex = index("备注", "说明", "商家")
        if (amountIndex < 0) return SuishouImportResult(emptyList(), lines.size - 1)
        var rejected = 0
        val rows = lines.drop(1).mapNotNull { line ->
            val cells = csvLine(line)
            fun cell(at: Int): String = cells.getOrNull(at)?.trim().orEmpty()
            val amount = normalizeAmount(cell(amountIndex))
            if (ManualLedgerRepository.parseCents(amount) == null) {
                rejected++
                return@mapNotNull null
            }
            NewManualTransaction(
                stableId = UUID.nameUUIDFromBytes("suishou:$line".toByteArray()).toString(),
                type = when (cell(typeIndex)) {
                    "收入" -> ManualTransactionType.INCOME
                    "转账" -> ManualTransactionType.TRANSFER
                    else -> ManualTransactionType.EXPENSE
                },
                amountText = amount,
                category = cell(categoryIndex).ifBlank { "未分类" },
                account = cell(accountIndex).ifBlank { "默认账户" },
                occurredAtMs = parseDate(cell(dateIndex)) ?: System.currentTimeMillis(),
                note = cell(noteIndex).ifBlank { null },
            )
        }
        return SuishouImportResult(rows, rejected)
    }

    private fun parseDate(raw: String): Long? = dateFormats.firstNotNullOfOrNull { formatter ->
        runCatching {
            LocalDateTime.parse(raw, formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun normalizeAmount(raw: String): String = raw
        .replace("¥", "")
        .replace("￥", "")
        .replace(",", "")
        .trim()
        .removePrefix("+")
        .removePrefix("-")

    internal fun csvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    result += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }
}
