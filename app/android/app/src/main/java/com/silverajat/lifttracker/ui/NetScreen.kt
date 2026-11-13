package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Energy
import com.silverajat.lifttracker.meals.MealEntry
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val shortDate = DateTimeFormatter.ofPattern("d MMM")

fun signedKcal(n: Int) = (if (n > 0) "+" else if (n < 0) "−" else "") + "%,d".format(abs(n))

/** Goals offered (kg/week) and their labels. */
val goals = listOf(-0.5 to "Lose 0.5", -0.25 to "Lose 0.25", 0.0 to "Maintain", 0.25 to "Gain 0.25", 0.5 to "Gain 0.5")

fun todayNet(e: Energy.Result, entries: List<MealEntry>, today: LocalDate, now: LocalTime) = Energy.today(e, entries, today, now)

/** Colour for a net number against the goal (±150 kcal counts as on target). */
fun netColor(net: Int, goal: Int): Color = when {
    abs(net - goal) <= 150 -> Recover
    abs(net - goal) <= 400 -> Yellow
    else -> RedZ
}

/** The Net view (top of the Food tab). */
fun LazyListScope.netItems(a: Analysis.Result?, todayEntries: List<MealEntry>, goalKg: Double, onWeight: () -> Unit = {}, onGoal: (Double) -> Unit) {
    val e = a?.energy
    if (e == null) {
        item { Text("Working it out…", color = C.Muted) }
        return
    }
    val today = LocalDate.now()
    val goal = e.netFor(goalKg)
    val t = todayNet(e, todayEntries, today, LocalTime.now())

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Net calories today")
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(signedKcal(t.endOfDay), style = MaterialTheme.typography.displayMedium.merge(Tnum), color = netColor(t.endOfDay, goal))
                    Text("  kcal", color = C.Muted, modifier = Modifier.padding(bottom = 10.dp))
                }
                Text(if (t.restOfPlan) "by tonight if you eat the rest of the plan (${signedKcal(t.soFar)} so far)"
                    else if (todayEntries.isEmpty()) "nothing logged yet: this is just what you've burned"
                    else "by the end of the day (${signedKcal(t.soFar)} so far)",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                Text("Goal ${signedKcal(goal)} a day (${goals.firstOrNull { it.first == goalKg }?.second?.lowercase() ?: "%+.2f".format(goalKg)} kg/week)",
                    style = MaterialTheme.typography.bodyLarge)
                FlowRowCompat(spacing = 6.dp) {
                    goals.forEach { (g, label) ->
                        Tag(label, color = if (g == goalKg) C.Accent else C.Muted, modifier = Modifier.clickable { onGoal(g) })
                    }
                }
            }
        }
    }

    item {
        val days = e.days.takeLast(14)
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Last two weeks")
                NetBars(days.map { it.net }, goal)
                Row {
                    Text(days.firstOrNull()?.date?.format(shortDate) ?: "", style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = Modifier.weight(1f))
                    Text("Dashed line is your goal. Grey: not logged.", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                }
                e.avgNet?.let { n ->
                    Text("Average ${signedKcal(n)} a day", style = MaterialTheme.typography.titleMedium, color = netColor(n, goal))
                }
            }
        }
    }

    item {
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Checked against the scale")
                if (e.trendKg != null && e.days.any { it.scaleKg != null }) WeightChart(e.days.takeLast(42))
                e.trendKgPerWeek?.let { k ->
                    Text("Trend ${"%.1f".format(e.trendKg)} kg, ${"%+.2f".format(k)} kg/week", style = MaterialTheme.typography.titleMedium)
                }
                if (e.avgNet != null && e.scaleNet != null) {
                    val diff = e.avgNet - e.scaleNet
                    Text("Your logs say ${signedKcal(e.avgNet)} a day; the scale says ${signedKcal(e.scaleNet)}.", style = MaterialTheme.typography.bodyLarge)
                    Text(when {
                        abs(diff) <= 200 -> "They agree ✓"
                        diff < 0 -> "The scale shows more surplus than the logs: some meals may be missing, or portions are bigger than estimated."
                        else -> "The scale shows less surplus than the logs: portions may be smaller than estimated, or it's still settling."
                    }, color = if (abs(diff) <= 200) Recover else Yellow, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (e.confidence >= 30) "Your Fitbit's burn runs ~${abs(e.fitbitOffPct)}% ${if (e.fitbitOffPct >= 0) "high" else "low"}: corrected using your weight."
                    else "Fitbit correction still learning.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Confidence ", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    LinearProgressIndicator(
                        progress = { e.confidence / 100f },
                        modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = C.Accent, trackColor = C.Raised, gapSize = 0.dp, drawStopIndicator = {},
                    )
                    Text("  ${e.confidence}%", style = MaterialTheme.typography.labelMedium.merge(Tnum), color = C.Muted)
                }
                Text(e.note, style = MaterialTheme.typography.labelMedium, color = C.Muted)
            }
        }
    }

    // body weight: completed-week averages and what they mean for the goal
    a?.let { an ->
        item {
            Card(Modifier.fillMaxWidth(), onClick = onWeight) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Body weight")
                    an.weight.lastWeekAvg?.let { w ->
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("%.1f kg".format(w), style = MaterialTheme.typography.displaySmall.merge(Tnum))
                            an.weight.changeKg?.let {
                                Text("  ${"%+.2f".format(it)} kg on the week before", style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                                    modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                    }
                    Text(an.weight.verdict + ".", style = MaterialTheme.typography.bodyMedium, color = if (an.weight.lastWeekAvg == null) C.Muted else C.Text)
                    Text("Tap for the graph, your rate and patterns", style = MaterialTheme.typography.titleSmall, color = C.Accent)
                }
            }
        }
    }
}

