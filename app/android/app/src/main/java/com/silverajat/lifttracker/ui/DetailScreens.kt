package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.activity.ActivityItem
import com.silverajat.lifttracker.activity.ActivityLog
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.SleepStage
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val dShort = DateTimeFormatter.ofPattern("d MMM")
private val dDay = DateTimeFormatter.ofPattern("EEE d MMM")
private val hhmm = DateTimeFormatter.ofPattern("H:mm")

enum class Detail { RECOVERY, STRAIN, SLEEP }

// Sleep stage colours (awake, light, deep, REM)
private val StageAwake = Plate.Chalk
private val StageLight = Color(0xFF7FA6F5)
private val StageDeep = Color(0xFF2E58C7)
private val StageRem = Color(0xFFA48CFF)
private fun stageColor(t: Int) = when (t) { 0 -> StageAwake; 1 -> StageLight; 2 -> StageDeep; else -> StageRem }

@Composable
private fun DetailPage(title: String, sub: String?, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        TopBar(title, sub, onBack)
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            content = content,
        )
    }
}

private fun LazyListScope.block(title: String, note: String? = null, content: @Composable () -> Unit) {
    item {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            Card(Modifier.fillMaxWidth()) { content() }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp)) }
        }
    }
}

@Composable
private fun Kv(label: String, value: String, sub: String? = null, color: Color = C.Text) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = C.Muted) }
        }
        Text(value, style = MaterialTheme.typography.headlineSmall.merge(Tnum), color = color)
    }
}

/** A simple line over days with an optional "normal" band. */
@Composable
private fun TrendLine(values: List<Double?>, color: Color, band: Pair<Double, Double>? = null, height: Int = 110) {
    val v = values.filterNotNull()
    if (v.size < 2) { Text("Not enough nights yet.", style = MaterialTheme.typography.bodySmall, color = C.Muted); return }
    val lo = (v + listOfNotNull(band?.first)).min()
    val hi = (v + listOfNotNull(band?.second)).max()
    val pad = ((hi - lo) * 0.15).coerceAtLeast(1.0)
    val a = lo - pad; val b = hi + pad
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val n = values.size
        fun x(i: Int) = if (n <= 1) 0f else i * size.width / (n - 1)
        fun y(x: Double) = (size.height * (1 - (x - a) / (b - a))).toFloat()
        band?.let { (l, h) -> drawRect(color.copy(alpha = 0.12f), Offset(0f, y(h)), Size(size.width, y(l) - y(h))) }
        var prev: Offset? = null
        values.forEachIndexed { i, x ->
            if (x == null) { prev = null; return@forEachIndexed }
            val p = Offset(x(i), y(x))
            prev?.let { q -> drawLine(color, q, p, 2.5.dp.toPx(), cap = StrokeCap.Round) }
            drawCircle(color, 3.dp.toPx(), p)
            prev = p
        }
    }
}

