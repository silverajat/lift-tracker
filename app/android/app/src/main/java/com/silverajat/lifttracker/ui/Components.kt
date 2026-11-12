package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.ble.PodStatus
import com.silverajat.lifttracker.data.PlanSet
import kotlin.math.roundToLong

// ---- formatting ---------------------------------------------------------------

fun kg(w: Double): String {
    val r = (w * 100).roundToLong()
    return when {
        r % 100 == 0L -> (r / 100).toString()
        r % 10 == 0L -> "%.1f".format(w)
        else -> "%.2f".format(w)
    }
}

fun weightLabel(w: Double, addons: Int, bodyweight: Boolean = false): String {
    if (bodyweight && w == 0.0) return "BW"
    val add = when (addons) {
        0 -> ""
        1 -> " + add-on"
        else -> " + $addons add-ons"
    }
    return kg(w) + add
}

fun setLabel(s: PlanSet, bodyweight: Boolean = false) =
    "${weightLabel(s.weight, s.addons, bodyweight)} × ${s.reps}"

fun clock(ms: Long): String {
    val t = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(t / 60, t % 60)
}

fun elapsed(ms: Long): String {
    val t = ms.coerceAtLeast(0) / 1000
    return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, (t / 60) % 60, t % 60)
    else "%d:%02d".format(t / 60, t % 60)
}

// ---- building blocks -------------------------------------------------------------

@Composable
fun Card(
    modifier: Modifier = Modifier,
    border: Color = C.Line,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier
            .clip(shape)
            .background(C.Surface)
            .border(1.dp, border, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(18.dp)
    ) { content() }
}

@Composable
fun Tag(text: String, color: Color = C.Muted, fill: Color = C.Raised) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.merge(Tnum),
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun SetChips(sets: List<PlanSet>, bodyweight: Boolean = false) {
    FlowRowCompat(spacing = 6.dp) {
        sets.forEach { s ->
            if (s.warmup) Tag("W  " + setLabel(s, bodyweight), color = C.Warm)
            else Tag(setLabel(s, bodyweight), color = C.Text)
        }
    }
}

/** A simple wrapping row (FlowRow is still experimental). */
@Composable
fun FlowRowCompat(spacing: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        var x = 0
        var y = 0
        var rowH = 0
        val pos = placeables.map { p ->
            if (x > 0 && x + p.width > constraints.maxWidth) {
                x = 0; y += rowH + gap; rowH = 0
            }
            val at = x to y
            x += p.width + gap
            rowH = maxOf(rowH, p.height)
            at
        }
        layout(constraints.maxWidth, y + rowH) {
            placeables.forEachIndexed { i, p -> p.place(pos[i].first, pos[i].second) }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = modifier)
}

// ---- pod status ---------------------------------------------------------------------

@Composable
fun PodBar(conn: PodConn, status: PodStatus?, onConnect: () -> Unit) {
    val (dot, title, sub) = when (conn) {
        PodConn.READY -> Triple(
            C.Pod, "Pod connected",
            status?.let {
                buildString {
                    append("${it.batteryPct}%")
                    if (it.charging) append(" · charging")
                    if (it.recording) append(" · recording")
                    if (!it.imuOk) append(" · IMU not started")
                    if (it.fault) append(" · flash full: dump it on the laptop")
                    append(" · fw ${it.firmware}")
                }
            } ?: "Reading status…",
        )
        PodConn.SCANNING -> Triple(C.Warm, "Looking for the pod…", "Wake it: give it a little shake")
        PodConn.CONNECTING -> Triple(C.Warm, "Connecting…", "")
        PodConn.BLUETOOTH_OFF -> Triple(C.Stop, "Bluetooth is off", "Turn it on to use the pod")
        PodConn.NO_PERMISSION -> Triple(C.Stop, "Allow Bluetooth", "Tap to let the app find the pod")
        PodConn.IDLE -> Triple(C.Muted, "Pod not connected", "Tap to connect")
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (conn == PodConn.READY) C.Pod.copy(alpha = 0.5f) else C.Line,
        onClick = if (conn == PodConn.READY) null else onConnect,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                }
            }
            if (conn == PodConn.READY && status != null) {
                BatteryGlyph(status.batteryPct)
            }
        }
    }
}

@Composable
private fun BatteryGlyph(pct: Int) {
    val color = when {
        pct < 15 -> C.Stop
        pct < 35 -> C.Warm
        else -> C.Accent
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(28.dp).height(14.dp)
                .border(1.5.dp, C.Muted, RoundedCornerShape(3.dp))
                .padding(2.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct / 100f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color)
            )
        }
        Box(Modifier.width(2.dp).height(6.dp).background(C.Muted))
    }
}

// ---- rest timer ------------------------------------------------------------------------

@Composable
fun RestBanner(
    endsAt: Long, total: Int, now: Long,
    onAdd: () -> Unit, onSkip: () -> Unit,
) {
    val left = endsAt - now
    val done = left <= 0
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (done) C.Accent else C.Line,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(if (done) "Rest done" else "Rest")
                    Text(
                        if (done) "Go!" else clock(left),
                        style = MaterialTheme.typography.displayMedium.merge(Tnum),
                        color = if (done) C.Accent else C.Text,
                    )
                }
                TextButton(onClick = onAdd) { Text("+15 s", color = C.Text, fontWeight = FontWeight.Bold) }
                TextButton(onClick = onSkip) { Text(if (done) "Close" else "Skip", color = C.Muted) }
            }
            if (!done) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { (1f - left / (total * 1000f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = C.Accent,
                    trackColor = C.Raised,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}
