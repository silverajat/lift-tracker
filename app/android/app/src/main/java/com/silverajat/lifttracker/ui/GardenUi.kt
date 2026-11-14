package com.silverajat.lifttracker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.silverajat.lifttracker.garden.ActiveFocus
import com.silverajat.lifttracker.garden.Area
import com.silverajat.lifttracker.garden.FocusSession
import com.silverajat.lifttracker.garden.Garden
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Each area's colour, so the six plants are easy to tell apart. */
fun areaColor(a: Area): Color = when (a) {
    Area.STUDY -> Color(0xFF3DD6B5)   // bamboo green-teal
    Area.WORK -> Color(0xFF4F9D69)    // pine green
    Area.GYM -> Color(0xFFFF8A3D)     // maple orange
    Area.SPORTS -> Color(0xFFC9DB4A)  // palm lime
    Area.SLEEP -> Color(0xFFA78BFA)   // wisteria violet
    Area.MIND -> Color(0xFFF59EC4)    // cherry pink
}

fun areaEmoji(a: Area) = when (a) {
    Area.STUDY -> "📚"; Area.WORK -> "💼"; Area.GYM -> "🏋️"; Area.SPORTS -> "🏸"; Area.SLEEP -> "🌙"; Area.MIND -> "🧠"
}

data class GardenUi(
    val plants: List<Garden.Plant>,
    val active: ActiveFocus?,
    val sessions: List<FocusSession>,
    val now: Long,
)

class GardenActions(
    val start: (Area, String) -> Unit = { _, _ -> },
    val stop: () -> Unit = {},
    val discard: () -> Unit = {},
    val delete: (String) -> Unit = {},
)

private val dT = DateTimeFormatter.ofPattern("H:mm")
private val dD = DateTimeFormatter.ofPattern("EEE d MMM")

fun LazyListScope.gardenTab(g: GardenUi, act: GardenActions) {
    item { FocusCard(g, act) }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            g.plants.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { p -> PlantCard(p, Modifier.weight(1f)) }
                }
            }
        }
    }
    if (g.sessions.isNotEmpty()) item { FocusLog(g.sessions.take(12), act) }
}

