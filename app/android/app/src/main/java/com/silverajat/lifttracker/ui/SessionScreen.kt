package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
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
    SessionContent(s, history, onBack = { vm.back() }, onShare = { onShare(s) })
}

@Composable
fun SessionContent(s: Session, history: List<Session>, onBack: () -> Unit = {}, onShare: () -> Unit = {}) {
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
    val groups = s.sets.groupBy { it.exerciseId }.toList()
        .sortedBy { (exId, _) -> Routines.forDay(s.day).indexOfFirst { it.id == exId }.let { if (it < 0) 99 else it } }

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
