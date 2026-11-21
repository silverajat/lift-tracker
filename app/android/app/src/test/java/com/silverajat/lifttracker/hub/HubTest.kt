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
        assertTrue("planned kcal ${t.kcal}", t.kcal in 2100.0..3000.0)   // ~2,300 with label values
        assertTrue("planned protein ${t.protein}", t.protein in 110.0..170.0)
    }

    @Test fun recompTargets() {
        val t = com.silverajat.lifttracker.meals.Targets.day(56.6, 2700.0, 0.0)
        assertEquals(2700, t.kcal)
        assertEquals(124, t.protein)          // 2.2 g/kg
        assertEquals(60, t.fat)               // 20% of calories
        assertTrue(t.carbs.toString(), t.carbs in 410..430)   // the rest
        assertEquals(37, t.fibre)             // 14 g per 1,000 kcal
        assertEquals(26, com.silverajat.lifttracker.meals.Targets.day(56.6, 1500.0, 0.0).fibre)   // never under HPB's 26 g
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

    /** A month of rock-steady readings mustn't make a 2 bpm bump look like a crash. */
    @Test fun steadyBaselineDoesntOverreact() {
        val start = LocalDate.of(2026, 10, 1)
        val base = (0 until 25).map { i -> day(start.plusDays(i.toLong()), 44.0 + (i % 2), 50) }
        val today = base + day(start.plusDays(25), 44.0, 52, 450)
        val r = Analysis.recovery(today, 25, 90)!!
        // resting HR 2 above a perfectly flat 50: about one "usual spread" worse, not a red day
        assertTrue(r.score.toString(), r.score in 40..60)
    }

    /** The normal follows you: after a few weeks at 52, 52 is normal again. */
    @Test fun baselineFollowsYou() {
        val start = LocalDate.of(2026, 9, 1)
        val days = (0 until 30).map { i -> day(start.plusDays(i.toLong()), 50.0 + (i % 3), 50 + (i % 2)) } +
            (30 until 62).map { i -> day(start.plusDays(i.toLong()), 50.0 + (i % 3), 52) }
        val r = Analysis.recovery(days, 61, 90)!!
        val rhr = r.contributors.first { it.name == "Resting HR" }
        assertTrue(rhr.vsNormal, rhr.vsNormal.startsWith("normal 52"))
    }

    @Test fun sleepScoreIsMoreThanHours() {
        val (full, _) = Analysis.sleepScore(100, 100, 95, 18, 24)
        assertEquals(100, full)
        // enough hours but messy timing, restless and light: not a great night
        val (messy, parts) = Analysis.sleepScore(100, 40, 78, 8, 15)
        assertTrue(messy.toString(), messy in 60..75)
        assertEquals(5, parts.size)
        // only hours known: the score is just hours
        assertEquals(80, Analysis.sleepScore(80, null, null, null, null).first)
    }

    /** Bedtime follows tomorrow's usual wake-up: a lie-in Saturday means a later lights-out on Friday. */
    @Test fun bedtimeFollowsTomorrow() {
        val fri = LocalDate.of(2026, 11, 20)
        val h = (0 until 28).map { i -> fri.minusDays(27L - i) }.map { d -> day(d, 45.0, 52, if (d.dayOfWeek == java.time.DayOfWeek.SATURDAY) 570 else 450) }
        val r = Analysis.run(h, emptyList(), emptyMap(), emptyList(), fri, zone)
        val sat = Analysis.tonight(r, fri, zone)
        assertEquals("Saturdays", sat.wakeFrom)
        assertEquals(java.time.LocalTime.of(9, 20), sat.wake)
        val thu = Analysis.tonight(r, fri.minusDays(1), zone)
        assertEquals("Fridays", thu.wakeFrom)
        assertEquals(java.time.LocalTime.of(7, 20), thu.wake)
        // lights out = wake − need ÷ efficiency − 15 min
        assertTrue("${thu.bed}", thu.bed!! in java.time.LocalTime.of(23, 0)..java.time.LocalTime.of(23, 30))
    }

    /** Owing sleep doesn't put lights out hours before you'd ever fall asleep: 45 min before your usual at most. */
    @Test fun bedtimeStaysNearYourUsual() {
        val sat = LocalDate.of(2026, 11, 21)
        // asleep 23:30 every night, 6 h 40 m: an hour owed after three nights
        val h = (0 until 28).map { i -> day(sat.minusDays(27L - i), 45.0, 52, 400) }
        val r = Analysis.run(h, emptyList(), emptyMap(), emptyList(), sat, zone)
        val t = Analysis.tonight(r, sat, zone)
        assertTrue("${t.needMin}", t.needMin >= 500)
        assertEquals(java.time.LocalTime.of(23, 30), t.usualBed)
        assertEquals(java.time.LocalTime.of(22, 45), t.bed)
        assertTrue("${t.shortMin}", t.shortMin in 60..100)
        // owing nothing: the plain sum, no cap needed
        val ok = Analysis.run((0 until 28).map { i -> day(sat.minusDays(27L - i), 45.0, 52, 450) }, emptyList(), emptyMap(), emptyList(), sat, zone)
        assertEquals(0, Analysis.tonight(ok, sat, zone).shortMin)
    }

    /** Lots of REM can't make up for very little deep: they're scored separately. */
    @Test fun deepAndRemScoredSeparately() {
        val (balanced, _) = Analysis.sleepScore(100, 100, 94, 16, 22)
        val (remHeavy, parts) = Analysis.sleepScore(100, 100, 94, 3, 36)
        assertEquals(100, balanced)
        assertEquals(0, parts.first { it.name == "Deep" }.score)
        assertTrue("$remHeavy", remHeavy <= 92)
        // last night on the screenshot: 10% deep, 25% REM, 94% efficient
        val (n, p2) = Analysis.sleepScore(100, 90, 94, 10, 25)
        assertEquals(50, p2.first { it.name == "Deep" }.score)
        assertTrue("$n", n in 90..97)
    }

    /** Debt is the NET shortfall: a long night pays back a short one. */
    @Test fun sleepDebtResetsWhenYouCatchUp() {
        val short = Analysis.sleepNeed(null, listOf(390, 390, 390))      // 3 nights an hour short
        assertEquals(180, short.owedMin)
        assertEquals(60, short.payBackMin)                                // half of it, at most 60
        val caughtUp = Analysis.sleepNeed(null, listOf(570, 390, 390))   // then a 9.5 h night
        assertEquals(0, caughtUp.owedMin)
        assertEquals(450, caughtUp.total)
        assertEquals(450 + 24, Analysis.sleepNeed(16.0, emptyList()).total)   // strain 16: +24 min
    }

    /** One wild HRV reading mustn't stretch "normal" so much that later warnings look normal. */
    @Test fun oneOutlierDoesntWidenTheBaseline() {
        val start = LocalDate.of(2026, 10, 1)
        val base = (0 until 25).map { i -> day(start.plusDays(i.toLong()), if (i == 10) 140.0 else 40.0 + (i % 3) * 2, 50 + (i % 2)) }
        val low = base + day(start.plusDays(25), 34.0, 51, 450)
        val r = Analysis.recovery(low, 25, 90)!!
        assertEquals(r.score.toString(), Analysis.Zone.RED, r.zone)
    }

    @Test fun recoveryZones() {
        val start = LocalDate.of(2026, 10, 1)
        val base = (0 until 20).map { i -> day(start.plusDays(i.toLong()), if (i % 2 == 0) 55.0 else 65.0, if (i % 2 == 0) 55 else 57) }
        val good = base + day(start.plusDays(20), 66.0, 50, 480)
        val bad = base + day(start.plusDays(20), 35.0, 66, 300)
        val g = Analysis.recovery(good, 20, 95)!!
        val b = Analysis.recovery(bad, 20, 60)!!
        assertEquals(Analysis.Zone.GREEN, g.zone)
        assertEquals(Analysis.Zone.RED, b.zone)
        assertNull(Analysis.recovery(base.take(3), 2, 90))     // no baseline yet
        val s = Analysis.sleep(bad, 20, 16.0, zone)!!
        assertTrue("need ${s.needMin}", s.needMin > 420)
        assertTrue(s.performance < 70)
    }

    /** HRV nearly 3x normal with resting HR up is NOT a great morning (Fitbit said 15 that day). */
    @Test fun hrvSpikeIsAWarningNotAGreenLight() {
        val start = LocalDate.of(2026, 10, 1)
        val base = (0 until 25).map { i -> day(start.plusDays(i.toLong()), 38.0 + (i % 5) * 2, 49 + (i % 3)) }
        val spike = base + day(start.plusDays(25), 116.0, 54, 470)
        val r = Analysis.recovery(spike, 25, 96)!!
        assertEquals(r.score.toString(), Analysis.Zone.RED, r.zone)
        assertEquals(false, r.contributors.first { it.name == "HRV" }.good)
        // a normal morning sits around 60
        val normal = base + day(start.plusDays(25), 42.0, 50, 450)
        assertTrue(Analysis.recovery(normal, 25, 88)!!.score.toString(), Analysis.recovery(normal, 25, 88)!!.score in 50..72)
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

    @Test fun weighedFoodsFollowGrams() {
        val base = com.silverajat.lifttracker.meals.Foods.base("shake")!!
        val old = com.silverajat.lifttracker.meals.Foods.withAmounts(base, mapOf("whey" to 29.0, "creatine" to 3.0, "hl" to 100.0, "soy" to 100.0))
        assertEquals(205.0, old.macros.kcal, 3.0)
        val usual = com.silverajat.lifttracker.meals.Foods.withAmounts(base, null)
        assertTrue(usual.name, usual.name.startsWith("Whey 32 g + creatine 3 g"))
        assertTrue(usual.macros.kcal > old.macros.kcal)
        val honey = com.silverajat.lifttracker.meals.Foods.withAmounts(com.silverajat.lifttracker.meals.Foods.base("honey20")!!, mapOf("g" to 30.0))
        assertEquals("Honey, 30 g", honey.name)
        assertEquals(99.0, honey.macros.kcal, 0.5)   // 33 kcal per 10 g, from your label
        // usual amounts are saved and come back
        val dir = java.nio.file.Files.createTempDirectory("meals").toFile()
        val store = com.silverajat.lifttracker.meals.MealStore(dir)
        store.setUsual("shake", mapOf("whey" to 35.0, "creatine" to 3.0, "hl" to 100.0, "soy" to 100.0))
        com.silverajat.lifttracker.meals.MealStore(dir)
        assertEquals(35.0, com.silverajat.lifttracker.meals.Foods.get("shake").amounts["whey"]!!, 0.01)
        com.silverajat.lifttracker.meals.Foods.usual = emptyMap()
    }

    @Test fun muscularLoadHeavierScoresMore() {
        val t0 = 1_760_000_000_000L
        fun set(w: Double, reps: Int, drop: Int?, i: Int) = com.silverajat.lifttracker.data.SetLog(1, i, w, 0, reps, false, t0 + i * 60_000L, t0 + i * 60_000L + 40_000, null, null, speedDrop = drop)
        val past = com.silverajat.lifttracker.data.Session(1, com.silverajat.lifttracker.data.Day.UPPER, t0 - 7 * 86_400_000L, t0 - 7 * 86_400_000L + 3_600_000,
            listOf(set(60.0, 8, null, 0)))
        val heavy = com.silverajat.lifttracker.data.Session(2, com.silverajat.lifttracker.data.Day.UPPER, t0, t0 + 3_600_000, (1..3).map { set(60.0, 8, 45, it) })
        val light = com.silverajat.lifttracker.data.Session(3, com.silverajat.lifttracker.data.Day.UPPER, t0, t0 + 3_600_000, (1..3).map { set(30.0, 8, 5, it) })
        val h = com.silverajat.lifttracker.health.MuscleLoad.score(heavy, listOf(past, heavy))
        val l = com.silverajat.lifttracker.health.MuscleLoad.score(light, listOf(past, light))
        assertTrue("heavy ${h.strain} > light ${l.strain}", h.strain > l.strain * 2)
        assertTrue(h.strain in 0.0..21.0)
        // a gym day with no heart-rate load still gets a strain
        val zone = ZoneId.of("Asia/Singapore")
        val d = java.time.Instant.ofEpochMilli(t0).atZone(zone).toLocalDate()
        val r = Analysis.run(listOf(HealthDay(d, null, null, null, null, null, null, null, null, emptyList(), null)), listOf(past, heavy), emptyMap(), emptyList(), d, zone)
        assertNotNull(r.today?.strain)
        assertTrue((r.today?.strain?.liftStrain ?: 0.0) > 0)
    }

    @Test fun healthCacheRoundTrip() {
        val dir = java.nio.file.Files.createTempDirectory("hc").toFile()
        val c = com.silverajat.lifttracker.health.HealthCache(dir)
        val d0 = day(LocalDate.now().minusDays(1), 61.5, 54).copy(
            workouts = listOf(com.silverajat.lifttracker.health.Workout(java.time.Instant.ofEpochSecond(1000), java.time.Instant.ofEpochSecond(4000), "Run", null, 150, 180, 90, 10.1)),
            load = com.silverajat.lifttracker.health.Load(120, 30, 12, 900, listOf(10, 20, 15, 8, 2)))
        c.save(listOf(d0), full = true)
        val back = c.load()!!
        assertEquals(listOf(d0), back.days)
        val merged = c.merge(back.days, listOf(day(LocalDate.now(), 70.0, 52)))
        assertEquals(2, merged.size)
    }
}
