package com.hulk.manualledger

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.*

internal data class ScenePoint(val x: Float, val y: Float, val z: Float = 0f)
internal data class ProjectedPoint(val x: Float, val y: Float, val depth: Float)
internal class CalendarProjection(val width: Float, val height: Float, yaw: Float, pitch: Float, zoom: Float) {
    private val yawRadians = Math.toRadians(yaw.toDouble())
    private val pitchRadians = Math.toRadians(pitch.toDouble())
    private val scale = min(width / 11.5f, height / 10f) * zoom
    fun project(p: ScenePoint): ProjectedPoint {
        val x = p.x * cos(yawRadians) - p.y * sin(yawRadians)
        val y = p.x * sin(yawRadians) + p.y * cos(yawRadians)
        val depth = y * cos(pitchRadians) + p.z * sin(pitchRadians)
        val perspective = 22.0 / (22.0 - depth)
        return ProjectedPoint((width / 2 + x * scale * perspective).toFloat(),
            (height * 0.57 + (y * sin(pitchRadians) - p.z * cos(pitchRadians)) * scale * perspective).toFloat(), depth.toFloat())
    }
    companion object {
        fun dayCenter(month: YearMonth, day: Int): ScenePoint {
            val offset = month.atDay(1).dayOfWeek.value - 1
            val rows = (offset + month.lengthOfMonth() + 6) / 7
            val slot = offset + day - 1
            return ScenePoint(slot % 7 - 3f, slot / 7 - (rows - 1) / 2f)
        }
        fun contains(polygon: List<ProjectedPoint>, x: Float, y: Float): Boolean {
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val a = polygon[i]; val b = polygon[j]
                if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
                j = i
            }
            return inside
        }
    }
}
private data class CalendarFace(val corners: List<ScenePoint>, val color: Color, val day: Int? = null,
                                val label: String? = null, val labelPosition: ScenePoint? = null)

