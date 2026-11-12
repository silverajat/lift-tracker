package com.silverajat.lifttracker.reps

import com.silverajat.lifttracker.ble.PodSample
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One rep. ROM leads the score: the goal is full range on every rep. */
data class RepQuality(
    val rep: Int,
    val score: Int,        // overall 0..100: 60% ROM, 25% speed, 15% steady tempo
    val romPct: Int,       // ROM as % of your full ROM for this exercise
    val romDeg: Float?,    // degrees the forearm turned (rotation lifts only)
    val partial: Boolean,  // under 90% of your full ROM
    val speed: Int,        // peak speed vs the fastest of the first reps
    val tempoS: Float,     // seconds for the whole rep
)

/** What the pod saw during one set. */
data class SetAnalysis(
    val reps: Int,
    val perRep: List<RepQuality>,
    val speedDropPct: Int, // how much slower the last reps were than the first ones
    val avgTempoS: Float,
    val rotation: Boolean, // ROM measured in degrees (else relative to the best rep)
    val fullRomDeg: Float?,// the full ROM the reps were compared with
    val setRomDeg: Float?, // this set's full ROM (median of its 3 biggest reps)
) {
    val partials: Int get() = perRep.count { it.partial }
    val avgRomPct: Int get() = if (perRep.isEmpty()) 0 else perRep.map { it.romPct }.average().roundToInt()
    val overall: Int get() = if (perRep.isEmpty()) 0 else perRep.map { it.score }.average().roundToInt()
}

/**
 * Live rep counter and rep quality, v0. Both are starting guesses: you
 * confirm the count after every set, and those confirmed sets become the
 * training data for the real model (python/, then the RTL pipeline).
 *
 * Method, re-run on the whole set so the count never flickers:
 *  1. Smooth each of the 6 channels (0.2 s) and remove the slow baseline
 *     (4 s), leaving the back-and-forth of the reps.
 *  2. Pick the channel that moves most relative to its noise: gyro when the
 *     wrist rotates (curls, raises), accel when it travels (presses, rows).
 *  3. Count swings with hysteresis (h = half the signal's spread, swings
 *     closer than 0.5 s ignored). Two swings = one rep.
 *  4. ROM per rep. Rotation lifts (curls, raises, flyes, extensions): the
 *     gyro is turned into the angle the forearm sweeps, in degrees. Gyro
 *     bias is taken from the still moment after Start, the rotation axis
 *     is the set's main axis of rotation (power iteration on the gyro's
 *     covariance), and each rep is integrated on its own so drift can't
 *     build up. ROM % is against your full ROM: the best set you've logged
 *     for this exercise (median of its 3 biggest reps), or this set's.
 *     Push/pull lifts where the wrist mostly travels: ROM is relative to
 *     the set's biggest reps for now (cm comes later, needs real data).
 *  5. Speed = peak of the rep signal vs the first reps; tempo = rep length.
 */
class RepCounter(private val hz: Int = 25) {
    private val buf = ArrayList<FloatArray>(4096)
    private var cached = 0

    fun reset() {
        buf.clear()
        cached = 0
    }

    fun add(s: PodSample): Int {
        buf.add(floatArrayOf(s.ax, s.ay, s.az, s.gx, s.gy, s.gz))
        if (buf.size % 5 == 0) cached = count(buf, hz)
        return cached
    }

    val reps: Int get() = cached

    /** Full analysis of the set so far (call after Stop). */
    fun analyze(fullRomDeg: Float?): SetAnalysis? = analyze(buf, hz, fullRomDeg)

    private class Signal(val sig: FloatArray, val swings: List<Int>)

