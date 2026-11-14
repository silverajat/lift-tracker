package com.silverajat.lifttracker.reps

import com.silverajat.lifttracker.ble.PodSample
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** One rep. ROM leads the score: the goal is full range on every rep. */
data class RepQuality(
    val rep: Int,
    val score: Int,        // overall 0..100: 60% ROM, 25% speed, 15% steady tempo
    val romPct: Int,       // ROM as % of the reference (calibration set = 100)
    val rom: Float,        // this rep's ROM, in the set's unit (deg or cm)
    val partial: Boolean,  // under 90% of the reference
    val speed: Int,        // peak speed vs the fastest of the first reps
    val tempoS: Float,     // seconds for the whole rep
)

/** What 100% ROM is for this set, and where it came from. */
data class RomRef(val value: Float, val unit: String, val source: Source) {
    enum class Source(val label: String) {
        CALIBRATION("your calibration set"), WARMUP("today's warm-up"),
        BEST("your best set"), SET("this set's best reps"), RECENT("your last sets")
    }
}

/** What the pod saw during one set. */
data class SetAnalysis(
    val reps: Int,
    val perRep: List<RepQuality>,
    val speedDropPct: Int, // how much slower the last reps were than the first ones
    val avgTempoS: Float,
    val unit: String,      // "deg" (forearm turns) or "cm" (wrist travels)
    val ref: RomRef,       // what the reps were compared with
    val setRom: Float,     // this set's full ROM (median of its 3 biggest reps)
    val signal: String,    // which signal was counted (turn, tilt, lift, push)
) {
    val partials: Int get() = perRep.count { it.partial }
    val avgRomPct: Int get() = if (perRep.isEmpty()) 0 else perRep.map { it.romPct }.average().roundToInt()
    val overall: Int get() = if (perRep.isEmpty()) 0 else perRep.map { it.score }.average().roundToInt()
}

fun unitLabel(unit: String) = if (unit == "cm") " cm" else "°"

/**
 * Live rep counter for the set in progress. Samples go into a buffer; the
 * count is re-worked on the whole set (RepEngine) so it never flickers
 * between two answers for the same data.
 */
class RepCounter(private val hz: Int = 25) {
    private val buf = ArrayList<FloatArray>(4096)
    @Volatile var params = CounterParams()
        private set
    @Volatile var oneArm = false
        private set
    /** One-arm lift with the pod arm done first: count the first stretch, not the last. */
    @Volatile var firstSide = false
        private set

    @Synchronized
    fun reset(params: CounterParams, oneArm: Boolean, firstSide: Boolean = false) {
        buf.clear()
        this.params = params
        this.oneArm = oneArm
        this.firstSide = firstSide
    }

    /** Adds a sample. Returns true every 5th sample (time to recount). */
    @Synchronized
    fun add(s: PodSample): Boolean {
        buf.add(floatArrayOf(s.ax, s.ay, s.az, s.gx, s.gy, s.gz))
        return buf.size % 5 == 0
    }

    @Synchronized
    fun snapshot(): List<FloatArray> = ArrayList(buf)

    /** Count the set so far (call off the main thread). */
    fun countNow(): Int = count(snapshot(), hz, params, oneArm, firstSide)

