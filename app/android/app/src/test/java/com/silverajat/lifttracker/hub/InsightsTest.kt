package com.silverajat.lifttracker.hub

import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.health.Energy
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.Workout
import com.silverajat.lifttracker.insights.ActivityStats
import com.silverajat.lifttracker.insights.Insights
import com.silverajat.lifttracker.insights.WeatherApi
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.Macros
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.WeightEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

class InsightsTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val day = LocalDate.of(2026, 11, 13)
    private fun ms(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

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
        assertNull(Insights.rainSoon(w, LocalDateTime.of(day.plusDays(1), LocalTime.of(13, 0))))
    }

    private fun hd(d: LocalDate, kcal: Double) = HealthDay(d, 9000, kcal, 700.0, 55, 60.0, 97.0, 15.0, null, emptyList(), null)
    private fun meal(d: LocalDate, kcal: Double) = MealEntry(d.toString(), d, Slot.LUNCH, null, "food", Macros(kcal, 0.0, 0.0, 0.0), ms(12, 0, d))

    /** Fitbit says 3,000 but he really burns 2,700; he eats 2,700 and his weight stays flat (with water noise). */
    @Test fun energyLearnsFitbitIsHigh() {
        val start = day.minusDays(35)
        val rnd = java.util.Random(1)
        val days = (0 until 35).map { start.plusDays(it.toLong()) }
        val meals = days.associateWith { listOf(meal(it, 2700.0)) }
        val weights = days.map { WeightEntry(it, 70.0 + rnd.nextGaussian() * 0.4, 0) }
        val health = days.associateWith { hd(it, 3000.0) }
        val e = Energy.estimate(meals, weights, health, day)
        assertTrue("k ${e.k}", abs(e.k - 0.9) < 0.04)
        assertTrue("fitbit off ${e.fitbitOffPct}", e.fitbitOffPct in 7..16)
        assertTrue("avg net ${e.avgNet}", abs(e.avgNet!!) < 150)
        assertTrue(e.confidence >= 50)
    }

    /** Eats 3,000 with a real burn of 2,700: should gain ~0.27 kg/week and show net ~+300. */
    @Test fun energySurplusFromScale() {
        val start = day.minusDays(42)
        val days = (0 until 42).map { start.plusDays(it.toLong()) }
        val meals = days.associateWith { listOf(meal(it, 3000.0)) }
        val weights = days.mapIndexed { i, d -> WeightEntry(d, 70.0 + i * 300.0 / 7700 + if (i % 2 == 0) 0.3 else -0.3, 0) }
        val health = days.associateWith { hd(it, 2700.0) }
        val e = Energy.estimate(meals, weights, health, day)
        assertTrue("k ${e.k}", abs(e.k - 1.0) < 0.05)
        assertTrue("net ${e.avgNet}", e.avgNet!! in 200..400)
        assertTrue("trend ${e.trendKgPerWeek}", e.trendKgPerWeek!! in 0.15..0.4)
        // unlogged days don't count as huge deficits
        val gaps = meals.filterKeys { it.dayOfMonth % 3 != 0 }
        val e2 = Energy.estimate(gaps, weights, health, day)
        assertTrue("k with gaps ${e2.k}", abs(e2.k - 1.0) < 0.08)
    }

    @Test fun eveningNet() {
        val e = Energy.estimate(emptyMap(), emptyList(), mapOf(day to hd(day, 2500.0)), day)
        val lunch = MealEntry("1", day, Slot.LUNCH, "salad_egg", "salad", Foods.get("salad_egg").macros, ms(12))
        val i = Insights.evening(e, listOf(lunch), 0.25, day, LocalTime.of(20, 30), ms(20, 30))
        assertTrue(i.title, i.title.contains("Net"))
        assertEquals("🍽️ Nothing logged today", Insights.evening(e, emptyList(), 0.25, day, LocalTime.of(20, 30), 0).title)
    }

    @Test fun walkAfterLunch() {
        val lunch = MealEntry("1", day, Slot.LUNCH, "chicken_rice", "cr", Foods.get("chicken_rice").macros, ms(12, 30))
        val w = Workout(java.time.Instant.ofEpochMilli(ms(12, 50)), java.time.Instant.ofEpochMilli(ms(13, 8)), "Walk", null)
        val i = Insights.activity(w, ActivityStats(2100, 104, 121, 130.0), listOf(lunch), ms(13, 10))
        assertTrue(i.title, i.title.contains("✓") || i.title.contains("complete"))
        assertTrue(i.body, i.body.contains("2,100 steps"))
    }

    @Test fun workoutAndNight() {
        val ex = Routines.forDay(Day.UPPER).first()
        fun set(n: Int, w: Double, reps: Int, q: Int?, d: LocalDate = day) =
            SetLog(ex.id, n, w, 0, reps, false, ms(8, n * 4, d), ms(8, n * 4 + 1, d), reps, null, quality = q)
        val wk = day.minusDays(7)
        val old = Session(1, Day.UPPER, ms(8, 0, wk), ms(9, 0, wk), listOf(set(1, 50.0, 8, null, wk), set(2, 50.0, 8, null, wk)))
        val now = Session(2, Day.UPPER, ms(8), ms(9), listOf(set(1, 55.0, 8, 90), set(2, 55.0, 8, 85), set(3, 55.0, 7, 60)))
        val i = Insights.workout(now, listOf(old, now), zone)
        assertTrue(i.title, i.title.contains("PR") || i.title.contains("best"))
        assertTrue(i.body, i.body.contains("60 min, 3 working sets"))
        assertTrue(i.body, i.body.contains("Toughest"))
        val a = com.silverajat.lifttracker.health.Analysis.run(emptyList(), listOf(old, now), emptyMap(), emptyList(), day, zone)
        val n = Insights.night(a, hd(day, 2800.0), listOf(old, now), emptyList(), day, zone, LocalTime.of(21, 45), 0)
        assertTrue(n.body, n.body.contains("9,000 steps") && n.body.contains("Upper day ✓"))
        assertEquals("about 1.9 Asian elephants", Insights.heavyThing(7_420.0))
    }
}
