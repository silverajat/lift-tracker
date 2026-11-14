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
}
