package com.silverajat.lifttracker.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The pod's Bluetooth protocol. Mirrors firmware/logger/src/ble.h (XIAO prototype, protocol 1)
 * and firmware/pod/src/ble.h (pod v2, protocol 2): keep them in sync. Everything is little-endian.
 */
object PodProtocol {
    private fun uuid(n: Int) = UUID.fromString("4c54000$n-7c3e-4f6b-9a1e-5f2b6c0d4e11")

    val SERVICE: UUID = uuid(1)
    val CONTROL: UUID = uuid(2)
    val STATUS: UUID = uuid(3)
    val STREAM: UUID = uuid(4)
    /** v2: recordings sent after [read]. */
    val DATA: UUID = uuid(5)
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val STREAM_HZ = 25

    private const val OP_START: Byte = 0x01
    private const val OP_STOP: Byte = 0x02
    private const val OP_CONFIRM: Byte = 0x03
    private const val OP_AUTO: Byte = 0x04
    private const val OP_GYM: Byte = 0x05
    private const val OP_BUZZ: Byte = 0x06
    private const val OP_READ: Byte = 0x07
    private const val OP_ERASE: Byte = 0x08

    /** Data packet kinds (v2). */
    const val DATA_HEADER = 0
    const val DATA_SAMPLES = 1
    const val DATA_END = 2
    const val DATA_ERROR = 3

    /** "from" for a header-only read: what the recording is, without its samples. */
    const val HEADER_ONLY = 0xFFFFFFFFL

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun start(
        exercise: Int, day: Int, setNo: Int, warmup: Boolean, calibration: Boolean,
        weightKg: Double, unixTime: Long, sessionId: Long,
    ): ByteArray = le(15).apply {
        put(OP_START)
        put(exercise.toByte())
        put(day.toByte())
        put(setNo.coerceIn(0, 255).toByte())
        put(((if (warmup) 1 else 0) or (if (calibration) 4 else 0)).toByte())
        putShort((weightKg * 100).roundToInt().coerceIn(0, 65535).toShort())
        putInt(unixTime.toInt())
        putInt(sessionId.toInt())
    }.array()

    fun stop(liveReps: Int?): ByteArray =
        byteArrayOf(OP_STOP, (liveReps?.coerceIn(0, 254) ?: 255).toByte())

    fun confirm(recId: Int, reps: Int): ByteArray = le(4).apply {
        put(OP_CONFIRM)
        putShort(recId.toShort())
        put(reps.coerceIn(0, 254).toByte())
    }.array()

    fun auto(on: Boolean): ByteArray = byteArrayOf(OP_AUTO, (if (on) 1 else 0).toByte())

    /** v2: full-rate sensor and fast link for a workout, or back to sleep. */
    fun gym(on: Boolean): ByteArray = byteArrayOf(OP_GYM, (if (on) 1 else 0).toByte())

    /** v2: buzz [count] times (1 = rest over, 2 = set starting). */
    fun buzz(count: Int): ByteArray = byteArrayOf(OP_BUZZ, count.coerceIn(1, 5).toByte())

    /** v2: send recording [recId] on DATA, from sample [from]. */
    fun read(recId: Int, from: Long = 0): ByteArray = le(7).apply {
        put(OP_READ); putShort(recId.toShort()); putInt(from.toInt())
    }.array()

    /** v2: erase every recording on the pod (only once the phone has them all). */
    fun erase(): ByteArray = byteArrayOf(OP_ERASE, 0xE5.toByte())
}

/** What a recording on the pod is (v2 DATA header): the set label the app sent with Start. */
data class PodRecInfo(
    val recId: Int, val samples: Long, val pretrigger: Long, val startUptimeS: Long, val sampleHz: Int,
    val unixTime: Long, val sessionId: Long, val weightKg: Double?, val exercise: Int?, val day: Int?,
    val setNo: Int?, val warmup: Boolean, val calibration: Boolean, val repsLive: Int?, val repsConfirmed: Int?,
) {
    /** The file it's saved as on the phone: one name per set, whatever its id on the pod. */
    val key: String get() = if (exercise != null) "s${sessionId}_t${unixTime}" else "u${startUptimeS}_r$recId"
}

