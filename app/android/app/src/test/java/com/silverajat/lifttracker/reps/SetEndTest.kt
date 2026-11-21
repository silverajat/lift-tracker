package com.silverajat.lifttracker.reps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/** Auto-end (SetEnd) on the test sets: never a rep short, and it sees the hands come off the bar. */
class SetEndTest {
    private class Fx(val name: String, val oneArm: Boolean, val data: List<FloatArray>)

    private fun fixtures(): List<Fx> {
        val dir = File(javaClass.classLoader!!.getResource("repsets")!!.toURI())
        return dir.listFiles()!!.filter { it.name.endsWith(".csv") }.sortedBy { it.name }.map { f ->
            val lines = f.readLines()
            val head = lines.first().removePrefix("# ").split(" ").associate { it.substringBefore("=") to it.substringAfter("=") }
            Fx(f.name, head.getValue("onearm") == "1",
                lines.drop(1).filter { it.isNotBlank() }.map { l -> l.split(",").map { it.toFloat() }.toFloatArray() })
        }
    }

    private fun count(d: List<FloatArray>) = RepEngine.count(d, 25)?.reps ?: 0

    /** After the set: the wrist turns [deg] about an axis across gravity over 0.8 s, then stays there 2 s. */
    private fun handsOff(d: List<FloatArray>, deg: Double): List<FloatArray> {
        val g0 = DoubleArray(3) { c -> d.takeLast(10).sumOf { it[c].toDouble() } / 10 }
        val gn = kotlin.math.sqrt(g0.sumOf { it * it }); for (c in 0..2) g0[c] /= gn
        // an axis across gravity
        val ref = if (kotlin.math.abs(g0[0]) < 0.9) doubleArrayOf(1.0, 0.0, 0.0) else doubleArrayOf(0.0, 1.0, 0.0)
        val ax = doubleArrayOf(g0[1] * ref[2] - g0[2] * ref[1], g0[2] * ref[0] - g0[0] * ref[2], g0[0] * ref[1] - g0[1] * ref[0])
        val an = kotlin.math.sqrt(ax.sumOf { it * it }); for (c in 0..2) ax[c] /= an
        fun turned(a: Double): DoubleArray {     // gravity in the pod's frame after the pod turns by a (Rodrigues, -a)
            val c = cos(-a); val s = sin(-a)
            val cr = doubleArrayOf(ax[1] * g0[2] - ax[2] * g0[1], ax[2] * g0[0] - ax[0] * g0[2], ax[0] * g0[1] - ax[1] * g0[0])
            val dt = ax[0] * g0[0] + ax[1] * g0[1] + ax[2] * g0[2]
            return DoubleArray(3) { k -> g0[k] * c + cr[k] * s + ax[k] * dt * (1 - c) }
        }
        val out = d.toMutableList()
        val n = 20
        val total = Math.toRadians(deg)
        var prev = 0.0
        for (i in 1..n) {
            val u = i / n.toDouble()
            val a = total * (10 * u * u * u - 15 * u * u * u * u + 6 * u * u * u * u * u)
            val rate = Math.toDegrees(a - prev) * 25; prev = a
            val g = turned(a)
            out += floatArrayOf(g[0].toFloat(), g[1].toFloat(), (g[2] + 0.1 * sin(Math.PI * u)).toFloat(),
                (rate * ax[0]).toFloat(), (rate * ax[1]).toFloat(), (rate * ax[2]).toFloat())
        }
        val g = turned(total)
        repeat(50) { out += floatArrayOf(g[0].toFloat(), g[1].toFloat(), g[2].toFloat(), 0f, 0f, 0f) }
        return out
    }

    @Test
    fun neverStopsBeforeTheLastRep() {
        for (f in fixtures().filter { !it.oneArm }) {
            val full = count(f.data)
            val e = SetEnd.replay(f.data, 25, step = 10) ?: continue
            assertEquals("${f.name} stopped early (${e.why})", full, count(f.data.subList(0, e.cut)))
        }
    }

    @Test
    fun handsOffTheBarEndsTheSet() {
        val fx = fixtures().filter { !it.oneArm && count(it.data) >= 3 }
        var caught = 0
        for (f in fx) {
            val full = count(f.data)
            val d = handsOff(f.data, 90.0)
            val e = SetEnd.replay(d, 25, step = 10) ?: continue
            caught++
            assertTrue("${f.name}: cut ${e.cut} before the set's end", count(d.subList(0, e.cut)) >= full)
            assertTrue("${f.name}: fired ${e.cut} after the hands came off", e.cut <= d.size)
        }
        println("auto-end caught $caught/${fx.size}")
        assertTrue("caught only $caught of ${fx.size}", caught >= fx.size * 0.6)
    }

    @Test
    fun aStillPauseIsNotTheEnd() {
        // a long breather at the same wrist angle in the middle: carry on
        val f = fixtures().first { !it.oneArm && count(it.data) >= 8 }
        val mid = f.data.size / 2
        val calm = f.data[mid].copyOf().also { it[3] = 0f; it[4] = 0f; it[5] = 0f }
        val d = f.data.subList(0, mid) + List(150) { calm.copyOf() } + f.data.subList(mid, f.data.size)
        val e = SetEnd.replay(d, 25, step = 10)
        assertTrue("stopped in the pause: $e", e == null || e.cut > mid + 150)
    }
}
