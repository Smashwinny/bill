package com.hulk.manualledger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.YearMonth
import kotlin.math.*

internal data class CityFace(val corners: List<ScenePoint>, val color: Color, val day: Int? = null,
                             val windows: List<List<ScenePoint>> = emptyList())
internal data class CityScene(val ground: List<CityFace>, val buildings: List<CityFace>)

/** Geometry stays in calendar coordinates: roads, gardens and landmarks rotate as one scene. */
internal fun buildCityScene(month: YearMonth, days: Map<Int, List<CityFloor>>, today: Int?): CityScene {
    val weeks = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
    val ground = mutableListOf<CityFace>()
    val buildings = mutableListOf<CityFace>()
    val roof = Color(0xFF536E73)
    val stone = Color(0xFFE7DECC)
    val maxCount = days.values.maxOfOrNull { it.size } ?: 1
    val floorHeight = CityCalendarModel.floorHeight(maxCount)

    fun quad(target: MutableList<CityFace>, points: List<ScenePoint>, color: Color, day: Int? = null) {
        target += CityFace(points, color, day)
    }
    fun plane(target: MutableList<CityFace>, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, color: Color, day: Int? = null) =
        quad(target, listOf(ScenePoint(x0,y0,z),ScenePoint(x1,y0,z),ScenePoint(x1,y1,z),ScenePoint(x0,y1,z)), color, day)
    fun prism(target: MutableList<CityFace>, x: Float, y: Float, z0: Float, z1: Float,
              radius: Float, upperRadius: Float, sides: Int, color: Color, day: Int?, windows: Boolean = false) {
        val angle = if (sides == 4) PI / 4 else 0.0
        val bottom = (0 until sides).map { i -> ScenePoint(x + radius*cos(angle+i*2*PI/sides).toFloat(), y+radius*sin(angle+i*2*PI/sides).toFloat(),z0) }
        val top = (0 until sides).map { i -> ScenePoint(x+upperRadius*cos(angle+i*2*PI/sides).toFloat(),y+upperRadius*sin(angle+i*2*PI/sides).toFloat(),z1) }
        for(i in 0 until sides) {
            val j=(i+1)%sides
            val a=bottom[i];val b=bottom[j];val c=top[j];val d=top[i]
            fun point(u: Float,v: Float): ScenePoint = ScenePoint(
                (a.x+(b.x-a.x)*u)*(1-v)+(d.x+(c.x-d.x)*u)*v,
                (a.y+(b.y-a.y)*u)*(1-v)+(d.y+(c.y-d.y)*u)*v,z0+(z1-z0)*v)
            val panes = if (windows && z1-z0>.055f) (0..1).map { k ->
                val left=.18f+k*.40f
                listOf(point(left,.25f),point(left+.22f,.25f),point(left+.22f,.72f),point(left,.72f))
            } else emptyList()
            val light = ((cos(angle+(i+.5)*2*PI/sides)+1)*.5).toFloat()
            target += CityFace(listOf(a,b,c,d), lerp(lerp(color,Color.Black,.18f),lerp(color,Color.White,.12f),light),day,panes)
        }
        quad(target, top, lerp(color, Color.White, .18f), day)
    }
    fun block(target: MutableList<CityFace>, x: Float,y: Float,z0: Float,z1: Float,half: Float,color: Color,day: Int?) =
        prism(target,x,y,z0,z1,half*sqrt(2f),half*sqrt(2f),4,color,day)
    fun tree(x:Float,y:Float,day:Int?) {
        prism(buildings,x,y,.045f,.15f,.025f,.025f,5,Color(0xFF9B8060),day)
        prism(buildings,x,y,.12f,.29f,.105f,.025f,7,Color(0xFF719B79),day)
        prism(buildings,x,y,.24f,.37f,.075f,0f,7,Color(0xFF91B28B),day)
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
        if(day==null) { if(slot%2==0)tree(x,y,null);return@repeat }
        val floors=days[day].orEmpty()
        if(floors.isEmpty()) {
            tree(x-.12f,y-.08f,day)
            plane(ground,x+.08f,y-.3f,x+.16f,y+.16f,.025f,Color(0xFFF1ECDD),day)
            block(buildings,x+.24f,y+.02f,.025f,.075f,.065f,Color(0xFFA78C6A),day)
            return@repeat
        }
        val style=CityCalendarModel.style(day,floors.size)
        val cx=x;val cy=y-.08f
        val top=CityCalendarModel.top(floors.size,maxCount)
        block(buildings,cx,cy,.02f,.08f,.27f,stone,day)
        // One colored band per real transaction, in chronological order. Decorations are neutral.
        floors.forEachIndexed { index,floor ->
            val z0=.08f+index*floorHeight;val z1=z0+floorHeight
            val color=ledgerCategoryColor(floor.category)
            when(style) {
                CityBuildingStyle.PEARL -> {
                    fun radius(t:Float)=.075f+.19f*max(exp(-((t-.28f)/.15f).pow(2)),exp(-((t-.76f)/.11f).pow(2)))
                    prism(buildings,cx,cy,z0,z1,radius(index.toFloat()/floors.size),radius((index+1f)/floors.size),12,color,day,true)
                }
                CityBuildingStyle.PAGODA -> {
                    val r=.30f-.10f*index/floors.size
                    prism(buildings,cx,cy,z0,z1,r*.78f,r*.78f,6,color,day,true)
                    // Swept eaves, kept neutral so only the wall band encodes a purchase.
                    prism(buildings,cx,cy,z1-minOf(.035f,floorHeight*.16f),z1,r,r*.70f,6,roof,day)
                }
                CityBuildingStyle.DOME -> prism(buildings,cx,cy,z0,z1,.25f,.25f,12,color,day,true)
                CityBuildingStyle.TERRACE -> {
                    val half=.24f-.075f*(index/floors.size.toFloat())
                    prism(buildings,cx+(index/floors.size.toFloat())*.045f,cy,z0,z1,half*sqrt(2f),half*sqrt(2f),4,color,day,true)
                }
                else -> prism(buildings,cx,cy,z0,z1,.30f,.30f,4,color,day,true)
            }
            // Floor seams remain visible even for consecutive purchases in the same category.
            if(floorHeight>.025f && style!=CityBuildingStyle.PEARL && style!=CityBuildingStyle.PAGODA) {
                val radius=when(style) { CityBuildingStyle.DOME -> .254f;CityBuildingStyle.TERRACE -> (.24f-.075f*index/floors.size)*sqrt(2f)+.004f;else -> .305f }
                val sides=if(style==CityBuildingStyle.DOME)12 else 4
                prism(buildings,cx+(if(style==CityBuildingStyle.TERRACE)index/floors.size.toFloat()*.045f else 0f),cy,z1-.012f,z1,radius,radius,sides,stone,day)
            }
        }
        when(style) {
            CityBuildingStyle.PLATFORM -> {
                block(buildings,cx,cy,top,top+.035f,.23f,stone,day)
                plane(buildings,cx-.16f,cy-.16f,cx+.16f,cy+.16f,top+.036f,Color(0xFFA7BBA2),day)
            }
            CityBuildingStyle.HOUSE -> {
                val a=ScenePoint(cx-.25f,cy-.25f,top);val b=ScenePoint(cx+.25f,cy-.25f,top)
                val c=ScenePoint(cx+.25f,cy+.25f,top);val d=ScenePoint(cx-.25f,cy+.25f,top)
                val e=ScenePoint(cx,cy-.25f,top+.22f);val f=ScenePoint(cx,cy+.25f,top+.22f)
                quad(buildings,listOf(a,d,f,e),roof,day);quad(buildings,listOf(e,f,c,b),lerp(roof,Color.White,.18f),day)
                quad(buildings,listOf(a,b,e),stone,day);quad(buildings,listOf(d,c,f),stone,day)
            }
            CityBuildingStyle.PAVILION -> {
                prism(buildings,cx,cy,top,top+.11f,.36f,.16f,4,roof,day)
                prism(buildings,cx,cy,top+.10f,top+.21f,.22f,0f,4,roof,day)
            }
            CityBuildingStyle.TERRACE -> {
                block(buildings,cx+.045f,cy,top,top+.05f,.19f,roof,day)
                block(buildings,cx+.09f,cy-.04f,top+.05f,top+.15f,.055f,stone,day)
            }
            CityBuildingStyle.DOME -> {
                repeat(6) { ring ->
                    val t0=ring/6f;val t1=(ring+1)/6f
                    prism(buildings,cx,cy,top+t0*.23f,top+t1*.23f,.28f*sqrt(1-t0*t0),.28f*sqrt(1-t1*t1),12,roof,day)
                }
            }
            CityBuildingStyle.PAGODA -> {
                prism(buildings,cx,cy,top,top+.18f,.25f,.02f,6,roof,day)
                prism(buildings,cx,cy,top+.18f,top+.30f,.025f,0f,6,stone,day)
            }
            CityBuildingStyle.PEARL -> {
                prism(buildings,cx,cy,top,top+.12f,.09f,.025f,12,roof,day)
                prism(buildings,cx,cy,top+.12f,top+.34f,.025f,0f,6,stone,day)
            }
        }
    }
    return CityScene(ground,buildings)
}
