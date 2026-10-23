package com.hulk.manualledger

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object LedgerBrowsing {
    fun filter(rows: List<ManualTransactionEntity>, month: YearMonth?, day: LocalDate?,
               zone: ZoneId = ZoneId.systemDefault()): List<ManualTransactionEntity> = rows.filter {
        val date = Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).toLocalDate()
        it.deletedAtMs == null && when {
            day != null -> date == day
            month != null -> YearMonth.from(date) == month
            else -> true
        }
    }.sortedByDescending { it.occurredAtMs }

    /** Remembered IDs survive rename/move; deleted or non-leaf IDs are never selected. */
    fun defaultCategory(type: ManualTransactionType, categories: List<LedgerCategoryEntity>,
                        rows: List<ManualTransactionEntity>, rememberedId: String?): String? {
        val valid = CategoryTree.selectable(type, categories).map { it.id }.toSet()
        if (rememberedId in valid) return rememberedId
        return rows.asSequence().filter { it.type == type && it.deletedAtMs == null && it.categoryId in valid }
            .maxByOrNull { it.createdAtMs }?.categoryId
    }
}
