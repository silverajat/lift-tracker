package com.silverajat.lifttracker.garden

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pixel-art plants. Each area is its own species, drawn on a small grid that gets
 * taller and fuller as the plant levels up. Colours are ARGB ints (0 = empty).
 */
object PixelPlant {
    const val W = 32
    const val H = 36
    const val GROUND = H - 8          // the soil line in the pot

    /** dark, mid, light, highlight */
    fun palette(a: Area, dry: Boolean): IntArray = if (dry) intArrayOf(0xFF5C4128.toInt(), 0xFF8A6A43.toInt(), 0xFFB0905E.toInt(), 0xFFCDB487.toInt())
    else when (a) {
        Area.STUDY -> intArrayOf(0xFF0E6B57.toInt(), 0xFF1FA383.toInt(), 0xFF3DD6B5.toInt(), 0xFFA6F5E1.toInt())
        Area.WORK -> intArrayOf(0xFF1C4A30.toInt(), 0xFF2F7449.toInt(), 0xFF4F9D69.toInt(), 0xFF8FD3A2.toInt())
        Area.GYM -> intArrayOf(0xFF8E2F12.toInt(), 0xFFD9581F.toInt(), 0xFFFF8A3D.toInt(), 0xFFFFC27A.toInt())
        Area.SPORTS -> intArrayOf(0xFF4F6E14.toInt(), 0xFF86A821.toInt(), 0xFFC9DB4A.toInt(), 0xFFEDF5A0.toInt())
        Area.SLEEP -> intArrayOf(0xFF4B3A8C.toInt(), 0xFF7A63D6.toInt(), 0xFFA78BFA.toInt(), 0xFFDCD0FF.toInt())
        Area.MIND -> intArrayOf(0xFFA3456E.toInt(), 0xFFE07AA6.toInt(), 0xFFF59EC4.toInt(), 0xFFFFE1EE.toInt())
    }

    private val BARK = intArrayOf(0xFF3E2716.toInt(), 0xFF6B4527.toInt(), 0xFF8E6239.toInt())
    private const val OUTLINE = 0xFF14100D.toInt()
    private val POT = intArrayOf(0xFF2A2E36.toInt(), 0xFF3B4150.toInt(), 0xFF555D70.toInt())
    private const val SOIL = 0xFF2B1B10.toInt()

