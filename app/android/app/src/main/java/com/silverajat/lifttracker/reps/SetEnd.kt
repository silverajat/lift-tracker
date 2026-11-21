package com.silverajat.lifttracker.reps

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * When is the set over? Not a timer: a pause between reps can be long (breathing at lockout, a grinder).
 * What changes at the end is the motion itself (reference: python/repcount/set_end.py):
 *
 *  - RACK: the counter's trimEnd drops a "rep" at the end that's far bigger than the set's reps (or bigger
 *    and quicker): putting the weight back. The set is over once that's 0.6 s past.
 *  - POSE: the wrist ends up somewhere it never was during the set (hands off the bar, arms dropping,
 *    the wrist turning over for the phone) and stays there 0.6 s. The set's own range of wrist angles comes
 *    from its reps, so a lift that swings the wrist a lot (lateral raise, curl) needs a far bigger change
 *    than one that holds it still (bench).
 *
 * Both need 3+ reps. [End.cut] is where the change began: count the set up to there.
 */
object SetEnd {
    enum class Why { RACK, POSE }
    data class End(val cut: Int, val why: Why)

    const val CONE_MIN = 12.0     // deg: the set's own range of wrist angles is at least this
    const val CONE_PAD = 30.0     // deg beyond that range ...
    const val HOLD_S = 0.6        // ... held this long
    const val RACK_AFTER_S = 0.6

    fun detect(data: List<FloatArray>, hz: Int, p: CounterParams = CounterParams()): End? {
        val n = data.size
        if (n < 4 * hz) return null
        val sig = RepEngine.signals(data, hz)
        val res = RepEngine.count(sig, p) ?: return null
        val ws = res.repWindows
        if (ws.size < 3) return null
        val lastEnd = ws.last()[1]
        if (res.allWindows.size > ws.size && lastEnd < n) {
            val droppedEnd = res.allWindows.last()[1]
            if (droppedEnd < n && n - droppedEnd >= RACK_AFTER_S * hz) return End(lastEnd, Why.RACK)
        }
        // the range of wrist angles from every rep but the last (the change itself can look like a rep)
        val g = sig.gravity
        if (g.size != n) return null
        val a0 = ws.first()[0]
        val b0 = ws[ws.size - 2][1]
        if (b0 - a0 < hz) return null
        val m = DoubleArray(3)
        for (i in a0 until b0) for (c in 0..2) m[c] += g[i][c]
        val mn = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        if (mn < 1e-9) return null
        for (c in 0..2) m[c] /= mn
        val ang = DoubleArray(n) { i ->
            Math.toDegrees(acos((g[i][0] * m[0] + g[i][1] * m[1] + g[i][2] * m[2]).coerceIn(-1.0, 1.0)))
        }
        val cone = max(CONE_MIN, RepEngine.percentile(ang.copyOfRange(a0, b0), 95.0))
        val hold = (HOLD_S * hz).toInt()
        var run = 0
        for (i in b0 until n) {
            run = if (ang[i] > cone + CONE_PAD) run + 1 else 0
            if (run >= hold) {
                var start = i - run + 1
                while (start > b0 && ang[start - 1] > cone) start--
                val before = RepEngine.count(data.subList(0, start), hz, p)?.reps ?: 0
                return if (before >= 3) End(start, Why.POSE) else null
            }
        }
        return null
    }

    /** Replay a saved set as it streamed in (every [step] samples): where it would have ended, or null. */
    fun replay(data: List<FloatArray>, hz: Int, p: CounterParams = CounterParams(), step: Int = 5): End? {
        var k = 4 * hz
        while (k <= data.size) {
            detect(data.subList(0, k), hz, p)?.let { return it }
            k += step
        }
        return null
    }
}
