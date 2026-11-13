package com.silverajat.lifttracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.activity.Activity
import com.silverajat.lifttracker.activity.ActivityItem
import com.silverajat.lifttracker.activity.ActivityLog
import com.silverajat.lifttracker.activity.RoutePoint
import com.silverajat.lifttracker.activity.RunTracker
import com.silverajat.lifttracker.activity.Sport
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.roundToInt

private val sDay = DateTimeFormatter.ofPattern("EEE d MMM")
private val sTime = DateTimeFormatter.ofPattern("H:mm")
private val SportOrange = Color(0xFFF08A3C)

/** Runs (with GPS), football and badminton; everything else your Fitbit picks up. */
@Composable
fun SportsScreen(hub: HubViewModel, onStartGps: (Sport) -> Unit, onOpenGym: (Long) -> Unit) {
    val items by hub.items.collectAsStateWithLifecycle()
    val acts by hub.activities.collectAsStateWithLifecycle()
    val live by RunTracker.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var runOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var logSport by remember { mutableStateOf<Sport?>(null) }
    var info by remember { mutableStateOf<ActivityItem?>(null) }

    LaunchedEffect(Unit) { hub.refreshActivities() }
    runOpen?.let { id ->
        val run = acts.firstOrNull { it.id == id }
        if (run != null) {
            BackHandler { runOpen = null }
            RunDetail(run, items.firstOrNull { it.manualId == id }, onBack = { runOpen = null }, onDelete = { hub.deleteActivity(id); runOpen = null })
            return
        }
    }
    if (live.active) { LiveSession(live, onFinish = { note -> RunTracker.stop(ctx, note) }); return }

    logSport?.let { sp -> LogActivityDialog(sp, onDismiss = { logSport = null }) { s, start, min, km, note -> hub.addActivity(s, start, min, km, note); logSport = null } }
    info?.let { it -> ActivityInfo(it, onDismiss = { info = null }, onDelete = it.manualId?.let { id -> { hub.deleteActivity(id); info = null } },
        onChange = { s -> hub.changeType(it, s); info = null }) }

    fun open(it: ActivityItem) {
        when {
            it.sessionId != null -> onOpenGym(it.sessionId)
            it.manualId != null && acts.firstOrNull { a -> a.id == it.manualId }?.route?.isNotEmpty() == true -> runOpen = it.manualId
            else -> info = it
        }
    }

    val zone = ZoneId.systemDefault()
    // one month at a time (this month to start); best 5K/10K stay all-time
    var monthKey by rememberSaveable { mutableStateOf(java.time.YearMonth.now().toString()) }
    val month = java.time.YearMonth.parse(monthKey)
    val monthName = month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    fun inMonth(i: Instant) = java.time.YearMonth.from(i.atZone(zone)) == month
    val shown = items.filter { inMonth(it.start) }
    val runs = acts.filter { it.sport == Sport.RUN }.sortedByDescending { it.startMs }
    val runItems = shown.filter { it.sport == Sport.RUN }
    val football = shown.filter { it.sport == Sport.FOOTBALL }
    val badminton = shown.filter { it.sport == Sport.BADMINTON }
    val walks = shown.filter { it.sport == Sport.WALK }
    val other = shown.filter { it.sport !in setOf(Sport.RUN, Sport.WALK, Sport.FOOTBALL, Sport.BADMINTON, Sport.GYM) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sports", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    Text("‹", style = MaterialTheme.typography.headlineMedium, color = C.Text,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { monthKey = month.minusMonths(1).toString() }.padding(horizontal = 12.dp))
                    Text(if (month.year == LocalDate.now().year) monthName else "$monthName ${month.year}", style = MaterialTheme.typography.titleMedium)
                    val canNext = month < java.time.YearMonth.now()
                    Text("›", style = MaterialTheme.typography.headlineMedium, color = if (canNext) C.Text else C.Line,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = canNext) { monthKey = month.plusMonths(1).toString() }.padding(horizontal = 12.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartTile("Run", Sport.RUN.emoji, "GPS", Plate.Blue, Modifier.weight(1f)) { onStartGps(Sport.RUN) }
                    StartTile("Walk", Sport.WALK.emoji, "GPS", Plate.Chalk, Modifier.weight(1f)) { onStartGps(Sport.WALK) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StartTile("Football", Sport.FOOTBALL.emoji, "timer", Plate.Green, Modifier.weight(1f)) { RunTracker.start(ctx, Sport.FOOTBALL) }
                    StartTile("Badminton", Sport.BADMINTON.emoji, "timer", Plate.Yellow, Modifier.weight(1f)) { RunTracker.start(ctx, Sport.BADMINTON) }
                }
                Text("Tap to start. Forgot to? Log a past one in each section below.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }

        // ---- running ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHead("Running", "Log a run") { logSport = Sport.RUN }
                val b5 = ActivityLog.best(runs, 5)
                val b10 = ActivityLog.best(runs, 10)
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            BigStat(b5?.let { ActivityLog.clock(it.second) } ?: "–", "best 5K")
                            BigStat(b10?.let { ActivityLog.clock(it.second) } ?: "–", "best 10K")
                            BigStat("%.1f".format(runItems.sumOf { it.distanceKm ?: 0.0 }), "km in ${monthName.take(3)}")
                        }
                        val tens = runs.filter { (it.distanceKm ?: 0.0) >= 9.5 }.sortedBy { it.startMs }.takeLast(12)
                        if (tens.size >= 2) {
                            HorizontalDivider(color = C.Line)
                            Text("Sunday 10Ks", style = MaterialTheme.typography.titleSmall)
                            TimeTrend(tens.map { (it.durationS * 10 / it.distanceKm!!).roundToInt() })
                            Text("Lower is faster. Last: ${ActivityLog.clock((tens.last().durationS * 10 / tens.last().distanceKm!!).roundToInt())}",
                                style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                    }
                }
                ShortList(runItems, ::open, "No runs in $monthName.")
            }
        }

        sportSection("Football", Sport.FOOTBALL, football, monthName, { logSport = Sport.FOOTBALL }, ::open)
        sportSection("Badminton", Sport.BADMINTON, badminton, monthName, { logSport = Sport.BADMINTON }, ::open)
        sportSection("Walks", Sport.WALK, walks, monthName, { logSport = Sport.WALK }, ::open, action = "Log a walk", showKm = true)

        if (other.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Other, from your Fitbit", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
                Text("Walks, swims, rides and anything else the Fitbit detects by itself.", style = MaterialTheme.typography.bodySmall,
                    color = C.Muted, modifier = Modifier.padding(start = 4.dp))
                ShortList(other, ::open, "")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.sportSection(
    title: String, sport: Sport, list: List<ActivityItem>, monthName: String, onLog: () -> Unit, onOpen: (ActivityItem) -> Unit,
    action: String = "Log a game", showKm: Boolean = false,
) {
    item {
        val month = list          // already just the chosen month
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHead(title, action, onLog)
            Card(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    BigStat("${month.size}", if (sport == Sport.WALK) "walks" else "games")
                    if (showKm) BigStat("%.1f".format(month.sumOf { it.distanceKm ?: 0.0 }), "km")
                    else BigStat("${month.sumOf { it.minutes }}", "minutes")
                    BigStat(list.mapNotNull { it.strain }.takeIf { it.isNotEmpty() }?.let { "%.1f".format(it.average()) } ?: "–", "average strain", StrainBlue)
                }
            }
            ShortList(list, onOpen, "No ${title.lowercase()} in $monthName." +
                (if (sport == Sport.WALK) " Walks your Fitbit detects show up here by themselves." else ""))
        }
    }
}

