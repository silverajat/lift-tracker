package com.silverajat.lifttracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.silverajat.lifttracker.HubViewModel
import com.silverajat.lifttracker.meals.Foods
import com.silverajat.lifttracker.meals.Macros
import com.silverajat.lifttracker.meals.PhotoMeal
import com.silverajat.lifttracker.meals.Slot
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/** Camera or gallery for a meal photo; [onPhoto] gets it as a ~1280 px JPEG, upright. */
class MealPhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberMealPhoto(onPhoto: (ByteArray) -> Unit): MealPhotoPicker {
    val ctx = LocalContext.current
    val shot = remember { File(File(ctx.cacheDir, "photos").apply { mkdirs() }, "meal.jpg") }
    val uri = remember { FileProvider.getUriForFile(ctx, ctx.packageName + ".files", shot) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) shrink(ctx, uri)?.let(onPhoto)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
        if (u != null) shrink(ctx, u)?.let(onPhoto)
    }
    return remember { MealPhotoPicker({ cam.launch(uri) }, { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) }
}

/** Longest side 1280 px, turned upright from its EXIF, JPEG 85. */
private fun shrink(ctx: Context, uri: Uri): ByteArray? = runCatching {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
    val bmp = cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }!!
    val rot = runCatching {
        cr.openInputStream(uri)!!.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) }
    }.getOrDefault(1).let { when (it) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f } }
    val k = 1280f / maxOf(bmp.width, bmp.height)
    val m = Matrix().apply { if (k < 1f) postScale(k, k); postRotate(rot) }
    val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}.getOrNull()

@Composable
private fun fc() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = C.Accent, unfocusedBorderColor = C.Line, focusedTextColor = C.Text, unfocusedTextColor = C.Text,
    focusedLabelColor = C.Accent, unfocusedLabelColor = C.Muted, cursorColor = C.Accent,
)

/** Ask camera or gallery. */
@Composable
fun PhotoSourceDialog(onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = C.Surface,
        title = { Text("Photo of the meal") },
        text = { Text("From above, the whole plate in view.", color = C.Muted) },
        confirmButton = { TextButton(onClick = onCamera) { Text("Camera", color = C.Accent, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onGallery) { Text("Gallery", color = C.Text) } })
}

/** The estimate: each part with its grams (change them, or remove a part), then Add. */
@Composable
fun PhotoMealDialog(hub: HubViewModel, slot: Slot, jpeg: ByteArray, onDone: () -> Unit) {
    var est by remember { mutableStateOf<PhotoMeal.Estimate?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val grams = remember { mutableStateListOf<String>() }
    val gone = remember { mutableStateListOf<Int>() }
    val answers = remember { mutableStateMapOf<String, String>() }

    fun run(ans: List<String>) {
        busy = true; error = null
        hub.estimatePhoto(jpeg, ans) { e, err ->
            busy = false
            est = e; error = err
            grams.clear(); gone.clear()
            e?.items?.forEach { grams += it.grams.roundToInt().toString() }
        }
    }
    LaunchedEffect(Unit) { run(emptyList()) }

    val e = est
    val g = e?.items?.indices?.map { i -> if (i in gone) null else grams.getOrNull(i)?.replace(',', '.')?.toDoubleOrNull() }
    val total = if (e == null || g == null) Macros.ZERO else e.items.indices.fold(Macros.ZERO) { a, i -> g[i]?.let { a + e.items[i].at(it).macros } ?: a }

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        containerColor = C.Surface,
        title = { Text(if (busy) "Looking at the plate…" else e?.name ?: "Couldn't estimate it") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (busy) { Text("Three estimates, keeping the middle one.", color = C.Muted); return@Column }
                if (e == null) { Text(error ?: "", color = C.Muted); return@Column }

                // your usual, side by side
                e.match?.let { id ->
                    val usual = Foods.get(id)
                    Text("Your usual: ${usual.macros.kcal.roundToInt()} kcal, ${usual.macros.protein.roundToInt()} g protein",
                        style = MaterialTheme.typography.titleSmall, color = C.Text)
                }
                e.rangeKcal?.let { (lo, hi) -> if (hi - lo >= 30) Text("The 3 estimates: $lo–$hi kcal", style = MaterialTheme.typography.labelMedium, color = C.Muted) }

                // questions it couldn't answer from the photo
                if (e.questions.isNotEmpty()) {
                    e.questions.forEach { q ->
                        Text(q.q, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            q.options.forEach { o ->
                                val on = answers[q.q] == o
                                Text(o, color = if (on) C.Bg else C.Text, style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                        .clickable { answers[q.q] = o }
                                        .background(if (on) C.Accent else C.Raised).padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    if (answers.size == e.questions.size) Text("Update with these", color = C.Accent, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.clickable { run(e.questions.map { "${it.q} ${answers[it.q]}" }) }.padding(vertical = 4.dp))
                }

                e.items.forEachIndexed { i, it ->
                    if (i in gone) return@forEachIndexed
                    val now = g?.get(i)?.let { x -> it.at(x) } ?: it
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.name, style = MaterialTheme.typography.bodyMedium)
                            Text("${now.kcal.roundToInt()} kcal · ${now.protein.roundToInt()} g protein", style = MaterialTheme.typography.labelMedium, color = C.Muted)
                        }
                        OutlinedTextField(grams.getOrElse(i) { "" }, { v -> if (i < grams.size) grams[i] = v },
                            suffix = { Text("g") }, singleLine = true, colors = fc(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(92.dp))
                        Text("✕", color = C.Muted, modifier = Modifier.clickable { gone += i }.padding(start = 10.dp, end = 2.dp))
                    }
                }
                Text("${total.kcal.roundToInt()} kcal · ${total.protein.roundToInt()} g protein · ${total.carbs.roundToInt()} g carbs · " +
                    "${total.fat.roundToInt()} g fat · ${total.fibre.roundToInt()} g fibre", style = MaterialTheme.typography.titleSmall)
                e.notes.take(4).forEach { n -> Text(n, style = MaterialTheme.typography.labelMedium, color = C.Muted) }
            }
        },
        confirmButton = {
            if (e != null && !busy) TextButton(onClick = { hub.logPhoto(slot, e, g ?: emptyList(), jpeg); onDone() }) {
                Text("Add", color = C.Accent, fontWeight = FontWeight.Bold)
            } else if (!busy) TextButton(onClick = { run(emptyList()) }) { Text("Try again", color = C.Accent) }
        },
        dismissButton = {
            Row {
                if (e?.match != null && !busy) TextButton(onClick = { hub.log(slot, Foods.get(e.match)); onDone() }) { Text("Log my usual", color = C.Text) }
                TextButton(onClick = onDone, enabled = !busy) { Text("Cancel", color = C.Muted) }
            }
        },
    )
}

