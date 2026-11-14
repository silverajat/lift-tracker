package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.activity.ActivityItem
import com.silverajat.lifttracker.activity.SportAnalysis
import com.silverajat.lifttracker.activity.Sport
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dDay = DateTimeFormatter.ofPattern("EEE d MMM")
private val dTime = DateTimeFormatter.ofPattern("H:mm")

/** Zone colours: warm-up, easy, aerobic, threshold, max. */
val ZoneColors = listOf(Color(0xFF8F98A6), Plate.Blue, Plate.Green, Plate.Yellow, Plate.Red)

fun hrZoneColor(bpm: Int, hrMax: Int): Color = SportAnalysis.zoneOf(bpm, hrMax).let { if (it < 0) C.Line else ZoneColors[it] }

/** Loads the heart-rate breakdown for an activity, then shows its page. */
@Composable
fun ActivityScreen(hub: HubViewModel, item: ActivityItem, onBack: () -> Unit) {
    val loaded by produceState<Pair<Boolean, SportAnalysis.Result?>>(true to null, item.key, item.end) {
        value = false to hub.analyzeActivity(item)
    }
    ActivityDetail(
        item, loaded.second, loading = loaded.first, onBack = onBack,
        onDelete = item.manualId?.let { id -> { hub.deleteActivity(id); onBack() } },
        onChange = if (item.sessionId == null) { s: Sport? -> hub.changeType(item, s); onBack() } else null,
    )
}

@Composable
fun ActivityDetail(
    item: ActivityItem, a: SportAnalysis.Result?, loading: Boolean, onBack: () -> Unit,
    onDelete: (() -> Unit)? = null, onChange: ((Sport?) -> Unit)? = null,
) {
    val zone = ZoneId.systemDefault()
    val st = item.start.atZone(zone)
    var confirm by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    if (confirm && onDelete != null) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Delete this?") }, text = { Text("It can't be undone.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = C.Stop) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = C.Muted) } })

    Column(Modifier.fillMaxSize()) {
        TopBar("${item.sport.emoji} ${item.title}", st.format(dDay) + ", " + st.format(dTime) + "–" + item.end.atZone(zone).format(dTime), onBack) {
            if (onDelete != null) TextButton(onClick = { confirm = true }) { Text("Delete", color = C.Muted) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            DStat("${item.minutes}", "minutes")
                            DStat((a?.avg ?: item.avgHr)?.toString() ?: "–", "avg bpm")
                            DStat((a?.max ?: item.maxHr)?.toString() ?: "–", "max bpm")
                            DStat(item.strain?.let { "%.1f".format(it) } ?: "–", "strain", StrainBlue)
                        }
                        val extra = listOfNotNull(
                            a?.kcal?.let { "$it" to "calories" },
                            a?.steps?.takeIf { it > 0 }?.let { "%,d".format(it) to "steps" },
                            item.distanceKm?.let { "%.2f".format(it) to "km" },
                        )
                        if (extra.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { extra.forEach { (v, l) -> DStat(v, l) } }
                    }
                }
            }
            if (a != null) heartRateSections(a)
            else item {
                Text(if (loading) "Loading heart rate…" else "No heart rate for this yet. It fills in once your Fitbit syncs.",
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted, modifier = Modifier.padding(start = 4.dp))
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 4.dp)) {
                    Text("From: ${item.source}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    if (onChange != null) {
                        if (!changing) Text("Wrong type? Change it", style = MaterialTheme.typography.titleSmall, color = C.Accent,
                            modifier = Modifier.clickable { changing = true })
                        else {
                            FlowRowCompat(spacing = 6.dp) {
                                Sport.entries.filter { s -> s != Sport.GYM }.forEach { s ->
                                    Tag("${s.emoji} ${s.label}", color = if (s == item.sport) C.Accent else C.Text, modifier = Modifier.clickable { onChange(s) })
                                }
                            }
                            if (item.manualId == null) Text("Not an activity: hide it", style = MaterialTheme.typography.labelLarge, color = C.Stop,
                                modifier = Modifier.clickable { onChange(null) })
                        }
                    }
                }
            }
        }
    }
}

