package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.journal.DayPage
import com.silverajat.lifttracker.journal.Journal
import com.silverajat.lifttracker.journal.JournalViewModel
import com.silverajat.lifttracker.journal.Note
import com.silverajat.lifttracker.journal.Task
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val MOODS = listOf("😞", "😕", "😐", "🙂", "😄")
private val dHead = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val dShort = DateTimeFormatter.ofPattern("EEE d MMM")
private val dTime = DateTimeFormatter.ofPattern("H:mm")

/** Everything the Day tab can do (no-ops by default, for previews). */
class DayActions(
    val add: (text: String, main: Boolean, day: LocalDate?) -> Unit = { _, _, _ -> },
    val toggle: (String) -> Unit = {},
    val edit: (String, String) -> Unit = { _, _ -> },
    val remove: (String) -> Unit = {},
    val promote: (String) -> Unit = {},
    val toTomorrow: (String) -> Unit = {},
    val removeTomorrow: (String) -> Unit = {},
    val bringOver: () -> Unit = {},
    val drop: () -> Unit = {},
    val mood: (Int?) -> Unit = {},
    val reflect: (String) -> Unit = {},
    val addNote: (String) -> Unit = {},
    val editNote: (String, String) -> Unit = { _, _ -> },
    val pinNote: (String) -> Unit = {},
    val deleteNote: (String) -> Unit = {},
)

data class DayUi(
    val today: LocalDate,
    val pages: Map<LocalDate, DayPage>,
    val notes: List<Note>,
    val leftovers: List<Task>,
    val now: LocalTime = LocalTime.now(),
)

@Composable
fun DayScreen(j: JournalViewModel) {
    val today by j.today.collectAsStateWithLifecycle()
    val pages by j.pages.collectAsStateWithLifecycle()
    val notes by j.notes.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { j.refresh() }
    val ui = DayUi(today, pages, notes, remember(pages, today) { j.leftovers() })
    DayContent(ui, DayActions(
        add = { t, m, d -> if (d == null) j.add(t, m) else j.add(t, m, d) },
        toggle = j::toggle, edit = { id, t -> j.edit(id, t) }, remove = { j.remove(it) }, promote = { j.promote(it) },
        toTomorrow = j::toTomorrow, removeTomorrow = { j.remove(it, today.plusDays(1)) },
        bringOver = j::bringOver, drop = j::dropLeftovers, mood = j::setMood, reflect = j::setReflection,
        addNote = j::addNote, editNote = j::editNote, pinNote = j::pinNote, deleteNote = j::deleteNote,
    ))
}

@Composable
fun DayContent(u: DayUi, act: DayActions = DayActions(), startTab: Int = 0) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val page = u.pages[u.today] ?: DayPage(u.today)
    var editing by remember { mutableStateOf<Task?>(null) }
    var editingNote by remember { mutableStateOf<Note?>(null) }
    editing?.let { t -> TaskDialog(t, page.mains.size < Journal.MAINS, act, onDone = { editing = null }) }
    editingNote?.let { n -> NoteDialog(n, act, onDone = { editingNote = null }) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    Text("Day", style = MaterialTheme.typography.headlineLarge)
                    Text(u.today.format(dHead), style = MaterialTheme.typography.titleMedium, color = C.Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Plan", "Notes", "Past").forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.titleSmall, color = if (tab == i) C.Text else C.Muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) C.Raised else C.Bg)
                                .clickable { tab = i }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
            }
        }
        when (tab) {
            0 -> planTab(u, page, act) { editing = it }
            1 -> notesTab(u, act) { editingNote = it }
            else -> pastTab(u)
        }
    }
}

// ---- plan -------------------------------------------------------------------------

private fun LazyListScope.planTab(u: DayUi, page: DayPage, act: DayActions, onEdit: (Task) -> Unit) {
    if (u.leftovers.isNotEmpty()) item {
        Card(Modifier.fillMaxWidth(), border = C.Accent.copy(alpha = 0.5f)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${u.leftovers.size} left from yesterday", style = MaterialTheme.typography.titleMedium)
                u.leftovers.forEach { t ->
                    Text((if (t.main) "★ " else "• ") + t.text, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Bring over", style = MaterialTheme.typography.titleSmall, color = C.Accent, modifier = Modifier.clickable { act.bringOver() })
                    Text("Drop them", style = MaterialTheme.typography.titleSmall, color = C.Muted, modifier = Modifier.clickable { act.drop() })
                }
            }
        }
    }
    item {
        Block("Main 3", progress = page.mains.takeIf { it.isNotEmpty() }?.let { "${it.count { t -> t.done }}/${it.size}" }) {
            page.mains.forEach { TaskRow(it, act, onEdit) }
            if (page.mains.size < Journal.MAINS) AddField(
                if (page.mains.isEmpty()) "What are your 3 main things today?" else "Main thing ${page.mains.size + 1}",
            ) { act.add(it, true, null) }
        }
    }
    item {
        Block("Side things", progress = page.sides.takeIf { it.isNotEmpty() }?.let { "${it.count { t -> t.done }}/${it.size}" }) {
            page.sides.forEach { TaskRow(it, act, onEdit) }
            AddField("Add a side thing") { act.add(it, false, null) }
        }
    }
    item { Reflect(u, page, act) }
    item {
        val tmr = u.pages[u.today.plusDays(1)]?.tasks.orEmpty()
        Block("Tomorrow") {
            tmr.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (t.main) "★ " else "• ") + t.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("✕", color = C.Muted, modifier = Modifier.clickable { act.removeTomorrow(t.id) }.padding(8.dp))
                }
            }
            AddField("Add something for tomorrow") { act.add(it, false, u.today.plusDays(1)) }
        }
    }
}

