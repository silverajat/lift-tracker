package com.silverajat.lifttracker.health

import java.time.Instant
import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.roundToInt

/** One heart-rate reading. */
data class HrSample(val time: Instant, val bpm: Int)

data class SleepSummary(
    val start: Instant, val end: Instant,
    val totalMin: Int, val deepMin: Int, val remMin: Int, val lightMin: Int, val awakeMin: Int,
)

data class Workout(val start: Instant, val end: Instant, val type: String, val title: String?)

/** A day of Fitbit data from Health Connect (anything missing is null). */
data class HealthDay(
    val date: LocalDate,
    val steps: Long?,
    val kcalTotal: Double?,
    val kcalActive: Double?,
    val restingHr: Int?,
    val hrvMs: Double?,
    val spo2: Double?,
    val respRate: Double?,             // breaths/min during sleep
    val sleep: SleepSummary?,          // the sleep that ENDED this morning
    val workouts: List<Workout>,
    val load: Load?,                   // from this day's heart rate
)

/** Training load from heart rate (see Scores.load). */
data class Load(val trimp: Int, val moderateMin: Int, val vigorousMin: Int, val samples: Int)

/**
 * Our own scores. Google's Cardio Load and readiness aren't shared with
 * other apps, so these are worked out from the raw data instead, with
 * formulas you can check:
 *
 * Load: Banister TRIMP. Each minute with heart rate counts
 *   minutes x HRr x 0.64 x e^(1.92 HRr), HRr = (HR - resting) / (max - resting),
 * only while HRr >= 0.3 (above easy daily movement). Moderate = HRr 0.5-0.7,
 * vigorous = HRr >= 0.7. Not on the same scale as Google's Cardio Load.
 *
 * Recovery, strain, sleep need etc. are in Analysis.
 */
object Scores {
    const val DEFAULT_HR_MAX = 200

    fun load(samples: List<HrSample>, restingHr: Int?, hrMax: Int = DEFAULT_HR_MAX): Load? {
        if (samples.size < 10) return null
        val rest = (restingHr ?: 60).toDouble()
        val range = (hrMax - rest).coerceAtLeast(40.0)
        val s = samples.sortedBy { it.time }
        var trimp = 0.0
        var moderate = 0.0
        var vigorous = 0.0
        for (i in 0 until s.size - 1) {
            val gapMin = (s[i + 1].time.epochSecond - s[i].time.epochSecond) / 60.0
            if (gapMin <= 0 || gapMin > 5) continue          // a gap in the data: don't invent minutes
            val hr = (s[i].bpm + s[i + 1].bpm) / 2.0
            val hrr = ((hr - rest) / range).coerceIn(0.0, 1.0)
            if (hrr >= 0.3) trimp += gapMin * hrr * 0.64 * exp(1.92 * hrr)
            if (hrr >= 0.7) vigorous += gapMin else if (hrr >= 0.5) moderate += gapMin
        }
        return Load(trimp.roundToInt(), moderate.roundToInt(), vigorous.roundToInt(), s.size)
    }

    fun weekLoad(days: List<HealthDay>): Int = days.takeLast(7).sumOf { it.load?.trimp ?: 0 }
}
