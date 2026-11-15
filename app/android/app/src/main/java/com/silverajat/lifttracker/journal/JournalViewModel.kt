package com.silverajat.lifttracker.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.LocalDateTime

/** The Day tab: today's plan and reflection, tomorrow's list, past days and notes. */
class JournalViewModel(app: Application) : AndroidViewModel(app) {
    private val store = JournalStore(app.filesDir)

    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today
    private val _pages = MutableStateFlow<Map<LocalDate, DayPage>>(emptyMap())
    val pages: StateFlow<Map<LocalDate, DayPage>> = _pages
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes

    private val _shopping = MutableStateFlow<List<ShopItem>>(emptyList())
    val shopping: StateFlow<List<ShopItem>> = _shopping

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders

    private val reading = ReadingStore(app.filesDir)
    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books
    private val _reads = MutableStateFlow<List<ReadSession>>(emptyList())
    val reads: StateFlow<List<ReadSession>> = _reads

    init { refresh() }

    /** Re-reads the file and moves to a new day after 4 AM. */
    fun refresh() {
        _today.value = Journal.dayOf(LocalDateTime.now())
        _pages.value = store.pages()
        _notes.value = store.notes()
        _shopping.value = store.shopping()
        // reminders that went off more than a day ago drop off the list
        val all = store.reminders()
        val keep = all.filter { !it.fired || it.at.isAfter(LocalDateTime.now().minusDays(1)) }
        if (keep.size != all.size) store.saveReminders(keep)
        _reminders.value = keep
        _books.value = reading.books()
        _reads.value = reading.sessions()
    }

    // ---- reading ----
    fun addBook(text: String) {
        val (title, author, pages) = Reading.parseBook(text)
        if (title.isBlank()) return
        setBooks(reading.books() + Book(title = title, author = author, pages = pages, started = _today.value))
    }
    fun editBook(id: String, title: String, author: String, pages: Int?) {
        if (title.isBlank()) return
        setBooks(reading.books().map { if (it.id == id) it.copy(title = title.trim(), author = author.trim(), pages = pages) else it })
    }
    /** Log a sitting: minutes, and optionally the page you're now on. Reaching the last page finishes the book. */
    fun logReading(bookId: String, minutes: Int, toPage: Int?, date: LocalDate = _today.value) {
        val book = reading.books().firstOrNull { it.id == bookId } ?: return
        if (minutes <= 0 && toPage == null) return
        val read = toPage?.let { (it - book.page).coerceAtLeast(0) } ?: 0
        setReads(reading.sessions() + ReadSession(bookId = bookId, date = date, minutes = minutes.coerceAtLeast(0), toPage = toPage, pagesRead = read))
        val done = toPage != null && book.pages != null && toPage >= book.pages
        setBooks(reading.books().map {
            if (it.id != bookId) it else it.copy(page = toPage ?: it.page, finished = if (done) (it.finished ?: date) else it.finished)
        })
    }
    fun finishBook(id: String, done: Boolean = true) =
        setBooks(reading.books().map { if (it.id == id) it.copy(finished = if (done) _today.value else null, page = if (done) (it.pages ?: it.page) else it.page) else it })
    fun removeBook(id: String) {
        setBooks(reading.books().filter { it.id != id })
        setReads(reading.sessions().filter { it.bookId != id })
    }
    fun removeRead(id: String) = setReads(reading.sessions().filter { it.id != id })
    private fun setBooks(bs: List<Book>) { reading.saveBooks(bs); _books.value = bs }
    private fun setReads(rs: List<ReadSession>) { reading.saveSessions(rs); _reads.value = rs.sortedByDescending { it.date } }

    fun page(d: LocalDate = _today.value): DayPage = _pages.value[d] ?: DayPage(d)

    /** Yesterday's unfinished things, if you haven't brought them over or dropped them yet. */
    fun leftovers(): List<Task> {
        val p = page()
        if (p.carryDecided) return emptyList()
        val have = p.tasks.map { it.text.lowercase() }.toSet()
        return page(_today.value.minusDays(1)).tasks.filter { !it.done && it.text.lowercase() !in have }
    }

    private fun save(p: DayPage) {
        store.save(p)
        _pages.value = _pages.value + (p.date to p)
    }

    fun add(text: String, main: Boolean, day: LocalDate = _today.value) = save(Journal.add(page(day), text, main))
    fun toggle(id: String, day: LocalDate = _today.value) = save(Journal.toggle(page(day), id))
    fun edit(id: String, text: String, day: LocalDate = _today.value) = save(Journal.edit(page(day), id, text))
    fun remove(id: String, day: LocalDate = _today.value) = save(Journal.remove(page(day), id))
    fun promote(id: String, day: LocalDate = _today.value) = save(Journal.promote(page(day), id))

    /** Move one of today's things to tomorrow. */
    fun toTomorrow(id: String) {
        val t = page().tasks.firstOrNull { it.id == id } ?: return
        remove(id)
        add(t.text, t.main, _today.value.plusDays(1))
    }

    fun bringOver() = save(Journal.carry(page(_today.value.minusDays(1)), page()))
    fun dropLeftovers() = save(page().copy(carryDecided = true))

    fun setMood(m: Int?) = save(page().copy(mood = m))
    fun setReflection(s: String) = save(page().copy(reflection = s))

    fun addShop(text: String) {
        val t = text.trim()
        if (t.isEmpty() || _shopping.value.any { !it.bought && it.text.equals(t, true) }) return
        setShop(_shopping.value + ShopItem(text = t))
    }
    fun toggleShop(id: String) = setShop(_shopping.value.map { if (it.id == id) it.copy(bought = !it.bought) else it })
    fun clearBought() = setShop(_shopping.value.filter { !it.bought })
    private fun setShop(items: List<ShopItem>) { store.saveShopping(items); _shopping.value = items }

    fun addReminder(text: String, at: LocalDateTime) {
        val t = text.trim()
        if (t.isEmpty()) return
        val r = Reminder(text = t, at = at.withSecond(0).withNano(0))
        setReminders(store.reminders() + r)
        Reminders.schedule(getApplication<Application>(), r)
    }
    fun moveReminder(id: String, at: LocalDateTime) {
        val rs = store.reminders()
        val r = rs.firstOrNull { it.id == id }?.copy(at = at.withSecond(0).withNano(0), fired = false) ?: return
        setReminders(rs.map { if (it.id == id) r else it })
        Reminders.schedule(getApplication<Application>(), r)
    }
    fun removeReminder(id: String) {
        Reminders.cancel(getApplication<Application>(), id)
        setReminders(store.reminders().filter { it.id != id })
    }
    private fun setReminders(rs: List<Reminder>) { store.saveReminders(rs); _reminders.value = rs.sortedBy { it.at } }

    fun addNote(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        setNotes(listOf(Note(text = text.trim(), created = now)) + _notes.value)
    }
    fun editNote(id: String, text: String) {
        if (text.isBlank()) return deleteNote(id)
        setNotes(_notes.value.map { if (it.id == id) it.copy(text = text.trim(), updated = System.currentTimeMillis()) else it })
    }
    fun pinNote(id: String) = setNotes(_notes.value.map { if (it.id == id) it.copy(pinned = !it.pinned) else it })
    fun deleteNote(id: String) = setNotes(_notes.value.filter { it.id != id })
    private fun setNotes(ns: List<Note>) { store.saveNotes(ns); _notes.value = ns }
}
