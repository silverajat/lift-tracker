package com.silverajat.lifttracker.reps

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rep counting v1, the same steps and numbers as python/repcount/counter.py
 * (the unit tests check the two agree on simulated sets).
 *
 * v0 counted swings of whichever raw channel moved most. On slow presses
 * and pulldowns that's an accelerometer axis, and acceleration goes through
 * two cycles per rep (speed up, slow down, both ways), so every rep counted
 * twice. v1 only counts signals that go through ONE cycle per rep:
 *  - turn: how fast the forearm turns about its main axis (deg/s)
 *  - tilt: how far the forearm is tilted (deg), from the gravity estimate
 *  - lift: how fast the wrist moves up/down (m/s)
 *  - push: how fast it moves sideways/forward (m/s)
 * The gravity estimate comes from sensor fusion: a complementary filter
 * that turns the gravity vector with the gyro and gently pulls it toward
 * the accelerometer. Taking it out of the accelerometer leaves the wrist's
 * own acceleration, which is integrated to speed.
 *
 * Counting: pick the most periodic signal that clearly moves, estimate the
 * rep period T from its autocorrelation, take out drift, then split it into
 * stretches that are clearly high or clearly low (with hysteresis). Blips
 * shorter than a fraction of T (re-racking, a twitch) are merged away.
 * Each rep is one high + one low stretch.
 */
data class CounterParams(
    val h: Double = 0.35,        // hysteresis, as a fraction of the signal's amplitude
    val gap: Double = 0.10,      // shortest stretch that counts, as a fraction of T
    val signal: String = AUTO,   // "auto" or one of SIGNALS
    val detrend: Double = 1.0,   // drift window, in multiples of T
) {
    fun changes(): Int {
        val d = CounterParams()
        return (if (h != d.h) 1 else 0) + (if (gap != d.gap) 1 else 0) +
            (if (signal != d.signal) 2 else 0) + (if (detrend != d.detrend) 1 else 0)
    }

    companion object {
        const val AUTO = "auto"
    }
}

/** A counted set: reps, which signal was used, the period, and each rep's samples. */
data class CountResult(
    val reps: Int,
    val signal: String,
    val periodSamples: Int,
    val x: DoubleArray,               // the counted signal, drift removed
    val repWindows: List<IntArray>,   // [start, end) per rep (no margins)
    val turns: Boolean,               // the forearm turns a lot (ROM in degrees)
)

object RepEngine {
    val SIGNALS = listOf("turn", "tilt", "lift", "push")
    private val FLOOR = mapOf("turn" to 6.0, "tilt" to 2.0, "lift" to 0.04, "push" to 0.04)

    /** The four signals for one set, worked out once (fusion is the slow part). */
    class Signals(val n: Int, val hz: Int, val bias: DoubleArray, private val m: Map<String, DoubleArray>) {
        operator fun get(name: String): DoubleArray = m.getValue(name)

        private val prepMemo = HashMap<Long, DoubleArray>()
        private val ampMemo = HashMap<Long, Double>()
        private val choiceMemo = HashMap<String, Choice?>()
        private var turnsMemo: Boolean? = null

        fun prepared(name: String, w: Double): DoubleArray {
            val wi = max(3, w.toInt())
            val key = SIGNALS.indexOf(name).toLong() * 1_000_000L + wi
            return prepMemo.getOrPut(key) { detrend(trailingMa(this[name], max(1, hz / 5)), wi) }
        }

        fun amplitude(name: String, w: Double): Double {
            val wi = max(3, w.toInt())
            val key = SIGNALS.indexOf(name).toLong() * 1_000_000L + wi
            return ampMemo.getOrPut(key) { amplitude(prepared(name, w)) }
        }

        /** The forearm turns a lot: speeds from acceleration are unreliable then. */
        fun turns(): Boolean = turnsMemo ?: (amplitude("tilt", 4.0 * hz) >= 15.0 ||
            amplitude("turn", 4.0 * hz) >= 40.0).also { turnsMemo = it }

        fun choice(signal: String): Choice? = choiceMemo.getOrPut(signal) { choose(this, signal) }
    }

    data class Choice(val score: Double, val name: String, val period: Int)

    // ---- signals -----------------------------------------------------------

