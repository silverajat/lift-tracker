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
    var metric by remember { mutableStateOf(Metric.ONE_RM) }

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
                ProgressStory(Stats.topSets(ex, sessions), ex.step, ex.bodyweight)
                LineChart(pts, Modifier.fillMaxWidth().height(200.dp))
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
            // just the result: how often it's been exactly right on this exercise
            Text(if (fit == null || fit.sets == 0) "No pod sets yet"
                else "Exact on ${fit.exactLearned} of ${fit.sets} sets", style = MaterialTheme.typography.bodyLarge)
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
        val few = points.size <= 5
        val notes = few && points.any { it.note != null }
        val bottom = size.height - (if (notes) 40 else 22).dp.toPx()
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
        points.forEachIndexed { i, p ->
            drawCircle(C.Accent, radius = 4.dp.toPx(), center = Offset(x(i), y(p.value)))
            // the best one gets a ring and says so; with few points every point shows its number
            if (i == best) drawCircle(Plate.Yellow, radius = 7.dp.toPx(), center = Offset(x(i), y(p.value)), style = Stroke(2.dp.toPx()))
            if (points.size <= 8 || i == best || i == points.lastIndex) {
                val t = Stats.fmt(p.value) + if (i == best && points.size > 1) " best" else ""
                val st = if (i == best) bestStyle else valueStyle
                val w = measurer.measure(t, st).size.width
                val tx = (x(i) - w / 2).coerceIn(left, size.width - w)
                drawText(measurer, t, Offset(tx, y(p.value) - 20.dp.toPx()), st)
            }
        }
        fun label(p: Point) = p.timeMs?.let { shortDate.format(Date(it)) } ?: "before"
        // dates: under every point when there's room, else first and last
        val show = if (few) points.indices.toList() else listOf(0, points.lastIndex)
        show.forEach { i ->
            val t = label(points[i])
            val w = measurer.measure(t, labelStyle).size.width
            val tx = (x(i) - w / 2).coerceIn(0f, size.width - w)
            drawText(measurer, t, Offset(tx, bottom + 6.dp.toPx()), labelStyle)
            // the set behind the point, so you can see whether it was weight or reps that moved
            if (notes) points[i].note?.let { n ->
                val nw = measurer.measure(n, valueStyle).size.width
                drawText(measurer, n, Offset((x(i) - nw / 2).coerceIn(0f, size.width - nw), bottom + 22.dp.toPx()), valueStyle)
            }
        }
    }
}


/**
 * Progress in a line or two, from each session's strongest set, judged with this lift's own reps-for-weight
 * trade-off (Strength): after going heavier, fewer reps is expected; the question is how many fewer.
 */
@Composable
private fun ProgressStory(t: List<com.silverajat.lifttracker.data.TopSet>, step: Double, bodyweight: Boolean) {
    if (t.size < 2) return
    val S = com.silverajat.lifttracker.data.Strength
    val first = t.first(); val last = t.last(); val prev = t[t.size - 2]
    val k = last.k
    val bestBefore = t.dropLast(1).maxOf { it.e1rm }
    val bestAt = t.indices.maxBy { t[it].e1rm }
    val sinceBest = t.lastIndex - bestAt
    fun pct(a: Double, b: Double) = if (b == 0.0) 0 else ((a - b) / b * 100).roundToInt()
    val heavier = last.load > prev.load + 1e-6
    val lighter = last.load < prev.load - 1e-6
    val sameKg = !heavier && !lighter
    // reps you'd expect at the new weight if you were exactly as strong as last time
    val expected = if (heavier) S.repsAt(prev.e1rm, last.load, k) else null
    val gap = expected?.let { last.reps - it }
    val newBest = last.e1rm > bestBefore + 1e-9
    val (head, color) = when {
        newBest && heavier -> "New best: heavier at ${last.text} (was ${prev.text})" to Plate.Green
        newBest && sameKg && last.reps > prev.reps -> "New best: +${last.reps - prev.reps} reps at ${Stats.fmt(last.load)} kg" to Plate.Green
        newBest -> "New best: ${last.text} (was ${prev.text})" to Plate.Green
        heavier && gap!! >= -0.75 -> "Heavier: ${prev.text} → ${last.text}, right on track" to Plate.Green
        heavier && gap!! >= -2.0 -> "Heavier: ${prev.text} → ${last.text} (about ${expected!!.roundToInt()} expected)" to C.Text
        heavier -> "Heavier: ${prev.text} → ${last.text}, ${(-gap!!).roundToInt()} reps under what ${Stats.fmt(last.load)} kg should give" to Plate.Yellow
        sameKg && last.reps > prev.reps -> "+${last.reps - prev.reps} reps at ${Stats.fmt(last.load)} kg" to Plate.Green
        lighter -> "Lighter: ${prev.text} → ${last.text}" to C.Text
        pct(last.e1rm, prev.e1rm) <= -3 -> "Down from last time: ${prev.text} → ${last.text}" to Plate.Yellow
        sinceBest >= 3 -> "No new best for $sinceBest sessions" to Plate.Yellow
        else -> "Holding at ${last.text}" to C.Text
    }
    // two sessions in a row clearly below the one before, at the same or a lower weight
    val twoOff = t.size >= 3 && !heavier && listOf(t[t.size - 3] to prev, prev to last).all { (a, b) -> b.load <= a.load + 1e-6 && pct(b.e1rm, a.e1rm) <= -3 }
    val tip = when {
        newBest -> null
        heavier && gap!! < -2.0 -> "A big step for this lift: stay here and build reps before the next one."
        twoOff -> "Two off sessions in a row: go a bit lighter for a week."
        sinceBest >= 3 && !heavier -> "Stalled: add a rep to each set before adding weight, or drop 5–10% and build back up."
        else -> null
    }
    val start = first.timeMs?.let { shortDate.format(Date(it)) } ?: "before the app"
    val cost = if (bodyweight || step <= 0) null else S.stepCost(last.load, last.reps, step, k)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(head, style = MaterialTheme.typography.titleMedium, color = color)
        Text("Strength since $start: ${Stats.fmt(first.e1rm)} → ${Stats.fmt(last.e1rm)} kg (%+d%%), ${first.text} → ${last.text}".format(pct(last.e1rm, first.e1rm)),
            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        cost?.takeIf { it > 0 }?.let { Text("Here +${Stats.fmt(step)} kg ≈ ${it} rep${if (it == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, color = C.Muted) }
        tip?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}
