package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.data.Stats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val headFmt = DateTimeFormatter.ofPattern("EEEE d MMMM")
private val clockFmt = DateTimeFormatter.ofPattern("H:mm")

/** A past workout: every exercise and set, what the pod measured, and what was a personal best. */
@Composable
fun SessionScreen(vm: AppViewModel, id: Long, onShare: (Session) -> Unit) {
    val history by vm.history.collectAsStateWithLifecycle()
    val s = history.firstOrNull { it.id == id }
    if (s == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Workout", "Deleted", onBack = { vm.back() })
        }
        return
    }
    SessionContent(s, history, onBack = { vm.back() }, onShare = { onShare(s) }, onFix = { vm.fixPast(s.id, it) })
}

@Composable
fun SessionContent(s: Session, history: List<Session>, onBack: () -> Unit = {}, onShare: () -> Unit = {},
                   onFix: ((List<Pair<SetLog, SetLog?>>) -> Unit)? = null) {
    var fixing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(s.startMs).atZone(zone)
    val end = s.endMs?.let { Instant.ofEpochMilli(it).atZone(zone) }
    val work = s.sets.filter { !it.warmup }
    fun vol(l: SetLog) = l.weight * l.reps * (if (Routines.isOneArm(l.exerciseId)) 2 else 1)
    val volume = work.sumOf { vol(it) }
    val quality = work.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()
    // best estimated 1RM per exercise before this session, to mark PRs
    val before = history.filter { it.id != s.id && it.startMs < s.startMs }.flatMap { it.sets }.filter { !it.warmup }
        .groupBy { it.exerciseId }.mapValues { (_, v) -> v.maxOf { Stats.oneRm(it.weight, it.reps) } }
    // the last time you did each exercise, and the last workout of the same day type
    val earlier = history.filter { it.id != s.id && it.startMs < s.startMs && it.sets.isNotEmpty() }.sortedByDescending { it.startMs }
    val lastSame = earlier.firstOrNull { it.day == s.day }
    fun lastTime(exId: Int) = earlier.firstOrNull { e -> e.sets.any { it.exerciseId == exId && !it.warmup } }
    val groups = s.sets.groupBy { it.exerciseId }.toList()
        .sortedBy { (exId, _) -> Routines.forDay(s.day).indexOfFirst { it.id == exId }.let { if (it < 0) 99 else it } }

    fixing?.let { exId ->
        FixDialog(s.sets.filter { it.exerciseId == exId }.sortedBy { it.setNo },
            runCatching { Routines.byId(exId).name }.getOrDefault("Exercise"), onDismiss = { fixing = null }) { ch ->
            onFix?.invoke(ch); fixing = null
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("${s.day.title} day", start.format(headFmt), onBack) {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share", tint = C.Muted) }
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    Big(end?.let { elapsed(s.endMs!! - s.startMs) } ?: "–", "${start.format(clockFmt)} to ${end?.format(clockFmt) ?: "not finished"}")
                    Big("%,d".format(volume.roundToInt()), "kg moved")
                    Big("${work.size}", "working sets")
                    quality?.let { Big("$it", "rep quality", C.score(it)) }
                }
            }
            if (lastSame != null) item {
                CompareCard(s, lastSame, groups.map { it.first }.mapNotNull { id -> lastTime(id)?.let { id to it } }.toMap(), ::vol)
            }
            if (s.sets.isNotEmpty()) item {
                val ml = com.silverajat.lifttracker.health.MuscleLoad.score(s, history)
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Muscular strain", style = MaterialTheme.typography.titleSmall)
                            Text(muscleVerdict(ml.strain), style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        }
                        Text("%.1f".format(ml.strain), style = MaterialTheme.typography.displaySmall.merge(Tnum), color = StrainBlue)
                    }
                }
            }
            items(groups, key = { it.first }) { (exId, sets) ->
                val ex = runCatching { Routines.byId(exId) }.getOrNull()
                val oneArm = ex != null && Routines.isOneArm(ex)
                val best = sets.filter { !it.warmup }.maxOfOrNull { Stats.oneRm(it.weight, it.reps) }
                val pr = best != null && before[exId]?.let { best > it + 0.01 } == true
                Card(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ex?.name ?: "Exercise $exId", style = MaterialTheme.typography.titleLarge)
                                Text(listOfNotNull(ex?.let { Routines.group(it).label }, if (oneArm) "reps per arm" else null).joinToString(", "),
                                    style = MaterialTheme.typography.bodySmall, color = C.Muted)
                            }
                            if (pr) Text("PR", style = MaterialTheme.typography.titleSmall, color = Plate.Yellow,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Plate.Yellow.copy(alpha = 0.15f))
                                    .padding(horizontal = 10.dp, vertical = 4.dp))
                            if (onFix != null) Text("Edit", style = MaterialTheme.typography.titleSmall, color = C.Accent,
                                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { fixing = exId }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                        Row(Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                            Text("Set", style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = Modifier.width(42.dp))
                            Text("Weight × reps", style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = Modifier.weight(1f))
                            Text("Quality", style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = Modifier.width(60.dp))
                            Text("ROM", style = MaterialTheme.typography.labelMedium, color = C.Muted, modifier = Modifier.width(48.dp))
                        }
                        sets.sortedBy { it.setNo }.forEachIndexed { i, l ->
                            if (i > 0) HorizontalDivider(color = C.Line, modifier = Modifier.padding(vertical = 8.dp))
                            SetLine(l, oneArm)
                        }
                        if (best != null) Text("Best set ≈ ${best.roundToInt()} kg one-rep max" + (before[exId]?.let { " (before: ${it.roundToInt()} kg)" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = C.Muted, modifier = Modifier.padding(top = 12.dp))
                        lastTime(exId)?.let { prev -> VsLast(sets, prev, exId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetLine(l: SetLog, oneArm: Boolean) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(42.dp)) {
                Text(when { l.calibration -> "C"; l.warmup -> "W"; else -> "${l.setNo}" }, style = MaterialTheme.typography.titleSmall,
                    color = when { l.calibration -> C.Pod; l.warmup -> C.Warm; else -> C.Text })
            }
            Text(buildString {
                append(fmtW(l.weight)); append(" kg")
                if (l.addons > 0) append(" +${l.addons}")
                append(" × ${l.reps}")
                if (oneArm) append(" /arm")
            }, style = MaterialTheme.typography.headlineSmall.merge(Tnum), modifier = Modifier.weight(1f))
            Text(l.quality?.toString() ?: "–", style = MaterialTheme.typography.titleSmall.merge(Tnum),
                color = l.quality?.let { C.score(it) } ?: C.Muted, modifier = Modifier.width(60.dp))
            Text(l.romPct.takeIf { it.isNotEmpty() }?.let { "${it.average().roundToInt()}%" } ?: "–",
                style = MaterialTheme.typography.titleSmall.merge(Tnum), color = if (l.romPct.isEmpty()) C.Muted else C.Text,
                modifier = Modifier.width(48.dp))
        }
        val notes = listOfNotNull(
            l.speedDrop?.takeIf { it >= 15 }?.let { "slowed $it% by the end" },
            l.liveReps?.takeIf { it != l.reps }?.let { "pod counted $it" },
            if (l.endMs > l.startMs) "${((l.endMs - l.startMs) / 1000)} s" else null,
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = C.Muted,
            modifier = Modifier.padding(start = 42.dp, top = 2.dp))
        if (l.repScores.size >= 2) RepDots(l.repScores, Modifier.padding(start = 42.dp, top = 6.dp))
    }
}

/** One dot per rep, coloured by its score: you can see where the set fell apart. */
@Composable
private fun RepDots(scores: List<Int>, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        scores.forEach { Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(C.score(it))) }
    }
}

