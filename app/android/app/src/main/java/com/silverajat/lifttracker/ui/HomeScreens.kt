package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import kotlin.math.roundToInt
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.Screen
import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.GymPlan
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.Stats
import com.silverajat.lifttracker.health.MuscleLoad
import java.time.DayOfWeek
import java.time.LocalDate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val timeFmt = SimpleDateFormat("H:mm", Locale.getDefault())

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
fun HomeScreen(vm: AppViewModel, onConnect: () -> Unit, onExport: (Session) -> Unit, strength: List<com.silverajat.lifttracker.health.Analysis.ExerciseTrend> = emptyList()) {
    val conn by vm.pod.conn.collectAsStateWithLifecycle()
    val status by vm.pod.status.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    var editPlan by remember { mutableStateOf(false) }
    if (editPlan) PlanDialog(plan, onSave = { vm.setPlan(it); editPlan = false }, onDismiss = { editPlan = false })
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now()
    // muscular strain for every session, and for today's (all of today's sessions together)
    val lift = remember(history) { history.filter { it.sets.isNotEmpty() }.associate { it.id to MuscleLoad.score(it, history) } }
    val todayLift = remember(history) { MuscleLoad.byDay(history, zone)[today] }

    var toDelete by remember { mutableStateOf<Session?>(null) }
    var showAll by remember { mutableStateOf(false) }
    var weeksBack by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    toDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            containerColor = C.Surface,
            title = { Text("Delete this workout?") },
            text = {
                Text("${s.day.title} day, ${dayFmt.format(Date(s.startMs))}: ${s.sets.size} sets. " +
                    "This can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteWorkout(s.id); toDelete = null }) {
                    Text("Delete", color = C.Stop, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep", color = C.Muted) } },
        )
    }

    val weekAgo = System.currentTimeMillis() - 7 * 24 * 3600_000L
    val week = history.filter { it.startMs >= weekAgo && it.sets.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dayFmt.format(Date()), style = MaterialTheme.typography.titleSmall, color = C.Muted)
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge)
                }
            }
        }
        item { PodBar(conn, status, onConnect) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Workouts", "${week.size}", "this week", Modifier.weight(1f))
                StatTile("Sets", "${week.sumOf { it.sets.size }}", "this week", Modifier.weight(1f))
                StatTile(
                    "Volume",
                    Stats.fmt(week.sumOf { s -> s.sets.sumOf { it.weight * it.reps } } / 1000.0),
                    "tonnes", Modifier.weight(1f),
                )
            }
        }
        item { PlanCard(plan, today, todayLift, onChange = { editPlan = true }) }
        item { SectionLabel("Start a workout", Modifier.padding(top = 6.dp)) }
        // today's planned day first
        val planned = plan[today.dayOfWeek]
        items(Day.entries.sortedBy { if (it == planned) 0 else 1 }) { day ->
            val last = history.firstOrNull { it.day == day && it.id != session?.id && it.sets.isNotEmpty() }
            DayCard(
                day = day,
                live = session?.takeIf { it.day == day },
                last = last,
                today = day == planned,
                onClick = { vm.open(Screen.Workout(day)) },
            )
        }
        // Strength: estimated 1RM per lift and what to do next session
        if (strength.isNotEmpty()) {
            item { SectionLabel("Strength", Modifier.padding(top = 6.dp)) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        strength.forEachIndexed { i, e ->
                            if (i > 0) androidx.compose.material3.HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 12.dp))
                            Row(Modifier.clickable { vm.open(Screen.ExerciseInfo(e.exerciseId, fromHome = true)) }, verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                                    Text(e.suggestion, style = MaterialTheme.typography.bodyMedium)
                                    Text("Last: ${e.lastSets.substringBefore(" (")}", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("${e.e1rmNow.roundToInt()} kg", style = MaterialTheme.typography.headlineSmall.merge(Tnum))
                                    e.changePct?.let {
                                        Text("${if (it >= 0) "+" else ""}${"%.1f".format(it)}%", style = MaterialTheme.typography.labelLarge.merge(Tnum),
                                            color = if (it >= 0) C.Done else C.Stop)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Past workouts one week (Mon-Sun) at a time, newest first, each with delete.
        val allPast = history.filter { it.id != session?.id && (it.sets.isNotEmpty() || it.endMs == null) }
        if (allPast.isNotEmpty()) {
            val monday = java.time.LocalDate.now().minusWeeks(weeksBack.toLong()).with(java.time.DayOfWeek.MONDAY)
            val past = allPast.filter {
                val d = java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                !d.isBefore(monday) && d.isBefore(monday.plusDays(7))
            }
            item {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("History", Modifier.weight(1f))
                    Text("‹", style = MaterialTheme.typography.headlineSmall, color = C.Text,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable { weeksBack++ }.padding(horizontal = 12.dp))
                    Text(when (weeksBack) { 0 -> "This week"; 1 -> "Last week"
                        else -> "Week of " + monday.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) },
                        style = MaterialTheme.typography.titleSmall)
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = if (weeksBack > 0) C.Text else C.Line,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = weeksBack > 0) { weeksBack-- }.padding(horizontal = 12.dp))
                }
            }
            if (past.isEmpty()) item { Text("No workouts this week.", style = MaterialTheme.typography.bodyMedium, color = C.Muted) }
            items(past, key = { it.id }) { s ->
                RecentRow(s, lift[s.id]?.strain, onOpen = { vm.open(Screen.Past(s.id)) }, onExport = { onExport(s) }, onDelete = { toDelete = s })
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.Muted)
        Text(value, style = MaterialTheme.typography.headlineMedium.merge(Tnum))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
    }
}