@Composable
internal fun CalendarSandtable(month: YearMonth, segments: Map<Int, Map<String, Long>>, maximum: Long, onDay: (LocalDate) -> Unit) {
    var yaw by remember { mutableStateOf(-22f) }
    var pitch by remember { mutableStateOf(52f) }
    var zoom by remember { mutableStateOf(1f) }
    val hitFaces = remember { java.util.concurrent.atomic.AtomicReference<List<Pair<List<ProjectedPoint>, Int>>>(emptyList()) }
    val latestOnDay by rememberUpdatedState(onDay)
    val offset = month.atDay(1).dayOfWeek.value - 1
    val weeks = (offset + month.lengthOfMonth() + 6) / 7
    val faces = remember(month, segments, maximum) {
        buildList {
            fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, color: Color, day: Int?) {
                val a = ScenePoint(x0,y0,z0); val b = ScenePoint(x1,y0,z0); val c = ScenePoint(x1,y1,z0); val d = ScenePoint(x0,y1,z0)
                val e = a.copy(z=z1); val f = b.copy(z=z1); val g = c.copy(z=z1); val h = d.copy(z=z1)
                add(CalendarFace(listOf(a,b,f,e), lerp(color,Color.Black,0.15f),day))
                add(CalendarFace(listOf(b,c,g,f), lerp(color,Color.Black,0.23f),day))
                add(CalendarFace(listOf(c,d,h,g), color,day))
                add(CalendarFace(listOf(d,a,e,h), lerp(color,Color.White,0.08f),day))
                add(CalendarFace(listOf(e,f,g,h), lerp(color,Color.White,0.22f),day))
            }
            box(-3.58f,3.58f,-weeks/2f-.08f,weeks/2f+.08f,-.20f,-.035f,Color(0xFFBCC8DA),null)
            repeat(weeks*7) { slot ->
                val day = (slot-offset+1).takeIf { it in 1..month.lengthOfMonth() }
                val x = slot%7-3f; val y = slot/7-(weeks-1)/2f
                val color = when { day == null -> Color(0xFFE2E8F1)
                    month.atDay(day) == LocalDate.now() -> Color(0xFFFFDCA3)
                    slot%7 >= 5 -> Color(0xFFE4EDF9)
                    else -> Color(0xFFF9FBFF) }
                add(CalendarFace(listOf(ScenePoint(x-.48f,y-.48f),ScenePoint(x+.48f,y-.48f),ScenePoint(x+.48f,y+.48f),ScenePoint(x-.48f,y+.48f)),
                    color,day,day?.toString(),ScenePoint(x-.30f,y+.31f,.005f)))
                if (day != null) {
                    var z = .015f
                    segments[day].orEmpty().forEach { (name,cents) ->
                        val top = z + (cents.toDouble()/maximum*2.2).toFloat()
                        box(x-.16f,x+.27f,y-.29f,y+.14f,z,top,ledgerCategoryColor(name),day)
                        z=top
                    }
                }
            }
        }
    }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { yaw=-22f;pitch=52f;zoom=1f }, modifier=Modifier.testTag("reset-sandtable")) { Text("复位视角") }
        }
        Canvas(Modifier.fillMaxWidth().height(350.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF0F4FA),RoundedCornerShape(18.dp))
            .testTag("calendar-sandtable")
            .semantics {
                contentDescription = "$month 消费沙盘"
                stateDescription = "旋转 ${yaw.toInt()} 度，俯角 ${pitch.toInt()} 度"
                customActions = (1..month.lengthOfMonth()).map { day ->
                    CustomAccessibilityAction("${day}日 查看流水") { latestOnDay(month.atDay(day));true }
                }
            }
            .pointerInput(month) {
                detectTapGestures { p -> hitFaces.get().lastOrNull { CalendarProjection.contains(it.first,p.x,p.y) }
                    ?.second?.let { latestOnDay(month.atDay(it)) } }
            }
            .pointerInput(month) {
                detectTransformGestures { _,pan,scale,rotation ->
                    yaw = ((yaw + pan.x/size.width*180f + rotation) % 360f)
                    pitch = (pitch - pan.y/size.height*100f).coerceIn(18f,82f)
                    zoom = (zoom*scale).coerceIn(.65f,1.5f)
                }
            }) {
            val camera = CalendarProjection(size.width,size.height,yaw,pitch,zoom)
            drawOval(Color(0xFFD8E0EC),Offset(size.width*.1f,size.height*.62f),Size(size.width*.8f,size.height*.16f))
            fun projected(part: List<CalendarFace>) = part.map { face -> face to face.corners.map(camera::project) }
                .sortedBy { it.second.map { p -> p.depth }.average() }
            // Draw the foundation underneath all cells. Its large top face must not
            // participate in centroid sorting, which would cover the far half of the calendar.
            val sorted = projected(faces.take(5)) + projected(faces.drop(5))
            val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize=11.sp.toPx();color=Color(0xFF354461).toArgb();isFakeBoldText=true }
            sorted.forEach { (face,points) ->
                val path=Path().apply { moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close() }
                drawPath(path,face.color)
                if(face.label!=null) {
                    drawPath(path,Color.White.copy(alpha=.8f),style=Stroke(.6.dp.toPx()))
                    val p=camera.project(face.labelPosition!!)
                    drawContext.canvas.nativeCanvas.drawText(face.label,p.x,p.y,labels)
                }
            }
            labels.textSize=10.sp.toPx()
            listOf("一","二","三","四","五","六","日").forEachIndexed { i,text ->
                val p=camera.project(ScenePoint(i-3f,-weeks/2f-.30f))
                drawContext.canvas.nativeCanvas.drawText(text,p.x,p.y,labels)
            }
            // The same projected polygons power drawing and picking, including rotated columns.
            hitFaces.set(sorted.mapNotNull { (face,points) -> face.day?.let { points to it } })
        }
    }
}
