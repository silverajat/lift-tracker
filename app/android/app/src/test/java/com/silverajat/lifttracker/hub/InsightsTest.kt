package com.silverajat.lifttracker.hub

import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.health.Workout
import com.silverajat.lifttracker.insights.ActivityStats
import com.silverajat.lifttracker.insights.Insights
import com.silverajat.lifttracker.insights.WeatherApi
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.Slot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class InsightsTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val day = LocalDate.of(2026, 11, 13)
    private fun ms(h: Int, m: Int = 0) = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private val weatherJson = """
        {"daily":{"time":["2026-11-13"],"temperature_2m_max":[31.6],"temperature_2m_min":[25.1],
          "precipitation_probability_max":[80],"uv_index_max":[11.2]},
         "hourly":{"precipitation_probability":[${(0 until 24).joinToString(",") { if (it in 15..17) "80" else "10" }}],
          "temperature_2m":[${(0 until 24).joinToString(",") { "28.0" }}]}}
    """.trimIndent()

    @Test fun weather() {
        val w = WeatherApi.parse(JSONObject(weatherJson))
        assertEquals(24, w.hourlyRainPct.size)
        val line = Insights.weatherLine(w)
        assertTrue(line, line.contains("32°C") && line.contains("rain likely 3 PM-6 PM") && line.contains("UV 11"))
        assertNotNull(Insights.rainSoon(w, day.atTime(13, 30)))
        assertNull(Insights.rainSoon(w, day.atTime(9, 0)))
        assertNull(Insights.rainSoon(w, LocalDateTime.of(day.plusDays(1), java.time.LocalTime.of(13, 0))))
    }

    @Test fun eveningFood() {
        val lunch = MealEntry("1", day, Slot.LUNCH, "salad_egg", "salad", Foods.get("salad_egg").macros, ms(12))
        val shake = MealEntry("2", day, Slot.POST_WORKOUT, "shake", "shake", Foods.get("shake").macros, ms(9))
        val i = Insights.evening(listOf(lunch, shake), day, ms(20, 30))
        assertTrue(i.body, i.body.contains("Meiji"))          // 74 g + bowl ~16 g: well short
        val empty = Insights.evening(emptyList(), day, ms(20, 30))
        assertEquals("Nothing logged today", empty.title)
    }

    @Test fun walkAfterLunch() {
        val lunch = MealEntry("1", day, Slot.LUNCH, "chicken_rice", "cr", Foods.get("chicken_rice").macros, ms(12, 30))
        val w = Workout(java.time.Instant.ofEpochMilli(ms(12, 50)), java.time.Instant.ofEpochMilli(ms(13, 8)), "Walk", null)
        val i = Insights.activity(w, ActivityStats(2100, 104, 121, 130.0), listOf(lunch), ms(13, 10))
        assertEquals("Walk · 18 min", i.title)
        assertTrue(i.body, i.body.contains("after-lunch walk done"))
    }

    @Test fun workoutSummary() {
        val ex = Routines.forDay(Day.UPPER).first()
        fun set(n: Int, w: Double, reps: Int, startH: Int, q: Int?) =
            SetLog(ex.id, n, w, 0, reps, false, ms(startH, n * 4), ms(startH, n * 4 + 1), reps, null, quality = q)
        val old = Session(1, Day.UPPER, ms(8) - 7 * 86_400_000L, ms(9) - 7 * 86_400_000L,
            listOf(set(1, 50.0, 8, 8, null), set(2, 50.0, 8, 8, null)).map { it.copy(startMs = it.startMs - 7 * 86_400_000L) })
        val now = Session(2, Day.UPPER, ms(8), ms(9), listOf(set(1, 55.0, 8, 8, 90), set(2, 55.0, 8, 8, 85), set(3, 55.0, 7, 8, 60)))
        val i = Insights.workout(now, listOf(old, now), zone)
        assertEquals("Upper day done · 60 min", i.title)
        assertTrue(i.body, i.body.contains("3 working sets"))
        assertTrue(i.body, i.body.contains("New best: ${ex.name}"))
        assertTrue(i.body, i.body.contains("Weakest"))
    }
}
