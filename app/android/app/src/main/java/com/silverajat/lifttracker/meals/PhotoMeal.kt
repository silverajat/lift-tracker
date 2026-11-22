package com.silverajat.lifttracker.meals

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * A meal from a photo: GPT looks at the plate and lists each part with its grams and macros.
 *
 * What makes it better than a plain guess:
 *  - your regular meals go in as reference, broken into their parts, so a plate that's one of them is
 *    estimated as "your usual, but more chicken" rather than from scratch
 *  - your past corrections go in too ("you said 90 g of chicken was really 60 g"), so it learns your stall's scoops
 *  - photos of your regular meals, with their worked-out breakdowns, go in as examples (assets/meal_refs), and so
 *    does every meal you log from a photo afterwards, with the grams you settled on: it sees your plates, your stall's
 *    scoops and your portions, not generic ones
 *  - it can ask one or two things a photo can't show (steamed or roasted, how much gravy)
 *  - it runs 3 times and keeps the middle answer, which cuts the random swing between runs
 *  - calories are worked out from the macros (4 protein + 4 carbs + 9 fat + 2 fibre), so they always add up
 */
object PhotoMeal {
    data class Item(val name: String, val grams: Double, val protein: Double, val carbs: Double, val fat: Double, val fibre: Double) {
        val kcal get() = 4 * protein + 4 * carbs + 9 * fat + 2 * fibre
        val macros get() = Macros(kcal, protein, carbs, fat, fibre)
        /** The same food at [g] grams. */
        fun at(g: Double): Item = if (grams <= 0) this else (g / grams).let { k ->
            copy(grams = g, protein = protein * k, carbs = carbs * k, fat = fat * k, fibre = fibre * k)
        }
    }

    data class Question(val q: String, val options: List<String>)

    data class Estimate(
        val name: String,
        val items: List<Item>,
        val match: String?,              // id of one of your regular meals it looks like, if any
        val notes: List<String>,         // what it assumed
        val questions: List<Question>,
        val rangeKcal: Pair<Int, Int>? = null,   // lowest and highest of the runs
    ) {
        val total get() = items.fold(Macros.ZERO) { a, i -> a + i.macros }
    }

    /** Your regular meals, part by part (the same breakdowns as Nutrition.kt), for GPT to start from. */
    private val REGULARS = listOf(
        "mixed_rice_red" to "mixed rice (cai fan) with red/white rice: rice 1 cup ~210 g (5 g protein, 52 g carbs, 1.5 g fat, 3 g fibre); " +
            "~3 scoops braised chicken thigh & wood ear mushroom in dark sauce, ~90 g meat (25 g protein, 9 g carbs, 10 g fat, 2 g fibre); " +
            "1 scoop tomato egg ~1 egg (6 g, 6 g carbs, 7 g fat); 1 scoop braised tofu slices with wood ear (6 g, 2 g carbs, 3.5 g fat, 1.3 g fibre); " +
            "1 scoop stir-fried napa cabbage (1 g, 3 g carbs, 3 g fat, 1.2 g fibre). Total ~700 kcal, 43 g protein",
        "mixed_rice" to "the same mixed rice with white rice (~210 g: 5 g protein, 57 g carbs, 0.6 g fat, 0.8 g fibre). Total ~710 kcal",
        "chicken_rice" to "steamed chicken rice, chicken without skin, plus extra chicken and extra rice, kailan in oyster sauce, half a braised egg, soup. " +
            "Rice is cooked in chicken fat (~1.5 plates). Total ~850 kcal, 44 g protein, 122 g carbs, 22.5 g fat, 3 g fibre",
        "chicken_rice_plain" to "steamed chicken rice (no skin), kailan, half a braised egg, soup: ~575 kcal, 27 g protein, 89 g carbs, 15.5 g fat",
    )

    fun instructions(corrections: List<Correction>): String = buildString {
        append("You estimate the nutrition of a meal from a photo, for someone in Singapore (mostly hawker and food-court food). ")
        append("List every separate part of the plate with its cooked weight in grams and its protein, carbs, fat and fibre in grams. ")
        append("Use Singapore HPB food composition values and typical stall portions. Count cooking oil and sauces: they're most of the fat in hawker food. ")
        append("Judge size from the plate (a standard food-court plate is ~26 cm across), the cutlery and the rice grains. ")
        append("Be realistic, not generous: a scoop of a meat dish is mostly sauce and vegetables with ~25-35 g of meat.\n\n")
        append("Before the photo to estimate come worked examples: photos of this person's own meals, each with what was really on it. ")
        append("Use them to judge their plates, portions and stalls. Estimate only the last photo.\n\n")
        append("This person's regular meals, already worked out with them part by part. If the photo is one of these, start from its parts ")
        append("and change only what's visibly different (more or less of something, a part missing or added); say what you changed in notes:\n")
        REGULARS.forEach { (id, d) -> append("- $id: $d\n") }
        if (corrections.isNotEmpty()) {
            append("\nTheir past corrections to your portions (your estimate -> what it really was). Use them to calibrate:\n")
            corrections.takeLast(30).forEach { append("- ${it.name}: ${it.guessG.roundToInt()} g -> ${it.realG.roundToInt()} g\n") }
        }
        append("\nIf something a photo can't show would change the answer by more than ~10% (fried or steamed, skin on, how much gravy, ")
        append("rice under the dishes), ask at most 2 short questions with 2-3 options each; otherwise ask none.\n")
        append("Reply with ONLY this JSON: {\"name\": short name of the meal, \"match\": id of a regular meal above or null, ")
        append("\"items\": [{\"name\":..., \"grams\":n, \"protein\":g, \"carbs\":g, \"fat\":g, \"fibre\":g}], ")
        append("\"notes\": [short assumptions], \"questions\": [{\"q\":..., \"options\":[...]}]}")
    }

