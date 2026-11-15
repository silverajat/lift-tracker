package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.garden.Garden
import com.silverajat.lifttracker.journal.Book
import com.silverajat.lifttracker.journal.Reading
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val BookPink = Color(0xFFE07AA6)   // the Mind plant's colour
private val dShort = DateTimeFormatter.ofPattern("EEE d MMM")

/** Productivity → Reading: books on the go, a log per sitting, and what you've finished. Feeds the Mind plant. */
internal fun LazyListScope.readingTab(u: DayUi, act: DayActions) {
    val reading = u.books.filter { it.finished == null }
    val done = u.books.filter { it.finished != null }.sortedByDescending { it.finished }
    item { ReadingStats(u) }
    item {
        Block("Reading now", progress = reading.size.takeIf { it > 0 }?.let { if (it == 1) "1 book" else "$it books" }) {
            reading.forEach { BookRow(it, u, act) }
            AddField("Add a book… e.g. Dune by Frank Herbert, 600") { act.addBook(it) }
            if (reading.isEmpty()) Text("Add what you're reading (author and pages optional), then log each sitting. " +
                "Reading grows the Mind plant: a point a minute, +${Reading.FINISH_BONUS} when you finish a book.",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
    if (u.reads.isNotEmpty()) item {
        Block("Recent") {
            u.reads.take(8).forEach { s ->
                val b = u.books.firstOrNull { it.id == s.bookId }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b?.title ?: "Book", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(dayName(s.date, u.today), Garden.hm(s.minutes).takeIf { s.minutes > 0 },
                            s.pagesRead.takeIf { it > 0 }?.let { "$it pages" }, s.toPage?.let { "to p. $it" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = C.Muted)
                    }
                    Text("✕", color = C.Muted, modifier = Modifier.clickable { act.removeRead(s.id) }.padding(8.dp))
                }
            }
        }
    }
    if (done.isNotEmpty()) item {
        val year = done.count { it.finished!!.year == u.today.year }
        Block("Finished", progress = "$year this year") {
            done.forEach { b -> FinishedRow(b, act) }
        }
    }
}

private fun dayName(d: LocalDate, today: LocalDate) = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(dShort)
}

@Composable
private fun ReadingStats(u: DayUi) {
    val week = u.reads.filter { !it.date.isBefore(u.today.minusDays(6)) }
    val todayMin = u.reads.filter { it.date == u.today }.sumOf { it.minutes }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                RStat(if (todayMin > 0) Garden.hm(todayMin) else "–", "today")
                RStat(Garden.hm(week.sumOf { it.minutes }), "last 7 days")
                RStat("${Reading.streak(u.reads, u.today)}", "day streak")
            }
            val pages = week.sumOf { it.pagesRead }
            Text((if (pages > 0) "$pages pages this week · " else "") +
                "${u.books.count { it.finished?.year == u.today.year }} books finished this year",
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
    }
}

@Composable
private fun RStat(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.merge(Tnum), color = C.Text)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

@Composable
private fun BookRow(b: Book, u: DayUi, act: DayActions) {
    var logging by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    if (logging) LogReadingDialog(b, u.today, onDismiss = { logging = false }) { min, page, day -> act.logReading(b.id, min, page, day); logging = false }
    if (editing) BookDialog(b, act) { editing = false }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { editing = true }) {
                Text(b.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val sub = listOfNotNull(b.author.ifBlank { null },
                    when { b.pages != null -> "p. ${b.page} of ${b.pages}"; b.page > 0 -> "p. ${b.page}"; else -> null }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
            Text("Log", style = MaterialTheme.typography.titleSmall, color = C.OnAccent,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(C.Accent).clickable { logging = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp))
        }
        b.progress?.let { p ->
            // a blocky progress bar, 20 cells
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
                val filled = (p * 20).toInt()
                repeat(20) { i -> Box(Modifier.weight(1f).height(6.dp).background(if (i < filled) BookPink else C.Raised)) }
            }
        }
    }
}

@Composable
private fun FinishedRow(b: Book, act: DayActions) {
    var editing by remember { mutableStateOf(false) }
    if (editing) BookDialog(b, act) { editing = false }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { editing = true }) {
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.bodyLarge)
            Text(listOfNotNull(b.author.ifBlank { null }, b.pages?.let { "$it pages" }, b.finished?.format(dShort)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = C.Muted)
        }
        Text("✓", color = BookPink, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun rFields() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, cursorColor = C.Accent,
    focusedTextColor = C.Text, unfocusedTextColor = C.Text, focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted,
)

@Composable
private fun RChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) C.OnAccent else C.Text,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) C.Accent else C.Raised)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun LogReadingDialog(b: Book, today: LocalDate, onDismiss: () -> Unit, onSave: (Int, Int?, LocalDate) -> Unit) {
    var min by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(today) }
    val m = min.toIntOrNull() ?: 0
    val p = page.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RChip("Today", day == today) { day = today }
                    RChip("Yesterday", day == today.minusDays(1)) { day = today.minusDays(1) }
                }
                OutlinedTextField(min, { min = it.filter(Char::isDigit).take(3) }, label = { Text("Minutes read") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = rFields(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 45, 60).forEach { q -> RChip("$q", m == q) { min = "$q" } }
                }
                OutlinedTextField(page, { page = it.filter(Char::isDigit).take(4) },
                    label = { Text(if (b.page > 0) "Now on page (was ${b.page})" else "Now on page (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = rFields(), modifier = Modifier.fillMaxWidth())
                if (p != null && b.pages != null && p >= b.pages) Text("That's the end: it'll go to Finished (+${Reading.FINISH_BONUS}).",
                    style = MaterialTheme.typography.bodySmall, color = BookPink)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(m, p, day) }, enabled = m > 0 || p != null) {
                Text("Save", color = if (m > 0 || p != null) C.Accent else C.Muted, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}

@Composable
private fun BookDialog(b: Book, act: DayActions, onDone: () -> Unit) {
    var title by remember { mutableStateOf(b.title) }
    var author by remember { mutableStateOf(b.author) }
    var pages by remember { mutableStateOf(b.pages?.toString() ?: "") }
    var sure by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.Surface,
        title = { Text("Book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, colors = rFields(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(author, { author = it }, label = { Text("Author") }, singleLine = true, colors = rFields(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pages, { pages = it.filter(Char::isDigit).take(4) }, label = { Text("Pages") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = rFields(), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(if (b.finished == null) "Mark finished" else "Not finished yet", style = MaterialTheme.typography.titleSmall, color = BookPink,
                        modifier = Modifier.clickable { act.finishBook(b.id, b.finished == null); onDone() }.padding(vertical = 6.dp))
                    Text(if (sure) "Tap again to remove" else "Remove", style = MaterialTheme.typography.titleSmall, color = Color(0xFFE5645A),
                        modifier = Modifier.clickable { if (sure) { act.removeBook(b.id); onDone() } else sure = true }.padding(vertical = 6.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { act.editBook(b.id, title, author, pages.toIntOrNull()); onDone() }) {
                Text("Save", color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel", color = C.Muted) } },
    )
}
