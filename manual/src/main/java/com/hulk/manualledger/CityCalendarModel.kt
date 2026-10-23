package com.hulk.manualledger

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

internal enum class CityBuildingStyle { TREE, HOUSE, PAVILION, TERRACE, DOME, PAGODA, PEARL }
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

    // One linear scale for the whole month; no per-day normalization or minimum floor height.
    const val MAX_HEIGHT = 2.5f
    const val GROUND_Z = .02f
    fun total(floors: List<CityFloor>): Double = floors.sumOf { it.amountCents.coerceAtLeast(0).toDouble() }
    fun height(amountCents: Double, maximumDailyCents: Double): Float =
        if (maximumDailyCents > 0) (MAX_HEIGHT * amountCents / maximumDailyCents).toFloat() else 0f

    fun layers(floors: List<CityFloor>, maximumDailyCents: Double): List<CityLayer> {
        var amount = 0.0
        return floors.map { floor ->
            val bottom = height(amount, maximumDailyCents)
            amount += floor.amountCents.coerceAtLeast(0)
            CityLayer(floor, bottom, height(amount, maximumDailyCents))
        }
    }

    // Silhouettes follow monetary height, with stable date-based variants at the same scale.
    fun style(day: Int, height: Float): CityBuildingStyle = when {
        height <= .38f -> CityBuildingStyle.TREE
        height <= .8f -> if (day % 2 == 0) CityBuildingStyle.HOUSE else CityBuildingStyle.PAVILION
        height <= 1.25f -> if (day % 2 == 0) CityBuildingStyle.TERRACE else CityBuildingStyle.DOME
        height <= 1.9f -> CityBuildingStyle.PAGODA
        else -> CityBuildingStyle.PEARL
    }

    fun marker(month: YearMonth, day: Int): ScenePoint {
        val center = CalendarProjection.dayCenter(month, day)
        return center.copy(x = center.x + .24f, y = center.y + .25f, z = .025f)
    }
}

internal data class CityLayer(val floor: CityFloor, val bottom: Float, val top: Float)
