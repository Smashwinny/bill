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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
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
            (height * 0.52 + (y * sin(pitchRadians) - p.z * cos(pitchRadians)) * scale * perspective).toFloat(), depth.toFloat())
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
@Composable
internal fun CalendarSandtable(month: YearMonth, days: Map<Int, List<CityFloor>>, onDay: (LocalDate) -> Unit, selectedDay: Int? = null) {
    var yaw by remember { mutableStateOf(-22f) }
    var pitch by remember { mutableStateOf(52f) }
    var zoom by remember { mutableStateOf(1f) }
    val hitFaces = remember { java.util.concurrent.atomic.AtomicReference<List<Pair<List<ProjectedPoint>, Int>>>(emptyList()) }
    val latestOnDay by rememberUpdatedState(onDay)
    val today = LocalDate.now().takeIf { YearMonth.from(it) == month }?.dayOfMonth
    val scene = remember(month, days, today, selectedDay) { buildCityScene(month, days, selectedDay ?: today) }
    val maxCount = days.values.maxOfOrNull { it.size } ?: 1
    val primary = MaterialTheme.colorScheme.primary
    val weeks = (month.atDay(1).dayOfWeek.value - 1 + month.lengthOfMonth() + 6) / 7
    Box {
        Canvas(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFFF5F7F1), Color(0xFFE1E9DE))))
            .testTag("calendar-sandtable")
            .semantics {
                contentDescription = "$month 消费小城，一天一栋，彩色一层一笔支出"
                stateDescription = "旋转 ${yaw.toInt()} 度，俯角 ${pitch.toInt()} 度"
                customActions = (1..month.lengthOfMonth()).map { day ->
                    val records = days[day].orEmpty()
                    CustomAccessibilityAction("${day}日，${records.size} 笔，支出 ${AmountExpression.format(records.sumOf { it.amountCents })} 元，查看当天分类") {
                        latestOnDay(month.atDay(day)); true
                    }
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
                    zoom = (zoom*scale).coerceIn(.65f,1.8f)
                }
            }) {
            val camera = CalendarProjection(size.width,size.height,yaw,pitch,zoom)
            drawOval(Brush.radialGradient(listOf(Color(0xFF647D69).copy(alpha=.22f),Color.Transparent),
                center=Offset(size.width*.5f,size.height*.70f),radius=size.width*.43f),
                Offset(size.width*.05f,size.height*.59f),Size(size.width*.9f,size.height*.22f))
            fun path(points: List<ProjectedPoint>) = Path().apply {
                moveTo(points[0].x,points[0].y);points.drop(1).forEach { lineTo(it.x,it.y) };close()
            }
            fun project(face: CityFace): Triple<CityFace, List<ProjectedPoint>, Float> {
                val points = face.corners.map(camera::project)
                return Triple(face, points, points.sumOf { it.depth.toDouble() }.toFloat() / points.size)
            }
            val ground = scene.ground.map(::project)
            val buildings = scene.buildings.map(::project).sortedBy { it.third }
            val painted = ground + buildings
            painted.forEach { (face,points) ->
                drawPath(path(points),face.color)

            }
            val hits = painted.mapNotNull { (face,points) -> face.day?.let { points to it } }.toMutableList()
            val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize=9.sp.toPx();color=Color(0xFF364D46).toArgb();isFakeBoldText=true;textAlign=Paint.Align.CENTER
            }
            // Screen-facing date plaques stay legible at any angle and use the same hit area.
            val occupiedLabels = mutableListOf<android.graphics.RectF>()
            val markers = (1..month.lengthOfMonth()).map { day ->
                day to camera.project(CityCalendarModel.marker(month, day, days[day].orEmpty().size, maxCount))
            }.sortedByDescending { it.second.depth }
            markers.forEach { (day, p) ->
                val count = days[day].orEmpty().size
                val w=labels.measureText(day.toString())+5.dp.toPx();val h=12.dp.toPx()
                val rect=android.graphics.RectF(p.x-w/2,p.y-h/2,p.x+w/2,p.y+h/2)
                // Do not put a date badge on a nearer building belonging to a different day.
                val occluder = buildings.lastOrNull { (face, points, depth) ->
                    face.day != day && depth > p.depth && CalendarProjection.contains(points,p.x,p.y)
                }
                if (occluder != null) return@forEach
                val clearance = android.graphics.RectF(rect).apply { inset(-1.dp.toPx(), -1.dp.toPx()) }
                if (occupiedLabels.any { android.graphics.RectF.intersects(it, clearance) }) return@forEach
                occupiedLabels += clearance
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=(if(day==(selectedDay ?: today)) primary else Color(0xFFFFFEF7).copy(alpha=.94f)).toArgb() }
                if (count > 0) drawLine(Color(0xFFFFFEF7),Offset(p.x,rect.bottom),Offset(p.x,rect.bottom+3.dp.toPx()),strokeWidth=1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawRoundRect(rect,3.dp.toPx(),3.dp.toPx(),ink)
                labels.color=(if(day==(selectedDay ?: today)) Color.White else Color(0xFF364D46)).toArgb()
                drawContext.canvas.nativeCanvas.drawText(day.toString(),p.x,p.y-(labels.ascent()+labels.descent())/2,labels)
                hits += listOf(ProjectedPoint(rect.left,rect.top,p.depth),ProjectedPoint(rect.right,rect.top,p.depth),
                    ProjectedPoint(rect.right,rect.bottom,p.depth),ProjectedPoint(rect.left,rect.bottom,p.depth)) to day
            }
            labels.color=Color(0xFF52685F).toArgb()
            listOf("一","二","三","四","五","六","日").forEachIndexed { i,text ->
                val p=camera.project(ScenePoint(i-3f,-weeks/2f-.30f))
                drawContext.canvas.nativeCanvas.drawText(text,p.x,p.y,labels)
            }
            hitFaces.set(hits)
        }
        TextButton(onClick = { yaw=-22f;pitch=52f;zoom=1f },
            modifier = Modifier.align(Alignment.TopEnd).testTag("reset-sandtable")) { Text("复位视角") }
    }
}
