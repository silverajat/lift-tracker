package com.silverajat.lifttracker.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.silverajat.lifttracker.R
import androidx.compose.ui.unit.sp

/**
 * "Rubber floor and bumper plates": a slate gym-floor background, chalk
 * text, and the competition plate colours (red 25, blue 20, yellow 15,
 * green 10, white 5) used only where a colour means something.
 */
object Plate {
    val Red = Color(0xFFE5484D)
    val Blue = Color(0xFF3E7BFA)
    val Yellow = Color(0xFFF5C542)
    val Green = Color(0xFF30B566)
    val Chalk = Color(0xFFECEEE8)
}

object C {
    val Bg = Color(0xFF1A1F27)          // rubber floor
    val Surface = Color(0xFF222832)
    val Raised = Color(0xFF2C3340)
    val Line = Color(0xFF353D4A)
    val Text = Plate.Chalk
    val Muted = Color(0xFF8F98A6)
    val Accent = Plate.Blue             // actions
    val OnAccent = Color(0xFFFFFFFF)
    val Done = Plate.Green              // completed sets
    val DoneRow = Color(0xFF1F3A2B)     // completed row background
    val Pod = Color(0xFF5FD4F4)         // the pod / Bluetooth
    val Stop = Plate.Red
    val Warm = Plate.Yellow             // warm-up sets
    val Violet = Color(0xFF9B8CFF)

    // Day card gradients (upper = blue plate, lower = green plate)
    val UpperA = Color(0xFF3E7BFA)
    val UpperB = Color(0xFF2448A8)
    val LowerA = Color(0xFF30B566)
    val LowerB = Color(0xFF1C6E46)

    fun group(g: com.silverajat.lifttracker.data.Group): Color = when (g) {
        com.silverajat.lifttracker.data.Group.CHEST -> Plate.Red
        com.silverajat.lifttracker.data.Group.BACK -> Plate.Blue
        com.silverajat.lifttracker.data.Group.SHOULDERS -> Plate.Yellow
        com.silverajat.lifttracker.data.Group.ARMS -> Color(0xFFB79CFF)
        com.silverajat.lifttracker.data.Group.LEGS -> Plate.Green
        com.silverajat.lifttracker.data.Group.CORE -> Color(0xFF5FD4F4)
    }

    /** Green / yellow / red for a 0..100 quality score. */
    fun score(v: Int): Color = when {
        v >= 80 -> Done
        v >= 60 -> Warm
        else -> Stop
    }
}

/** Barlow for text, Barlow Condensed (scoreboard numbers) for figures and headings. */
val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semibold, FontWeight.Bold),
)
val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_bold, FontWeight.Black),
    Font(R.font.barlow_condensed_bold, FontWeight.ExtraBold),
)

/** Tabular figures so timers and counts don't jitter. */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

private val type = Typography(
    // scoreboard numbers
    displayLarge = TextStyle(fontFamily = BarlowCondensed, fontSize = 104.sp, lineHeight = 100.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = BarlowCondensed, fontSize = 60.sp, lineHeight = 60.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontFamily = BarlowCondensed, fontSize = 40.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold),
    // headings
    headlineLarge = TextStyle(fontFamily = BarlowCondensed, fontSize = 36.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = BarlowCondensed, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = BarlowCondensed, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = BarlowCondensed, fontSize = 22.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    // text
    titleMedium = TextStyle(fontFamily = Barlow, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = Barlow, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Barlow, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Barlow, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Barlow, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Barlow, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontFamily = Barlow, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = Barlow, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun LiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent,
            onPrimary = C.OnAccent,
            secondary = C.Pod,
            background = C.Bg,
            onBackground = C.Text,
            surface = C.Surface,
            onSurface = C.Text,
            surfaceVariant = C.Raised,
            onSurfaceVariant = C.Muted,
            outline = C.Line,
            error = C.Stop,
        ),
        typography = type,
    ) {
        // Text without its own colour is light on the dark background
        // (outside a Surface, Material's default content colour is black).
        CompositionLocalProvider(LocalContentColor provides C.Text, content = content)
    }
}
