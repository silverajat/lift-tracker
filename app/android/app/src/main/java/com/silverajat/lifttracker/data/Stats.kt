package com.silverajat.lifttracker.data

/**
 * Progress numbers for the exercise page, Hevy-style. Add-on plates aren't
 * weighed, so they don't count toward weight or volume.
 */
enum class Metric(val label: String, val unit: String) {
    HEAVIEST("Heaviest weight", "kg"),
    ONE_RM("Est. 1-rep max", "kg"),
    BEST_SET("Best set volume", "kg"),
    VOLUME("Session volume", "kg"),
    REPS("Total reps", "reps"),
}

data class Point(val label: String, val timeMs: Long?, val value: Double)

object Stats {
    /** Epley: weight x (1 + reps / 30). */
    fun oneRm(weight: Double, reps: Int) = if (reps <= 1) weight else weight * (1 + reps / 30.0)

    fun value(m: Metric, sets: List<PlanSet>): Double {
        val work = sets.filter { !it.warmup }.ifEmpty { sets }
        return when (m) {
            Metric.HEAVIEST -> work.maxOfOrNull { it.weight } ?: 0.0
            Metric.ONE_RM -> work.maxOfOrNull { oneRm(it.weight, it.reps) } ?: 0.0
            Metric.BEST_SET -> work.maxOfOrNull { it.weight * it.reps } ?: 0.0
            Metric.VOLUME -> sets.sumOf { it.weight * it.reps }
            Metric.REPS -> sets.sumOf { it.reps }.toDouble()
        }
    }

    /**
     * One point per session. The routine's starting numbers (what you did
     * before the app) come first, labelled "before".
     */
    fun series(m: Metric, ex: Exercise, sessions: List<Session>): List<Point> {
        val pts = mutableListOf(Point("before", null, value(m, ex.last)))
        sessions.forEach { s ->
            val sets = s.sets.filter { it.exerciseId == ex.id }
                .map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
            if (sets.isNotEmpty()) pts += Point("", s.startMs, value(m, sets))
        }
        return pts
    }

    data class Record(val label: String, val value: String)

    fun records(ex: Exercise, sessions: List<Session>): List<Record> {
        val all = ex.last + sessions.flatMap { s ->
            s.sets.filter { it.exerciseId == ex.id }.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
        }
        val work = all.filter { !it.warmup }.ifEmpty { all }
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