@Composable
private fun Big(value: String, label: String, color: androidx.compose.ui.graphics.Color = C.Text) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium.merge(Tnum), color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

private fun fmtW(w: Double) = if (w == Math.floor(w)) "%.0f".format(w) else "%.2f".format(w).trimEnd('0').trimEnd('.')

private val dFmt = DateTimeFormatter.ofPattern("EEE d MMM")
private val Up = androidx.compose.ui.graphics.Color(0xFF4CC38A)
private val Down = androidx.compose.ui.graphics.Color(0xFFE5645A)

private fun pct(now: Double, was: Double) = if (was <= 0) 0 else ((now - was) / was * 100).roundToInt()

/** This workout against the last one of the same day type: totals, then how many lifts went up or down. */
@Composable
private fun CompareCard(s: Session, last: Session, lastByEx: Map<Int, Session>, vol: (SetLog) -> Double) {
    val now = s.sets.filter { !it.warmup }; val was = last.sets.filter { !it.warmup }
    val vNow = now.sumOf(vol); val vWas = was.sumOf(vol)
    val qNow = now.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()
    val qWas = was.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.average()
    // per lift: best estimated one-rep max now vs last time (1% either way counts as the same)
    var up = 0; var down = 0; var same = 0
    s.sets.groupBy { it.exerciseId }.forEach { (ex, sets) ->
        val b = sets.filter { !it.warmup }.maxOfOrNull { Stats.oneRm(it.weight, it.reps) } ?: return@forEach
        val p = lastByEx[ex]?.sets?.filter { it.exerciseId == ex && !it.warmup }?.maxOfOrNull { Stats.oneRm(it.weight, it.reps) } ?: return@forEach
        when { b > p * 1.01 -> up++; b < p * 0.99 -> down++; else -> same++ }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("vs last ${last.day.title} day · ${Instant.ofEpochMilli(last.startMs).atZone(ZoneId.systemDefault()).format(dFmt)}",
                style = MaterialTheme.typography.titleSmall, color = C.Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Delta("%,d kg".format(vNow.roundToInt()), "volume", pct(vNow, vWas).let { if (it == 0) "same" else "%+d%%".format(it) }, pct(vNow, vWas))
                Delta("${now.size}", "working sets", (now.size - was.size).let { if (it == 0) "same" else "%+d".format(it) }, now.size - was.size)
                if (qNow != null && qWas != null) Delta("${qNow.roundToInt()}", "quality", (qNow - qWas).roundToInt().let { if (it == 0) "same" else "%+d".format(it) }, (qNow - qWas).roundToInt())
            }
            if (up + down + same > 0) Text(listOfNotNull(
                up.takeIf { it > 0 }?.let { "$it lift${if (it > 1) "s" else ""} stronger" },
                same.takeIf { it > 0 }?.let { "$it the same" },
                down.takeIf { it > 0 }?.let { "$it down" },
            ).joinToString(", ") + " (best set, as an estimated one-rep max)",
                style = MaterialTheme.typography.bodyMedium, color = if (down > up) Down else if (up > 0) Up else C.Text)
        }
    }
}

