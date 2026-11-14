package com.silverajat.lifttracker.reps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin rep counter against python/repcount (the reference), on
 * simulated sets made by python/repcount/make_fixtures.py.
 */
class RepEngineTest {
    private class Fixture(val name: String, val trueReps: Int, val py: Int, val oneArm: Boolean, val data: List<FloatArray>)

    private fun fixtures(): List<Fixture> {
        val dir = File(javaClass.classLoader!!.getResource("repsets")!!.toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sortedBy { it.name }.map { f ->
            val lines = f.readLines()
            val head = lines.first().removePrefix("# ").split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            val data = lines.drop(1).filter { it.isNotBlank() }.map { l -> l.split(",").map { it.toFloat() }.toFloatArray() }
            Fixture(f.name, head.getValue("true").toInt(), head.getValue("py").toInt(), head.getValue("onearm") == "1", data)
        }
    }

    private fun countOf(f: Fixture, p: CounterParams = CounterParams()) = RepCounter.count(f.data, 25, p, f.oneArm)

    @Test
    fun matchesPythonReference() {
        val fx = fixtures()
        assertTrue("no fixtures", fx.size >= 20)
        val mismatches = fx.filter { countOf(it) != it.py }
        mismatches.forEach { println("MISMATCH ${it.name}: kotlin ${countOf(it)} python ${it.py} true ${it.trueReps}") }
        assertTrue("Kotlin and Python disagree on ${mismatches.size} sets", mismatches.size <= 1)
    }

    @Test
    fun countsSimulatedLifts() {
        val fx = fixtures()
        val exact = fx.count { countOf(it) == it.trueReps }
        val within1 = fx.count { kotlin.math.abs(countOf(it) - it.trueReps) <= 1 }
        println("exact $exact/${fx.size}, within 1: $within1/${fx.size}")
        assertTrue("exact only $exact of ${fx.size}", exact >= fx.size * 0.85)
        assertEquals(fx.size, within1)
    }

    @Test
    fun firstAndLastBoutPickTheRightSide() {
        fun move(sec: Int) = List(sec * 25) { i -> val t = i / 25.0
            floatArrayOf(0f, 0f, 1f, (150 * kotlin.math.sin(2 * Math.PI * 0.5 * t)).toFloat(), 0f, 0f) }
        fun still(sec: Int) = List(sec * 25) { floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f) }
        val d = still(2) + move(8) + still(4) + move(6) + still(2)
        val first = RepEngine.firstBout(d, 25)
        val last = RepEngine.lastBout(d, 25)
        assertTrue("first $first", first.first < 3 * 25 && first.last < 12 * 25)
        assertTrue("last $last", last.first > 12 * 25)
    }

    @Test
    fun peakHoldKeepsTheCountWhenTheEndWipesItOut() {
        val h = PeakHold()
        // counting up through the set, then picking up the phone collapses it to 2
        var shown = 0
        (1..14).forEach { shown = h.update(it, it * 60) }
        assertEquals(14, shown)
        assertEquals(14, h.update(2, 900))
        assertTrue(h.collapsed(2))
        val data = List(900) { FloatArray(6) }
        assertEquals(14 * 60, h.cut(data, 2).size)
        // a small correction (one rep less) is taken as it is
        assertEquals(13, h.update(13, 950))
        assertEquals(900, h.cut(data, 13).size)
        // short sets aren't held
        val s = PeakHold(); s.update(3, 100)
        assertEquals(1, s.update(1, 200))
    }

    @Test
    fun analysisGivesRomPerRep() {
        val f = fixtures().first { it.name.contains("curl") && !it.oneArm }
        val a = RepCounter.analyze(f.data, 25, CounterParams(), false) { null }!!
        assertEquals(a.reps, a.perRep.size)
        assertEquals("deg", a.unit)
        assertTrue("curl ROM ${a.setRom}", a.setRom in 60f..200f)
        val p = fixtures().first { it.name.contains("_ohp") }
        val b = RepCounter.analyze(p.data, 25, CounterParams(), false) { null }!!
        assertEquals("cm", b.unit)
        println("ohp ROM ${b.setRom} cm, curl ROM ${a.setRom} deg")
    }

    @Test
    fun learnerKeepsDefaultsWhenTheyWork() {
        val ex = fixtures().filter { !it.oneArm }.take(8).map {
            RepModel.Example(1, RepEngine.signals(it.data, 25), it.trueReps)
        }
        val fit = RepModel.fit(ex)!!
        assertTrue(fit.missLearned <= fit.missDefault)
        val m = RepModel.train(ex)
        val back = RepModel.Model.fromJson(m.toJson())
        assertEquals(m.params(1), back.params(1))
    }
}
