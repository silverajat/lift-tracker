package com.silverajat.lifttracker.activity

import com.silverajat.lifttracker.health.Analysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunEffortTest {
    private fun run(km: Double, s: Int, splits: List<Int> = emptyList(), sport: Sport = Sport.RUN) =
        Activity(id = "r", sport = sport, startMs = 0, durationS = s, distanceKm = km, note = "", route = emptyList(),
            splitsS = splits, elapsedS = s)

    @Test
    fun aHardFiveKIsARealStrain() {
        val t = RunEffort.trimp(run(5.0, 1421, listOf(290, 285, 284, 282, 280)), 50.0, 55, 200)!!
        val strain = Analysis.strainOf(t)
        println("5 km in 23:41: trimp $t strain %.1f".format(strain))
        assertTrue("strain $strain", strain in 6.0..12.0)
        // a slow jog of the same time is clearly less, a walk less again
        val jog = Analysis.strainOf(RunEffort.trimp(run(3.5, 1421), 50.0, 55, 200)!!)
        val walk = Analysis.strainOf(RunEffort.trimp(run(2.2, 1421, sport = Sport.WALK), 50.0, 55, 200)!!)
        assertTrue("jog $jog", jog < strain)
        assertTrue("walk $walk", walk < jog)
    }

    @Test
    fun coverage() {
        assertEquals(1.0, RunEffort.coverage((0..60).map { it * 1000L }, 0, 60_000), 1e-9)
        // a minute of readings in a 24 min run
        assertTrue(RunEffort.coverage((0..30).map { it * 2000L }, 0, 24 * 60_000) < 0.1)
    }
}
