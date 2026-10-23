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
    @Test fun skylineUsesMonetaryHeightAndStableDateVariants() {
        assertEquals(CityBuildingStyle.TREE,CityCalendarModel.style(1,.2f))
        assertEquals(CityBuildingStyle.PAVILION,CityCalendarModel.style(1,.6f))
        assertEquals(CityBuildingStyle.HOUSE,CityCalendarModel.style(2,.6f))
        assertEquals(CityBuildingStyle.DOME,CityCalendarModel.style(1,1f))
        assertEquals(CityBuildingStyle.TERRACE,CityCalendarModel.style(2,1f))
        assertEquals(CityBuildingStyle.PAGODA,CityCalendarModel.style(1,1.6f))
        assertEquals(CityBuildingStyle.PEARL,CityCalendarModel.style(1,2.5f))
    }

    @Test fun amountRatiosHoldForFloorsAndWholeBuildingsIncludingRoofs() {
        val days = mapOf(1 to listOf(CityFloor("a","饮食",100), CityFloor("b","交通",300)),
            2 to listOf(CityFloor("c","饮食",800)),
            3 to (1..8).map { CityFloor("d$it","购物",100) })
        val maximum = days.values.maxOf(CityCalendarModel::total)
        val layers = CityCalendarModel.layers(days.getValue(1), maximum)
        assertEquals(3f, (layers[1].top-layers[1].bottom)/(layers[0].top-layers[0].bottom), .00001f)
        val other = CityCalendarModel.layers(days.getValue(3), maximum)
        assertEquals(layers[0].top, other[0].top, .00001f)
        val scene = buildCityScene(month, days, null)
        fun height(day: Int) = scene.buildings.filter { it.day == day }.flatMap { it.corners }.maxOf { it.z } - CityCalendarModel.GROUND_Z
        assertEquals(2f, height(2)/height(1), .00001f)
        assertEquals(height(2), height(3), .00001f)
        assertEquals(CityCalendarModel.MAX_HEIGHT, height(2), .00001f)
        for (day in 1..3) {
            val points = scene.buildings.filter { it.day == day }.flatMap { it.corners }
            CityCalendarModel.layers(days.getValue(day), maximum).forEach { layer ->
                assertTrue(points.any { kotlin.math.abs(it.z - CityCalendarModel.GROUND_Z - layer.bottom) < .00001f })
                assertTrue(points.any { kotlin.math.abs(it.z - CityCalendarModel.GROUND_Z - layer.top) < .00001f })
            }
        }
    }

    @Test fun datesStayAtTheBottomRightOfEveryCalendarTile() {
        for (day in 1..month.lengthOfMonth()) {
            val center = CalendarProjection.dayCenter(month, day)
            val marker = CityCalendarModel.marker(month, day)
            assertEquals(center.x + .24f, marker.x, .00001f)
            assertEquals(center.y + .25f, marker.y, .00001f)
            assertEquals(.025f, marker.z, .00001f)
        }
    }

    @Test fun tinyAmountsAreNotInflatedAndEmptyScaleIsFinite() {
        val floors = listOf(CityFloor("tiny", "饮食", 1), CityFloor("large", "交通", 999999))
        val layers = CityCalendarModel.layers(floors, 1000000.0)
        assertEquals(.0000025f, layers[0].top, .00000001f)
        assertEquals(2.5f, layers[1].top, .00001f)
        assertEquals(0f, CityCalendarModel.height(0.0, 0.0), 0f)
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

    @Test fun emptyDaysHaveNoDecorativeBuildingsAndTreesHaveNoExtraPlatformOrRoof() {
        assertTrue(buildCityScene(month, emptyMap(), null).buildings.isEmpty())
        val floors = listOf(CityFloor("a", "饮食", 100), CityFloor("b", "交通", 200))
        val days = mapOf(29 to floors, 1 to listOf(CityFloor("large", "购物", 10000)))
        val scene = buildCityScene(month, days, null)
        val height = CityCalendarModel.height(300.0, 10000.0)
        assertEquals(CityBuildingStyle.TREE, CityCalendarModel.style(29, height))
        assertEquals(CityCalendarModel.GROUND_Z + height,
            scene.buildings.filter { it.day == 29 }.flatMap { it.corners }.maxOf { it.z }, .0001f)
    }
}
