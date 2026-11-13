package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.WeightEntry
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val wFmt = DateTimeFormatter.ofPattern("d MMM")

/** Weight analysis worked out from the weigh-ins (pure, so it can be tested and previewed). */
object WeightStats {
    data class Point(val date: LocalDate, val scale: Double?, val trend: Double)

    /**
     * Smoothed trend: each day moves 10% of the way towards the scale
     * (the Hacker's Diet method). Days without a weigh-in carry the trend on.
     */
    fun trend(w: List<WeightEntry>): List<Point> {
        val byDay = w.associate { it.date to it.kg }
        val first = w.minOfOrNull { it.date } ?: return emptyList()
        val last = maxOf(w.maxOf { it.date }, LocalDate.now())
        var t = byDay.getValue(first)
        val out = ArrayList<Point>()
        var d = first
        while (!d.isAfter(last)) {
            byDay[d]?.let { t += 0.1 * (it - t) }
            out += Point(d, byDay[d], t)
            d = d.plusDays(1)
        }
        return out
    }

    /** kg per week from a straight line through the weigh-ins in the last [days]. */
    fun rate(w: List<WeightEntry>, days: Long, today: LocalDate = LocalDate.now()): Double? {
        val pts = w.filter { it.date > today.minusDays(days) }
        if (pts.size < 4) return null
        val x0 = pts.minOf { it.date }
        val xs = pts.map { ChronoUnit.DAYS.between(x0, it.date).toDouble() }
        if (xs.max() - xs.min() < 6) return null
        val mx = xs.average(); val my = pts.map { it.kg }.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        return xs.indices.sumOf { (xs[it] - mx) * (pts[it].kg - my) } / sxx * 7
    }

    /** How much a single weigh-in normally bounces around the trend (water, food, salt). */
    fun swing(p: List<Point>): Double? {
        val r = p.mapNotNull { it.scale?.let { s -> s - it.trend } }
        if (r.size < 7) return null
        return sqrt(r.sumOf { it * it } / r.size)
    }

    /** Average difference from the trend on each weekday (needs 3+ weeks to mean anything). */
    fun weekdays(p: List<Point>): Map<DayOfWeek, Double> =
        p.filter { it.scale != null }.groupBy { it.date.dayOfWeek }
            .filterValues { it.size >= 3 }.mapValues { (_, v) -> v.map { it.scale!! - it.trend }.average() }
}

