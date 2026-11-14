package com.silverajat.lifttracker.insights

import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.data.Stats
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Energy
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.Workout
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.Targets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kinds of notification (each is its own Android channel, so you can mute one). */
enum class Kind(val title: String, val about: String) {
    WORKOUT("Workout summaries", "After you finish a gym session"),
    MORNING("Morning summary", "Recovery, sleep and today's weather"),
    ACTIVITY("Activity summaries", "Walks and workouts your Fitbit recorded"),
    FOOD("Net check", "Evening: where today's net calories are heading"),
    NIGHT("Day wrap-up", "9:45 PM: your whole day, and when to sleep"),
    WEATHER("Weather", "Rain coming up"),
    WEEKLY("Weekly review", "Monday: last week in numbers"),
    PLAN("Plan and reflect", "Morning: set today's 3. 9:30 PM: tick off and reflect"),
}

data class Insight(val key: String, val kind: Kind, val title: String, val body: String, val timeMs: Long)

/** Heart rate, steps and calories during one Fitbit workout (from Health Connect). */
data class ActivityStats(val steps: Long?, val avgHr: Int?, val maxHr: Int?, val kcal: Double?)

/** Today's forecast (Open-Meteo), hourly from midnight local time. */
data class Weather(
    val maxC: Double?, val minC: Double?, val rainMaxPct: Int?, val uvMax: Double?,
    val hourlyRainPct: List<Int>, val hourlyTempC: List<Double>, val date: LocalDate,
)

/**
 * The words in the notifications: worked out from your own numbers, with a
 * bit of personality (the same day always gets the same wording).
 */
object Insights {
    private fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    private fun signed(n: Int) = (if (n > 0) "+" else if (n < 0) "−" else "") + "%,d".format(abs(n))
    private fun <T> pick(seed: String, vararg options: T): T = options[abs(seed.hashCode()) % options.size]
    private fun clock(h: Int) = when {
        h == 0 || h == 24 -> "12 AM"
        h == 12 -> "12 PM"
        h < 12 -> "$h AM"
        else -> "${h - 12} PM"
    }
    private fun clock(t: LocalTime) = "${if (t.hour % 12 == 0) 12 else t.hour % 12}:${"%02d".format(t.minute)} ${if (t.hour < 12) "AM" else "PM"}"

    /** "about 5 Toyota Corollas" for a weight in kg. */
    fun heavyThing(kg: Double): String? {
        val things = listOf("SBS double-decker buses" to 12_000.0, "Asian elephants" to 4_000.0,
            "Toyota Corollas" to 1_300.0, "grand pianos" to 400.0, "fridges" to 80.0)
        val (name, w) = things.firstOrNull { kg / it.second >= 1.5 } ?: return null
        val n = kg / w
        return "about ${if (n < 10) "%.1f".format(n).removeSuffix(".0") else n.roundToInt().toString()} $name"
    }

    /** Fun distance for a step count (0.75 m stride). */
    fun stepsThing(steps: Long): String {
        val km = steps * 0.00075
        val laps = (km / 0.4).roundToInt()
        return if (km >= 9) "%.1f km: that's NUS to Marina Bay on foot".format(km) else "%.1f km, or $laps laps of a running track".format(km)
    }

    // ---- after a gym session ---------------------------------------------------------------

