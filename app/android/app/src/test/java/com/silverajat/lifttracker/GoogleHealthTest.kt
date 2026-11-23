package com.silverajat.lifttracker

import com.silverajat.lifttracker.health.GoogleHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class GoogleHealthTest {
    @Test
    fun aFewWildWindowsDontMoveTheNight() {
        val night = List(40) { 32.0 + (it % 5) } + listOf(140.0, 160.0, 150.0)
        val m = GoogleHealth.robustMedian(night)!!
        assertTrue("$m", m in 32.0..36.0)
        assertNull(GoogleHealth.robustMedian(listOf(30.0, 31.0)))
    }

    @Test
    fun usesOursOnlyWhenItsSteadier() {
        val d0 = LocalDate.of(2026, 11, 1)
        // Fitbit's nightly number jumps about (one night doubles), ours stays close
        val fitbit = (0 until 10).associate { d0.plusDays(it.toLong()) to (if (it % 3 == 0) 66.0 else 34.0 + it % 2) }
        val ours = (0 until 10).associate { d0.plusDays(it.toLong()) to GoogleHealth.Night(d0.plusDays(it.toLong()), hrvOurs = 34.0 + it % 2) }
        val s = GoogleHealth.steadiness(ours, fitbit)
        assertEquals(10, s.nights)
        assertTrue(s.toString(), s.useOurs)
        // the other way round: keep Fitbit's
        val swapped = (0 until 10).associate { d0.plusDays(it.toLong()) to GoogleHealth.Night(d0.plusDays(it.toLong()), hrvOurs = fitbit.getValue(d0.plusDays(it.toLong()))) }
        assertFalse(GoogleHealth.steadiness(swapped, ours.mapValues { it.value.hrvOurs!! }).useOurs)
        // too few nights: not yet
        assertFalse(GoogleHealth.steadiness(ours.filterKeys { it < d0.plusDays(5) }, fitbit).useOurs)
    }
}
