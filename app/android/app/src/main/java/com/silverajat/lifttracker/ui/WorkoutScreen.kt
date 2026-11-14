package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.ActiveSet
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.Phase
import com.silverajat.lifttracker.Screen
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Exercise
import com.silverajat.lifttracker.data.PlanSet
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.data.Stats
import com.silverajat.lifttracker.reps.SetAnalysis
import kotlin.math.max

/** "61 +1A × 10": weight, add-on plates (A), reps, short enough for the table. */
fun compact(weight: Double, addons: Int, reps: Int?, bodyweight: Boolean): String {
    val w = if (bodyweight && weight == 0.0) "BW" else kg(weight) + if (addons > 0) " +${addons}A" else ""
    return if (reps != null) "$w × $reps" else w
}

private val calDate = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())

// Table column widths
private const val W_SET = 0.55f
private const val W_PREV = 1.5f
private const val W_KG = 1f
private const val W_REPS = 0.85f
private val ACTION = 44.dp

@Composable
fun WorkoutScreen(vm: AppViewModel, day: Day, onShare: (Session) -> Unit) {
    val session by vm.session.collectAsStateWithLifecycle()
    // History feeds the PREVIOUS column; reading it here recomposes when it changes.
    val history by vm.history.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val live by vm.liveReps.collectAsStateWithLifecycle()
    val analysis by vm.analysis.collectAsStateWithLifecycle()
    val restEndsAt by vm.restEndsAt.collectAsStateWithLifecycle()
    val restS by vm.restSeconds.collectAsStateWithLifecycle()
    val extra by vm.extraRows.collectAsStateWithLifecycle()
    val conn by vm.pod.conn.collectAsStateWithLifecycle()
    val status by vm.pod.status.collectAsStateWithLifecycle()

    val current = session?.takeIf { it.day == day }
    val sets = current?.sets ?: emptyList()
    var confirmFinish by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf<Session?>(null) }
    var removeSet by remember { mutableStateOf<SetLog?>(null) }
    val accentA = if (day == Day.UPPER) C.UpperA else C.LowerA
    val accentB = if (day == Day.UPPER) C.UpperB else C.LowerB

    Column(Modifier.fillMaxSize()) {
        // ---- top bar: back · title · share · Finish ----
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.back() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = C.Text)
            }
            Text("${day.title} day", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (current != null) {
                IconButton(onClick = { onShare(current) }) { Icon(Icons.Filled.Share, "Share workout", tint = C.Muted) }
                Button(
                    onClick = { confirmFinish = true },
                    enabled = phase == Phase.READY,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnAccent),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                ) { Text("Finish", fontWeight = FontWeight.Bold) }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Duration · Volume · Sets
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                        .background(Brush.linearGradient(listOf(accentA.copy(alpha = 0.35f), accentB.copy(alpha = 0.25f))))
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                ) {
                    StatCell("Duration", current?.let { elapsed(now - it.startMs) } ?: "0:00", Modifier.weight(1f))
                    StatCell("Volume", "${Stats.fmt(sets.sumOf { it.weight * it.reps })} kg", Modifier.weight(1f))
                    StatCell("Sets", "${sets.size}", Modifier.weight(0.6f))
                }
            }
            if (conn != PodConn.READY) {
                item { PodBar(conn, status) { vm.pod.connect() } }
            }
            items(Routines.forDay(day), key = { it.id }) { ex ->
                ExerciseCard(
                    vm = vm, ex = ex,
                    plan = remember(history, current?.id, ex.id) { vm.lastTime(ex) },
                    done = sets.filter { it.exerciseId == ex.id },
                    extraRows = extra[ex.id] ?: 0,
                    active = active,
                    canStart = phase == Phase.READY,
                    onRemove = { removeSet = it },
                )
            }
        }

        BottomPanel(vm, phase, active, live, analysis, conn == PodConn.READY, now, restEndsAt, restS)
    }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = C.Surface,
            title = { Text("Finish workout?") },
            text = {
                Text("${sets.size} sets in ${elapsed(now - (current?.startMs ?: now))}. " +
                    "It's saved and becomes \"previous\" for next ${day.title.lowercase()} day.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFinish = false
                    val done = current?.copy(endMs = System.currentTimeMillis())
                    vm.finishWorkout()
                    if (done == null || done.sets.isEmpty()) vm.open(Screen.Home) else finished = done
                }) { Text("Finish", color = C.Accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmFinish = false
                        current?.let { vm.deleteWorkout(it.id) }
                        vm.open(Screen.Home)
                    }) { Text("Delete workout", color = C.Stop) }
                    TextButton(onClick = { confirmFinish = false }) { Text("Keep going", color = C.Muted) }
                }
            },
        )
    }
    finished?.let { s ->
        run {
            AlertDialog(
                onDismissRequest = { finished = null; vm.open(Screen.Home) },
                containerColor = C.Surface,
                title = { Text("Workout saved 💪") },
                text = { Text("${s.sets.size} sets · ${Stats.fmt(s.sets.sumOf { it.weight * it.reps })} kg volume. Send the summary to ChatGPT?") },
                confirmButton = {
                    TextButton(onClick = { onShare(s); finished = null; vm.open(Screen.Home) }) {
                        Text("Share", color = C.Accent, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { finished = null; vm.open(Screen.Home) }) { Text("Done", color = C.Muted) }
                },
            )
        }
    }
    removeSet?.let { s ->
        AlertDialog(
            onDismissRequest = { removeSet = null },
            containerColor = C.Surface,
            title = { Text("Remove set ${s.setNo}?") },
            text = { Text("${compact(s.weight, s.addons, s.reps, false)} will be taken out of today's log.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteSet(s); removeSet = null }) { Text("Remove", color = C.Stop) }
            },
            dismissButton = { TextButton(onClick = { removeSet = null }) { Text("Keep", color = C.Muted) } },
        )
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = C.Text.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleLarge.merge(Tnum), color = C.Text)
    }
}

