package com.silverajat.lifttracker.data

import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * How many reps a kilo is worth, per lift.
 *
 * Est. 1RM = weight × (1 + reps / k). Epley uses k = 30 for everything, but the trade-off between weight and reps
 * differs by exercise: at 80% of max people manage ~9 reps on the bench press but ~13 on the leg press
 * (Nuzzo et al. 2023, meta-regression of 269 studies), and a fixed k fits light/isolation lifts worst (2026
 * analysis of 303k logged sets). So each lift gets its own k:
 *
 *   start (prior):  upper-body compound 34 (bench ~9 reps at 80%), lower-body compound 50 (leg press ~13 at 80%),
 *                   isolation 38 (between; studies disagree most here)
 *   then learned from YOUR sessions: whenever the weight changed from one session to the next, how many reps
 *   you gained or lost says what a kilo is worth on that lift. k is the value that keeps est. 1RM steadiest
 *   across those changes, pulled toward the prior (each change counts about as much as a third of the prior).
 *
 * The step that matters is relative: +1 kg on an 8 kg raise is +12% (several reps), on a 62 kg pec dec +1.6%.
 */
object Strength {
    enum class Kind(val prior: Double) { UPPER_COMPOUND(34.0), LOWER_COMPOUND(50.0), ISOLATION(38.0) }

    fun kind(exerciseId: Int) = when (exerciseId) {
        1, 2, 3, 4, 8 -> Kind.UPPER_COMPOUND          // incline bench, rows, overhead press, pulldown
        21, 22 -> Kind.LOWER_COMPOUND                 // leg press, RDL
        else -> Kind.ISOLATION
    }

    const val K_MIN = 18.0
    const val K_MAX = 90.0
    private const val NOISE = 0.025   // session-to-session wobble in est. 1RM (log scale)
    private const val PRIOR_SD = 0.30 // how far a lift's k can plausibly sit from its prior (log scale)

    fun oneRm(load: Double, reps: Int, k: Double) = if (reps <= 1) load else load * (1 + reps / k)

    /** Reps you'd expect at [load] with an est. 1RM of [e1]. */
    fun repsAt(e1: Double, load: Double, k: Double) = (k * (e1 / load - 1)).coerceAtLeast(0.0)

    /** The lift's k from its sessions' strongest sets (oldest first) and its prior. */
    fun fit(prior: Double, tops: List<Pair<Double, Int>>): Double {
        val pairs = tops.zipWithNext().filter { (a, b) -> a.first > 0 && b.first > 0 && kotlin.math.abs(b.first / a.first - 1) >= 0.01 && a.second > 1 && b.second > 1 }
        if (pairs.isEmpty()) return prior
        var best = prior; var bestCost = Double.MAX_VALUE
        var k = K_MIN
        while (k <= K_MAX) {
            val fitCost = pairs.sumOf { (a, b) -> val d = ln(oneRm(b.first, b.second, k) / oneRm(a.first, a.second, k)) / NOISE; d * d }
            val p = ln(k / prior) / PRIOR_SD
            val cost = fitCost + p * p
            if (cost < bestCost) { bestCost = cost; best = k }
            k += 0.5
        }
        return best
    }

    /** k for one exercise, learned from its sessions. */
    fun k(ex: Exercise, sessions: List<Session>): Double {
        val tops = (listOf(ex.last) + sessions.sortedBy { it.startMs }.map { s ->
            s.sets.filter { it.exerciseId == ex.id }.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
        }).mapNotNull { sets -> sets.filter { !it.warmup }.maxByOrNull { oneRm(it.load, it.reps, kind(ex.id).prior) }?.let { it.load to it.reps } }
        return fit(kind(ex.id).prior, tops)
    }

    /** k for an exercise by id (Epley's 30 if it isn't in the routines). */
    fun kFor(exerciseId: Int, sessions: List<Session>): Double =
        runCatching { Routines.byId(exerciseId) }.getOrNull()?.let { k(it, sessions) } ?: 30.0

    /** Reps the next weight step costs from this set (e.g. 8.3×11 → 9.3: about 5 fewer). */
    fun stepCost(load: Double, reps: Int, step: Double, k: Double): Int =
        (reps - repsAt(oneRm(load, reps, k), load + step, k)).roundToInt()
}