@Composable
private fun DayCard(day: Day, live: Session?, last: Session?, today: Boolean = false, onClick: () -> Unit) {
    val exercises = Routines.forDay(day)
    val groups = exercises.map { Routines.group(it).label }.distinct().joinToString(" · ")
    val brush = if (day == Day.UPPER) Brush.linearGradient(listOf(C.UpperA, C.UpperB))
    else Brush.linearGradient(listOf(C.LowerA, C.LowerB))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(brush)
            .clickable(onClick = onClick).padding(22.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(day.title.uppercase(), style = MaterialTheme.typography.displayMedium, color = Color.White,
                    modifier = Modifier.weight(1f))
                Text(
                    if (live != null) "Resume" else "Start",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (day == Day.UPPER) C.UpperB else C.LowerB,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White).padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            Text(groups, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (today) { Pill("Today"); Spacer(Modifier.width(8.dp)) }
                Pill("${exercises.size} exercises")
            }
            Spacer(Modifier.height(10.dp))
            if (live != null) {
                Text(
                    "● In progress · ${live.sets.size} sets done",
                    style = MaterialTheme.typography.labelLarge, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.28f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            } else {
                Text(
                    last?.let { "Last: ${dayFmt.format(Date(it.startMs))} · ${it.sets.size} sets" }
                        ?: "Last numbers loaded from your log",
                    style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun RecentRow(s: Session, strain: Double?, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    val color = if (s.day == Day.UPPER) C.UpperA else C.LowerA
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.day.title} day", style = MaterialTheme.typography.titleMedium)
            val dur = s.endMs?.let { elapsed(it - s.startMs) } ?: "not finished"
            val q = s.sets.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            Text(
                "${dayFmt.format(Date(s.startMs))}, ${timeFmt.format(Date(s.startMs))}, $dur, ${s.sets.size} sets" +
                    (q?.let { ", quality $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        strain?.let {
            Column(horizontalAlignment = Alignment.End) {
                Text("%.1f".format(it), style = MaterialTheme.typography.titleLarge.merge(Tnum), color = StrainBlue)
                Text("muscle", style = MaterialTheme.typography.labelSmall, color = C.Muted)
            }
        }
        IconButton(onClick = onExport) { Icon(Icons.Filled.Share, "Share", tint = C.Muted) }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.DeleteOutline, "Delete", tint = C.Muted) }
    }
}

@Composable
fun TopBar(title: String, subtitle: String?, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.Text) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = C.Muted, maxLines = 1)
            }
        }
        trailing()
    }
}


