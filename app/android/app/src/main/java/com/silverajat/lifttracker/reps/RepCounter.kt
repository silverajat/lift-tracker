package com.silverajat.lifttracker.reps

import com.silverajat.lifttracker.ble.PodSample
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Rough live rep counter (v0). It is only a starting guess: you confirm or
 * fix the number after every set, and those confirmed counts become the
 * labels for the real model later (python/, then the RTL pipeline).
 *
 * Method, re-run on the whole set every few samples so the count never
 * jumps around because of a late decision:
 *  1. Smooth each of the 6 channels (0.2 s moving average) and remove the
 *     slow baseline (4 s), leaving the back-and-forth of the reps.
 *  2. Pick the channel that moves the most relative to its noise level:
 *     gyro for curls/raises (the wrist rotates), accel for presses/rows.
 *  3. Count swings with hysteresis: each pass from below -h to above +h,
 *     or back, is half a rep (h = half the signal's spread). Swings closer
 *     than 0.5 s are ignored.
 *
 * Parameters were picked on the desk-test curls only (Nov 11), so expect
 * it to be off by a rep or two on real lifts. Tomorrow's confirmed counts
 * are the data to tune it properly.
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

    companion object {
        // Noise scale per channel: accel in g, gyro in deg/s.
        private val NOISE = floatArrayOf(0.04f, 0.04f, 0.04f, 10f, 10f, 10f)

        fun count(data: List<FloatArray>, hz: Int): Int {
            val n = data.size
            if (n < hz) return 0
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
            if (best == null || bestScore < 1.5f) return 0

            val sig = best
            val h = 0.5f * std(sig)
            val minGap = (0.5f * hz).toInt()
            var state = 0          // -1 low, +1 high, 0 unknown
            var swings = 0
            var lastSwing = -minGap
            for (i in sig.indices) {
                val v = sig[i]
                val next = when {
                    v > h -> 1
                    v < -h -> -1
                    else -> state
                }
                if (next != state) {
                    if (state != 0 && i - lastSwing >= minGap) {
                        swings++
                        lastSwing = i
                    }
                    state = next
                }
            }
            return ceil(swings / 2.0).toInt()
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