@Composable
private fun ExerciseCard(
    vm: AppViewModel,
    ex: Exercise,
    plan: List<PlanSet>,
    done: List<SetLog>,
    extraRows: Int,
    active: ActiveSet?,
    canStart: Boolean,
    onRemove: (SetLog) -> Unit,
) {
    val rows = max(plan.size, done.maxOfOrNull { it.setNo } ?: 0) + extraRows
    val group = Routines.group(ex)
    val gc = C.group(group)
    val complete = done.size >= plan.size && plan.isNotEmpty()
    val shape = RoundedCornerShape(22.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(C.Surface)
            .border(1.dp, if (complete) C.Done.copy(alpha = 0.5f) else C.Line, shape)
            .padding(top = 14.dp, bottom = 8.dp),
    ) {
        // Header: badge · name (opens the exercise page) · group and rest
        Row(
            Modifier.fillMaxWidth().clickable { vm.open(Screen.ExerciseInfo(ex.id)) }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(gc.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                if (complete) Icon(Icons.Filled.Check, null, tint = C.Done)
                else Text(ex.name.split(" ").take(2).joinToString("") { it.take(1) }.uppercase(),
                    style = MaterialTheme.typography.titleMedium, color = gc)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ex.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(group.label, ex.detail.ifEmpty { null },
                        if (Routines.isOneArm(ex)) (if (Routines.podSideFirst(ex.id)) "pod side first" else "one arm at a time") else null,
                        "rest ${clock(Routines.restSeconds(ex) * 1000L)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                )
            }
            Icon(Icons.Filled.ChevronRight, "Progress", tint = C.Muted)
        }
        NoteLine(vm, ex.id, Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("SET", W_SET)
            HeaderCell("PREVIOUS", W_PREV)
            HeaderCell(if (ex.bodyweight) "+KG" else "KG", W_KG)
            HeaderCell("REPS", W_REPS)
            Spacer(Modifier.width(ACTION))
        }
        var working = 0
        var addonEdit by remember { mutableStateOf<androidx.compose.runtime.MutableIntState?>(null) }
        for (i in 0 until rows) key(ex.id, i) {
            val p = plan.getOrNull(i)
            val d = done.firstOrNull { it.setNo == i + 1 }
            val isActive = active != null && active.exerciseId == ex.id && active.setNo == i + 1
            // Set type: 0 working, 1 warm-up (W), 2 ROM calibration (C). Tap the label to cycle.
            val kind = remember(ex.id, i, d) {
                mutableIntStateOf(
                    when {
                        d?.calibration == true -> 2
                        d?.warmup ?: p?.warmup ?: false -> 1
                        else -> 0
                    }
                )
            }
            if (kind.intValue == 0) working++
            val label = when (kind.intValue) { 1 -> "W"; 2 -> "C"; else -> "$working" }
            val fallback = p ?: plan.lastOrNull()
            val weightText = remember(ex.id, i, d) {
                mutableStateOf(d?.let { kg(it.weight) } ?: fallback?.let { kg(it.weight) } ?: "")
            }
            val addons = remember(ex.id, i, d) { mutableIntStateOf(d?.addons ?: fallback?.addons ?: 0) }
            SetRow(
                label = label,
                kind = kind.intValue,
                previous = p?.let { compact(it.weight, it.addons, it.reps, ex.bodyweight) } ?: "–",
                weight = weightText.value,
                onWeight = { weightText.value = it },
                addons = addons.intValue,
                onAddons = { if (d == null && !isActive) addonEdit = addons },
                reps = d?.reps?.toString(),
                repsHint = p?.reps?.toString() ?: "",
                quality = d?.quality,
                done = d != null,
                active = isActive,
                canStart = canStart && d == null,
                onToggleWarm = { if (d == null && !isActive) kind.intValue = (kind.intValue + 1) % 3 },
                onCopyPrev = { p?.let { weightText.value = kg(it.weight); addons.intValue = it.addons } },
                onStart = {
                    val w = weightText.value.replace(',', '.').toDoubleOrNull() ?: 0.0
                    vm.requestSet(ex, i + 1, w, addons.intValue,
                        warmup = kind.intValue >= 1, calibration = kind.intValue == 2)
                },
                onDone = { d?.let(onRemove) },
            )
        }
        addonEdit?.let { st ->
            AlertDialog(
                onDismissRequest = { addonEdit = null },
                containerColor = C.Surface,
                title = { Text("Add-ons") },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("Add-on weights you put on the stack for this set (not counted in kg or volume).",
                            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                            RoundBtn(Icons.Filled.Remove) { st.intValue = max(0, st.intValue - 1) }
                            Text("${st.intValue}", style = MaterialTheme.typography.displayMedium.merge(Tnum),
                                textAlign = TextAlign.Center, modifier = Modifier.width(96.dp))
                            RoundBtn(Icons.Filled.Add) { st.intValue = minOf(9, st.intValue + 1) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { addonEdit = null }) { Text("Done", color = C.Accent, fontWeight = FontWeight.Bold) }
                },
            )
        }
        TextButton(
            onClick = { vm.addRow(ex.id) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Filled.Add, null, tint = C.Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add set", color = C.Muted, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RowScope.HeaderCell(text: String, w: Float) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = C.Muted,
        textAlign = TextAlign.Center, modifier = Modifier.weight(w),
    )
}

@Composable
private fun SetRow(
    label: String,
    kind: Int,
    previous: String,
    weight: String,
    onWeight: (String) -> Unit,
    addons: Int,
    onAddons: () -> Unit,
    reps: String?,
    repsHint: String,
    quality: Int?,
    done: Boolean,
    active: Boolean,
    canStart: Boolean,
    onToggleWarm: () -> Unit,
    onCopyPrev: () -> Unit,
    onStart: () -> Unit,
    onDone: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val bg = when {
        done -> C.Done.copy(alpha = 0.13f)
        active -> C.Accent.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp).clip(shape).background(bg)
            .then(if (active) Modifier.border(1.dp, C.Accent, shape) else Modifier)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(W_SET), contentAlignment = Alignment.Center) {
            val kc = when (kind) { 1 -> C.Warm; 2 -> C.Pod; else -> C.Text }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                color = kc,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (kind != 0) kc.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable(onClick = onToggleWarm).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Text(
            previous,
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = C.Muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(W_PREV).clickable(onClick = onCopyPrev).padding(vertical = 6.dp),
        )
        Column(Modifier.weight(W_KG).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (done || active) {
                Text(weight, style = MaterialTheme.typography.titleMedium.merge(Tnum), color = C.Text)
            } else {
                BasicTextField(
                    value = weight,
                    onValueChange = { v -> onWeight(v.filter { it.isDigit() || it == '.' || it == ',' }.take(6)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.titleMedium.merge(Tnum)
                        .copy(color = C.Text, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(C.Accent),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(C.Raised).padding(vertical = 8.dp),
                )
            }
            // Add-ons: tap to set how many you put on
            Text(
                if (addons > 0) "+$addons add-on" + (if (addons > 1) "s" else "") else "+ add-on",
                style = MaterialTheme.typography.labelMedium,
                color = if (addons > 0) C.Warm else C.Muted.copy(alpha = 0.7f),
                maxLines = 1,
                modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(6.dp))
                    .then(if (!done && !active) Modifier.clickable(onClick = onAddons) else Modifier)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Column(Modifier.weight(W_REPS), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                reps ?: repsHint,
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                color = if (reps != null) C.Text else C.Muted.copy(alpha = 0.55f),
            )
            if (quality != null) {
                Text("Q $quality", style = MaterialTheme.typography.labelMedium.merge(Tnum), color = C.score(quality))
            }
        }
        Box(Modifier.size(ACTION), contentAlignment = Alignment.Center) {
            when {
                done -> Box(
                    Modifier.size(34.dp).clip(CircleShape).background(C.Done).clickable(onClick = onDone),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, "Done", tint = Color.Black, modifier = Modifier.size(20.dp)) }
                active -> Box(Modifier.size(14.dp).clip(CircleShape).background(C.Stop))
                else -> Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (canStart) C.Accent else C.Raised)
                        .clickable(enabled = canStart, onClick = onStart),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, "Start set", tint = if (canStart) C.OnAccent else C.Muted) }
            }
        }
    }
}

// ---- bottom panel ----------------------------------------------------------------

@Composable
private fun BottomPanel(
    vm: AppViewModel, phase: Phase, active: ActiveSet?, live: Int, analysis: SetAnalysis?,
    podReady: Boolean, now: Long, restEndsAt: Long?, restS: Int,
) {
    val ex = active?.let { Routines.byId(it.exerciseId) }
    val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
    val show = phase != Phase.READY || restEndsAt != null
    if (!show) return
    val bg = if (phase == Phase.LIFTING)
        Brush.verticalGradient(listOf(Color(0xFF1B2E5C), C.Surface)) else SolidColor(C.Surface)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(bg)
            .border(1.dp, C.Line, shape).navigationBarsPadding().padding(18.dp),
    ) {
        when (phase) {
            Phase.COUNTDOWN -> {
                val endsAt by vm.countdownEndsAt.collectAsStateWithLifecycle()
                val total by vm.countdownSeconds.collectAsStateWithLifecycle()
                val left = (((endsAt ?: now) - now + 999) / 1000).coerceIn(1, total.toLong())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${ex?.name ?: ""} · set ${active?.setNo ?: ""}",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (active?.auto == true) {
                            Text("Next set starts by itself" + if (active.warmup) " · warm-up" else "",
                                style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { vm.adjustPending(-2.5) }) { Text("−2.5", color = C.Text, fontWeight = FontWeight.Bold) }
                                Text(compact(active.weight, active.addons, null, ex?.bodyweight == true) + " kg",
                                    style = MaterialTheme.typography.titleMedium.merge(Tnum))
                                TextButton(onClick = { vm.adjustPending(2.5) }) { Text("+2.5", color = C.Text, fontWeight = FontWeight.Bold) }
                            }
                        } else {
                            Text("Get into position…", style = MaterialTheme.typography.bodyLarge, color = C.Muted)
                        }
                        Row {
                            TextButton(onClick = { vm.cancelCountdown() }) { Text("Cancel", color = C.Muted) }
                            TextButton(onClick = { vm.goNow() }) { Text("Go now", color = C.Accent, fontWeight = FontWeight.Bold) }
                        }
                    }
                    Box(
                        Modifier.size(108.dp).clip(CircleShape).background(C.Accent).clickable { vm.goNow() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("$left", style = MaterialTheme.typography.displayMedium.merge(Tnum), color = Color.White)
                    }
                }
            }
            Phase.LIFTING -> {
                val paused by vm.paused.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${ex?.name ?: ""} · set ${active?.setNo ?: ""}",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (active?.let { compact(it.weight, it.addons, null, ex?.bodyweight == true) + " kg" } ?: "") +
                                (if (active?.calibration == true) " · calibration: slow, full stretch"
                                else if (active?.warmup == true) " · warm-up" else "") +
                                "  ·  " + elapsed(now - (active?.startMs ?: now) - vm.pausedMs(now)),
                            style = MaterialTheme.typography.bodyLarge.merge(Tnum), color = C.Muted,
                        )
                        if (ex != null && !Routines.podCounts(ex.id)) {
                            Text("Count yourself: your wrist stays still on this one", style = MaterialTheme.typography.bodyMedium,
                                color = C.Muted, modifier = Modifier.padding(top = 8.dp))
                        } else if (podReady) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$live", style = MaterialTheme.typography.displayLarge.merge(Tnum)
                                    .copy(fontSize = 92.sp, lineHeight = 92.sp), color = C.Text)
                                Spacer(Modifier.width(8.dp))
                                Text(if (ex != null && Routines.isOneArm(ex)) (if (Routines.podSideFirst(ex.id)) "reps\nleft side" else "reps\nright arm") else "reps",
                                    style = MaterialTheme.typography.titleMedium, color = C.Muted,
                                    modifier = Modifier.padding(bottom = 16.dp))
                            }
                        } else {
                            Text("Pod not connected: count yourself", style = MaterialTheme.typography.bodyMedium,
                                color = C.Warm, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (paused) Text("Paused: not counting. Resume when you're back in the set.",
                            style = MaterialTheme.typography.bodyMedium, color = C.Warm)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(108.dp).clip(CircleShape).background(C.Stop).clickable { vm.stopSet() },
                            contentAlignment = Alignment.Center,
                        ) { Text("STOP", style = MaterialTheme.typography.headlineMedium, color = Color.White) }
                        Box(
                            Modifier.size(width = 108.dp, height = 44.dp).clip(RoundedCornerShape(22.dp))
                                .background(if (paused) C.Accent else C.Raised)
                                .clickable { if (paused) vm.resumeSet() else vm.pauseSet() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (paused) "▶ RESUME" else "❚❚ PAUSE", style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold, color = if (paused) Color.White else C.Text)
                        }
                    }
                }
            }
            Phase.CONFIRM -> {
                var reps by remember(active?.startMs) { mutableIntStateOf(if (live > 0) live else 0) }
                Text("${ex?.name ?: ""} · set ${active?.setNo ?: ""}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (analysis != null) {
                    Spacer(Modifier.height(10.dp))
                    QualityView(analysis, warmup = active?.warmup == true, calibration = active?.calibration == true)
                }
                Spacer(Modifier.height(10.dp))
                val perArm = ex != null && Routines.isOneArm(ex)
                Text(
                    (if (perArm) "Reps per arm?" else "How many reps?") +
                        if (active?.podStarted == true) " (pod counted $live)" else "",
                    style = MaterialTheme.typography.bodyLarge, color = C.Muted,
                )
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    RoundBtn(Icons.Filled.Remove) { reps = max(0, reps - 1) }
                    Text("$reps", style = MaterialTheme.typography.displayMedium.merge(Tnum),
                        textAlign = TextAlign.Center, modifier = Modifier.width(120.dp))
                    RoundBtn(Icons.Filled.Add) { reps++ }
                }
                Button(
                    onClick = { vm.saveSet(reps) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnAccent),
                ) { Text("Save · start rest", style = MaterialTheme.typography.titleMedium) }
                TextButton(onClick = { vm.discardSet() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Discard this set", color = C.Muted)
                }
            }
            Phase.READY -> restEndsAt?.let { end ->
                val next by vm.nextUp.collectAsStateWithLifecycle()
                val auto by vm.autoNext.collectAsStateWithLifecycle()
                val left = end - now
                val doneRest = left <= 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { if (doneRest) 1f else (left / (restS * 1000f)).coerceIn(0f, 1f) },
                            modifier = Modifier.size(84.dp),
                            color = if (doneRest) C.Done else C.Accent,
                            trackColor = C.Raised, strokeWidth = 7.dp, strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                        )
                        Text(
                            if (doneRest) "GO" else clock(left),
                            style = MaterialTheme.typography.titleLarge.merge(Tnum),
                            color = if (doneRest) C.Done else C.Text,
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (doneRest) "Rest done: next set" else "Resting",
                            style = MaterialTheme.typography.titleMedium)
                        Text(next?.let { "Next: ${it.ex.name} · set ${it.setNo} · " + compact(it.weight, it.addons, null, it.ex.bodyweight) + " kg" }
                                ?: "All planned sets done: tap Finish",
                            style = MaterialTheme.typography.bodyMedium, color = if (next != null) C.Text else C.Done,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                                restS >= 180 -> "Heavy lift · ${clock(restS * 1000L)}"
                                restS <= 60 -> "Warm-up · ${clock(restS * 1000L)}"
                                else -> "Rest · ${clock(restS * 1000L)}"
                            },
                            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        Row {
                            TextButton(onClick = { vm.addRest(-15) }) { Text("−15", color = C.Text, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { vm.addRest(15) }) { Text("+15", color = C.Text, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { if (doneRest || next == null) vm.closeRest() else vm.skipRest() }) {
                                Text(if (doneRest || next == null) "Close" else "Skip", color = C.Muted)
                            }
                            TextButton(onClick = { vm.setAutoNext(!auto) }) {
                                Text(if (auto) "Auto ✓" else "Auto off", color = if (auto) C.Accent else C.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Rep quality after a set: overall score, ROM per rep, speed drop, tempo. */
@Composable
private fun QualityView(a: SetAnalysis, warmup: Boolean, calibration: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(C.Raised).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { a.overall / 100f }, modifier = Modifier.size(64.dp),
                    color = C.score(a.overall), trackColor = C.Line, strokeWidth = 6.dp,
                    strokeCap = StrokeCap.Round, gapSize = 0.dp,
                )
                Text("${a.overall}", style = MaterialTheme.typography.titleLarge.merge(Tnum))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (calibration) "ROM calibration" else "Rep quality", style = MaterialTheme.typography.titleMedium)
                val u = com.silverajat.lifttracker.reps.unitLabel(a.unit)
                val romLine = when {
                    calibration -> "Calibration set: ROM ${a.setRom.toInt()}$u, now part of your usual range"
                    warmup -> "Warm-up ROM: ${a.setRom.toInt()}$u"
                    else -> "ROM ${a.avgRomPct}% of ${a.ref.source.label} (${a.ref.value.toInt()}$u)"
                }
                Text(romLine, style = MaterialTheme.typography.bodyMedium, color = C.Text)
                Text(
                    (if (a.partials > 0) "${a.partials} partial rep${if (a.partials > 1) "s" else ""} · " else "No partials · ") +
                        "speed drop ${a.speedDropPct}% · ${"%.1f".format(a.avgTempoS)} s/rep",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (a.partials > 0) C.Warm else C.Muted,
                )
            }
        }
        // ROM per rep as bars (taller = fuller range); red = partial
        Row(
            Modifier.fillMaxWidth().height(70.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            a.perRep.forEach { r ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom) {
                    Box(
                        Modifier.fillMaxWidth()
                            .height((44 * minOf(r.romPct, 120) / 100f).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (r.partial) C.Stop else C.score(r.score)),
                    )
                    Text("${r.rep}", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                }
            }
        }
        Text(
            "Bars: range of motion per rep (full height = 100%).",
            style = MaterialTheme.typography.labelMedium, color = C.Muted,
        )
    }
}

@Composable
private fun RoundBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(C.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = C.Text, modifier = Modifier.size(28.dp)) }
}


