package com.silverajat.lifttracker.insights

import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Workout
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealPlan
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.Targets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** Kinds of notification (each is its own Android channel, so you can mute one). */
enum class Kind(val title: String, val about: String) {
    WORKOUT("Workout summaries", "After you finish a gym session"),
    MORNING("Morning summary", "Recovery, sleep and today's weather"),
    ACTIVITY("Activity summaries", "Walks and workouts your Fitbit recorded"),
    FOOD("Food check", "Evening protein and calories check"),
    WEATHER("Weather", "Rain coming up"),
    WEEKLY("Weekly review", "Monday: weight, training and food for last week"),
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
 * The words in the notifications. Everything is worked out from your own
 * numbers (the Analysis engine): no chatbot needed.
 */
object Insights {
    private fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    private fun kg(w: Double) = if (w == Math.floor(w)) "%.0f".format(w) else "%.1f".format(w)
    private fun signed(x: Double, d: Int = 0) = (if (x >= 0) "+" else "") + "%.${d}f".format(x)

    // ---- after a gym session ---------------------------------------------------------------

    fun workout(s: Session, history: List<Session>, zone: ZoneId): Insight {
        val end = s.endMs ?: System.currentTimeMillis()
        val mins = ((end - s.startMs) / 60_000).toInt()
        val work = s.sets.filter { !it.warmup }
        fun vol(list: List<com.silverajat.lifttracker.data.SetLog>) =
            list.sumOf { it.weight * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }
        val volume = vol(work)
        val past = history.filter { it.id != s.id && it.day == s.day && it.startMs < s.startMs && it.sets.isNotEmpty() }
            .sortedByDescending { it.startMs }.take(4)
        val usual = past.map { vol(it.sets.filter { l -> !l.warmup }) }.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

        val lines = ArrayList<String>()
        lines += "${work.size} working sets, ${"%,d".format(volume.roundToInt())} kg moved" +
            (usual?.let { " (${(volume / it * 100).roundToInt()}% of your last ${past.size} ${s.day.title.lowercase()} days)" } ?: "") + "."

        // personal bests and progress, from the analysis engine
        val today = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val all = history.filter { it.id != s.id } + s
        val trends = Analysis.exerciseTrends(all, today, zone).filter { t -> work.any { it.exerciseId == t.exerciseId } }
        val bestBefore = { exId: Int ->
            history.filter { it.id != s.id && it.startMs < s.startMs }.flatMap { it.sets }
                .filter { it.exerciseId == exId && !it.warmup }
                .maxOfOrNull { com.silverajat.lifttracker.data.Stats.oneRm(it.weight, it.reps) }
        }
        val prs = trends.filter { t -> bestBefore(t.exerciseId)?.let { t.e1rmNow > it + 0.01 } == true }
        if (prs.isNotEmpty()) lines += "New best: " + prs.take(3).joinToString(", ") { "${it.name} ${it.e1rmNow.roundToInt()} kg est. 1RM" } + "."

        // quality: the best and the weakest set (pod sets only)
        val q = work.filter { it.quality != null }
        if (q.size >= 3) {
            lines += "Rep quality ${q.map { it.quality!! }.average().roundToInt()}" +
                (q.flatMap { it.romPct }.takeIf { it.isNotEmpty() }?.let { ", ROM ${it.average().roundToInt()}%" } ?: "") + "."
            val worst = q.minBy { it.quality!! }
            if (worst.quality!! < 70) {
                val name = runCatching { Routines.byId(worst.exerciseId).name }.getOrDefault("?")
                lines += "Weakest: $name set ${worst.setNo} (quality ${worst.quality}" +
                    (worst.speedDrop?.takeIf { it >= 20 }?.let { ", slowed $it%" } ?: "") + ")."
            }
        }
        // what to do next time: the exercises where the plan says go up
        val up = trends.filter { it.suggestion.startsWith("Hit") }
        if (up.isNotEmpty()) lines += "Next time go up: " + up.take(3).joinToString("; ") { it.name + ": " + it.suggestion.substringAfter(": ") } + "."
        // sets that got corrected a lot: tell the user the counter is still learning
        val off = work.count { it.liveReps != null && it.liveReps != it.reps }
        if (off >= 3) lines += "You corrected the pod on $off sets: it has learned from them for next time."

        return Insight("workout-${s.id}", Kind.WORKOUT, "${s.day.title} day done · ${mins} min", lines.joinToString(" "), end)
    }

