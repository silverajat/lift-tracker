package com.silverajat.lifttracker.activity

import com.silverajat.lifttracker.health.HrSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SportAnalysisTest {
    private val t0 = Instant.parse("2026-11-14T02:00:00Z")

    /** 60 min of badminton every 5 s: ~140 bpm with a 40 s rally burst to ~176 every 5 min, then 3 min of rest after. */
    fun game(minutes: Int = 60, fade: Boolean = false): List<HrSample> = (0..(minutes + 3) * 12).map { i ->
        val sec = i * 5L
        val bpm = when {
            sec > minutes * 60 -> (150 - (sec - minutes * 60) / 2).toInt().coerceAtLeast(100)   // cool-down after the end
            sec % 300 in 0..40 -> 176
            else -> 140 - (if (fade && sec > minutes * 30) 12 else 0)
        }
        HrSample(t0.plusSeconds(sec), bpm)
    }

    @Test fun badmintonBreakdown() {
        val end = t0.plusSeconds(3600)
        val r = SportAnalysis.analyze(Sport.BADMINTON, t0, end, game(), 55, 195)!!
        assertEquals(176, r.max)
        assertEquals(12, r.bursts)                      // one every 5 min
        assertEquals(6, r.blocks.size)                  // 10-min blocks over an hour
        assertEquals(10, r.blockMin)
        assertTrue(r.zoneMin.sum() in 55..61)
        assertTrue(r.zoneMin[4] >= 6)                  // ~8 min of bursts at 176 (90% of 195 = 175.5)
        assertNotNull(r.recovery)
        assertTrue(r.notes.any { it.contains("hard bursts") })
        assertTrue(r.notes.any { it.contains("Even effort") })
    }

    @Test fun fadingSecondHalfIsCalledOut() {
        val r = SportAnalysis.analyze(Sport.BADMINTON, t0, t0.plusSeconds(3600), game(fade = true), 55, 195)!!
        assertTrue(r.notes.any { it.startsWith("You eased off") })
    }

    @Test fun comparesWithYourUsualGames() {
        fun past(days: Long, strain: Double) = ActivityItem("m$days", Sport.BADMINTON, "Badminton", t0.minusSeconds(days * 86400),
            t0.minusSeconds(days * 86400 - 3600), 60, null, 140, 175, strain, "Logged")
        val r = SportAnalysis.analyze(Sport.BADMINTON, t0, t0.plusSeconds(3600), game(), 55, 195, strain = 12.0,
            past = listOf(past(3, 9.0), past(7, 9.4), past(10, 8.8)))!!
        assertEquals(3, r.usualCount)
        assertTrue(r.notes.any { it.startsWith("Harder than your usual badminton") })
    }

    @Test fun tooLittleDataGivesNothing() {
        assertNull(SportAnalysis.analyze(Sport.BADMINTON, t0, t0.plusSeconds(3600), game().take(5), 55, 195))
    }
}