/** The heart-rate curve, zones, effort over time and the analysis (also used on a run's page). */
fun LazyListScope.heartRateSections(a: SportAnalysis.Result) {
    val zone = ZoneId.systemDefault()
    item {
        DSection("Heart rate") {
            HrChart(a)
            Text("Peak ${a.max} bpm at ${a.peakAt.atZone(zone).format(dTime)}, lowest ${a.min}",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
    item {
        DSection("Heart rate zones") {
            val total = a.zoneMin.sum().coerceAtLeast(1)
            val most = a.zoneMin.max().coerceAtLeast(1)
            for (i in SportAnalysis.ZONES.indices.reversed()) {
                val lo = (SportAnalysis.ZONE_FLOOR[i] * a.hrMax).toInt()
                val hi = if (i == 4) null else (SportAnalysis.ZONE_FLOOR[i + 1] * a.hrMax).toInt() - 1
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(92.dp)) {
                        Text(SportAnalysis.ZONES[i], style = MaterialTheme.typography.titleSmall, color = ZoneColors[i])
                        Text(if (hi == null) "$lo+ bpm" else "$lo–$hi", style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted)
                    }
                    Box(Modifier.weight(1f).height(14.dp)) {
                        val f = a.zoneMin[i].toFloat() / most
                        if (f > 0) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.02f)).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(ZoneColors[i]))
                    }
                    Text("${a.zoneMin[i]} min", style = MaterialTheme.typography.titleSmall.merge(Tnum), textAlign = TextAlign.End, modifier = Modifier.width(64.dp))
                    Text("${a.zoneMin[i] * 100 / total}%", style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted,
                        textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
                }
            }
        }
    }
    if (a.blocks.count { it != null } >= 2) item {
        DSection("Effort every ${a.blockMin} min") { BlockBars(a) }
    }
    if (a.notes.isNotEmpty()) item {
        DSection("Analysis") {
            a.notes.forEach { n ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 7.dp, end = 10.dp).size(6.dp).clip(CircleShape).background(C.Accent))
                    Text(n, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Card(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } }
    }
}

@Composable
private fun DStat(value: String, label: String, color: Color = C.Text) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.merge(Tnum), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

/** Heart rate over time, coloured by zone, on faint zone bands. */
@Composable
fun HrChart(a: SportAnalysis.Result) {
    val zone = ZoneId.systemDefault()
    val lo = ((a.min - 8) / 10) * 10
    val hi = ((a.max + 12) / 10) * 10
    val t0 = a.start.epochSecond; val t1 = a.end.epochSecond.coerceAtLeast(t0 + 60)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            Column(Modifier.width(34.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                listOf(hi, (hi + lo) / 2, lo).forEach { Text("$it", style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight()) {
                fun y(b: Double) = (size.height * (1 - (b - lo) / (hi - lo))).toFloat()
                fun x(t: Long) = (size.width * (t - t0).toDouble() / (t1 - t0)).toFloat()
                // zone bands
                for (i in 0 until 5) {
                    val bLo = SportAnalysis.ZONE_FLOOR[i] * a.hrMax
                    val bHi = if (i == 4) hi.toDouble() else SportAnalysis.ZONE_FLOOR[i + 1] * a.hrMax
                    val top = y(minOf(bHi, hi.toDouble())); val bot = y(maxOf(bLo, lo.toDouble()))
                    if (bot > top) drawRect(ZoneColors[i].copy(alpha = 0.07f), Offset(0f, top), Size(size.width, bot - top))
                }
                // grid
                listOf(hi, (hi + lo) / 2, lo).forEach { v ->
                    drawLine(C.Line, Offset(0f, y(v.toDouble())), Offset(size.width, y(v.toDouble())), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
                }
                // the curve (a gap of over 2 min in the data isn't bridged)
                val s = a.samples
                for (i in 1 until s.size) {
                    val p = s[i - 1]; val q = s[i]
                    if (q.time.epochSecond - p.time.epochSecond > 120) continue
                    drawLine(hrZoneColor((p.bpm + q.bpm) / 2, a.hrMax), Offset(x(p.time.epochSecond), y(p.bpm.toDouble())),
                        Offset(x(q.time.epochSecond), y(q.bpm.toDouble())), 2.2.dp.toPx(), cap = StrokeCap.Round)
                }
                drawCircle(Plate.Red, 4.5.dp.toPx(), Offset(x(a.peakAt.epochSecond), y(a.max.toDouble())))
                drawCircle(C.Surface, 2.dp.toPx(), Offset(x(a.peakAt.epochSecond), y(a.max.toDouble())))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 34.dp)) {
            val mid = a.start.plusSeconds((t1 - t0) / 2)
            Text(a.start.atZone(zone).format(dTime), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted, modifier = Modifier.weight(1f))
            Text(mid.atZone(zone).format(dTime), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            Text(a.end.atZone(zone).format(dTime), style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted,
                textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
    }
}

/** Average heart rate in each block, so you can see where the game picked up or faded. */
@Composable
private fun BlockBars(a: SportAnalysis.Result) {
    val vals = a.blocks.filterNotNull()
    val lo = (vals.min() - 15).coerceAtLeast(40); val hi = vals.max() + 5
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            a.blocks.forEach { b ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    if (b != null) {
                        Text("$b", style = MaterialTheme.typography.labelSmall.merge(Tnum), color = C.Muted)
                        Box(Modifier.fillMaxWidth().height((88 * (b - lo).toFloat() / (hi - lo)).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(hrZoneColor(b, a.hrMax)))
                    } else Box(Modifier.fillMaxWidth().height(4.dp).background(C.Line))
                }
            }
        }
        HorizontalDivider(color = C.Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            a.blocks.indices.forEach { i ->
                Text("${i * a.blockMin}", style = MaterialTheme.typography.labelSmall.merge(Tnum), color = C.Muted,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
    }
}
