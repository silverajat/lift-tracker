package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.Screen
import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Routines
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dayFmt = SimpleDateFormat("EEE d MMM", Locale.getDefault())
private val timeFmt = SimpleDateFormat("H:mm", Locale.getDefault())

@Composable
fun HomeScreen(vm: AppViewModel, onConnect: () -> Unit) {
    val conn by vm.pod.conn.collectAsStateWithLifecycle()
    val status by vm.pod.status.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 8.dp, bottom = 6.dp)) {
                SectionLabel(dayFmt.format(Date()))
                Text("Lift Tracker", style = MaterialTheme.typography.headlineLarge)
            }
        }
        item { PodBar(conn, status, onConnect) }
        item { SectionLabel("Pick today's day", Modifier.padding(top = 10.dp)) }
        items(Day.entries.toList()) { day ->
            val last = history.firstOrNull { it.day == day && it.id != session?.id }
            val live = session?.takeIf { it.day == day }
            DayCard(
                day = day,
                count = Routines.forDay(day).size,
                lastText = last?.let { s ->
                    val end = s.endMs?.let { " – " + timeFmt.format(Date(it)) } ?: ""
                    "Last: ${dayFmt.format(Date(s.startMs))}, ${timeFmt.format(Date(s.startMs))}$end"
                } ?: "Usually ${day.usual}",
                liveText = live?.let { "In progress · ${it.sets.size} sets" },
                onClick = { vm.open(Screen.DayList(day)) },
            )
        }
    }
}

@Composable
private fun DayCard(day: Day, count: Int, lastText: String, liveText: String?, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (liveText != null) C.Accent else C.Line,
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(day.title.uppercase(), style = MaterialTheme.typography.displayMedium)
                Text("$count exercises", style = MaterialTheme.typography.titleMedium, color = C.Text)
                Spacer(Modifier.height(6.dp))
                if (liveText != null) Tag(liveText, color = C.OnAccent, fill = C.Accent)
                else Text(lastText, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
            }
            Icon(Icons.Filled.ChevronRight, null, tint = C.Muted, modifier = Modifier.size(32.dp))
        }
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
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = C.Muted, maxLines = 1)
            }
        }
        trailing()
    }
}

@Composable
fun DayScreen(vm: AppViewModel, day: Day) {
    val session by vm.session.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val restEndsAt by vm.restEndsAt.collectAsStateWithLifecycle()
    val restS by vm.restSeconds.collectAsStateWithLifecycle()
    val conn by vm.pod.conn.collectAsStateWithLifecycle()
    val status by vm.pod.status.collectAsStateWithLifecycle()
    val live = session?.takeIf { it.day == day }
    var confirmFinish by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopBar(
            title = "${day.title} day",
            subtitle = live?.let { "Workout ${elapsed(now - it.startMs)} · ${it.sets.size} sets" }
                ?: "Pick an exercise to start",
            onBack = { vm.back() },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { PodBar(conn, status) { vm.pod.connect() } }
            restEndsAt?.let { end ->
                item { RestBanner(end, restS, now, onAdd = { vm.addRest(15) }, onSkip = { vm.skipRest() }) }
            }
            val exercises = Routines.forDay(day)
            items(exercises, key = { it.id }) { ex ->
                val done = live?.sets?.filter { it.exerciseId == ex.id } ?: emptyList()
                val plan = vm.lastTime(ex)
                val complete = done.size >= plan.size
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    border = if (complete) C.Accent.copy(alpha = 0.6f) else C.Line,
                    onClick = { vm.open(Screen.Lift(ex.id)) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp).clip(CircleShape)
                                .background(if (complete) C.Accent else C.Raised),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (complete) Icon(Icons.Filled.Check, null, tint = C.OnAccent)
                            else Text("${exercises.indexOf(ex) + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ex.name, style = MaterialTheme.typography.titleMedium)
                            if (ex.detail.isNotEmpty()) {
                                Text(ex.detail, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                            }
                            Spacer(Modifier.height(8.dp))
                            SetChips(plan, ex.bodyweight)
                        }
                        if (done.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${done.size}/${plan.size}",
                                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                                color = if (complete) C.Accent else C.Text,
                            )
                        }
                    }
                }
            }
        }
        if (live != null) {
            Button(
                onClick = { confirmFinish = true },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.Raised, contentColor = C.Text),
            ) { Text("Finish workout", style = MaterialTheme.typography.titleMedium) }
        }
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = C.Surface,
            title = { Text("Finish workout?") },
            text = {
                Text("${live?.sets?.size ?: 0} sets in ${elapsed(now - (live?.startMs ?: now))}. " +
                    "This saves it and becomes \"last time\" for next ${day.title.lowercase()} day.")
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finishWorkout() }) {
                    Text("Finish", color = C.Accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) { Text("Keep going", color = C.Muted) }
            },
        )
    }
}
