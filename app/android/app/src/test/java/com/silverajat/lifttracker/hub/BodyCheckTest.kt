package com.silverajat.lifttracker.hub

import com.silverajat.lifttracker.health.BodyCheck
import com.silverajat.lifttracker.health.BodyCheck.State
import com.silverajat.lifttracker.health.HealthDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BodyCheckTest {
    private val start = LocalDate.of(2026, 10, 10)

    private fun night(i: Int, rr: Double = 15.0 + (i % 3) * 0.2, rhr: Int = 52 + i % 3, hrv: Double = 42.0 + (i % 3) * 3, spo2: Double = 97.0) =
        HealthDay(start.plusDays(i.toLong()), 8000, 2700.0, 600.0, rhr, hrv, spo2, rr, null, emptyList(), null)

    private val normal = (0 until 30).map { night(it) }
    private val today = start.plusDays(31)

    @Test fun allNormal() {
        val r = BodyCheck.run(normal + night(30) + night(31), emptyMap(), today)!!
        assertEquals(State.NORMAL, r.state)
        assertTrue(r.reasons.isEmpty())
    }

    /** Breathing up, resting HR up, HRV down, two nights running: fighting something off. */
    @Test fun gettingSick() {
        val sick = { i: Int -> night(i, rr = 16.6, rhr = 59, hrv = 32.0) }
        val r = BodyCheck.run(normal + sick(30) + sick(31), emptyMap(), today)!!
        assertEquals(State.SICK, r.state)
        val top = r.reasons.first()
        assertEquals("Getting sick", top.title)
        assertTrue(top.likely)
        assertTrue(top.evidence.toString(), top.evidence.any { it.startsWith("Breathing rate 16.6") })
        assertEquals(start.plusDays(30), r.since)
    }

    /** Heart signs off after a huge day, breathing normal: that's training, not illness. */
    @Test fun hardTrainingIsNotIllness() {
        val days = normal + night(30) + night(31, rhr = 57, hrv = 36.0)
        val ctx = mapOf(start.plusDays(30) to BodyCheck.Context(17.0, 140, 13.0, 90, 0, null))
        val r = BodyCheck.run(days, ctx, today)!!
        assertEquals(State.STRAINED, r.state)
        assertEquals("Recovering from yesterday's training", r.reasons.first().title)
        assertTrue(r.reasons.none { it.title == "Getting sick" })
    }

    /** Heart signs off with nothing to explain them: stress, alcohol, an injury. */
    @Test fun unexplainedHeartSigns() {
        val r = BodyCheck.run(normal + night(30) + night(31, rhr = 57, hrv = 36.0), emptyMap(), today)!!
        assertEquals(State.WATCH, r.state)
        assertTrue(r.reasons.toString(), r.reasons.any { it.title == "Stress, alcohol or an injury" })
    }

    /** After a sick spell: which signs are back and which aren't yet. */
    @Test fun settlingDown() {
        val sick = { i: Int -> night(i, rr = 16.6, rhr = 59, hrv = 32.0) }
        val days = normal.take(27) + sick(27) + sick(28) + sick(29) + night(30) + night(31, rhr = 57)
        val r = BodyCheck.run(days, emptyMap(), today)!!
        assertEquals(State.SETTLING, r.state)
        val ev = r.reasons.first().evidence
        assertTrue(ev.toString(), ev.any { it.startsWith("Breathing rate back to normal") })
        assertTrue(ev.toString(), ev.any { it.startsWith("Resting HR still") })
        assertEquals(start.plusDays(27), r.since)
    }
}
