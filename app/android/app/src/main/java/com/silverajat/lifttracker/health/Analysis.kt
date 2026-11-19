package com.silverajat.lifttracker.health

import com.silverajat.lifttracker.data.Group
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.Stats
import com.silverajat.lifttracker.meals.Macros
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Targets
import com.silverajat.lifttracker.meals.WeightEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WHOOP-style analysis, tailored: the Fitbit's heart and sleep data plus
 * the pod's gym log, the food log and morning weights. Every number comes
 * from a formula written out here (nothing hidden, nothing copied from
 * Google or WHOOP, whose scores other apps can't read).
 *
 * RECOVERY (1-99%) each morning, against YOUR last 30 days (full write-up: docs/HOW_IT_WORKS.md):
 *   HRV tonight (ln RMSSD, z)          0.35   near or a bit above normal is good; far off EITHER way is bad
 *   HRV past week (7-day mean, z)      0.15   same shape; one odd night can't carry the score
 *   resting HR (z, lower is better)    0.25   lower helps a little (capped at +1), higher hurts (to -2.5)
 *   sleep, last 4 nights               0.20   0.6 x last night + 0.4 x the 3 before, vs 85% of need
 *   breathing rate (z)                 0.05   a rise warns; a drop doesn't add
 *   score = 100 x Phi(z / 0.7 + 0.3): a normal day ~60, +-1 combined spread ~90 / ~25.
 *   Zones: green >= 67, yellow 34-66, red < 34. Needs 5+ days of baseline.
 *
 * STRAIN (0-21), cardiovascular, from heart rate:
 *   strain = 21 x (1 - e^(-TRIMP / 110)), TRIMP = Banister (see Scores.load).
 *   Like WHOOP's, it's logarithmic: each extra point takes more work.
 *   Target band from recovery: green 14-18, yellow 10-14, red 5-10.
 *
 * MUSCULAR LOAD, from the pod: working-set volume (kg x reps) vs your
 * average gym day of the last 28 days. Heart rate misses most of lifting.
 *
 * SLEEP: need = 7.5 h + strain (+4 min per point above 10, max 45) + debt
 *   (half of the last 3 nights' net shortfall, max 60 min). Performance =
 *   asleep / need. Consistency = how close bed and wake times are to the
 *   last 4 nights (100% = same minute, -1% per minute of average spread).
 *
 * WHAT AFFECTS YOUR RECOVERY: for each habit, next morning's recovery on
 *   days with vs without it. Shown once there are 4+ days each way; "clear"
 *   when the difference is more than twice its uncertainty.
 */
object Analysis {
    enum class Zone(val label: String) { GREEN("Recovered"), YELLOW("Adequate"), RED("Low") }

    data class Contributor(val name: String, val value: String, val vsNormal: String, val good: Boolean?)

    data class RecoveryResult(val score: Int, val zone: Zone, val contributors: List<Contributor>, val baselineDays: Int)

    data class SleepResult(
        val needMin: Int, val asleepMin: Int, val performance: Int, val debtMin: Int,
        val consistency: Int?, val efficiency: Int?, val deepPct: Int?, val remPct: Int?,
        val bed: Instant, val wake: Instant,
        // what the need is made of: 7.5 h baseline + yesterday's strain + half the recent debt
        val needStrainMin: Int = 0, val needDebtMin: Int = 0,
        val deepMin: Int = 0, val remMin: Int = 0, val lightMin: Int = 0, val awakeMin: Int = 0,
        val stages: List<SleepStage> = emptyList(),
        val score: Int = performance,
        val scoreParts: List<ScorePart> = emptyList(),
    )

    /** One piece of the sleep score: its own 0-100, and its share of the total. */
    data class ScorePart(val name: String, val value: String, val score: Int, val weight: Double, val about: String)

    /**
     * SLEEP SCORE (0-100): not just hours. Hours vs need 55%, consistency 15%, efficiency 15%,
     * deep 8% and REM 7%, each against its own normal range (a big REM night can't hide very little
     * deep: they do different jobs). The weights renormalise over what the Fitbit gave.
     * 85+ optimal, 70-84 sufficient, below 70 poor.
     */
    fun sleepScore(performance: Int, consistency: Int?, efficiency: Int?, deepPct: Int?, remPct: Int?): Pair<Int, List<ScorePart>> {
        val parts = listOfNotNull(
            ScorePart("Hours vs needed", "$performance%", performance.coerceIn(0, 100), 0.55, "asleep compared with what you needed"),
            consistency?.let { ScorePart("Consistency", "$it%", it.coerceIn(0, 100), 0.15, "bed and wake times vs the last few nights") },
            // 85% is the clinical line for good sleep (NSF); 90%+ scores full
            efficiency?.let { ScorePart("Efficiency", "$it%", ((it - 70) * 5).coerceIn(0, 100), 0.15, "asleep while in bed") },
            // adults: deep ~13-23%, REM ~20-25% of sleep. Full marks from 15% deep / 20% REM
            deepPct?.let { ScorePart("Deep", "$it%", ((it - 5) * 10).coerceIn(0, 100), 0.08, "of your sleep") },
            remPct?.let { ScorePart("REM", "$it%", ((it - 10) * 10).coerceIn(0, 100), 0.07, "of your sleep") },
        )
        val score = (parts.sumOf { it.score * it.weight } / parts.sumOf { it.weight }).roundToInt().coerceIn(0, 100)
        return score to parts
    }

    fun sleepZone(score: Int) = when { score >= 85 -> Zone.GREEN; score >= 70 -> Zone.YELLOW; else -> Zone.RED }

    /** trimp = heart-rate load + the gym's muscular load (MuscleLoad.TO_TRIMP of it); liftStrain = that day's session score. */
    data class StrainResult(val strain: Double, val target: ClosedFloatingPointRange<Double>?, val trimp: Int, val moderateMin: Int, val vigorousMin: Int,
                            val liftStrain: Double? = null, val hrTrimp: Int = trimp)

    data class MuscularLoad(val volumeKg: Double, val vsAvgPct: Int?, val sets: Int, val strain: Double? = null)

    data class ExerciseTrend(
        val exerciseId: Int, val name: String, val e1rmNow: Double, val e1rmThen: Double?, val changePct: Double?,
        val lastSets: String, val suggestion: String, val qualityAvg: Int?, val romAvg: Int?,
        /** Next time, short: the weight (null for bodyweight), the reps to aim for (null: whatever you get), and why. */
        val nextKg: Double? = null, val nextReps: Int? = null, val nextWhy: String = "",
    )

    data class NutritionResult(
        val days: Int, val avgKcal: Int, val avgProtein: Int, val avgFat: Int, val proteinDaysHit: Int,
        val proteinPerKg: Double?, val avgBalance: Int?, val predictedKgPerWeek: Double?,
    )

    data class WeightResult(val lastWeekAvg: Double?, val prevWeekAvg: Double?, val changeKg: Double?, val changePctPerWeek: Double?, val verdict: String,
                            val rateKgPerWeek: Double? = null, val trendKg: Double? = null, val lastWeighIn: WeightEntry? = null)

    data class Effect(val habit: String, val with: Double, val without: Double, val nWith: Int, val nWithout: Int, val clear: Boolean) {
        val delta get() = with - without
    }

    data class Day(
        val date: LocalDate,
        val recovery: RecoveryResult?,
        val strain: StrainResult?,
        val sleep: SleepResult?,
        val muscular: MuscularLoad?,
        val food: Macros?,
    )

    data class Result(
        val days: List<Day>,                 // oldest first
        val today: Day?,
        val exercises: List<ExerciseTrend>,
        val groupSets7d: Map<Group, Int>,
        val nutrition: NutritionResult?,
        val weight: WeightResult,
        val effects: List<Effect>,
        val energy: Energy.Result,           // net calories, checked against the scale
        val goalKgPerWeek: Double,
        val headline: List<String>,          // short plain-language summary for today
        val body: BodyCheck.Result? = null,  // what last night's signals say together
    )

    // ---- helpers -----------------------------------------------------------------------

    private data class Base(val mean: Double, val sd: Double, val n: Int)

    /**
     * Your normal over the window, and how much it usually varies. [floor] is the least
     * spread we assume: a Fitbit that read 50 bpm every night for a month would otherwise
     * make 52 look like a crisis.
     */
    private fun base(x: List<Double>, floor: Double): Base? {
        if (x.size < 5) return null
        // one wild night (a bad reading, a sick day) is pulled in to 3 spreads from the median first,
        // so it can't stretch "normal" for the next month and make every later warning look normal
        val med = median(x)
        val robust = (1.4826 * median(x.map { abs(it - med) })).coerceAtLeast(floor)
        val y = x.map { it.coerceIn(med - 3 * robust, med + 3 * robust) }
        val m = y.average()
        val sd = sqrt(y.sumOf { (it - m) * (it - m) } / (y.size - 1)).coerceAtLeast(floor)
        return Base(m, sd, x.size)
    }

    private fun median(x: List<Double>): Double {
        val s = x.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** One metric can move the score this far and no further. */
    private fun clampZ(z: Double) = z.coerceIn(-2.5, 2.5)

    fun strainOf(trimp: Int): Double = 21.0 * (1 - exp(-trimp / 110.0))

    fun zoneOf(score: Int) = when { score >= 67 -> Zone.GREEN; score >= 34 -> Zone.YELLOW; else -> Zone.RED }

    fun strainTarget(z: Zone): ClosedFloatingPointRange<Double> = when (z) {
        Zone.GREEN -> 14.0..18.0
        Zone.YELLOW -> 10.0..14.0
        Zone.RED -> 5.0..10.0
    }

    /** Clock time as minutes, with times after noon counted as before midnight (for bedtimes). */
    private fun bedMinutes(t: Instant, zone: ZoneId): Int {
        val lt = t.atZone(zone).toLocalTime()
        val m = lt.hour * 60 + lt.minute
        return if (m >= 12 * 60) m - 24 * 60 else m
    }

    private fun wakeMinutes(t: Instant, zone: ZoneId): Int = t.atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }

    private fun mad(x: List<Int>): Double {
        val med = median(x.map { it.toDouble() })
        return x.map { abs(it - med) }.average()
    }

    // ---- sleep ------------------------------------------------------------------------------

    /** Your baseline sleep need: 7 h 30 min. */
    const val SLEEP_BASE_MIN = 450

    /** What a night needs: the baseline, extra after a hard day, and half of what's still owed. */
    data class Need(val total: Int, val strainMin: Int, val payBackMin: Int, val owedMin: Int)

    /**
     * Need = 7 h 30 min + 4 min per strain point above 10 (max 45) + half of the sleep still owed from the
     * last 3 nights (max 60). Owed is the NET shortfall: a long night pays back a short one, so the debt
     * really does reset as you catch up (one long night only repays part of a run of short ones).
     */
    fun sleepNeed(strainPrev: Double?, prevNightsMin: List<Int>): Need {
        val forStrain = if (strainPrev != null && strainPrev > 10) ((strainPrev - 10) * 4).coerceAtMost(45.0) else 0.0
        val owed = prevNightsMin.sumOf { SLEEP_BASE_MIN - it }.coerceAtLeast(0)
        val payBack = (owed * 0.5).coerceAtMost(60.0)
        return Need((SLEEP_BASE_MIN + forStrain + payBack).roundToInt(), forStrain.roundToInt(), payBack.roundToInt(), owed)
    }

    /**
     * Tonight: how much sleep you need and when to turn the lights out.
     * [inBedMin] = need ÷ your sleep efficiency (the time awake in the night), [wakeFrom] says which wake-ups it's based on.
     */
    data class Tonight(
        val needMin: Int, val bed: LocalTime?, val wake: LocalTime?,
        val inBedMin: Int = needMin, val wakeFrom: String = "", val strainMin: Int = 0, val debtMin: Int = 0, val sickMin: Int = 0,
    )

    /** Minutes to fall asleep, added before the time in bed. */
    const val FALL_ASLEEP_MIN = 15

    /**
     * Need = 7 h 30 + today's strain so far (gym included) + half of what's owed from the last 3 nights, + 30 min
     * when your body looks like it's fighting something off (+15 while it's settling).
     * Wake-up = your median wake time on the same weekday over the last 6 weeks (2+ of them), else weekdays vs
     * weekends, else the last 2 weeks. Bedtime = wake-up − need ÷ your efficiency (last 7 nights) − 15 min to fall asleep.
     */
    fun tonight(r: Result, date: LocalDate, zone: ZoneId, strainFallback: Double? = null): Tonight {
        val strain = r.days.lastOrNull { it.date == date }?.strain?.strain ?: strainFallback
        val nd = sleepNeed(strain, r.days.filter { it.date <= date }.takeLast(3).mapNotNull { it.sleep?.asleepMin })
        val sick = when (r.body?.state) { BodyCheck.State.SICK -> 30; BodyCheck.State.SETTLING -> 15; else -> 0 }
        val need = nd.total + sick
        // wake-ups by the morning they happened on
        val wakes = r.days.filter { it.date > date.minusDays(42) && it.date <= date }
            .mapNotNull { d -> d.sleep?.let { d.date to it.wake.atZone(zone).toLocalTime() } }
        fun med(x: List<LocalTime>) = x.map { it.toSecondOfDay() }.sorted().let { s -> LocalTime.ofSecondOfDay(((s[(s.size - 1) / 2] + s[s.size / 2]) / 2).toLong()) }
        val tomorrow = date.plusDays(1).dayOfWeek
        fun isWeekend(d: java.time.DayOfWeek) = d == java.time.DayOfWeek.SATURDAY || d == java.time.DayOfWeek.SUNDAY
        val sameDay = wakes.filter { it.first.dayOfWeek == tomorrow }.map { it.second }
        val sameKind = wakes.filter { isWeekend(it.first.dayOfWeek) == isWeekend(tomorrow) }.map { it.second }
        val recent = wakes.filter { it.first > date.minusDays(14) }.map { it.second }
        val dayName = tomorrow.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.UK)
        val (wake, from) = when {
            sameDay.size >= 2 -> med(sameDay) to "${dayName}s"
            sameKind.size >= 3 -> med(sameKind) to (if (isWeekend(tomorrow)) "weekends" else "weekdays")
            recent.size >= 3 -> med(recent) to "lately"
            else -> return Tonight(need, null, null, need, "", nd.strainMin, nd.payBackMin, sick)
        }
        val eff = r.days.takeLast(7).mapNotNull { it.sleep?.efficiency }.takeIf { it.size >= 3 }?.average()?.div(100.0) ?: 0.9
        val inBed = (need / eff.coerceIn(0.75, 1.0)).roundToInt()
        return Tonight(need, wake.minusMinutes((inBed + FALL_ASLEEP_MIN).toLong()), wake, inBed, from, nd.strainMin, nd.payBackMin, sick)
    }

    fun sleep(days: List<HealthDay>, i: Int, strainPrev: Double?, zone: ZoneId): SleepResult? {
        val s = days[i].sleep ?: return null
        val nd = sleepNeed(strainPrev, (1..3).mapNotNull { k -> days.getOrNull(i - k)?.sleep?.totalMin })
        val need = nd.total.toDouble()
        val recent = (0..3).mapNotNull { k -> days.getOrNull(i - k)?.sleep }
        val consistency = if (recent.size >= 3) {
            val spread = (mad(recent.map { bedMinutes(it.start, zone) }) + mad(recent.map { wakeMinutes(it.end, zone) })) / 2
            (100 - spread).roundToInt().coerceIn(0, 100)
        } else null
        val inBed = Duration.between(s.start, s.end).toMinutes().toInt().coerceAtLeast(1)
        val staged = s.deepMin + s.remMin + s.lightMin > 0
        val perf = (s.totalMin * 100.0 / need).roundToInt().coerceIn(0, 100)
        val eff = if (staged) (s.totalMin * 100 / inBed).coerceIn(0, 100) else null
        val deepPct = if (staged && s.totalMin > 0) (s.deepMin * 100.0 / s.totalMin).roundToInt() else null
        val remPct = if (staged && s.totalMin > 0) (s.remMin * 100.0 / s.totalMin).roundToInt() else null
        val (score, parts) = sleepScore(perf, consistency, eff, deepPct, remPct)
        return SleepResult(
            score = score, scoreParts = parts,
            needMin = nd.total, asleepMin = s.totalMin,
            performance = perf,
            debtMin = nd.owedMin, consistency = consistency,
            efficiency = eff,
            deepPct = deepPct,
            remPct = remPct,
            bed = s.start, wake = s.end,
            needStrainMin = nd.strainMin, needDebtMin = nd.payBackMin,
            deepMin = s.deepMin, remMin = s.remMin, lightMin = s.lightMin, awakeMin = s.awakeMin, stages = s.stages,
        )
    }

    // ---- recovery --------------------------------------------------------------------------

    /**
     * HRV is good near and a little above your normal, but far outside it either way is a warning:
     * a big spike often means the body is under unusual load (or a bad reading), not super recovery.
     */
    private fun hrvEffect(z: Double) = if (z <= 1.0) z.coerceAtLeast(-2.5) else (1.0 - 1.25 * (z - 1.0)).coerceAtLeast(-2.5)

    /** Standard normal CDF (Abramowitz-Stegun), for turning the combined z into a 1-99 score. */
    private fun phi(x: Double): Double {
        val t = 1 / (1 + 0.2316419 * abs(x))
        val d = 0.3989423 * exp(-x * x / 2)
        val p = d * t * (0.3193815 + t * (-0.3565638 + t * (1.781478 + t * (-1.821256 + t * 1.330274))))
        return if (x >= 0) 1 - p else p
    }

    fun recovery(days: List<HealthDay>, i: Int, sleepPerf: Int?, priorSleep: List<Int> = emptyList()): RecoveryResult? {
        val d = days[i]
        val past = days.subList(maxOf(0, i - 30), i)
        val parts = ArrayList<Pair<Double, Double>>()   // (effect, weight)
        val contrib = ArrayList<Contributor>()
        fun word(z: Double, hi: String, lo: String) = when { z >= 2 -> "much $hi than usual"; z >= 1 -> "$hi than usual"; z <= -2 -> "much $lo than usual"; z <= -1 -> "$lo than usual"; else -> "normal" }
        val hrvBase = base(past.mapNotNull { it.hrvMs?.takeIf { v -> v > 0 }?.let { v -> ln(v) } }, floor = 0.08)
        if (d.hrvMs != null && d.hrvMs > 0 && hrvBase != null) {
            val z = (ln(d.hrvMs) - hrvBase.mean) / hrvBase.sd
            val e = hrvEffect(z)
            parts += e to 0.35
            contrib += Contributor("HRV", "${d.hrvMs.roundToInt()} ms", "normal ${exp(hrvBase.mean).roundToInt()} ms · ${word(z, "higher", "lower")}",
                if (abs(z) < 1) null else e > 0)
            // the past week's average against the same normal: one odd night can't carry it
            val week = days.subList(maxOf(0, i - 6), i + 1).mapNotNull { it.hrvMs?.takeIf { v -> v > 0 }?.let { v -> ln(v) } }
            if (week.size >= 3) {
                val zw = (week.average() - hrvBase.mean) / (hrvBase.sd * 0.5)
                val ew = hrvEffect(zw)
                parts += ew to 0.15
                contrib += Contributor("HRV, past week", "${exp(week.average()).roundToInt()} ms", word(zw, "higher", "lower"), if (abs(zw) < 1) null else ew > 0)
            }
        }
        val rhrBase = base(past.mapNotNull { it.restingHr?.toDouble() }, floor = 2.0)
        if (d.restingHr != null && rhrBase != null) {
            val z = (rhrBase.mean - d.restingHr) / rhrBase.sd
            val e = z.coerceIn(-2.5, 1.0)   // lower than usual helps a little; higher hurts a lot
            parts += e to 0.25
            contrib += Contributor("Resting HR", "${d.restingHr} bpm", "normal ${rhrBase.mean.roundToInt()} · ${word(-z, "higher", "lower")}", if (abs(z) < 1) null else e > 0)
        }
        if (sleepPerf != null) {
            // last night counts most, the three before it too (one bad night matters less than a run of them)
            val recent = if (priorSleep.isEmpty()) sleepPerf.toDouble() else 0.6 * sleepPerf + 0.4 * priorSleep.average()
            val z = ((recent - 85) / 10.0).coerceIn(-3.0, 1.5)
            parts += z to 0.20
            contrib += Contributor("Sleep, last 4 nights", "${recent.roundToInt()}%", "of what you needed", if (abs(z) < 0.5) null else z > 0)
        }
        val rrBase = base(past.mapNotNull { it.respRate }, floor = 0.6)
        if (d.respRate != null && rrBase != null) {
            val z = (rrBase.mean - d.respRate) / rrBase.sd
            val e = z.coerceIn(-2.5, 0.5)   // a rise is the warning sign; a drop is usually just a different night
            parts += e to 0.05
            contrib += Contributor("Breathing rate", "%.1f /min".format(d.respRate), "normal %.1f".format(rrBase.mean), if (e > -1.0) null else false)
        }
        // needs HRV or resting HR against a baseline: sleep alone isn't a recovery score
        if (hrvBase == null && rhrBase == null) return null
        if (parts.isEmpty()) return null
        val z = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        // a normal day lands around 60; one combined "usual spread" either way moves it to about 90 or 25
        val score = (100 * phi(z / 0.7 + 0.3)).roundToInt().coerceIn(1, 99)
        return RecoveryResult(score, zoneOf(score), contrib, minOf(maxOf(hrvBase?.n ?: 0, rhrBase?.n ?: 0), 30))
    }

    // ---- gym -------------------------------------------------------------------------------

    private fun sessionDate(s: Session, zone: ZoneId) = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()

    fun volumeByDay(sessions: List<Session>, zone: ZoneId): Map<LocalDate, Pair<Double, Int>> =
        sessions.groupBy { sessionDate(it, zone) }.mapValues { (_, ss) ->
            val work = ss.flatMap { it.sets }.filter { !it.warmup }
            Pair(work.sumOf { it.load * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }, work.size)
        }

    fun exerciseTrends(sessions: List<Session>, today: LocalDate, zone: ZoneId): List<ExerciseTrend> {
        val all = Routines.upper + Routines.lower
        return all.mapNotNull { ex ->
            val bySession = sessions.sortedBy { it.startMs }.mapNotNull { s ->
                val work = s.sets.filter { it.exerciseId == ex.id && !it.warmup }
                if (work.isEmpty()) null else Pair(sessionDate(s, zone), work)
            }
            if (bySession.isEmpty()) return@mapNotNull null
            val (lastDate, lastSets) = bySession.last()
            val e1 = lastSets.maxOf { Stats.oneRm(it.load, it.reps) }
            val old = bySession.lastOrNull { it.first <= lastDate.minusDays(21) } ?: bySession.firstOrNull()?.takeIf { it.first < lastDate }
            val e1Then = old?.second?.maxOf { Stats.oneRm(it.load, it.reps) }
                ?: Stats.value(com.silverajat.lifttracker.data.Metric.ONE_RM, ex.last)
            val top = (ex.last.filter { !it.warmup }.maxOfOrNull { it.reps } ?: 10).coerceAtLeast(8)
            val heaviest = lastSets.maxOf { it.weight }
            val atTop = lastSets.filter { it.weight == heaviest }
            // range of motion: the third lever. Short reps first get fixed, before more reps or more weight
            val romLast = lastSets.flatMap { it.romPct }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
            val shortRom = romLast != null && romLast < ROM_FULL
            val suggestion = when {
                shortRom -> "Stay at ${fmt(heaviest)} kg and use the full range (ROM $romLast%, aim $ROM_FULL%+)"
                ex.bodyweight -> "Add reps: aim ${atTop.maxOf { it.reps } + 1}+"
                atTop.all { it.reps >= top } -> "Hit $top+ on every set: go up to ${fmt(heaviest + ex.step)} kg"
                atTop.any { it.reps < top - 3 } -> "Stay at ${fmt(heaviest)} kg, build back to $top reps"
                else -> "Stay at ${fmt(heaviest)} kg, add a rep per set (target $top)"
            }
            val (nextKg, nextReps, nextWhy) = when {
                shortRom -> Triple<Double?, Int?, String>(if (ex.bodyweight) null else heaviest, null, "full range")
                ex.bodyweight -> Triple<Double?, Int?, String>(null, atTop.maxOf { it.reps } + 1, "add a rep")
                atTop.all { it.reps >= top } -> Triple(heaviest + ex.step, null, "go heavier")
                atTop.any { it.reps < top - 3 } -> Triple(heaviest, top, "build back up")
                else -> Triple(heaviest, top, "add reps")
            }
            val q = lastSets.mapNotNull { it.quality }
            val rom = lastSets.flatMap { it.romPct }
            ExerciseTrend(
                exerciseId = ex.id, name = ex.name, e1rmNow = e1, e1rmThen = e1Then,
                changePct = e1Then?.takeIf { it > 0 }?.let { (e1 / it - 1) * 100 },
                lastSets = lastSets.sortedBy { it.setNo }.joinToString(", ") { "${fmt(it.weight)}×${it.reps}" } +
                    " (${lastDate})",
                suggestion = suggestion,
                nextKg = nextKg, nextReps = nextReps, nextWhy = nextWhy,
                qualityAvg = q.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                romAvg = rom.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            )
        }
    }

    private fun fmt(w: Double) = if (w == Math.floor(w)) "%.0f".format(w) else "%.2f".format(w).trimEnd('0').trimEnd('.')

    /** ROM % (of the calibration set) that counts as full range; under it, range comes before reps or weight. */
    const val ROM_FULL = 90

    // ---- food, weight ------------------------------------------------------------------------

    /**
     * Rate of change from every weigh-in in the last 3 weeks (a straight-line fit), so one
     * salty or dehydrated morning can't swing it. Needs 8+ weigh-ins over 10+ days.
     */
    fun weightRate(weights: List<WeightEntry>, today: LocalDate): Double? {
        val w = weights.filter { it.date <= today && it.date > today.minusDays(21) }
        if (w.size < 8 || w.maxOf { it.date }.toEpochDay() - w.minOf { it.date }.toEpochDay() < 10) return null
        val xs = w.map { it.date.toEpochDay().toDouble() }; val ys = w.map { it.kg }
        val mx = xs.average(); val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx * 7
    }

    fun weight(weights: List<WeightEntry>, today: LocalDate, goalKgPerWeek: Double = 0.0): WeightResult =
        weightVerdict(weights, today, goalKgPerWeek).copy(trendKg = WeightTrend.now(weights.sortedBy { it.date }, today),
            lastWeighIn = weights.filter { it.date <= today }.maxByOrNull { it.date })

    private fun weightVerdict(weights: List<WeightEntry>, today: LocalDate, goalKgPerWeek: Double): WeightResult {
        val weeks = MealStore.weekAverages(weights, today).filter { it.days >= 3 }
        val last = weeks.getOrNull(0)
        val prev = weeks.getOrNull(1)
        val rate = weightRate(weights, today)
        if (last == null) return WeightResult(null, null, null, null, "Weigh in each morning: needs a full week (3+ weigh-ins) to compare", rate)
        if (prev == null && rate == null) return WeightResult(last.avgKg, null, null, null, "One full week so far: next week gives the first comparison")
        val ch = prev?.let { last.avgKg - it.avgKg }
        val pct = ch?.let { it / prev.avgKg * 100 }
        // judged on the 3-week trend when there is one (week-to-week averages jump with water and salt);
        // within 0.15 kg a week of the goal counts as on target
        val r = rate ?: ch!!
        val off = r - goalKgPerWeek
        val kcal = (kotlin.math.abs(off) * 7700 / 7 / 50).roundToInt() * 50
        val goalWord = if (goalKgPerWeek == 0.0) "maintenance" else if (goalKgPerWeek > 0) "gain goal" else "cut"
        val what = if (rate != null) "trend" else "week"
        val verdict = when {
            kotlin.math.abs(off) <= 0.15 -> if (goalKgPerWeek == 0.0) "Holding steady (%+.2f kg a week, $what): right on maintenance".format(r)
                else "%+.2f kg a week: right on your $goalWord".format(r)
            off > 0 -> "Gaining %.2f kg a week ($what), above $goalWord: trim about $kcal kcal a day (peanut butter first)".format(r)
            else -> "Losing %.2f kg a week ($what), below $goalWord: about $kcal kcal a day more".format(-r)
        }
        return WeightResult(last.avgKg, prev?.avgKg, ch, pct, verdict, rate)
    }

    /**
     * Last 7 days of food. Averages use fully logged days when there are any (a day with a forgotten
     * meal would drag every average down), and the balance uses the scale-corrected burn, like the Net tab.
     */
    fun nutrition(meals: Map<LocalDate, List<MealEntry>>, energy: Energy.Result, today: LocalDate, kg: Double?): NutritionResult? {
        val any = meals.filterKeys { it < today && it >= today.minusDays(7) }.filterValues { it.isNotEmpty() }
        if (any.isEmpty()) return null
        val complete = energy.days.filter { it.logged && it.date in any.keys }
        val logged = if (complete.isNotEmpty()) any.filterKeys { d -> complete.any { it.date == d } } else any
        val tot = logged.mapValues { MealStore.totals(it.value) }
        val bal = complete.mapNotNull { it.net }
        val avgBal = bal.takeIf { it.size >= 3 }?.average()
        val avgP = tot.values.map { it.protein }.average()
        val proteinGoal = kg?.let { Targets.PROTEIN_G_PER_KG * it * 0.9 } ?: 110.0
        return NutritionResult(
            days = logged.size,
            avgKcal = tot.values.map { it.kcal }.average().roundToInt(),
            avgProtein = avgP.roundToInt(),
            avgFat = tot.values.map { it.fat }.average().roundToInt(),
            proteinDaysHit = tot.values.count { it.protein >= proteinGoal },
            proteinPerKg = kg?.let { avgP / it },
            avgBalance = avgBal?.roundToInt(),
            predictedKgPerWeek = avgBal?.let { it * 7 / 7700 },
        )
    }

    // ---- what affects recovery ------------------------------------------------------------------

    fun effects(habits: Map<String, Map<LocalDate, Boolean>>, recovery: Map<LocalDate, Int>): List<Effect> =
        habits.mapNotNull { (name, byDay) ->
            // a habit on day d is compared with recovery on the morning of d+1
            val with = byDay.filter { it.value }.keys.mapNotNull { recovery[it.plusDays(1)]?.toDouble() }
            val without = byDay.filter { !it.value }.keys.mapNotNull { recovery[it.plusDays(1)]?.toDouble() }
            if (with.size < MIN_EACH || without.size < MIN_EACH) return@mapNotNull null
            fun v(x: List<Double>) = x.sumOf { (it - x.average()) * (it - x.average()) } / (x.size - 1)
            val se = sqrt(v(with) / with.size + v(without) / without.size)
            val delta = with.average() - without.average()
            // 2.5, not 2: six habits are tested at once, so plain 2 would flag a fluke about 1 time in 4
            Effect(name, with.average(), without.average(), with.size, without.size, se > 0 && abs(delta) > 2.5 * se)
        }.sortedByDescending { abs(it.delta) }

    private const val MIN_EACH = 5

    // ---- everything -------------------------------------------------------------------------------

    fun run(
        health: List<HealthDay>, sessions: List<Session>, meals: Map<LocalDate, List<MealEntry>>,
        weights: List<WeightEntry>, today: LocalDate, zone: ZoneId, goalKgPerWeek: Double = 0.0,
    ): Result {
        val vol = volumeByDay(sessions, zone)
        val gymDays = vol.filterKeys { it >= today.minusDays(28) && it < today }.values.filter { it.second > 0 }
        val avgVol = gymDays.map { it.first }.takeIf { it.isNotEmpty() }?.average()

        val lift = MuscleLoad.byDay(sessions, zone)
        val out = ArrayList<Day>()
        var prevStrain: Double? = null
        val perfs = ArrayList<Int>()   // sleep performance night by night, for recovery's "last 4 nights"
        health.forEachIndexed { i, h ->
            // heart rate plus what the gym did to your muscles (heart rate alone undercounts lifting)
            val ml = lift[h.date]
            val strain = if (h.load == null && ml == null) null else {
                val hr = h.load?.trimp ?: 0
                val total = hr + (ml?.let { MuscleLoad.trimpOf(it.load) } ?: 0)
                StrainResult(strainOf(total), null, total, h.load?.moderateMin ?: 0, h.load?.vigorousMin ?: 0, ml?.strain, hr)
            }
            val sl = sleep(health, i, prevStrain, zone)
            val rec = recovery(health, i, sl?.performance, perfs.takeLast(3))
            sl?.performance?.let { perfs += it }
            val v = vol[h.date]
            out += Day(
                date = h.date, recovery = rec,
                strain = strain?.copy(target = rec?.let { strainTarget(it.zone) }),
                sleep = sl,
                muscular = v?.let { MuscularLoad(it.first, avgVol?.takeIf { a -> a > 0 }?.let { a -> (it.first / a * 100).roundToInt() }, it.second, ml?.strain) },
                food = meals[h.date]?.takeIf { it.isNotEmpty() }?.let { MealStore.totals(it) },
            )
            prevStrain = strain?.strain
        }
        val todayDay = out.lastOrNull { it.date == today }

        // muscle-group sets in the last 7 days (working sets; one-arm sets count once per arm side)
        val groupSets = sessions.filter { sessionDate(it, zone) > today.minusDays(7) }
            .flatMap { it.sets }.filter { !it.warmup }
            .groupBy { runCatching { Routines.group(Routines.byId(it.exerciseId)) }.getOrDefault(Group.CORE) }
            .mapValues { it.value.size }

        val healthMap = health.associateBy { it.date }
        val lastKg = weights.lastOrNull()?.kg
        val recMap = out.mapNotNull { d -> d.recovery?.let { d.date to it.score } }.toMap()

        // habits per day (evening habits are compared with the NEXT morning's recovery)
        val dates = health.map { it.date }.filter { it < today }
        val habits = linkedMapOf<String, Map<LocalDate, Boolean>>()
        fun habit(name: String, f: (LocalDate) -> Boolean?) {
            val m = dates.mapNotNull { d -> f(d)?.let { d to it } }.toMap()
            if (m.isNotEmpty()) habits[name] = m
        }
        habit("Last food before 9:30 PM") { d ->
            meals[d]?.takeIf { it.isNotEmpty() }?.maxOf { it.timeMs }?.let {
                Instant.ofEpochMilli(it).atZone(zone).toLocalTime() <= LocalTime.of(21, 30)
            }
        }
        val energy = Energy.estimate(meals, weights, healthMap, today)
        val netByDay = energy.days.associate { it.date to it.net }
        habit("Net calories in surplus") { d -> netByDay[d]?.let { it >= 0 } }
        habit("Gym session") { d -> (vol[d]?.second ?: 0) > 0 }
        habit("10,000+ steps") { d -> healthMap[d]?.steps?.let { it >= 10_000 } }
        val strainByDay = out.associate { it.date to it.strain?.strain }
        habit("Strain 14+") { d -> strainByDay[d]?.let { it >= 14 } }
        habit("Asleep before midnight") { d ->
            healthMap[d.plusDays(1)]?.sleep?.let { bedMinutes(it.start, zone) < 0 }
        }
        val effects = effects(habits, recMap)

        val weight = weight(weights, today, goalKgPerWeek)
        val nutrition = nutrition(meals, energy, today, weight.trendKg ?: lastKg)

        // headline
        val head = ArrayList<String>()
        todayDay?.recovery?.let { r ->
            val t = strainTarget(r.zone)
            head += "Recovery ${r.score}% (${r.zone.label.lowercase()}): aim for strain ${t.start.toInt()}-${t.endInclusive.toInt()} today."
            r.contributors.filter { it.good == false }.take(2).forEach { head += "${it.name} is off: ${it.value} (${it.vsNormal})." }
        } ?: run { if (health.isNotEmpty()) head += "Recovery needs 5+ days of HRV/resting HR from the Fitbit to compare against." }
        todayDay?.sleep?.let { s ->
            head += "Slept ${s.asleepMin / 60}h${s.asleepMin % 60}m of a ${s.needMin / 60}h${s.needMin % 60}m need (${s.performance}%)" +
                (if (s.debtMin >= 60) ", owing ${s.debtMin} min from the last 3 nights" else "") + "."
        }
        energy.avgNet?.let { n ->
            val goal = energy.netFor(goalKgPerWeek)
            head += "Net calories, last 2 weeks: ${if (n >= 0) "+" else ""}$n a day (goal ${if (goal >= 0) "+" else ""}$goal)" +
                (energy.trendKgPerWeek?.let { " · scale trend ${"%+.2f".format(it)} kg/week" } ?: "") + "."
        }
        if (today == Targets.reviewDate || weight.changePctPerWeek != null) head += "Weight: ${weight.verdict}."
        effects.firstOrNull { it.clear }?.let { e ->
            head += "Pattern: ${e.habit.lowercase()} → next-day recovery ${if (e.delta > 0) "+" else ""}${e.delta.roundToInt()} points."
        }

        val ctx = out.associate { d ->
            d.date to BodyCheck.Context(d.strain?.strain, d.muscular?.vsAvgPct, d.muscular?.strain, d.sleep?.performance, d.sleep?.debtMin,
                meals[d.date]?.takeIf { it.isNotEmpty() }?.maxOf { it.timeMs }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() })
        }
        val body = BodyCheck.run(health, ctx, today)
        body?.takeIf { it.state != BodyCheck.State.NORMAL && it.state != BodyCheck.State.ADAPTING }?.let { b ->
            head += "Body: ${b.state.label.lowercase()}" + (b.reasons.firstOrNull()?.let { r -> " (${r.title.lowercase()}: ${r.evidence.take(3).joinToString("; ")})" } ?: "") + "."
        }

        return Result(out, todayDay, exerciseTrends(sessions, today, zone).sortedByDescending { it.changePct ?: -999.0 },
            groupSets, nutrition, weight, effects, energy, goalKgPerWeek, head, body)
    }
}
