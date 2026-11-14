package com.silverajat.lifttracker.activity

import com.silverajat.lifttracker.health.HrSample
import com.silverajat.lifttracker.health.Scores
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What a game or run looked like from the Fitbit's heart rate: the curve,
 * time in each zone, hard bursts, how the effort changed, how fast the heart
 * rate came down afterwards, and how it compares with your usual.
 */
object SportAnalysis {

    /** Zones as % of max heart rate (Fitbit-style 5 zones). */
    val ZONES = listOf("Warm-up", "Easy", "Aerobic", "Threshold", "Max")
    val ZONE_FLOOR = listOf(0.5, 0.6, 0.7, 0.8, 0.9)

    data class Result(
        val start: Instant,
        val end: Instant,
        val samples: List<HrSample>,      // inside the activity, oldest first
        val avg: Int,
        val max: Int,
        val min: Int,
        val peakAt: Instant,
        val hrMax: Int,
        val zoneMin: List<Int>,           // minutes in each of ZONES
        val bursts: Int,                  // hard efforts above 85% of max, >= 20 s each
        val longestHardS: Int,            // longest unbroken stretch above 80% of max
        val blockMin: Int,                // length of each block below
        val blocks: List<Int?>,           // average heart rate per block (null: no data)
        val firstHalf: Int?,
        val secondHalf: Int?,
        val recovery: Int?,               // bpm drop in the first minute after the last hard effort
        val steps: Long?,
        val kcal: Int?,
        val usualStrain: Double?,         // average strain of your recent games of this sport
        val usualAvgHr: Int?,
        val usualCount: Int,
        val notes: List<String>,
    )

    fun zoneOf(bpm: Int, hrMax: Int): Int {
        val p = bpm.toDouble() / hrMax
        return ZONE_FLOOR.indexOfLast { p >= it }      // -1 below 50%
    }

    /**
     * [all] may run past both ends (the minute after the end is used for recovery).
     * [past] are your earlier activities of the same sport, newest first or any order.
     */
    fun analyze(
        sport: Sport, start: Instant, end: Instant, all: List<HrSample>, restingHr: Int?, hrMax: Int,
        steps: Long? = null, kcal: Double? = null, strain: Double? = null, past: List<ActivityItem> = emptyList(),
    ): Result? {
        val sorted = all.sortedBy { it.time }
        val s = sorted.filter { it.time >= start && it.time <= end }
        if (s.size < 10) return null
        val avg = s.map { it.bpm }.average().roundToInt()
        val peak = s.maxBy { it.bpm }
        val zones = Scores.load(s, restingHr, hrMax)?.zones ?: List(5) { 0 }

        val bursts = runs(s, hrMax * 0.85, hrMax * 0.80).count { it >= 20 }
        val longest = runs(s, hrMax * 0.80, hrMax * 0.80).maxOrNull() ?: 0

        val totalMin = ((end.epochSecond - start.epochSecond) / 60).toInt().coerceAtLeast(1)
        val blockMin = if (totalMin <= 40) 5 else 10
        val nBlocks = (totalMin + blockMin - 1) / blockMin
        val blocks = (0 until nBlocks).map { b ->
            val a = start.plusSeconds(b * blockMin * 60L); val z = a.plusSeconds(blockMin * 60L)
            s.filter { it.time >= a && it.time < z }.takeIf { it.size >= 3 }?.map { it.bpm }?.average()?.roundToInt()
        }
        val mid = start.plusSeconds((end.epochSecond - start.epochSecond) / 2)
        val h1 = s.filter { it.time < mid }.takeIf { it.size >= 5 }?.map { it.bpm }?.average()?.roundToInt()
        val h2 = s.filter { it.time >= mid }.takeIf { it.size >= 5 }?.map { it.bpm }?.average()?.roundToInt()

        val recovery = recovery(sorted, start, end, avg)

        val same = past.filter { it.sport == sport && it.start < start }.sortedByDescending { it.start }.take(10)
        val usualStrain = same.mapNotNull { it.strain }.takeIf { it.size >= 2 }?.average()
        val usualHr = same.mapNotNull { it.avgHr }.takeIf { it.size >= 2 }?.average()?.roundToInt()

        val r = Result(start, end, s, avg, peak.bpm, s.minOf { it.bpm }, peak.time, hrMax, zones, bursts, longest, blockMin, blocks,
            h1, h2, recovery, steps, kcal?.roundToInt(), usualStrain, usualHr, same.size, emptyList())
        return r.copy(notes = notes(sport, r, strain))
    }

