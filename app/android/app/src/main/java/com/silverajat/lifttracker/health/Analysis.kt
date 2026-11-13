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
 * RECOVERY (1-99%) each morning, against YOUR last 30 days:
 *   HRV (ln RMSSD, z-score)            weight 0.50
 *   resting HR (z, lower is better)    weight 0.20
 *   sleep performance (vs sleep need)  weight 0.20
 *   breathing rate (z, lower is better) weight 0.10
 *   score = 50 + 20 x weighted z, zones: green >= 67, yellow 34-66, red < 34.
 *   Weights renormalise over what's available; needs 5+ days of baseline.
 *
 * STRAIN (0-21), cardiovascular, from heart rate:
 *   strain = 21 x (1 - e^(-TRIMP / 110)), TRIMP = Banister (see Scores.load).
 *   Like WHOOP's, it's logarithmic: each extra point takes more work.
 *   Target band from recovery: green 14-18, yellow 10-14, red 5-10.
 *
 * MUSCULAR LOAD, from the pod: working-set volume (kg x reps) vs your
 * average gym day of the last 28 days. Heart rate misses most of lifting.
 *
 * SLEEP: need = 8 h + strain (+4 min per point above 10, max 45) + debt
 *   (half of the last 3 nights' shortfall, max 60 min). Performance =
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
        // what the need is made of: 8 h baseline + yesterday's strain + half the recent debt
        val needStrainMin: Int = 0, val needDebtMin: Int = 0,
        val deepMin: Int = 0, val remMin: Int = 0, val lightMin: Int = 0, val awakeMin: Int = 0,
        val stages: List<SleepStage> = emptyList(),
    )

    data class StrainResult(val strain: Double, val target: ClosedFloatingPointRange<Double>?, val trimp: Int, val moderateMin: Int, val vigorousMin: Int)

    data class MuscularLoad(val volumeKg: Double, val vsAvgPct: Int?, val sets: Int)

    data class ExerciseTrend(
        val exerciseId: Int, val name: String, val e1rmNow: Double, val e1rmThen: Double?, val changePct: Double?,
        val lastSets: String, val suggestion: String, val qualityAvg: Int?, val romAvg: Int?,
    )

    data class NutritionResult(
        val days: Int, val avgKcal: Int, val avgProtein: Int, val avgFat: Int, val proteinDaysHit: Int,
        val proteinPerKg: Double?, val avgBalance: Int?, val predictedKgPerWeek: Double?,
    )

    data class WeightResult(val lastWeekAvg: Double?, val prevWeekAvg: Double?, val changeKg: Double?, val changePctPerWeek: Double?, val verdict: String)

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
    )

    // ---- helpers -----------------------------------------------------------------------

    private data class Base(val mean: Double, val sd: Double, val n: Int)

    private fun base(x: List<Double>): Base? {
        if (x.size < 5) return null
        val m = x.average()
        val sd = sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1)).coerceAtLeast(1e-6)
        return Base(m, sd, x.size)
    }

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
        val s = x.sorted()
        val med = s[s.size / 2]
        return x.map { abs(it - med) }.average()
    }

    // ---- sleep ------------------------------------------------------------------------------

    fun sleep(days: List<HealthDay>, i: Int, strainPrev: Double?, zone: ZoneId): SleepResult? {
        val s = days[i].sleep ?: return null
        var need = 480.0
        val forStrain = if (strainPrev != null && strainPrev > 10) ((strainPrev - 10) * 4).coerceAtMost(45.0) else 0.0
        need += forStrain
        val prev = (1..3).mapNotNull { k -> days.getOrNull(i - k)?.sleep }
        val debt = prev.sumOf { (480 - it.totalMin).coerceAtLeast(0) } * 0.5
        need += debt.coerceAtMost(60.0)
        val recent = (0..3).mapNotNull { k -> days.getOrNull(i - k)?.sleep }
        val consistency = if (recent.size >= 3) {
            val spread = (mad(recent.map { bedMinutes(it.start, zone) }) + mad(recent.map { wakeMinutes(it.end, zone) })) / 2
            (100 - spread).roundToInt().coerceIn(0, 100)
        } else null
        val inBed = Duration.between(s.start, s.end).toMinutes().toInt().coerceAtLeast(1)
        val staged = s.deepMin + s.remMin + s.lightMin > 0
        return SleepResult(
            needMin = need.roundToInt(), asleepMin = s.totalMin,
            performance = (s.totalMin * 100.0 / need).roundToInt().coerceIn(0, 100),
            debtMin = debt.roundToInt(), consistency = consistency,
            efficiency = if (staged) (s.totalMin * 100 / inBed).coerceIn(0, 100) else null,
            deepPct = if (staged && s.totalMin > 0) s.deepMin * 100 / s.totalMin else null,
            remPct = if (staged && s.totalMin > 0) s.remMin * 100 / s.totalMin else null,
            bed = s.start, wake = s.end,
            needStrainMin = forStrain.roundToInt(), needDebtMin = debt.coerceAtMost(60.0).roundToInt(),
            deepMin = s.deepMin, remMin = s.remMin, lightMin = s.lightMin, awakeMin = s.awakeMin, stages = s.stages,
        )
    }

    // ---- recovery --------------------------------------------------------------------------

    fun recovery(days: List<HealthDay>, i: Int, sleepPerf: Int?): RecoveryResult? {
        val d = days[i]
        val past = days.subList(maxOf(0, i - 30), i)
        val parts = ArrayList<Pair<Double, Double>>()   // (z, weight)
        val contrib = ArrayList<Contributor>()
        val hrvBase = base(past.mapNotNull { it.hrvMs?.takeIf { v -> v > 0 }?.let { v -> ln(v) } })
        if (d.hrvMs != null && d.hrvMs > 0 && hrvBase != null) {
            val z = (ln(d.hrvMs) - hrvBase.mean) / hrvBase.sd
            parts += z to 0.5
            contrib += Contributor("HRV", "${d.hrvMs.roundToInt()} ms", "normal ${exp(hrvBase.mean).roundToInt()} ms", if (abs(z) < 0.5) null else z > 0)
        }
        val rhrBase = base(past.mapNotNull { it.restingHr?.toDouble() })
        if (d.restingHr != null && rhrBase != null) {
            val z = (rhrBase.mean - d.restingHr) / rhrBase.sd
            parts += z to 0.2
            contrib += Contributor("Resting HR", "${d.restingHr} bpm", "normal ${rhrBase.mean.roundToInt()}", if (abs(z) < 0.5) null else z > 0)
        }
        if (sleepPerf != null) {
            val z = ((sleepPerf - 85) / 10.0).coerceIn(-3.0, 1.5)
            parts += z to 0.2
            contrib += Contributor("Sleep performance", "$sleepPerf%", "aim 85%+", if (abs(z) < 0.5) null else z > 0)
        }
        val rrBase = base(past.mapNotNull { it.respRate })
        if (d.respRate != null && rrBase != null) {
            val z = (rrBase.mean - d.respRate) / rrBase.sd
            parts += z to 0.1
            contrib += Contributor("Breathing rate", "%.1f /min".format(d.respRate), "normal %.1f".format(rrBase.mean), if (abs(z) < 1.0 || abs(d.respRate - rrBase.mean) < 0.5) null else z > 0)
        }
        // needs HRV or resting HR against a baseline: sleep alone isn't a recovery score
        if (hrvBase == null && rhrBase == null) return null
        if (parts.isEmpty()) return null
        val z = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        val score = (50 + 20 * z).roundToInt().coerceIn(1, 99)
        return RecoveryResult(score, zoneOf(score), contrib, minOf(maxOf(hrvBase?.n ?: 0, rhrBase?.n ?: 0), 30))
    }

    // ---- gym -------------------------------------------------------------------------------

    private fun sessionDate(s: Session, zone: ZoneId) = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()

    fun volumeByDay(sessions: List<Session>, zone: ZoneId): Map<LocalDate, Pair<Double, Int>> =
        sessions.groupBy { sessionDate(it, zone) }.mapValues { (_, ss) ->
            val work = ss.flatMap { it.sets }.filter { !it.warmup }
            Pair(work.sumOf { it.weight * it.reps * (if (Routines.isOneArm(it.exerciseId)) 2 else 1) }, work.size)
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
            val e1 = lastSets.maxOf { Stats.oneRm(it.weight, it.reps) }
            val old = bySession.lastOrNull { it.first <= lastDate.minusDays(21) } ?: bySession.firstOrNull()?.takeIf { it.first < lastDate }
            val e1Then = old?.second?.maxOf { Stats.oneRm(it.weight, it.reps) }
                ?: Stats.value(com.silverajat.lifttracker.data.Metric.ONE_RM, ex.last)
            val top = (ex.last.filter { !it.warmup }.maxOfOrNull { it.reps } ?: 10).coerceAtLeast(8)
            val heaviest = lastSets.maxOf { it.weight }
            val atTop = lastSets.filter { it.weight == heaviest }
            val suggestion = when {
                ex.bodyweight -> "Add reps: aim ${atTop.maxOf { it.reps } + 1}+"
                atTop.all { it.reps >= top } -> "Hit $top+ on every set: go up to ${fmt(heaviest + ex.step)} kg"
                atTop.any { it.reps < top - 3 } -> "Stay at ${fmt(heaviest)} kg, build back to $top reps"
                else -> "Stay at ${fmt(heaviest)} kg, add a rep per set (target $top)"
            }
            val q = lastSets.mapNotNull { it.quality }
            val rom = lastSets.flatMap { it.romPct }
            ExerciseTrend(
                exerciseId = ex.id, name = ex.name, e1rmNow = e1, e1rmThen = e1Then,
                changePct = e1Then?.takeIf { it > 0 }?.let { (e1 / it - 1) * 100 },
                lastSets = lastSets.sortedBy { it.setNo }.joinToString(", ") { "${fmt(it.weight)}×${it.reps}" } +
                    " (${lastDate})",
                suggestion = suggestion,
                qualityAvg = q.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                romAvg = rom.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            )
        }
    }

    private fun fmt(w: Double) = if (w == Math.floor(w)) "%.0f".format(w) else "%.2f".format(w).trimEnd('0').trimEnd('.')

    // ---- food, weight ------------------------------------------------------------------------

    fun weight(weights: List<WeightEntry>, today: LocalDate): WeightResult {
        val weeks = MealStore.weekAverages(weights, today).filter { it.days >= 3 }
        val last = weeks.getOrNull(0)
        val prev = weeks.getOrNull(1)
        if (last == null) return WeightResult(null, null, null, null, "Weigh in each morning: needs a full week (3+ weigh-ins) to compare")
        if (prev == null) return WeightResult(last.avgKg, null, null, null, "One full week so far: next week gives the first comparison")
        val ch = last.avgKg - prev.avgKg
        val pct = ch / prev.avgKg * 100
        val verdict = when {
            pct > 0.6 -> "Gaining fast (${"%.2f".format(pct)}%/week). Plan: small cut, peanut butter first"
            pct >= 0.2 -> "Gaining at a lean-bulk pace (${"%.2f".format(pct)}%/week): keep the plan"
            pct > -0.2 -> "Holding steady (${"%.2f".format(pct)}%/week): if you want to gain, add ~150-200 kcal"
            else -> "Losing (${"%.2f".format(pct)}%/week): eat more if that's not the goal"
        }
        return WeightResult(last.avgKg, prev.avgKg, ch, pct, verdict)
    }

    fun nutrition(meals: Map<LocalDate, List<MealEntry>>, health: Map<LocalDate, HealthDay>, today: LocalDate, kg: Double?): NutritionResult? {
        val logged = meals.filterKeys { it < today && it >= today.minusDays(7) }.filterValues { it.isNotEmpty() }
        if (logged.isEmpty()) return null
        val tot = logged.mapValues { MealStore.totals(it.value) }
        val bal = logged.keys.mapNotNull { d -> health[d]?.kcalTotal?.let { tot.getValue(d).kcal - it } }
        val avgBal = bal.takeIf { it.size >= 3 }?.average()
        val avgP = tot.values.map { it.protein }.average()
        return NutritionResult(
            days = logged.size,
            avgKcal = tot.values.map { it.kcal }.average().roundToInt(),
            avgProtein = avgP.roundToInt(),
            avgFat = tot.values.map { it.fat }.average().roundToInt(),
            proteinDaysHit = tot.values.count { it.protein >= Targets.protein.start },
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
            if (with.size < 4 || without.size < 4) return@mapNotNull null
            fun v(x: List<Double>) = x.sumOf { (it - x.average()) * (it - x.average()) } / (x.size - 1)
            val se = sqrt(v(with) / with.size + v(without) / without.size)
            val delta = with.average() - without.average()
            Effect(name, with.average(), without.average(), with.size, without.size, se > 0 && abs(delta) > 2 * se)
        }.sortedByDescending { abs(it.delta) }

    // ---- everything -------------------------------------------------------------------------------

    fun run(
        health: List<HealthDay>, sessions: List<Session>, meals: Map<LocalDate, List<MealEntry>>,
        weights: List<WeightEntry>, today: LocalDate, zone: ZoneId, goalKgPerWeek: Double = 0.25,
    ): Result {
        val vol = volumeByDay(sessions, zone)
        val gymDays = vol.filterKeys { it >= today.minusDays(28) && it < today }.values.filter { it.second > 0 }
        val avgVol = gymDays.map { it.first }.takeIf { it.isNotEmpty() }?.average()

        val out = ArrayList<Day>()
        var prevStrain: Double? = null
        health.forEachIndexed { i, h ->
            val strain = h.load?.let { StrainResult(strainOf(it.trimp), null, it.trimp, it.moderateMin, it.vigorousMin) }
            val sl = sleep(health, i, prevStrain, zone)
            val rec = recovery(health, i, sl?.performance)
            val v = vol[h.date]
            out += Day(
                date = h.date, recovery = rec,
                strain = strain?.copy(target = rec?.let { strainTarget(it.zone) }),
                sleep = sl,
                muscular = v?.let { MuscularLoad(it.first, avgVol?.takeIf { a -> a > 0 }?.let { a -> (it.first / a * 100).roundToInt() }, it.second) },
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
        habit("Strain 14+") { d -> healthMap[d]?.load?.let { strainOf(it.trimp) >= 14 } }
        habit("Asleep before midnight") { d ->
            healthMap[d.plusDays(1)]?.sleep?.let { bedMinutes(it.start, zone) < 0 }
        }
        val effects = effects(habits, recMap)

        val nutrition = nutrition(meals, healthMap, today, lastKg)
        val weight = weight(weights, today)

        // headline
        val head = ArrayList<String>()
        todayDay?.recovery?.let { r ->
            val t = strainTarget(r.zone)
            head += "Recovery ${r.score}% (${r.zone.label.lowercase()}): aim for strain ${t.start.toInt()}-${t.endInclusive.toInt()} today."
            r.contributors.filter { it.good == false }.take(2).forEach { head += "${it.name} is off: ${it.value} (${it.vsNormal})." }
        } ?: run { if (health.isNotEmpty()) head += "Recovery needs 5+ days of HRV/resting HR from the Fitbit to compare against." }
        todayDay?.sleep?.let { s ->
            head += "Slept ${s.asleepMin / 60}h${s.asleepMin % 60}m of a ${s.needMin / 60}h${s.needMin % 60}m need (${s.performance}%)" +
                (if (s.debtMin >= 30) ", carrying ${s.debtMin} min of debt" else "") + "."
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

        return Result(out, todayDay, exerciseTrends(sessions, today, zone).sortedByDescending { it.changePct ?: -999.0 },
            groupSets, nutrition, weight, effects, energy, goalKgPerWeek, head)
    }
}
