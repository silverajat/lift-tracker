package com.silverajat.lifttracker.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
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

/** Moods 1 to 5 as pixel faces (Sprites.moods). */
val MOODS = Sprites.moods
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
    val addShop: (String) -> Unit = {},
    val toggleShop: (String) -> Unit = {},
    val clearBought: () -> Unit = {},
    val addReminder: (String, java.time.LocalDateTime) -> Unit = { _, _ -> },
    val moveReminder: (String, java.time.LocalDateTime) -> Unit = { _, _ -> },
    val removeReminder: (String) -> Unit = {},
    val addBook: (String) -> Unit = {},
    val editBook: (String, String, String, Int?) -> Unit = { _, _, _, _ -> },
    val logReading: (String, Int, Int?, LocalDate) -> Unit = { _, _, _, _ -> },
    val finishBook: (String, Boolean) -> Unit = { _, _ -> },
    val removeBook: (String) -> Unit = {},
    val removeRead: (String) -> Unit = {},
    val brainstorm: () -> Unit = {},
)

data class DayUi(
    val today: LocalDate,
    val pages: Map<LocalDate, DayPage>,
    val notes: List<Note>,
    val leftovers: List<Task>,
    val now: LocalTime = LocalTime.now(),
    val shopping: List<com.silverajat.lifttracker.journal.ShopItem> = emptyList(),
    val reminders: List<com.silverajat.lifttracker.journal.Reminder> = emptyList(),
    val books: List<com.silverajat.lifttracker.journal.Book> = emptyList(),
    val reads: List<com.silverajat.lifttracker.journal.ReadSession> = emptyList(),
)

@Composable
fun DayScreen(
    j: JournalViewModel, g: com.silverajat.lifttracker.garden.GardenViewModel,
    hub: com.silverajat.lifttracker.HubViewModel, gym: com.silverajat.lifttracker.AppViewModel,
    onBrainstorm: () -> Unit = {},
) {
    val today by j.today.collectAsStateWithLifecycle()
    val pages by j.pages.collectAsStateWithLifecycle()
    val notes by j.notes.collectAsStateWithLifecycle()
    val shopping by j.shopping.collectAsStateWithLifecycle()
    val reminders by j.reminders.collectAsStateWithLifecycle()
    val books by j.books.collectAsStateWithLifecycle()
    val reads by j.reads.collectAsStateWithLifecycle()
    val focus by g.sessions.collectAsStateWithLifecycle()
    val active by g.active.collectAsStateWithLifecycle()
    val sessions by gym.history.collectAsStateWithLifecycle()
    val items by hub.items.collectAsStateWithLifecycle()
    val an by hub.analysis.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) { while (active != null) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    LaunchedEffect(Unit) { j.refresh() }
    val zone = ZoneId.systemDefault()
    val plants = com.silverajat.lifttracker.garden.Garden.plants(
        com.silverajat.lifttracker.garden.Garden.Inputs(focus, sessions, items, an?.days.orEmpty(), pages, active, reads, books), today, zone, now)
    val ui = DayUi(today, pages, notes, remember(pages, today) { j.leftovers() }, shopping = shopping, reminders = reminders,
        books = books, reads = reads)
    val gIn = com.silverajat.lifttracker.garden.Garden.Inputs(focus, sessions, items, an?.days.orEmpty(), pages, active, reads, books)
    val logs = remember(focus, sessions, items, an, pages, reads, books) { com.silverajat.lifttracker.garden.Area.entries.associateWith { com.silverajat.lifttracker.garden.Garden.log(it, gIn, zone) } }
    val summaries = remember(focus, sessions, items, an, pages, reads, books, now / 60_000) {
        (0L..30L).map { today.minusDays(it) }.associateWith { com.silverajat.lifttracker.garden.Garden.summary(it, gIn, zone, now) }
    }
    DayContent(ui, garden = GardenUi(plants, active, focus, now, logs, summaries), gAct = GardenActions(
        start = { a, l -> g.start(a, l) }, stop = { g.stop() }, discard = g::discard, delete = g::delete, save = g::save,
    ), act = DayActions(
        add = { t, m, d -> if (d == null) j.add(t, m) else j.add(t, m, d) },
        toggle = j::toggle, edit = { id, t -> j.edit(id, t) }, remove = { j.remove(it) }, promote = { j.promote(it) },
        toTomorrow = j::toTomorrow, removeTomorrow = { j.remove(it, today.plusDays(1)) },
        bringOver = j::bringOver, drop = j::dropLeftovers, mood = j::setMood, reflect = j::setReflection,
        addNote = j::addNote, editNote = j::editNote, pinNote = j::pinNote, deleteNote = j::deleteNote,
        addShop = j::addShop, toggleShop = j::toggleShop, clearBought = j::clearBought, brainstorm = onBrainstorm,
        addReminder = j::addReminder, moveReminder = j::moveReminder, removeReminder = j::removeReminder,
        addBook = j::addBook, editBook = j::editBook, logReading = { b, m, p, d -> j.logReading(b, m, p, d) },
        finishBook = { id, done -> j.finishBook(id, done) }, removeBook = j::removeBook, removeRead = { j.removeRead(it) },
    ))
}