@Composable
private fun DayBars(values: List<Float>, colors: List<Color>, height: Int = 90, marker: Float? = null) {
    Canvas(Modifier.fillMaxWidth().height(height.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val empty = v <= 0f
            val h = if (empty) 3.dp.toPx() else (size.height * v.coerceIn(0f, 1f)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (empty) C.Raised else colors[i], Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(3.dp.toPx()))
        }
        marker?.let { m ->
            val y = size.height * (1 - m.coerceIn(0f, 1f))
            drawLine(C.Text.copy(alpha = 0.6f), Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }
    }
}

@Composable
private fun Axis(first: LocalDate?, last: String = "today") {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(first?.format(dShort) ?: "", style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.weight(1f))
        Text(last, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

private fun baseOf(v: List<Double>): Pair<Double, Double>? {
    if (v.size < 5) return null
    val m = v.average()
    val sd = sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1)).coerceAtLeast(0.5)
    return m to sd
}

// ================================================================================================
// Recovery

@Composable
fun RecoveryDetail(a: Analysis.Result?, days: List<HealthDay>, onBack: () -> Unit) {
    val r = a?.today?.recovery
    DetailPage("Recovery", a?.today?.date?.format(dDay), onBack) {
        item {
            val color = zoneColor(r?.zone)
            Column {
                if (r != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${r.score}", style = MaterialTheme.typography.displayLarge.merge(Tnum), color = color)
                        Text("%", style = MaterialTheme.typography.displaySmall, color = color, modifier = Modifier.padding(bottom = 14.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(r.zone.label, style = MaterialTheme.typography.headlineSmall, color = color, modifier = Modifier.padding(bottom = 18.dp))
                    }
                    val t = Analysis.strainTarget(r.zone)
                    Text(when (r.zone) {
                        Analysis.Zone.GREEN -> "Your body is ready for a hard day. Strain target ${t.start.toInt()} to ${t.endInclusive.toInt()}: push the top sets or go long on the run."
                        Analysis.Zone.YELLOW -> "Recovered enough to train as planned. Strain target ${t.start.toInt()} to ${t.endInclusive.toInt()}: skip the PR attempts."
                        Analysis.Zone.RED -> "Your body is still catching up. Strain target ${t.start.toInt()} to ${t.endInclusive.toInt()}: keep it light, sleep early."
                    }, style = MaterialTheme.typography.bodyLarge)
                } else Text("Recovery needs about 5 nights of heart-rate data from the Fitbit to know what's normal for you.",
                    style = MaterialTheme.typography.bodyLarge, color = C.Muted)
            }
        }
        r?.let { rr ->
            block("Why ${rr.score}%", "Compared with your last ${rr.baselineDays} days. HRV counts for half, resting heart rate and sleep a fifth each, breathing rate the rest.") {
                Column {
                    rr.contributors.forEachIndexed { i, c ->
                        if (i > 0) HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 10.dp))
                        Kv(c.name, c.value, c.vsNormal, when (c.good) { true -> Recover; false -> RedZ; null -> C.Text })
                    }
                }
            }
        }
        val ds = a?.days?.takeLast(30).orEmpty()
        if (ds.any { it.recovery != null }) block("Last 30 days", ds.mapNotNull { it.recovery?.score }.takeIf { it.isNotEmpty() }?.let { "Average ${it.average().roundToInt()}%." }) {
            Column {
                DayBars(ds.map { (it.recovery?.score ?: 0) / 100f }, ds.map { zoneColor(it.recovery?.zone) }, 100)
                Axis(ds.firstOrNull()?.date)
            }
        }
        val hd = days.takeLast(30)
        val hrv = hd.map { it.hrvMs }
        block("Heart rate variability", "Higher than your normal (the shaded band) usually means well recovered. Measured while you sleep.") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hd.lastOrNull()?.hrvMs?.let { Text("${it.roundToInt()} ms last night", style = MaterialTheme.typography.titleMedium) }
                val b = baseOf(hrv.filterNotNull().dropLast(1).map { ln(it) })
                TrendLine(hrv, Plate.Green, b?.let { exp(it.first - it.second) to exp(it.first + it.second) })
                Axis(hd.firstOrNull()?.date)
            }
        }
        block("Resting heart rate", "Lower than normal is good; a jump of 3+ bpm often means stress, illness, alcohol or a late meal.") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hd.lastOrNull()?.restingHr?.let { Text("$it bpm", style = MaterialTheme.typography.titleMedium) }
                val rhr = hd.map { it.restingHr?.toDouble() }
                TrendLine(rhr, Plate.Red, baseOf(rhr.filterNotNull().dropLast(1))?.let { it.first - it.second to it.first + it.second })
                Axis(hd.firstOrNull()?.date)
            }
        }
        if (hd.any { it.respRate != null }) block("Breathing rate", "Usually very steady. A rise of 1+ breath a minute can be an early sign of getting sick.") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                hd.lastOrNull()?.respRate?.let { Text("%.1f per minute".format(it), style = MaterialTheme.typography.titleMedium) }
                TrendLine(hd.map { it.respRate }, Plate.Chalk)
                Axis(hd.firstOrNull()?.date)
            }
        }
    }
}

// ================================================================================================
// Strain (cardio load)

/** Weekly load against a range from your last 4 weeks, the same idea as Cardio Load's target. */
data class WeekLoad(val load: Int, val low: Int?, val high: Int?, val status: String)

