package com.silverajat.lifttracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.meals.Food
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.Macros
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealPlan
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.Targets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val hmFmt = DateTimeFormatter.ofPattern("H:mm")
private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM")

/** About 15 minutes of walking. */
private const val WALK_STEPS = 1200

/**
 * Food: Net (eaten − burned, checked against your weight) and Log (the plan
 * for a day: tap a planned item to log it now, tap a logged one to change
 * its time or portion). Calories are only used for the net number.
 */
@Composable
fun MealsScreen(hub: HubViewModel, startOnLog: Boolean = false) {
    var view by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (startOnLog) 1 else 0) }   // 0 Net, 1 Log
    val analysis by hub.analysis.collectAsStateWithLifecycle()
    val todayEntries by hub.todayEntries.collectAsStateWithLifecycle()
    val goal by hub.goal.collectAsStateWithLifecycle()
    val date by hub.date.collectAsStateWithLifecycle()
    val entries by hub.entries.collectAsStateWithLifecycle()
    val weights by hub.weights.collectAsStateWithLifecycle()
    val walks by hub.walks.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val plan = remember(date) { MealPlan.day(date) }

    var editing by remember { mutableStateOf<MealEntry?>(null) }
    var timing by remember { mutableStateOf<MealEntry?>(null) }
    timing?.let { e ->
        val t = Instant.ofEpochMilli(e.timeMs).atZone(ZoneId.systemDefault())
        TimeDialog(t.hour, t.minute, onDismiss = { timing = null }) { h, m -> hub.setTime(e, h, m); timing = null }
    }
    var customSlot by remember { mutableStateOf<Slot?>(null) }
    var weightDialog by remember { mutableStateOf(false) }
    var weightOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    editing?.let { e -> EntryDialog(e, onDismiss = { editing = null }, hub = hub) }
    customSlot?.let { s -> CustomDialog(s, onDismiss = { customSlot = null }, onSave = { n, m -> hub.logCustom(s, n, m); customSlot = null }) }
    if (weightDialog) WeightDialog(onDismiss = { weightDialog = false }, onSave = { hub.setWeight(it, if (weightOpen) LocalDate.now() else date); weightDialog = false })
    if (weightOpen) {
        androidx.activity.compose.BackHandler { weightOpen = false }
        WeightDetail(weights, goal, onBack = { weightOpen = false }, onLog = { weightDialog = true })
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Net", "Log").forEachIndexed { i, label ->
                    Text(label, style = MaterialTheme.typography.titleMedium,
                        color = if (view == i) C.Text else C.Muted,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (view == i) C.Raised else C.Surface)
                            .clickable { view = i }.padding(vertical = 10.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        if (view == 0) {
            netItems(analysis, todayEntries, goal, onWeight = { weightOpen = true })
            return@LazyColumn
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { hub.setDate(date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous day", tint = C.Text)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (date == today) "Today" else date.format(dateFmt), style = MaterialTheme.typography.headlineMedium)
                    if (date == today) Text(date.format(dateFmt), color = C.Muted)
                }
                IconButton(onClick = { hub.setDate(date.plusDays(1)) }, enabled = date < today) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next day", tint = if (date < today) C.Text else C.Line)
                }
            }
        }


        // ---- slots ----
        Slot.entries.forEach { slot ->
            val plannedHere = plan.filter { it.slot == slot }
            val logged = entries.filter { it.slot == slot }.sortedBy { it.timeMs }
            val isMain = slot == Slot.LUNCH || slot == Slot.DINNER
            item(key = slot.name) {
                Card(Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(slot.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (logged.isNotEmpty()) Text("✓", style = MaterialTheme.typography.titleMedium, color = C.Done)
                        }
                        if (slot.hint.isNotEmpty()) Text(slot.hint, style = MaterialTheme.typography.labelMedium, color = C.Muted)

                        // logged items
                        logged.forEach { e ->
                            LoggedRow(e, zone, walk = if (isMain) walks[e.id] else null, onClick = { editing = e }, onTime = { timing = e })
                        }

                        // planned items not yet logged
                        val loggedIds = logged.mapNotNull { it.foodId }.toSet()
                        val options: List<Food> = when {
                            isMain -> (MealPlan.mains(date.dayOfWeek) + listOf(Foods.get("chicken_rice"), Foods.get("mixed_rice"),
                                MealPlan.salad(date.dayOfWeek))).distinctBy { it.id }.takeIf { logged.isEmpty() } ?: emptyList()
                            slot == Slot.EXTRA -> listOf(Foods.get("meiji"), Foods.get("konjac"))
                            // the bowl: today's planned items first, then any of your fruits
                            slot == Slot.YOGHURT -> (plannedHere.map { it.food } + MealPlan.allFruits.map { Foods.get(it) })
                                .distinctBy { it.id }.filter { it.id !in loggedIds }
                            else -> plannedHere.map { it.food }.filter { it.id !in loggedIds }
                        }
                        val suggested = if (isMain) plannedHere.firstOrNull()?.food?.id else null
                        if (options.isNotEmpty()) {
                            FlowRowCompat(spacing = 6.dp) {
                                options.forEach { f ->
                                    Tag("+ " + short(f), color = if (f.id == suggested) C.Accent else C.Text,
                                        modifier = Modifier.clickable { hub.log(slot, f) })
                                }
                                Tag("+ other", color = C.Muted, modifier = Modifier.clickable { customSlot = slot })
                            }
                        } else {
                            Text("+ add something else", color = C.Muted, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable { customSlot = slot })
                        }
                        if (isMain && logged.isNotEmpty()) Text("Walk ${Targets.walkAfterMealMin} min after", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                    }
                }
            }
        }

        // ---- weight ----
        item {
            val weeks = MealStore.weekAverages(weights, today)
            val todays = weights.firstOrNull { it.date == date }
            Card(Modifier.fillMaxWidth(), onClick = { weightDialog = true }) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Morning weight")
                    Text(todays?.let { "${"%.1f".format(it.kg)} kg" } ?: "Tap to log (after toilet, before food)",
                        style = if (todays != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                        color = if (todays != null) C.Text else C.Accent)
                    weeks.take(3).forEach { w ->
                        Text("Week of ${w.weekStart.format(dateFmt)}: ${"%.2f".format(w.avgKg)} kg (${w.days} days)", color = C.Muted)
                    }
                    if (weeks.size >= 2) {
                        val d = weeks[0].avgKg - weeks[1].avgKg
                        Text("Change ${if (d >= 0) "+" else ""}${"%.2f".format(d)} kg week on week", color = C.Text)
                    }
                }
            }
        }
    }
}