    companion object {
        /** The part of a set the pod arm did (one-arm lifts), else all of it. */
        fun side(data: List<FloatArray>, hz: Int, oneArm: Boolean, firstSide: Boolean = false): List<FloatArray> =
            if (!oneArm) data else data.slice(if (firstSide) RepEngine.firstBout(data, hz) else RepEngine.lastBout(data, hz))

        fun count(data: List<FloatArray>, hz: Int, p: CounterParams, oneArm: Boolean, firstSide: Boolean = false): Int {
            val d = side(data, hz, oneArm, firstSide)
            return RepEngine.count(d, hz, p)?.reps ?: 0
        }

        /**
         * Full analysis of a finished set. ROM % is against refFor(unit) (the
         * calibration set when there is one); if that's null, against this
         * set's own best reps.
         */
        fun analyze(
            data: List<FloatArray>, hz: Int, p: CounterParams, oneArm: Boolean,
            refFor: (String) -> RomRef?, firstSide: Boolean = false,
        ): SetAnalysis? {
            val d = side(data, hz, oneArm, firstSide)
            if (d.size < hz) return null
            val sig = RepEngine.signals(d, hz)
            val res = RepEngine.count(sig, p) ?: return null
            if (res.reps < 1) return null
            val (roms, unit) = RepEngine.rom(sig, res)

            val setRom = top3(roms)
            val ref = refFor(unit)
            val r = if (ref != null && ref.unit == unit && ref.value > 0) ref
                else RomRef(setRom.toFloat(), unit, RomRef.Source.SET)
            val refVal = max(1e-6, r.value.toDouble())

            data class Raw(val peak: Double, val tempo: Double)
            val raw = res.repWindows.map { (a, b) ->
                var peak = 0.0
                for (i in a until minOf(b, res.x.size)) peak = max(peak, abs(res.x[i]))
                Raw(peak, (b - a).toDouble() / hz)
            }
            val refPeak = max(1e-6, raw.take(3).maxOf { it.peak })
            val typTempo = max(1e-6, median(raw.map { it.tempo }))

            val per = raw.mapIndexed { i, rr ->
                val romPct = (roms[i] / refVal * 100).roundToInt().coerceIn(0, 150)
                val speed = pct(rr.peak / refPeak)
                val tempoOk = pct(1 - abs(rr.tempo - typTempo) / typTempo)
                val score = (0.6 * minOf(romPct, 100) + 0.25 * speed + 0.15 * tempoOk).roundToInt()
                RepQuality(
                    rep = i + 1, score = score, romPct = romPct, rom = roms[i].toFloat(),
                    partial = romPct < 90, speed = speed, tempoS = rr.tempo.toFloat(),
                )
            }
            val firstPeak = raw.take(2).map { it.peak }.average()
            val lastPeak = raw.takeLast(2).map { it.peak }.average()
            val drop = if (raw.size >= 3 && firstPeak > 0)
                ((1 - lastPeak / firstPeak) * 100).roundToInt().coerceIn(0, 100) else 0
            return SetAnalysis(
                reps = res.reps, perRep = per, speedDropPct = drop,
                avgTempoS = raw.map { it.tempo }.average().toFloat(),
                unit = unit, ref = r, setRom = setRom.toFloat(), signal = res.signal,
            )
        }

        /** Median of the 3 biggest values: a set's full ROM, ignoring one fluke. */
        fun top3(x: List<Double>): Double {
            if (x.isEmpty()) return 0.0
            val t = x.sortedDescending().take(3).sorted()
            return t[t.size / 2]
        }

        private fun pct(x: Double) = (x * 100).roundToInt().coerceIn(0, 100)

        private fun median(x: List<Double>): Double {
            if (x.isEmpty()) return 0.0
            val s = x.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

/**
 * Keeps a set's count when the end of the set wipes it out: re-racking,
 * walking to the phone and picking it up are big movements that can swamp
 * the reps (14 counted while lifting, then 2). The count is worked out on the
 * whole set so far; if it collapses (to 60% of its best or less), the set is
 * read up to where the best count was reached instead.
 */
class PeakHold {
    var peak = 0
        private set
    /** Samples in the set when [peak] was first reached. */
    var peakLen = 0
        private set

    fun reset() { peak = 0; peakLen = 0 }

    /** [n] reps counted on the first [len] samples; returns the count to show. */
    fun update(n: Int, len: Int): Int {
        if (n > peak) { peak = n; peakLen = len }
        return shown(n)
    }

    fun collapsed(n: Int) = peak >= 4 && n <= peak * 0.6
    fun shown(n: Int) = if (collapsed(n)) peak else n

    /** The set's data to analyse: cut back to the best count if the end wiped it out. */
    fun cut(data: List<FloatArray>, n: Int): List<FloatArray> =
        if (collapsed(n) && peakLen in 1..data.size) data.subList(0, peakLen) else data
}