@Composable
private fun Reflect(u: DayUi, page: DayPage, act: DayActions) {
    val night = u.now.hour >= 18 || u.now.hour < 4
    var text by remember(page.date) { mutableStateOf(page.reflection) }
    Block("Tonight", progress = null, highlight = night && page.planned && !page.reflected) {
        if (page.planned) {
            val m = page.mains; val s = page.sides
            Text("Done: ${m.count { it.done }} of ${m.size} main" + if (s.isNotEmpty()) ", ${s.count { it.done }} of ${s.size} side" else "",
                style = MaterialTheme.typography.bodyMedium, color = C.Muted)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MOODS.forEachIndexed { i, e ->
                val on = page.mood == i + 1
                Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) C.Raised else C.Bg)
                    .then(if (on) Modifier.border(1.5.dp, C.Accent, CircleShape) else Modifier)
                    .clickable { act.mood(if (on) null else i + 1) }, contentAlignment = Alignment.Center) {
                    Text(e, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        OutlinedTextField(text, { text = it; act.reflect(it) }, placeholder = { Text("How did today go?", color = C.Muted) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), colors = dayField(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
    }
}

@Composable
private fun Block(title: String, progress: String? = null, highlight: Boolean = false, content: @Composable () -> Unit) {
    Column {
        Row(Modifier.padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (progress != null) Text(progress, style = MaterialTheme.typography.titleMedium.merge(Tnum), color = C.Muted)
        }
        Card(Modifier.fillMaxWidth(), border = if (highlight) C.Accent.copy(alpha = 0.6f) else androidx.compose.ui.graphics.Color.Transparent) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

@Composable
private fun TaskRow(t: Task, act: DayActions, onEdit: (Task) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape)
            .background(if (t.done) C.Accent else C.Bg)
            .border(1.5.dp, if (t.done) C.Accent else C.Line, CircleShape)
            .clickable { act.toggle(t.id) }, contentAlignment = Alignment.Center) {
            if (t.done) Text("✓", color = C.Bg, style = MaterialTheme.typography.labelLarge)
        }
        Text(t.text, style = MaterialTheme.typography.bodyLarge, color = if (t.done) C.Muted else C.Text,
            textDecoration = if (t.done) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f).clickable { onEdit(t) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        if (t.carried) Text("↻", color = C.Muted, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun AddField(hint: String, onAdd: (String) -> Unit) {
    var s by remember { mutableStateOf("") }
    fun go() { if (s.isNotBlank()) { onAdd(s); s = "" } }
    OutlinedTextField(s, { s = it }, placeholder = { Text(hint, color = C.Muted) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), colors = dayField(),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { go() }),
        trailingIcon = if (s.isNotBlank()) ({ Text("Add", color = C.Accent, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickable { go() }.padding(12.dp)) }) else null)
}

@Composable
private fun dayField() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text, cursorColor = C.Accent,
)

@Composable
private fun TaskDialog(t: Task, roomForMain: Boolean, act: DayActions, onDone: () -> Unit) {
    var s by remember(t.id) { mutableStateOf(t.text) }
    AlertDialog(onDismissRequest = onDone, containerColor = C.Surface,
        title = { Text(if (t.main) "Main thing" else "Side thing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(s, { s = it }, modifier = Modifier.fillMaxWidth(), colors = dayField())
                if (t.main || roomForMain) Text(if (t.main) "Make it a side thing" else "Make it a main thing", color = C.Accent,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { act.promote(t.id); onDone() })
                Text("Move to tomorrow", color = C.Accent, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable { act.toTomorrow(t.id); onDone() })
                Text("Delete", color = C.Stop, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.clickable { act.remove(t.id); onDone() })
            }
        },
        confirmButton = { TextButton(onClick = { act.edit(t.id, s); onDone() }) { Text("Save", color = C.Accent) } },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = C.Muted) } })
}

// ---- notes ------------------------------------------------------------------------

private fun LazyListScope.notesTab(u: DayUi, act: DayActions, onEdit: (Note) -> Unit) {
    item { NoteCapture(act) }
    if (u.notes.size > 6) item { NoteSearch() }
    val q = noteQuery.value.trim().lowercase()
    val shown = u.notes.filter { q.isEmpty() || q in it.text.lowercase() }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
    if (shown.isEmpty() && u.notes.isNotEmpty()) item { Text("No notes match.", color = C.Muted) }
    items(shown, key = { it.id }) { n ->
        Card(Modifier.fillMaxWidth(), onClick = { onEdit(n) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Text((if (n.pinned) "📌 " else "") + whenText(n.updated), style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        }
    }
}

/** The search box text lives outside the lazy list so typing doesn't lose focus. */
private val noteQuery = mutableStateOf("")

@Composable
private fun NoteSearch() {
    OutlinedTextField(noteQuery.value, { noteQuery.value = it }, placeholder = { Text("Search notes", color = C.Muted) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(), colors = dayField())
}

@Composable
private fun NoteCapture(act: DayActions) {
    var s by rememberSaveable { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(s, { s = it }, placeholder = { Text("Brain dump: ideas, plans, anything", color = C.Muted) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp), colors = dayField(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            if (s.isNotBlank()) Text("Save note", color = C.Accent, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { act.addNote(s); s = "" }.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun NoteDialog(n: Note, act: DayActions, onDone: () -> Unit) {
    var s by remember(n.id) { mutableStateOf(n.text) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Delete this note?") },
        confirmButton = { TextButton(onClick = { act.deleteNote(n.id); confirm = false; onDone() }) { Text("Delete", color = C.Stop) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = C.Muted) } })
    AlertDialog(onDismissRequest = { act.editNote(n.id, s); onDone() }, containerColor = C.Surface,
        title = { Text(whenText(n.created), style = MaterialTheme.typography.titleMedium, color = C.Muted) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(s, { s = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp), colors = dayField(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(if (n.pinned) "Unpin" else "Pin", color = C.Accent, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { act.editNote(n.id, s); act.pinNote(n.id); onDone() })
                    Text("Delete", color = C.Stop, style = MaterialTheme.typography.titleSmall, modifier = Modifier.clickable { confirm = true })
                }
            }
        },
        confirmButton = { TextButton(onClick = { act.editNote(n.id, s); onDone() }) { Text("Done", color = C.Accent) } })
}

private fun whenText(ms: Long): String {
    val z = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(ms).atZone(z)
    val d = t.toLocalDate()
    val today = LocalDate.now(z)
    return when (d) {
        today -> "Today " + t.format(dTime)
        today.minusDays(1) -> "Yesterday " + t.format(dTime)
        else -> t.format(dShort) + " " + t.format(dTime)
    }
}

// ---- past -------------------------------------------------------------------------

private fun LazyListScope.pastTab(u: DayUi) {
    val w = Journal.week(u.pages, u.today)
    item {
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Last 7 days", style = MaterialTheme.typography.titleMedium, color = C.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    PastStat(if (w.mainsSet == 0) "–" else "${w.mainsDone}/${w.mainsSet}", "main things done")
                    PastStat("${w.reflected}/${w.days}", "nights reflected")
                    PastStat("${w.streak}", "day streak")
                }
            }
        }
    }
    val past = u.pages.values.filter { it.date < u.today && (it.planned || it.reflected) }.sortedByDescending { it.date }.take(60)
    if (past.isEmpty()) item { Text("Your past days show up here.", color = C.Muted, modifier = Modifier.padding(start = 4.dp)) }
    items(past, key = { it.date.toString() }) { p -> PastDay(p) }
}

@Composable
private fun PastStat(v: String, label: String) {
    Column {
        Text(v, style = MaterialTheme.typography.headlineMedium.merge(Tnum))
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun PastDay(p: DayPage) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), onClick = { open = !open }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.date.format(dShort), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                p.mood?.let { Text(MOODS[it - 1], style = MaterialTheme.typography.titleLarge) }
            }
            if (p.planned) Text("${p.mains.count { it.done }}/${p.mains.size} main" +
                (if (p.sides.isNotEmpty()) " · ${p.sides.count { it.done }}/${p.sides.size} side" else ""),
                style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = C.Muted)
            if (open) p.tasks.forEach { t ->
                Text((if (t.done) "✓ " else "○ ") + t.text, style = MaterialTheme.typography.bodyMedium,
                    color = if (t.done) C.Text else C.Muted)
            }
            if (p.reflection.isNotBlank()) Text(p.reflection, style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
