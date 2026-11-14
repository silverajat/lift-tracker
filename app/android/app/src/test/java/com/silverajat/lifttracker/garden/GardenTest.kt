package com.silverajat.lifttracker.garden

import com.silverajat.lifttracker.journal.DayPage
import com.silverajat.lifttracker.journal.Journal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId

class GardenTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val today = LocalDate.of(2026, 11, 14)
    private val t0 = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()

    @Test fun levels() {
        assertEquals(1, Garden.level(0)); assertEquals(1, Garden.level(99))
        assertEquals(2, Garden.level(100)); assertEquals(3, Garden.level(300)); assertEquals(5, Garden.level(1000))
        assertEquals(0.5f, Garden.progress(200), 0.001f)
    }

    @Test fun timeInOtherAppsDoesntCount() {
        val a = ActiveFocus(Area.STUDY, "EE2023", t0, awayS = 300, awaySince = t0 + 50 * 60_000L)
        // 60 min elapsed: 5 min away earlier + 10 min away right now
        assertEquals(45 * 60L, a.focusedS(t0 + 60 * 60_000L))
        val s = FocusSession(area = Area.STUDY, label = "", startMs = t0, endMs = t0 + 60 * 60_000L, awayS = 900)
        assertEquals(45, s.minutes)
    }

    @Test fun eachAreaGrowsFromItsOwnData() {
        var p = DayPage(today)
        repeat(3) { p = Journal.add(p, "t$it", true) }
        p.mains.take(2).forEach { p = Journal.toggle(p, it.id) }
        p = p.copy(reflection = "good")
        val focus = listOf(
            FocusSession(area = Area.STUDY, label = "EE2023", startMs = t0, endMs = t0 + 90 * 60_000L),
            FocusSession(area = Area.WORK, label = "app", startMs = t0, endMs = t0 + 30 * 60_000L),
        )
        val plants = Garden.plants(Garden.Inputs(focus = focus, pages = mapOf(today to p)), today, zone, t0 + 100 * 60_000L).associateBy { it.area }
        assertEquals(90, plants[Area.STUDY]!!.xp)
        assertEquals(30, plants[Area.WORK]!!.xp)
        assertEquals(10 + 30 + 25, plants[Area.MIND]!!.xp)
        assertEquals("1h 30m", plants[Area.STUDY]!!.week)
        assertEquals(0, plants[Area.GYM]!!.xp)
        assertTrue(plants[Area.GYM]!!.dryDays >= 99)
    }

    @Test fun storeKeepsTheRunningBlock() {
        val dir = Files.createTempDirectory("focus").toFile()
        val s = FocusStore(dir)
        s.setActive(ActiveFocus(Area.WORK, "pod firmware", t0, 60, t0 + 1000))
        s.saveSessions(listOf(FocusSession(area = Area.STUDY, label = "x", startMs = t0, endMs = t0 + 600_000)))
        val back = FocusStore(dir)
        assertEquals(ActiveFocus(Area.WORK, "pod firmware", t0, 60, t0 + 1000), back.active())
        assertEquals(10, back.sessions().single().minutes)
        back.setActive(null)
        assertEquals(null, FocusStore(dir).active())
    }
}