@Composable
fun WeightDetail(weights: List<WeightEntry>, goalKgPerWeek: Double, onBack: () -> Unit, onLog: () -> Unit) {
    var range by rememberSaveable { mutableIntStateOf(60) }
    val today = LocalDate.now()
    val all = WeightStats.trend(weights.sortedBy { it.date })
    val shown = if (range == 0) all else all.filter { it.date > today.minusDays(range.toLong()) }
    val now = all.lastOrNull()
    val r28 = WeightStats.rate(weights, 28, today)
    val r14 = WeightStats.rate(weights, 14, today)
    val swing = WeightStats.swing(all.takeLast(60))
    val weeks = MealStore.weekAverages(weights, today)

    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier) {
        TopBar("Body weight", now?.let { "trend %.1f kg".format(it.trend) }, onBack) {
            androidx.compose.material3.TextButton(onClick = onLog) { Text("Log weight", color = C.Accent) }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (now == null) {
                item { Text("Weigh in each morning (after the toilet, before food) and this fills in.", color = C.Muted) }
                return@LazyColumn
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("%.1f".format(now.trend), style = MaterialTheme.typography.displayLarge.merge(Tnum))
                        Text(" kg", style = MaterialTheme.typography.displaySmall, color = C.Muted, modifier = Modifier.padding(bottom = 14.dp))
                    }
                    Text("Your trend weight: the weigh-ins smoothed, so one salty dinner doesn't move it." +
                        (weights.lastOrNull()?.let { " Last weigh-in %.1f kg on %s.".format(it.kg, it.date.format(wFmt)) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                }
            }
            item {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                        listOf(30 to "30 days", 60 to "60 days", 90 to "90 days", 0 to "All").forEach { (d, l) ->
                            Tag(l, color = if (d == range) C.Accent else C.Muted, modifier = Modifier.clickable { range = d })
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            WeightGraph(shown)
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                                Text(shown.firstOrNull()?.date?.format(wFmt) ?: "", style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.weight(1f))
                                Text("dots: weigh-ins, line: trend", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                            }
                        }
                    }
                }
            }
            item {
                val goalWord = when { goalKgPerWeek > 0 -> "gain %.2f".format(goalKgPerWeek); goalKgPerWeek < 0 -> "lose %.2f".format(-goalKgPerWeek); else -> "hold steady" }
                Column {
                    Text("Rate", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            RateRow("Last 2 weeks", r14, goalKgPerWeek)
                            RateRow("Last 4 weeks", r28, goalKgPerWeek)
                            HorizontalDivider(color = C.Line)
                            Row {
                                Column(Modifier.weight(1f)) {
                                    Text("Your goal", style = MaterialTheme.typography.titleSmall)
                                    Text("set on the Net page", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                                }
                                Text("$goalWord kg/week", style = MaterialTheme.typography.titleMedium)
                            }
                            (r28 ?: r14)?.let { r ->
                                Text("At this rate: %.1f kg in 4 weeks, %.1f kg in 8 weeks.".format(now.trend + r * 4, now.trend + r * 8),
                                    style = MaterialTheme.typography.bodyLarge)
                                val off = r - goalKgPerWeek
                                Text(when {
                                    abs(off) <= 0.1 -> "Right on your goal. Keep doing what you're doing."
                                    off > 0 -> "%.2f kg a week faster than your goal: about %d kcal a day less, or more movement, would line it up.".format(off, (off * 7700 / 7).roundToInt())
                                    else -> "%.2f kg a week behind your goal: about %d kcal a day more would line it up.".format(-off, (-off * 7700 / 7).roundToInt())
                                }, style = MaterialTheme.typography.bodyMedium, color = if (abs(off) <= 0.1) Recover else Yellow)
                            } ?: Text("Needs a week or two of weigh-ins for a rate.", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        }
                    }
                }
            }
            if (weeks.isNotEmpty()) item {
                Column {
                    Text("Week by week", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            weeks.take(8).forEachIndexed { i, w ->
                                if (i > 0) HorizontalDivider(color = C.Line)
                                val prev = weeks.getOrNull(i + 1)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Week of ${w.weekStart.format(wFmt)}", style = MaterialTheme.typography.titleSmall)
                                        Text("${w.days} weigh-ins", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                                    }
                                    Text("%.2f kg".format(w.avgKg), style = MaterialTheme.typography.titleMedium.merge(Tnum))
                                    Text(prev?.let { "  %+.2f".format(w.avgKg - it.avgKg) } ?: "", style = MaterialTheme.typography.titleSmall.merge(Tnum),
                                        color = C.Muted, modifier = Modifier.width(64.dp))
                                }
                            }
                            Text("Monday to Sunday averages; only finished weeks.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                    }
                }
            }
            item {
                val last30 = all.filter { it.date > today.minusDays(30) }
                val logged14 = all.filter { it.date > today.minusDays(14) }.count { it.scale != null }
                val scales = last30.mapNotNull { it.scale }
                Column {
                    Text("Patterns", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            swing?.let {
                                StatLine("Daily swing", "±%.1f kg".format(it), "how much one weigh-in bounces around the trend. Changes smaller than this mean nothing.")
                            }
                            if (scales.isNotEmpty()) StatLine("Last 30 days", "%.1f to %.1f".format(scales.min(), scales.max()), "lowest and highest weigh-in")
                            StatLine("Weigh-ins", "$logged14 of 14", "days logged in the last two weeks", if (logged14 >= 10) Recover else Yellow)
                            val wd = WeightStats.weekdays(all.takeLast(84))
                            if (wd.size >= 5) {
                                HorizontalDivider(color = C.Line)
                                Text("By day of the week", style = MaterialTheme.typography.titleSmall)
                                WeekdayBars(wd)
                                val hi = wd.maxBy { it.value }
                                Text("You weigh most on ${hi.key.getDisplayName(TextStyle.FULL, Locale.getDefault())}s (%+.1f kg vs trend): usually the day after bigger meals or more salt.".format(hi.value),
                                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateRow(label: String, rate: Double?, goal: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(rate?.let { "%+.2f kg/week".format(it) } ?: "–", style = MaterialTheme.typography.titleMedium.merge(Tnum),
            color = if (rate == null) C.Muted else if (abs(rate - goal) <= 0.1) Recover else Yellow)
    }
}

@Composable
private fun StatLine(label: String, value: String, sub: String, color: androidx.compose.ui.graphics.Color = C.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        Text(value, style = MaterialTheme.typography.headlineSmall.merge(Tnum), color = color)
    }
}

@Composable
private fun WeightGraph(p: List<WeightStats.Point>) {
    if (p.size < 2) { Text("A couple more weigh-ins and the graph appears.", color = C.Muted); return }
    val vals = p.mapNotNull { it.scale } + p.map { it.trend }
    val lo = vals.min() - 0.3; val hi = vals.max() + 0.3
    Column {
        Row {
            Column(Modifier.height(180.dp).width(36.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text("%.1f".format(hi), style = MaterialTheme.typography.labelSmall, color = C.Muted)
                Text("%.1f".format((hi + lo) / 2), style = MaterialTheme.typography.labelSmall, color = C.Muted)
                Text("%.1f".format(lo), style = MaterialTheme.typography.labelSmall, color = C.Muted)
            }
            Canvas(Modifier.weight(1f).height(180.dp)) {
                val n = p.size
                fun x(i: Int) = i * size.width / (n - 1).coerceAtLeast(1)
                fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
                for (k in 0..2) { val yy = size.height * k / 2f; drawLine(C.Line, Offset(0f, yy), Offset(size.width, yy), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                p.forEachIndexed { i, pt -> pt.scale?.let { drawCircle(C.Muted, 3.dp.toPx(), Offset(x(i), y(it))) } }
                for (i in 1 until n) drawLine(Plate.Blue, Offset(x(i - 1), y(p[i - 1].trend)), Offset(x(i), y(p[i].trend)), 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun WeekdayBars(wd: Map<DayOfWeek, Double>) {
    val max = wd.values.maxOf { abs(it) }.coerceAtLeast(0.1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { d ->
            val v = wd[d]
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.fillMaxWidth().height(60.dp)) {
                    val mid = size.height / 2
                    drawLine(C.Line, Offset(0f, mid), Offset(size.width, mid), 1f)
                    if (v != null) {
                        val h = (abs(v) / max * mid).toFloat().coerceAtLeast(2f)
                        drawRect(if (v > 0) Plate.Yellow else Plate.Green, Offset(size.width * 0.2f, if (v > 0) mid - h else mid), Size(size.width * 0.6f, h))
                    }
                }
                Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2), style = MaterialTheme.typography.labelSmall, color = C.Muted)
            }
        }
    }
}
