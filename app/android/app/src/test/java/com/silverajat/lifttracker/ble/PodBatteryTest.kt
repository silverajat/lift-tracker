package com.silverajat.lifttracker.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PodBatteryTest {
    private fun st(mv: Int, usb: Boolean = false) = PodStatus(1, false, usb, false, false, true, true, false, mv, 0, null, 0, 0, 2)

    @Test fun curve() {
        assertEquals(100, st(4200).batteryPct)
        assertEquals(52, st(3800).batteryPct)
        assertEquals(46, st(3775).batteryPct)
        assertEquals(0, st(3250).batteryPct)
        assertEquals("52%", st(3800).batteryLabel)
        assertEquals("4% (3.50 V)", st(3500).batteryLabel)
    }

    @Test fun noReading() {
        assertFalse(st(0).batteryKnown)
        assertEquals("battery not reading", st(0).batteryLabel)
        assertEquals("on USB, no battery reading", st(0, usb = true).batteryLabel)
    }
}
