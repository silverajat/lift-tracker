package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.HealthState
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.chat.Coach
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Energy
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.meals.MealEntry
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private val hm = DateTimeFormatter.ofPattern("H:mm")
private val dayHead = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val shortDay = DateTimeFormatter.ofPattern("d MMM")

// Metric colours: the plate colours, each with one meaning.
val Recover = Plate.Green
val Yellow = Plate.Yellow
val RedZ = Plate.Red
val StrainBlue = Plate.Blue
val SleepPurple = Plate.Chalk

fun zoneColor(z: Analysis.Zone?) = when (z) {
    Analysis.Zone.GREEN -> Recover
    Analysis.Zone.YELLOW -> Yellow
    Analysis.Zone.RED -> RedZ
    null -> C.Muted
}

/** Everything the Today tab shows (plain data, so it can be previewed and screenshot-tested). */
data class TodayUi(
    val health: HealthState,
    val analysis: Analysis.Result?,
    val todayHealth: HealthDay?,
    val syncedAt: Long?,
    val podReady: Boolean,
    val podSub: String,
    val food: List<MealEntry>,
    val goalKg: Double,
    val take: Coach.Take?,
    val taking: Boolean,
    val takeError: String?,
    val hasKey: Boolean,
    val date: LocalDate = LocalDate.now(),
    val now: LocalTime = LocalTime.now(),
    val items: List<com.silverajat.lifttracker.activity.ActivityItem> = emptyList(),
    val plan: com.silverajat.lifttracker.journal.DayPage? = null,
)

@Composable
fun TodayScreen(hub: HubViewModel, gym: AppViewModel, onConnectHealth: () -> Unit, onConnectPod: () -> Unit, onOpenGym: () -> Unit, onOpenNet: () -> Unit,
                journal: com.silverajat.lifttracker.journal.JournalViewModel? = null, onOpenDay: () -> Unit = {}) {
    val jPages = journal?.pages?.collectAsStateWithLifecycle()?.value
    val jToday = journal?.today?.collectAsStateWithLifecycle()?.value
    val state by hub.healthState.collectAsStateWithLifecycle()
    val a by hub.analysis.collectAsStateWithLifecycle()
    val days by hub.days.collectAsStateWithLifecycle()
    val synced by hub.syncedAt.collectAsStateWithLifecycle()
    val conn by gym.pod.conn.collectAsStateWithLifecycle()
    val status by gym.pod.status.collectAsStateWithLifecycle()
    val food by hub.todayEntries.collectAsStateWithLifecycle()
    val goal by hub.goal.collectAsStateWithLifecycle()
    val take by hub.take.collectAsStateWithLifecycle()
    val taking by hub.taking.collectAsStateWithLifecycle()
    val takeError by hub.takeError.collectAsStateWithLifecycle()
    val key by hub.apiKey.collectAsStateWithLifecycle()
    val items by hub.items.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.refreshHealth() }
    // last night's sleep often reaches Health Connect a while after you wake: keep checking every 2 min while it's missing
    val sleepMissing = a?.today?.sleep == null
    LaunchedEffect(sleepMissing) {
        while (sleepMissing && java.time.LocalTime.now().hour in 4..13) {
            kotlinx.coroutines.delay(120_000)
            hub.refreshHealth()
        }
    }
    var detail by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Detail?>(null) }
    var logging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var info by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.silverajat.lifttracker.activity.ActivityItem?>(null) }
    if (logging) LogActivityDialog(com.silverajat.lifttracker.activity.Sport.FOOTBALL, onDismiss = { logging = false }) { sp, st, m, km, note ->
        hub.addActivity(sp, st, m, km, note); logging = false
    }
    info?.let { it ->
        androidx.activity.compose.BackHandler { info = null }
        ActivityScreen(hub, it, onBack = { info = null })
        return
    }
    detail?.let { d ->
        androidx.activity.compose.BackHandler { detail = null }
        when (d) {
            Detail.RECOVERY -> RecoveryDetail(a, days, onBack = { detail = null })
            Detail.STRAIN -> StrainDetail(a, days, items, goal, onBack = { detail = null }, onLog = { logging = true }, onOpen = { info = it })
            Detail.SLEEP -> SleepDetail(a, days, onBack = { detail = null })
        }
        return
    }
    var keyDialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (keyDialog) SettingsDialog(hub) { keyDialog = false }
    var dataDialog by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (dataDialog) FitbitDataDialog(hub) { dataDialog = false }
    val ready = conn == PodConn.READY
    TodayContent(
        TodayUi(
            health = state, analysis = a, todayHealth = days.lastOrNull { it.date == LocalDate.now() }, syncedAt = synced,
            podReady = ready,
            podSub = if (ready) (status?.let { "${it.batteryPct}%" + if (it.charging) ", charging" else "" } ?: "connected") else "for the gym",
            food = food, goalKg = goal, take = take, taking = taking, takeError = takeError, hasKey = key.isNotEmpty(),
            items = items,
            plan = jToday?.let { d -> jPages?.get(d) ?: com.silverajat.lifttracker.journal.DayPage(d) },
        ),
        onOpenDay = onOpenDay, onTogglePlan = { id -> journal?.toggle(id) },
        onDetail = { detail = it },
        onFitbit = if (state == HealthState.OK) ({ dataDialog = true }) else onConnectHealth,
        onPod = onConnectPod, onOpenGym = onOpenGym, onOpenNet = onOpenNet,
        onRefreshTake = { hub.refreshTake(force = true) },
        onAddKey = { keyDialog = true },
    )
}

