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
import com.silverajat.lifttracker.reps.RepModel
import com.silverajat.lifttracker.reps.unitLabel
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import java.util.Locale

private val shortDate = SimpleDateFormat("d MMM", Locale.getDefault())
private val longDate = SimpleDateFormat("EEE d MMM yyyy, H:mm", Locale.getDefault())

/** Hevy-style exercise page: Summary (chart + records) and History. */
@Composable
fun ExerciseScreen(vm: AppViewModel, exerciseId: Int) {
    val ex = remember(exerciseId) { Routines.byId(exerciseId) }
    val history by vm.history.collectAsStateWithLifecycle()
    val sessions = remember(history, exerciseId) { vm.sessionsWith(exerciseId) }
    val model by vm.model.collectAsStateWithLifecycle()
    val training by vm.training.collectAsStateWithLifecycle()
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
                    NoteLine(vm, ex.id, Modifier.padding(top = 8.dp))
                }
                val pts = if (metric == Metric.ROM) Stats.romSeries(ex, sessions, AppViewModel.RepEngineVersion)
                    else Stats.series(metric, ex, sessions)
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
                ProgressStory(pts, metric)
                LineChart(pts, Modifier.fillMaxWidth().height(200.dp))
                if (pts.size < 3) {
                    Text(
                        if (metric == Metric.ROM) "Average ROM of your working sets each session, as % of your calibration set (100%). Fills in once you log sets with the pod."
                        else "Working sets only (no warm-ups). The line fills in as you log sessions; the first point is what you did before the app.",
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
                Text(metricHelp(metric), style = MaterialTheme.typography.bodySmall, color = C.Muted)
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Personal records")
                        vm.calibration(ex.id)?.let { c ->
                            Row {
                                Text("Full ROM (calibration = 100%)", style = MaterialTheme.typography.bodyLarge, color = C.Muted,
                                    modifier = Modifier.weight(1f))
                                Text("${c.rom.toInt()}${unitLabel(c.unit)} · ${shortDate.format(Date(c.atMs))}",
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
                CounterCard(model.perExercise[ex.id], training, Routines.isOneArm(ex))
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
                                    Triple(l.warmup, compact(l.weight, l.addons, l.reps, ex.bodyweight) +
                                        (if (Routines.isOneArm(ex)) " /arm" else ""), extra)
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

/** How well the rep counter does on this exercise, and what it has learned. */
@Composable
private fun CounterCard(fit: RepModel.Fit?, training: Boolean, oneArm: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Rep counter")
            val line = when {
                fit == null || fit.sets == 0 ->
                    "No pod sets yet. Fix the count after each set: every correction is training data."
                fit.sets < RepModel.MIN_SETS ->
                    "Learning from ${fit.sets} corrected set${if (fit.sets > 1) "s" else ""}: " +
                        "this exercise gets its own settings from ${RepModel.MIN_SETS}. " +
                        "Exact on ${fit.exactLearned} of ${fit.sets}."
                fit.params.changes() == 0 ->
                    "Exact on ${fit.exactLearned} of ${fit.sets} sets with the standard settings " +
                        "(${fit.missLearned} rep${if (fit.missLearned != 1) "s" else ""} off in total). Nothing to fix yet."
                else ->
                    "Learned from your ${fit.sets} corrected sets: exact on ${fit.exactLearned} of them " +
                        "(was ${fit.exactDefault} before learning, ${fit.missDefault} → ${fit.missLearned} reps off)."
            }
            Text(line, style = MaterialTheme.typography.bodyLarge)
            if (fit != null && fit.params.changes() > 0 && fit.sets >= RepModel.MIN_SETS) {
                val p = fit.params
                Text(
                    "Settings: counts the ${signalName(p.signal)} · threshold ${(p.h * 100).toInt()}% · " +
                        "ignores blips under ${(p.gap * 100).toInt()}% of a rep",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                )
            }
            if (training) Text("Updating…", style = MaterialTheme.typography.labelMedium, color = C.Pod)
        }
    }
}

private fun signalName(s: String) = when (s) {
    "turn" -> "forearm's turning speed"
    "tilt" -> "forearm's tilt"
    "lift" -> "wrist's up/down speed"
    "push" -> "wrist's sideways speed"
    else -> "best signal for each set"
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
    val valueStyle = TextStyle(color = C.Text, fontSize = 11.sp)
    val bestStyle = TextStyle(color = Plate.Yellow, fontSize = 11.sp)
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val left = 44.dp.toPx()
        val bottom = size.height - 22.dp.toPx()
        val top = 20.dp.toPx()
        val right = size.width - 14.dp.toPx()
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
        val best = points.indices.maxBy { points[it].value }
        val few = points.size <= 8
        points.forEachIndexed { i, p ->
            drawCircle(C.Accent, radius = 4.dp.toPx(), center = Offset(x(i), y(p.value)))
            // the best one gets a ring and says so; with few points every point shows its number
            if (i == best) drawCircle(Plate.Yellow, radius = 7.dp.toPx(), center = Offset(x(i), y(p.value)), style = Stroke(2.dp.toPx()))
            if (few || i == best || i == points.lastIndex) {
                val t = Stats.fmt(p.value) + if (i == best && points.size > 1) " best" else ""
                val st = if (i == best) bestStyle else valueStyle
                val w = measurer.measure(t, st).size.width
                val tx = (x(i) - w / 2).coerceIn(left, size.width - w)
                drawText(measurer, t, Offset(tx, y(p.value) - 20.dp.toPx()), st)
            }
        }
        fun label(p: Point) = p.timeMs?.let { shortDate.format(Date(it)) } ?: "before"
        // dates: under every point when there's room, else first and last
        val show = if (points.size <= 5) points.indices.toList() else listOf(0, points.lastIndex)
        show.forEach { i ->
            val t = label(points[i])
            val w = measurer.measure(t, labelStyle).size.width
            val tx = (x(i) - w / 2).coerceIn(0f, size.width - w)
            drawText(measurer, t, Offset(tx, bottom + 6.dp.toPx()), labelStyle)
        }
    }
}

private fun metricHelp(m: Metric) = when (m) {
    Metric.HEAVIEST -> "Heaviest weight: the most you put on the bar for a working set, whatever the reps."
    Metric.ONE_RM -> "Est. 1-rep max: what your best set says you could lift once. It counts weight and reps together, so it's the fairest single number for getting stronger."
    Metric.BEST_SET -> "Best set volume: weight × reps of your biggest single set."
    Metric.VOLUME -> "Session volume: weight × reps added up over all working sets. More sets or more reps push it up too."
    Metric.REPS -> "Total reps across your working sets."
    Metric.ROM -> "Range of motion: how far each rep went, as a % of your calibration set. Dropping means shorter reps."
}

/** The chart in a sentence or two: where you are, which way it's going, and what to do about it. */
@Composable
private fun ProgressStory(pts: List<Point>, metric: Metric) {
    if (pts.size < 2) return
    val unit = if (metric.unit == "kg") " kg" else if (metric == Metric.ROM) "%" else " ${metric.unit}"
    val first = pts.first(); val last = pts.last(); val prev = pts[pts.size - 2]
    val bestBefore = pts.dropLast(1).maxOf { it.value }
    val bestAt = pts.indices.maxBy { pts[it].value }
    val sinceBest = pts.lastIndex - bestAt
    fun pct(a: Double, b: Double) = if (b == 0.0) 0 else ((a - b) / b * 100).roundToInt()
    val (head, color) = when {
        last.value > bestBefore + 1e-9 -> "New best last session: ${Stats.fmt(last.value)}$unit" to Plate.Green
        pct(last.value, prev.value) <= -3 -> "Down from last time: ${Stats.fmt(prev.value)} → ${Stats.fmt(last.value)}$unit" to Plate.Yellow
        sinceBest >= 3 -> "No new best for $sinceBest sessions" to Plate.Yellow
        pct(last.value, prev.value) >= 1 -> "Up from last time: ${Stats.fmt(prev.value)} → ${Stats.fmt(last.value)}$unit" to Plate.Green
        else -> "Holding steady at ${Stats.fmt(last.value)}$unit" to C.Text
    }
    val tip = when {
        last.value > bestBefore + 1e-9 -> "Keep the same weight next time and add a rep before going heavier."
        pct(last.value, prev.value) <= -3 -> "One off day is normal (sleep, food, a hard day before). Two in a row: take it a bit lighter for a week."
        sinceBest >= 3 -> "You've stalled: add a rep to each set before adding weight, or drop 5–10% and build back up."
        else -> null
    }
    val start = first.timeMs?.let { shortDate.format(Date(it)) } ?: "before the app"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(head, style = MaterialTheme.typography.titleMedium, color = color)
        Text("Since $start: ${Stats.fmt(first.value)} → ${Stats.fmt(last.value)}$unit (%+d%%) over ${pts.size} sessions".format(pct(last.value, first.value)),
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        tip?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