/** One DATA notification (v2). */
class PodDataPacket(val recId: Int, val kind: Int, val n: Int, val index: Long, val payload: ByteArray) {
    fun info(): PodRecInfo? {
        if (kind != PodProtocol.DATA_HEADER || payload.size < 38) return null
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val id = b.int; val n = b.int.toLong() and 0xffffffffL; val pre = b.int.toLong() and 0xffffffffL
        val up = b.int.toLong() and 0xffffffffL; val hz = b.short.toInt() and 0xffff
        val unix = b.int.toLong() and 0xffffffffL; val sess = b.int.toLong() and 0xffffffffL
        val wcg = b.short.toInt() and 0xffff
        val ex = b.get().toInt() and 0xff; val day = b.get().toInt() and 0xff; val set = b.get().toInt() and 0xff
        val flags = b.get().toInt() and 0xff
        b.short
        val live = b.get().toInt() and 0xff; val conf = b.get().toInt() and 0xff
        val labelled = ex != 0xff
        return PodRecInfo(id, n, pre, up, hz, unix, sess, if (labelled) wcg / 100.0 else null,
            ex.takeIf { labelled }, day.takeIf { labelled }, set.takeIf { labelled },
            labelled && flags and 1 != 0, labelled && flags and 4 != 0,
            live.takeIf { it != 0xff }, conf.takeIf { it != 0xff })
    }

    /** Samples as (t_ms, ax, ay, az in milli-g, gx, gy, gz in 0.1 deg/s), like the laptop dumps. */
    fun samples(): List<ShortArray> {
        if (kind != PodProtocol.DATA_SAMPLES) return emptyList()
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return (0 until n).mapNotNull { if (b.remaining() < 14) null else ShortArray(7) { b.short } }
    }

    companion object {
        fun parse(v: ByteArray): PodDataPacket? {
            if (v.size < 8) return null
            val b = ByteBuffer.wrap(v).order(ByteOrder.LITTLE_ENDIAN)
            val rec = b.short.toInt() and 0xffff
            val kind = b.get().toInt() and 0xff
            val n = b.get().toInt() and 0xff
            val idx = b.int.toLong() and 0xffffffffL
            return PodDataPacket(rec, kind, n, idx, v.copyOfRange(8, v.size))
        }
    }
}

