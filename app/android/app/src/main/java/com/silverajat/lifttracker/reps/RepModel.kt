package com.silverajat.lifttracker.reps

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * The rep counter's learned settings, per exercise. Same method as
 * python/repcount/learn.py.
 *
 * Training data: every saved set's 25 Hz data with the rep count you
 * confirmed. After each set, for each exercise, every combination on the
 * grid below is tried on your past sets of that exercise, and the one that
 * miscounts the fewest reps wins (ties go to the defaults, then to fewer
 * changes). It only moves off the defaults to fix a clear, repeated
 * mistake (at least 3 reps, and 0.3 of a rep per set): with a handful of
 * sets, chasing single miscounts would just learn the noise. Exercises
 * with fewer than 3 sets use the settings fitted on all your sets.
 */
object RepModel {
    private val H = listOf(0.25, 0.35, 0.45, 0.55)
    private val GAP = listOf(0.06, 0.10, 0.15, 0.20)
    private val SIG = listOf(CounterParams.AUTO) + RepEngine.SIGNALS
    private val DET = listOf(0.75, 1.0, 1.5)
    const val MIN_SETS = 3

    /** A set to learn from: its data (one-arm sets already cut to the right arm) and true reps. */
    class Example(val exerciseId: Int, val signals: RepEngine.Signals, val reps: Int)

    data class Fit(
        val params: CounterParams,
        val sets: Int,          // corrected sets it was fitted on
        val exactDefault: Int,  // sets the default counter got exactly right
        val exactLearned: Int,  // sets the learned counter gets exactly right
        val missDefault: Int,   // reps the default miscounted in total
        val missLearned: Int,
    )

    data class Model(val global: Fit?, val perExercise: Map<Int, Fit>) {
        fun params(exerciseId: Int): CounterParams =
            perExercise[exerciseId]?.takeIf { it.sets >= MIN_SETS }?.params
                ?: global?.params ?: CounterParams()

        fun toJson(): String = JSONObject().apply {
            global?.let { put("global", fitJson(it)) }
            put("ex", JSONObject().apply { perExercise.forEach { (k, v) -> put(k.toString(), fitJson(v)) } })
        }.toString()

        companion object {
            val EMPTY = Model(null, emptyMap())

            fun fromJson(s: String?): Model {
                if (s.isNullOrEmpty()) return EMPTY
                return runCatching {
                    val o = JSONObject(s)
                    val ex = o.optJSONObject("ex")
                    Model(
                        o.optJSONObject("global")?.let(::fitFrom),
                        ex?.keys()?.asSequence()?.associate { it.toInt() to fitFrom(ex.getJSONObject(it)) } ?: emptyMap(),
                    )
                }.getOrDefault(EMPTY)
            }

            private fun fitJson(f: Fit) = JSONObject().apply {
                put("h", f.params.h); put("gap", f.params.gap); put("signal", f.params.signal)
                put("detrend", f.params.detrend); put("sets", f.sets)
                put("exactDefault", f.exactDefault); put("exactLearned", f.exactLearned)
                put("missDefault", f.missDefault); put("missLearned", f.missLearned)
            }

            private fun fitFrom(o: JSONObject) = Fit(
                CounterParams(o.getDouble("h"), o.getDouble("gap"), o.getString("signal"), o.getDouble("detrend")),
                o.getInt("sets"), o.getInt("exactDefault"), o.getInt("exactLearned"),
                o.getInt("missDefault"), o.getInt("missLearned"),
            )
        }
    }

    fun grid(): List<CounterParams> = buildList {
        for (h in H) for (g in GAP) for (s in SIG) for (d in DET) add(CounterParams(h, g, s, d))
    }

    private fun errors(p: CounterParams, sets: List<Example>): IntArray =
        IntArray(sets.size) { i -> abs((RepEngine.count(sets[i].signals, p)?.reps ?: 0) - sets[i].reps) }

    fun fit(sets: List<Example>): Fit? {
        if (sets.isEmpty()) return null
        val def = CounterParams()
        val base = errors(def, sets)
        var best = def
        var bestErr = base.sum()
        var bestChanges = 0
        for (p in grid()) {
            val e = errors(p, sets).sum()
            val ch = p.changes()
            if (e < bestErr || (e == bestErr && ch < bestChanges)) {
                best = p; bestErr = e; bestChanges = ch
            }
        }
        val accept = base.sum() - bestErr >= max(3.0, 0.3 * sets.size)
        val chosen = if (accept) best else def
        val learned = if (accept) errors(chosen, sets) else base
        return Fit(
            params = chosen, sets = sets.size,
            exactDefault = base.count { it == 0 }, exactLearned = learned.count { it == 0 },
            missDefault = base.sum(), missLearned = learned.sum(),
        )
    }

    /** Fit everything: all sets together, then each exercise on its own. */
    fun train(examples: List<Example>): Model {
        val global = fit(examples)
        val per = examples.groupBy { it.exerciseId }.mapValues { (_, v) -> fit(v)!! }
        return Model(global, per)
    }
}
