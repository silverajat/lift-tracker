package com.silverajat.lifttracker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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
    val Accent = Color(0xFFC6FF3D)      // lime: go / done
    val OnAccent = Color(0xFF0B0D10)
    val Pod = Color(0xFF3DDCFF)         // cyan: the pod / Bluetooth
    val Stop = Color(0xFFFF5A4E)        // red: stop
    val Warm = Color(0xFFFFB547)        // amber: warm-up sets
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
        content = content,
    )
}
