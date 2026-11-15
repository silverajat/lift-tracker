package com.silverajat.lifttracker.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime

class JournalTest {
    private val d = LocalDate.of(2026, 11, 14)

    @Test fun lateNightStillCountsAsTheDayBefore() {
        assertEquals(d, Journal.dayOf(LocalDateTime.of(2026, 11, 15, 1, 30)))
        assertEquals(d.plusDays(1), Journal.dayOf(LocalDateTime.of(2026, 11, 15, 7, 0)))
    }

    @Test fun onlyThreeMainThings() {
        var p = DayPage(d)
        listOf("a", "b", "c", "d").forEach { p = Journal.add(p, it, main = true) }
        assertEquals(listOf("a", "b", "c"), p.mains.map { it.text })
        assertEquals(listOf("d"), p.sides.map { it.text })
        // promoting a side thing is refused while the 3 are full
        assertEquals(p, Journal.promote(p, p.sides[0].id))
        // demote one, then the side thing can go up
        p = Journal.promote(p, p.mains[0].id)
        p = Journal.promote(p, p.sides.first { it.text == "d" }.id)
        assertTrue(p.mains.any { it.text == "d" })
    }

    @Test fun carryBringsOnlyUnfinishedThings() {
        var y = DayPage(d.minusDays(1))
        y = Journal.add(y, "call papa", true); y = Journal.add(y, "claims urex", true); y = Journal.add(y, "buy yogurt", false)
        y = Journal.toggle(y, y.tasks.first { it.text == "claims urex" }.id)
        var t = Journal.add(DayPage(d), "interview prep", true)
        t = Journal.carry(y, t)
        assertEquals(listOf("interview prep", "call papa"), t.mains.map { it.text })
        assertEquals(listOf("buy yogurt"), t.sides.map { it.text })
        assertTrue(t.tasks.filter { it.text != "interview prep" }.all { it.carried })
        assertTrue(t.carryDecided)
    }

    @Test fun weekAndStreak() {
        fun day(n: Long, done: Int, reflect: Boolean): DayPage {
            var p = DayPage(d.minusDays(n))
            repeat(3) { p = Journal.add(p, "t$it", true) }
            p.mains.take(done).forEach { p = Journal.toggle(p, it.id) }
            return if (reflect) p.copy(reflection = "ok", mood = 4) else p
        }
        val pages = listOf(day(0, 1, false), day(1, 3, true), day(2, 2, true), day(3, 0, false)).associateBy { it.date }
        val w = Journal.week(pages, d)
        assertEquals(6, w.mainsDone); assertEquals(12, w.mainsSet)
        assertEquals(2, w.reflected)
        assertEquals(2, w.streak)            // yesterday and the day before; today isn't reflected yet
    }

    @Test fun storeRoundTrip() {
        val dir = Files.createTempDirectory("journal").toFile()
        val s = JournalStore(dir)
        var p = Journal.add(DayPage(d), "gym", true)
        p = Journal.toggle(p, p.tasks[0].id).copy(mood = 5, reflection = "good day")
        s.save(p)
        s.saveNotes(listOf(Note(text = "pod idea: buzz twice", created = 1L, pinned = true)))
        val back = JournalStore(dir)
        assertEquals(p, back.page(d))
        assertEquals("pod idea: buzz twice", back.notes().single().text)
        assertTrue(back.notes().single().pinned)
        assertFalse(back.page(d.plusDays(1)).planned)
    }

    // ---- reminders ----
    private val now = LocalDateTime.of(2026, 11, 15, 14, 48)

