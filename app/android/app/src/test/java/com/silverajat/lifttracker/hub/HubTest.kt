package com.silverajat.lifttracker.hub

import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.SleepSummary
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.MealPlan
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.WeightEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HubTest {
    private val zone = ZoneId.of("Asia/Singapore")
    private val tue = LocalDate.of(2026, 11, 10)
    private val sat = LocalDate.of(2026, 11, 14)

    @Test fun mealPlanRotation() {
        assertEquals("salad_carrot", MealPlan.salad(tue.dayOfWeek).id)
        assertEquals("salad_egg", MealPlan.salad(tue.minusDays(1).dayOfWeek).id)
        assertEquals(listOf("chicken_rice", "mixed_rice"), MealPlan.mains(sat.dayOfWeek).map { it.id })
        assertEquals("blueberries", MealPlan.fruit(tue.dayOfWeek).single().id)
        val satPlan = MealPlan.day(sat)
        assertEquals(2, satPlan.count { it.slot == Slot.YOGHURT && it.food.id in setOf("guava", "dragon_fruit") })
        assertEquals(1, satPlan.count { it.optional })
        val t = MealPlan.plannedTotal(tue)
        assertTrue("planned kcal ${t.kcal}", t.kcal in 2400.0..3000.0)
        assertTrue("planned protein ${t.protein}", t.protein in 130.0..170.0)
    }

    @Test fun weekAveragesOnlyCompletedWeeks() {
        val today = LocalDate.of(2026, 11, 13)            // Friday
        val w = (0 until 18).map { WeightEntry(today.minusDays(it.toLong()), 70.0 + it * 0.1, 0) }
        val weeks = MealStore.weekAverages(w, today)
        assertEquals(LocalDate.of(2026, 11, 2), weeks[0].weekStart)
        assertEquals(7, weeks[0].days)
        assertEquals(70.0 + (5 + 11) / 2.0 * 0.1, weeks[0].avgKg, 1e-9)
        val r = Analysis.weight(w, today)
        assertNotNull(r.changeKg)
        assertTrue(r.changeKg!! < 0)                       // weights go down towards today
    }

    @Test fun strainScale() {
        assertEquals(0.0, Analysis.strainOf(0), 1e-9)
        assertTrue(Analysis.strainOf(110) in 13.0..13.5)
        assertTrue(Analysis.strainOf(1000) < 21.0)
        assertEquals(Analysis.Zone.GREEN, Analysis.zoneOf(67))
        assertEquals(Analysis.Zone.YELLOW, Analysis.zoneOf(34))
        assertEquals(Analysis.Zone.RED, Analysis.zoneOf(33))
    }

    private fun day(d: LocalDate, hrv: Double?, rhr: Int?, sleepMin: Int = 450) = HealthDay(
        date = d, steps = 8000, kcalTotal = 2900.0, kcalActive = 600.0, restingHr = rhr, hrvMs = hrv, spo2 = 97.0,
        respRate = 15.0, workouts = emptyList(), load = null,
        sleep = SleepSummary(
            start = d.minusDays(1).atTime(23, 30).atZone(zone).toInstant(),
            end = d.minusDays(1).atTime(23, 30).plusMinutes(sleepMin + 20L).atZone(zone).toInstant(),
            totalMin = sleepMin, deepMin = 80, remMin = 100, lightMin = sleepMin - 180, awakeMin = 20,
        ),
    )

    @Test fun recoveryZones() {
        val start = LocalDate.of(2026, 10, 1)
        val base = (0 until 20).map { i -> day(start.plusDays(i.toLong()), if (i % 2 == 0) 55.0 else 65.0, if (i % 2 == 0) 55 else 57) }
        val good = base + day(start.plusDays(20), 85.0, 50, 480)
        val bad = base + day(start.plusDays(20), 35.0, 66, 300)
        val g = Analysis.recovery(good, 20, 95)!!
        val b = Analysis.recovery(bad, 20, 60)!!
        assertEquals(Analysis.Zone.GREEN, g.zone)
        assertEquals(Analysis.Zone.RED, b.zone)
        assertNull(Analysis.recovery(base.take(3), 2, 90))     // no baseline yet
        val s = Analysis.sleep(bad, 20, 16.0, zone)!!
        assertTrue("need ${s.needMin}", s.needMin > 480)
        assertTrue(s.performance < 70)
    }

    @Test fun habitEffects() {
        val start = LocalDate.of(2026, 10, 1)
        val habit = (0 until 20).associate { start.plusDays(it.toLong()) to (it % 2 == 0) }
        val rec = (0 until 21).associate { start.plusDays(it.toLong()) to if ((it - 1) % 2 == 0) 80 + it % 3 else 45 + it % 3 }
        val e = Analysis.effects(mapOf("test" to habit), rec).single()
        assertTrue(e.delta > 25)
        assertTrue(e.clear)
    }

    @Test fun runWithNoData() {
        val r = Analysis.run(emptyList(), emptyList(), emptyMap(), emptyList(), LocalDate.of(2026, 11, 13), zone)
        assertTrue(r.days.isEmpty())
        assertNull(r.nutrition)
        val withHealth = (0 until 10).map { day(LocalDate.of(2026, 11, 4).plusDays(it.toLong()), 60.0, 55) }
        val r2 = Analysis.run(withHealth, emptyList(),
            mapOf(LocalDate.of(2026, 11, 12) to listOf(com.silverajat.lifttracker.meals.MealEntry("a", LocalDate.of(2026, 11, 12),
                Slot.LUNCH, "shake", "x", Foods.get("shake").macros, 0))), emptyList(), LocalDate.of(2026, 11, 13), zone)
        assertEquals(10, r2.days.size)
        assertNotNull(r2.nutrition)
    }
}
