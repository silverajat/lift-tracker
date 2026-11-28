package com.silverajat.lifttracker.ble

import com.silverajat.lifttracker.data.PodRecStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Pod v2 protocol (firmware/pod/src/ble.h) and the phone's copy of its recordings. */
class PodV2Test {
    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    private fun status(proto: Int, flags: Int, size: Int) = le(size).apply {
        put(proto.toByte()); put(flags.toByte()); putShort(3900.toShort()); putShort(4.toShort()); putShort(3.toShort()); putInt(0)
        put(42.toByte()); put(3.toByte()); if (size > 14) put(2.toByte()); if (size > 15) put(1.toByte())
    }.array()

    @Test
    fun statusV2AndPrototype() {
        val v2 = PodStatus.parse(status(2, 0x80 or 0x10, 16))!!
        assertTrue(v2.v2); assertTrue(v2.gym); assertTrue(v2.busy); assertEquals(2, v2.hardware)
        assertEquals(4, v2.recordings); assertEquals(42, v2.flashPct)
        // the prototype: protocol 1, bit 7 isn't gym, reserved bytes are ignored
        val p = PodStatus.parse(status(1, 0x80 or 0x10, 16))!!
        assertFalse(p.v2); assertFalse(p.gym); assertFalse(p.busy)
        assertNull(p.taps); assertNull(v2.taps)
        val tapped = PodStatus.parse(status(2, 0x80, 16) + byteArrayOf(5))!!
        assertEquals(5, tapped.taps)
        assertNull(tapped.uptimeS)
        // v2.1: uptime, why it last started and the restart count on the end
        val diag = PodStatus.parse(status(2, 0x80, 16) + byteArrayOf(5) + le(6).apply { putInt(3725); put(2); put(4) }.array())!!
        assertEquals(3725L, diag.uptimeS); assertEquals("watchdog", diag.resetLabel); assertEquals(4, diag.restarts)
        assertNull(diag.faultLabel)
        // v2.2: what ended the run before (a crash in an unnamed thread, Zephyr reason 2, after 12 s)
        val fault = PodStatus.parse(status(2, 0x80, 16) + byteArrayOf(5) + le(6).apply { putInt(40); put(3); put(1) }.array() +
            le(11).apply { put(1); put(9); put(3); put(2); put(8); putShort(12); putInt(0x0002d1a0) }.array())!!
        assertEquals("crash t9 p8 s3 r2 at 0x0002d1a0 after 12 s", fault.faultLabel)
        assertEquals(1, fault.restarts)
    }

    @Test
    fun commands() {
        assertEquals(listOf<Byte>(5, 1), PodProtocol.gym(true).toList())
        assertEquals(listOf<Byte>(6, 2), PodProtocol.buzz(2).toList())
        val r = ByteBuffer.wrap(PodProtocol.read(7, 300)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7, r.get().toInt()); assertEquals(7, r.short.toInt()); assertEquals(300, r.int)
        assertEquals(-1, ByteBuffer.wrap(PodProtocol.read(0, PodProtocol.HEADER_ONLY), 3, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(listOf<Byte>(8, 0xE5.toByte()), PodProtocol.erase().toList())
    }

    private fun header(labelled: Boolean): ByteArray = le(8 + 38).apply {
        putShort(3.toShort()); put(0.toByte()); put(0.toByte()); putInt(0)
        putInt(3); putInt(250); putInt(50); putInt(1234); putShort(100.toShort())
        if (labelled) {
            putInt(1_795_000_000); putInt(1_794_990_000); putShort(625.toShort()); put(11.toByte()); put(0.toByte()); put(2.toByte()); put((1 or 2).toByte())
        } else {
            repeat(16) { put(0xff.toByte()) }
        }
        if (labelled) { putShort(0.toShort()); put(12.toByte()); put(11.toByte()); putShort(0.toShort()) } else repeat(4) { put(0xff.toByte()) }
    }.array()

    @Test
    fun headerAndSamples() {
        val h = PodDataPacket.parse(header(true))!!
        val i = h.info()!!
        assertEquals(250L, i.samples); assertEquals(100, i.sampleHz); assertEquals(11, i.exercise)
        assertEquals(6.25, i.weightKg!!, 1e-9); assertTrue(i.warmup); assertFalse(i.calibration)
        assertEquals(12, i.repsLive); assertEquals(11, i.repsConfirmed)
        val u = PodDataPacket.parse(header(false))!!.info()!!
        assertNull(u.exercise); assertNull(u.weightKg); assertNull(u.repsLive)

        val pk = le(8 + 2 * 14).apply {
            putShort(3.toShort()); put(1.toByte()); put(2.toByte()); putInt(10)
            repeat(2) { k -> putShort((65000 + k).toShort()); putShort((-12).toShort()); putShort(5.toShort()); putShort(1000.toShort()); putShort(1.toShort()); putShort((-2).toShort()); putShort(3.toShort()) }
        }.array()
        val p = PodDataPacket.parse(pk)!!
        assertEquals(10L, p.index)
        val s = p.samples()
        assertEquals(2, s.size); assertEquals(1000, s[0][3].toInt()); assertEquals(65001, s[1][0].toInt() and 0xffff)
    }

    @Test
    fun storeWritesDumpLogsFormatOnce() {
        val dir = Files.createTempDirectory("podrec").toFile()
        val st = PodRecStore(dir)
        val info = PodDataPacket.parse(header(true))!!.info()!!
        val samples = List(250) { k -> shortArrayOf(k.toShort(), 0, 0, 1000, 0, 0, 0) }
        st.save(info, samples)
        st.save(info, samples)                       // same set again: not saved twice
        assertEquals(1, st.count())
        assertTrue(st.has(info.key))
        val idx = java.io.File(st.dir, "recordings.csv").readLines()
        assertEquals(PodRecStore.INDEX_HEADER, idx[0])
        val row = idx[1].split(",")
        val cols = PodRecStore.INDEX_HEADER.split(",")
        assertEquals(cols.size, row.size)
        val m = cols.zip(row).toMap()
        assertEquals("2.50", m["seconds"]); assertEquals("11", m["exercise"]); assertEquals("6.25", m["weight_kg"])
        assertEquals("1", m["warmup"]); assertEquals("11", m["reps_confirmed"]); assertEquals("3", m["pod_rec"])
        val rec = java.io.File(st.dir, "rec_000.csv").readLines()
        assertEquals(PodRecStore.SAMPLE_HEADER, rec[0]); assertEquals(251, rec.size)
        assertEquals("1,1,0,0,1000,0,0,0", rec[2])
        // unlabelled recordings: blank label columns, same column count
        val u = PodDataPacket.parse(header(false))!!.info()!!
        st.save(u, samples.take(5))
        assertEquals(cols.size, java.io.File(st.dir, "recordings.csv").readLines()[2].split(",").size)
        dir.deleteRecursively()
    }
}