/** The 3 newest, with "Show all" for the rest of the month. */
@Composable
private fun ShortList(list: List<ActivityItem>, onOpen: (ActivityItem) -> Unit, empty: String) {
    var all by remember(list.size) { mutableStateOf(false) }
    if (list.isEmpty() && empty.isNotEmpty()) Text(empty, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(start = 4.dp))
    (if (all) list else list.take(3)).forEach { ActivityRow(it, onOpen, showDate = true) }
    if (list.size > 3) Text(if (all) "Show less" else "Show all ${list.size}", style = MaterialTheme.typography.titleSmall, color = C.Accent,
        modifier = Modifier.clickable { all = !all }.padding(start = 4.dp, top = 2.dp))
}

@Composable
private fun SectionHead(title: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        Text(action, style = MaterialTheme.typography.titleSmall, color = C.Accent, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
    }
}

@Composable
private fun BigStat(value: String, label: String, color: Color = C.Text) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.merge(Tnum), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun StartTile(name: String, emoji: String, sub: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(color.copy(alpha = 0.16f)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.headlineMedium)
        // one line even for "Badminton" on a narrow phone
        Text(name, style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp), color = color, maxLines = 1, softWrap = false)
        Text("Start, $sub", style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun TimeTrend(sec: List<Int>) {
    val lo = sec.min() - 30; val hi = sec.max() + 30
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val n = sec.size
        fun x(i: Int) = i * size.width / (n - 1)
        fun y(v: Int) = size.height * (v - lo) / (hi - lo).toFloat()      // faster = higher
        for (i in 1 until n) drawLine(Plate.Blue, Offset(x(i - 1), y(sec[i - 1])), Offset(x(i), y(sec[i])), 2.5.dp.toPx(), cap = StrokeCap.Round)
        sec.indices.forEach { drawCircle(Plate.Blue, 3.5.dp.toPx(), Offset(x(it), y(sec[it]))) }
    }
}

