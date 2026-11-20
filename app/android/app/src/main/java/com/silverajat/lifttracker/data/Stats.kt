package com.silverajat.lifttracker.data

/**
 * Progress numbers for the exercise page, Hevy-style. Add-on plates count as
 * 1 kg each everywhere (see Routines.ADDON_KG). Warm-up and calibration sets
 * are left out of every chart and record.
 */
enum class Metric(val label: String, val unit: String) {
    ONE_RM("Strength (est. 1RM)", "kg"), // est. 1-rep max: goes up with more weight OR more reps
    HEAVIEST("Heaviest weight", "kg"),
    TOP_REPS("Reps at top weight", "reps"),
    BEST_SET("Best set volume", "kg"),
    VOLUME("Session volume", "kg"),
    REPS("Total reps", "reps"),
    ROM("ROM", "% of calibration"),
}

/** [note]: the set behind the point ("8.3×11"), shown under the chart. */
data class Point(val label: String, val timeMs: Long?, val value: Double, val note: String? = null)

/** A session's best set by est. 1RM. */
data class TopSet(val timeMs: Long?, val load: Double, val reps: Int) {
    val e1rm get() = Stats.oneRm(load, reps)
    val text get() = "${Stats.fmt(load)}×$reps"
}

object Stats {
    /** Epley: weight x (1 + reps / 30). */
    fun oneRm(weight: Double, reps: Int) = if (reps <= 1) weight else weight * (1 + reps / 30.0)

    /** Null when there are no working sets (warm-ups only). */
    fun value(m: Metric, sets: List<PlanSet>): Double? {
        val work = sets.filter { !it.warmup }
        if (work.isEmpty()) return null
        return when (m) {
            Metric.HEAVIEST -> work.maxOf { it.load }
            Metric.TOP_REPS -> work.maxOf { it.load }.let { top -> work.filter { it.load >= top - 1e-9 }.maxOf { it.reps }.toDouble() }
            Metric.ONE_RM -> work.maxOf { oneRm(it.load, it.reps) }
            Metric.BEST_SET -> work.maxOf { it.load * it.reps }
            Metric.VOLUME -> work.sumOf { it.load * it.reps }
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
        value(m, ex.last)?.let { pts += Point("before", null, it, note(m, ex.last)) }
        sessions.forEach { s ->
            val sets = s.sets.filter { it.exerciseId == ex.id }
                .map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
            value(m, sets)?.let { pts += Point("", s.startMs, it, note(m, sets)) }
        }
        return pts
    }

    /** The set behind a session's point: the strongest set, or the top-weight set for weight/reps charts. */
    private fun note(m: Metric, sets: List<PlanSet>): String? {
        val work = sets.filter { !it.warmup }.takeIf { it.isNotEmpty() } ?: return null
        val s = when (m) {
            Metric.HEAVIEST, Metric.TOP_REPS -> work.maxOf { it.load }.let { top -> work.filter { it.load >= top - 1e-9 }.maxBy { it.reps } }
            Metric.VOLUME, Metric.REPS -> return "${work.size} sets"
            else -> work.maxBy { oneRm(it.load, it.reps) }
        }
        return "${fmt(s.load)}×${s.reps}"
    }

    /** Each session's strongest set (est. 1RM), oldest first; "before the app" first if there is one. */
    fun topSets(ex: Exercise, sessions: List<Session>): List<TopSet> {
        fun top(sets: List<PlanSet>, t: Long?) = sets.filter { !it.warmup }.maxByOrNull { oneRm(it.load, it.reps) }?.let { TopSet(t, it.load, it.reps) }
        return listOfNotNull(top(ex.last, null)) + sessions.mapNotNull { s ->
            top(s.sets.filter { it.exerciseId == ex.id }.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }, s.startMs)
        }
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
        val heaviest = work.maxBy { it.load }
        val best1rm = work.maxBy { oneRm(it.load, it.reps) }
        val bestSet = work.maxBy { it.load * it.reps }
        val mostReps = work.maxBy { it.reps }
        return listOf(
            Record("Heaviest weight", "%s kg".format(fmt(heaviest.load))),
            Record("Best est. 1RM", "%s kg".format(fmt(oneRm(best1rm.load, best1rm.reps)))),
            Record("Best set", "%s kg × %d".format(fmt(bestSet.load), bestSet.reps)),
            Record("Most reps", "%d × %s kg".format(mostReps.reps, fmt(mostReps.load))),
        )
    }

    fun fmt(v: Double): String =
        if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)
}