data class PodStatus(
    val proto: Int,
    val recording: Boolean,
    val usb: Boolean,
    val charging: Boolean,
    val fault: Boolean,
    val imuOk: Boolean,
    val autoOn: Boolean,
    val appRecording: Boolean,
    val battMv: Int,
    val recordings: Int,
    val lastRec: Int?,
    val recSamples: Long,
    val flashPct: Int,
    val firmware: Int,
    /** v2: 2 = pod v2 board (0 on the XIAO prototype). */
    val hardware: Int = 0,
    /** v2: sending a recording or erasing. */
    val busy: Boolean = false,
    /** v2: gym mode (full-rate sensor). */
    val gym: Boolean = false,
    /** v2: double taps on the pod, counting up (wraps at 256); a new one ends the set. Null on older firmware. */
    val taps: Int? = null,
    /** v2.1: seconds since the pod last started, why it did (0 power-up, 1 pin, 2 watchdog, 3 software, 4 lockup),
     *  and how many times it has restarted since it was powered up. Null on older firmware. */
    val uptimeS: Long? = null,
    val resetWhy: Int? = null,
    val restarts: Int? = null,
) {
    /** "watchdog" etc., for the restart line in Settings. */
    val resetLabel: String?
        get() = when (resetWhy) {
            null -> null; 0 -> "power-up"; 1 -> "reset pin"; 2 -> "watchdog"; 3 -> "software"; 4 -> "lockup"; else -> "other"
        }

    /** Talks protocol 2: gym mode, buzz, recording download. */
    val v2: Boolean get() = proto >= 2

    /** A real battery reading: under ~2.5 V means no battery on the pad or the reading failed (the pod sends 0 then). */
    val batteryKnown: Boolean get() = battMv >= 2500

    /** Charge from voltage along a LiPo discharge curve (a straight line reads too low in the middle). */
    val batteryPct: Int
        get() {
            if (!batteryKnown) return 0
            val i = CURVE.indexOfFirst { battMv >= it.first }
            if (i == 0) return 100
            if (i < 0) return 0
            val (v1, p1) = CURVE[i - 1]
            val (v0, p0) = CURVE[i]
            return (p0 + (p1 - p0) * (battMv - v0) / (v1 - v0)).coerceIn(0, 100)
        }

    /** What the app shows: "62%", "4% (3.42 V)" when it's nearly flat, or why there's no number. */
    val batteryLabel: String
        get() = when {
            !batteryKnown && usb -> "on USB, no battery reading"
            !batteryKnown -> "battery not reading"
            batteryPct <= 10 -> "$batteryPct%% (%.2f V)".format(battMv / 1000.0)
            else -> "$batteryPct%"
        }

    companion object {
        /** (mV, %) for a small LiPo under light load. */
        private val CURVE = listOf(4150 to 100, 4050 to 90, 3950 to 78, 3870 to 65, 3800 to 52, 3750 to 40,
            3700 to 28, 3650 to 18, 3600 to 10, 3500 to 4, 3400 to 1, 3300 to 0)

        fun parse(b: ByteArray): PodStatus? {
            if (b.size < 16) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val proto = bb.get().toInt() and 0xff
            val f = bb.get().toInt() and 0xff
            val mv = bb.short.toInt() and 0xffff
            val n = bb.short.toInt() and 0xffff
            val last = bb.short.toInt() and 0xffff
            val samples = bb.int.toLong() and 0xffffffffL
            val flash = bb.get().toInt() and 0xff
            val fw = bb.get().toInt() and 0xff
            val hw = if (bb.hasRemaining()) bb.get().toInt() and 0xff else 0
            val busy = if (bb.hasRemaining()) bb.get().toInt() and 0xff else 0
            val taps = if (bb.hasRemaining()) bb.get().toInt() and 0xff else null
            val up = if (bb.remaining() >= 6) bb.int.toLong() and 0xffffffffL else null
            val why = if (up != null) bb.get().toInt() and 0xff else null
            val restarts = if (up != null) bb.get().toInt() and 0xff else null
            return PodStatus(
                proto = proto,
                recording = f and 0x01 != 0,
                usb = f and 0x02 != 0,
                charging = f and 0x04 != 0,
                fault = f and 0x08 != 0,
                imuOk = f and 0x10 != 0,
                autoOn = f and 0x20 != 0,
                appRecording = f and 0x40 != 0,
                battMv = mv,
                recordings = n,
                lastRec = if (last == 0xffff) null else last,
                recSamples = samples,
                flashPct = flash,
                firmware = fw,
                hardware = hw,
                busy = proto >= 2 && busy != 0,
                gym = proto >= 2 && f and 0x80 != 0,
                uptimeS = up,
                resetWhy = why,
                restarts = restarts,
                taps = taps.takeIf { proto >= 2 },
            )
        }
    }
}

/** One live IMU sample: accel in g, gyro in deg/s. */
data class PodSample(val seq: Int, val ax: Float, val ay: Float, val az: Float,
                     val gx: Float, val gy: Float, val gz: Float) {
    companion object {
        fun parse(b: ByteArray): PodSample? {
            if (b.size < 14) return null
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            val seq = bb.short.toInt() and 0xffff
            val a = FloatArray(3) { bb.short / 1000f }
            val g = FloatArray(3) { bb.short / 10f }
            return PodSample(seq, a[0], a[1], a[2], g[0], g[1], g[2])
        }
    }
}