private fun DayOfWeek.short() = name.take(1) + name.substring(1, 3).lowercase()

/** What today is in your week, the week at a glance, and today's muscular strain if you've trained. */
@Composable
fun PlanCard(week: Map<DayOfWeek, Day?>, today: LocalDate, todayLift: MuscleLoad.SessionLoad?, onChange: () -> Unit) {
    val planned = week[today.dayOfWeek]
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Today", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    Text(if (planned != null) "${planned.title} day" else "Rest day", style = MaterialTheme.typography.headlineSmall,
                        color = when (planned) { Day.UPPER -> C.UpperA; Day.LOWER -> C.LowerA; null -> C.Text })
                    if (planned == null) {
                        GymPlan.next(week, today)?.let { (d, day) ->
                            Text("Next: ${day.title}, " + (if (d == today.plusDays(1)) "tomorrow" else d.dayOfWeek.short()),
                                style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        }
                    }
                }
                Text("Change", style = MaterialTheme.typography.labelLarge, color = C.Accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onChange).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayOfWeek.entries.forEach { d ->
                    val day = week[d]
                    val isToday = d == today.dayOfWeek
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (isToday) C.Raised else Color.Transparent).padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(d.short(), style = MaterialTheme.typography.labelSmall, color = if (isToday) C.Text else C.Muted)
                        Text(when (day) { Day.UPPER -> "U"; Day.LOWER -> "L"; null -> "–" }, style = MaterialTheme.typography.titleMedium,
                            color = when (day) { Day.UPPER -> C.UpperA; Day.LOWER -> C.LowerA; null -> C.Line })
                    }
                }
            }
            todayLift?.let { l ->
                androidx.compose.material3.HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Muscular strain", style = MaterialTheme.typography.titleSmall)
                        Text(muscleVerdict(l.strain), style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                    }
                    Text("%.1f".format(l.strain), style = MaterialTheme.typography.displaySmall.merge(Tnum), color = StrainBlue)
                }
                val total = l.byGroup.values.sum().takeIf { it > 0 }
                if (total != null) {
                    Spacer(Modifier.height(10.dp))
                    l.byGroup.entries.sortedByDescending { it.value }.take(4).forEach { (g, v) ->
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.label, style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.width(76.dp))
                            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.Raised)) {
                                Box(Modifier.fillMaxWidth((v / total).toFloat().coerceIn(0.02f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(StrainBlue))
                            }
                            Text("${(v / total * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted,
                                modifier = Modifier.width(40.dp).padding(start = 8.dp))
                        }
                    }
                }
            }
        }
    }
}

fun muscleVerdict(strain: Double) = when {
    strain >= 16 -> "Brutal session. Eat and sleep like you mean it"
    strain >= 12 -> "Hard session, right where it should be"
    strain >= 8 -> "Solid, moderate session"
    else -> "Light session"
}

@Composable
private fun PlanDialog(week: Map<DayOfWeek, Day?>, onSave: (Map<DayOfWeek, Day?>) -> Unit, onDismiss: () -> Unit) {
    var edit by remember { mutableStateOf(week) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Your week") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DayOfWeek.entries.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(d.short(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(44.dp))
                        listOf<Pair<String, Day?>>("Upper" to Day.UPPER, "Lower" to Day.LOWER, "Rest" to null).forEach { (label, v) ->
                            val on = edit[d] == v
                            val col = when (v) { Day.UPPER -> C.UpperA; Day.LOWER -> C.LowerA; null -> C.Muted }
                            Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else C.Muted,
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50))
                                    .background(if (on) col else C.Raised)
                                    .clickable { edit = edit + (d to v) }.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(edit) }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = {
            Row {
                TextButton(onClick = { edit = GymPlan.DEFAULT }) { Text("Default", color = C.Muted) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) }
            }
        },
    )
}
