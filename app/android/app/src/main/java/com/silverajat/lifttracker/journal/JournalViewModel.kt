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

    init { refresh() }

    /** Re-reads the file and moves to a new day after 4 AM. */
    fun refresh() {
        _today.value = Journal.dayOf(LocalDateTime.now())
        _pages.value = store.pages()
        _notes.value = store.notes()
    }

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