    fun signals(data: List<FloatArray>, hz: Int): Signals {
        val n = data.size
        val bias = gyroBias(data, hz)
        val gb = Array(n) { DoubleArray(3) }
        val vert = DoubleArray(n)
        val horiz = Array(n) { DoubleArray(3) }
        if (n > 0) {
            val dt = 1.0 / hz
            val alpha = dt / 2.0
            val a0 = data[0]
            val n0 = max(1e-6, norm(a0[0].toDouble(), a0[1].toDouble(), a0[2].toDouble()))
            var gx = a0[0] / n0
            var gy = a0[1] / n0
            var gz = a0[2] / n0
            val d2r = PI / 180.0
            for (i in 0 until n) {
                val s = data[i]
                val ax = s[0].toDouble()
                val ay = s[1].toDouble()
                val az = s[2].toDouble()
                val wx = (s[3] - bias[0]) * d2r
                val wy = (s[4] - bias[1]) * d2r
                val wz = (s[5] - bias[2]) * d2r
                // gravity is fixed in the world: in the pod's frame it turns by -w
                val cx = wy * gz - wz * gy
                val cy = wz * gx - wx * gz
                val cz = wx * gy - wy * gx
                gx -= cx * dt; gy -= cy * dt; gz -= cz * dt
                val an = norm(ax, ay, az)
                if (abs(an - 1.0) < 0.35) {      // trust the accelerometer only near 1 g
                    gx = (1 - alpha) * gx + alpha * ax / an
                    gy = (1 - alpha) * gy + alpha * ay / an
                    gz = (1 - alpha) * gz + alpha * az / an
                }
                val gn = norm(gx, gy, gz)
                gx /= gn; gy /= gn; gz /= gn
                gb[i][0] = gx; gb[i][1] = gy; gb[i][2] = gz
                val lx = (ax - gx) * 9.81
                val ly = (ay - gy) * 9.81
                val lz = (az - gz) * 9.81
                val v = lx * gx + ly * gy + lz * gz
                vert[i] = v
                horiz[i][0] = lx - v * gx; horiz[i][1] = ly - v * gy; horiz[i][2] = lz - v * gz
            }
        }
        val gyro = Array(n) { i -> DoubleArray(3) { c -> data[i][3 + c] - bias[c] } }
        val turnAxis = mainAxis(gyro)
        val turn = DoubleArray(n) { i -> dot(gyro[i], turnAxis) }

        val gMean = DoubleArray(3) { c -> if (n == 0) 0.0 else gb.sumOf { it[c] } / n }
        val gc = Array(n) { i -> DoubleArray(3) { c -> gb[i][c] - gMean[c] } }
        val tiltAxis = mainAxis(gb)
        val tilt = DoubleArray(n) { i -> asin(dot(gc[i], tiltAxis).coerceIn(-1.0, 1.0)) * 180.0 / PI }

        val lift = leakyInt(vert, hz)
        val hAxis = mainAxis(horiz)
        val push = leakyInt(DoubleArray(n) { i -> dot(horiz[i], hAxis) }, hz)
        return Signals(n, hz, bias, mapOf("turn" to turn, "tilt" to tilt, "lift" to lift, "push" to push))
    }

    /** Mean gyro over the stillest second, if that second really is still. */
    fun gyroBias(data: List<FloatArray>, hz: Int): DoubleArray {
        val n = data.size
        val w = hz
        if (n < w) return DoubleArray(3)
        val mag = DoubleArray(n) { i -> norm(data[i][3].toDouble(), data[i][4].toDouble(), data[i][5].toDouble()) }
        var best = 1e9
        var bi = -1
        var i = 0
        val step = max(1, w / 5)
        while (i <= n - w) {
            var m = 0.0
            for (k in i until i + w) m += mag[k]
            m /= w
            var v = 0.0
            for (k in i until i + w) v += (mag[k] - m) * (mag[k] - m)
            val s = sqrt(v / w)
            if (s < best) { best = s; bi = i }
            i += step
        }
        if (best > 4.0) return DoubleArray(3)
        return DoubleArray(3) { c -> (bi until bi + w).sumOf { data[it][3 + c].toDouble() } / w }
    }

    // ---- choosing the signal and its period ----------------------------------

    fun choose(sig: Signals, signal: String): Choice? {
        val hz = sig.hz
        val turns = sig.turns()
        var best: Choice? = null
        for (name in SIGNALS) {
            if (signal != CounterParams.AUTO && name != signal) continue
            if (signal == CounterParams.AUTO && turns && (name == "lift" || name == "push")) continue
            val x = sig.prepared(name, 4.0 * hz)
            val amp = sig.amplitude(name, 4.0 * hz)
            val snr = amp / FLOOR.getValue(name)
            if (snr < 2.0) continue
            val (t, per) = period(x, hz)
            val score = per * min(1.0, snr / 4)
            if (best == null || score > best.score) best = Choice(score, name, t)
        }
        return best
    }

