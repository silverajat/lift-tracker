package com.silverajat.lifttracker.insights

import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.Stats
import com.silverajat.lifttracker.data.Strength
import com.silverajat.lifttracker.garden.Area
import com.silverajat.lifttracker.garden.FocusSession
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.journal.ReadSession
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Targets
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The week, Monday to Sunday, next to the week before: training, each lift, recovery, sleep, food, weight,
 * body signals and focus, then a few concrete changes for next week. Pure: everything comes in as data.
 */
object Weekly {
    /** One number, how it changed vs last week, and whether that's good (null: neither). */
    data class Line(val label: String, val value: String, val change: String? = null, val good: Boolean? = null)

    /** A lift's best set this week vs before the week (est. 1RM with the lift's own k). */
    data class Lift(val name: String, val now: String, val was: String?, val pct: Int?, val best: Boolean)

    /** One day of the week for the strip at the top. */
    data class DayMark(val date: LocalDate, val recovery: Analysis.Zone?, val sleepH: Double?, val strain: Double?, val gym: Boolean)

    data class Report(
        val start: LocalDate, val end: LocalDate, val complete: Boolean,
        val headline: String,
        val days: List<DayMark>,
        val next: List<String>,
        val training: List<Line>, val lifts: List<Lift>,
        val recovery: List<Line>, val sleep: List<Line>, val food: List<Line>, val weight: List<Line>,
        val body: List<Line>, val mind: List<Line>,
    )

    fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun build(
        a: Analysis.Result, health: List<HealthDay>, sessions: List<Session>, meals: Map<LocalDate, List<MealEntry>>,
        focus: List<FocusSession>, reads: List<ReadSession>, weekStart: LocalDate, today: LocalDate, zone: ZoneId,
    ): Report {
        val start = monday(weekStart)
        val end = minOf(start.plusDays(6), today)
        val complete = today > start.plusDays(6)
        val pStart = start.minusDays(7); val pEnd = start.minusDays(1)
        fun inW(d: LocalDate) = d in start..end
        fun inP(d: LocalDate) = d in pStart..pEnd
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        fun pctCh(now: Double, was: Double) = if (was == 0.0) null else ((now - was) / was * 100).roundToInt()
        fun signedPct(p: Int?) = p?.let { if (it == 0) "same" else "%+d%%".format(it) }

        // ---- training ----------------------------------------------------------------------------
        val gym = sessions.filter { it.sets.any { s -> !s.warmup } }
        val gW = gym.filter { inW(dayOf(it.startMs)) }; val gP = gym.filter { inP(dayOf(it.startMs)) }
        fun work(ss: List<Session>) = ss.flatMap { it.sets }.filter { !it.warmup }
        fun vol(ss: List<Session>) = work(ss).sumOf { it.load * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }
        val daysW = a.days.filter { inW(it.date) }; val daysP = a.days.filter { inP(it.date) }
        val strainW = daysW.mapNotNull { it.strain?.strain }; val strainP = daysP.mapNotNull { it.strain?.strain }
        val training = buildList {
            add(Line("Gym sessions", "${gW.size}", (gW.size - gP.size).let { if (gP.isEmpty() && gW.isEmpty()) null else if (it == 0) "same" else "%+d".format(it) },
                (gW.size - gP.size).takeIf { it != 0 }?.let { it > 0 }))
            if (gW.isNotEmpty()) {
                add(Line("Working sets", "${work(gW).size}", (work(gW).size - work(gP).size).takeIf { gP.isNotEmpty() }?.let { if (it == 0) "same" else "%+d".format(it) }))
                add(Line("Volume", "%,d kg".format(vol(gW).roundToInt()), if (gP.isNotEmpty()) signedPct(pctCh(vol(gW), vol(gP))) else null))
            }
            if (strainW.isNotEmpty()) add(Line("Strain, daily average", "%.1f".format(strainW.average()),
                if (strainP.isNotEmpty()) "%+.1f".format(strainW.average() - strainP.average()) else null))
        }

        // ---- each lift ------------------------------------------------------------------------------
        val lifts = work(gW).map { it.exerciseId }.distinct().mapNotNull { id ->
            val ex = runCatching { Routines.byId(id) }.getOrNull() ?: return@mapNotNull null
            val k = Strength.kFor(id, sessions)
            fun e1(l: com.silverajat.lifttracker.data.SetLog) = Stats.oneRm(l.load, l.reps, k)
            val nowSets = work(gW).filter { it.exerciseId == id }
            val top = nowSets.maxBy { e1(it) }
            val before = gym.filter { dayOf(it.startMs) < start }.sortedBy { it.startMs }
            val beforeSets = work(before).filter { it.exerciseId == id }
            val lastBefore = before.lastOrNull { s -> s.sets.any { it.exerciseId == id && !it.warmup } }
                ?.sets?.filter { it.exerciseId == id && !it.warmup }?.maxBy { e1(it) }
            val baseline = ex.last.filter { !it.warmup }.maxOfOrNull { Stats.oneRm(it.load, it.reps, k) }
            val bestBefore = listOfNotNull(beforeSets.maxOfOrNull { e1(it) }, baseline).maxOrNull()
            val wasE1 = lastBefore?.let { e1(it) } ?: baseline
            Lift(ex.name, "${Stats.fmt(top.load)}×${top.reps}",
                lastBefore?.let { "${Stats.fmt(it.load)}×${it.reps}" },
                wasE1?.let { pctCh(e1(top), it) },
                bestBefore != null && e1(top) > bestBefore + 1e-6)
        }.sortedWith(compareByDescending<Lift> { it.best }.thenByDescending { it.pct ?: -999 })

        // ---- recovery -------------------------------------------------------------------------------
        val recW = daysW.mapNotNull { it.recovery }; val recP = daysP.mapNotNull { it.recovery }
        fun geo(x: List<Double>) = exp(x.map { ln(it) }.average())
        val hrvW = health.filter { inW(it.date) }.mapNotNull { it.hrvMs?.takeIf { v -> v > 0 } }
        val hrvBase = health.filter { it.date < start && it.date >= start.minusDays(30) }.mapNotNull { it.hrvMs?.takeIf { v -> v > 0 } }
        val rhrW = health.filter { inW(it.date) }.mapNotNull { it.restingHr }
        val rhrBase = health.filter { it.date < start && it.date >= start.minusDays(30) }.mapNotNull { it.restingHr }
        val hrvPct = if (hrvW.size >= 3 && hrvBase.size >= 7) pctCh(geo(hrvW), geo(hrvBase)) else null
        val rhrDiff = if (rhrW.size >= 3 && rhrBase.size >= 7) rhrW.average() - rhrBase.average() else null
        val green = recW.count { it.zone == Analysis.Zone.GREEN }; val red = recW.count { it.zone == Analysis.Zone.RED }
        val recovery = buildList {
            if (recW.isNotEmpty()) {
                add(Line("Average", "${recW.map { it.score }.average().roundToInt()}%",
                    if (recP.isNotEmpty()) "%+d".format((recW.map { it.score }.average() - recP.map { it.score }.average()).roundToInt()) else null,
                    if (recP.isNotEmpty()) (recW.map { it.score }.average() >= recP.map { it.score }.average()) else null))
                add(Line("Green · yellow · red days", "$green · ${recW.size - green - red} · $red"))
            }
            hrvPct?.let { add(Line("HRV vs your month", "${geo(hrvW).roundToInt()} ms", "%+d%%".format(it), if (abs(it) < 8) null else it > 0 && it < 40)) }
            rhrDiff?.let { add(Line("Resting HR vs your month", "${rhrW.average().roundToInt()} bpm", "%+.1f".format(it), if (abs(it) < 1.5) null else it < 0)) }
        }

        // ---- sleep ----------------------------------------------------------------------------------
        val slW = daysW.mapNotNull { it.sleep }; val slP = daysP.mapNotNull { it.sleep }
        // bedtimes as minutes after noon, so 23:30 and 00:30 sit an hour apart
        fun bedMin(i: Instant) = i.atZone(zone).toLocalTime().let { t -> (t.hour * 60 + t.minute - 720 + 1440) % 1440 }
        val beds = slW.map { bedMin(it.bed) }
        val bedSpread = if (beds.size >= 3) beds.average().let { m -> sqrt(beds.sumOf { (it - m) * (it - m) } / beds.size) }.roundToInt() else null
        val sleep = buildList {
            if (slW.isNotEmpty()) {
                val asleep = slW.map { it.asleepMin }.average(); val need = slW.map { it.needMin }.average()
                add(Line("Asleep a night", hm(asleep.roundToInt()),
                    if (slP.isNotEmpty()) signedMin((asleep - slP.map { it.asleepMin }.average()).roundToInt()) else null,
                    if (slP.isNotEmpty()) asleep >= slP.map { it.asleepMin }.average() else null))
                add(Line("Needed a night", hm(need.roundToInt()), "${slW.count { it.asleepMin >= it.needMin * 0.95 }}/${slW.size} nights met"))
                add(Line("Sleep score", "${slW.map { it.score }.average().roundToInt()}",
                    if (slP.isNotEmpty()) "%+d".format((slW.map { it.score }.average() - slP.map { it.score }.average()).roundToInt()) else null))
                if (beds.size >= 3) add(Line("Usual bedtime", clock((beds.average().roundToInt() + 720) % 1440), "± ${bedSpread} min",
                    bedSpread?.let { it <= 30 }))
            }
        }

        // ---- food -----------------------------------------------------------------------------------
        val e = a.energy
        val fullDays = e.days.filter { inW(it.date) && it.logged }
        val kg = a.weight.trendKg ?: a.weight.lastWeighIn?.kg
        val eatenW = fullDays.map { MealStore.totals(meals[it.date].orEmpty()) }
        val targetsW = fullDays.map { Targets.day(it.trendKg ?: kg, it.burned?.toDouble() ?: (e.typicalFitbit * e.k), a.goalKgPerWeek) }
        val food = buildList {
            add(Line("Fully logged days", "${fullDays.size}/${(end.toEpochDay() - start.toEpochDay() + 1).toInt()}"))
            if (fullDays.isNotEmpty()) {
                val kcal = eatenW.map { it.kcal }.average(); val tk = targetsW.map { it.kcal }.average()
                val p = eatenW.map { it.protein }.average(); val tp = targetsW.map { it.protein }.average()
                val f = eatenW.map { it.fibre }.average(); val tf = targetsW.map { it.fibre }.average()
                add(Line("Calories", "%,d".format(kcal.roundToInt()), "target %,d".format(tk.roundToInt()), abs(kcal - tk) <= 150))
                add(Line("Protein", "${p.roundToInt()} g", "target ${tp.roundToInt()} g", p >= tp * 0.9))
                add(Line("Carbs · fat", "${eatenW.map { it.carbs }.average().roundToInt()} g · ${eatenW.map { it.fat }.average().roundToInt()} g",
                    "target ${targetsW.map { it.carbs }.average().roundToInt()} · ${targetsW.map { it.fat }.average().roundToInt()}"))
                add(Line("Fibre", "${f.roundToInt()} g", "target ${tf.roundToInt()} g", f >= tf * 0.85))
            }
        }

        // ---- weight ---------------------------------------------------------------------------------
        val trendEnd = e.days.lastOrNull { it.date <= end && it.trendKg != null }?.trendKg
        val trendStart = e.days.lastOrNull { it.date < start && it.trendKg != null }?.trendKg
        val span = (end.toEpochDay() - start.toEpochDay() + 1).toInt()
        val change = if (trendEnd != null && trendStart != null) trendEnd - trendStart else null
        val goalChange = a.goalKgPerWeek * span / 7
        val weighIns = e.days.count { inW(it.date) && it.scaleKg != null }
        val weight = buildList {
            trendEnd?.let { add(Line("Trend weight", "%.1f kg".format(it), change?.let { c -> "%+.2f kg".format(c) },
                change?.let { c -> abs(c - goalChange) <= 0.15 * span / 7 + 0.05 })) }
            add(Line("Weigh-ins", "$weighIns/$span"))
        }

        // ---- body signals -----------------------------------------------------------------------------
        val nightsOff = a.body?.nights.orEmpty().filter { inW(it.date) }
            .filter { n -> n.readings.sumOf { r -> if (r.signal == com.silverajat.lifttracker.health.BodyCheck.Signal.SLEEP) 0 else r.points } >= 2 }
        val body = buildList {
            if (a.body != null) add(Line("Nights clearly off", "${nightsOff.size}",
                nightsOff.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() } },
                if (nightsOff.isEmpty()) true else if (nightsOff.size >= 3) false else null))
        }

        // ---- focus and reading -------------------------------------------------------------------------
        fun focusMin(area: Area, inRange: (LocalDate) -> Boolean) = focus.filter { it.area == area && inRange(dayOf(it.startMs)) }.sumOf { it.minutes }
        val study = focusMin(Area.STUDY, ::inW); val workM = focusMin(Area.WORK, ::inW)
        val studyP = focusMin(Area.STUDY, ::inP); val workP = focusMin(Area.WORK, ::inP)
        val readW = reads.filter { inW(it.date) }.sumOf { it.minutes }; val readP = reads.filter { inP(it.date) }.sumOf { it.minutes }
        val mind = buildList {
            if (study + studyP > 0) add(Line("Study", hm(study), signedMin(study - studyP), (study - studyP).takeIf { it != 0 }?.let { it > 0 }))
            if (workM + workP > 0) add(Line("Work", hm(workM), signedMin(workM - workP), (workM - workP).takeIf { it != 0 }?.let { it > 0 }))
            if (readW + readP > 0) add(Line("Reading", hm(readW), signedMin(readW - readP), (readW - readP).takeIf { it != 0 }?.let { it > 0 }))
        }

        // ---- the week at a glance ------------------------------------------------------------------------
        val marks = (0 until 7).map { i ->
            val d = start.plusDays(i.toLong())
            val ad = a.days.firstOrNull { it.date == d }
            DayMark(d, ad?.recovery?.zone, ad?.sleep?.asleepMin?.div(60.0), ad?.strain?.strain, gW.any { dayOf(it.startMs) == d })
        }

        // ---- what to change next week (most important first, at most 4) ------------------------------------
        val next = ArrayList<String>()
        val usualGym = (1..4).map { w -> gym.count { dayOf(it.startMs) in start.minusWeeks(w.toLong())..start.minusWeeks(w.toLong()).plusDays(6) } }
            .filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
        if (red >= 3 || (hrvPct != null && hrvPct <= -10)) next += "Make next week lighter: one set less per lift, or one session fewer, until HRV is back to normal."
        if (slW.size >= 3) {
            val short = (slW.map { it.needMin - it.asleepMin }.average()).roundToInt()
            if (short >= 30) next += "Sleep was ${hm(short)} short a night: get to bed ${hm(short)} earlier" +
                (Analysis.tonight(a, today, zone).bed?.let { " (around ${clock(it.hour * 60 + it.minute)})" } ?: "") + "."
            else if (bedSpread != null && bedSpread > 45) next += "Bedtimes moved around by ±$bedSpread min: keep them within half an hour."
        }
        if (fullDays.size >= 3) {
            val p = eatenW.map { it.protein }.average(); val tp = targetsW.map { it.protein }.average()
            if (p < tp * 0.9) next += "Protein averaged ${p.roundToInt()} g vs ${tp.roundToInt()} g: a shake on ${(((tp - p) * 7) / 32.5).roundToInt().coerceIn(1, 7)} more days covers it."
        } else next += "Log full days of food (only ${fullDays.size} this week), so calories and macros can be checked."
        if (change != null && span >= 5) {
            val off = (change - goalChange) * 7 / span
            if (abs(off) >= 0.2) next += "Weight trend went ${"%+.2f".format(change)} kg vs a goal of ${"%+.2f".format(goalChange)}: " +
                (if (off > 0) "about ${(off * 7700 / 7).roundToInt()} kcal a day less" else "about ${(-off * 7700 / 7).roundToInt()} kcal a day more") + "."
        }
        if (complete && usualGym != null && gW.size < usualGym - 0.5) next += "Only ${gW.size} gym session${if (gW.size == 1) "" else "s"} (usually ${usualGym.roundToInt()}): plan them in for next week."
        val stalled = a.exercises.filter { t -> lifts.any { it.name == t.name } && (t.changePct ?: 1.0) <= 0.0 }.map { it.name }
        if (stalled.size >= 2) next += "Not moving yet: ${stalled.take(3).joinToString(", ")}. Add a rep per set before adding weight."
        if (fullDays.size >= 3 && eatenW.map { it.fibre }.average() < targetsW.map { it.fibre }.average() * 0.85)
            next += "Fibre ${eatenW.map { it.fibre }.average().roundToInt()} g vs ${targetsW.map { it.fibre }.average().roundToInt()} g: add a fruit or a scoop of psyllium."
        if (next.isEmpty()) next += "Nothing to fix: keep the same plan."

        // ---- headline -----------------------------------------------------------------------------------
        val prs = lifts.count { it.best }
        val upLifts = lifts.count { (it.pct ?: 0) > 1 }
        val recWord = when {
            recW.isEmpty() -> null
            green * 2 > recW.size -> "recovery mostly green"
            red * 2 >= recW.size -> "recovery mostly red"
            else -> "recovery mostly yellow"
        }
        val headline = listOfNotNull(
            "${gW.size} gym session${if (gW.size == 1) "" else "s"}",
            when { prs > 0 -> "$prs new best${if (prs == 1) "" else "s"}"; upLifts > 0 -> "$upLifts lift${if (upLifts == 1) "" else "s"} up"; else -> null },
            recWord,
            slW.takeIf { it.isNotEmpty() }?.let { "${hm(it.map { s -> s.asleepMin }.average().roundToInt())} sleep a night" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() } + "."

        return Report(start, end, complete, headline, marks, next.take(4), training, lifts, recovery, sleep, food, weight, body, mind)
    }

    fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    private fun signedMin(m: Int) = if (m == 0) "same" else (if (m > 0) "+" else "−") + hm(abs(m))
    private fun clock(min: Int) = "%d:%02d".format(min / 60, min % 60)
}