    fun workout(s: Session, history: List<Session>, zone: ZoneId): Insight {
        val end = s.endMs ?: System.currentTimeMillis()
        val mins = ((end - s.startMs) / 60_000).toInt()
        val work = s.sets.filter { !it.warmup }
        fun vol(list: List<SetLog>) = list.sumOf { it.weight * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }
        val volume = vol(work)
        val past = history.filter { it.id != s.id && it.day == s.day && it.startMs < s.startMs && it.sets.isNotEmpty() }
            .sortedByDescending { it.startMs }.take(4)
        val usual = past.map { vol(it.sets.filter { l -> !l.warmup }) }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

        val today = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val trends = Analysis.exerciseTrends(history.filter { it.id != s.id } + s, today, zone)
            .filter { t -> work.any { it.exerciseId == t.exerciseId } }
        val bestBefore = { exId: Int ->
            history.filter { it.id != s.id && it.startMs < s.startMs }.flatMap { it.sets }
                .filter { it.exerciseId == exId && !it.warmup }.maxOfOrNull { Stats.oneRm(it.weight, it.reps) }
        }
        val prs = trends.filter { t -> bestBefore(t.exerciseId)?.let { t.e1rmNow > it + 0.01 } == true }
        val pct = usual?.let { (volume / it * 100).roundToInt() }

        val title = when {
            prs.isNotEmpty() -> pick(s.id.toString(), "🏆 New PR on ${prs[0].name}!", "🏆 ${s.day.title} day: PR unlocked", "🔥 ${prs[0].name}: personal best")
            pct != null && pct >= 105 -> pick(s.id.toString(), "💪 Big ${s.day.title.lowercase()} day", "💪 ${s.day.title} day: more than usual", "🔥 ${s.day.title} day crushed")
            else -> pick(s.id.toString(), "💪 ${s.day.title} day done", "🏋️ ${s.day.title} day in the books", "✅ ${s.day.title} day: done and dusted")
        }
        val lines = ArrayList<String>()
        lines += "$mins min, ${work.size} working sets, ${"%,d".format(volume.roundToInt())} kg moved" +
            (heavyThing(volume)?.let { " ($it)" } ?: "") + "."
        if (pct != null) lines += when {
            pct >= 105 -> "That's $pct% of your usual: more work than the last ${past.size}."
            pct <= 85 -> "$pct% of your usual: a lighter one."
            else -> "Right on your usual volume."
        }
        val ms = com.silverajat.lifttracker.health.MuscleLoad.score(s, history).strain
        lines += "Muscular strain ${"%.1f".format(ms)}" + when {
            ms >= 16 -> pick(s.id.toString() + "m", ": absolute beast mode.", ": your muscles felt that one.", ": big one, sleep well.")
            ms >= 12 -> pick(s.id.toString() + "m", ": proper hard session.", ": solid work.", ": right on target.")
            else -> pick(s.id.toString() + "m", ": on the lighter side.", ": an easy one.", ".")
        }
        if (prs.isNotEmpty()) lines += "PR: " + prs.take(3).joinToString(", ") { "${it.name} ${it.e1rmNow.roundToInt()} kg (est. 1RM)" } + "."
        val q = work.filter { it.quality != null }
        if (q.size >= 3) {
            val avg = q.map { it.quality!! }.average().roundToInt()
            lines += "Rep quality $avg/100" + pick(s.id.toString() + "q", ": clean reps.", ": solid form.", ".")
                .let { if (avg < 70) ": form slipped a bit." else it }
            val worst = q.minBy { it.quality!! }
            if (worst.quality!! < 70) {
                val name = runCatching { Routines.byId(worst.exerciseId).name }.getOrDefault("?")
                lines += "Toughest: $name set ${worst.setNo}" + (worst.speedDrop?.takeIf { it >= 20 }?.let { ", slowed $it% by the end" } ?: "") + "."
            }
        }
        val up = trends.filter { it.suggestion.startsWith("Hit") }
        if (up.isNotEmpty()) lines += "Next time, go up: " + up.take(3).joinToString("; ") { it.name + " → " + it.suggestion.substringAfter("go up to ") } + "."
        val off = work.count { it.liveReps != null && it.liveReps != it.reps }
        if (off >= 3) lines += "You corrected the pod on $off sets: it has learned from them."
        return Insight("workout-${s.id}", Kind.WORKOUT, title, lines.joinToString(" "), end)
    }

    // ---- morning ----------------------------------------------------------------------------

