package com.silverajat.lifttracker.reps

import com.silverajat.lifttracker.data.Routines
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real sets from pod v2 (the first lower day on it, 28 Nov), as the phone receives them (25 Hz), with the reps you
 * confirmed. Shows what the counter gets with its defaults, and with settings learned only from pod v2 sets the way
 * the phone now learns them (each set counted with settings fitted on the OTHER sets, so it can't learn its own answer).
 */
class PodV2CountTest {
    private class SetData(val name: String, val ex: Int, val warm: Boolean, val truth: Int, val live: Int?, val data: List<FloatArray>)

    private fun sets(): List<SetData> {
        val dir = File(javaClass.classLoader!!.getResource("podv2")!!.toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sortedBy { it.name }.map { f ->
            val lines = f.readLines()
            val h = lines.first().removePrefix("# ").split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            val data = lines.drop(1).filter { it.isNotBlank() }.map { l -> l.split(",").map { it.toFloat() }.toFloatArray() }
            SetData(f.name, h.getValue("ex").toInt(), h["warm"] == "1", h.getValue("true").toInt(), h["live"]?.toIntOrNull(), data)
        }
    }

    private fun example(s: SetData): RepModel.Example {
        val cut = RepCounter.side(s.data, 25, Routines.isOneArm(s.ex), Routines.podSideFirst(s.ex))
        return RepModel.Example(s.ex, RepEngine.signals(cut, 25), s.truth, s.warm, newPod = true)
    }

    @Test
    fun learnedOnPodV2BeatsDefaults() {
        val all = sets()
        assertTrue(all.size >= 15)
        val ex = all.map { example(it) }
        var missDef = 0; var missLoo = 0; var missLive = 0
        for (i in all.indices) {
            val s = all[i]
            val def = RepEngine.count(ex[i].signals, CounterParams())?.reps ?: 0
            val others = RepModel.train(ex.filterIndexed { j, _ -> j != i })
            val loo = RepEngine.count(ex[i].signals, others.params(s.ex, s.warm))?.reps ?: 0
            missDef += kotlin.math.abs(def - s.truth); missLoo += kotlin.math.abs(loo - s.truth)
            missLive += kotlin.math.abs((s.live ?: 0) - s.truth)
            println("PODV2 ${s.name}: true ${s.truth} live ${s.live} default $def learned $loo")
        }
        val full = RepModel.train(ex)
        full.perExercise.forEach { (k, f) -> println("PODV2 fit ex $k: ${f.sets} sets, ${f.params}, missed ${f.missDefault} -> ${f.missLearned}") }
        println("PODV2 missed reps: live (old-pod settings) $missLive, default $missDef, learned on pod v2 (held out) $missLoo")
        assertTrue("learning from pod v2 sets shouldn't make it worse", missLoo <= missDef)
    }
}