@Composable
private fun Delta(value: String, label: String, change: String, sign: Int) {
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge.merge(Tnum))
            Text("  $change", style = MaterialTheme.typography.labelMedium.merge(Tnum),
                color = when { sign > 0 -> Up; sign < 0 -> Down; else -> C.Muted }, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = C.Muted)
    }
}

/** Under each lift: what you did last time, and what changed. */
@Composable
private fun VsLast(sets: List<SetLog>, prev: Session, exId: Int) {
    val was = prev.sets.filter { it.exerciseId == exId && !it.warmup }.sortedBy { it.setNo }
    val now = sets.filter { !it.warmup }
    if (was.isEmpty() || now.isEmpty()) return
    fun line(l: List<SetLog>) = l.groupBy { it.weight to it.addons }.entries.joinToString(", ") { (k, v) ->
        fmtW(k.first) + (if (k.second > 0) " +${k.second}" else "") + " × " + v.joinToString(",") { it.reps.toString() }
    }
    val bNow = now.maxBy { Stats.oneRm(it.weight, it.reps) }; val bWas = was.maxBy { Stats.oneRm(it.weight, it.reps) }
    val change = when {
        bNow.weight > bWas.weight -> "+" + fmtW(bNow.weight - bWas.weight) + " kg on the best set"
        bNow.weight < bWas.weight -> "−" + fmtW(bWas.weight - bNow.weight) + " kg on the best set"
        bNow.reps != bWas.reps -> "%+d reps on the best set".format(bNow.reps - bWas.reps)
        else -> "same best set"
    }
    val e = pct(Stats.oneRm(bNow.weight, bNow.reps), Stats.oneRm(bWas.weight, bWas.reps))
    Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.Raised).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Last time (${Instant.ofEpochMilli(prev.startMs).atZone(ZoneId.systemDefault()).format(dFmt)}): ${line(was)}",
            style = MaterialTheme.typography.bodySmall, color = C.Muted)
        Text(change + if (e != 0) " · strength %+d%%".format(e) else "", style = MaterialTheme.typography.titleSmall,
            color = when { e > 0 -> Up; e < 0 -> Down; else -> C.Text })
    }
}

