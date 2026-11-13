package com.silverajat.lifttracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.insights.Kind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val whenFmt = DateTimeFormatter.ofPattern("EEE d MMM, H:mm")
private val timeFmt = DateTimeFormatter.ofPattern("H:mm")

private fun kindColor(k: Kind): Color = when (k) {
    Kind.WORKOUT -> C.Accent
    Kind.MORNING -> Recover
    Kind.ACTIVITY -> StrainBlue
    Kind.FOOD -> C.Warm
    Kind.WEATHER -> C.Pod
    Kind.WEEKLY -> C.Violet
    Kind.NIGHT -> SleepPurple
}

private fun kindLabel(k: Kind) = when (k) {
    Kind.WORKOUT -> "Workout"; Kind.MORNING -> "Morning"; Kind.ACTIVITY -> "Activity"; Kind.FOOD -> "Net"
    Kind.NIGHT -> "Wrap-up"; Kind.WEATHER -> "Weather"; Kind.WEEKLY -> "Week"
}

/** Every summary the app has sent (Fitbit-style), plus the meal-plan brainstorm. */
@Composable
fun InsightsScreen(hub: HubViewModel) {
    var chat by rememberSaveable { mutableStateOf(false) }
    if (chat) {
        BackHandler { chat = false }
        ChatScreen(hub, onBack = { chat = false })
        return
    }
    val list by hub.insights.collectAsStateWithLifecycle()
    val on by hub.notifyOn.collectAsStateWithLifecycle()
    val checking by hub.checking.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Insights", style = MaterialTheme.typography.headlineMedium)
            Text("Summaries after workouts and walks, a morning brief with the weather, an evening net-calorie check, a 9:45 PM wrap-up of your day and a Monday review.",
                color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Notifications", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Switch(on, { hub.setNotifyOn(it) }, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent))
                    }
                    Text("Mute single kinds in Android settings (each kind is its own channel).", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    TextButton(onClick = { hub.checkInsights(force = true) }, enabled = !checking) {
                        Text(if (checking) "Checking…" else "Send me a summary now", color = C.Accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), onClick = { chat = true }) {
                Column {
                    Text("Brainstorm your meal plan", style = MaterialTheme.typography.titleMedium)
                    Text("Chat with GPT about changing the plan. It sees your food log, weight trend, training and Fitbit data.",
                        color = C.Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (list.isEmpty()) item {
            Text("Nothing yet. Finish a workout, or tap \"Send me a summary now\".", color = C.Muted)
        }
        items(list, key = { it.key }) { i ->
            val t = Instant.ofEpochMilli(i.timeMs).atZone(zone)
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tag(kindLabel(i.kind), color = kindColor(i.kind))
                        Spacer(Modifier.width(8.dp))
                        Text(if (t.toLocalDate() == today) "Today " + t.format(timeFmt) else t.format(whenFmt),
                            style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                    Text(i.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                    Text(i.body, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