fun weekLoad(days: List<HealthDay>): WeekLoad {
    val trimp = days.map { it.load?.trimp ?: 0 }
    val week = trimp.takeLast(7).sum()
    val prior = trimp.dropLast(7).takeLast(21)
    if (prior.count { it > 0 } < 10) return WeekLoad(week, null, null, "Needs about 3 more weeks of heart rate to set your range.")
    val avg = prior.sum() / 3.0
    val low = (avg * 0.8).roundToInt(); val high = (avg * 1.3).roundToInt()
    val status = when {
        week < low * 0.75 -> "Well under your usual: fitness slowly fades at this level."
        week < low -> "A bit under your usual: fine for a recovery week."
        week <= high -> "In your sweet spot: enough to improve without overdoing it."
        week <= high * 1.2 -> "Above your usual: you're building, watch your recovery."
        else -> "Far above your usual: injury and illness risk goes up. Ease off."
    }
    return WeekLoad(week, low, high, status)
}

@Composable
fun StrainDetail(a: Analysis.Result?, days: List<HealthDay>, items: List<ActivityItem>, goalKg: Double, onBack: () -> Unit, onLog: () -> Unit, onOpen: (ActivityItem) -> Unit) {
    val st = a?.today?.strain
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    DetailPage("Strain", "How hard your heart worked", onBack) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(st?.let { "%.1f".format(it.strain) } ?: "0.0", style = MaterialTheme.typography.displayLarge.merge(Tnum), color = StrainBlue)
                    Text("  of 21", style = MaterialTheme.typography.titleMedium, color = C.Muted, modifier = Modifier.padding(bottom = 18.dp))
                }
                st?.target?.let { t ->
                    val left = (t.start - st.strain)
                    Text(if (left > 0) "Target today ${t.start.toInt()} to ${t.endInclusive.toInt()}, from your recovery. About %.1f to go.".format(left)
                        else if (st.strain <= t.endInclusive) "In today's target (${t.start.toInt()} to ${t.endInclusive.toInt()}). Nice." else "Past today's target of ${t.endInclusive.toInt()}: rest up tonight.",
                        style = MaterialTheme.typography.bodyLarge)
                }
                Text("Strain is a daily score from your heart rate: every minute your heart works hard adds to it, and it gets harder to raise the higher it goes. 10 is a normal active day, 14+ is a hard training day, 18+ is a big one.",
                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
        val wl = weekLoad(days)
        block("This week's load", wl.status) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${wl.load}", style = MaterialTheme.typography.displaySmall.merge(Tnum), color = StrainBlue)
                    if (wl.low != null) Text("  your range ${wl.low} to ${wl.high}", style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(bottom = 6.dp))
                }
                if (wl.low != null && wl.high != null) LoadGauge(wl.load, wl.low, wl.high)
                Text("Load is the 7-day total of heart-rate effort. Like Cardio Load, the range comes from your last 4 weeks.",
                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
        a?.let { an ->
            val mg = com.silverajat.lifttracker.health.MoveGoal.compute(an.energy, goalKg, an.weight.lastWeekAvg)
            block("Moving enough for your goal?", if (mg == null) null else "Uses your burn as checked against your weight trend (the Fitbit alone runs ${abs(an.energy.fitbitOffPct)}% ${if (an.energy.fitbitOffPct >= 0) "high" else "low"}), what you've eaten, and your weight of %.1f kg.".format(mg.weightKg)) {
                if (mg == null) Text("Needs about 2 weeks of logged food and morning weigh-ins. Then this tells you whether your running, games and gym are enough to hold your weight goal.",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(mg.verdict, style = MaterialTheme.typography.bodyLarge, color = if (abs(mg.gapPerDay) <= 100) Recover else C.Text)
                    HorizontalDivider(color = C.Line)
                    Kv("You burn", "%,d".format(mg.burnPerDay), "a typical day, scale-checked")
                    Kv("Your goal needs", "%,d".format(mg.burnGoalPerDay), "at what you eat now", if (abs(mg.gapPerDay) <= 100) Recover else Yellow)
                }
            }
        }
        val todayItems = items.filter { it.start.atZone(zone).toLocalDate() == today }
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)) {
                    Text("Today", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    Text("Log activity", style = MaterialTheme.typography.titleSmall, color = C.Accent, modifier = Modifier.clickable(onClick = onLog).padding(4.dp))
                }
                if (todayItems.isEmpty()) Text("No activities yet today. Runs, games and gym sessions show up here, plus anything your Fitbit picks up.",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(start = 4.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { todayItems.forEach { ActivityRow(it, onOpen) } }
            }
        }
        days.lastOrNull()?.load?.zones?.takeIf { it.sum() > 0 }?.let { z ->
            block("Heart-rate zones today", "Minutes at each share of your max heart rate. Zones 2 and 3 build your base; 4 and 5 are hard efforts.") { Zones(z) }
        }
        val ds = a?.days?.takeLast(14).orEmpty()
        if (ds.isNotEmpty()) block("Last 14 days", ds.mapNotNull { it.strain?.strain }.takeIf { it.isNotEmpty() }?.let { "Average %.1f a day.".format(it.average()) }) {
            Column {
                DayBars(ds.map { ((it.strain?.strain ?: 0.0) / 21.0).toFloat() }, ds.map { StrainBlue }, 110, marker = 14f / 21f)
                Axis(ds.firstOrNull()?.date)
            }
        }
        val past = items.filter { it.start.atZone(zone).toLocalDate() < today }.take(20)
        if (past.isNotEmpty()) item {
            Column {
                Text("Earlier", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { past.forEach { ActivityRow(it, onOpen, showDate = true) } }
            }
        }
    }
}