// ---- live run / game -------------------------------------------------------------------------

@Composable
fun LiveSession(s: RunTracker.State, onFinish: (String) -> Unit) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    var confirm by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    val moving = (s.movingMs(now) / 1000).toInt()
    val color = when (s.sport) { Sport.RUN -> Plate.Blue; Sport.WALK -> Plate.Chalk; Sport.FOOTBALL -> Plate.Green; Sport.BADMINTON -> Plate.Yellow; else -> SportOrange }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Finish ${s.sport.label.lowercase()}?") },
        text = {
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional), e.g. 5-a-side") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text))
        },
        confirmButton = { TextButton(onClick = { confirm = false; onFinish(note) }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirm = false; RunTracker.discard(ctx) }) { Text("Discard", color = C.Stop) }
                TextButton(onClick = { confirm = false }) { Text("Keep going", color = C.Muted) }
            }
        },
    )

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${s.sport.emoji} ${s.sport.label}", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (s.usesGps) Text(if (s.gpsOk) "GPS good" else "Finding GPS…", style = MaterialTheme.typography.labelLarge,
                color = if (s.gpsOk) Recover else Yellow)
        }
        Text(ActivityLog.clock(moving), style = MaterialTheme.typography.displayLarge.merge(Tnum), color = if (s.paused) C.Muted else color)
        if (s.paused) Text("Paused", style = MaterialTheme.typography.titleMedium, color = Yellow)
        if (s.usesGps) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                BigStat("%.2f".format(s.distanceM / 1000), "km")
                BigStat(s.paceSPerKm?.let { ActivityLog.pace(it).removeSuffix(" /km") } ?: "–", "pace now")
                BigStat(if (s.distanceM > 200) ActivityLog.pace((moving / (s.distanceM / 1000)).roundToInt()).removeSuffix(" /km") else "–", "average")
            }
            if (s.points.size >= 2) Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(20.dp)).background(C.Surface).padding(16.dp)) {
                Route(s.points, color)
            } else Spacer(Modifier.weight(1f))
            if (s.splitsS.isNotEmpty()) Text("Last km ${ActivityLog.clock(s.splitsS.last())}", style = MaterialTheme.typography.titleMedium)
        } else {
            Text("Keep your Fitbit on: its heart rate fills in your strain for this ${s.sport.label.lowercase()} afterwards.",
                style = MaterialTheme.typography.bodyMedium, color = C.Muted)
            Spacer(Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp)).background(C.Raised)
                .clickable { if (s.paused) RunTracker.resume(ctx) else RunTracker.pause(ctx) }, contentAlignment = Alignment.Center) {
                Text(if (s.paused) "Resume" else "Pause", style = MaterialTheme.typography.titleLarge)
            }
            Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(32.dp)).background(C.Stop).clickable { confirm = true },
                contentAlignment = Alignment.Center) {
                Text("Finish", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }
    }
}

