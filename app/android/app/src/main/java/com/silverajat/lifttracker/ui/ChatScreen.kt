package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.chat.Claude
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "How am I recovering this week, and what should today look like?",
    "What should I eat for the rest of today to hit protein?",
    "Am I on track to lose weight this week?",
    "Which lifts are stalling, and what should I change?",
    "What's been hurting my sleep?",
)

/** Chat with Claude, with everything the app knows attached to every message. */
@Composable
fun ChatScreen(hub: HubViewModel) {
    val chat by hub.chat.collectAsStateWithLifecycle()
    val sending by hub.sending.collectAsStateWithLifecycle()
    val error by hub.chatError.collectAsStateWithLifecycle()
    val key by hub.apiKey.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(chat.size, sending) { if (chat.isNotEmpty()) list.animateScrollToItem(chat.size - 1 + if (sending) 1 else 0) }

    if (settings) SettingsDialog(hub) { settings = false }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Coach", style = MaterialTheme.typography.headlineMedium)
                Text("Claude, with your gym, Fitbit, food and weight data", style = MaterialTheme.typography.labelMedium, color = C.Muted)
            }
            if (chat.isNotEmpty()) TextButton(onClick = { hub.clearChat() }) { Text("New chat", color = C.Muted) }
            IconButton(onClick = { settings = true }) { Icon(Icons.Filled.Settings, "Chat settings", tint = C.Muted) }
        }

        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (chat.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (key.isEmpty()) Card(Modifier.fillMaxWidth(), onClick = { settings = true }, border = C.Accent.copy(alpha = 0.5f)) {
                            Column {
                                Text("Add your Anthropic API key", style = MaterialTheme.typography.titleMedium)
                                Text("Get one at console.anthropic.com → API keys. It's stored only on this phone and sent only to Anthropic.",
                                    color = C.Muted)
                            }
                        }
                        Text("Try asking", color = C.Muted, style = MaterialTheme.typography.labelLarge)
                        suggestions.forEach { s ->
                            Text(s, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.Surface)
                                .clickable { hub.send(s) }.padding(14.dp))
                        }
                    }
                }
            }
            items(chat, key = { it.timeMs.toString() + it.role }) { m -> Bubble(m.role == "user", m.text) }
            if (sending) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.padding(4.dp).size(18.dp), color = C.Accent, strokeWidth = 2.dp)
                    Text("  thinking…", color = C.Muted)
                }
            }
            error?.let { item { Text(it, color = C.Stop) } }
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it },
                modifier = Modifier.weight(1f).heightIn(max = 160.dp),
                placeholder = { Text("Ask about training, food, sleep…", color = C.Muted) },
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text,
                    unfocusedTextColor = C.Text, cursorColor = C.Accent, focusedContainerColor = C.Surface, unfocusedContainerColor = C.Surface,
                ),
            )
            IconButton(onClick = { hub.send(input); input = "" }, enabled = input.isNotBlank() && !sending) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = if (input.isNotBlank() && !sending) C.Accent else C.Line)
            }
        }
    }
}

@Composable
private fun Bubble(user: Boolean, text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp))
                    .background(if (user) C.Accent.copy(alpha = 0.22f) else C.Surface).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingsDialog(hub: HubViewModel, onDismiss: () -> Unit) {
    val key by hub.apiKey.collectAsStateWithLifecycle()
    val model by hub.model.collectAsStateWithLifecycle()
    val hrMax by hub.hrMax.collectAsStateWithLifecycle()
    var k by remember { mutableStateOf(key) }
    var hr by remember { mutableStateOf(hrMax.toString()) }
    var context by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    context?.let { text ->
        AlertDialog(
            onDismissRequest = { context = null },
            containerColor = C.Surface,
            title = { Text("What Claude sees (${text.length / 4} tokens approx.)") },
            text = {
                SelectionContainer {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = C.Text,
                        modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = { TextButton(onClick = { context = null }) { Text("Close", color = C.Accent) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Coach settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(k, { k = it }, label = { Text("Anthropic API key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line,
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text))
                Text("Model", color = C.Muted)
                Claude.MODELS.forEach { (id, label) ->
                    Text((if (id == model) "● " else "○ ") + label, color = if (id == model) C.Accent else C.Text,
                        modifier = Modifier.fillMaxWidth().clickable { hub.setModel(id) }.padding(vertical = 4.dp))
                }
                OutlinedTextField(hr, { hr = it }, label = { Text("Max heart rate (for strain)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line,
                        focusedTextColor = C.Text, unfocusedTextColor = C.Text))
                Text("200 is a default. If you've seen a higher number on your Fitbit during a hard effort, use that.",
                    style = MaterialTheme.typography.labelMedium, color = C.Muted)
                Text("View what Claude sees", color = C.Accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { scope.launch { context = hub.contextText() } }.padding(vertical = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                hub.setApiKey(k)
                hr.toIntOrNull()?.let { if (it != hrMax) hub.setHrMax(it) }
                onDismiss()
            }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}