    fun morning(a: Analysis.Result, weather: Weather?, date: LocalDate, now: Long): Insight? {
        val d = a.today
        val r = d?.recovery
        val s = d?.sleep
        if (r == null && s == null && weather == null) return null
        val seed = date.toString()
        val title = when {
            r != null && r.zone == Analysis.Zone.GREEN -> pick(seed, "🟢 Recovery ${r.score}%: green light", "🟢 ${r.score}% recovered: go get it", "🟢 Fully charged: ${r.score}%")
            r != null && r.zone == Analysis.Zone.YELLOW -> pick(seed, "🟡 Recovery ${r.score}%: steady day", "🟡 ${r.score}% recovered: train smart", "🟡 Recovery ${r.score}%: not bad, not peak")
            r != null -> pick(seed, "🔴 Recovery ${r.score}%: take it easy", "🔴 ${r.score}%: your body wants a chill day", "🔴 Low battery: ${r.score}%")
            s != null -> "😴 You slept ${hm(s.asleepMin)}"
            else -> pick(seed, "☀️ Morning!", "☀️ Rise and lift")
        }
        val lines = ArrayList<String>()
        if (s != null) lines += when {
            s.performance >= 95 -> "Slept ${hm(s.asleepMin)}: all ${hm(s.needMin)} you needed. 👌"
            s.performance >= 80 -> "Slept ${hm(s.asleepMin)} of ${hm(s.needMin)} needed: close enough."
            else -> "Slept ${hm(s.asleepMin)} of ${hm(s.needMin)} needed: ${hm(s.needMin - s.asleepMin)} short."
        } + (if (s.debtMin >= 45) " Carrying ${hm(s.debtMin)} of sleep debt." else "")
        if (r != null) {
            r.contributors.firstOrNull { it.name == "HRV" && it.good != null }?.let {
                lines += if (it.good == true) "HRV ${it.value}, above your ${it.vsNormal.removePrefix("normal ")} normal." else "HRV ${it.value}, under your ${it.vsNormal.removePrefix("normal ")} normal."
            }
            val t = Analysis.strainTarget(r.zone)
            lines += when (r.zone) {
                Analysis.Zone.GREEN -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: push the top sets."
                Analysis.Zone.YELLOW -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: train as planned, no PR hunting."
                Analysis.Zone.RED -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: keep it light, or make it a walking day."
            }
        } else if (a.days.size < 6) lines += "Recovery scores start after 5 nights with the Fitbit."
        weather?.let { lines += weatherLine(it) }
        return Insight("morning-$date", Kind.MORNING, title, lines.joinToString(" "), now)
    }

    fun weatherLine(w: Weather): String {
        val parts = ArrayList<String>()
        if (w.maxC != null) parts += "${w.maxC.roundToInt()}°C"
        val rainHours = w.hourlyRainPct.withIndex().filter { it.index in 7..22 && it.value >= 50 }.map { it.index }
        parts += if (rainHours.isEmpty()) "no rain expected ☀️"
            else "rain likely ${clock(rainHours.first())}" + (if (rainHours.last() > rainHours.first()) "-${clock(rainHours.last() + 1)}" else "") +
                " (${w.hourlyRainPct.slice(rainHours).max()}%) ☔"
        if ((w.uvMax ?: 0.0) >= 8) parts += "UV ${w.uvMax!!.roundToInt()} around midday"
        return "Today: " + parts.joinToString(", ") + "."
    }

    // ---- rain coming up ---------------------------------------------------------------------

    fun rainSoon(w: Weather, now: LocalDateTime): Insight? {
        if (now.toLocalDate() != w.date || now.hour !in 7..21) return null
        val next = (now.hour + 1..now.hour + 2).filter { it < w.hourlyRainPct.size }
        val hit = next.firstOrNull { w.hourlyRainPct[it] >= 70 } ?: return null
        return Insight("rain-${w.date}-$hit", Kind.WEATHER, "☔ Rain around ${clock(hit)}",
            "${w.hourlyRainPct[hit]}% chance from about ${clock(hit)}. " +
                pick("${w.date}$hit", "Umbrella time if you're heading out.", "Walk now or bring an umbrella.", "Good moment to get your walk in before it hits."),
            System.currentTimeMillis())
    }

    // ---- a Fitbit workout just ended -------------------------------------------------------

    fun activity(w: Workout, st: ActivityStats, meals: List<MealEntry>, now: Long): Insight {
        val mins = Duration.between(w.start, w.end).toMinutes().toInt()
        val emoji = when (w.type) { "Walk" -> "🚶"; "Run" -> "🏃"; "Bike" -> "🚴"; "Swim" -> "🏊"; else -> "⚡" }
        val name = (w.title ?: w.type).lowercase()
        val lines = ArrayList<String>()
        st.steps?.takeIf { it > 300 }?.let { lines += "${"%,d".format(it)} steps (${stepsThing(it)})." }
        st.avgHr?.let { lines += "Avg heart rate $it" + (st.maxHr?.let { m -> ", peaking at $m" } ?: "") + "." }
        val mains = setOf("salad_egg", "salad_carrot", "chicken_rice", "mixed_rice")
        val meal = meals.filter { it.foodId in mains || it.slot == Slot.LUNCH || it.slot == Slot.DINNER }
            .lastOrNull { val dt = (w.start.toEpochMilli() - it.timeMs) / 60_000; dt in 0..75 }
        var title = "$emoji $mins-min $name"
        if (meal != null && w.type == "Walk") {
            val m = meal.slot.title.lowercase()
            if (mins >= Targets.walkAfterMealMin) {
                title = pick(w.start.toString(), "$emoji After-$m walk: done ✓", "$emoji $mins-min after-$m walk ✓", "$emoji Post-$m stroll complete")
                lines += "Good for digestion and blood sugar after a big meal."
            } else lines += "After-$m walk: $mins of ${Targets.walkAfterMealMin} min. A few more minutes next time."
        }
        return Insight("act-${w.start.toEpochMilli()}", Kind.ACTIVITY, title, lines.joinToString(" ").ifEmpty { "Recorded by your Fitbit." }, now)
    }