@Composable
private fun LoadGauge(v: Int, low: Int, high: Int) {
    val max = (high * 1.6).coerceAtLeast(v * 1.1)
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        fun x(t: Double) = (size.width * (t / max)).toFloat().coerceIn(0f, size.width)
        drawRoundRect(C.Raised, cornerRadius = CornerRadius(6.dp.toPx()))
        drawRect(Recover.copy(alpha = 0.35f), Offset(x(low.toDouble()), 0f), Size(x(high.toDouble()) - x(low.toDouble()), size.height))
        drawCircle(StrainBlue, 8.dp.toPx(), Offset(x(v.toDouble()), size.height / 2))
        drawCircle(C.Surface, 3.dp.toPx(), Offset(x(v.toDouble()), size.height / 2))
    }
}

@Composable
private fun Zones(z: List<Int>) {
    val max = z.max().coerceAtLeast(1)
    val colors = listOf(Color(0xFF8F98A6), Plate.Green, Plate.Yellow, Color(0xFFF08A3C), Plate.Red)
    val names = listOf("Zone 1, 50-60%", "Zone 2, 60-70%", "Zone 3, 70-80%", "Zone 4, 80-90%", "Zone 5, 90%+")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        z.forEachIndexed { i, m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(names[i], style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.width(104.dp))
                Box(Modifier.weight(1f).height(12.dp)) {
                    Box(Modifier.fillMaxWidth(m / max.toFloat()).height(12.dp).clip(RoundedCornerShape(3.dp)).background(colors[i]))
                }
                Text("$m min", style = MaterialTheme.typography.labelLarge.merge(Tnum), modifier = Modifier.width(56.dp).padding(start = 8.dp))
            }
        }
    }
}

@Composable
fun ActivityRow(it: ActivityItem, onOpen: (ActivityItem) -> Unit, showDate: Boolean = false) {
    val zone = ZoneId.systemDefault()
    val s = it.start.atZone(zone)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).clickable { onOpen(it) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(it.sport.emoji, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(it.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(listOfNotNull(
                (if (showDate) s.format(dDay) + ", " else "") + s.format(hhmm),
                "${it.minutes} min",
                it.distanceKm?.let { k -> "%.2f km".format(k) },
                it.avgHr?.let { h -> "avg $h bpm" },
                it.source,
            ).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.Muted, maxLines = 2)
        }
        it.strain?.let { Text("%.1f".format(it), style = MaterialTheme.typography.headlineSmall.merge(Tnum), color = StrainBlue) }
    }
}

// ================================================================================================
// Sleep

