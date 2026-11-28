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
 *
 * Warm-ups get their own fit as well: they're lighter and quicker, often with a
 * shorter range, so settings tuned on working sets can be a rep out on them. Once
 * there are 3 corrected warm-ups (of one exercise, or of all of them together) they
 * use those settings; until then, the normal ones.
 */
object RepModel {
    private val H = listOf(0.25, 0.35, 0.45, 0.55)
    private val GAP = listOf(0.06, 0.10, 0.15, 0.20)
    private val SIG = listOf(CounterParams.AUTO) + RepEngine.SIGNALS
    private val DET = listOf(0.75, 1.0, 1.5)
    const val MIN_SETS = 3
    /** Pod v2 sits differently on the wrist, so its sets are fitted on their own once an exercise has this many. */
    const val MIN_NEW_POD = 2
    /** And the all-exercise fit once there are this many pod v2 sets in total. */
    const val MIN_NEW_POD_GLOBAL = 10

    /** A set to learn from: its data (one-arm sets already cut to the right arm) and true reps. */
    class Example(val exerciseId: Int, val signals: RepEngine.Signals, val reps: Int, val warmup: Boolean = false,
                  val newPod: Boolean = false)

    data class Fit(
        val params: CounterParams,
        val sets: Int,          // corrected sets it was fitted on
        val exactDefault: Int,  // sets the default counter got exactly right
        val exactLearned: Int,  // sets the learned counter gets exactly right
        val missDefault: Int,   // reps the default miscounted in total
        val missLearned: Int,
        val newPod: Boolean = false,  // fitted on pod v2 sets only
    ) {
        fun usable() = sets >= MIN_SETS || (newPod && sets >= MIN_NEW_POD)
    }

    data class Model(
        val global: Fit?,
        val perExercise: Map<Int, Fit>,
        val warmGlobal: Fit? = null,
        val warmPerExercise: Map<Int, Fit> = emptyMap(),
    ) {
        fun params(exerciseId: Int, warmup: Boolean = false): CounterParams =
            (if (warmup) warmPerExercise[exerciseId]?.takeIf { it.usable() }?.params
                ?: warmGlobal?.takeIf { it.usable() }?.params else null)
                ?: perExercise[exerciseId]?.takeIf { it.usable() }?.params
                ?: global?.params ?: CounterParams()

        fun toJson(): String = JSONObject().apply {
            global?.let { put("global", fitJson(it)) }
            put("ex", JSONObject().apply { perExercise.forEach { (k, v) -> put(k.toString(), fitJson(v)) } })
            warmGlobal?.let { put("warm", fitJson(it)) }
            put("warmEx", JSONObject().apply { warmPerExercise.forEach { (k, v) -> put(k.toString(), fitJson(v)) } })
        }.toString()

        companion object {
            val EMPTY = Model(null, emptyMap())

            fun fromJson(s: String?): Model {
                if (s.isNullOrEmpty()) return EMPTY
                return runCatching {
                    val o = JSONObject(s)
                    val ex = o.optJSONObject("ex")
                    val wex = o.optJSONObject("warmEx")
                    Model(
                        o.optJSONObject("global")?.let(::fitFrom),
                        ex?.keys()?.asSequence()?.associate { it.toInt() to fitFrom(ex.getJSONObject(it)) } ?: emptyMap(),
                        o.optJSONObject("warm")?.let(::fitFrom),
                        wex?.keys()?.asSequence()?.associate { it.toInt() to fitFrom(wex.getJSONObject(it)) } ?: emptyMap(),
                    )
                }.getOrDefault(EMPTY)
            }

            private fun fitJson(f: Fit) = JSONObject().apply {
                put("h", f.params.h); put("gap", f.params.gap); put("signal", f.params.signal)
                put("detrend", f.params.detrend); put("sets", f.sets)
                put("exactDefault", f.exactDefault); put("exactLearned", f.exactLearned)
                put("missDefault", f.missDefault); put("missLearned", f.missLearned)
                put("newPod", if (f.newPod) 1 else 0)
            }

            private fun fitFrom(o: JSONObject) = Fit(
                CounterParams(o.getDouble("h"), o.getDouble("gap"), o.getString("signal"), o.getDouble("detrend")),
                o.getInt("sets"), o.getInt("exactDefault"), o.getInt("exactLearned"),
                o.getInt("missDefault"), o.getInt("missLearned"),
                newPod = runCatching { o.getInt("newPod") == 1 }.getOrDefault(false),
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

    /**
     * Fit everything: all sets together, then each exercise on its own; and the warm-ups the same way, on their own.
     * Pod v2 reads a rep a bit differently (where it sits, its case, its sensor), so wherever there are enough pod v2
     * sets they're fitted on their own and the old pod's sets are left out; until then everything counts together.
     */
    fun train(examples: List<Example>): Model {
        fun pick(xs: List<Example>, min: Int): Fit? {
            val fresh = xs.filter { it.newPod }
            return if (fresh.size >= min) fit(fresh)?.copy(newPod = true) else fit(xs)
        }
        val global = pick(examples, MIN_NEW_POD_GLOBAL)
        val per = examples.groupBy { it.exerciseId }.mapValues { (_, v) -> pick(v, MIN_NEW_POD)!! }
        val warm = examples.filter { it.warmup }
        return Model(global, per, pick(warm, MIN_NEW_POD_GLOBAL), warm.groupBy { it.exerciseId }.mapValues { (_, v) -> pick(v, MIN_NEW_POD)!! })
    }
}