@Composable
private fun FocusCard(g: GardenUi, act: GardenActions) {
    val a = g.active
    if (a == null) {
        var what by rememberSaveable { mutableStateOf("") }
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Focus", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(what, { what = it }, placeholder = { Text("What on? (optional, e.g. EE2023)", color = C.Muted) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(Area.STUDY to "📚 Study now", Area.WORK to "💼 Work now").forEach { (ar, label) ->
                        Text(label, style = MaterialTheme.typography.titleMedium, color = C.Bg, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(areaColor(ar))
                                .clickable { act.start(ar, what); what = "" }.padding(vertical = 14.dp))
                    }
                }
            }
        }
        return
    }
    val p = g.plants.first { it.area == a.area }
    val s = a.focusedS(g.now)
    var confirm by remember { mutableStateOf(false) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Discard this block?") }, text = { Text("It won't count towards your ${a.area.label.lowercase()} plant.") },
        confirmButton = { TextButton(onClick = { act.discard(); confirm = false }) { Text("Discard", color = C.Stop) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going", color = C.Muted) } })
    Card(Modifier.fillMaxWidth(), border = areaColor(a.area).copy(alpha = 0.6f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("${areaEmoji(a.area)} ${if (a.area == Area.STUDY) "Studying" else "Working"}" + if (a.label.isNotBlank()) ": ${a.label}" else "",
                style = MaterialTheme.typography.titleMedium, color = C.Muted)
            PlantArt(a.area, p.level, p.progress, dry = false, growing = true, modifier = Modifier.fillMaxWidth().height(200.dp))
            Text(focusClock(s), style = MaterialTheme.typography.displayMedium.merge(Tnum))
            Text("${a.area.label} Lv ${p.level} · ${(p.progress * 100).toInt()}% to Lv ${p.level + 1}", style = MaterialTheme.typography.bodyMedium, color = areaColor(a.area))
            if (a.awayS >= 60) Text("Paused ${a.awayS / 60} min while you were in other apps", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text("Stop and save", style = MaterialTheme.typography.titleMedium, color = C.Accent, modifier = Modifier.clickable { act.stop() })
                Text("Discard", style = MaterialTheme.typography.titleMedium, color = C.Muted, modifier = Modifier.clickable { confirm = true })
            }
        }
    }
}

private fun focusClock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

@Composable
private fun PlantCard(p: Garden.Plant, modifier: Modifier) {
    val dry = p.dryDays >= 4
    Card(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlantArt(p.area, p.level, p.progress, dry = dry, growing = false, modifier = Modifier.fillMaxWidth().height(120.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.area.label, style = MaterialTheme.typography.titleMedium, color = areaColor(p.area), modifier = Modifier.weight(1f))
                Text("Lv ${p.level}", style = MaterialTheme.typography.titleSmall.merge(Tnum))
            }
            Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(C.Raised)) {
                Box(Modifier.fillMaxWidth(p.progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(areaColor(p.area)))
            }
            Text(p.week, style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted, maxLines = 1)
            if (dry && p.dryDays < 99) Text("dry ${p.dryDays} days", style = MaterialTheme.typography.labelSmall, color = Plate.Yellow)
        }
    }
}

@Composable
private fun FocusLog(ss: List<FocusSession>, act: GardenActions) {
    var del by remember { mutableStateOf<FocusSession?>(null) }
    del?.let { s ->
        AlertDialog(onDismissRequest = { del = null }, containerColor = C.Surface, title = { Text("Delete this block?") },
            confirmButton = { TextButton(onClick = { act.delete(s.id); del = null }) { Text("Delete", color = C.Stop) } },
            dismissButton = { TextButton(onClick = { del = null }) { Text("Cancel", color = C.Muted) } })
    }
    val z = ZoneId.systemDefault()
    val today = LocalDate.now(z)
    Column {
        Text("Focus log", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ss.forEach { s ->
                    val t = Instant.ofEpochMilli(s.startMs).atZone(z)
                    Row(Modifier.fillMaxWidth().clickable { del = s }, verticalAlignment = Alignment.CenterVertically) {
                        Text(areaEmoji(s.area), modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.label.ifBlank { s.area.label }, style = MaterialTheme.typography.bodyLarge)
                            Text((if (t.toLocalDate() == today) "Today " else t.format(dD) + " ") + t.format(dT),
                                style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                        Text(Garden.hm(s.minutes), style = MaterialTheme.typography.titleMedium.merge(Tnum), color = areaColor(s.area))
                    }
                }
            }
        }
    }
}

// ---- the plants -------------------------------------------------------------------

private val Bark = Color(0xFF7A5536)
private val DryLeaf = Color(0xFFA0825A)

/** A plant drawn from its level: bigger and fuller as you level up, dry if neglected. */
@Composable
fun PlantArt(area: Area, level: Int, progress: Float, dry: Boolean, growing: Boolean, modifier: Modifier = Modifier) {
    val sway = if (growing) {
        val t = rememberInfiniteTransition(label = "sway")
        t.animateFloat(-1.6f, 1.6f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "sway").value
    } else 0f
    val leaf = if (dry) DryLeaf else areaColor(area)
    Canvas(modifier.clipToBounds().rotate(sway)) {
        val potW = size.width * 0.34f; val potH = size.height * 0.16f
        val base = Offset(size.width / 2, size.height - potH)
        val grow = (0.35f + 0.65f * min(1f, (level - 1 + progress) / 9f))   // 35% at Lv 1 to full size at Lv 10
        val h = (size.height - potH) * 0.92f * grow
        val rnd = Random(area.ordinal * 7919L + level)
        when (area) {
            Area.STUDY -> bamboo(base, h, level, leaf, dry, rnd)
            Area.WORK -> pine(base, h, level, leaf)
            Area.SPORTS -> palm(base, h, level, leaf, rnd)
            Area.GYM -> tree(base, h, level, leaf, rnd, spread = 0.55f, style = 0)
            Area.SLEEP -> tree(base, h, level, leaf, rnd, spread = 0.75f, style = 1)
            Area.MIND -> tree(base, h * 0.85f, level, leaf, rnd, spread = 0.95f, style = 2)
        }
        // pot
        val pot = Path().apply {
            moveTo(base.x - potW / 2, base.y); lineTo(base.x + potW / 2, base.y)
            lineTo(base.x + potW * 0.38f, size.height); lineTo(base.x - potW * 0.38f, size.height); close()
        }
        drawPath(pot, C.Raised)
        drawRect(areaColor(area).copy(alpha = 0.8f), Offset(base.x - potW / 2 - 2, base.y - 4), Size(potW + 4, 6f))
    }
}

/** Stalks with nodes and a few leaves; more stalks as you level up. */
private fun DrawScope.bamboo(base: Offset, h: Float, level: Int, leaf: Color, dry: Boolean, rnd: Random) {
    val n = min(1 + level / 2, 5)
    for (i in 0 until n) {
        val x = base.x + (i - (n - 1) / 2f) * 9f
        val sh = h * (0.7f + 0.3f * rnd.nextFloat())
        val segs = 3 + level / 3
        val stalk = if (dry) DryLeaf else lerp(leaf, Color(0xFF1E7F64), 0.3f)
        drawLine(stalk, Offset(x, base.y), Offset(x, base.y - sh), 5f, StrokeCap.Round)
        for (k in 1..segs) {
            val y = base.y - sh * k / (segs + 1)
            drawLine(Color.Black.copy(alpha = 0.25f), Offset(x - 3, y), Offset(x + 3, y), 1.5f)
            if (rnd.nextFloat() < 0.6f) {
                val dir = if (rnd.nextBoolean()) 1 else -1
                drawLine(leaf, Offset(x, y), Offset(x + dir * 14f, y - 6f), 4f, StrokeCap.Round)
            }
        }
    }
}

/** Stacked tiers on a trunk; a tier per level. */
private fun DrawScope.pine(base: Offset, h: Float, level: Int, leaf: Color) {
    drawLine(Bark, base, Offset(base.x, base.y - h), 6f, StrokeCap.Round)
    val tiers = min(1 + level, 8)
    for (k in 0 until tiers) {
        val y = base.y - h * (0.25f + 0.75f * k / tiers)
        val w = h * 0.42f * (1f - k.toFloat() / (tiers + 1))
        val tier = Path().apply { moveTo(base.x - w, y); lineTo(base.x + w, y); lineTo(base.x, y - h * 0.24f); close() }
        drawPath(tier, lerp(leaf, Color.Black, 0.12f * (k % 2)))
    }
}

/** A curved trunk with fronds; more fronds as you level up. */
private fun DrawScope.palm(base: Offset, h: Float, level: Int, leaf: Color, rnd: Random) {
    val top = Offset(base.x + h * 0.12f, base.y - h)
    val trunk = Path().apply { moveTo(base.x, base.y); quadraticTo(base.x - h * 0.08f, base.y - h * 0.5f, top.x, top.y) }
    drawPath(trunk, Bark, style = Stroke(7f, cap = StrokeCap.Round))
    val fronds = min(3 + level, 10)
    for (i in 0 until fronds) {
        val ang = Math.PI * (0.1 + 0.8 * i / (fronds - 1).coerceAtLeast(1)) + (rnd.nextFloat() - 0.5) * 0.15
        val len = h * (0.32f + 0.08f * rnd.nextFloat())
        val end = Offset(top.x + (cos(ang) * len).toFloat(), top.y - (sin(ang) * len * 0.45f).toFloat() + len * 0.25f)
        val ctrl = Offset(top.x + (cos(ang) * len * 0.5f).toFloat(), top.y - (sin(ang) * len * 0.6f).toFloat())
        drawPath(Path().apply { moveTo(top.x, top.y); quadraticTo(ctrl.x, ctrl.y, end.x, end.y) }, leaf, style = Stroke(5f, cap = StrokeCap.Round))
    }
}

/**
 * A branching tree. style 0: round canopy (maple), 1: hanging clusters (wisteria), 2: blossoms (cherry).
 * Deeper branching as you level up.
 */
private fun DrawScope.tree(base: Offset, h: Float, level: Int, leaf: Color, rnd: Random, spread: Float, style: Int) {
    val depth = min(1 + (level + 1) / 2, 5)
    fun branch(p: Offset, ang: Double, len: Float, d: Int, w: Float) {
        val end = Offset(p.x + (cos(ang) * len).toFloat(), p.y - (sin(ang) * len).toFloat())
        drawLine(Bark, p, end, w, StrokeCap.Round)
        if (d == 0) {
            when (style) {
                0 -> { drawCircle(leaf, len * 0.75f + 6f, end); drawCircle(lerp(leaf, Color.White, 0.18f), len * 0.4f + 3f, Offset(end.x - 3, end.y - 3)) }
                1 -> for (k in 0..3) drawCircle(leaf.copy(alpha = 0.9f - k * 0.15f), 5f - k * 0.6f, Offset(end.x, end.y + k * 6f))
                else -> repeat(5) { drawCircle(leaf, 4.5f, Offset(end.x + (rnd.nextFloat() - 0.5f) * 18f, end.y + (rnd.nextFloat() - 0.5f) * 14f)) }
            }
            return
        }
        val kids = if (rnd.nextFloat() < 0.35f) 3 else 2
        for (k in 0 until kids) {
            val off = (k - (kids - 1) / 2.0) * spread + (rnd.nextFloat() - 0.5) * 0.25
            branch(end, ang + off, len * (0.68f + 0.1f * rnd.nextFloat()), d - 1, (w * 0.7f).coerceAtLeast(1.5f))
        }
    }
    branch(base, Math.PI / 2, h * 0.34f, depth, 7f)
}
