package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.HealthState
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Targets
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val hm = DateTimeFormatter.ofPattern("H:mm")
private val dayHead = DateTimeFormatter.ofPattern("EEEE d MMMM")

val Recover = Color(0xFF16EC06)
val Yellow = Color(0xFFFFDE00)
val RedZ = Color(0xFFFF0026)
val StrainBlue = Color(0xFF0093E7)
val SleepPurple = Color(0xFF7BA1FF)

fun zoneColor(z: Analysis.Zone?) = when (z) {
    Analysis.Zone.GREEN -> Recover
    Analysis.Zone.YELLOW -> Yellow
    Analysis.Zone.RED -> RedZ
    null -> C.Muted
}

/** Google Health-style home: your two devices, then WHOOP-style rings and what they mean today. */
@Composable
fun TodayScreen(hub: HubViewModel, gym: AppViewModel, onConnectHealth: () -> Unit, onConnectPod: () -> Unit, onOpenGym: () -> Unit, onOpenNet: () -> Unit) {
    val state by hub.healthState.collectAsStateWithLifecycle()
    val a by hub.analysis.collectAsStateWithLifecycle()
    val days by hub.days.collectAsStateWithLifecycle()
    val synced by hub.syncedAt.collectAsStateWithLifecycle()
    val conn by gym.pod.conn.collectAsStateWithLifecycle()
    val status by gym.pod.status.collectAsStateWithLifecycle()
    val food by hub.todayEntries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.refreshHealth() }
    val zone = ZoneId.systemDefault()
    val today = a?.today
    val todayHealth = days.lastOrNull()

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---- devices (top left), date ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeviceChip(
                    name = "Fitbit Air",
                    ok = state == HealthState.OK,
                    sub = when (state) {
                        HealthState.OK -> synced?.let { "synced " + java.time.Instant.ofEpochMilli(it).atZone(zone).format(hm) } ?: "synced"
                        HealthState.LOADING -> "syncing…"
                        HealthState.NEEDS_PERMISSION -> "tap to connect"
                        HealthState.UNAVAILABLE -> "needs Health Connect"
                    },
                    onClick = if (state == HealthState.OK) ({ hub.refreshHealth() }) else onConnectHealth,
                )
                DeviceChip(
                    name = "Pod",
                    ok = conn == PodConn.READY,
                    sub = if (conn == PodConn.READY) (status?.let { "${it.batteryPct}%" + if (it.charging) " · charging" else "" } ?: "connected")
                        else "for the gym",
                    onClick = onConnectPod,
                )
            }
            Text(java.time.LocalDate.now().format(dayHead), style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 14.dp))
        }

        if (state == HealthState.NEEDS_PERMISSION || state == HealthState.UNAVAILABLE) {
            item {
                Card(Modifier.fillMaxWidth(), onClick = onConnectHealth) {
                    Column {
                        Text("Connect your Fitbit data", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state == HealthState.UNAVAILABLE) "Health Connect isn't available on this phone. Install or update it from the Play Store, then come back."
                            else "The Google Health app already copies your Fitbit's data into Health Connect. Allow this app to read it (heart rate, HRV, sleep, steps, calories, workouts). Nothing leaves your phone.",
                            style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                        )
                        Text("Connect", color = C.Accent, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }

        // ---- the three rings ----
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Ring("RECOVERY", today?.recovery?.let { "${it.score}%" } ?: "–",
                    (today?.recovery?.score ?: 0) / 100f, zoneColor(today?.recovery?.zone))
                Ring("STRAIN", today?.strain?.let { "%.1f".format(it.strain) } ?: "–",
                    ((today?.strain?.strain ?: 0.0) / 21.0).toFloat(), StrainBlue)
                Ring("SLEEP", today?.sleep?.let { "${it.performance}%" } ?: "–",
                    (today?.sleep?.performance ?: 0) / 100f, SleepPurple)
            }
        }

        // ---- today in plain words ----
        a?.headline?.takeIf { it.isNotEmpty() }?.let { lines ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Today")
                        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
        }

        // ---- recovery ----
        today?.recovery?.let { r ->
            item {
                Card(Modifier.fillMaxWidth(), border = zoneColor(r.zone).copy(alpha = 0.5f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("Recovery", Modifier.weight(1f))
                            Tag(r.zone.label, color = zoneColor(r.zone))
                        }
                        r.contributors.forEach { c ->
                            Row {
                                Text(c.name, style = MaterialTheme.typography.bodyLarge, color = C.Muted, modifier = Modifier.weight(1f))
                                Text(c.value, style = MaterialTheme.typography.titleMedium.merge(Tnum),
                                    color = when (c.good) { true -> Recover; false -> RedZ; null -> C.Text })
                                Text("  ${c.vsNormal}", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                            }
                        }
                        val t = Analysis.strainTarget(r.zone)
                        Text("Strain target today: ${t.start.toInt()}–${t.endInclusive.toInt()}", color = StrainBlue,
                            style = MaterialTheme.typography.titleMedium)
                        Text("Against your last ${r.baselineDays} days. HRV counts most.", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                }
            }
        }

        // ---- 14-day trends ----
        a?.days?.takeLast(14)?.takeIf { it.isNotEmpty() }?.let { ds ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("Last 14 days")
                        Text("Recovery", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                        Bars(ds.map { (it.recovery?.score ?: 0) / 100f }, ds.map { zoneColor(it.recovery?.zone) })
                        Text("Strain", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                        Bars(ds.map { ((it.strain?.strain ?: 0.0) / 21.0).toFloat() }, ds.map { StrainBlue })
                        Text("Sleep (hours)", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                        Bars(ds.map { ((it.sleep?.asleepMin ?: 0) / 600f).coerceAtMost(1f) }, ds.map { SleepPurple })
                        Text("${ds.first().date.format(DateTimeFormatter.ofPattern("d MMM"))} → today",
                            style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                }
            }
        }

        // ---- sleep ----
        today?.sleep?.let { s ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Sleep")
                        Text("${hmin(s.asleepMin)} of ${hmin(s.needMin)} needed · ${s.performance}%", style = MaterialTheme.typography.titleLarge)
                        Text("${s.bed.atZone(zone).format(hm)} – ${s.wake.atZone(zone).format(hm)}", color = C.Muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            s.deepPct?.let { Tag("Deep $it%", color = SleepPurple) }
                            s.remPct?.let { Tag("REM $it%", color = SleepPurple) }
                            s.efficiency?.let { Tag("Efficiency $it%") }
                            s.consistency?.let { Tag("Consistency $it%") }
                        }
                        if (s.debtMin >= 15) Text("Sleep debt ${s.debtMin} min from the last 3 nights (half of it is added to tonight's need).",
                            style = MaterialTheme.typography.bodyMedium, color = Yellow)
                    }
                }
            }
        }

        // ---- strain & gym ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Strain & training")
                    today?.strain?.let { st ->
                        Text("Strain %.1f".format(st.strain) + (st.target?.let { " · target ${it.start.toInt()}–${it.endInclusive.toInt()}" } ?: ""),
                            style = MaterialTheme.typography.titleLarge, color = StrainBlue)
                        Text("${st.moderateMin} min moderate, ${st.vigorousMin} min vigorous heart rate", color = C.Muted)
                    } ?: Text("No heart-rate data yet today", color = C.Muted)
                    today?.muscular?.let { m ->
                        Text("Gym: ${m.sets} working sets, ${"%,d".format(m.volumeKg.roundToInt())} kg moved" +
                            (m.vsAvgPct?.let { " ($it% of your usual)" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                    }
                    todayHealth?.let { h ->
                        h.steps?.let { Text("${"%,d".format(it)} steps", color = C.Muted) }
                    }
                    TextButton(onClick = onOpenGym) { Text("Open gym", color = C.Accent) }
                }
            }
        }

        // ---- net calories ----
        a?.energy?.let { e ->
            item {
                val goal by hub.goal.collectAsStateWithLifecycle()
                val g = e.netFor(goal)
                val t = todayNet(e, food, java.time.LocalDate.now(), java.time.LocalTime.now())
                Card(Modifier.fillMaxWidth(), onClick = onOpenNet) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SectionLabel("Net calories")
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(signedKcal(t.endOfDay), style = MaterialTheme.typography.headlineLarge.merge(Tnum), color = netColor(t.endOfDay, g))
                            Text("  today · goal ${signedKcal(g)}", color = C.Muted, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        e.avgNet?.let { Text("2-week average ${signedKcal(it)} a day" +
                            (e.trendKgPerWeek?.let { k -> " · scale ${"%+.2f".format(k)} kg/week" } ?: ""), style = MaterialTheme.typography.bodyMedium) }
                        Text(if (e.confidence >= 30) "Burn checked against your weight (Fitbit runs ~${kotlin.math.abs(e.fitbitOffPct)}% ${if (e.fitbitOffPct >= 0) "high" else "low"})"
                            else "Checked against your weight once there's ~2 weeks of weigh-ins",
                            style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                }
            }
        }

        // ---- strength ----
        a?.exercises?.takeIf { it.isNotEmpty() }?.let { ex ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Strength (est. 1RM) and next session")
                        ex.take(8).forEach { e ->
                            Column {
                                Row {
                                    Text(e.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text("${e.e1rmNow.roundToInt()} kg", style = MaterialTheme.typography.titleMedium.merge(Tnum))
                                    e.changePct?.let {
                                        Text("  ${if (it >= 0) "+" else ""}${"%.1f".format(it)}%", color = if (it >= 0) Recover else RedZ,
                                            style = MaterialTheme.typography.titleMedium.merge(Tnum))
                                    }
                                }
                                Text(e.suggestion, style = MaterialTheme.typography.bodyMedium, color = C.Accent)
                                Text(listOfNotNull("last ${e.lastSets}", e.qualityAvg?.let { "Q $it" }, e.romAvg?.let { "ROM $it%" }).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = C.Muted)
                            }
                        }
                        a?.groupSets7d?.takeIf { it.isNotEmpty() }?.let { g ->
                            Text("Sets this week: " + g.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key.label} ${it.value}" },
                                style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        }
                    }
                }
            }
        }

        // ---- body & food trends ----
        a?.let { an ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SectionLabel("Body weight")
                        an.weight.lastWeekAvg?.let { w ->
                            Text("Last week ${"%.2f".format(w)} kg" + (an.weight.changeKg?.let { " (${if (it >= 0) "+" else ""}${"%.2f".format(it)} kg)" } ?: ""),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        Text(an.weight.verdict, color = C.Muted)
                    }
                }
            }
        }

        // ---- what affects your recovery ----
        a?.effects?.takeIf { it.isNotEmpty() }?.let { effects ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel("What affects your recovery")
                        effects.take(6).forEach { e ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(e.habit, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text("${if (e.delta >= 0) "+" else ""}${e.delta.roundToInt()}", color = if (e.delta >= 0) Recover else RedZ,
                                    style = MaterialTheme.typography.titleMedium.merge(Tnum))
                                Spacer(Modifier.width(8.dp))
                                Tag(if (e.clear) "clear" else "early", color = if (e.clear) C.Text else C.Muted)
                            }
                        }
                        Text("Next-morning recovery on days with vs without. Fills in as days pass.",
                            style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                }
            }
        }
    }
}

