package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.AppViewModel
import com.silverajat.lifttracker.Phase
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.data.PlanSet
import com.silverajat.lifttracker.data.Routines
import kotlin.math.max

@Composable
fun LiftScreen(vm: AppViewModel, exerciseId: Int) {
    val ex = remember(exerciseId) { Routines.byId(exerciseId) }
    val session by vm.session.collectAsStateWithLifecycle()
    val phase by vm.phase.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val live by vm.liveReps.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val restEndsAt by vm.restEndsAt.collectAsStateWithLifecycle()
    val restS by vm.restSeconds.collectAsStateWithLifecycle()
    val conn by vm.pod.conn.collectAsStateWithLifecycle()

    val today = session?.sets?.filter { it.exerciseId == ex.id } ?: emptyList()
    val sugg = remember(ex.id, today.size, session?.id) { vm.suggestion(ex) }
    var weight by remember(ex.id, today.size) { mutableDoubleStateOf(sugg.weight) }
    var addons by remember(ex.id, today.size) { mutableIntStateOf(sugg.addons) }
    var warmup by remember(ex.id, today.size) { mutableStateOf(sugg.warmup) }
    var editWeight by remember { mutableStateOf(false) }
    // After Stop: start from the pod's count, or last time's reps without the pod.
    var reps by remember(phase) { mutableIntStateOf(if (live > 0) live else sugg.reps) }

    Column(Modifier.fillMaxSize()) {
        TopBar(title = ex.name, subtitle = ex.detail, onBack = { vm.back() })

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (phase) {
                Phase.READY -> {
                    restEndsAt?.let {
                        RestBanner(it, restS, now, onAdd = { vm.addRest(15) }, onSkip = { vm.skipRest() })
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel("Last time")
                            SetChips(vm.lastTime(ex), ex.bodyweight)
                            if (ex.note.isNotEmpty()) {
                                Text("“${ex.note}”", style = MaterialTheme.typography.bodyMedium, color = C.Muted)
                            }
                        }
                    }
                    if (today.isNotEmpty()) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                SectionLabel("Today")
                                today.forEach { s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "${s.setNo}",
                                            style = MaterialTheme.typography.labelLarge.merge(Tnum),
                                            color = C.Muted, modifier = Modifier.width(22.dp),
                                        )
                                        Text(
                                            "${weightLabel(s.weight, s.addons, ex.bodyweight)} × ${s.reps}",
                                            style = MaterialTheme.typography.titleMedium.merge(Tnum),
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (s.warmup) Tag("W", color = C.Warm)
                                        s.liveReps?.let {
                                            Spacer(Modifier.width(6.dp))
                                            Tag("pod $it", color = C.Pod)
                                        }
                                        IconButton(onClick = { vm.deleteSet(s) }) {
                                            Icon(Icons.Filled.Close, "Delete set", tint = C.Muted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "SET ${today.size + 1}",
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Pill("Warm-up", on = warmup, color = C.Warm) { warmup = !warmup }
                    }
                    Stepper(
                        value = if (ex.bodyweight && weight == 0.0) "BW" else kg(weight),
                        unit = if (ex.bodyweight && weight == 0.0) "" else "kg",
                        big = true,
                        onMinus = { weight = max(0.0, weight - ex.step) },
                        onPlus = { weight += ex.step },
                        onTap = { editWeight = true },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Add-on plates", style = MaterialTheme.typography.bodyLarge,
                            color = C.Muted, modifier = Modifier.weight(1f))
                        SmallStepper(addons, onMinus = { addons = max(0, addons - 1) }, onPlus = { addons++ })
                    }
                }

                Phase.LIFTING -> {
                    val a = active
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionLabel(
                            "Set ${a?.setNo ?: ""} · " +
                                (a?.let { weightLabel(it.weight, it.addons, ex.bodyweight) } ?: "") +
                                (if (a?.warmup == true) " · warm-up" else "")
                        )
                        Text(
                            elapsed(now - (a?.startMs ?: now)),
                            style = MaterialTheme.typography.headlineMedium.merge(Tnum),
                            color = C.Muted,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (conn == PodConn.READY) {
                            Text(
                                "$live",
                                style = MaterialTheme.typography.displayLarge.merge(Tnum)
                                    .copy(fontSize = 160.sp, lineHeight = 160.sp),
                                color = C.Accent,
                            )
                            Text("reps · live from the pod", style = MaterialTheme.typography.bodyLarge, color = C.Muted)
                        } else {
                            Text("Lifting", style = MaterialTheme.typography.displayMedium, color = C.Accent)
                            Text(
                                "Pod not connected: count yourself, timing still works",
                                style = MaterialTheme.typography.bodyLarge, color = C.Muted,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Phase.CONFIRM -> {
                    val a = active
                    Spacer(Modifier.height(12.dp))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionLabel("Set ${a?.setNo ?: ""} done · ${elapsed((a?.endMs ?: now) - (a?.startMs ?: now))}")
                        Text("How many reps?", style = MaterialTheme.typography.headlineMedium)
                        if (a?.podStarted == true) {
                            Text("The pod counted $live", style = MaterialTheme.typography.bodyLarge, color = C.Pod)
                        }
                    }
                    Stepper(
                        value = "$reps", unit = "reps", big = true,
                        onMinus = { reps = max(0, reps - 1) },
                        onPlus = { reps++ },
                        onTap = null,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        val plan: PlanSet? = vm.lastTime(ex).getOrNull((a?.setNo ?: 1) - 1)
                        plan?.let { p -> Pill("Last time: ${p.reps}", on = false, color = C.Text) { reps = p.reps } }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        // ---- the big button ----
        Box(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 20.dp, top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (phase) {
                Phase.READY -> BigRound("START", C.Accent, C.OnAccent) {
                    vm.startSet(ex, weight, addons, warmup)
                }
                Phase.LIFTING -> BigRound("STOP", C.Stop, Color.White) { vm.stopSet() }
                Phase.CONFIRM -> Column(Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { vm.saveSet(reps) },
                        modifier = Modifier.fillMaxWidth().height(64.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = C.Accent, contentColor = C.OnAccent),
                    ) { Text("Save set · start rest", style = MaterialTheme.typography.titleLarge) }
                    TextButton(onClick = { vm.discardSet() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Discard this set", color = C.Muted)
                    }
                }
            }
        }
    }

    if (editWeight) {
        var text by remember { mutableStateOf(kg(weight)) }
        AlertDialog(
            onDismissRequest = { editWeight = false },
            containerColor = C.Surface,
            title = { Text("Weight (kg)") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace(',', '.') },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = MaterialTheme.typography.headlineMedium.merge(Tnum),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.Accent, cursorColor = C.Accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    text.toDoubleOrNull()?.let { if (it in 0.0..650.0) weight = it }
                    editWeight = false
                }) { Text("Set", color = C.Accent, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { editWeight = false }) { Text("Cancel", color = C.Muted) } },
        )
    }
}

@Composable
private fun BigRound(label: String, fill: Color, text: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(176.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineLarge, color = text)
    }
}

@Composable
private fun Pill(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (on) C.OnAccent else color,
        modifier = Modifier
            .clip(shape)
            .background(if (on) color else Color.Transparent)
            .border(1.5.dp, color, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun Stepper(
    value: String, unit: String, big: Boolean,
    onMinus: () -> Unit, onPlus: () -> Unit, onTap: (() -> Unit)?,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Filled.Remove, "Less", onMinus)
            Column(
                Modifier.weight(1f).then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    value,
                    style = (if (big) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineMedium)
                        .merge(Tnum),
                )
                if (unit.isNotEmpty()) {
                    Text(
                        if (onTap != null) "$unit · tap to type" else unit,
                        style = MaterialTheme.typography.bodyMedium, color = C.Muted,
                    )
                }
            }
            RoundIcon(Icons.Filled.Add, "More", onPlus)
        }
    }
}

@Composable
private fun SmallStepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, "Fewer", tint = C.Text) }
        Text("$value", style = MaterialTheme.typography.titleLarge.merge(Tnum),
            modifier = Modifier.width(28.dp), textAlign = TextAlign.Center)
        IconButton(onClick = onPlus) { Icon(Icons.Filled.Add, "More", tint = C.Text) }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(60.dp).clip(CircleShape).background(C.Raised).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = C.Text, modifier = Modifier.size(30.dp)) }
}
