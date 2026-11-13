package com.silverajat.lifttracker.health

import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.WeightEntry
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Net calories (eaten − burned), with "burned" checked against the scale.
 *
 * Wearables guess calories from heart rate and movement and are often
 * 10-25% off. Your weight, over a few weeks, isn't: if you ate 2,900 a day
 * and held steady, you burned ~2,900. MacroFactor and similar apps work the
 * same way: a smoothed weight trend plus logged intake, solved for burn.
 *
 * Here it's a small Kalman filter, run day by day:
 *   state:    W = your true (trend) weight, kg
 *             k = how far off the Fitbit is (real burn = k × Fitbit's number)
 *   each day: W' = W + (eaten − k × fitbit) / 7700
 *   measured: the morning scale = W + water noise (±~0.6 kg)
 *
 * It starts at k = 1 (trust the Fitbit) with ±15% doubt, and the weigh-ins
 * pull it to the truth over ~2-4 weeks. Because it corrects the Fitbit's
 * DAILY numbers, each day keeps its own burn (gym day vs lazy Sunday).
 *
 * Days with under 1,200 kcal logged count as "not logged" (a forgotten
 * meal would otherwise look like a huge deficit): on those days the filter
 * just widens its doubt about the weight instead of learning from them.
 */
object Energy {
    const val KCAL_PER_KG = 7700.0          // ~3,500 kcal/lb; early on, water and glycogen make it lower
    const val MIN_LOGGED = 1200.0
    private const val SCALE_SD = 0.6        // kg, day-to-day water/food/gut swing
    private const val LOG_SD_FRAC = 0.15    // logged intake is ±15% (hawker estimates)
    private const val K_DRIFT_SD = 0.004    // the correction can drift slowly (per day)
    const val DAYS = 60L

    /** Today's net: so far, and by the end of the day if you eat the rest of the plan. */
    data class TodayNet(val soFar: Int, val endOfDay: Int, val restOfPlan: Boolean)

    fun today(e: Result, entries: List<MealEntry>, date: LocalDate, now: java.time.LocalTime): TodayNet {
        val eaten = MealStore.totals(entries).kcal
        val min = now.hour * 60 + now.minute
        val burnedSoFar = e.todayFitbitSoFar?.let { it * e.k } ?: (e.typicalFitbit * e.k * min / 1440.0)
        val logged = entries.map { it.slot }.toSet()
        val rest = if (entries.isEmpty()) emptyList()
            else com.silverajat.lifttracker.meals.MealPlan.day(date).filter { !it.optional && it.slot !in logged }
        return TodayNet(
            soFar = (eaten - burnedSoFar).roundToInt(),
            endOfDay = (eaten + rest.sumOf { it.food.macros.kcal } - e.burnedToday(min)).roundToInt(),
            restOfPlan = rest.isNotEmpty(),
        )
    }

    data class DayNet(val date: LocalDate, val net: Int?, val burned: Int?, val logged: Boolean, val trendKg: Double?, val scaleKg: Double?)

    data class Result(
        val k: Double,                  // real burn ÷ Fitbit burn
        val kSd: Double,
        val confidence: Int,            // 0..100: how settled k is
        val fitbitOffPct: Int,          // + = Fitbit says more than you burn
        val avgNet: Int?,               // last 14 logged days, eaten − corrected burn
        val scaleNet: Int?,             // what the weight trend alone implies per day (last 14 days)
        val trendKg: Double?,           // today's trend weight
        val trendKgPerWeek: Double?,    // trend slope over the last 14 days
        val todayFitbitSoFar: Double?,
        val typicalFitbit: Double,      // the Fitbit's median day
        val days: List<DayNet>,         // oldest first, up to [DAYS]
        val note: String,
    ) {
        fun burned(fitbitKcal: Double) = (fitbitKcal * k).roundToInt()
        /**
         * Today's burn for the whole day: the Fitbit's number so far plus the
         * rest of the day at about resting rate, corrected by k.
         */
        fun burnedToday(minutesNow: Int): Int {
            val soFar = todayFitbitSoFar ?: return (typicalFitbit * k).roundToInt()
            val rest = (1440 - minutesNow).coerceAtLeast(0) * typicalFitbit * 0.6 / 1440
            return ((soFar + rest) * k).roundToInt()
        }
        /** Net calories a day for a weekly weight change. */
        fun netFor(kgPerWeek: Double) = (kgPerWeek * KCAL_PER_KG / 7).roundToInt()
    }

    fun estimate(
        meals: Map<LocalDate, List<MealEntry>>, weights: List<WeightEntry>, health: Map<LocalDate, HealthDay>, today: LocalDate,
        fallbackBurn: Double = 3000.0,
    ): Result {
        val from = today.minusDays(DAYS)
        val scale = weights.filter { it.date >= from && it.date <= today }.associate { it.date to it.kg }
        val fitbitDays = health.filterKeys { it < today }.mapNotNull { (d, h) -> h.kcalTotal?.takeIf { it > 1000 }?.let { d to it } }.toMap()
        val typicalFitbit = fitbitDays.values.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] } ?: fallbackBurn
        fun eaten(d: LocalDate) = meals[d]?.takeIf { it.isNotEmpty() }?.let { MealStore.totals(it).kcal }

        // start the day of the first weigh-in in the window
        val first = scale.keys.minOrNull()
        var w = first?.let { scale.getValue(it) } ?: 0.0
        var kk = 1.0
        // covariance [[pww, pwk],[pwk, pkk]]
        var pww = SCALE_SD * SCALE_SD
        var pwk = 0.0
        var pkk = 0.15 * 0.15
        val out = ArrayList<DayNet>()
        var d = first ?: today
        while (d < today) {
            val f = fitbitDays[d] ?: typicalFitbit
            val e = eaten(d)
            val logged = e != null && e >= MIN_LOGGED
            // measurement update with this morning's weigh-in
            scale[d]?.let { z ->
                val r = SCALE_SD * SCALE_SD
                val s = pww + r
                val gw = pww / s
                val gk = pwk / s
                val y = z - w
                w += gw * y
                kk += gk * y
                val npww = (1 - gw) * pww
                val npwk = (1 - gw) * pwk
                val npkk = pkk - gk * pwk
                pww = npww; pwk = npwk; pkk = npkk
            }
            out += DayNet(d, if (logged) (e!! - kk * f).roundToInt() else null, (kk * f).roundToInt(), logged, w, scale[d])
            // predict to tomorrow: W' = W + (eaten − k f)/ρ
            // dW'/dk: only a logged day says anything about k (an unlogged day is assumed in balance)
            val a = if (logged) -f / KCAL_PER_KG else 0.0
            val intake = if (logged) e!! else kk * f
            w += (intake - kk * f) / KCAL_PER_KG
            val qw = if (logged) (LOG_SD_FRAC * intake / KCAL_PER_KG).let { it * it } else (0.35 * 0.35)
            // P' = A P Aᵀ + Q, A = [[1, a],[0, 1]]
            val npww = pww + 2 * a * pwk + a * a * pkk + qw
            val npwk = pwk + a * pkk
            val npkk = pkk + K_DRIFT_SD * K_DRIFT_SD
            pww = npww; pwk = npwk; pkk = npkk
            d = d.plusDays(1)
        }
        // today: measurement only (the day isn't over)
        scale[today]?.let { z ->
            val s = pww + SCALE_SD * SCALE_SD
            val gw = pww / s; val gk = pwk / s; val y = z - w
            w += gw * y; kk += gk * y
            pkk -= gk * pwk; pww *= (1 - gw)
        }
        kk = kk.coerceIn(0.6, 1.4)
        val kSd = sqrt(max(pkk, 0.0))
        val conf = ((1 - (kSd / 0.15)) * 100).roundToInt().coerceIn(0, 100)

        val recent = out.takeLast(14)
        val loggedRecent = recent.filter { it.logged }
        val avgNet = loggedRecent.mapNotNull { it.net }.takeIf { it.size >= 3 }?.average()?.roundToInt()
        val trendNow = if (first != null) w else null
        val trend14 = recent.firstOrNull()?.trendKg
        val slope = if (trendNow != null && trend14 != null && recent.size >= 7) (trendNow - trend14) / recent.size * 7 else null
        val note = when {
            first == null -> "Weigh in each morning (after the toilet, before food). Net calories get checked against your weight from week 2."
            scale.size < 7 || loggedRecent.size < 5 -> "Still learning: needs ~a week of weigh-ins and logged days. Until then burn = the Fitbit's number."
            conf < 50 -> "Early estimate: water and glycogen swings blur the first 2 weeks."
            else -> "Checked against ${scale.size} weigh-ins and ${out.count { it.logged }} logged days."
        }
        return Result(
            k = kk, kSd = kSd, confidence = conf,
            fitbitOffPct = ((1 / kk - 1) * 100).roundToInt(),
            avgNet = avgNet,
            scaleNet = slope?.let { (it * KCAL_PER_KG / 7).roundToInt() },
            trendKg = trendNow, trendKgPerWeek = slope,
            todayFitbitSoFar = health[today]?.kcalTotal,
            typicalFitbit = typicalFitbit,
            days = out, note = note,
        )
    }
}