/** Fix what was logged for one lift: weight, add-ons and reps per set, or take a set out. */
@Composable
private fun FixDialog(sets: List<SetLog>, name: String, onDismiss: () -> Unit, onSave: (List<Pair<SetLog, SetLog?>>) -> Unit) {
    val w = androidx.compose.runtime.remember { sets.map { androidx.compose.runtime.mutableStateOf(fmtW(it.weight)) } }
    val r = androidx.compose.runtime.remember { sets.map { androidx.compose.runtime.mutableStateOf(it.reps.toString()) } }
    val gone = androidx.compose.runtime.remember { sets.map { androidx.compose.runtime.mutableStateOf(false) } }
    var all by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Fix $name") },
        text = {
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // one weight for every working set: the usual fix when the wrong weight went in all session
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(all, { v -> all = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                        label = { Text("kg for all working sets") }, singleLine = true, modifier = Modifier.weight(1f),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                    androidx.compose.material3.TextButton(onClick = {
                        if (all.replace(',', '.').toDoubleOrNull() != null) sets.forEachIndexed { i, l -> if (!l.warmup) w[i].value = all }
                    }) { Text("Apply", color = C.Accent) }
                }
                sets.forEachIndexed { i, l ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (l.warmup) "W" else "${l.setNo}", color = if (gone[i].value) C.Muted else if (l.warmup) C.Warm else C.Text,
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(22.dp))
                        androidx.compose.material3.OutlinedTextField(w[i].value, { v -> w[i].value = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                            label = { Text("kg") }, singleLine = true, enabled = !gone[i].value, modifier = Modifier.weight(1f),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
                        androidx.compose.material3.OutlinedTextField(r[i].value, { v -> r[i].value = v.filter { it.isDigit() }.take(3) },
                            label = { Text("reps") }, singleLine = true, enabled = !gone[i].value, modifier = Modifier.width(76.dp),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                        Text(if (gone[i].value) "↺" else "✕", color = C.Muted, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.clickable { gone[i].value = !gone[i].value }.padding(6.dp))
                    }
                }
                if (sets.any { it.addons > 0 }) Text("Add-ons stay as logged.", style = MaterialTheme.typography.bodySmall, color = C.Muted)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val ch = sets.mapIndexedNotNull<SetLog, Pair<SetLog, SetLog?>> { i, l ->
                    if (gone[i].value) l to null
                    else {
                        val kg = w[i].value.replace(',', '.').toDoubleOrNull() ?: l.weight
                        val reps = r[i].value.toIntOrNull()?.takeIf { it > 0 } ?: l.reps
                        if (kg != l.weight || reps != l.reps) l to l.copy(weight = kg, reps = reps) else null
                    }
                }
                onSave(ch)
            }) { Text("Save", color = C.Accent, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}
