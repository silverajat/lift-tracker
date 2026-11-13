package com.silverajat.lifttracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
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

/** Every summary the app has sent (Fitbit-style), plus Brainstorm (GPT). */
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
    val key by hub.apiKey.collectAsStateWithLifecycle()
    var keyDialog by rememberSaveable { mutableStateOf(false) }
    if (keyDialog) SettingsDialog(hub) { keyDialog = false }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Insights", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            Card(Modifier.fillMaxWidth(), onClick = { chat = true }) {
                Column {
                    Text("Brainstorm", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        if (list.isEmpty()) item {
            Text("Nothing yet. Finish a workout, or tap \"Send me a summary now\".", color = C.Muted)
        }
        items(list, key = { it.key }) { i -> InsightCard(i, today) }
    }
}

/** One summary: a coloured edge for its kind, the title, the words. */
@Composable
fun InsightCard(i: com.silverajat.lifttracker.insights.Insight, today: LocalDate) {
    val t = Instant.ofEpochMilli(i.timeMs).atZone(ZoneId.systemDefault())
    Row(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(C.Surface)
        .height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        androidx.compose.foundation.layout.Box(Modifier.width(4.dp).fillMaxHeight().background(kindColor(i.kind)))
        Column(Modifier.padding(start = 16.dp, end = 18.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(kindLabel(i.kind) + ", " + (if (t.toLocalDate() == today) "today " + t.format(timeFmt) else t.format(whenFmt)),
                style = MaterialTheme.typography.labelMedium, color = C.Muted)
            Text(i.title, style = MaterialTheme.typography.titleLarge)
            Text(i.body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