    // ---- morning ----------------------------------------------------------------------------

    fun morning(a: Analysis.Result, weather: Weather?, date: LocalDate, now: Long): Insight? {
        val d = a.today
        val r = d?.recovery
        val s = d?.sleep
        if (r == null && s == null && weather == null) return null
        val title = when {
            r != null -> "Recovery ${r.score}% · ${r.zone.label}"
            s != null -> "Slept ${hm(s.asleepMin)}"
            else -> "Good morning"
        }
        val lines = ArrayList<String>()
        if (s != null) lines += "Slept ${hm(s.asleepMin)} of ${hm(s.needMin)} needed (${s.performance}%)" +
            (if (s.debtMin >= 30) ", ${s.debtMin} min of sleep debt" else "") + "."
        if (r != null) {
            val off = r.contributors.filter { it.good != null }.maxByOrNull { if (it.name == "HRV") 2 else 1 }
            off?.let { lines += "${it.name} ${it.value} (${it.vsNormal})." }
            val t = Analysis.strainTarget(r.zone)
            lines += when (r.zone) {
                Analysis.Zone.GREEN -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: a good day to push the top sets."
                Analysis.Zone.YELLOW -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: train as planned, don't chase PRs."
                Analysis.Zone.RED -> "Strain target ${t.start.toInt()}-${t.endInclusive.toInt()}: keep it light or make it a walking day."
            }
        } else if (a.days.size < 6) lines += "Recovery starts after 5 nights of HRV from the Fitbit."
        weather?.let { lines += weatherLine(it) }
        return Insight("morning-$date", Kind.MORNING, title, lines.joinToString(" "), now)
    }

    fun weatherLine(w: Weather): String {
        val parts = ArrayList<String>()
        if (w.maxC != null) parts += "${w.maxC.roundToInt()}°C"
        val rainHours = w.hourlyRainPct.withIndex().filter { it.index in 7..22 && it.value >= 50 }.map { it.index }
        parts += if (rainHours.isEmpty()) "no rain expected"
            else "rain likely ${clock(rainHours.first())}" + (if (rainHours.last() > rainHours.first()) "-${clock(rainHours.last() + 1)}" else "") +
                " (${w.hourlyRainPct.slice(rainHours).max()}%)"
        if ((w.uvMax ?: 0.0) >= 8) parts += "UV ${w.uvMax!!.roundToInt()}: very high around midday"
        return "Today: " + parts.joinToString(", ") + "."
    }

    private fun clock(h: Int) = when {
        h == 0 || h == 24 -> "12 AM"
        h == 12 -> "12 PM"
        h < 12 -> "$h AM"
        else -> "${h - 12} PM"
    }

    // ---- rain coming up ---------------------------------------------------------------------

    /** Rain 70%+ in the next 2 hours (daytime only), or null. */
    fun rainSoon(w: Weather, now: LocalDateTime): Insight? {
        if (now.toLocalDate() != w.date || now.hour !in 7..21) return null
        val next = (now.hour + 1..now.hour + 2).filter { it < w.hourlyRainPct.size }
        val hit = next.firstOrNull { w.hourlyRainPct[it] >= 70 } ?: return null
        return Insight("rain-${w.date}-$hit", Kind.WEATHER, "Rain around ${clock(hit)}",
            "${w.hourlyRainPct[hit]}% chance of rain from about ${clock(hit)}. Bring an umbrella if you're heading out" +
                " (or walk before then).", System.currentTimeMillis())
    }

    // ---- a Fitbit workout just ended -------------------------------------------------------

    fun activity(w: Workout, st: ActivityStats, meals: List<MealEntry>, now: Long): Insight {
        val mins = Duration.between(w.start, w.end).toMinutes().toInt()
        val name = w.title ?: w.type
        val parts = listOfNotNull(
            st.steps?.takeIf { it > 0 }?.let { "${"%,d".format(it)} steps" },
            st.avgHr?.let { "avg HR $it" + (st.maxHr?.let { m -> ", max $m" } ?: "") },
            st.kcal?.takeIf { it > 0 }?.let { "~${it.roundToInt()} kcal" },
        )
        val lines = ArrayList<String>()
        if (parts.isNotEmpty()) lines += parts.joinToString(", ") + "."
        // the plan: a 15-minute walk after every meal
        val mains = setOf("salad_egg", "salad_carrot", "chicken_rice", "mixed_rice")
        val meal = meals.filter { it.foodId in mains || it.slot == Slot.LUNCH || it.slot == Slot.DINNER }
            .lastOrNull { val dt = (w.start.toEpochMilli() - it.timeMs) / 60_000; dt in 0..75 }
        if (meal != null && w.type == "Walk") {
            lines += if (mins >= Targets.walkAfterMealMin) "That's your after-${meal.slot.title.lowercase()} walk done ✓"
                else "After-${meal.slot.title.lowercase()} walk: $mins of ${Targets.walkAfterMealMin} min."
        }
        st.avgHr?.let { if (w.type != "Walk" && it < 100 && mins >= 20) lines += "Easy effort overall: mostly recovery-zone heart rate." }
        return Insight("act-${w.start.toEpochMilli()}", Kind.ACTIVITY, "$name · $mins min", lines.joinToString(" ").ifEmpty { "Recorded by your Fitbit." }, now)
    }