@Composable
fun SleepDetail(a: Analysis.Result?, days: List<HealthDay>, onBack: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val s = a?.today?.sleep
    DetailPage("Sleep", s?.let { "${it.bed.atZone(zone).format(hhmm)} to ${it.wake.atZone(zone).format(hhmm)}" }, onBack) {
        if (s == null) { item { Text("No sleep synced for last night yet. Wear the Fitbit to bed and open Google Health in the morning.", color = C.Muted) }; return@DetailPage }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${s.performance}", style = MaterialTheme.typography.displayLarge.merge(Tnum), color = SleepPurple)
                    Text("%", style = MaterialTheme.typography.displaySmall, color = SleepPurple, modifier = Modifier.padding(bottom = 14.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("of the sleep\nyou needed", style = MaterialTheme.typography.titleSmall, color = C.Muted, modifier = Modifier.padding(bottom = 18.dp))
                }
                NeedBar(s.asleepMin, s.needMin)
                Row {
                    Text("${hmin(s.asleepMin)} asleep", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("${hmin(s.needMin)} needed", style = MaterialTheme.typography.titleSmall, color = C.Muted)
                }
            }
        }
        if (s.stages.isNotEmpty()) block("Stages through the night") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Hypnogram(s.stages)
                Row {
                    Text(s.bed.atZone(zone).format(hhmm), style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.weight(1f))
                    Text(s.wake.atZone(zone).format(hhmm), style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
            }
        }
        val staged = s.deepMin + s.remMin + s.lightMin > 0
        if (staged) block("Time in each stage", "Typical for adults: deep 13-23%, REM 20-25%, light about half. Deep repairs muscle; REM sorts memory and learning.") {
            val total = (s.deepMin + s.remMin + s.lightMin + s.awakeMin).coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StageRow("Awake", s.awakeMin, total, StageAwake)
                StageRow("Light", s.lightMin, total, StageLight)
                StageRow("Deep", s.deepMin, total, StageDeep)
                StageRow("REM", s.remMin, total, StageRem)
                HorizontalDivider(color = C.Line)
                Kv("Restorative sleep", hmin(s.deepMin + s.remMin), "deep plus REM, ${(s.deepMin + s.remMin) * 100 / s.asleepMin.coerceAtLeast(1)}% of your sleep", StageRem)
            }
        }
        block("What you needed", "Need goes up after hard days and when you're short on sleep. It resets as you catch up.") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Kv("Baseline", "8h 0m")
                Kv("From yesterday's strain", "+${s.needStrainMin}m")
                Kv("Paying back sleep debt", "+${s.needDebtMin}m", if (s.debtMin > 0) "${s.debtMin} min owed over the last 3 nights" else null)
                HorizontalDivider(color = C.Line)
                Kv("Needed", hmin(s.needMin))
            }
        }
        block("Quality") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.efficiency?.let { Kv("Efficiency", "$it%", "time asleep while in bed; 85%+ is good", if (it >= 85) Recover else Yellow) }
                s.consistency?.let { Kv("Regularity", "$it%", "how close your bed and wake times are to the last few nights", if (it >= 80) Recover else Yellow) }
                days.lastOrNull()?.respRate?.let { Kv("Breathing rate", "%.1f".format(it), "breaths a minute while asleep") }
                days.lastOrNull()?.spo2?.let { Kv("Blood oxygen", "${it.roundToInt()}%", "average overnight") }
            }
        }
        val nights = a?.days?.takeLast(14).orEmpty()
        block("Last 14 nights", "Bars are time asleep; the dashed line is 8 hours.") {
            Column {
                DayBars(nights.map { n -> n.sleep?.let { (it.asleepMin / 600f) } ?: 0f },
                    nights.map { n -> n.sleep?.let { if (it.performance >= 85) SleepPurple else if (it.performance >= 70) Yellow else RedZ } ?: C.Raised }, 110, 480f / 600f)
                Axis(nights.firstOrNull()?.date)
            }
        }
        block("Bed and wake times", "Each bar is one night, from falling asleep to waking. Going to bed at the same time is one of the biggest wins for deep sleep.") {
            Column { Schedule(nights, zone); Axis(nights.firstOrNull()?.date) }
        }
        item {
            val wakes = nights.mapNotNull { it.sleep?.wake?.atZone(zone)?.toLocalTime() }
            if (wakes.size >= 3) {
                val wake = wakes.map { it.toSecondOfDay() }.sorted()[wakes.size / 2].let { java.time.LocalTime.ofSecondOfDay(it.toLong()) }
                val need = 480 + s.needDebtMin
                val bed = wake.minusMinutes(need + 15L)
                Card(Modifier.fillMaxWidth(), border = SleepPurple.copy(alpha = 0.4f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Tonight", style = MaterialTheme.typography.titleLarge)
                        Text("Lights out by ${bed.format(hhmm)} to get ${hmin(need)} before your usual ${wake.format(hhmm)} wake-up.",
                            style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun NeedBar(asleep: Int, need: Int) {
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val max = maxOf(asleep, need) * 1.08f
        drawRoundRect(C.Raised, cornerRadius = CornerRadius(7.dp.toPx()))
        drawRoundRect(SleepPurple, size = Size(size.width * asleep / max, size.height), cornerRadius = CornerRadius(7.dp.toPx()))
        val nx = size.width * need / max
        drawLine(Plate.Yellow, Offset(nx, -4f), Offset(nx, size.height + 4f), 3.dp.toPx())
    }
}

@Composable
private fun StageRow(name: String, min: Int, total: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(56.dp))
        Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(C.Raised)) {
            Box(Modifier.fillMaxWidth(min / total.toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
        }
        Text(hmin(min), style = MaterialTheme.typography.labelLarge.merge(Tnum), modifier = Modifier.width(64.dp).padding(start = 10.dp))
        Text("${min * 100 / total}%", style = MaterialTheme.typography.labelLarge.merge(Tnum), color = C.Muted, modifier = Modifier.width(38.dp))
    }
}

/** Stage graph: awake at the top, deep at the bottom, like a sleep lab chart. */
@Composable
private fun Hypnogram(stages: List<SleepStage>) {
    val rows = mapOf(0 to 0, 3 to 1, 1 to 2, 2 to 3)       // awake, REM, light, deep
    val labels = listOf("Awake", "REM", "Light", "Deep")
    val t0 = stages.first().start.toEpochMilli()
    val t1 = stages.last().end.toEpochMilli()
    Row {
        Column(Modifier.height(140.dp).width(48.dp), verticalArrangement = Arrangement.SpaceAround) {
            labels.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = C.Muted) }
        }
        Canvas(Modifier.weight(1f).height(140.dp)) {
            val rh = size.height / 4
            for (i in 0..3) drawLine(C.Line, Offset(0f, rh * i + rh / 2), Offset(size.width, rh * i + rh / 2), 1f)
            fun x(ms: Long) = ((ms - t0).toFloat() / (t1 - t0).coerceAtLeast(1)) * size.width
            var prev: Pair<Float, Float>? = null
            stages.forEach { st ->
                val r = rows[st.type] ?: return@forEach
                val x0 = x(st.start.toEpochMilli()); val x1 = x(st.end.toEpochMilli())
                val y = rh * r + rh * 0.15f
                drawRoundRect(stageColor(st.type), Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), rh * 0.7f), CornerRadius(3.dp.toPx()))
                prev?.let { (px, py) -> drawLine(C.Muted.copy(alpha = 0.5f), Offset(x0, py), Offset(x0, y + rh * 0.35f), 1.dp.toPx()) }
                prev = x1 to (y + rh * 0.35f)
            }
        }
    }
}