/** The route's shape (no map tiles needed): north up, scaled to fit. */
@Composable
fun Route(points: List<RoutePoint>, color: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    val lat0 = points.map { it.lat }.average()
    val k = cos(Math.toRadians(lat0))
    val xs = points.map { it.lng * k }; val ys = points.map { it.lat }
    val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
    Canvas(modifier) {
        val span = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val sc = minOf(size.width, size.height) / span
        val ox = (size.width - (maxX - minX) * sc) / 2
        val oy = (size.height - (maxY - minY) * sc) / 2
        fun p(i: Int) = Offset((ox + (xs[i] - minX) * sc).toFloat(), (oy + (maxY - ys[i]) * sc).toFloat())
        for (i in 1 until points.size) drawLine(color, p(i - 1), p(i), 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(Plate.Green, 6.dp.toPx(), p(0))
        drawCircle(Plate.Red, 6.dp.toPx(), p(points.size - 1))
    }
}

// ---- a finished run ----------------------------------------------------------------------------

@Composable
fun RunDetail(r: Activity, item: ActivityItem?, onBack: () -> Unit, onDelete: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(r.startMs).atZone(zone)
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Delete this run?") }, text = { Text("It can't be undone.") },
        confirmButton = { TextButton(onClick = onDelete) { Text("Delete", color = C.Stop) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = C.Muted) } })
    Column(Modifier.fillMaxSize()) {
        TopBar("${r.sport.label}, %.2f km".format(r.distanceKm ?: 0.0), start.format(sDay) + ", " + start.format(sTime), onBack) {
            TextButton(onClick = { confirm = true }) { Text("Delete", color = C.Muted) }
        }
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (r.route.size >= 2) item {
                Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(22.dp)).background(C.Surface).padding(18.dp)) { Route(r.route, Plate.Blue) }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            BigStat("%.2f".format(r.distanceKm ?: 0.0), "km")
                            BigStat(ActivityLog.clock(r.durationS), "moving time")
                            BigStat(r.paceSPerKm?.let { ActivityLog.pace(it).removeSuffix(" /km") } ?: "–", "per km")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            BigStat(item?.avgHr?.toString() ?: "–", "avg heart rate")
                            BigStat(item?.maxHr?.toString() ?: "–", "max")
                            BigStat(item?.strain?.let { "%.1f".format(it) } ?: "–", "strain", StrainBlue)
                            climb(r.route)?.let { BigStat("${it}m", "climb") }
                        }
                        if (item?.avgHr == null) Text("Heart rate and strain fill in once the Fitbit syncs.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            }
            if (r.splitsS.isNotEmpty()) item {
                Column {
                    Text("Splits", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
                    Card(Modifier.fillMaxWidth()) {
                        val fastest = r.splitsS.min(); val slowest = r.splitsS.max()
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.splitsS.forEachIndexed { i, s ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("${i + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
                                    Box(Modifier.weight(1f).height(14.dp)) {
                                        val frac = 0.45f + 0.55f * (if (slowest == fastest) 1f else (slowest - s).toFloat() / (slowest - fastest))
                                        Box(Modifier.fillMaxWidth(frac).height(14.dp).clip(RoundedCornerShape(4.dp))
                                            .background(if (s == fastest) Plate.Green else Plate.Blue))
                                    }
                                    Text(ActivityLog.clock(s), style = MaterialTheme.typography.titleSmall.merge(Tnum), modifier = Modifier.width(64.dp).padding(start = 10.dp))
                                }
                            }
                            Text("Green is your fastest km.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                    }
                }
            }
        }
    }
}

private fun climb(route: List<RoutePoint>): Int? {
    val alts = route.mapNotNull { it.alt?.takeIf { a -> a != 0.0 } }
    if (alts.size < 10) return null
    var up = 0.0
    var ref = alts.first()
    for (a in alts) { if (a - ref > 3) { up += a - ref; ref = a } else if (a < ref) ref = a }   // 3 m hysteresis against GPS noise
    return up.roundToInt()
}

// ---- logging after the fact ---------------------------------------------------------------------

@Composable
fun LogActivityDialog(start: Sport, onDismiss: () -> Unit, onSave: (Sport, Long, Int, Double?, String) -> Unit) {
    var sport by remember { mutableStateOf(start) }
    var day by remember { mutableStateOf(0) }            // days ago
    var hour by remember { mutableStateOf(java.time.LocalTime.now().minusHours(1).hour) }
    var minute by remember { mutableStateOf(0) }
    var mins by remember { mutableStateOf(if (start == Sport.RUN) "55" else "60") }
    var km by remember { mutableStateOf(if (start == Sport.RUN) "10" else "") }
    var note by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    if (picking) TimeDialog(hour, minute, onDismiss = { picking = false }) { h, m -> hour = h; minute = m; picking = false }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text)
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text("Log an activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRowCompat(spacing = 6.dp) {
                    Sport.entries.filter { it != Sport.GYM }.forEach { s ->
                        Tag("${s.emoji} ${s.label}", color = if (s == sport) C.Accent else C.Text, modifier = Modifier.clickable { sport = s })
                    }
                }
                FlowRowCompat(spacing = 6.dp) {
                    listOf(0 to "Today", 1 to "Yesterday", 2 to "2 days ago").forEach { (d, l) ->
                        Tag(l, color = if (d == day) C.Accent else C.Text, modifier = Modifier.clickable { day = d })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Started at", color = C.Muted, modifier = Modifier.weight(1f))
                    Text("%d:%02d".format(hour, minute), style = MaterialTheme.typography.headlineSmall.merge(Tnum),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Raised).clickable { picking = true }.padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mins, { mins = it }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = colors)
                    if (sport in setOf(Sport.RUN, Sport.WALK, Sport.CYCLE, Sport.SWIM)) OutlinedTextField(km, { km = it }, label = { Text("km") }, singleLine = true,
                        modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = colors)
                }
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true, colors = colors)
                Text("Heart rate and strain come from your Fitbit for that time.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = (mins.toIntOrNull() ?: 0) > 0, onClick = {
                val t = LocalDate.now().minusDays(day.toLong()).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onSave(sport, t, mins.toInt(), km.replace(',', '.').toDoubleOrNull()?.takeIf { sport in setOf(Sport.RUN, Sport.WALK, Sport.CYCLE, Sport.SWIM) }, note.trim())
            }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}