/** Your note on this exercise: shows every session until you change it. Tap to edit. */
@Composable
fun NoteLine(vm: AppViewModel, exerciseId: Int, modifier: Modifier = Modifier) {
    val notes by vm.notes.collectAsStateWithLifecycle()
    val note = notes[exerciseId]
    var editing by remember { mutableStateOf(false) }
    if (editing) NoteDialog(note?.text.orEmpty(), onDismiss = { editing = false }) { vm.setNote(exerciseId, it); editing = false }
    if (note != null) {
        Column(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.Warm.copy(alpha = 0.10f))
            .clickable { editing = true }.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(note.text, style = MaterialTheme.typography.bodyMedium, color = C.Text)
            Text("Your note, " + java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(note.updatedMs)) + ". Tap to edit.",
                style = MaterialTheme.typography.labelSmall, color = C.Muted)
        }
    } else {
        Text("+ Add a note for next time", style = MaterialTheme.typography.labelLarge, color = C.Muted,
            modifier = modifier.clickable { editing = true }.padding(vertical = 2.dp))
    }
}

@Composable
private fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Note for this exercise") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    text, { text = it }, minLines = 3,
                    placeholder = { Text("e.g. seat at 4, felt easy: go 2.5 kg up", color = C.Muted) },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = {
            Row {
                if (initial.isNotEmpty()) TextButton(onClick = { onSave("") }) { Text("Delete", color = C.Stop) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) }
            }
        },
    )
}