@Composable
fun TodayContent(
    u: TodayUi,
    onFitbit: () -> Unit = {}, onPod: () -> Unit = {}, onOpenGym: () -> Unit = {}, onOpenNet: () -> Unit = {}, onRefreshTake: () -> Unit = {},
    onAddKey: () -> Unit = {},
    onDetail: (Detail) -> Unit = {},
    onOpenDay: () -> Unit = {}, onTogglePlan: (String) -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val a = u.analysis
    val today = a?.today
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        // ---- devices + date ----
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DeviceChip("Fitbit Air", u.health == HealthState.OK, when (u.health) {
                        HealthState.OK -> u.syncedAt?.let { "synced " + Instant.ofEpochMilli(it).atZone(zone).format(hm) } ?: "synced"
                        HealthState.LOADING -> "syncing"
                        HealthState.NEEDS_PERMISSION -> "tap to connect"
                        HealthState.UNAVAILABLE -> "needs Health Connect"
                    }, onFitbit)
                    DeviceChip("Pod", u.podReady, u.podSub, onPod)
                    Box(Modifier.weight(1f))
                    TopActionIcons()
                }
                Text(u.date.format(dayHead), style = MaterialTheme.typography.titleMedium, color = C.Muted)
            }
        }

        if (u.health == HealthState.NEEDS_PERMISSION || u.health == HealthState.UNAVAILABLE) item {
            Card(Modifier.fillMaxWidth(), onClick = onFitbit, border = C.Accent.copy(alpha = 0.6f)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Connect your Fitbit", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (u.health == HealthState.UNAVAILABLE) "Health Connect isn't on this phone yet. Install it from the Play Store, then come back."
                        else "The Google Health app already copies your Fitbit's data into Health Connect. Allow this app to read it: heart rate, HRV, sleep, steps and workouts. It stays on your phone.",
                        style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                    )
                    Text("Connect", color = C.Accent, style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        item { Scoreboard(today, onDetail) }

        u.plan?.let { p -> item { PlanGlance(p, u.now, onOpenDay, onTogglePlan) } }

        item { CoachTake(u, a, onRefreshTake, onAddKey) }

        a?.days?.takeLast(14)?.takeIf { ds -> ds.any { it.recovery != null || it.strain != null || it.sleep != null } }?.let { ds ->
            section("Two weeks") {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        TrendRow("Recovery", ds.map { (it.recovery?.score ?: 0) / 100f }, ds.map { zoneColor(it.recovery?.zone) },
                            ds.lastOrNull()?.recovery?.let { "${it.score}%" })
                        TrendRow("Strain", ds.map { ((it.strain?.strain ?: 0.0) / 18.0).toFloat() }, ds.map { StrainBlue },
                            ds.lastOrNull()?.strain?.let { "%.1f".format(it.strain) })
                        // 5 h at the bottom, 9 h at the top, so a short night stands out
                        TrendRow("Sleep", ds.map { s -> s.sleep?.let { ((it.asleepMin - 300) / 240f).coerceIn(0.08f, 1f) } ?: 0f }, ds.map { SleepPurple.copy(alpha = 0.85f) },
                            ds.lastOrNull()?.sleep?.let { hmin(it.asleepMin) })
                        Text("${ds.first().date.format(shortDay)} to today", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            }
        }

        section("Activity today") {
            Card(Modifier.fillMaxWidth(), onClick = { onDetail(Detail.STRAIN) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        today?.strain?.let { st ->
                            Stat("Strain", "%.1f".format(st.strain), StrainBlue)
                            Stat("Hard minutes", "${st.moderateMin + st.vigorousMin}")
                        }
                        u.todayHealth?.steps?.let { Stat("Steps", "%,d".format(it)) }
                    }
                    val todays = u.items.filter { it.start.atZone(zone).toLocalDate() == u.date }
                    if (todays.isEmpty()) Text("Nothing logged yet today.", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                    todays.take(4).forEach { it ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${it.sport.emoji}  ${it.title}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${it.minutes} min" + (it.strain?.let { s -> ", %.1f".format(s) } ?: ""), style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = C.Muted)
                        }
                    }
                    Text("See strain and weekly load", style = MaterialTheme.typography.titleSmall, color = C.Accent)
                }
            }
        }

        a?.energy?.let { e ->
            section("Net calories") {
                val g = e.netFor(u.goalKg)
                val t = Energy.today(e, u.food, u.date, u.now)
                Card(Modifier.fillMaxWidth(), onClick = onOpenNet) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(signedKcal(t.endOfDay), style = MaterialTheme.typography.displaySmall.merge(Tnum), color = netColor(t.endOfDay, g))
                            Text("  today, goal ${signedKcal(g)}", style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                                modifier = Modifier.padding(bottom = 6.dp))
                        }
                        e.avgNet?.let { n ->
                            Text("Two-week average ${signedKcal(n)} a day" + (e.trendKgPerWeek?.let { k -> ", scale ${"%+.2f".format(k)} kg a week" } ?: "") + ".",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(if (e.confidence >= 30) "Burn checked against your weight: the Fitbit runs about ${abs(e.fitbitOffPct)}% ${if (e.fitbitOffPct >= 0) "high" else "low"}."
                            else "Burn gets checked against your weight after about 2 weeks of weigh-ins.",
                            style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                }
            }
        }

    }
}

/** A titled block in the Today list. */
private fun LazyListScope.section(title: String, content: @Composable () -> Unit) {
    item {
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
}

/** Recovery as a huge scoreboard number, strain and sleep beside it. */
@Composable
private fun Scoreboard(d: Analysis.Day?, onDetail: (Detail) -> Unit) {
    val r = d?.recovery
    val color = zoneColor(r?.zone)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1.25f).clickable { onDetail(Detail.RECOVERY) }) {
            Text("Recovery", style = MaterialTheme.typography.titleSmall, color = C.Muted)
            if (r != null) Row(verticalAlignment = Alignment.Bottom) {
                Text(r.score.toString(), style = MaterialTheme.typography.displayLarge.merge(Tnum), color = color)
                Text("%", style = MaterialTheme.typography.displaySmall, color = color, modifier = Modifier.padding(bottom = 14.dp))
            } else Text("No score yet", style = MaterialTheme.typography.headlineMedium, color = C.Muted, modifier = Modifier.padding(vertical = 18.dp))
            PlateBar(r?.score, color)
            Text(r?.zone?.label ?: "Needs 5 nights of Fitbit data", style = MaterialTheme.typography.titleSmall,
                color = if (r == null) C.Muted else color, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(C.Line))
        Column(Modifier.weight(1f).padding(start = 18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.clickable { onDetail(Detail.STRAIN) }) {
                Text("Strain", style = MaterialTheme.typography.titleSmall, color = C.Muted)
                Text(d?.strain?.let { "%.1f".format(it.strain) } ?: "Not yet", style = if (d?.strain == null) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displayMedium.merge(Tnum), color = if (d?.strain == null) C.Muted else StrainBlue)
                Text(d?.strain?.target?.let { "aim ${it.start.toInt()} to ${it.endInclusive.toInt()}" } ?: "out of 21",
                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
            Column(Modifier.clickable { onDetail(Detail.SLEEP) }) {
                Text("Sleep", style = MaterialTheme.typography.titleSmall, color = C.Muted)
                Text(d?.sleep?.let { hmin(it.asleepMin) } ?: "Not yet", style = if (d?.sleep == null) MaterialTheme.typography.headlineSmall
                    else MaterialTheme.typography.displaySmall.merge(Tnum), color = if (d?.sleep == null) C.Muted else SleepPurple)
                Text(d?.sleep?.let { "${it.performance}% of what you needed" } ?: if (java.time.LocalTime.now().hour in 4..13) "waiting on Fitbit" else "from your Fitbit", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
    }
}

/** Five plate-like segments, filled up to the score (20 points each). */
@Composable
private fun PlateBar(score: Int?, color: Color) {
    Row(Modifier.padding(top = 4.dp, end = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { i ->
            val fill = ((score ?: 0) - i * 20).coerceIn(0, 20) / 20f
            Canvas(Modifier.weight(1f).height(10.dp)) {
                drawRoundRect(C.Raised, cornerRadius = CornerRadius(3.dp.toPx()))
                if (fill > 0) drawRoundRect(color, size = Size(size.width * fill, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
            }
        }
    }
}

@Composable
private fun CoachTake(u: TodayUi, a: Analysis.Result?, onRefresh: () -> Unit, onAddKey: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(C.Accent))
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Coach's take", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (u.hasKey) Text(if (u.taking) "Thinking" else "Refresh", style = MaterialTheme.typography.labelLarge,
                    color = if (u.taking) C.Muted else C.Accent, modifier = Modifier.clickable(enabled = !u.taking, onClick = onRefresh).padding(4.dp))
            }
            val take = u.take?.takeIf { it.date == u.date }
            if (take != null) TakeText(take.text)
            else {
                val lines = a?.headline.orEmpty()
                if (lines.isEmpty()) Text("Log a few days of food, weight and workouts and this fills in.",
                    style = MaterialTheme.typography.bodyLarge, color = C.Muted)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
            u.takeError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = RedZ) }
        }
    }
}

/** GPT's take: "- " lines become bullets, a line ending in ":" becomes a small heading. */
@Composable
private fun TakeText(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val bullet = line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")
            when {
                bullet -> Row {
                    Box(Modifier.padding(top = 9.dp, end = 10.dp).size(5.dp).clip(CircleShape).background(C.Accent))
                    Text(line.drop(2).replace("**", ""), style = MaterialTheme.typography.bodyLarge)
                }
                line.endsWith(":") -> Text(line.removeSuffix(":"), style = MaterialTheme.typography.titleSmall, color = C.Accent,
                    modifier = Modifier.padding(top = 4.dp))
                else -> Text(line.replace("**", ""), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = C.Text) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineSmall.merge(Tnum), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun TrendRow(label: String, values: List<Float>, colors: List<Color>, now: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            now?.let { Text(it, style = MaterialTheme.typography.titleSmall.merge(Tnum), color = C.Muted) }
        }
        Bars(values, colors)
    }
}

fun hmin(m: Int) = "${m / 60}h ${m % 60}m"

@Composable
private fun DeviceChip(name: String, ok: Boolean, sub: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(C.Surface)
            .border(1.dp, if (ok) Recover.copy(alpha = 0.45f) else C.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Recover else C.Muted))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = C.Muted)
        }
    }
}