    companion object {
        // Noise scale per channel: accel in g, gyro in deg/s.
        private val NOISE = floatArrayOf(0.04f, 0.04f, 0.04f, 10f, 10f, 10f)

        private fun signal(data: List<FloatArray>, hz: Int): Signal? {
            val n = data.size
            if (n < hz) return null
            val smoothW = max(1, hz / 5)
            val baseW = 4 * hz

            var best: FloatArray? = null
            var bestScore = 0f
            for (c in 0 until 6) {
                val x = FloatArray(n) { data[it][c] }
                val sm = movingAverage(x, smoothW)
                val base = movingAverage(sm, baseW)
                val hp = FloatArray(n) { sm[it] - base[it] }
                val score = std(hp) / NOISE[c]
                if (score > bestScore) { bestScore = score; best = hp }
            }
            // Barely moving: not a set (or a very still machine; fix it by hand).
            if (best == null || bestScore < 1.5f) return null

            val h = 0.5f * std(best)
            val minGap = (0.5f * hz).toInt()
            var state = 0          // -1 low, +1 high, 0 unknown
            var lastSwing = -minGap
            val swings = ArrayList<Int>()
            for (i in best.indices) {
                val v = best[i]
                val next = when {
                    v > h -> 1
                    v < -h -> -1
                    else -> state
                }
                if (next != state) {
                    if (state != 0 && i - lastSwing >= minGap) {
                        swings.add(i)
                        lastSwing = i
                    }
                    state = next
                }
            }
            return Signal(best, swings)
        }

        fun count(data: List<FloatArray>, hz: Int): Int {
            val s = signal(data, hz) ?: return 0
            return ceil(s.swings.size / 2.0).toInt()
        }

        fun analyze(data: List<FloatArray>, hz: Int, fullRomDeg: Float?): SetAnalysis? {
            val s = signal(data, hz) ?: return null
            val reps = ceil(s.swings.size / 2.0).toInt()
            if (reps < 1) return null

            // Rep k runs from the swing before it to the swing two later.
            // The first rep starts half a swing earlier, the last one ends
            // half a swing later (or at the end of the data).
            val sw = s.swings
            val half = if (sw.size >= 2) (sw.last() - sw.first()) / (sw.size - 1) else hz
            val bounds = ArrayList<Int>()
            bounds.add(max(0, sw.first() - half))
            var k = 1
            while (k < sw.size) { bounds.add(sw[k]); k += 2 }
            if (bounds.size <= reps) bounds.add(minOf(s.sig.size, sw.last() + half))

            // Angular rate about the set's main rotation axis, bias removed.
            val w = rotationRate(data)

            data class Raw(val peak: Float, val travel: Float, val tempo: Float, val deg: Float)
            val raw = (0 until reps).mapNotNull { r ->
                val a = bounds.getOrNull(r) ?: return@mapNotNull null
                val b = bounds.getOrNull(r + 1) ?: return@mapNotNull null
                if (b <= a) return@mapNotNull null
                var peak = 0f
                var travel = 0f
                var theta = 0f
                var lo = 0f
                var hi = 0f
                for (i in a until b) {
                    val v = abs(s.sig[i])
                    if (v > peak) peak = v
                    travel += v
                    theta += w[i] / hz
                    if (theta < lo) lo = theta
                    if (theta > hi) hi = theta
                }
                Raw(peak, travel / hz, (b - a).toFloat() / hz, hi - lo)
            }
            if (raw.isEmpty()) return null

            fun top3(x: List<Float>) = median(x.sortedDescending().take(3))
            val setDeg = top3(raw.map { it.deg })
            val rotation = setDeg >= 25f        // the forearm really turns: degrees mean something
            val romOf: (Raw) -> Float = if (rotation) { r -> r.deg } else { r -> r.travel }
            val setRef = top3(raw.map(romOf)).coerceAtLeast(1e-6f)
            val ref = if (rotation && fullRomDeg != null) max(fullRomDeg, setRef) else setRef

            val refPeak = raw.take(3).maxOf { it.peak }.coerceAtLeast(1e-6f)
            val typTempo = median(raw.map { it.tempo }).coerceAtLeast(1e-6f)

            val per = raw.mapIndexed { i, r ->
                val romPct = pct(romOf(r) / ref)
                val speed = pct(r.peak / refPeak)
                val tempoOk = pct(1f - abs(r.tempo - typTempo) / typTempo)
                val score = (0.6f * romPct + 0.25f * speed + 0.15f * tempoOk).roundToInt()
                RepQuality(
                    rep = i + 1, score = score, romPct = romPct,
                    romDeg = if (rotation) r.deg else null, partial = romPct < 90,
                    speed = speed, tempoS = r.tempo,
                )
            }
            val firstPeak = raw.take(2).map { it.peak }.average()
            val lastPeak = raw.takeLast(2).map { it.peak }.average()
            val drop = if (raw.size >= 3 && firstPeak > 0)
                ((1 - lastPeak / firstPeak) * 100).roundToInt().coerceIn(0, 100) else 0

            return SetAnalysis(
                reps = reps, perRep = per, speedDropPct = drop,
                avgTempoS = raw.map { it.tempo }.average().toFloat(),
                rotation = rotation,
                fullRomDeg = if (rotation) ref else null,
                setRomDeg = if (rotation) setDeg else null,
            )
        }

        /**
         * Gyro rate (deg/s) about the set's main rotation axis. Bias comes
         * from the first 0.4 s if the wrist was still then (it usually is
         * right after pressing Start); otherwise none is removed.
         */
        private fun rotationRate(data: List<FloatArray>): FloatArray {
            val n = data.size
            val bias = FloatArray(3)
            val still = data.take(10)
            if (still.size == 10 && still.all { sqrt(it[3] * it[3] + it[4] * it[4] + it[5] * it[5]) < 15f }) {
                for (c in 0 until 3) bias[c] = still.map { it[3 + c] }.average().toFloat()
            }
            val g = Array(n) { i -> FloatArray(3) { c -> data[i][3 + c] - bias[c] } }
            // Covariance of the gyro vector; its main eigenvector is the axis.
            val cov = Array(3) { DoubleArray(3) }
            for (v in g) for (r in 0 until 3) for (c in 0 until 3) cov[r][c] += (v[r] * v[c]).toDouble()
            var u = doubleArrayOf(1.0, 1.0, 1.0)
            repeat(30) {
                val nu = DoubleArray(3) { r -> (0 until 3).sumOf { c -> cov[r][c] * u[c] } }
                val len = sqrt(nu.sumOf { it * it })
                if (len < 1e-9) return FloatArray(n)
                u = DoubleArray(3) { nu[it] / len }
            }
            return FloatArray(n) { i -> (g[i][0] * u[0] + g[i][1] * u[1] + g[i][2] * u[2]).toFloat() }
        }

        private fun pct(x: Float) = (x * 100).roundToInt().coerceIn(0, 100)

        private fun median(x: List<Float>): Float {
            if (x.isEmpty()) return 0f
            val s = x.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** Trailing moving average (only uses the past, so live = offline). */
        private fun movingAverage(x: FloatArray, w: Int): FloatArray {
            val out = FloatArray(x.size)
            var sum = 0.0
            for (i in x.indices) {
                sum += x[i]
                if (i >= w) sum -= x[i - w]
                out[i] = (sum / minOf(i + 1, w)).toFloat()
            }
            return out
        }

        private fun std(x: FloatArray): Float {
            if (x.isEmpty()) return 0f
            val m = x.average()
            var s = 0.0
            for (v in x) s += (v - m) * (v - m)
            return sqrt(s / x.size).toFloat()
        }
    }
}
