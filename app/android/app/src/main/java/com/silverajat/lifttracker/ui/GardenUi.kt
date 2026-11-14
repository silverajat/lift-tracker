package com.silverajat.lifttracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silverajat.lifttracker.R
import com.silverajat.lifttracker.garden.ActiveFocus
import com.silverajat.lifttracker.garden.Area
import com.silverajat.lifttracker.garden.FocusSession
import com.silverajat.lifttracker.garden.Garden
import com.silverajat.lifttracker.garden.PixelPlant
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.floor
import kotlin.math.min

/** Pixel fonts for the garden (both SIL Open Font License, see docs/fonts). */
val PixelFont = FontFamily(Font(R.font.silkscreen))
val PixelTitle = FontFamily(Font(R.font.press_start))
private fun px(size: Int, color: Color = C.Text) = TextStyle(fontFamily = PixelFont, fontSize = size.sp, color = color, lineHeight = (size + 4).sp)
private fun pxTitle(size: Int, color: Color = C.Text) = TextStyle(fontFamily = PixelTitle, fontSize = size.sp, color = color, lineHeight = (size + 6).sp)
private val Ink = Color(0xFF0B0D10)

/** Each area's colour, so the six plants are easy to tell apart. */
fun areaColor(a: Area): Color = Color(PixelPlant.palette(a, false)[2])

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

/** A chunky pixel button: flat colour, black border and a hard drop shadow. */
@Composable
private fun PixelButton(label: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(label, style = px(15, Ink), textAlign = TextAlign.Center,
        modifier = modifier
            .drawBehind { drawRect(Ink, Offset(4.dp.toPx(), 4.dp.toPx()), size) }
            .background(color).border(2.dp, Ink, RectangleShape)
            .clickable(onClick = onClick).padding(vertical = 14.dp))
}

@Composable
private fun FocusCard(g: GardenUi, act: GardenActions) {
    val a = g.active
    if (a == null) {
        var what by rememberSaveable { mutableStateOf("") }
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("FOCUS", style = pxTitle(14))
                OutlinedTextField(what, { what = it }, placeholder = { Text("What on? (optional, e.g. EE2023)", color = C.Muted) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                    PixelButton("Study now", areaColor(Area.STUDY), Modifier.weight(1f)) { act.start(Area.STUDY, what); what = "" }
                    PixelButton("Work now", areaColor(Area.WORK), Modifier.weight(1f)) { act.start(Area.WORK, what); what = "" }
                }
            }
        }
        return
    }
    val p = g.plants.first { it.area == a.area }
    val s = a.focusedS(g.now)
    var confirm by remember { mutableStateOf(false) }
    // LEVEL UP! for a few seconds when the plant levels up while you focus
    var seenLevel by remember(a.startMs) { mutableIntStateOf(p.level) }
    var levelUpUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(p.level) { if (p.level > seenLevel) { levelUpUntil = System.currentTimeMillis() + 5000; seenLevel = p.level } }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, containerColor = C.Surface,
        title = { Text("Discard this block?") }, text = { Text("It won't count towards your ${a.area.label.lowercase()} plant.") },
        confirmButton = { TextButton(onClick = { act.discard(); confirm = false }) { Text("Discard", color = C.Stop) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep going", color = C.Muted) } })
    Card(Modifier.fillMaxWidth(), border = areaColor(a.area).copy(alpha = 0.7f)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Text((if (a.area == Area.STUDY) "STUDYING" else "WORKING") + if (a.label.isNotBlank()) ": ${a.label}" else "",
                style = px(14, C.Muted), textAlign = TextAlign.Center)
            Box(contentAlignment = Alignment.TopCenter) {
                PixelPlantArt(a.area, p.level, p.progress, dry = false, focusing = true, modifier = Modifier.fillMaxWidth().height(230.dp))
                if (g.now < levelUpUntil) Text("LEVEL UP!", style = pxTitle(16, Plate.Yellow), modifier = Modifier.padding(top = 6.dp))
            }
            Text(focusClock(s), style = pxTitle(30))
            Text("${a.area.label.uppercase()} LV ${p.level}", style = px(14, areaColor(a.area)))
            PixelBar(p.progress, areaColor(a.area), Modifier.fillMaxWidth(0.8f))
            if (a.awayS >= 60) Text("Paused ${a.awayS / 60} min while you were in other apps", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                PixelButton("Stop + save", areaColor(a.area), Modifier.weight(1f)) { act.stop() }
                PixelButton("Discard", C.Muted, Modifier.weight(1f)) { confirm = true }
            }
        }
    }
}