@Composable
private fun Bars(values: List<Float>, colors: List<Color>) {
    Canvas(Modifier.fillMaxWidth().height(36.dp)) {
        val n = values.size
        if (n == 0) return@Canvas
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        values.forEachIndexed { i, v ->
            val empty = v <= 0f
            val h = if (empty) 3.dp.toPx() else (size.height * v.coerceIn(0f, 1f)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(if (empty) C.Raised else colors[i], Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
        }
    }
}

/** What's actually arriving from the Fitbit through Health Connect, and how to fix what isn't. */
@Composable
fun FitbitDataDialog(hub: HubViewModel, onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var checks by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<com.silverajat.lifttracker.health.HealthRepo.DataCheck>?>(null) }
    var round by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(round) {
        checks = null
        checks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { hub.health.check() }.getOrDefault(emptyList()) }
    }
    val zone = ZoneId.systemDefault()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Fitbit data, last 3 days") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val list = checks
                if (list == null) Text("Checking…", color = C.Muted)
                else list.forEach { c ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(when {
                            !c.allowed -> RedZ; c.count == 0 -> Yellow; else -> Recover }))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(c.name, style = MaterialTheme.typography.titleSmall)
                            Text(when {
                                !c.allowed -> "Not allowed: tap Health Connect below and allow it for Spotter"
                                c.count == 0 -> "Nothing in 3 days"
                                else -> "${c.count}${if (c.more) "+" else ""} readings, latest " +
                                    (c.latest?.atZone(zone)?.let { t -> (if (t.toLocalDate() == LocalDate.now()) "" else t.format(shortDay) + " ") + t.format(hm) } ?: "?") +
                                    ", from " + c.sources.joinToString(", ")
                            }, style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                    }
                }
                Text("If something says nothing: open the Google Health app, go to your profile, then Settings, then Health Connect, " +
                    "and turn sharing on for everything. Then open Google Health once so the watch syncs, and tap Check again.",
                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { round++; hub.refreshHealth(force = true) }) { Text("Check again", color = C.Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = {
                runCatching { ctx.startActivity(android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS")) }
                    .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS)) } }
            }) { Text("Health Connect", color = C.Muted) }
        },
    )
}


