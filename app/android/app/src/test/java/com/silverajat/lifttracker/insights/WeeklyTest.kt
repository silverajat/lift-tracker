package com.silverajat.lifttracker.insights

import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val monday = LocalDate.of(2026, 11, 16)
    private val today = LocalDate.of(2026, 11, 23)   // the Monday after: last week is complete
    private val ex = Routines.forDay(Day.UPPER).first { !it.bodyweight }

    private fun health(d: LocalDate, hrv: Double) = HealthDay(d, 8000, 2700.0, 600.0, 52, hrv, null, 15.0, null, emptyList(), null)
    private fun gym(d: LocalDate, kg: Double, reps: Int): Session {
        val ms = d.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        return Session(ms / 1000, Day.UPPER, ms, ms + 3_600_000, (1..3).map { SetLog(ex.id, it, kg, 0, reps, false, 0, 0, null, null) })
    }

    private fun report(sessions: List<Session>, lowHrv: Boolean = false): Weekly.Report {
        val h = (0 until 45).map { i -> today.minusDays(44L - i) }.map { d -> health(d, if (lowHrv && d >= monday) 30.0 else 42.0 + (d.dayOfMonth % 3)) }
        val a = Analysis.run(h, sessions, emptyMap(), emptyList(), today, zone)
        return Weekly.build(a, h, sessions, emptyMap(), emptyList(), emptyList(), monday, today, zone)
    }

    @Test fun weekAgainstTheWeekBefore() {
        val base = ex.last.filter { !it.warmup }.maxOf { it.load }
        val s = listOf(gym(monday.minusDays(6), base, 10), gym(monday.minusDays(3), base, 10),
            gym(monday.plusDays(1), base, 12), gym(monday.plusDays(3), base + 10, 15), gym(monday.plusDays(5), base + 10, 15))
        val r = report(s)
        assertTrue(r.complete)
        assertEquals(monday.plusDays(6), r.end)
        assertTrue(r.headline, r.headline.startsWith("3 gym sessions"))
        assertEquals("3", r.training.first { it.label == "Gym sessions" }.value)
        assertEquals("+1", r.training.first { it.label == "Gym sessions" }.change)
        val lift = r.lifts.single()
        assertTrue(lift.best)
        assertTrue("${lift.pct}", (lift.pct ?: 0) > 0)
        assertEquals(7, r.days.size)
        assertEquals(3, r.days.count { it.gym })
        // nothing logged: the first thing to fix is logging food
        assertTrue(r.next.toString(), r.next.any { it.startsWith("Log full days of food") })
        assertTrue(r.next.size in 1..4)
    }

    @Test fun lowHrvWeekSaysGoLighter() {
        val r = report(listOf(gym(monday.plusDays(1), 60.0, 10)), lowHrv = true)
        assertTrue(r.next.toString(), r.next.first().startsWith("Make next week lighter"))
        val hrv = r.recovery.first { it.label == "HRV vs your month" }
        assertEquals(false, hrv.good)
    }

    @Test fun weekSoFar() {
        val r = Weekly.build(Analysis.run(emptyList(), emptyList(), emptyMap(), emptyList(), monday.plusDays(2), zone),
            emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), monday, monday.plusDays(2), zone)
        assertTrue(!r.complete)
        assertEquals(monday.plusDays(2), r.end)
        assertTrue(r.headline, r.headline.startsWith("0 gym sessions"))
    }
}
