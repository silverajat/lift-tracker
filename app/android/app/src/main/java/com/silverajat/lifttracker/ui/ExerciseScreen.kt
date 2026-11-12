package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.data.Metric
import com.silverajat.lifttracker.data.Point
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Stats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val shortDate = SimpleDateFormat("d MMM", Locale.getDefault())
private val longDate = SimpleDateFormat("EEE d MMM yyyy, H:mm", Locale.getDefault())

/** Hevy-style exercise page: Summary (chart + records) and History. */
@Composable
fun ExerciseScreen(vm: AppViewModel, exerciseId: Int) {
    val ex = remember(exerciseId) { Routines.byId(exerciseId) }
    val history by vm.history.collectAsStateWithLifecycle()
    val sessions = remember(history, exerciseId) { vm.sessionsWith(exerciseId) }
    var tab by remember { mutableStateOf(0) }
    var metric by remember { mutableStateOf(Metric.HEAVIEST) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = ex.name, subtitle = null, onBack = { vm.back() })
        // Tabs
        Row(Modifier.fillMaxWidth()) {
            listOf("Summary", "History").forEachIndexed { i, t ->
                Column(
                    Modifier.weight(1f).clickable { tab = i }.padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(t, style = MaterialTheme.typography.titleMedium, color = if (tab == i) C.Accent else C.Muted)
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) C.Accent else C.Line))
                }
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tab == 0) {
                Column {
                    Text(ex.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${ex.day.title} day" + if (ex.detail.isNotEmpty()) " · ${ex.detail}" else "",
                        style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                    )
                    if (ex.note.isNotEmpty()) {
                        Text(ex.note, style = MaterialTheme.typography.bodyMedium, color = C.Warm,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
                val pts = if (metric == Metric.ROM) Stats.romSeries(ex, sessions) else Stats.series(metric, ex, sessions)
                val lastPt = pts.lastOrNull() ?: com.silverajat.lifttracker.data.Point("", null, 0.0)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (pts.isEmpty()) "No pod data yet" else "${Stats.fmt(lastPt.value)} ${metric.unit}",
                        style = MaterialTheme.typography.titleLarge.merge(Tnum),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        lastPt.timeMs?.let { shortDate.format(Date(it)) } ?: if (pts.isEmpty()) "" else "before the app",
                        style = MaterialTheme.typography.bodyMedium, color = C.Accent,
                    )
                }
                LineChart(pts, Modifier.fillMaxWidth().height(200.dp))
                if (pts.size < 3) {
                    Text(
                        if (metric == Metric.ROM) "Full ROM in degrees per session, measured by the pod (rotation lifts). Fills in once you log sets with it."
                        else "The line fills in as you log sessions. The first point is what you did before the app.",
                        style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                    )
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Metric.entries.forEach { m ->
                        val on = m == metric
                        Text(
                            m.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) C.OnAccent else C.Text,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (on) C.Accent else C.Raised)
                                .clickable { metric = m }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Personal records")
                        vm.calibration(ex.id)?.let { (deg, at) ->
                            Row {
                                Text("Full ROM (calibrated)", style = MaterialTheme.typography.bodyLarge, color = C.Muted,
                                    modifier = Modifier.weight(1f))
                                Text("${deg.toInt()}° · ${shortDate.format(Date(at))}",
                                    style = MaterialTheme.typography.titleMedium.merge(Tnum), color = C.Pod)
                            }
                        }
                        Stats.records(ex, sessions).forEach { r ->
                            Row {
                                Text(r.label, style = MaterialTheme.typography.bodyLarge, color = C.Muted,
                                    modifier = Modifier.weight(1f))
                                Text(r.value, style = MaterialTheme.typography.titleMedium.merge(Tnum))
                            }
                        }
                    }
                }
            } else {
                sessions.reversed().forEach { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(longDate.format(Date(s.startMs)), style = MaterialTheme.typography.titleMedium)
                            SetLines(s.sets.filter { it.exerciseId == ex.id }.sortedBy { it.setNo }
                                .map { l ->
                                    val extra = listOfNotNull(
                                        l.romPct.takeIf { it.isNotEmpty() }?.let { "ROM ${it.average().toInt()}%" },
                                        l.quality?.let { "Q $it" },
                                    ).joinToString(" · ").ifEmpty { null }
                                    Triple(l.warmup, compact(l.weight, l.addons, l.reps, ex.bodyweight), extra)
                                })
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Before the app", style = MaterialTheme.typography.titleMedium)
                        SetLines(ex.last.map { Triple(it.warmup, compact(it.weight, it.addons, it.reps, ex.bodyweight), null) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SetLines(lines: List<Triple<Boolean, String, String?>>) {
    var n = 0
    lines.forEach { (warm, text, pod) ->
        if (!warm) n++
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (warm) "W" else "$n",
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                color = if (warm) C.Warm else C.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.width(28.dp),
            )
            Text(text,
                style = MaterialTheme.typography.bodyLarge.merge(Tnum), modifier = Modifier.weight(1f))
            pod?.let { Tag(it, color = C.Pod) }
        }
    }
}

/** Line chart: dots per session, 3 grid lines with values, first/last dates. */
@Composable
fun LineChart(points: List<Point>, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = C.Muted, fontSize = 11.sp)
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val left = 44.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val top = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val vMin = points.minOf { it.value }
        val vMax = points.maxOf { it.value }
        val pad = if (vMax == vMin) maxOf(1.0, vMax * 0.1) else (vMax - vMin) * 0.15
        val lo = (vMin - pad).coerceAtLeast(0.0)
        val hi = vMax + pad
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + i * (right - left) / (points.size - 1)

        for (k in 0..2) {
            val v = lo + (hi - lo) * k / 2
            val yy = y(v)
            drawLine(C.Line, Offset(left, yy), Offset(right, yy), strokeWidth = 1.dp.toPx())
            drawText(measurer, Stats.fmt(v), Offset(0f, yy - 8.dp.toPx()), labelStyle)
        }
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(x(i), y(p.value)) else path.lineTo(x(i), y(p.value))
        }
        drawPath(path, C.Accent, style = Stroke(width = 2.5.dp.toPx()))
        points.forEachIndexed { i, p ->
            drawCircle(C.Accent, radius = 4.dp.toPx(), center = Offset(x(i), y(p.value)))
        }
        fun label(p: Point) = p.timeMs?.let { shortDate.format(Date(it)) } ?: "before"
        drawText(measurer, label(points.first()), Offset(left - 10.dp.toPx(), bottom + 6.dp.toPx()), labelStyle)
        if (points.size > 1) {
            val t = label(points.last())
            val w = measurer.measure(t, labelStyle).size.width
            drawText(measurer, t, Offset(right - w, bottom + 6.dp.toPx()), labelStyle)
        }
    }
}
