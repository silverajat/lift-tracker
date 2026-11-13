package com.silverajat.lifttracker.data

/**
 * Progress numbers for the exercise page, Hevy-style. Add-on plates aren't
 * weighed, so they don't count toward weight or volume. Warm-up and
 * calibration sets are left out of every chart and record.
 */
enum class Metric(val label: String, val unit: String) {
    HEAVIEST("Heaviest weight", "kg"),
    ONE_RM("Est. 1-rep max", "kg"),
    BEST_SET("Best set volume", "kg"),
    VOLUME("Session volume", "kg"),
    REPS("Total reps", "reps"),
    ROM("ROM", "% of calibration"),
}

data class Point(val label: String, val timeMs: Long?, val value: Double)

object Stats {
    /** Epley: weight x (1 + reps / 30). */
    fun oneRm(weight: Double, reps: Int) = if (reps <= 1) weight else weight * (1 + reps / 30.0)

    /** Null when there are no working sets (warm-ups only). */
    fun value(m: Metric, sets: List<PlanSet>): Double? {
        val work = sets.filter { !it.warmup }
        if (work.isEmpty()) return null
        return when (m) {
            Metric.HEAVIEST -> work.maxOf { it.weight }
            Metric.ONE_RM -> work.maxOf { oneRm(it.weight, it.reps) }
            Metric.BEST_SET -> work.maxOf { it.weight * it.reps }
            Metric.VOLUME -> work.sumOf { it.weight * it.reps }
            Metric.REPS -> work.sumOf { it.reps }.toDouble()
            Metric.ROM -> null   // from the pod's per-rep data: see romSeries
        }
    }

    /**
     * One point per session. The routine's starting numbers (what you did
     * before the app) come first, labelled "before".
     */
    fun series(m: Metric, ex: Exercise, sessions: List<Session>): List<Point> {
        val pts = mutableListOf<Point>()
        value(m, ex.last)?.let { pts += Point("before", null, it) }
        sessions.forEach { s ->
            val sets = s.sets.filter { it.exerciseId == ex.id }
                .map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
            value(m, sets)?.let { pts += Point("", s.startMs, it) }
        }
        return pts
    }

    /** ROM per session: average ROM % of the working sets (calibration set = 100). */
    fun romSeries(ex: Exercise, sessions: List<Session>, minCounter: Int): List<Point> = sessions.mapNotNull { s ->
        s.sets.filter { it.exerciseId == ex.id && !it.warmup && it.counter >= minCounter && it.romPct.isNotEmpty() }
            .flatMap { it.romPct }.takeIf { it.isNotEmpty() }
            ?.let { Point("", s.startMs, it.average()) }
    }

    data class Record(val label: String, val value: String)

    fun records(ex: Exercise, sessions: List<Session>): List<Record> {
        val all = ex.last + sessions.flatMap { s ->
            s.sets.filter { it.exerciseId == ex.id }.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
        }
        val work = all.filter { !it.warmup }
        if (work.isEmpty()) return emptyList()
        val heaviest = work.maxBy { it.weight }
        val best1rm = work.maxBy { oneRm(it.weight, it.reps) }
        val bestSet = work.maxBy { it.weight * it.reps }
        val mostReps = work.maxBy { it.reps }
        return listOf(
            Record("Heaviest weight", "%s kg".format(fmt(heaviest.weight))),
            Record("Best est. 1RM", "%s kg".format(fmt(oneRm(best1rm.weight, best1rm.reps)))),
            Record("Best set", "%s kg × %d".format(fmt(bestSet.weight), bestSet.reps)),
            Record("Most reps", "%d × %s kg".format(mostReps.reps, fmt(mostReps.weight))),
        )
    }

    fun fmt(v: Double): String =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)
}
