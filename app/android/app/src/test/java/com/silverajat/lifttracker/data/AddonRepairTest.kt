package com.silverajat.lifttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class AddonRepairTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private fun at(d: LocalDate) = d.atTime(7, 30).atZone(zone).toInstant().toEpochMilli()
    private fun set(ex: Int, kg: Double, addons: Int, warmup: Boolean = false) =
        SetLog(ex, 1, kg, addons, 10, warmup, 0, 0, null, null)
    private fun session(d: LocalDate, vararg sets: SetLog) = Session(at(d) / 1000, Day.UPPER, at(d), at(d) + 3_600_000, sets.toList())

    @Test fun takesTheTypedAddonBackOutOfTheWeight() {
        val nov13 = session(LocalDate.of(2026, 11, 13),
            set(LATERAL, 9.3, 1),          // typed 8.3 + 1 kg, and the add-on recorded too
            set(PRESS, 47.5, 1),           // already fixed by hand: 47.5 is the usual weight, leave it
            set(CURL, 12.0, 0))            // no add-on: untouched
        val others = listOf(
            session(LocalDate.of(2026, 11, 16), set(LATERAL, 8.3, 0), set(PRESS, 47.5, 1)),
            session(LocalDate.of(2026, 11, 18), set(LATERAL, 8.3, 0), set(PRESS, 47.5, 1)),
        )
        val fixed = AddonRepair.repair(others + nov13, zone)
        assertEquals(1, fixed.size)
        val s = fixed.single().sets
        assertEquals(8.3, s[0].weight, 1e-9)
        assertEquals(9.3, s[0].load, 1e-9)      // 8.3 + one 1 kg add-on: counted once
        assertEquals(47.5, s[1].weight, 1e-9)
        assertEquals(12.0, s[2].weight, 1e-9)
        // other days are never touched
        assertTrue(AddonRepair.repair(others, zone).isEmpty())
    }

    private companion object { const val LATERAL = 1; const val PRESS = 2; const val CURL = 3 }
}