    // ---- evening net check ---------------------------------------------------------------------

    fun evening(e: Energy.Result, entries: List<MealEntry>, goalKg: Double, date: LocalDate, now: LocalTime, nowMs: Long): Insight {
        if (entries.isEmpty()) return Insight("food-$date", Kind.FOOD, "🍽️ Nothing logged today",
            "Tap today's meals into the Food tab (a few seconds each) so your net calories count.", nowMs)
        val t = Energy.today(e, entries, date, now)
        val goal = e.netFor(goalKg)
        val diff = t.endOfDay - goal
        val title = when {
            abs(diff) <= 150 -> pick(date.toString(), "🎯 Net ${signed(t.endOfDay)}: right on target", "🎯 On track: ${signed(t.endOfDay)} today")
            diff < 0 -> "🍽️ Net ${signed(t.endOfDay)}: ${signed(-diff)} under goal"
            else -> "🍽️ Net ${signed(t.endOfDay)}: ${signed(diff)} over goal"
        }
        val lines = ArrayList<String>()
        lines += "Where today is heading" + (if (t.restOfPlan) " with the rest of the plan" else "") + ": ${signed(t.endOfDay)} (goal ${signed(goal)})."
        lines += when {
            abs(diff) <= 150 -> "Nothing to change. Nice."
            diff < -400 -> "Quite a bit under: a Meiji (~170) plus an extra bread + Mayver's (~135) gets you closer."
            diff < 0 -> "Slightly under: an extra slice of bread with honey (~100) or a Meiji (~170) closes it."
            diff > 400 -> "Well over: skip the extra peanut butter and keep the yoghurt bowl plain-ish."
            else -> "A little over: fine if it's a training day."
        }
        return Insight("food-$date", Kind.FOOD, title, lines.joinToString(" "), nowMs)
    }

    // ---- 9:45 PM: the whole day -----------------------------------------------------------------