private fun focusClock(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)

/** Progress as 10 chunky blocks. */
@Composable
private fun PixelBar(f: Float, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.height(10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        val on = (f * 10).toInt().coerceIn(0, 10)
        repeat(10) { i -> Box(Modifier.weight(1f).height(10.dp).background(if (i < on) color else C.Raised)) }
    }
}

@Composable
private fun PlantCard(p: Garden.Plant, modifier: Modifier) {
    val dry = p.dryDays >= 4
    Card(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PixelPlantArt(p.area, p.level, p.progress, dry = dry, focusing = false, modifier = Modifier.fillMaxWidth().height(124.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.area.label.uppercase(), style = px(13, areaColor(p.area)), modifier = Modifier.weight(1f))
                Text("LV ${p.level}", style = px(13))
            }
            PixelBar(p.progress, areaColor(p.area))
            Text(p.week, style = MaterialTheme.typography.bodySmall.merge(Tnum), color = C.Muted, maxLines = 1)
            if (dry && p.dryDays < 99) Text("DRY ${p.dryDays} DAYS", style = px(11, Plate.Yellow))
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
        Text("FOCUS LOG", style = pxTitle(13), modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ss.forEach { s ->
                    val t = Instant.ofEpochMilli(s.startMs).atZone(z)
                    Row(Modifier.fillMaxWidth().clickable { del = s }, verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(end = 12.dp).size(8.dp, 26.dp).background(areaColor(s.area)))
                        Column(Modifier.weight(1f)) {
                            Text(s.label.ifBlank { s.area.label }, style = MaterialTheme.typography.bodyLarge)
                            Text((if (t.toLocalDate() == today) "Today " else t.format(dD) + " ") + t.format(dT),
                                style = MaterialTheme.typography.bodySmall, color = C.Muted)
                        }
                        Text(Garden.hm(s.minutes), style = px(14, areaColor(s.area)))
                    }
                }
            }
        }
    }
}

// ---- the pixel plants -------------------------------------------------------------

/**
 * A pixel-art plant, drawn cell by cell. It animates at 4 frames a second: the
 * top sways, leaves or petals drift down, and sparkles rise while you focus.
 */
@Composable
fun PixelPlantArt(area: Area, level: Int, progress: Float, dry: Boolean, focusing: Boolean, modifier: Modifier = Modifier) {
    val sprite = remember(area, level, (progress * 20).toInt(), dry) { PixelPlant.sprite(area, level, progress, dry) }
    val frame by produceState(0) { while (true) { delay(250); value++ } }
    val sway = intArrayOf(0, 0, 1, 1, 0, 0, -1, -1)[(frame / (if (focusing) 1 else 2)) % 8]
    val parts = PixelPlant.particles(area, frame, focusing, dry)
    Canvas(modifier) {
        val cell = floor(min(size.width / PixelPlant.W, size.height / PixelPlant.H))
        val ox = floor((size.width - cell * PixelPlant.W) / 2); val oy = size.height - cell * PixelPlant.H
        val sz = Size(cell, cell)
        for (y in 0 until PixelPlant.H) {
            val shift = if (y < PixelPlant.GROUND * 0.55f) sway else 0
            val row = sprite.c[y]
            for (x in 0 until PixelPlant.W) {
                val c = row[x]
                if (c != 0) drawRect(Color(c), Offset(ox + (x + shift) * cell, oy + y * cell), sz)
            }
        }
        parts.forEach { (x, y, c) -> if (y in 0 until PixelPlant.H) drawRect(Color(c), Offset(ox + x * cell, oy + y * cell), sz) }
    }
}