    /** Rep period (samples) and how periodic the signal is, from the
     *  autocorrelation's first clear peak between 0.8 s and 9 s. */
    fun period(x0: DoubleArray, hz: Int): Pair<Int, Double> {
        val n = x0.size
        val mean = x0.average()
        val x = DoubleArray(n) { x0[it] - mean }
        val lo = (0.8 * hz).toInt()
        val hi = min(9 * hz, n - hz)
        if (hi <= lo + 2) return Pair(3 * hz, 0.0)
        var e = 0.0
        for (v in x) e += v * v
        if (e <= 0) return Pair(3 * hz, 0.0)
        val r = DoubleArray(hi - lo + 1) { k ->
            val l = lo + k
            var s = 0.0
            for (i in 0 until n - l) s += x[i] * x[i + l]
            s / e * n / (n - l)
        }
        val peak = r.max()
        for (k in 1 until r.size - 1) {
            if (r[k] >= r[k - 1] && r[k] >= r[k + 1] && r[k] > 0.25 && r[k] >= 0.6 * peak) {
                return Pair(lo + k, r[k])
            }
        }
        return Pair(3 * hz, 0.0)
    }

    // ---- counting ----------------------------------------------------------

    fun count(data: List<FloatArray>, hz: Int, p: CounterParams = CounterParams()): CountResult? =
        if (data.size < hz) null else count(signals(data, hz), p)

    fun count(sig: Signals, p: CounterParams): CountResult? {
        if (sig.n < sig.hz) return null
        val c = sig.choice(p.signal) ?: return null
        val t = c.period
        // Speeds sit at 0 when still: only slow drift goes. Tilt (a position)
        // has its mean taken out over each rep.
        val w = (if (c.name == "tilt") 1.0 else 2.0) * p.detrend * t
        val x = sig.prepared(c.name, w)
        val amp = sig.amplitude(c.name, w)
        val rs = cleanRuns(runs(x, p.h * amp), p.gap * t)
        val reps = if (rs.isEmpty()) 0 else ceil((rs.size - 1) / 2.0).toInt()
        val windows = (0 until reps).map { k ->
            val a = rs[2 * k][1]
            val b = if (2 * k + 2 < rs.size) rs[2 * k + 2][1] else x.size
            intArrayOf(a, b)
        }
        return CountResult(reps, c.name, t, x, windows, sig.turns())
    }

    /** Stretches clearly high (+1) or low (-1), with hysteresis.
     *  Each is [sign, start, end, samples actually beyond the threshold]. */
    fun runs(x: DoubleArray, h: Double): MutableList<IntArray> {
        val out = ArrayList<IntArray>()
        var state = 0
        for (i in x.indices) {
            val v = x[i]
            val nxt = if (v > h) 1 else if (v < -h) -1 else state
            if (nxt != state) {
                if (out.isNotEmpty()) out.last()[2] = i
                if (nxt != 0) out.add(intArrayOf(nxt, i, x.size, 0))
                state = nxt
            }
            if (out.isNotEmpty() && state * v > h) out.last()[3]++
        }
        return out
    }

    /** Drop blips: too little time beyond the threshold, or (not the last)
     *  too short overall. An interior blip joins its two neighbours. */
    fun cleanRuns(rs0: List<IntArray>, minLen: Double): List<IntArray> {
        val rs = rs0.map { it.copyOf() }.toMutableList()
        while (true) {
            val last = rs.size - 1
            var k = -1
            for (i in rs.indices) {
                val r = rs[i]
                if (r[3] < minLen || (i < last && r[2] - r[1] < 2 * minLen)) { k = i; break }
            }
            if (k < 0) return rs
            if (k == 0 || k == rs.size - 1) {
                rs.removeAt(k)
            } else {
                val a = rs[k - 1]
                val b = rs[k + 1]
                val merged = intArrayOf(a[0], a[1], b[2], a[3] + b[3])
                rs.removeAt(k + 1); rs.removeAt(k); rs[k - 1] = merged
            }
        }
    }

    /**
     * One-arm lifts (left arm first, pod on the right): the last clear
     * stretch of movement long enough to be a set (>= 3 s), with 0.5 s
     * either side. The whole set if there isn't one.
     */
    fun lastBout(data: List<FloatArray>, hz: Int): IntRange = bout(data, hz, last = true)

    /** The first clear stretch of movement: one-arm lifts done pod-arm first (woodchoppers). */
    fun firstBout(data: List<FloatArray>, hz: Int): IntRange = bout(data, hz, last = false)