    @Test fun reminderPicksTheNextSensibleTime() {
        val p = ReminderText.parse("bring umbrella at 3.30", now)
        assertEquals("Bring umbrella", p.text)
        assertEquals(LocalDateTime.of(2026, 11, 15, 15, 30), p.at)
        assertEquals(LocalDateTime.of(2026, 11, 15, 21, 0), ReminderText.parse("remind me to call mum at 9", now).at)
        assertEquals("Call mum", ReminderText.parse("remind me to call mum at 9", now).text)
        // already past today: tomorrow
        assertEquals(LocalDateTime.of(2026, 11, 16, 14, 0), ReminderText.parse("pay rent at 14:00", now).at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 14, 0), ReminderText.parse("stretch at 2", now).at)
    }

    @Test fun reminderUnderstandsAmPmTomorrowAndIn() {
        val a = ReminderText.parse("gym bag tmr 8am", now)
        assertEquals("Gym bag", a.text)
        assertEquals(LocalDateTime.of(2026, 11, 16, 8, 0), a.at)
        assertEquals(LocalDateTime.of(2026, 11, 15, 17, 15), ReminderText.parse("collect parcel 5:15pm", now).at)
        val b = ReminderText.parse("take out laundry in 20 min", now)
        assertEquals("Take out laundry", b.text)
        assertEquals(LocalDateTime.of(2026, 11, 15, 15, 8), b.at)
        assertEquals(LocalDateTime.of(2026, 11, 15, 16, 48), ReminderText.parse("check oven in 2 hours", now).at)
    }

    @Test fun reminderWithoutATimeAsksForOne() {
        val p = ReminderText.parse("buy milk", now)
        assertEquals("Buy milk", p.text)
        assertEquals(null, p.at)
    }

    @Test fun remindersAreSaved() {
        val dir = Files.createTempDirectory("j").toFile()
        val st = JournalStore(dir)
        st.saveReminders(listOf(Reminder(text = "B", at = now.plusHours(2)), Reminder(text = "A", at = now.plusHours(1), fired = true)))
        val back = JournalStore(dir).reminders()
        assertEquals(listOf("A", "B"), back.map { it.text })
        assertTrue(back[0].fired)
    }

    @Test fun reminderForOtherDays() {   // 15 Nov 2026 is a Sunday
        val a = ReminderText.parse("dentist fri 3pm", now)
        assertEquals("Dentist", a.text)
        assertEquals(LocalDateTime.of(2026, 11, 20, 15, 0), a.at)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), ReminderText.parse("submit lab on 20 nov at 9", now).at)
        assertEquals("Submit lab", ReminderText.parse("submit lab on 20 nov at 9", now).text)
        assertEquals(LocalDateTime.of(2026, 11, 18, 7, 0), ReminderText.parse("call papa in 3 days at 7", now).at)
        assertEquals(LocalDateTime.of(2026, 12, 25, 10, 0), ReminderText.parse("buy flowers 25/12 10am", now).at)
        assertEquals(LocalDateTime.of(2026, 11, 16, 15, 0), ReminderText.parse("tmr at 3 return book", now).at)
    }

    @Test fun reminderWithADayButNoTimeKeepsTheDay() {
        val p = ReminderText.parse("pay bills next sunday", now)
        assertEquals("Pay bills", p.text)
        assertEquals(null, p.at)
        assertEquals(LocalDate.of(2026, 11, 22), p.day)
        val q = ReminderText.parse("sun cream", now)   // not a day
        assertEquals("Sun cream", q.text)
        assertEquals(null, q.day)
    }

    // ---- reading ----
    @Test fun readingParsesBooks() {
        assertEquals(Triple("Dune", "Frank Herbert", 600), Reading.parseBook("dune by Frank Herbert, 600"))
        assertEquals(Triple("Atomic Habits", "", 320), Reading.parseBook("Atomic Habits 320 pages"))
        assertEquals(Triple("Catch 22", "", null), Reading.parseBook("Catch 22"))
    }

    @Test fun readingXpIsCappedPerDay() {
        val d = LocalDate.of(2026, 11, 15)
        val xp = Reading.xp(listOf(ReadSession(bookId = "a", date = d, minutes = 60), ReadSession(bookId = "a", date = d, minutes = 60),
            ReadSession(bookId = "a", date = d.minusDays(1), minutes = 20)))
        assertEquals(listOf(20, 60, 30), xp.map { it.second })
        assertEquals(2, Reading.streak(xp.map { it.first }, d))
        assertEquals(2, Reading.streak(xp.map { it.first }, d.plusDays(1)))   // nothing yet today: still counts to yesterday
    }

    @Test fun readingIsSaved() {
        val dir = Files.createTempDirectory("r").toFile()
        val st = ReadingStore(dir)
        val d = LocalDate.of(2026, 11, 15)
        st.saveBooks(listOf(Book("x", "Dune", "Frank Herbert", 600, 120, d), Book("y", "Ender", "", null, 0, d, d)))
        st.saveSessions(listOf(ReadSession("s", "x", d, 30, 120, 20)))
        val r = ReadingStore(dir)
        assertEquals(listOf("Dune", "Ender"), r.books().map { it.title })
        assertEquals(600, r.books()[0].pages)
        assertEquals(d, r.books()[1].finished)
        assertEquals(null, r.books()[1].pages)
        assertEquals(20, r.sessions()[0].pagesRead)
    }
}