    // ---- evening food check ---------------------------------------------------------------------

    fun evening(entries: List<MealEntry>, date: LocalDate, now: Long): Insight {
        if (entries.isEmpty()) return Insight("food-$date", Kind.FOOD, "Nothing logged today",
            "Log today's meals in the Meals tab (a few taps each) so tomorrow's analysis counts them.", now)
        val t = MealStore.totals(entries)
        // what's still planned tonight (yoghurt bowl, supplements) and not logged yet
        val loggedSlots = entries.map { it.slot }.toSet()
        val still = MealPlan.day(date).filter { !it.optional && it.slot in setOf(Slot.YOGHURT, Slot.SUPPLEMENTS) && it.slot !in loggedSlots }
        val add = still.fold(com.silverajat.lifttracker.meals.Macros.ZERO) { a, p -> a + p.food.macros }
        val p = t.protein + add.protein
        val lines = ArrayList<String>()
        lines += "So far ${t.kcal.roundToInt()} kcal, ${t.protein.roundToInt()} g protein, ${t.fat.roundToInt()} g fat."
        if (still.isNotEmpty()) lines += "The 9 PM bowl and supplements add ~${add.kcal.roundToInt()} kcal, ${add.protein.roundToInt()} g protein."
        val gap = Targets.protein.start - p
        lines += when {
            gap > 20 -> "That leaves you ~${gap.roundToInt()} g short of ${Targets.protein.start.toInt()} g: a Meiji (+${Foods.get("meiji").macros.protein.roundToInt()} g) covers it."
            gap > 0 -> "About ${gap.roundToInt()} g short of ${Targets.protein.start.toInt()} g: close enough, or add a Meiji."
            else -> "Protein covered (${p.roundToInt()} g with tonight's bowl)."
        }
        val fat = t.fat + add.fat
        if (fat > Targets.fat.endInclusive + 10) lines += "Fat is high today (${fat.roundToInt()} g vs ${Targets.fat.endInclusive.toInt()} g): skip the extra peanut butter."
        return Insight("food-$date", Kind.FOOD, "Food check · ${p.roundToInt()} g protein", lines.joinToString(" "), now)
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
        val lines = ArrayList<String>()
        lines += a.weight.verdict.trimEnd('.') + "."
        lines += "${gym.size} gym sessions" + (if (gym.isNotEmpty()) " (${gym.sumOf { s -> s.sets.count { !it.warmup } }} working sets)" else "") + "."
        if (rec.isNotEmpty()) lines += "Average recovery ${rec.average().roundToInt()}%."
        if (sleep.isNotEmpty()) lines += "Average sleep ${hm(sleep.average().roundToInt())}."
        a.nutrition?.let { lines += "Food: ${it.avgKcal} kcal and ${it.avgProtein} g protein a day, protein hit ${it.proteinDaysHit}/${it.days} days." }
        a.effects.firstOrNull { it.clear }?.let { lines += "Your clearest pattern: ${it.habit.lowercase()} → next-day recovery ${signed(it.delta)}." }
        if (gym.isEmpty() && rec.isEmpty() && a.nutrition == null) return null
        return Insight("week-$monday", Kind.WEEKLY, "Last week", lines.joinToString(" "), now)
    }

    /** True if a Fitbit workout overlaps a pod gym session (that one already has its own summary). */
    fun overlapsGym(w: Workout, sessions: List<Session>): Boolean = sessions.any { s ->
        val e = s.endMs ?: System.currentTimeMillis()
        w.start.toEpochMilli() < e && w.end.toEpochMilli() > s.startMs
    }
}
