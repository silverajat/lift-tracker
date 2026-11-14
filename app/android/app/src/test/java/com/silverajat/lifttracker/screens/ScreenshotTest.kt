package com.silverajat.lifttracker.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
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
            sleep = stagedNight(d, asleep, i),
            workouts = if (i % 7 == 2) listOf(com.silverajat.lifttracker.health.Workout(ms(d, 18).let { java.time.Instant.ofEpochMilli(it) },
                java.time.Instant.ofEpochMilli(ms(d, 18, 50)), "Walk", null, 104, 128, 22, 4.1)) else emptyList(),
            load = Load(60 + rnd.nextInt(120), 20 + rnd.nextInt(30), 5 + rnd.nextInt(20), 1400, listOf(40 + rnd.nextInt(30), 25 + rnd.nextInt(20), 12 + rnd.nextInt(12), 6 + rnd.nextInt(8), rnd.nextInt(5))),
        )
    }

    /** A night of 90-minute cycles: deep early, REM later, a couple of short wake-ups. */
    private fun stagedNight(d: LocalDate, asleep: Int, seed: Int): SleepSummary {
        val start = d.minusDays(1).atTime(23, 30 + seed % 25).atZone(zone).toInstant()
        val stages = ArrayList<com.silverajat.lifttracker.health.SleepStage>()
        var t = start
        fun add(type: Int, min: Int) { val e = t.plusSeconds(min * 60L); stages += com.silverajat.lifttracker.health.SleepStage(t, e, type); t = e }
        add(0, 8)
        for (c in 0 until 5) {
            add(1, 20 + c * 4); add(2, (40 - c * 9).coerceAtLeast(5)); add(1, 12); add(3, 10 + c * 7)
            if (c == 2 || c == 4) add(0, 4)
        }
        val mins = { ty: Int -> stages.filter { it.type == ty }.sumOf { java.time.Duration.between(it.start, it.end).toMinutes().toInt() } }
        val total = mins(1) + mins(2) + mins(3)
        return SleepSummary(start, t, total, mins(2), mins(3), mins(1), mins(0), stages)
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

    private val analysis = Analysis.run(days, sessions, meals, weights, today, zone, 0.0)
    /** Same, with today's Fitbit burn as it would be at 2:30 PM. */
    private val analysisPartial = Analysis.run(days.dropLast(1) + days.last().copy(kcalTotal = 1750.0), sessions, meals, weights, today, zone, 0.0)

    private fun shoot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) =
        captureRoboImage("build/outputs/roborazzi/$name.png") {
            LiftTheme { Box(Modifier.fillMaxSize().background(C.Bg)) { content() } }
        }

    @Test fun today() = shoot("today") {
      androidx.compose.runtime.CompositionLocalProvider(com.silverajat.lifttracker.ui.LocalTopActions provides com.silverajat.lifttracker.ui.TopActions(2, null) {}) {
        TodayContent(TodayUi(
            health = HealthState.OK, analysis = analysisPartial, todayHealth = days.last().copy(kcalTotal = 1750.0), syncedAt = System.currentTimeMillis(),
            food = meals[today].orEmpty(), goalKg = 0.0,
            take = Coach.Take(today, "- Recovery is up to 74% after your best sleep in a week: HRV is back above normal.\n" +
                "- Net calories averaged +190 against a +275 goal, and the scale agrees: you're gaining slowly.\n" +
                "- Incline bench has gone up three sessions running.\nToday:\n- Lower day: push the top sets, you're recovered.\n" +
                "- Add a Meiji tonight to close the calorie gap.", System.currentTimeMillis()),
            taking = false, takeError = null, hasKey = true, date = today, now = LocalTime.of(14, 30),
            podReady = true, podSub = "37%",
        ))
      }
    }

    @Test fun todayEmpty() = shoot("today_new_user") {
        TodayContent(TodayUi(HealthState.NEEDS_PERMISSION, null, null, null, false, "for the gym", emptyList(), 0.0, null, false, null, false))
    }

    @Test fun net() = shoot("net") {
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            netItems(analysisPartial, meals[today].orEmpty(), 0.0) {}
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

    @Test fun gymPlan() = shoot("gym_plan") {
        val s0 = sessions.first()
        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.padding(20.dp)) {
            com.silverajat.lifttracker.ui.PlanCard(com.silverajat.lifttracker.data.GymPlan.DEFAULT, java.time.LocalDate.of(2026, 11, 13),
                com.silverajat.lifttracker.health.MuscleLoad.score(s0, sessions), onChange = {})
        }
    }

    @Test fun pastWorkout() = shoot("past_workout") {
        val s0 = sessions.first()
        val s = s0.copy(sets = s0.sets.mapIndexed { i, l -> l.copy(repScores = List(l.reps) { r -> (95 - r * (3 + i % 4)).coerceAtLeast(40) },
            speedDrop = if (i % 4 == 3) 24 else 8, liveReps = if (i == 2) l.reps - 1 else l.reps) })
        com.silverajat.lifttracker.ui.SessionContent(s, sessions)
    }

    private val route: List<com.silverajat.lifttracker.activity.RoutePoint> = run {
        // a 10 km loop-ish route around Kent Ridge, one point every 25 m
        val pts = ArrayList<com.silverajat.lifttracker.activity.RoutePoint>()
        val t0 = ms(today.minusDays(5), 7)
        for (i in 0..400) {
            val a = i / 400.0 * 2 * Math.PI
            pts += com.silverajat.lifttracker.activity.RoutePoint(1.2966 + 0.012 * Math.sin(a) + 0.003 * Math.sin(3 * a), 103.7764 + 0.018 * Math.cos(a), t0 + i * 8_400L, 20 + 15 * Math.sin(2 * a))
        }
        pts
    }
    private val acts = listOf(
        com.silverajat.lifttracker.activity.Activity("r1", com.silverajat.lifttracker.activity.Sport.RUN, ms(today.minusDays(5), 7), 3360, 10.02, "", route,
            listOf(338, 334, 331, 336, 340, 333, 329, 335, 332, 322), 3400),
        com.silverajat.lifttracker.activity.Activity("r0", com.silverajat.lifttracker.activity.Sport.RUN, ms(today.minusDays(12), 7), 3450, 10.0),
        com.silverajat.lifttracker.activity.Activity("f1", com.silverajat.lifttracker.activity.Sport.FOOTBALL, ms(today, 17), 75 * 60, null, "5-a-side"),
        com.silverajat.lifttracker.activity.Activity("b1", com.silverajat.lifttracker.activity.Sport.BADMINTON, ms(today.minusDays(2), 19), 90 * 60),
    )
    private val hrFor = mapOf(
        "r1" to com.silverajat.lifttracker.health.Workout(java.time.Instant.EPOCH, java.time.Instant.EPOCH, "", null, 162, 184, 210),
        "f1" to com.silverajat.lifttracker.health.Workout(java.time.Instant.EPOCH, java.time.Instant.EPOCH, "", null, 148, 181, 160),
        "b1" to com.silverajat.lifttracker.health.Workout(java.time.Instant.EPOCH, java.time.Instant.EPOCH, "", null, 139, 172, 120),
    )
    private val items = com.silverajat.lifttracker.activity.ActivityLog.merge(days, acts, hrFor, sessions)

    @Test fun recoveryDetail() = shoot("detail_recovery") { com.silverajat.lifttracker.ui.RecoveryDetail(analysis, days) {} }
    @Test fun strainDetail() = shoot("detail_strain") { com.silverajat.lifttracker.ui.StrainDetail(analysis, days, items, 0.0, {}, {}, {}) }
    @Test fun sleepDetail() = shoot("detail_sleep") { com.silverajat.lifttracker.ui.SleepDetail(analysis, days) {} }
    @Test fun runDetail() = shoot("run_detail") { com.silverajat.lifttracker.ui.RunDetail(acts[0], items.first { it.manualId == "r1" }, {}, {}) }
    private fun journal(): com.silverajat.lifttracker.ui.DayUi {
        val J = com.silverajat.lifttracker.journal.Journal
        val today = java.time.LocalDate.now()
        var p = com.silverajat.lifttracker.journal.DayPage(today)
        listOf("Interview prep", "Pick up 3D print", "Study 2 chapters").forEach { p = J.add(p, it, true) }
        listOf("Do claims on UREX", "Buy yogurt", "Amitaansh bday msg").forEach { p = J.add(p, it, false) }
        p = J.toggle(p, p.mains[1].id); p = J.toggle(p, p.sides[0].id); p = J.toggle(p, p.sides[2].id)
        p = p.copy(mood = 4, reflection = "Prep went well, 3D print looks clean. Didn't get to chapter 2.")
        var y = com.silverajat.lifttracker.journal.DayPage(today.minusDays(1))
        listOf("Order PCBs", "Pay for 3D print", "Study 2 chapters").forEach { y = J.add(y, it, true) }
        y = J.toggle(J.toggle(y, y.mains[0].id), y.mains[1].id).copy(mood = 5, reflection = "Finally ordered everything for the pod.")
        var tm = com.silverajat.lifttracker.journal.DayPage(today.plusDays(1))
        tm = J.add(tm, "Talk to papa about the crypto project", true)
        val now = System.currentTimeMillis()
        val notes = listOf(
            com.silverajat.lifttracker.journal.Note(text = "Talk to papa on Friday about the IC design chip and the crypto one. Also how the interview went.", created = now - 3_600_000, pinned = true),
            com.silverajat.lifttracker.journal.Note(text = "LED sync and wearable before the holidays, crypto after those 2 are done.", created = now - 86_400_000),
        )
        return com.silverajat.lifttracker.ui.DayUi(today, mapOf(today to p, y.date to y, tm.date to tm), notes, emptyList(), java.time.LocalTime.of(22, 10),
            listOf("Greek yogurt", "Eggs x30", "Isopropyl alcohol 99%", "Bananas").mapIndexed { i, t -> com.silverajat.lifttracker.journal.ShopItem(text = t, bought = i == 0) })
    }
    @Test fun dayPlan() = shoot("day_plan") { com.silverajat.lifttracker.ui.DayContent(journal()) }
    @Test fun dayNotes() = shoot("day_notes") { com.silverajat.lifttracker.ui.DayContent(journal(), startTab = 2) }
    @Test fun dayPast() = shoot("day_past") { com.silverajat.lifttracker.ui.DayContent(journal(), startTab = 3) }
    private fun gardenUi(active: Boolean): com.silverajat.lifttracker.ui.GardenUi {
        val now = System.currentTimeMillis()
        val focus = (1..20).map { i ->
            com.silverajat.lifttracker.garden.FocusSession(area = if (i % 3 == 0) com.silverajat.lifttracker.garden.Area.WORK else com.silverajat.lifttracker.garden.Area.STUDY, label = if (i % 3 == 0) "Lift tracker app" else "EE2023",
                startMs = now - i * 86_400_000L / 2, endMs = now - i * 86_400_000L / 2 + (40 + i * 7) * 60_000L)
        }
        val j = journal()
        val plants = com.silverajat.lifttracker.garden.Garden.plants(com.silverajat.lifttracker.garden.Garden.Inputs(focus, sessions, items, emptyList(), j.pages,
            if (active) com.silverajat.lifttracker.garden.ActiveFocus(com.silverajat.lifttracker.garden.Area.STUDY, "EE2023 tutorial 6", now - 34 * 60_000L - 12_000) else null),
            java.time.LocalDate.now(), java.time.ZoneId.systemDefault(), now)
            .map { p -> if (p.area == com.silverajat.lifttracker.garden.Area.SLEEP) com.silverajat.lifttracker.garden.Garden.Plant(p.area, 2650, com.silverajat.lifttracker.garden.Garden.level(2650), com.silverajat.lifttracker.garden.Garden.progress(2650), "5/7 good nights", java.time.LocalDate.now(), 0) else p }
        return com.silverajat.lifttracker.ui.GardenUi(plants,
            if (active) com.silverajat.lifttracker.garden.ActiveFocus(com.silverajat.lifttracker.garden.Area.STUDY, "EE2023 tutorial 6", now - 34 * 60_000L - 12_000, awayS = 180) else null, focus, now)
    }
    @Test fun garden() = shoot("day_garden") { com.silverajat.lifttracker.ui.DayContent(journal(), startTab = 1, garden = gardenUi(false)) }
    @Test fun gardenFocus() = shoot("day_garden_focus") { com.silverajat.lifttracker.ui.DayContent(journal(), startTab = 1, garden = gardenUi(true)) }
    @Test fun badmintonDetail() = shoot("activity_badminton") {
        val item = items.first { it.manualId == "b1" }
        val samples = (0..(93 * 12)).map { i ->
            val sec = i * 5L
            val bpm = when {
                sec > 90 * 60 -> (158 - (sec - 90 * 60) / 3).toInt()
                sec % 270 in 0..45 -> 168 + rnd.nextInt(14)
                else -> 128 + rnd.nextInt(18) + (if (sec > 50 * 60) -6 else 0)
            }
            com.silverajat.lifttracker.health.HrSample(item.start.plusSeconds(sec), bpm)
        }
        val a = com.silverajat.lifttracker.activity.SportAnalysis.analyze(item.sport, item.start, item.end, samples, 56, 196, 6120, 812.0, item.strain,
            listOf(item.copy(key = "x1", start = item.start.minusSeconds(4 * 86400), strain = 9.1), item.copy(key = "x2", start = item.start.minusSeconds(9 * 86400), strain = 8.7)))
        com.silverajat.lifttracker.ui.ActivityDetail(item, a, false, {})
    }
    @Test fun liveRun() = shoot("run_live") {
        com.silverajat.lifttracker.ui.LiveSession(com.silverajat.lifttracker.activity.RunTracker.State(
            active = true, startMs = System.currentTimeMillis() - 1_520_000, distanceM = 4630.0, points = route.take(185),
            splitsS = listOf(331, 327, 334, 329), paceSPerKm = 322, gpsOk = true, accuracyM = 6f)) {}
    }

    @Test fun weightDetail() = shoot("detail_weight") {
        val ws = (0 until 70).filter { it % 9 != 4 }.map { i ->
            val d = today.minusDays(i.toLong())
            WeightEntry(d, 70.2 + (69 - i) * 0.022 + rnd.nextGaussian() * 0.35 + (if (d.dayOfWeek == java.time.DayOfWeek.MONDAY) 0.4 else 0.0), 0)
        }.sortedBy { it.date }
        com.silverajat.lifttracker.ui.WeightDetail(ws, 0.0, {}, {})
    }

    @Suppress("unused") private val keepFoods = Foods.all.size
}