private fun short(f: Food): String = when (f.id) {
    "salad_egg" -> "Salad bowl"
    "salad_carrot" -> "Salad bowl (carrot)"
    "chicken_rice" -> "Chicken rice"
    "mixed_rice" -> "Mixed rice"
    "coconut_water" -> "Coconut water"
    "bread" -> "Bread"
    "honey20", "honey10" -> "Honey"
    "caffeine" -> "Caffeine"
    "shake" -> "Shake"
    "mayvers10" -> "Mayver's"
    "clusters" -> "Clusters"
    "yoghurt" -> "Yoghurt"
    "supplements" -> "Supplements"
    "psyllium" -> "Psyllium"
    "meiji" -> "Meiji protein"
    "konjac" -> "Konjac jelly"
    "mixed_fruit" -> "Mixed fruit"
    "kiwi2" -> "2 kiwis"
    "dragon_fruit" -> "Dragon fruit"
    else -> f.name.substringBefore(",")
}

@Composable
private fun LoggedRow(e: MealEntry, zone: ZoneId, walk: Long?, onClick: () -> Unit, onTime: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // tap the time to change it
        Text(Instant.ofEpochMilli(e.timeMs).atZone(zone).format(hmFmt), style = MaterialTheme.typography.labelLarge.merge(Tnum),
            color = C.Done, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(C.Raised).clickable(onClick = onTime)
                .padding(horizontal = 8.dp, vertical = 4.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name + if (e.portion != 1.0) "  ×${fmtPortion(e.portion)}" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
        walk?.let {
            Spacer(Modifier.width(6.dp))
            Tag(if (it >= WALK_STEPS) "walked ✓" else "$it steps", color = if (it >= WALK_STEPS) C.Done else C.Warm)
        }
    }
}

private fun fmtPortion(p: Double) = if (p == Math.floor(p)) "%.0f".format(p) else "%.2f".format(p).trimEnd('0').trimEnd('.')

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text,
    cursorColor = C.Accent, focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted,
)

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors())
}