@Composable
fun ActivityInfo(it: ActivityItem, onDismiss: () -> Unit, onDelete: (() -> Unit)?, onChange: ((Sport?) -> Unit)? = null) {
    val zone = ZoneId.systemDefault()
    var changing by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text("${it.sport.emoji} ${it.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(it.start.atZone(zone).format(sDay) + ", " + it.start.atZone(zone).format(sTime) + " to " + it.end.atZone(zone).format(sTime),
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                Text("${it.minutes} min" + (it.distanceKm?.let { k -> ", %.2f km".format(k) } ?: ""), style = MaterialTheme.typography.titleMedium)
                it.avgHr?.let { h -> Text("Average heart rate $h, max ${it.maxHr ?: "–"}", style = MaterialTheme.typography.bodyMedium) }
                it.strain?.let { s -> Text("Strain %.1f".format(s), style = MaterialTheme.typography.titleMedium, color = StrainBlue) }
                Text("From: ${it.source}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                if (onChange != null && it.sessionId == null) {
                    if (!changing) Text("Wrong type? Change it", style = MaterialTheme.typography.titleSmall, color = C.Accent,
                        modifier = Modifier.padding(top = 8.dp).clickable { changing = true })
                    else Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Text("What was it?", style = MaterialTheme.typography.titleSmall)
                        FlowRowCompat(spacing = 6.dp) {
                            Sport.entries.filter { s -> s != Sport.GYM }.forEach { s ->
                                Tag("${s.emoji} ${s.label}", color = if (s == it.sport) C.Accent else C.Text, modifier = Modifier.clickable { onChange(s) })
                            }
                        }
                        if (it.manualId == null) Text("Not an activity: hide it", style = MaterialTheme.typography.labelLarge, color = C.Stop,
                            modifier = Modifier.clickable { onChange(null) })
                        Text("Heart rate and strain stay the same; it just moves to the right sport.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = C.Accent) } },
        dismissButton = { onDelete?.let { d -> TextButton(onClick = d) { Text("Delete", color = C.Stop) } } },
    )
}
