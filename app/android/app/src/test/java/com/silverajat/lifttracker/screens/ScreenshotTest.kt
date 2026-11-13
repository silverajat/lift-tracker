package com.silverajat.lifttracker.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.silverajat.lifttracker.HealthState
import com.silverajat.lifttracker.chat.Coach
import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.Load
import com.silverajat.lifttracker.health.SleepSummary
import com.silverajat.lifttracker.insights.Insights
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealPlan
import com.silverajat.lifttracker.meals.WeightEntry
import com.silverajat.lifttracker.ui.C
import com.silverajat.lifttracker.ui.InsightCard
import com.silverajat.lifttracker.ui.LiftTheme
import com.silverajat.lifttracker.ui.TodayContent
import com.silverajat.lifttracker.ui.TodayUi
import com.silverajat.lifttracker.ui.netItems
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

/** Renders the main screens with three weeks of made-up data, for design review (build/outputs/roborazzi). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h2900dp-xxhdpi")
class ScreenshotTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()
    private val rnd = Random(7)
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, 0).plusMinutes(m.toLong()).atZone(zone).toInstant().toEpochMilli()

    private val days: List<HealthDay> = (0 until 30).map { i ->
        val d = today.minusDays((29 - i).toLong())
        val asleep = 400 + rnd.nextInt(90)
        HealthDay(
            date = d, steps = 7000L + rnd.nextInt(7000), kcalTotal = 2900.0 + rnd.nextInt(300), kcalActive = 700.0,
            restingHr = 54 + rnd.nextInt(5), hrvMs = 58.0 + rnd.nextGaussian() * 8 + (if (i == 29) 10 else 0), spo2 = 97.0, respRate = 15.0 + rnd.nextGaussian() * 0.4,
            sleep = SleepSummary(d.minusDays(1).atTime(23, 40).atZone(zone).toInstant(),
                d.minusDays(1).atTime(23, 40).plusMinutes(asleep + 25L).atZone(zone).toInstant(), asleep, 75, 95, asleep - 190, 25),
            workouts = emptyList(),
            load = Load(60 + rnd.nextInt(120), 20 + rnd.nextInt(30), 5 + rnd.nextInt(20), 1400),
        )
    }

    private val sessions: List<Session> = (0 until 8).map { k ->
        val d = today.minusDays((k * 3 + 1).toLong())
        val day = if (k % 2 == 0) Day.UPPER else Day.LOWER
        val sets = Routines.forDay(day).take(5).flatMapIndexed { j, ex ->
            (1..3).map { n ->
                val w = 40.0 + j * 10 + (8 - k) * 1.25
                SetLog(ex.id, n, w, 0, 8 + (n % 2), false, ms(d, 7, 20 + j * 10 + n * 3), ms(d, 7, 21 + j * 10 + n * 3), 8, null,
                    quality = 70 + rnd.nextInt(25), romPct = listOf(95, 97, 92))
            }
        }
        Session(ms(d, 7) / 1000, day, ms(d, 7, 15), ms(d, 8, 35), sets)
    }

    private val meals: Map<LocalDate, List<MealEntry>> = (0 until 21).associate { i ->
        val d = today.minusDays(i.toLong())
        d to MealPlan.day(d).filter { !it.optional }.mapIndexed { k, p ->
            MealEntry("$i-$k", d, p.slot, p.food.id, p.food.name, p.food.macros, ms(d, 7 + k))
        }.let { if (d == today) it.take(7) else it }
    }

    private val weights = (0 until 21).map { i -> WeightEntry(today.minusDays(i.toLong()), 71.2 - i * 0.03 + rnd.nextGaussian() * 0.3, 0) }

    private val analysis = Analysis.run(days, sessions, meals, weights, today, zone, 0.25)
    /** Same, with today's Fitbit burn as it would be at 2:30 PM. */
    private val analysisPartial = Analysis.run(days.dropLast(1) + days.last().copy(kcalTotal = 1750.0), sessions, meals, weights, today, zone, 0.25)

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            LiftTheme { Box(Modifier.fillMaxSize().background(C.Bg)) { content() } }
        }

    @Test fun today() = shoot("today") {
        TodayContent(TodayUi(
            health = HealthState.OK, analysis = analysisPartial, todayHealth = days.last().copy(kcalTotal = 1750.0), syncedAt = System.currentTimeMillis(),
            podReady = false, podSub = "for the gym", food = meals[today].orEmpty(), goalKg = 0.25,
            take = Coach.Take(today, "- Recovery is up to 74% after your best sleep in a week: HRV is back above normal.\n" +
                "- Net calories averaged +190 against a +275 goal, and the scale agrees: you're gaining slowly.\n" +
                "- Incline bench has gone up three sessions running.\nToday:\n- Lower day: push the top sets, you're recovered.\n" +
                "- Add a Meiji tonight to close the calorie gap.", System.currentTimeMillis()),
            taking = false, takeError = null, hasKey = true, date = today, now = LocalTime.of(14, 30),
        ))
    }

    @Test fun todayEmpty() = shoot("today_new_user") {
        TodayContent(TodayUi(HealthState.NEEDS_PERMISSION, null, null, null, false, "for the gym", emptyList(), 0.25, null, false, null, false))
    }

    @Test fun net() = shoot("net") {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            netItems(analysisPartial, meals[today].orEmpty(), 0.25) {}
        }
    }

    @Test fun insights() = shoot("insights") {
        val list = listOfNotNull(
            Insights.night(analysis, days.last(), sessions, meals[today].orEmpty(), today, zone, LocalTime.of(21, 45), System.currentTimeMillis()),
            Insights.workout(sessions.first(), sessions, zone),
            Insights.morning(analysis, null, today, System.currentTimeMillis()),
        )
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.size) { InsightCard(list[it], today) }
        }
    }

    /** Screens that need the real view models: best effort, never fails the build. */
    private fun tryShoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        runCatching { shoot(name, content) }.onFailure { System.err.println("screenshot $name skipped: $it") }
    }

    @Test fun gymHome() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = com.silverajat.lifttracker.data.SessionStore(app)
        sessions.forEach { store.save(it) }
        tryShoot("gym_home") {
            val vm = androidx.lifecycle.viewmodel.compose.viewModel<com.silverajat.lifttracker.AppViewModel>()
            com.silverajat.lifttracker.ui.HomeScreen(vm, onConnect = {}, onExport = {})
        }
        tryShoot("workout") {
            val vm = androidx.lifecycle.viewmodel.compose.viewModel<com.silverajat.lifttracker.AppViewModel>()
            com.silverajat.lifttracker.ui.WorkoutScreen(vm, Day.UPPER, onShare = {})
        }
    }

    @Test fun foodLog() {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val store = com.silverajat.lifttracker.meals.MealStore(app.filesDir)
        meals[today].orEmpty().forEach { store.add(it) }
        tryShoot("food_log") {
            val hub = androidx.lifecycle.viewmodel.compose.viewModel<com.silverajat.lifttracker.HubViewModel>()
            com.silverajat.lifttracker.ui.MealsScreen(hub, startOnLog = true)
        }
    }

    @Test fun pastWorkout() = shoot("past_workout") {
        val s0 = sessions.first()
        val s = s0.copy(sets = s0.sets.mapIndexed { i, l -> l.copy(repScores = List(l.reps) { r -> (95 - r * (3 + i % 4)).coerceAtLeast(40) },
            speedDrop = if (i % 4 == 3) 24 else 8, liveReps = if (i == 2) l.reps - 1 else l.reps) })
        com.silverajat.lifttracker.ui.SessionContent(s, sessions)
    }

    @Suppress("unused") private val keepFoods = Foods.all.size
}
