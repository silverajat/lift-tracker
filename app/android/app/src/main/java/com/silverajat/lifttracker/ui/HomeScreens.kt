package com.silverajat.lifttracker.ui

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
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.Stats
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
fun HomeScreen(vm: AppViewModel, onConnect: () -> Unit, onExport: (Session) -> Unit) {
    val conn by vm.pod.conn.collectAsStateWithLifecycle()
    val status by vm.pod.status.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()

    var toDelete by remember { mutableStateOf<Session?>(null) }
    var showAll by remember { mutableStateOf(false) }
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
                    Text(dayFmt.format(Date()).uppercase(), style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    Text(greeting(), style = MaterialTheme.typography.headlineLarge)
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(C.UpperA, C.UpperB))),
                    contentAlignment = Alignment.Center,
                ) { Text("R", style = MaterialTheme.typography.titleLarge, color = Color.White) }
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
        item { SectionLabel("Start a workout", Modifier.padding(top = 6.dp)) }
        items(Day.entries.toList()) { day ->
            val last = history.firstOrNull { it.day == day && it.id != session?.id && it.sets.isNotEmpty() }
            DayCard(
                day = day,
                live = session?.takeIf { it.day == day },
                last = last,
                onClick = { vm.open(Screen.Workout(day)) },
            )
        }
        // Every past workout (unfinished ones too), newest first, each with delete.
        val past = history.filter { it.id != session?.id && (it.sets.isNotEmpty() || it.endMs == null) }
        if (past.isNotEmpty()) {
            item { SectionLabel("History · ${past.size} workout${if (past.size > 1) "s" else ""}", Modifier.padding(top = 6.dp)) }
            items(if (showAll) past else past.take(8), key = { it.id }) { s ->
                RecentRow(s, onExport = { onExport(s) }, onDelete = { toDelete = s })
            }
            if (past.size > 8) {
                item {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAll) "Show fewer" else "Show all ${past.size}", color = C.Muted)
                    }
                }
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
private fun DayCard(day: Day, live: Session?, last: Session?, onClick: () -> Unit) {
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
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White) }
            }
            Text(groups, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("${exercises.size} exercises")
                Spacer(Modifier.width(8.dp))
                Pill(day.usual)
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
private fun RecentRow(s: Session, onExport: () -> Unit, onDelete: () -> Unit) {
    val color = if (s.day == Day.UPPER) C.UpperA else C.LowerA
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${s.day.title} day", style = MaterialTheme.typography.titleMedium)
            val dur = s.endMs?.let { elapsed(it - s.startMs) } ?: "not finished"
            val q = s.sets.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()?.toInt()
            Text(
                "${dayFmt.format(Date(s.startMs))}, ${timeFmt.format(Date(s.startMs))} · $dur · ${s.sets.size} sets" +
                    (q?.let { " · quality $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
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