    /** Lengths (s) of unbroken stretches at or above [enter], ending when it drops below [stay]. Gaps over 60 s break a stretch. */
    private fun runs(s: List<HrSample>, enter: Double, stay: Double): List<Int> {
        val out = ArrayList<Int>()
        var from: Instant? = null; var last: Instant? = null
        for (x in s) {
            val gap = last?.let { x.time.epochSecond - it.epochSecond } ?: 0L
            if (from != null && (x.bpm < stay || gap > 60)) { out += (last!!.epochSecond - from.epochSecond).toInt(); from = null }
            if (from == null && x.bpm >= enter) from = x.time
            last = x.time
        }
        if (from != null && last != null) out += (last.epochSecond - from.epochSecond).toInt()
        return out
    }

    /**
     * Heart-rate recovery: from the last moment you were working (at or above your
     * average, in the last 10 min of the activity or just after) to one minute later.
     */
    private fun recovery(s: List<HrSample>, start: Instant, end: Instant, avg: Int): Int? {
        val window = s.filter { it.time >= end.minusSeconds(600) && it.time <= end.plusSeconds(180) && it.time >= start }
        val stop = window.lastOrNull { it.bpm >= avg } ?: return null
        val after = s.filter { it.time.epochSecond - stop.time.epochSecond in 45L..90L }
            .minByOrNull { abs(it.time.epochSecond - stop.time.epochSecond - 60) } ?: return null
        val drop = stop.bpm - after.bpm
        return drop.takeIf { it >= 0 }
    }

    private fun notes(sport: Sport, r: Result, strain: Double?): List<String> {
        val out = ArrayList<String>()
        val total = r.zoneMin.sum()
        if (total > 0) {
            val top = r.zoneMin.indices.maxBy { r.zoneMin[it] }
            val hard = r.zoneMin[3] + r.zoneMin[4]
            out += "Mostly ${ZONES[top].lowercase()} (${r.zoneMin[top]} of $total min)" +
                (if (hard >= 5) ", with $hard min above 80% of your max." else ".")
        }
        if (sport in setOf(Sport.BADMINTON, Sport.FOOTBALL, Sport.OTHER)) {
            out += when {
                r.bursts >= 8 -> "${r.bursts} hard bursts above 85%: a lot of fast, explosive play."
                r.bursts >= 3 -> "${r.bursts} hard bursts above 85%, with recovery between them."
                r.bursts > 0 -> "Only ${r.bursts} hard burst${if (r.bursts == 1) "" else "s"} above 85%: mostly steady play."
                else -> "No bursts above 85%: a steady, controlled session."
            }
        } else if (r.longestHardS >= 300) out += "Longest stretch above 80%: ${r.longestHardS / 60} min."
        if (r.firstHalf != null && r.secondHalf != null) {
            val d = r.secondHalf - r.firstHalf
            out += when {
                d <= -6 -> "You eased off in the second half (${r.firstHalf} → ${r.secondHalf} bpm)."
                d >= 6 -> "You got more intense as it went on (${r.firstHalf} → ${r.secondHalf} bpm)."
                else -> "Even effort from start to finish (${r.firstHalf} → ${r.secondHalf} bpm)."
            }
        }
        r.recovery?.let { d ->
            out += "Heart rate dropped $d bpm in the minute after you stopped" + when {
                d >= 25 -> ": very good recovery."
                d >= 15 -> ": good recovery."
                d >= 12 -> ": fair recovery."
                else -> ". Low: you may be tired or not fully recovered."
            }
        }
        if (strain != null && r.usualStrain != null) {
            val pct = (strain - r.usualStrain) / r.usualStrain
            val name = sport.label.lowercase()
            out += when {
                pct >= 0.15 -> "Harder than your usual $name (strain %.1f vs %.1f).".format(strain, r.usualStrain)
                pct <= -0.15 -> "Lighter than your usual $name (strain %.1f vs %.1f).".format(strain, r.usualStrain)
                else -> "About your usual $name (strain %.1f vs %.1f).".format(strain, r.usualStrain)
            }
        }
        return out
    }
}
