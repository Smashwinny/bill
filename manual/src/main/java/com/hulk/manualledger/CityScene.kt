package com.hulk.manualledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.YearMonth
import kotlin.math.*

internal data class CityFace(val corners: List<ScenePoint>, val color: Color, val day: Int? = null)
internal data class CityScene(val ground: List<CityFace>, val buildings: List<CityFace>)

/** Geometry stays in calendar coordinates: roads, gardens and landmarks rotate as one scene. */
internal fun buildCityScene(month: YearMonth, days: Map<Int, List<CityFloor>>, today: Int?): CityScene {
    val weeks = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
    val ground = mutableListOf<CityFace>()
    val buildings = mutableListOf<CityFace>()
    val stone = Color(0xFFE7DECC)
    val maximumDailyCents = days.values.maxOfOrNull(CityCalendarModel::total) ?: 0.0

    fun quad(target: MutableList<CityFace>, points: List<ScenePoint>, color: Color, day: Int? = null) {
        target += CityFace(points, color, day)
    }
    fun plane(target: MutableList<CityFace>, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, color: Color, day: Int? = null) =
        quad(target, listOf(ScenePoint(x0,y0,z),ScenePoint(x1,y0,z),ScenePoint(x1,y1,z),ScenePoint(x0,y1,z)), color, day)
    fun prism(target: MutableList<CityFace>, x: Float, y: Float, z0: Float, z1: Float,
              radius: Float, upperRadius: Float, sides: Int, color: Color, day: Int?) {
        val angle = if (sides == 4) PI / 4 else 0.0
        val bottom = (0 until sides).map { i -> ScenePoint(x + radius*cos(angle+i*2*PI/sides).toFloat(), y+radius*sin(angle+i*2*PI/sides).toFloat(),z0) }
        val top = (0 until sides).map { i -> ScenePoint(x+upperRadius*cos(angle+i*2*PI/sides).toFloat(),y+upperRadius*sin(angle+i*2*PI/sides).toFloat(),z1) }
        for(i in 0 until sides) {
            val j=(i+1)%sides
            val a=bottom[i];val b=bottom[j];val c=top[j];val d=top[i]
            val light = ((cos(angle+(i+.5)*2*PI/sides)+1)*.5).toFloat()
            target += CityFace(listOf(a,b,c,d), lerp(lerp(color,Color.Black,.08f),lerp(color,Color.White,.04f),light),day)
        }
        quad(target, top, lerp(color, Color.White, .04f), day)
    }
    // Separate foundation draw pass avoids a large top face hiding the far part of the map.
    val x0=-3.6f; val x1=3.6f; val y0=-weeks/2f-.1f;val y1=weeks/2f+.1f
    quad(ground,listOf(ScenePoint(x0,y0,-.22f),ScenePoint(x1,y0,-.22f),ScenePoint(x1,y0,0f),ScenePoint(x0,y0,0f)),Color(0xFFAAB4A4))
    quad(ground,listOf(ScenePoint(x1,y0,-.22f),ScenePoint(x1,y1,-.22f),ScenePoint(x1,y1,0f),ScenePoint(x1,y0,0f)),Color(0xFF91A193))
    quad(ground,listOf(ScenePoint(x1,y1,-.22f),ScenePoint(x0,y1,-.22f),ScenePoint(x0,y1,0f),ScenePoint(x1,y1,0f)),Color(0xFFB5C1AA))
    quad(ground,listOf(ScenePoint(x0,y1,-.22f),ScenePoint(x0,y0,-.22f),ScenePoint(x0,y0,0f),ScenePoint(x0,y1,0f)),Color(0xFF8D9E90))
    plane(ground,x0,y0,x1,y1,0f,Color(0xFFA4B5B5))
    // Small lane dashes make the grid read as streets without obscuring date labels.
    for(col in 0..7) {
        val x=col-3.5f
        for(step in 0 until weeks*4) {
            val y=-weeks/2f+step*.25f
            plane(ground,x-.011f,y+.04f,x+.011f,y+.15f,.005f,Color(0xFFE9EEE6))
        }
    }
    for(row in 0..weeks) {
        val y=row-weeks/2f
        for(step in 0 until 28) {
            val x=-3.5f+step*.25f
            plane(ground,x+.04f,y-.011f,x+.15f,y+.011f,.005f,Color(0xFFE9EEE6))
        }
    }
    val offset=month.atDay(1).dayOfWeek.value-1
    repeat(weeks*7) { slot ->
        val day=(slot-offset+1).takeIf { it in 1..month.lengthOfMonth() }
        val x=slot%7-3f;val y=slot/7-(weeks-1)/2f
        plane(ground,x-.43f,y-.43f,x+.43f,y+.43f,.015f,stone,day)
        plane(ground,x-.38f,y-.38f,x+.38f,y+.37f,.02f,
            if(day==today) Color(0xFFE4D4A9) else Color(0xFFD8E3CC),day)
        if(day==null) return@repeat
        val floors=days[day].orEmpty()
        if(floors.isEmpty()) return@repeat
        val totalHeight = CityCalendarModel.height(CityCalendarModel.total(floors), maximumDailyCents)
        if (totalHeight <= 0f) return@repeat
        val style = CityCalendarModel.style(day, totalHeight)
        val cx = x; val cy = y - .08f
        val profile = buildingProfile(style)
        val sides = when (style) {
            CityBuildingStyle.HOUSE, CityBuildingStyle.PAVILION, CityBuildingStyle.TERRACE -> 4
            CityBuildingStyle.PAGODA -> 6
            else -> 12
        }
        // Intersect the entire silhouette (including roofs and spires) with monetary layers.
        // Every category occupies exactly its amount's vertical interval, regardless of shape.
        CityCalendarModel.layers(floors, maximumDailyCents).forEach { layer ->
            if (layer.top <= layer.bottom) return@forEach
            val color = ledgerCategoryColor(layer.floor.category)
            profile.zipWithNext().forEach { (a, b) ->
                val lower = maxOf(layer.bottom, a.first * totalHeight)
                val upper = minOf(layer.top, b.first * totalHeight)
                if (upper > lower) {
                    fun radius(z: Float): Float {
                        val fraction = (z / totalHeight - a.first) / (b.first - a.first)
                        return a.second + (b.second - a.second) * fraction.coerceIn(0f, 1f)
                    }
                    prism(buildings, cx, cy, CityCalendarModel.GROUND_Z + lower,
                        CityCalendarModel.GROUND_Z + upper, radius(lower), radius(upper), sides, color, day)
                }
            }
        }
    }
    return CityScene(ground,buildings)
}