    fun parse(text: String): Estimate {
        val o = JSONObject(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        val items = o.optJSONArray("items")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Item(it.optString("name", "?"), it.optDouble("grams", 0.0).coerceAtLeast(0.0), it.optDouble("protein", 0.0).coerceAtLeast(0.0),
                    it.optDouble("carbs", 0.0).coerceAtLeast(0.0), it.optDouble("fat", 0.0).coerceAtLeast(0.0), it.optDouble("fibre", 0.0).coerceAtLeast(0.0))
            }
        }.orEmpty()
        fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty().filter { it.isNotBlank() }
        val qs = o.optJSONArray("questions")?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { q ->
                val opts = strings(q.optJSONArray("options"))
                if (q.optString("q").isBlank() || opts.size < 2) null else Question(q.getString("q"), opts.take(3))
            }
        }.orEmpty().take(2)
        val match = o.optString("match").takeIf { it.isNotBlank() && it != "null" && Foods.find(it) != null }
        return Estimate(o.optString("name", "Meal").ifBlank { "Meal" }, items, match, strings(o.optJSONArray("notes")), qs)
    }

    /** The run in the middle (by calories) of several, with the spread. */
    fun middle(runs: List<Estimate>): Estimate? {
        val ok = runs.filter { it.items.isNotEmpty() }.sortedBy { it.total.kcal }
        if (ok.isEmpty()) return null
        val mid = ok[(ok.size - 1) / 2]
        // a question any run asked is worth asking
        val qs = (mid.questions + ok.flatMap { it.questions }).distinctBy { it.q.lowercase() }.take(2)
        return mid.copy(questions = qs,
            rangeKcal = if (ok.size > 1) ok.first().total.kcal.roundToInt() to ok.last().total.kcal.roundToInt() else null)
    }

    /** A worked example: a photo and what was really on it. */
    class Reference(val jpeg: ByteArray, val text: String)

    /** Photos you sent of your regular meals, each with its agreed breakdown (bundled with the app). */
    val BUNDLED = listOf(
        "mixed_rice_red.jpg" to "Example: their mixed rice, an OLD order. Since then they no longer get the chilli chicken slices " +
            "(bottom right) and get more of the braised chicken (top left) instead. As ordered now: red/white rice ~210 g; " +
            "~3 scoops braised chicken & wood ear (~90 g meat); tomato egg (~1 egg); braised tofu slices with wood ear and cucumber " +
            "(top right); napa cabbage. ~700 kcal, 43 g protein, 72 g carbs, 25 g fat, 8 g fibre.",
        "chicken_rice_plain.jpg" to "Example: their steamed chicken rice (skinless), plain plate with chilli, kailan in oyster sauce, " +
            "a braised egg and soup. ~575 kcal, 27 g protein, 89 g carbs, 15.5 g fat with half an egg (they usually have half). " +
            "Their usual adds extra chicken and extra rice: ~850 kcal, 44 g protein.",
        "salad_egg.jpg" to "Example: their salad bowl: red/brown rice ~220 g, citrus-lime chicken ~75 g, chickpeas ~40 g, edamame ~40 g, " +
            "an egg, broccoli, ~15 ml sesame dressing. ~700 kcal, 44 g protein, 73 g carbs, 26 g fat, 10 g fibre.",
    )

    /** Meals you logged from a photo, with the grams you settled on: the newest go in as examples. */
    class Log(root: File) {
        private val dir = File(root, "photo_meals").apply { mkdirs() }
        fun save(jpeg: ByteArray, name: String, items: List<Item>) = runCatching {
            val t = System.currentTimeMillis()
            File(dir, "$t.jpg").writeBytes(jpeg)
            val text = "Example: a meal they logged ($name), with the portions they confirmed: " +
                items.joinToString("; ") { "${it.name} ${it.grams.roundToInt()} g" } +
                ". Total ${items.sumOf { it.kcal }.roundToInt()} kcal, ${items.sumOf { it.protein }.roundToInt()} g protein."
            File(dir, "$t.txt").writeText(text)
            // keep the newest 20
            dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedByDescending { it.name }.drop(20).forEach {
                it.delete(); File(dir, it.nameWithoutExtension + ".txt").delete()
            }
        }

        fun newest(n: Int): List<Reference> = dir.listFiles().orEmpty().filter { it.extension == "jpg" }.sortedByDescending { it.name }
            .take(n).mapNotNull { f -> runCatching { Reference(f.readBytes(), File(dir, f.nameWithoutExtension + ".txt").readText()) }.getOrNull() }
    }

    data class Correction(val name: String, val guessG: Double, val realG: Double, val timeMs: Long)

    /** Your fixes to its portions, kept to calibrate the next photos. */
    class Corrections(root: File) {
        private val f = File(root, "photo_corrections.json")
        fun all(): List<Correction> = runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Correction(it.getString("n"), it.getDouble("g"), it.getDouble("r"), it.getLong("t")) }
        }.getOrDefault(emptyList())

        fun add(list: List<Correction>) {
            if (list.isEmpty()) return
            val all = (all() + list).takeLast(100)
            f.writeText(JSONArray(all.map { JSONObject().put("n", it.name).put("g", it.guessG).put("r", it.realG).put("t", it.timeMs) }).toString())
        }
    }
}