/** Today's main 3 from the Day tab: tick them here, tap the card to open the full plan. */
@Composable
private fun PlanGlance(p: com.silverajat.lifttracker.journal.DayPage, now: LocalTime, onOpen: () -> Unit, onToggle: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Today's 3", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (p.mains.isNotEmpty()) Text("${p.mains.count { it.done }}/${p.mains.size}", style = MaterialTheme.typography.titleMedium.merge(Tnum), color = C.Muted)
            }
            if (p.mains.isEmpty()) Text("Set your 3 main things for today", style = MaterialTheme.typography.bodyMedium, color = C.Accent)
            p.mains.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (t.done) C.Accent else C.Bg)
                        .border(1.5.dp, if (t.done) C.Accent else C.Line, androidx.compose.foundation.shape.CircleShape)
                        .clickable { onToggle(t.id) }, contentAlignment = Alignment.Center) {
                        if (t.done) Text("✓", color = C.Bg, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(t.text, style = MaterialTheme.typography.bodyLarge, color = if (t.done) C.Muted else C.Text,
                        textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (p.mains.isNotEmpty() && (now.hour >= 18 || now.hour < 4) && !p.reflected)
                Text("Tick off and reflect", style = MaterialTheme.typography.titleSmall, color = C.Accent)
        }
    }
}
