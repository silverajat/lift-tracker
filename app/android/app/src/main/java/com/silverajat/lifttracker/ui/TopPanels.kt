package com.silverajat.lifttracker.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.garden.GardenViewModel
import com.silverajat.lifttracker.insights.InsightEngine
import com.silverajat.lifttracker.insights.Kind

/** Top-right: notifications (with a dot for new ones) and settings. */
@Composable
fun TopIcons(panel: String?, unread: Int, onPanel: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 6.dp).height(44.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Box {
            TopIcon(Icons.Filled.Notifications, "Notifications", panel == "notifications") { onPanel("notifications") }
            if (unread > 0) Box(Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 10.dp).size(9.dp).clip(CircleShape).background(C.Stop))
        }
        TopIcon(Icons.Filled.Settings, "Settings", panel == "settings") { onPanel("settings") }
    }
}

@Composable
private fun TopIcon(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = if (on) C.Text else C.Muted) }
}

/** Everything you can switch on and off. */
@Composable
fun SettingsScreen(hub: HubViewModel) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("hub", Context.MODE_PRIVATE) }
    val notifyOn by hub.notifyOn.collectAsStateWithLifecycle()
    val key by hub.apiKey.collectAsStateWithLifecycle()
    var aiDialog by remember { mutableStateOf(false) }
    if (aiDialog) SettingsDialog(hub) { aiDialog = false }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineLarge) }
        item {
            Group("Notifications") {
                Toggle("Notifications", null, notifyOn) { hub.setNotifyOn(it) }
                if (notifyOn) {
                    Kind.entries.forEach { k -> PrefToggle(prefs, "notify_off_${k.name}", k.title, k.about, inverted = true) }
                    HorizontalDivider(color = C.Line)
                    PrefToggle(prefs, InsightEngine.GPT_VOICE, "Write them with GPT",
                        if (key.isEmpty()) "Needs your OpenAI key (below)" else "Fresh wording every time, same numbers")
                }
            }
        }
        item {
            Group("Focus timer") {
                PrefToggle(prefs, GardenViewModel.PAUSE_AWAY, "Pause while I'm in other apps", "Locking the phone doesn't pause it")
            }
        }
        item {
            Group("AI and body") {
                Column(Modifier.fillMaxWidth().clickable { aiDialog = true }.padding(vertical = 4.dp)) {
                    Text("OpenAI key, model and max heart rate", style = MaterialTheme.typography.titleMedium)
                    Text(if (key.isEmpty()) "No key yet: Brainstorm (Productivity, Notes), Coach's take and GPT notifications need one" else "Key saved",
                        style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Card(Modifier.fillMaxWidth()) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { content() } }
    }
}

/** A switch stored in the app's preferences. [inverted]: the stored flag means "off". */
@Composable
private fun PrefToggle(prefs: android.content.SharedPreferences, key: String, title: String, sub: String?, inverted: Boolean = false) {
    // stored default: "off" flags default to false, normal switches default to on
    var on by remember { mutableStateOf(prefs.getBoolean(key, !inverted) != inverted) }
    Toggle(title, sub, on) { v -> on = v; prefs.edit().putBoolean(key, v != inverted).apply() }
}

@Composable
private fun Toggle(title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!on) }) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, checkedThumbColor = C.Bg))
    }
}