    class Grid {
        val c = Array(H) { IntArray(W) }
        operator fun set(x: Int, y: Int, col: Int) { if (x in 0 until W && y in 0 until H) c[y][x] = col }
        operator fun get(x: Int, y: Int) = if (x in 0 until W && y in 0 until H) c[y][x] else 0
        fun line(x0: Int, y0: Int, x1: Int, y1: Int, col: Int, w: Int = 1) {
            var x = x0; var y = y0
            val dx = abs(x1 - x0); val dy = -abs(y1 - y0)
            val sx = if (x0 < x1) 1 else -1; val sy = if (y0 < y1) 1 else -1
            var err = dx + dy
            while (true) {
                for (k in 0 until w) set(x + k, y, col)
                if (x == x1 && y == y1) break
                val e2 = 2 * err
                if (e2 >= dy) { err += dy; x += sx }
                if (e2 <= dx) { err += dx; y += sy }
            }
        }
        /** A filled, shaded blob: light top-left, dark bottom-right, a few highlight pixels. */
        fun blob(cx: Int, cy: Int, r: Int, pal: IntArray, rnd: Random) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy > r * r + r) continue
                val s = (dx + dy).toFloat() / r
                this[x, y] = when {
                    s < -0.9f && rnd.nextFloat() < 0.45f -> pal[3]
                    s < -0.25f -> pal[2]
                    s > 0.65f -> pal[0]
                    else -> pal[1]
                }
            }
        }
    }

    /** The plant at a level (1..) with progress (0..1) into the next one. */
    fun sprite(area: Area, level: Int, progress: Float, dry: Boolean): Grid {
        val g = Grid()
        val pal = palette(area, dry)
        val rnd = Random(area.ordinal * 7919L + level * 31L)
        val grow = min(1f, (level - 1 + progress) / 9f)                  // 0 at Lv 1, 1 at Lv 10
        val hgt = (5 + grow * 23).roundToInt()                           // 5 to 28 pixels tall
        val bx = W / 2; val by = GROUND
        when (area) {
            Area.STUDY -> bamboo(g, bx, by, hgt, level, pal, rnd)
            Area.WORK -> pine(g, bx, by, hgt, level, pal)
            Area.GYM -> broadleaf(g, bx, by, hgt, level, pal, rnd, canopy = 1.0f)
            Area.SPORTS -> palm(g, bx, by, hgt, level, pal, rnd)
            Area.SLEEP -> wisteria(g, bx, by, hgt, level, pal, rnd)
            Area.MIND -> blossom(g, bx, by, hgt, level, pal, rnd)
        }
        outline(g)
        pot(g, area)
        return g
    }

    private fun bamboo(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray, rnd: Random) {
        val xs = listOf(0, -5, 5, -10, 10).take(min(1 + level / 2, 5))
        xs.forEachIndexed { i, off ->
            val sh = (h * (if (i == 0) 1f else 0.65f + 0.3f * rnd.nextFloat())).roundToInt().coerceAtLeast(3)
            val x = bx + off - 1
            for (y in by - sh until by) {
                val node = (by - y) % 4 == 0
                g[x, y] = if (node) pal[0] else pal[2]; g[x + 1, y] = if (node) pal[0] else pal[1]
            }
            // leaves off some nodes, and a tuft at the top
            var y = by - 5
            while (y > by - sh) {
                if (rnd.nextFloat() < 0.55f) {
                    val d = if (rnd.nextBoolean()) 1 else -1
                    val sx = if (d > 0) x + 2 else x - 1
                    g.line(sx, y, sx + d * 3, y - 2, pal[1]); g[sx + d * 4, y - 2] = pal[2]
                }
                y -= 4
            }
            g.line(x, by - sh, x - 3, by - sh - 2, pal[2]); g.line(x + 1, by - sh, x + 4, by - sh - 2, pal[1])
        }
    }

    private fun pine(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray) {
        for (y in by - h until by) { g[bx - 1, y] = BARK[1]; g[bx, y] = BARK[0] }
        val tiers = min(1 + level * 2 / 3, 6)
        val top = by - h - 1
        val trunkShow = (h * 0.2f).roundToInt().coerceAtLeast(2)
        val span = (by - trunkShow) - top
        for (k in 0 until tiers) {
            // each tier: a triangle; lower tiers are wider
            val y0 = top + span * k / (tiers + 1)
            val y1 = top + span * (k + 2) / (tiers + 1)
            val maxW = 2 + (h / 3) * (k + 1) / tiers
            for (y in y0..y1) {
                val w = ((y - y0).toFloat() / (y1 - y0).coerceAtLeast(1) * maxW).roundToInt()
                for (x in bx - w..bx + w - 1) g[x, y] = when {
                    x < bx - w / 2 -> pal[2]
                    x < bx -> pal[1]
                    y == y1 -> pal[0]
                    else -> if ((x + y) % 5 == 0) pal[0] else pal[1]
                }
            }
        }
        g[bx - 1, top - 1] = pal[3]; g[bx, top - 1] = pal[3]
    }

    /** Trunk and branches, with shaded canopy blobs at the branch tips. */
    private fun broadleaf(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray, rnd: Random, canopy: Float): List<Pair<Int, Int>> {
        val trunkTop = by - (h * 0.5f).roundToInt()
        for (y in trunkTop until by) { g[bx - 1, y] = BARK[2]; g[bx, y] = BARK[1]; if (y > by - 3) g[bx + 1, y] = BARK[0] }
        val n = min(1 + level / 2, 5)
        val tips = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) {
            val ang = Math.PI / 2 + (i - (n - 1) / 2.0) * 0.55 + (rnd.nextFloat() - 0.5) * 0.2
            val len = h * (0.35f + 0.15f * rnd.nextFloat())
            val tx = (bx + cos(ang) * len).roundToInt(); val ty = (trunkTop - sin(ang) * len * 0.8).roundToInt()
            g.line(bx, trunkTop, tx, ty, BARK[1])
            tips += tx to ty
        }
        if (canopy > 0) {
            val r = (2 + h / 6f * canopy).roundToInt().coerceIn(2, 6)
            tips.sortedByDescending { it.second }.forEach { (x, y) -> g.blob(x, y, r, pal, rnd) }
            g.blob(bx, trunkTop - r / 2, r + 1, pal, rnd)
        }
        return tips
    }

    private fun palm(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray, rnd: Random) {
        var tx = bx; var ty = by
        for (k in 0 until h) {
            val y = by - k
            val x = bx + (sin(k.toDouble() / h * 1.6) * h * 0.18).roundToInt()
            g[x - 1, y] = if (k % 3 == 0) BARK[0] else BARK[2]; g[x, y] = if (k % 3 == 0) BARK[0] else BARK[1]
            tx = x; ty = y
        }
        val fronds = min(3 + level, 9)
        for (i in 0 until fronds) {
            val dir = if (i % 2 == 0) 1 else -1
            val reach = (h * (0.3f + 0.25f * rnd.nextFloat())).roundToInt().coerceAtLeast(3)
            val lift = (i / 2) % 3
            var px = tx; var py = ty
            for (s in 1..reach) {
                val x = tx + dir * s
                val y = ty - lift + ((s * s) / (reach * 1.3f)).roundToInt() - (if (s < reach / 3) 1 else 0)
                g.line(px, py, x, y, if (s % 2 == 0) pal[1] else pal[2])
                if (s % 2 == 0) g[x, y + 1] = pal[0]
                px = x; py = y
            }
        }
        if (h > 12) { g[tx - 1, ty + 1] = 0xFF6B4A1E.toInt(); g[tx + 1, ty + 1] = 0xFF6B4A1E.toInt() }   // coconuts
    }

    private fun wisteria(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray, rnd: Random) {
        val green = intArrayOf(0xFF1E4D2B.toInt(), 0xFF2E7044.toInt(), 0xFF46955E.toInt(), 0xFF7CC48F.toInt())
        val tips = broadleaf(g, bx, by, h, level, green, rnd, canopy = 0.55f)
        // hanging flower clusters under the canopy
        val clusters = (tips + (bx to by - (h * 0.6f).roundToInt())).flatMap { (x, y) -> listOf(x - 2 to y + 2, x + 1 to y + 3, x + 3 to y + 1) }
        clusters.take(3 + level * 2).forEach { (x, y) ->
            val len = 3 + rnd.nextInt(min(2 + level / 2, 5))
            for (k in 0 until len) {
                val c = when { k == 0 -> pal[2]; k < len / 2 -> pal[1]; else -> pal[0] }
                g[x, y + k] = c
                if (k < len - 1 && k % 2 == 0) g[x + 1, y + k] = pal[2]
            }
            g[x, y] = pal[3]
        }
    }

    private fun blossom(g: Grid, bx: Int, by: Int, h: Int, level: Int, pal: IntArray, rnd: Random) {
        // a bonsai: S-curved trunk, low wide canopy of blossom clouds
        val th = (h * 0.55f).roundToInt().coerceAtLeast(3)
        var lx = bx
        for (k in 0 until th) {
            val x = bx + (sin(k * 3.0 / th) * 2.5).roundToInt()
            g[x - 1, by - k] = BARK[2]; g[x, by - k] = BARK[1]; g[x + 1, by - k] = if (k < th / 2) BARK[0] else 0
            lx = x
        }
        val top = by - th
        val clouds = min(1 + level / 2, 5)
        val r = (2 + h / 8).coerceIn(2, 5)
        val pts = (0 until clouds).map { i ->
            val off = (i - (clouds - 1) / 2f) * (r + 2.5f)
            (lx + off.roundToInt()) to (top - (r * 0.6f).roundToInt() + (abs(off) / 3).roundToInt())
        }
        pts.forEach { (x, y) -> g.line(lx, top, x, y + r / 2, BARK[1]) }
        pts.forEach { (x, y) -> g.blob(x, y, r, pal, rnd) }
        // little white flowers
        repeat(clouds * 3) {
            val (x, y) = pts[rnd.nextInt(pts.size)]
            g[x + rnd.nextInt(2 * r + 1) - r, y + rnd.nextInt(r + 1) - r] = pal[3]
        }
    }

    /** A dark 1-pixel outline around the plant, the classic pixel-art look. */
    private fun outline(g: Grid) {
        val add = ArrayList<Pair<Int, Int>>()
        for (y in 0 until GROUND) for (x in 0 until W) {
            if (g[x, y] != 0) continue
            if (g[x - 1, y] != 0 || g[x + 1, y] != 0 || g[x, y - 1] != 0 || g[x, y + 1] != 0) add += x to y
        }
        add.forEach { (x, y) -> g[x, y] = OUTLINE }
    }

    private fun pot(g: Grid, area: Area) {
        val accent = palette(area, false)[1]
        val cx = W / 2
        // soil and rim
        for (x in cx - 7 until cx + 7) { g[x, GROUND] = SOIL; g[x, GROUND + 1] = POT[2] }
        g[cx - 8, GROUND + 1] = OUTLINE; g[cx + 7, GROUND + 1] = OUTLINE
        for (x in cx - 8..cx + 7) g[x, GROUND] = if (x == cx - 8 || x == cx + 7) OUTLINE else g[x, GROUND]
        // body narrows towards the bottom
        for (row in 0 until 6) {
            val y = GROUND + 2 + row
            val half = 6 - row / 2
            for (x in cx - half until cx + half) g[x, y] = when {
                row == 2 -> accent
                x >= cx + half - 3 -> POT[0]
                x <= cx - half + 1 -> POT[2]
                else -> POT[1]
            }
            g[cx - half - 1, y] = OUTLINE; g[cx + half, y] = OUTLINE
        }
        for (x in cx - 4 until cx + 4) g[x, H - 1] = OUTLINE
    }

    /** Ambient pixels for frame [t]: falling leaves/petals, sleepy z's, or rising sparkles while focusing. */
    fun particles(area: Area, t: Int, focusing: Boolean, dry: Boolean): List<Triple<Int, Int, Int>> {
        val pal = palette(area, dry)
        val out = ArrayList<Triple<Int, Int, Int>>()
        if (focusing) for (k in 0 until 5) {
            val period = 14 + k * 3
            val p = (t + k * 5) % period
            val x = W / 2 - 6 + (k * 5) % 13 + (if (p % 4 < 2) 0 else 1)
            val y = GROUND - 1 - p * GROUND / period
            out += Triple(x, y, if (p % 3 == 0) 0xFFFFF4B0.toInt() else pal[3])
        }
        if (dry) return out
        when (area) {
            Area.GYM, Area.MIND, Area.STUDY -> for (k in 0 until 2) {   // leaves / petals drifting down
                val period = 26 + k * 9
                val p = (t + k * 13) % period
                val x = 8 + (k * 11 + p / 3) % 16 + (if ((p / 2) % 2 == 0) 0 else 1)
                out += Triple(x, 4 + p * (GROUND - 4) / period, pal[if (area == Area.MIND) 3 else 2])
            }
            Area.SLEEP -> {                                                // z z z
                val p = t % 18
                val x = W / 2 + 4 + p / 4; val y = 8 - p / 3
                if (p < 15) listOf(0 to 0, 1 to 0, 2 to 0, 1 to 1, 0 to 2, 1 to 2, 2 to 2).forEach { (dx, dy) -> out += Triple(x + dx, y + dy, pal[3]) }
            }
            Area.SPORTS -> {                                               // sun glint
                if (t % 10 < 3) listOf(0 to 0, -1 to 0, 1 to 0, 0 to -1, 0 to 1).forEach { (dx, dy) -> out += Triple(W - 5 + dx, 4 + dy, 0xFFFFE27A.toInt()) }
            }
            Area.WORK -> for (k in 0 until 2) {                            // fireflies
                val p = (t + k * 7) % 20
                if (p < 12) out += Triple(6 + k * 18 + (p % 3), 10 + (p / 3) % 4 + k * 3, 0xFFF2FF8A.toInt())
            }
        }
        return out
    }
}