@Composable
private fun EntryDialog(e: MealEntry, onDismiss: () -> Unit, hub: HubViewModel) {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(e.timeMs).atZone(zone)
    var hour by remember { mutableStateOf(t.hour) }
    var minute by remember { mutableStateOf(t.minute) }
    var picking by remember { mutableStateOf(false) }
    var portion by remember { mutableStateOf(e.portion) }
    // weighed foods: grams/ml per ingredient instead of a portion
    val parts = e.foodId?.let { Foods.base(it) }?.let { Foods.withAmounts(it, e.amounts.ifEmpty { null }) }
    val amountText = remember { mutableStateMapOf<String, String>().apply {
        parts?.amounts?.forEach { (k, v) -> put(k, if (v == Math.floor(v)) "%.0f".format(v) else v.toString()) } } }
    var usual by remember { mutableStateOf(true) }
    val amounts = parts?.parts?.associate { p -> p.key to (amountText[p.key]?.toDoubleOrNull() ?: p.default) }
    if (picking) TimeDialog(hour, minute, onDismiss = { picking = false }) { h, m -> hour = h; minute = m; picking = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(e.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Eaten at", color = C.Muted, modifier = Modifier.weight(1f))
                    Text("%d:%02d".format(hour, minute), style = MaterialTheme.typography.headlineSmall.merge(Tnum),
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(C.Raised).clickable { picking = true }
                            .padding(horizontal = 14.dp, vertical = 6.dp))
                }
                if (parts != null && parts.parts.isNotEmpty()) {
                    parts.parts.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.label, modifier = Modifier.weight(1f))
                            NumField(p.unit, amountText[p.key] ?: "", { amountText[p.key] = it.filter { c -> c.isDigit() || c == '.' } },
                                Modifier.width(96.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { usual = !usual }) {
                        androidx.compose.material3.Checkbox(usual, { usual = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = C.Accent, uncheckedColor = C.Muted, checkmarkColor = C.OnAccent))
                        Text("Make this my usual", color = C.Text)
                    }
                } else {
                    Text("Portion", color = C.Muted)
                    FlowRowCompat(spacing = 6.dp) {
                        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { p ->
                            Tag("×${fmtPortion(p)}", color = if (p == portion) C.Accent else C.Text, modifier = Modifier.clickable { portion = p })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                hub.setTime(e, hour, minute)
                if (amounts != null && amounts.isNotEmpty() && (amounts != parts?.amounts || usual))
                    hub.setAmounts(e.copy(timeMs = e.date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()), amounts, usual)
                else if (portion != e.portion) hub.setPortion(e.copy(timeMs = e.date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()), portion)
                onDismiss()
            }) { Text("Save", color = C.Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { hub.remove(e); onDismiss() }) { Text("Remove", color = C.Stop) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) }
            }
        },
    )
}

/** Clock picker for when you ate something. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val state = androidx.compose.material3.rememberTimePickerState(hour, minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("When did you eat it?") },
        text = {
            androidx.compose.material3.TimePicker(
                state = state,
                colors = androidx.compose.material3.TimePickerDefaults.colors(
                    clockDialColor = C.Raised, clockDialSelectedContentColor = C.OnAccent, clockDialUnselectedContentColor = C.Text,
                    selectorColor = C.Accent, containerColor = C.Surface,
                    timeSelectorSelectedContainerColor = C.Accent, timeSelectorUnselectedContainerColor = C.Raised,
                    timeSelectorSelectedContentColor = C.OnAccent, timeSelectorUnselectedContentColor = C.Text,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text("Set time", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}

@Composable
private fun CustomDialog(slot: Slot, onDismiss: () -> Unit, onSave: (String, Macros) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Add to ${slot.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("What (e.g. kaya toast)") }, singleLine = true, colors = fieldColors())
                NumField("Rough kcal", kcal, { kcal = it })
                Text("Only used for net calories. A rough guess is fine: the scale corrects it over time.", style = MaterialTheme.typography.labelMedium, color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(name.trim(), Macros(kcal.toDoubleOrNull() ?: 0.0, 0.0, 0.0, 0.0))
            }) { Text("Add", color = C.Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}

@Composable
private fun WeightDialog(onDismiss: () -> Unit, onSave: (Double) -> Unit) {
    var kg by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Morning weight") },
        text = { NumField("kg", kg, { kg = it }) },
        confirmButton = {
            TextButton(enabled = kg.toDoubleOrNull()?.let { it in 30.0..200.0 } == true, onClick = { onSave(kg.toDouble()) }) {
                Text("Save", color = C.Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = C.Muted) } },
    )
}
