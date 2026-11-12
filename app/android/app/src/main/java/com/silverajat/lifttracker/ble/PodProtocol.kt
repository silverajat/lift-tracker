package com.silverajat.lifttracker.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The pod's Bluetooth protocol. Mirrors firmware/logger/src/ble.h:
 * keep the two in sync. Everything is little-endian.
 */
object PodProtocol {
    private fun uuid(n: Int) = UUID.fromString("4c54000$n-7c3e-4f6b-9a1e-5f2b6c0d4e11")

    val SERVICE: UUID = uuid(1)
    val CONTROL: UUID = uuid(2)
    val STATUS: UUID = uuid(3)
    val STREAM: UUID = uuid(4)
    val CCC: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val STREAM_HZ = 25

    private const val OP_START: Byte = 0x01
    private const val OP_STOP: Byte = 0x02
    private const val OP_CONFIRM: Byte = 0x03
    private const val OP_AUTO: Byte = 0x04

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    fun start(
        exercise: Int, day: Int, setNo: Int, warmup: Boolean,
        weightKg: Double, unixTime: Long, sessionId: Long,
    ): ByteArray = le(15).apply {
        put(OP_START)
        put(exercise.toByte())
        put(day.toByte())
        put(setNo.coerceIn(0, 255).toByte())
        put((if (warmup) 1 else 0).toByte())
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
) {
    /** Rough charge from voltage (LiPo, light load). */
    val batteryPct: Int
        get() = (((battMv - 3350) * 100) / (4150 - 3350)).coerceIn(0, 100)

    companion object {
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
