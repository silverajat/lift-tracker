package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.activity.Sport

/** A little pixel picture: rows of characters, each character a colour ('.' is empty). Original art, drawn for this app. */
class Sprite(val rows: List<String>, val colors: Map<Char, Color>) {
    val w = rows.maxOf { it.length }
    val h = rows.size
}

/** Draws a sprite square-pixelled, centred in a [size] box. Used in place of emoji, to match the garden. */
@Composable
fun PixelSprite(s: Sprite, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val p = minOf(this.size.width / s.w, this.size.height / s.h).let { kotlin.math.floor(it) }.coerceAtLeast(1f)
        val ox = (this.size.width - p * s.w) / 2
        val oy = (this.size.height - p * s.h) / 2
        s.rows.forEachIndexed { y, row -> row.forEachIndexed { x, c ->
            s.colors[c]?.let { drawRect(it, Offset(ox + x * p, oy + y * p), Size(p + 0.5f, p + 0.5f)) }
        } }
    }
}

object Sprites {
    private val skin = Color(0xFFF2B48A)
    private val hair = Color(0xFF5A3A22)
    private val pants = Color(0xFF3B5BDB)
    private val shoe = Color(0xFFE9ECEF)
    private val steel = Color(0xFFC9CED6)
    private val plate = Color(0xFF6B7280)
    private val dark = Color(0xFF2B2F36)
    private val white = Color(0xFFF1F3F5)

    private fun person(shirt: Color) = mapOf('S' to skin, 'H' to hair, 'R' to shirt, 'B' to pants, 'W' to shoe)

    val lifter = Sprite(listOf(
        "D..........D",
        "DGGGGGGGGGGD",
        "D.S......S.D",
        "..S.HHHH.S..",
        "..S.SSSS.S..",
        "..SRRRRRRS..",
        "....RRRR....",
        "....RRRR....",
        "....BBBB....",
        "....B..B....",
        "...BB..BB...",
        "...WW..WW...",
    ), person(Plate.Red) + mapOf('D' to plate, 'G' to steel))

    val runner = Sprite(listOf(
        "......HH....",
        ".....HSS....",
        "......SS....",
        ".....RRR....",
        "....RRRRS...",
        "...S.RRR.S..",
        "..S..RRR....",
        ".....BBB....",
        "....BB.BB...",
        "...BB...BB..",
        "..WB.....B..",
        "..W......WW.",
    ), person(Plate.Blue))

    val walker = Sprite(listOf(
        ".....HH.....",
        ".....SS.....",
        ".....SS.....",
        "....RRRR....",
        "...SRRRR....",
        "...S.RRRS...",
        ".....RRR.S..",
        ".....BBB....",
        ".....B.B....",
        "....B...B...",
        "....B...B...",
        "...WW...WW..",
    ), person(Color(0xFF9AA4B2)))

    val ball = Sprite(listOf(
        "...WWWW...",
        ".WWKKWWWW.",
        ".WKKKWWKW.",
        "WWWKWWKKKW",
        "WKWWWWWKWW",
        "WKKWWKWWWW",
        "WWWWKKKWKW",
        ".WWWKKWKK.",
        ".WKWWWWWW.",
        "...WWWW...",
    ), mapOf('W' to white, 'K' to dark))

    val racket = Sprite(listOf(
        "......FFFF..",
        ".....FWSWSF.",
        "....FWSWSWSF",
        "....FSWSWSWF",
        "....FWSWSWSF",
        ".....FSWSWF.",
        "......FFFF..",
        ".....G......",
        "....G.......",
        "...HH.......",
        "..HH........",
        ".HH.........",
    ), mapOf('F' to Plate.Yellow, 'W' to white, 'S' to Color(0xFF3A3F48), 'G' to steel, 'H' to Plate.Red))

    val swimmer = Sprite(listOf(
        "........HHS.",
        ".SS.RRRRRSS.",
        "S..RRRRRR...",
        "............",
        ".WW..WW..WW.",
        "W..WW..WW..W",
    ), person(Plate.Blue) + mapOf('W' to Color(0xFF4DABF7)))

    val cyclist = Sprite(listOf(
        "......HH....",
        ".....SS.....",
        "....RRRS....",
        "...RRR..S...",
        "...BB.......",
        "..KKBB.KK...",
        ".K..K.K..K..",
        ".K..K.K..K..",
        "..KK...KK...",
    ), person(Plate.Green) + mapOf('K' to steel))

    val bolt = Sprite(listOf(
        "...YYY.",
        "..YYY..",
        ".YYY...",
        "YYYYYY.",
        "..YYY..",
        ".YYY...",
        "YYY....",
    ), mapOf('Y' to Plate.Yellow))

    val book = Sprite(listOf(
        ".PPPP..PPPP.",
        "PWWWWPPWWWWP",
        "PWKKWPPWKKWP",
        "PWWWWPPWWWWP",
        "PWKKWPPWKKWP",
        "PWWWWPPWWWWP",
        ".PPPPPPPPPP.",
    ), mapOf('P' to Color(0xFFE07AA6), 'W' to white, 'K' to Color(0xFF9AA4B2)))

    val chat = Sprite(listOf(
        ".WWWWWWWW.",
        "WWWWWWWWWW",
        "WWKWWKWWKW",
        "WWWWWWWWWW",
        ".WWWWWWWW.",
        "..WW......",
        ".W........",
    ), mapOf('W' to Color(0xFF6EA8FE), 'K' to Color(0xFF1B2333)))

    private fun face(mouth4: String, mouth5: String) = Sprite(listOf(
        "..YYYY..",
        ".YYYYYY.",
        "YYKYYKYY",
        "YYYYYYYY",
        mouth4,
        mouth5,
        ".YYYYYY.",
        "..YYYY..",
    ), mapOf('Y' to Color(0xFFFFC94D), 'K' to Color(0xFF3B2A12)))

    /** Moods 1 to 5: low to great. */
    val moods = listOf(
        face("YYYKKYYY", "YYKYYKYY"),
        face("YYYYYKYY", "YYKKKYYY"),
        face("YYYYYYYY", "YYKKKKYY"),
        face("YYKYYKYY", "YYYKKYYY"),
        face("YKKKKKKY", "YYKKKKYY"),
    )

    fun sport(s: Sport) = when (s) {
        Sport.RUN -> runner
        Sport.WALK -> walker
        Sport.FOOTBALL -> ball
        Sport.BADMINTON -> racket
        Sport.GYM -> lifter
        Sport.SWIM -> swimmer
        Sport.CYCLE -> cyclist
        Sport.OTHER -> bolt
    }
}
