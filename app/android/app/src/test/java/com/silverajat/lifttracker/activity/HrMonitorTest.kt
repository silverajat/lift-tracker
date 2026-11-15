package com.silverajat.lifttracker.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrMonitorTest {
    @Test fun parsesBothFormats() {
        assertEquals(152, HrMonitor.parse(byteArrayOf(0x00, 152.toByte())))                 // 8-bit
        assertEquals(301, HrMonitor.parse(byteArrayOf(0x01, 0x2D, 0x01)))                    // 16-bit little-endian
        assertEquals(98, HrMonitor.parse(byteArrayOf(0x16, 98, 0x10, 0x03)))                 // 8-bit with RR intervals after
        assertNull(HrMonitor.parse(byteArrayOf(0x00)))
    }

    @Test fun heartRateIsSavedWithTheRun() {
        val a = Activity("a1", Sport.RUN, 1_000_000L, 600, 2.0, hr = listOf(HrPoint(1_002_000L, 120), HrPoint(1_004_000L, 131)))
        val back = ActivityStore.fromJson(ActivityStore.toJson(a))
        assertEquals(a.hr, back.hr)
    }
}