fun hmin(m: Int) = "${m / 60}h ${m % 60}m"

@Composable
private fun DeviceChip(name: String, ok: Boolean, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.Surface).border(1.dp, if (ok) C.Pod.copy(alpha = 0.4f) else C.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) C.Done else C.Muted))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(sub, style = MaterialTheme.typography.labelMedium, color = C.Muted)
        }
    }
}

@Composable
private fun Ring(label: String, value: String, fraction: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(104.dp)) {
                val sw = 10.dp.toPx()
                val inset = sw / 2
                val sz = Size(size.width - sw, size.height - sw)
                drawArc(C.Raised, -90f, 360f, false, Offset(inset, inset), sz, style = Stroke(sw))
                drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), sz,
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Tnum), color = if (value == "–") C.Muted else color)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bars(values: List<Float>, colors: List<Color>) {
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val h = (size.height * v.coerceIn(0f, 1f)).coerceAtLeast(2.dp.toPx())
            drawRoundRect(colors[i].copy(alpha = if (v <= 0f) 0.25f else 1f), Offset(i * (w + gap), size.height - h), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        }
    }
}

@Composable
fun MacroBar(name: String, value: Double, lo: Double, hi: Double, color: Color) {
    Column {
        Row {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("${value.roundToInt()} / ${lo.roundToInt()}–${hi.roundToInt()} g", style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                color = if (value in lo..hi) Recover else if (value > hi) Yellow else C.Text)
        }
        LinearProgressIndicator(
            progress = { (value / hi).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = C.Raised, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}