    private fun bout(data: List<FloatArray>, hz: Int, last: Boolean): IntRange {
        val n = data.size
        if (n < hz) return 0 until n
        val bias = gyroBias(data, hz)
        val act0 = DoubleArray(n) { i ->
            val s = data[i]
            val g = norm(s[3] - bias[0], s[4] - bias[1], s[5] - bias[2])
            val a = abs(norm(s[0].toDouble(), s[1].toDouble(), s[2].toDouble()) - 1.0) * 300
            max(g, a)
        }
        val act = centredMa(act0, hz)
        val thr = max(12.0, 0.25 * percentile(act, 95.0))
        val segs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (act[i] > thr) {
                var j = i
                while (j < n && act[j] > thr) j++
                segs.add(intArrayOf(i, j))
                i = j
            } else i++
        }
        val merged = ArrayList<IntArray>()
        for (s in segs) {
            if (merged.isNotEmpty() && s[0] - merged.last()[1] < 1.5 * hz) merged.last()[1] = s[1]
            else merged.add(s.copyOf())
        }
        val long = merged.filter { it[1] - it[0] >= 3 * hz }
        if (long.isEmpty()) return 0 until n
        val (a, b) = if (last) long.last() else long.first()
        return max(0, a - hz / 2) until min(n, b + hz / 2)
    }

    // ---- ROM per rep ---------------------------------------------------------

    /**
     * Range of motion of each rep. If the forearm turns a lot (curls,
     * raises, flyes, extensions): degrees it turns, from the gyro integrated
     * over the rep. Otherwise (presses, rows, pulldowns): centimetres the
     * wrist travels, from the speed integrated over the rep. Each rep is
     * integrated on its own, so drift can't build up. Returns (values, unit).
     */
    fun rom(sig: Signals, res: CountResult): Pair<List<Double>, String> {
        val hz = sig.hz
        val margin = (0.15 * res.periodSamples).toInt()
        val (src, scale, unit) = if (res.turns) Triple(sig["turn"], 1.0, "deg") else {
            val l = sig.amplitude("lift", 4.0 * hz)
            val p = sig.amplitude("push", 4.0 * hz)
            Triple(if (l >= p) sig["lift"] else sig["push"], 100.0, "cm")
        }
        val vals = res.repWindows.map { (a0, b0) ->
            val a = max(0, a0 - margin)
            val b = min(src.size, b0 + margin)
            var pos = 0.0
            var lo = 0.0
            var hi = 0.0
            for (i in a until b) {
                pos += src[i] / hz
                if (pos < lo) lo = pos
                if (pos > hi) hi = pos
            }
            (hi - lo) * scale
        }
        return Pair(vals, unit)
    }

    // ---- helpers (match numpy) -----------------------------------------------------

    fun trailingMa(x: DoubleArray, w: Int): DoubleArray {
        val c = DoubleArray(x.size + 1)
        for (i in x.indices) c[i + 1] = c[i] + x[i]
        return DoubleArray(x.size) { i ->
            val lo = max(0, i + 1 - w)
            (c[i + 1] - c[lo]) / (i + 1 - lo)
        }
    }

    fun centredMa(x: DoubleArray, w: Int): DoubleArray {
        val n = x.size
        val h = w / 2
        val c = DoubleArray(n + 1)
        for (i in x.indices) c[i + 1] = c[i] + x[i]
        return DoubleArray(n) { i ->
            val lo = max(0, i - h)
            val hi = min(n, i + h + 1)
            (c[hi] - c[lo]) / (hi - lo)
        }
    }

    fun detrend(x: DoubleArray, w: Int): DoubleArray {
        val m = centredMa(x, max(3, w))
        return DoubleArray(x.size) { x[it] - m[it] }
    }

    /** numpy.percentile, linear interpolation. */
    fun percentile(x: DoubleArray, q: Double): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sortedArray()
        val pos = q / 100.0 * (s.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(s.size - 1, lo + 1)
        val f = pos - lo
        return s[lo] + (s[hi] - s[lo]) * f
    }

    fun amplitude(x: DoubleArray): Double = (percentile(x, 95.0) - percentile(x, 5.0)) / 2

    private fun leakyInt(x: DoubleArray, hz: Int, tau: Double = 1.5): DoubleArray {
        val lam = exp(-1.0 / (hz * tau))
        var s = 0.0
        return DoubleArray(x.size) { i -> s = lam * s + x[i] / hz; s }
    }

    /** Unit vector along the biggest variance (power iteration). */
    private fun mainAxis(v: Array<DoubleArray>): DoubleArray {
        val n = v.size
        if (n == 0) return doubleArrayOf(1.0, 0.0, 0.0)
        val mean = DoubleArray(3) { c -> v.sumOf { it[c] } / n }
        val cov = Array(3) { DoubleArray(3) }
        for (row in v) {
            for (r in 0 until 3) for (c in 0 until 3) cov[r][c] += (row[r] - mean[r]) * (row[c] - mean[c])
        }
        var u = doubleArrayOf(1.0, 1.0, 1.0)
        repeat(30) {
            val nu = DoubleArray(3) { r -> cov[r][0] * u[0] + cov[r][1] * u[1] + cov[r][2] * u[2] }
            val ln = sqrt(nu[0] * nu[0] + nu[1] * nu[1] + nu[2] * nu[2])
            if (ln < 1e-12) return doubleArrayOf(1.0, 0.0, 0.0)
            u = DoubleArray(3) { nu[it] / ln }
        }
        return u
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)
}
