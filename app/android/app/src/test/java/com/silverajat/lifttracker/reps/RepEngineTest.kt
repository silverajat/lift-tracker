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