@Composable
fun DayContent(u: DayUi, act: DayActions = DayActions(), startTab: Int = 0, garden: GardenUi? = null, gAct: GardenActions = GardenActions()) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val page = u.pages[u.today] ?: DayPage(u.today)
    var plant by rememberSaveable { mutableStateOf<com.silverajat.lifttracker.garden.Area?>(null) }
    if (plant != null && garden != null) {
        androidx.activity.compose.BackHandler { plant = null }
        PlantPage(garden, plant!!, gAct) { plant = null }
        return
    }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PixelGlyph(Glyphs.sprout, Color(0xFF6CC24A), modifier = Modifier.padding(end = 10.dp))
                            Text("Productivity", style = MaterialTheme.typography.headlineLarge)
                        }
                        Text(u.today.format(dHead), style = MaterialTheme.typography.titleMedium, color = C.Muted)
                    }
                    TopActionIcons()
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Plan", "Garden", "Reading", "Notes", "Past").forEachIndexed { i, s ->
                        Text(s, style = MaterialTheme.typography.titleSmall, color = if (tab == i) C.Text else C.Muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) C.Raised else C.Bg)
                                .clickable { tab = i }.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }
        when (tab) {
            0 -> {
                item { DaySummaryCard(garden?.summaries?.get(u.today), page.mains.count { it.done }, page.mains.size, "Today so far") }
                planTab(u, page, act) { editing = it }
            }
            1 -> if (garden != null) gardenTab(garden, gAct) { plant = it }
            2 -> readingTab(u, act)
            3 -> notesTab(u, act) { editingNote = it }
            else -> pastTab(u, garden?.summaries.orEmpty())
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
    item { RemindersBlock(u.reminders, act) }
    item {
        val bought = u.shopping.count { it.bought }
        Block("Shopping", progress = u.shopping.takeIf { it.isNotEmpty() }?.let { "${it.size - bought} to buy" }) {
            u.shopping.forEach { it ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(26.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (it.bought) C.Accent else C.Bg)
                        .border(1.5.dp, if (it.bought) C.Accent else C.Line, RoundedCornerShape(6.dp))
                        .clickable { act.toggleShop(it.id) }, contentAlignment = Alignment.Center) {
                        if (it.bought) Text("✓", color = C.Bg, style = MaterialTheme.typography.labelLarge)
                    }
                    Text(it.text, style = MaterialTheme.typography.bodyLarge, color = if (it.bought) C.Muted else C.Text,
                        textDecoration = if (it.bought) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f).clickable { act.toggleShop(it.id) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
                }
            }
            AddField("Add to the shopping list") { act.addShop(it) }
            if (bought > 0) Text("Clear $bought bought", style = MaterialTheme.typography.titleSmall, color = C.Accent,
                modifier = Modifier.clickable { act.clearBought() }.padding(top = 2.dp))
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

/** Reminders: type "umbrella at 3:30" (or just "umbrella" and pick a time); it pops up as a notification then. */
@Composable
private fun RemindersBlock(rs: List<com.silverajat.lifttracker.journal.Reminder>, act: DayActions) {
    var now by remember { mutableStateOf(java.time.LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); now = java.time.LocalDateTime.now() } }
    // text waiting for a day and time (and the day, if you named one)
    var asking by remember { mutableStateOf<Pair<String, java.time.LocalDate?>?>(null) }
    var moving by remember { mutableStateOf<com.silverajat.lifttracker.journal.Reminder?>(null) }
    asking?.let { (text, day) ->
        val h = now.plusHours(1).hour
        val start = (day ?: now.toLocalDate()).atTime(if (day != null && day.isAfter(now.toLocalDate())) 9 else h, 0)
        ReminderWhenDialog("Remind you when?", start, now, onDismiss = { asking = null }) { act.addReminder(text, it); asking = null }
    }
    moving?.let { r ->
        ReminderWhenDialog("Move to when?", r.at, now, onDismiss = { moving = null }) { act.moveReminder(r.id, it); moving = null }
    }
    val next = rs.count { it.at.isAfter(now) }
    Block("Reminders", progress = next.takeIf { it > 0 }?.let { "$it coming up" }) {
        rs.forEach { r ->
            val past = !r.at.isAfter(now)
            val day = r.at.toLocalDate()
            val time = (when (day) {
                now.toLocalDate() -> ""
                now.toLocalDate().plusDays(1) -> "Tmr "
                in now.toLocalDate()..now.toLocalDate().plusDays(6) -> day.format(DateTimeFormatter.ofPattern("EEE "))
            else -> day.format(DateTimeFormatter.ofPattern("d MMM "))
            }) + r.at.format(dTime)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { moving = r }) {
                Text(time, style = MaterialTheme.typography.titleSmall.merge(Tnum), color = if (past) C.Muted else C.Accent,
                    modifier = Modifier.width(78.dp).padding(vertical = 6.dp))
                Text(r.text, style = MaterialTheme.typography.bodyLarge, color = if (past) C.Muted else C.Text,
                    textDecoration = if (past) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
                Text("✕", color = C.Muted, modifier = Modifier.clickable { act.removeReminder(r.id) }.padding(8.dp))
            }
        }
        AddField("Remind me… e.g. umbrella tmr 8am") { s ->
            val p = com.silverajat.lifttracker.journal.ReminderText.parse(s, now)
            if (p.text.isBlank()) return@AddField
            if (p.at != null) act.addReminder(p.text, p.at) else asking = p.text to p.day
        }
        if (rs.isEmpty()) Text("Just a notification at that time. Try \"call mum at 9\", \"gym bag tmr 8am\", " +
            "\"dentist fri 3pm\" or \"laundry in 40 min\". No time? You'll pick a day and time.",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

/** Day chips (today, tomorrow, the rest of the week, or any date) above the clock. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ReminderWhenDialog(title: String, start: java.time.LocalDateTime, now: java.time.LocalDateTime,
                               onDismiss: () -> Unit, onPick: (java.time.LocalDateTime) -> Unit) {
    val today = now.toLocalDate()
    var day by remember { mutableStateOf(if (start.toLocalDate().isBefore(today)) today else start.toLocalDate()) }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        val utc = java.time.ZoneOffset.UTC
        val first = today.atStartOfDay(utc).toInstant().toEpochMilli()
        val ds = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = day.atStartOfDay(utc).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= first
            })
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                ds.selectedDateMillis?.let { day = java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate() }; picking = false
            }) { Text("OK", color = C.Accent) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { picking = false }) { Text("Cancel", color = C.Muted) } },
            colors = androidx.compose.material3.DatePickerDefaults.colors(containerColor = C.Surface),
        ) {
            androidx.compose.material3.DatePicker(ds, colors = androidx.compose.material3.DatePickerDefaults.colors(
                containerColor = C.Surface, selectedDayContainerColor = C.Accent, selectedDayContentColor = C.OnAccent,
                todayDateBorderColor = C.Accent, todayContentColor = C.Accent))
        }
    }
    TimeDialog(start.hour, start.minute, onDismiss, title, header = {
        val week = (0L..6L).map { today.plusDays(it) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (week + listOfNotNull(day.takeIf { it !in week })).forEach { d ->
                DayChip(when (d) {
                    today -> "Today"
                    today.plusDays(1) -> "Tmr"
                    in week -> d.format(DateTimeFormatter.ofPattern("EEE d"))
                    else -> d.format(DateTimeFormatter.ofPattern("d MMM"))
                }, d == day) { day = d }
            }
            DayChip("Other date", false) { picking = true }
        }
    }) { h, m ->
        val t = day.atTime(h, m)
        // a time that's already gone today means tomorrow
        onPick(if (day == today && !t.isAfter(now)) t.plusDays(1) else t)
    }
}

@Composable
private fun DayChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) C.OnAccent else C.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Accent else C.Raised)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun Reflect(u: DayUi, page: DayPage, act: DayActions) {
    val night = u.now.hour >= 18 || u.now.hour < 4
    var text by remember(page.date) { mutableStateOf(page.reflection) }
    var writing by remember(page.date) { mutableStateOf(page.reflection.isBlank()) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
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
                    PixelSprite(e, 26.dp)
                }
            }
        }
        if (!writing && text.isNotBlank()) {
            // saved: show it, tap to change
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { writing = true }.padding(vertical = 4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saved ✓", style = MaterialTheme.typography.titleSmall, color = C.Done, modifier = Modifier.weight(1f))
                Text("Edit", style = MaterialTheme.typography.titleSmall, color = C.Accent, modifier = Modifier.clickable { writing = true }.padding(4.dp))
            }
        } else {
            OutlinedTextField(text, { text = it; act.reflect(it) }, placeholder = { Text("How did today go?", color = C.Muted) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), colors = dayField(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Saves as you type", style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.weight(1f))
                val ok = text.isNotBlank() || page.mood != null
                Text("Done", style = MaterialTheme.typography.titleSmall, color = if (ok) C.OnAccent else C.Muted,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (ok) C.Accent else C.Raised)
                        .clickable(enabled = ok) { act.reflect(text.trim()); text = text.trim(); focus.clearFocus(); writing = false }
                        .padding(horizontal = 20.dp, vertical = 9.dp))
            }
        }
    }
}

@Composable
internal fun Block(title: String, progress: String? = null, highlight: Boolean = false, content: @Composable () -> Unit) {
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
        // square, pixel-style tick box
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(3.dp))
            .background(if (t.done) C.Accent else C.Bg)
            .border(2.dp, if (t.done) C.Accent else C.Line, RoundedCornerShape(3.dp))
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
internal fun AddField(hint: String, onAdd: (String) -> Unit) {
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
    item {
        Card(Modifier.fillMaxWidth(), onClick = act.brainstorm) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PixelSprite(Sprites.chat, 28.dp, Modifier.padding(end = 14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Brainstorm with GPT", style = MaterialTheme.typography.titleMedium)
                    Text("Talk an idea through. It knows your training, sleep and food.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
                }
                Text("›", style = MaterialTheme.typography.headlineMedium, color = C.Muted)
            }
        }
    }
    if (u.notes.size > 6) item { NoteSearch() }
    val q = noteQuery.value.trim().lowercase()
    val shown = u.notes.filter { q.isEmpty() || q in it.text.lowercase() }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
    if (shown.isEmpty() && u.notes.isNotEmpty()) item { Text("No notes match.", color = C.Muted) }
    items(shown, key = { it.id }) { n ->
        Card(Modifier.fillMaxWidth(), onClick = { onEdit(n) }) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
                Text((if (n.pinned) "Pinned · " else "") + whenText(n.updated), style = MaterialTheme.typography.bodySmall, color = C.Muted)
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

private fun LazyListScope.pastTab(u: DayUi, sums: Map<LocalDate, com.silverajat.lifttracker.garden.Garden.DaySummary> = emptyMap()) {
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
    items(past, key = { it.date.toString() }) { p -> PastDay(p, sums[p.date]) }
}

@Composable
private fun PastStat(v: String, label: String) {
    Column {
        Text(v, style = MaterialTheme.typography.headlineMedium.merge(Tnum))
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun PastDay(p: DayPage, sum: com.silverajat.lifttracker.garden.Garden.DaySummary? = null) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth(), onClick = { open = !open }) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.date.format(dShort), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                p.mood?.let { PixelSprite(MOODS[it - 1], 22.dp) }
            }
            sum?.minutes?.takeIf { it.isNotEmpty() }?.let { m ->
                Text(m.entries.joinToString(" · ") { (a, v) -> "${if (a == com.silverajat.lifttracker.garden.Area.MIND) "Reading" else a.label} ${com.silverajat.lifttracker.garden.Garden.hm(v)}" },
                    style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = C.Muted)
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