@Composable
private fun NetBars(values: List<Int?>, goal: Int) {
    val lim = 700f
    Canvas(Modifier.fillMaxWidth().height(130.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val mid = size.height / 2
        fun y(v: Float) = mid - (v.coerceIn(-lim, lim) / lim) * mid
        drawLine(C.Line, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
        values.forEachIndexed { i, v ->
            val x = i * (w + gap)
            if (v == null) {
                drawRoundRect(C.Raised, Offset(x, mid - 3.dp.toPx()), Size(w, 6.dp.toPx()), CornerRadius(2.dp.toPx()))
            } else {
                val top = minOf(y(v.toFloat()), mid)
                val h = abs(y(v.toFloat()) - mid).coerceAtLeast(2.dp.toPx())
                drawRoundRect(netColor(v, goal), Offset(x, top), Size(w, h), CornerRadius(3.dp.toPx()))
            }
        }
        val gy = y(goal.toFloat())
        drawLine(C.Text.copy(alpha = 0.7f), Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
}

@Composable
private fun WeightChart(days: List<Energy.DayNet>) {
    val pts = days.filter { it.scaleKg != null || it.trendKg != null }
    if (pts.size < 2) return
    val all = pts.mapNotNull { it.scaleKg } + pts.mapNotNull { it.trendKg }
    val lo = all.min() - 0.3
    val hi = all.max() + 0.3
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val n = days.size
        fun x(i: Int) = if (n <= 1) 0f else i * size.width / (n - 1)
        fun y(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
        days.forEachIndexed { i, d -> d.scaleKg?.let { drawCircle(C.Muted, 3.dp.toPx(), Offset(x(i), y(it))) } }
        var prev: Offset? = null
        days.forEachIndexed { i, d ->
            d.trendKg?.let {
                val p = Offset(x(i), y(it))
                prev?.let { q -> drawLine(C.Accent, q, p, 2.5.dp.toPx(), cap = StrokeCap.Round) }
                prev = p
            }
        }
    }
}
