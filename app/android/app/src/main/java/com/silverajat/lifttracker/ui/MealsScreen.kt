package com.silverajat.lifttracker.ui

import androidx.compose.foundation.clickable
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

/** The meal plan for a day: tap a planned item to log it (now), tap a logged one to change time/portion. */
@Composable
fun MealsScreen(hub: HubViewModel) {
    val date by hub.date.collectAsStateWithLifecycle()
    val entries by hub.entries.collectAsStateWithLifecycle()
    val weights by hub.weights.collectAsStateWithLifecycle()
    val walks by hub.walks.collectAsStateWithLifecycle()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val plan = remember(date) { MealPlan.day(date) }
    val total = MealStore.totals(entries)
    val planned = remember(date) { MealPlan.plannedTotal(date) }

    var editing by remember { mutableStateOf<MealEntry?>(null) }
    var customSlot by remember { mutableStateOf<Slot?>(null) }
    var weightDialog by remember { mutableStateOf(false) }

    editing?.let { e -> EntryDialog(e, onDismiss = { editing = null }, hub = hub) }
    customSlot?.let { s -> CustomDialog(s, onDismiss = { customSlot = null }, onSave = { n, m -> hub.logCustom(s, n, m); customSlot = null }) }
    if (weightDialog) WeightDialog(onDismiss = { weightDialog = false }, onSave = { hub.setWeight(it, date); weightDialog = false })

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        if (date == Targets.reviewDate) item {
            Card(Modifier.fillMaxWidth(), border = C.Warm.copy(alpha = 0.6f)) {
                Text("Plan review day: compare this week's average weight with last week's (Body card on Today) and adjust.",
                    color = C.Warm)
            }
        }

        // ---- totals ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("${total.kcal.roundToInt()}", style = MaterialTheme.typography.headlineLarge.merge(Tnum))
                        Text("  kcal eaten (est.)", color = C.Muted, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    MacroBar("Protein", total.protein, Targets.protein.start, Targets.protein.endInclusive, Recover)
                    MacroBar("Fat", total.fat, Targets.fat.start, Targets.fat.endInclusive, Yellow)
                    Text("Carbs ${total.carbs.roundToInt()} g · fibre ${total.fibre.roundToInt()} g", color = C.Muted)
                    Text("Full plan today ≈ ${planned.kcal.roundToInt()} kcal, P ${planned.protein.roundToInt()} g, F ${planned.fat.roundToInt()} g",
                        style = MaterialTheme.typography.labelMedium, color = C.Muted)
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
                            val m = MealStore.totals(logged)
                            if (logged.isNotEmpty()) Text("${m.kcal.roundToInt()} kcal · P ${m.protein.roundToInt()}",
                                style = MaterialTheme.typography.labelLarge.merge(Tnum), color = C.Muted)
                        }
                        if (slot.hint.isNotEmpty()) Text(slot.hint, style = MaterialTheme.typography.labelMedium, color = C.Muted)

                        // logged items
                        logged.forEach { e ->
                            LoggedRow(e, zone, walk = if (isMain) walks[e.id] else null, onClick = { editing = e })
                        }

                        // planned items not yet logged
                        val loggedIds = logged.mapNotNull { it.foodId }.toSet()
                        val options: List<Food> = when {
                            isMain -> (MealPlan.mains(date.dayOfWeek) + listOf(Foods.get("chicken_rice"), Foods.get("mixed_rice"),
                                MealPlan.salad(date.dayOfWeek))).distinctBy { it.id }.takeIf { logged.isEmpty() } ?: emptyList()
                            slot == Slot.EXTRA -> listOf(Foods.get("meiji"), Foods.get("konjac"))
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
    else -> f.name.substringBefore(",")
}

@Composable
private fun LoggedRow(e: MealEntry, zone: ZoneId, walk: Long?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(Instant.ofEpochMilli(e.timeMs).atZone(zone).format(hmFmt), style = MaterialTheme.typography.labelLarge.merge(Tnum),
            color = C.Done, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name + if (e.portion != 1.0) "  ×${fmtPortion(e.portion)}" else "", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text("${e.macros.kcal.roundToInt()} kcal · P ${e.macros.protein.roundToInt()} · C ${e.macros.carbs.roundToInt()} · F ${e.macros.fat.roundToInt()}",
                style = MaterialTheme.typography.labelMedium.merge(Tnum), color = C.Muted)
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
    var time by remember { mutableStateOf(t.format(hmFmt)) }
    var portion by remember { mutableStateOf(e.portion) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text(e.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NumField("Time (H:MM)", time, { time = it })
                Text("Portion", color = C.Muted)
                FlowRowCompat(spacing = 6.dp) {
                    listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).forEach { p ->
                        Tag("×${fmtPortion(p)}", color = if (p == portion) C.Accent else C.Text, modifier = Modifier.clickable { portion = p })
                    }
                }
                val m = e.macros * (portion / e.portion)
                Text("${m.kcal.roundToInt()} kcal, P ${m.protein.roundToInt()} g, C ${m.carbs.roundToInt()} g, F ${m.fat.roundToInt()} g", color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parts = time.trim().split(':', '.')
                val h = parts.getOrNull(0)?.toIntOrNull()
                val min = parts.getOrNull(1)?.toIntOrNull() ?: 0
                if (h != null) hub.setTime(e, h, min)
                if (portion != e.portion) hub.setPortion(e.copy(timeMs = if (h != null) e.date.atTime(h.coerceIn(0, 23), min.coerceIn(0, 59))
                    .atZone(zone).toInstant().toEpochMilli() else e.timeMs), portion)
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

@Composable
private fun CustomDialog(slot: Slot, onDismiss: () -> Unit, onSave: (String, Macros) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    var p by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    var f by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = C.Surface,
        title = { Text("Add to ${slot.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("What (e.g. kaya toast)") }, singleLine = true, colors = fieldColors())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("kcal", kcal, { kcal = it }, Modifier.weight(1f))
                    NumField("protein g", p, { p = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField("carbs g", c, { c = it }, Modifier.weight(1f))
                    NumField("fat g", f, { f = it }, Modifier.weight(1f))
                }
                Text("Don't know the numbers? Log a rough kcal, or ask Claude in Insights → Brainstorm to estimate.", style = MaterialTheme.typography.labelMedium, color = C.Muted)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                onSave(name.trim(), Macros(kcal.toDoubleOrNull() ?: 0.0, p.toDoubleOrNull() ?: 0.0, c.toDoubleOrNull() ?: 0.0, f.toDoubleOrNull() ?: 0.0))
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
