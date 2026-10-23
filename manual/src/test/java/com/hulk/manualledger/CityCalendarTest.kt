package com.hulk.manualledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CityCalendarTest {
    private val month=YearMonth.of(2024,2)
    private val zone=ZoneId.of("Asia/Shanghai")
    private fun row(id:String,day:Int,category:String="饮食")=ManualTransactionEntity(
        id,ManualTransactionType.EXPENSE,100,category=category,account="模拟",targetAccount=null,
        occurredAtMs=month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli(),note=null,createdAtMs=1,updatedAtMs=1)

    @Test fun eachActiveExpenseBecomesOneChronologicalFloorWithoutMergingSameCategory() {
        val a=row("a",29);val b=row("b",29).copy(amountCents=50000)
        val c=row("c",29,"交通").copy(occurredAtMs=a.occurredAtMs+1000)
        val ignored=listOf(a.copy(id="trash",deletedAtMs=1),a.copy(id="income",type=ManualTransactionType.INCOME),
            a.copy(id="transfer",type=ManualTransactionType.TRANSFER),a.copy(id="other-month",occurredAtMs=LocalDate.of(2024,3,1).atStartOfDay(zone).toInstant().toEpochMilli()))
        val result=CityCalendarModel.floors(listOf(c,b,a)+ignored,month,{"当前分类 › ${it.category}"},zone)
        assertEquals(setOf(29),result.keys)
        assertEquals(listOf("a","b","c"),result.getValue(29).map { it.transactionId })
        assertEquals(listOf("当前分类 › 饮食","当前分类 › 饮食","当前分类 › 交通"),result.getValue(29).map { it.category })
        assertEquals(50200L,result.getValue(29).sumOf { it.amountCents })
        assertEquals(result,CityCalendarModel.floors(listOf(a,b,c).reversed(),month,{"当前分类 › ${it.category}"},zone))
    }
    @Test fun skylineUsesCountTiersAndStableDateVariants() {
        assertEquals(CityBuildingStyle.PLATFORM,CityCalendarModel.style(1,2))
        assertEquals(CityBuildingStyle.PAVILION,CityCalendarModel.style(1,3))
        assertEquals(CityBuildingStyle.HOUSE,CityCalendarModel.style(2,4))
        assertEquals(CityBuildingStyle.DOME,CityCalendarModel.style(1,5))
        assertEquals(CityBuildingStyle.TERRACE,CityCalendarModel.style(2,7))
        assertEquals(CityBuildingStyle.PAGODA,CityCalendarModel.style(1,8))
        assertEquals(CityBuildingStyle.PAGODA,CityCalendarModel.style(2,11))
        assertEquals(CityBuildingStyle.PEARL,CityCalendarModel.style(1,12))
        assertTrue(CityCalendarModel.top(20,20)>CityCalendarModel.top(2,20))
        assertEquals(2.58f,CityCalendarModel.top(500,500),.001f)
    }
    @Test fun sceneHandlesSparseLeapMonthAndDenseDayWithFiniteGeometryAndDatePicking() {
        val sparse=mapOf(29 to listOf(CityFloor("a","饮食",100)))
        val scene=buildCityScene(month,sparse,29)
        assertEquals((1..29).toSet(),scene.ground.mapNotNull { it.day }.toSet())
        assertTrue(scene.buildings.any { it.day==29 })
        val dense=buildCityScene(month,mapOf(1 to (1..500).map { CityFloor("$it","交通",100) }),null)
        assertTrue(dense.buildings.size<10000)
        (scene.ground+scene.buildings+dense.buildings).forEach { face ->
            assertTrue(face.corners.size>=3)
            face.corners.forEach { assertTrue(it.x.isFinite()&&it.y.isFinite()&&it.z.isFinite());assertTrue(it.z<=3f) }
        }
    }
}
