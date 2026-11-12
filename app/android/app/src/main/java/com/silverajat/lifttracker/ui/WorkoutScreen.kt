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

/** "61+1 × 10": weight, add-on plates, reps, short enough for the table. */
fun compact(weight: Double, addons: Int, reps: Int?, bodyweight: Boolean): String {
    val w = if (bodyweight && weight == 0.0) "BW" else kg(weight) + if (addons > 0) "+$addons" else ""
    return if (reps != null) "$w × $reps" else w
}

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
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going", color = C.Muted) } },
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
        Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = C.Text.copy(alpha = 0.7f))
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
                        "rest ${clock(Routines.restSeconds(ex) * 1000L)}").joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                )
            }
            Icon(Icons.Filled.ChevronRight, "Progress", tint = C.Muted)
        }
        if (ex.note.isNotEmpty()) {
            Text(
                "📝 ${ex.note}", style = MaterialTheme.typography.bodyMedium, color = C.Warm,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell("SET", W_SET)
            HeaderCell("PREVIOUS", W_PREV)
            HeaderCell(if (ex.bodyweight) "+KG" else "KG", W_KG)
            HeaderCell("REPS", W_REPS)
            Spacer(Modifier.width(ACTION))
        }
        var working = 0
        for (i in 0 until rows) key(ex.id, i) {
            val p = plan.getOrNull(i)
            val d = done.firstOrNull { it.setNo == i + 1 }
            val isActive = active != null && active.exerciseId == ex.id && active.setNo == i + 1
            val warm = remember(ex.id, i, d) { mutableStateOf(d?.warmup ?: p?.warmup ?: false) }
            if (!warm.value) working++
            val label = if (warm.value) "W" else "$working"
            val fallback = p ?: plan.lastOrNull()
            val weightText = remember(ex.id, i, d) {
                mutableStateOf(d?.let { kg(it.weight) } ?: fallback?.let { kg(it.weight) } ?: "")
            }
            SetRow(
                label = label,
                warm = warm.value,
                previous = p?.let { compact(it.weight, it.addons, it.reps, ex.bodyweight) } ?: "–",
                weight = weightText.value,
                onWeight = { weightText.value = it },
                reps = d?.reps?.toString(),
                repsHint = p?.reps?.toString() ?: "",
                quality = d?.quality,
                done = d != null,
                active = isActive,
                canStart = canStart && d == null,
                onToggleWarm = { if (d == null && !isActive) warm.value = !warm.value },
                onCopyPrev = { p?.let { weightText.value = kg(it.weight) } },
                onStart = {
                    val w = weightText.value.replace(',', '.').toDoubleOrNull() ?: 0.0
                    vm.startSet(ex, i + 1, w, fallback?.addons ?: 0, warm.value)
                },
                onDone = { d?.let(onRemove) },
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
    warm: Boolean,
    previous: String,
    weight: String,
    onWeight: (String) -> Unit,
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
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                color = if (warm) C.Warm else C.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (warm) C.Warm.copy(alpha = 0.15f) else Color.Transparent)
                    .clickable(onClick = onToggleWarm).padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Text(
            previous,
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = C.Muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(W_PREV).clickable(onClick = onCopyPrev).padding(vertical = 6.dp),
        )
        Box(Modifier.weight(W_KG).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
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
            Phase.LIFTING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${ex?.name ?: ""} · set ${active?.setNo ?: ""}",
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            (active?.let { compact(it.weight, it.addons, null, ex?.bodyweight == true) + " kg" } ?: "") +
                                (if (active?.warmup == true) " · warm-up" else "") +
                                "  ·  " + elapsed(now - (active?.startMs ?: now)),
                            style = MaterialTheme.typography.bodyLarge.merge(Tnum), color = C.Muted,
                        )
                        if (podReady) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text("$live", style = MaterialTheme.typography.displayLarge.merge(Tnum)
                                    .copy(fontSize = 92.sp, lineHeight = 92.sp), color = C.Text)
                                Spacer(Modifier.width(8.dp))
                                Text("reps", style = MaterialTheme.typography.titleMedium, color = C.Muted,
                                    modifier = Modifier.padding(bottom = 16.dp))
                            }
                        } else {
                            Text("Pod not connected: count yourself", style = MaterialTheme.typography.bodyMedium,
                                color = C.Warm, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    Box(
                        Modifier.size(108.dp).clip(CircleShape).background(C.Stop).clickable { vm.stopSet() },
                        contentAlignment = Alignment.Center,
                    ) { Text("STOP", style = MaterialTheme.typography.headlineMedium, color = Color.White) }
                }
            }
            Phase.CONFIRM -> {
                var reps by remember(active?.startMs) { mutableIntStateOf(if (live > 0) live else 0) }
                Text("${ex?.name ?: ""} · set ${active?.setNo ?: ""}",
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (analysis != null) {
                    Spacer(Modifier.height(10.dp))
                    QualityView(analysis, warmup = active?.warmup == true)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (active?.podStarted == true) "How many reps? (pod counted $live)" else "How many reps?",
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
                        Text(if (restS >= 120) "Main lift · 2:00" else "Isolation · 1:30",
                            style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                        Row {
                            TextButton(onClick = { vm.addRest(-15) }) { Text("−15", color = C.Text, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { vm.addRest(15) }) { Text("+15", color = C.Text, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = { vm.skipRest() }) { Text(if (doneRest) "Close" else "Skip", color = C.Muted) }
                        }
                    }
                }
            }
        }
    }
}

/** Rep quality after a set: overall score, ROM per rep, speed drop, tempo. */
@Composable
private fun QualityView(a: SetAnalysis, warmup: Boolean) {
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
                Text("Rep quality", style = MaterialTheme.typography.titleMedium)
                val romLine = when {
                    warmup && a.setRomDeg != null -> "Warm-up sets your full ROM: ${a.setRomDeg.toInt()}°"
                    a.rotation && a.fullRomDeg != null ->
                        "ROM ${a.avgRomPct}% of your full ${a.fullRomDeg.toInt()}°"
                    else -> "ROM ${a.avgRomPct}% of your best rep"
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
                            .height((44 * r.romPct / 100f).coerceAtLeast(4f).dp)
                            .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            .background(if (r.partial) C.Stop else C.score(r.score)),
                    )
                    Text("${r.rep}", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                }
            }
        }
        Text(
            "Bars: range of motion per rep. Beta: tuned on your gym data next.",
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