    fun night(
        a: Analysis.Result, today: HealthDay?, sessions: List<Session>, entries: List<MealEntry>,
        date: LocalDate, zone: ZoneId, now: LocalTime, nowMs: Long,
    ): Insight {
        val seed = "night$date"
        val steps = today?.steps ?: 0L
        val lines = ArrayList<String>()
        if (steps > 0) lines += "${"%,d".format(steps)} steps: ${stepsThing(steps)}" +
            (if (steps >= 10_000) " 🎉" else " (${"%,d".format(10_000 - steps)} short of 10k)") + "."
        today?.load?.let { l ->
            val azm = l.moderateMin + 2 * l.vigorousMin
            if (azm > 0) lines += "$azm active zone minutes, strain ${"%.1f".format(Analysis.strainOf(l.trimp))}."
        }
        val gym = sessions.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == date && it.sets.isNotEmpty() }
        gym.forEach { s ->
            val v = s.sets.filter { !it.warmup }.sumOf { it.weight * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }
            lines += "${s.day.title} day ✓ (${"%,d".format(v.roundToInt())} kg)."
        }
        // net
        val goal = a.energy.netFor(a.goalKgPerWeek)
        if (entries.isNotEmpty()) {
            val t = Energy.today(a.energy, entries, date, now)
            lines += "Net ${signed(t.endOfDay)} vs a ${signed(goal)} goal" + when {
                abs(t.endOfDay - goal) <= 150 -> ": nailed it 🎯."
                t.endOfDay < goal -> ": a bit under."
                else -> ": a bit over."
            }
            val streak = a.energy.days.reversed().takeWhile { it.net != null && abs(it.net - goal) <= 250 }.size +
                (if (abs(t.endOfDay - goal) <= 250) 1 else 0)
            if (streak >= 3) lines += "$streak days on target in a row 🔥"
        } else lines += "No food logged today, so no net calories."
        // when to sleep: tonight's need, against your usual wake-up
        val wakes = a.days.takeLast(7).mapNotNull { it.sleep?.wake?.atZone(zone)?.toLocalTime() }
        val todayStrain = today?.load?.let { Analysis.strainOf(it.trimp) } ?: 0.0
        val lastNights = a.days.takeLast(3).mapNotNull { it.sleep?.asleepMin }
        val need = Analysis.SLEEP_BASE_MIN + ((todayStrain - 10) * 4).coerceIn(0.0, 45.0).roundToInt() +
            (lastNights.sumOf { (Analysis.SLEEP_BASE_MIN - it).coerceAtLeast(0) } / 2).coerceAtMost(60)
        if (wakes.size >= 3) {
            val wake = wakes.map { it.toSecondOfDay() }.sorted()[wakes.size / 2].let { LocalTime.ofSecondOfDay(it.toLong()) }
            val bed = wake.minusMinutes(need + 15L)
            lines += "Tonight you need ${hm(need)}: lights out by ${clock(bed)} for your usual ${clock(wake)} wake-up."
        } else lines += "Tonight you need about ${hm(need)} of sleep."
        val title = when {
            gym.isNotEmpty() && steps >= 10_000 -> pick(seed, "🌙 Gym + 10k steps. Big day.", "🌙 What a day: lifted and walked")
            gym.isNotEmpty() -> pick(seed, "🌙 Day wrap: ${gym[0].day.title.lowercase()} day done", "🌙 Lifted today. Time to recover.")
            steps >= 12_000 -> pick(seed, "🌙 ${"%,d".format(steps)} steps. Legs: earned.", "🌙 Big walking day")
            steps in 1..5_000 -> pick(seed, "🌙 Chill day wrap-up", "🌙 Rest day vibes")
            else -> pick(seed, "🌙 Your day in a nutshell", "🌙 Day wrap-up", "🌙 That's a wrap on today")
        }
        return Insight("night-$date", Kind.NIGHT, title, lines.joinToString(" "), nowMs)
    }

    // ---- Monday: last week ------------------------------------------------------------------------

    fun weekly(a: Analysis.Result, sessions: List<Session>, today: LocalDate, zone: ZoneId, now: Long): Insight? {
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val from = monday.minusDays(7)
        fun inWeek(d: LocalDate) = d >= from && d < monday
        val gym = sessions.filter { inWeek(Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()) && it.sets.isNotEmpty() }
        val days = a.days.filter { inWeek(it.date) }
        val rec = days.mapNotNull { it.recovery?.score }
        val sleep = days.mapNotNull { it.sleep?.asleepMin }
        val nets = a.energy.days.filter { inWeek(it.date) }.mapNotNull { it.net }
        if (gym.isEmpty() && rec.isEmpty() && nets.isEmpty()) return null
        val lines = ArrayList<String>()
        lines += "🏋️ ${gym.size} gym session${if (gym.size == 1) "" else "s"}" +
            (if (gym.isNotEmpty()) ", ${"%,d".format(gym.sumOf { s -> s.sets.filter { !it.warmup }.sumOf { it.weight * it.reps } }.roundToInt())} kg total" else "") + "."
        if (rec.isNotEmpty()) lines += "💚 Recovery averaged ${rec.average().roundToInt()}%."
        if (sleep.isNotEmpty()) lines += "😴 Sleep averaged ${hm(sleep.average().roundToInt())}."
        if (nets.isNotEmpty()) {
            val goal = a.energy.netFor(a.goalKgPerWeek)
            lines += "🍽️ Net ${signed(nets.average().roundToInt())} a day (goal ${signed(goal)}), ${nets.size}/7 days logged."
        }
        a.energy.trendKgPerWeek?.let { lines += "⚖️ Scale trend ${"%+.2f".format(it)} kg/week" + (a.energy.scaleNet?.let { n -> " (≈ ${signed(n)} a day)" } ?: "") + "." }
        a.exercises.firstOrNull { (it.changePct ?: 0.0) > 0 }?.let { lines += "📈 Biggest gain: ${it.name} +${"%.1f".format(it.changePct)}%." }
        a.effects.firstOrNull { it.clear }?.let { lines += "🔍 Pattern: ${it.habit.lowercase()} → next-day recovery ${if (it.delta >= 0) "+" else ""}${it.delta.roundToInt()}." }
        return Insight("week-$monday", Kind.WEEKLY, pick(monday.toString(), "📊 Your week in numbers", "📊 Last week, wrapped", "📊 Weekly recap"),
            lines.joinToString(" "), now)
    }

    /** True if a Fitbit workout overlaps a pod gym session (that one already has its own summary). */
    fun overlapsGym(w: Workout, sessions: List<Session>): Boolean = sessions.any { s ->
        val e = s.endMs ?: System.currentTimeMillis()
        w.start.toEpochMilli() < e && w.end.toEpochMilli() > s.startMs
    }
}
