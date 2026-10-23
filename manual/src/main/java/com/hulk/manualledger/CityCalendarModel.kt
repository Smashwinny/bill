package com.hulk.manualledger

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal enum class CityBuildingStyle { PLATFORM, HOUSE, PAVILION, TERRACE, DOME, PAGODA, PEARL }
internal data class CityFloor(val transactionId: String, val category: String, val amountCents: Long)
internal object CityCalendarModel {
    fun floors(rows: List<ManualTransactionEntity>, month: YearMonth,
               resolveCategory: (ManualTransactionEntity) -> String,
               zone: ZoneId = ZoneId.systemDefault()): Map<Int, List<CityFloor>> =
        LedgerBrowsing.filter(rows, month, null, zone)
            .filter { it.type == ManualTransactionType.EXPENSE }
            .sortedWith(compareBy<ManualTransactionEntity> { it.occurredAtMs }.thenBy { it.id })
            .groupBy { Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).dayOfMonth }
            .mapValues { (_, records) -> records.map { CityFloor(it.id, resolveCategory(it), it.amountCents) } }

    // More transactions create a taller landmark. Stable date-based variants avoid random reshuffling.
    fun style(day: Int, count: Int): CityBuildingStyle = when {
        count <= 2 -> CityBuildingStyle.PLATFORM
        count <= 4 -> if (day % 2 == 0) CityBuildingStyle.HOUSE else CityBuildingStyle.PAVILION
        count <= 7 -> if (day % 2 == 0) CityBuildingStyle.TERRACE else CityBuildingStyle.DOME
        count <= 11 -> CityBuildingStyle.PAGODA
        else -> CityBuildingStyle.PEARL
    }

    fun floorHeight(maxCount: Int): Float = minOf(.22f, 2.5f / maxCount.coerceAtLeast(1))
    fun top(count: Int, maxCount: Int): Float = .08f + count * floorHeight(maxCount)

    fun marker(month: YearMonth, day: Int, count: Int, maxCount: Int): ScenePoint {
        val center = CalendarProjection.dayCenter(month, day)
        if (count == 0) return center.copy(y = center.y + .20f, z = .04f)
        val roof = when (style(day, count)) {
            CityBuildingStyle.PLATFORM -> .035f
            CityBuildingStyle.HOUSE -> .22f
            CityBuildingStyle.PAVILION -> .21f
            CityBuildingStyle.TERRACE -> .15f
            CityBuildingStyle.DOME -> .23f
            CityBuildingStyle.PAGODA -> .30f
            CityBuildingStyle.PEARL -> .34f
        }
        return center.copy(y = center.y - .08f, z = top(count, maxCount) + roof + .08f)
    }
}
