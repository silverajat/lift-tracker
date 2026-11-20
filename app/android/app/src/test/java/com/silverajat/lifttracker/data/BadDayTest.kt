package com.silverajat.lifttracker.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BadDayTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private fun session(d: LocalDate, h: Int = 7): Session {
        val ms = d.atTime(h, 30).atZone(zone).toInstant().toEpochMilli()
        return Session(ms / 1000, Day.UPPER, ms, ms + 3_600_000, listOf(SetLog(1, 1, 9.3, 1, 10, false, 0, 0, null, null)))
    }

    @Test fun onlyThe13thGoes() {
        val all = listOf(session(LocalDate.of(2026, 11, 12)), session(LocalDate.of(2026, 11, 13)),
            session(LocalDate.of(2026, 11, 13), 18), session(LocalDate.of(2026, 11, 16)))
        val gone = BadDay.sessionsOn(all, zone)
        assertEquals(2, gone.size)
        assertEquals(setOf(LocalDate.of(2026, 11, 13)), gone.map { java.time.Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }.toSet())
    }
}
