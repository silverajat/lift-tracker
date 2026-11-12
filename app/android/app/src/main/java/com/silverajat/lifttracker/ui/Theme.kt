package com.silverajat.lifttracker.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0B0D10)
    val Surface = Color(0xFF15181D)
    val Raised = Color(0xFF1D2128)
    val Line = Color(0xFF2A2F37)
    val Text = Color(0xFFF2F4F7)
    val Muted = Color(0xFF8B93A1)
    val Accent = Color(0xFF3D8BFF)      // blue: actions, links (Hevy-style)
    val OnAccent = Color(0xFFFFFFFF)
    val Done = Color(0xFF2FD27A)        // green: completed sets
    val DoneRow = Color(0xFF12301F)     // completed row background
    val Pod = Color(0xFF3DDCFF)         // cyan: the pod / Bluetooth
    val Stop = Color(0xFFFF5A4E)        // red: stop
    val Warm = Color(0xFFFFB547)        // amber: warm-up sets
    val Violet = Color(0xFF7A4DFF)

    // Day card gradients
    val UpperA = Color(0xFF2F6BFF)
    val UpperB = Color(0xFF7A3DFF)
    val LowerA = Color(0xFF10B981)
    val LowerB = Color(0xFF0E8FA8)

    fun group(g: com.silverajat.lifttracker.data.Group): Color = when (g) {
        com.silverajat.lifttracker.data.Group.CHEST -> Color(0xFFFF6B6B)
        com.silverajat.lifttracker.data.Group.BACK -> Color(0xFF4DA3FF)
        com.silverajat.lifttracker.data.Group.SHOULDERS -> Color(0xFFFFB547)
        com.silverajat.lifttracker.data.Group.ARMS -> Color(0xFFB57BFF)
        com.silverajat.lifttracker.data.Group.LEGS -> Color(0xFF2FD27A)
        com.silverajat.lifttracker.data.Group.CORE -> Color(0xFF3DDCFF)
    }

    /** Green / amber / red for a 0..100 quality score. */
    fun score(v: Int): Color = when {
        v >= 80 -> Done
        v >= 60 -> Warm
        else -> Stop
    }
}

/** Tabular figures so timers and counts don't jitter. */
val Tnum = TextStyle(fontFeatureSettings = "tnum")

private val type = Typography(
    displayLarge = TextStyle(fontSize = 96.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp),
    displayMedium = TextStyle(fontSize = 56.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
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