/** Normalized height/radius pairs; all ornaments are contained in 0..1. */
private fun buildingProfile(style: CityBuildingStyle): List<Pair<Float, Float>> = when (style) {
    CityBuildingStyle.TREE -> listOf(0f to .022f, .1f to .022f, .1f to .16f,
        .55f to .07f, .55f to .12f, 1f to 0f)
    CityBuildingStyle.HOUSE -> listOf(0f to .30f, .70f to .30f, .70f to .36f, 1f to 0f)
    CityBuildingStyle.PAVILION -> listOf(0f to .28f, .65f to .28f, .65f to .36f,
        .83f to .15f, .83f to .23f, 1f to 0f)
    CityBuildingStyle.TERRACE -> listOf(0f to .34f, .45f to .34f, .45f to .27f,
        .78f to .27f, .78f to .19f, 1f to .19f)
    CityBuildingStyle.DOME -> listOf(0f to .25f, .72f to .25f) + (0..8).map { ring ->
        val t = ring / 8f
        (.72f + .28f * t) to (.25f * sqrt(1f - t * t))
    }
    CityBuildingStyle.PAGODA -> buildList {
        for (tier in 0..4) {
            val t = tier * .17f
            val radius = .28f - tier * .025f
            add(t to radius * .75f)
            add(t + .13f to radius * .75f)
            add(t + .13f to radius)
            add(t + .17f to radius * .65f)
        }
        add(.85f to .17f); add(.95f to .02f); add(1f to 0f)
    }
    CityBuildingStyle.PEARL -> (0..40).map { ring ->
        val t = ring / 40f
        val radius = if (t <= .88f) .055f + .21f * max(
            exp(-((t - .28f) / .15f).pow(2)), exp(-((t - .72f) / .11f).pow(2)))
        else .055f * (1f - t) / .12f
        t to radius
    }
}