/** Each night as a bar from falling asleep to waking, on a 9 PM to noon axis. */
@Composable
private fun Schedule(nights: List<Analysis.Day>, zone: ZoneId) {
    // minutes since 9 PM of the evening before
    fun pos(i: java.time.Instant, d: LocalDate): Float =
        java.time.Duration.between(d.minusDays(1).atTime(21, 0).atZone(zone).toInstant(), i).toMinutes().toFloat()
    val span = 15 * 60f
    Row {
        Column(Modifier.height(150.dp).width(40.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf("9 PM", "12 AM", "3 AM", "6 AM", "9 AM", "12 PM").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = C.Muted) }
        }
        Canvas(Modifier.weight(1f).height(150.dp)) {
            val n = nights.size.coerceAtLeast(1)
            val gap = 5.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (k in 0..5) { val y = size.height * k / 5f; drawLine(C.Line, Offset(0f, y), Offset(size.width, y), 1f) }
            nights.forEachIndexed { i, d ->
                val s = d.sleep ?: return@forEachIndexed
                val a = (pos(s.bed, d.date) / span).coerceIn(0f, 1f) * size.height
                val b = (pos(s.wake, d.date) / span).coerceIn(0f, 1f) * size.height
                drawRoundRect(SleepPurple.copy(alpha = 0.85f), Offset(i * (w + gap), a), Size(w, (b - a).coerceAtLeast(4f)), CornerRadius(4.dp.toPx()))
            }
        }
    }
}

@Suppress("unused") private fun keep() = abs(1) + ActivityLog.pace(300).length
