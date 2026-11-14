package com.silverajat.lifttracker.hub

import com.silverajat.lifttracker.insights.Insight
import com.silverajat.lifttracker.insights.Insights
import com.silverajat.lifttracker.insights.Kind
import com.silverajat.lifttracker.insights.Voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    private val draft = Insight("night-1", Kind.NIGHT, "🌙 Day wrap-up",
        "14,200 steps: 10.7 km, or 27 laps of a running track 🎉. Net −320 vs a −300 goal: nailed it 🎯. Lights out by 12:15 AM for your usual 8:00 AM wake-up.", 0)

    @Test fun keepsAGoodRewrite() {
        val r = Voice.check(draft, """{"title": "🌙 14k-step kind of day", "body": "14,200 steps today. Net −320, right on your −300 goal. Bed by 12:15 AM to wake fresh at 8:00 AM."}""")
        assertNotNull(r); assertEquals("🌙 14k-step kind of day", r!!.title)
    }

    @Test fun rejectsInventedNumbers() {
        assertNull(Voice.check(draft, """{"title": "🌙 Big day", "body": "14,200 steps and 2,000 kcal burned."}"""))
    }

    @Test fun rejectsJunk() {
        assertNull(Voice.check(draft, "Sorry, I can't help with that."))
        assertNull(Voice.check(draft, """{"title": "", "body": "x"}"""))
    }

    @Test fun stepComparisonsVaryAndSkipRecentOnes() {
        val seen = (1..30).map { Insights.stepsThing(14_000, "night2026-11-$it") }.toSet()
        assertTrue(seen.toString(), seen.size >= 3)
        val recent = listOf("12,100 steps: 9.1 km, or 23 laps of a running track")
        assertFalse(Insights.stepsThing(14_000, "x", recent).contains("laps of a running track") &&
            (1..10).all { Insights.stepsThing(14_000, "s$it", recent).contains("laps of a running track") })
    }
}
