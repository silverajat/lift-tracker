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

    /** Racking the weight, walking to the phone and picking it up after the last rep. */
    private fun junk(seconds: Int, seed: Int): List<FloatArray> {
        val r = java.util.Random(seed.toLong())
        return (0 until seconds * 25).map { i ->
            val t = i / 25.0
            floatArrayOf(
                (0.2 + 1.2 * kotlin.math.sin(2 * Math.PI * 1.8 * t) + 0.3 * r.nextGaussian()).toFloat(),
                (0.9 * kotlin.math.cos(2 * Math.PI * 0.7 * t) + 0.3 * r.nextGaussian()).toFloat(),
                (0.6 + 0.8 * kotlin.math.sin(2 * Math.PI * 2.3 * t) + 0.3 * r.nextGaussian()).toFloat(),
                (250 * kotlin.math.sin(2 * Math.PI * 1.1 * t) + 40 * r.nextGaussian()).toFloat(),
                (180 * kotlin.math.cos(2 * Math.PI * 1.9 * t) + 40 * r.nextGaussian()).toFloat(),
                (220 * kotlin.math.sin(2 * Math.PI * 0.6 * t) + 40 * r.nextGaussian()).toFloat(),
            )
        }
    }

    @Test
    fun junkAfterTheSetDoesntWipeTheCount() {
        val fx = fixtures()
        var raw = 0; var ok = 0
        fx.forEachIndexed { i, f ->
            val d = f.data + junk(4 + i % 6, i)
            val before = RepEngine.count(if (f.oneArm) d.slice(RepEngine.lastBout(d, 25)) else d, 25)?.reps ?: 0
            val after = RepCounter.count(d, 25, CounterParams(), f.oneArm)
            if (kotlin.math.abs(before - f.trueReps) <= 1) raw++
            if (kotlin.math.abs(after - f.trueReps) <= 1) ok++ else println("JUNK ${f.name}: true ${f.trueReps} got $after (untrimmed $before)")
        }
        println("with junk at the end: within 1 untrimmed $raw/${fx.size}, trimmed $ok/${fx.size}")
        assertTrue("trimmed within 1 only $ok of ${fx.size}", ok >= fx.size - 2)
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
