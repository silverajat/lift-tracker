package com.silverajat.lifttracker.meals

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Rajat's meal plan (Nov 2026) as data, with estimated macros per item.
 *
 * Estimates: packaged items use their usual label values (whey and milks
 * from the plan's own numbers); hawker meals are educated guesses from
 * typical portions and are the least certain (+-20%). Everything is per
 * portion as eaten.
 */
data class Macros(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double, val fibre: Double = 0.0) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat, fibre + o.fibre)
    operator fun times(k: Double) = Macros(kcal * k, protein * k, carbs * k, fat * k, fibre * k)

    companion object {
        val ZERO = Macros(0.0, 0.0, 0.0, 0.0)
    }
}

/** A weighed ingredient: [per] is the macros for one gram or ml. */
data class Part(val key: String, val label: String, val unit: String, val default: Double, val per: Macros)

/** [parts] = the amounts you can change (grams/ml); [amounts] = what this one has. */
data class Food(val id: String, val name: String, val macros: Macros, val note: String = "",
                val parts: List<Part> = emptyList(), val amounts: Map<String, Double> = emptyMap())

object Foods {
    private fun per(m: Macros, amount: Double) = m * (1.0 / amount)
    private fun one(key: String, label: String, unit: String, amount: Double, m: Macros) = listOf(Part(key, label, unit, amount, per(m, amount)))

    /** Things you weigh or measure, and their ingredients. */
    private val weighed: Map<String, List<Part>> = mapOf(
        // your labels: whey 130 kcal / 32 g; milks from the plan's shake totals
        "shake" to listOf(
            Part("whey", "Whey (ON Gold Standard)", "g", 32.0, Macros(4.0625, 0.75, 0.09375, 0.0625)),
            Part("creatine", "Creatine", "g", 3.0, Macros.ZERO),
            Part("hl", "Marigold HL milk", "ml", 100.0, Macros(0.49, 0.042, 0.067, 0.006)),
            Part("soy", "NutriSoy no sugar", "ml", 100.0, Macros(0.40, 0.043, 0.012, 0.020, 0.013)),
        ),
        "coconut_water" to one("ml", "Coconut water", "ml", 250.0, Macros(55.0, 0.5, 13.0, 0.0)),
        "honey20" to one("g", "Honey", "g", 20.0, Macros(66.0, 0.1, 17.8, 0.0)),
        "honey10" to one("g", "Honey", "g", 10.0, Macros(33.0, 0.0, 8.9, 0.0)),
        "mayvers10" to one("g", "Peanut butter", "g", 10.0, Macros(58.5, 2.45, 2.15, 4.2, 0.75)),
        "clusters" to one("g", "Protein clusters", "g", 50.0, Macros(207.0, 8.3, 16.2, 10.1, 8.5)),
        "yoghurt" to one("g", "Yoghurt", "g", 125.0, Macros(95.0, 5.0, 18.1, 0.2)),
        "blueberries" to one("g", "Blueberries", "g", 125.0, Macros(71.0, 0.9, 18.0, 0.4, 3.0)),
        "psyllium" to one("g", "Psyllium husk", "g", 5.0, Macros(20.0, 0.0, 4.0, 0.0, 4.0)),
    )

    /** Your usual amounts (food id -> part -> amount), loaded from the meal store. */
    @Volatile var usual: Map<String, Map<String, Double>> = emptyMap()

    /** [f] with these amounts: macros and name worked out from the grams. */
    fun withAmounts(f: Food, amounts: Map<String, Double>?): Food {
        val parts = weighed[f.id] ?: return f
        val a = parts.associate { it.key to (amounts?.get(it.key) ?: it.default) }
        val m = parts.fold(Macros.ZERO) { acc, p -> acc + p.per * a.getValue(p.key) }
        fun n(v: Double) = if (v == Math.floor(v)) "%.0f".format(v) else "%.1f".format(v)
        val name = if (f.id == "shake")
            "Whey ${n(a.getValue("whey"))} g + creatine ${n(a.getValue("creatine"))} g, ${n(a.getValue("hl"))} ml HL milk + ${n(a.getValue("soy"))} ml NutriSoy"
            else f.name.substringBeforeLast(",") + ", ${n(a.values.first())} ${parts.first().unit}"
        return f.copy(name = name, macros = m, parts = parts, amounts = a)
    }

    private val base: List<Food> = listOf(
        Food("coconut_water", "UFC Refresh coconut water, 250 ml", Macros(55.0, 0.5, 13.0, 0.0)),
        Food("bread", "FairPrice Enriched White Bread, 1 slice", Macros(85.0, 3.5, 15.9, 0.8, 1.05), "label"),
        Food("honey20", "Honey, 20 g", Macros(66.0, 0.1, 17.8, 0.0)),
        Food("honey10", "Honey, 10 g", Macros(33.0, 0.0, 8.9, 0.0)),
        Food("banana", "Banana, 1 medium", Macros(105.0, 1.3, 27.0, 0.4, 3.1)),
        Food("caffeine", "Caffeine pill, 200 mg", Macros.ZERO),
        Food("shake", "Whey 32 g + creatine 3 g, 100 ml HL milk + 100 ml NutriSoy", Macros(219.0, 32.5, 10.9, 4.6, 1.3),
            "labels"),
        // salad: brown/red rice ~150 g, citrus-lime chicken breast ~110 g, a scoop each of chickpeas and edamame,
        // 1 egg, a little broccoli, ~10 ml roasted sesame dressing (USDA values per part)
        Food("salad_egg", "Salad bowl: citrus-lime chicken, brown rice, broccoli, chickpeas, edamame, egg, sesame dressing",
            Macros(610.0, 54.0, 55.0, 18.0, 9.0), "built from its parts"),
        Food("salad_carrot", "Salad bowl, carrot instead of egg", Macros(550.0, 48.0, 58.0, 13.5, 10.0), "built from its parts"),
        // HPB steamed chicken rice plate (488 kcal, ~21 g protein) + kailan in oyster sauce + braised egg + soup
        Food("chicken_rice", "Steamed chicken rice, kailan, braised egg, soup",
            Macros(645.0, 31.0, 89.0, 21.0, 4.0), "HPB plate + sides"),
        // 1 cup rice ~200 g, 3 scoops curry chicken + mushroom, tofu, tomato egg, cabbage (per-part estimates)
        Food("mixed_rice", "Mixed rice: 1 cup rice, curry chicken & mushroom, tofu, tomato egg, cabbage",
            Macros(870.0, 43.0, 80.0, 40.0, 4.0), "built from its parts"),
        Food("mayvers10", "Peanut butter (unsalted), 10 g", Macros(58.5, 2.45, 2.15, 4.2, 0.75), "label"),
        Food("clusters", "Red Tractor protein clusters, 50 g", Macros(207.0, 8.3, 16.2, 10.1, 8.5), "label"),
        Food("yoghurt", "Marigold mixed berries non-fat yoghurt, 125 g", Macros(95.0, 5.0, 18.1, 0.2)),
        // fruit: one serving of about 150 g (USDA values per 100 g x 1.5)
        Food("kiwi2", "2 green kiwis (150 g)", Macros(92.0, 1.7, 22.0, 0.8, 4.5)),
        Food("blueberries", "Blueberries, 125 g", Macros(71.0, 0.9, 18.0, 0.4, 3.0)),
        Food("mixed_fruit", "Mixed fruit (apple, orange, guava), 150 g", Macros(84.0, 1.3, 20.0, 0.4, 4.0)),
        Food("guava", "Guava, 150 g", Macros(102.0, 3.9, 21.5, 1.4, 8.1)),
        Food("dragon_fruit", "Red dragon fruit, 150 g", Macros(90.0, 1.8, 19.5, 0.6, 4.5)),
        Food("mango", "Mango, 150 g", Macros(90.0, 1.2, 22.5, 0.6, 2.4)),
        Food("apple", "Apple, 150 g", Macros(78.0, 0.4, 20.7, 0.3, 3.6)),
        Food("orange", "Orange, 150 g", Macros(70.0, 1.4, 17.7, 0.2, 3.6)),
        Food("supplements", "Zinc 30 mg, magnesium glycinate 200 mg, D3/K2, omega-3", Macros(10.0, 0.0, 0.0, 1.0)),
        Food("psyllium", "Psyllium husk, 5 g (with water)", Macros(20.0, 0.0, 4.0, 0.0, 4.0), "label"),
        Food("meiji", "Meiji High Protein milk, 350 ml", Macros(240.0, 32.0, 20.0, 3.5), "online values"),
        Food("konjac", "Konjac jelly", Macros(5.0, 0.0, 1.2, 0.0, 0.3)),
    )
    private val byId = base.associateBy { it.id }
    val all: List<Food> get() = base.map { withAmounts(it, usual[it.id]) }
    /** The food with your usual amounts. */
    fun get(id: String): Food = withAmounts(byId.getValue(id), usual[id])
    fun find(id: String): Food? = byId[id]?.let { withAmounts(it, usual[id]) }
    /** The food as listed (no amounts applied). */
    fun base(id: String): Food? = byId[id]
}

/** Parts of the day, in order. */
enum class Slot(val title: String, val hint: String) {
    BREAKFAST("Breakfast", "pre-workout, on waking"),
    POST_WORKOUT("Post-workout", "shake, measure everything"),
    LUNCH("Lunch", "salad early on weekdays: the shop may close"),
    SNACK("Afternoon snack", "before dinner"),
    DINNER("Dinner", ""),
    YOGHURT("Yoghurt bowl", "9 PM"),
    SUPPLEMENTS("Supplements", "10 PM"),
    EXTRA("Extras", "going out, lab days, anything else"),
}

/** Targets from the plan. */
object Targets {
    val protein = 120.0..125.0
    val fat = 60.0..70.0
    const val burnKcal = 3000          // daily average burned (tracker estimate)
    const val cardioLoadGoogle = 800   // Google Health's Cardio Load target (not readable by apps)
    const val walkAfterMealMin = 15
    const val psylliumG = 5
    val reviewDate: LocalDate = LocalDate.of(2026, 11, 13)
}

data class PlannedItem(val slot: Slot, val food: Food, val optional: Boolean = false)

object MealPlan {
    /** Every fruit you have in the bowl, for swapping on any day. */
    val allFruits: List<String> = listOf("kiwi2", "blueberries", "mixed_fruit", "apple", "orange", "guava", "dragon_fruit", "mango")

    /** Fruit for the 9 PM yoghurt bowl. Saturday: guava or red dragon fruit. */
    fun fruit(d: DayOfWeek): List<Food> = when (d) {
        DayOfWeek.MONDAY, DayOfWeek.THURSDAY -> listOf(Foods.get("kiwi2"))
        DayOfWeek.TUESDAY, DayOfWeek.FRIDAY -> listOf(Foods.get("blueberries"))
        DayOfWeek.WEDNESDAY -> listOf(Foods.get("mixed_fruit"))
        DayOfWeek.SATURDAY -> listOf(Foods.get("guava"), Foods.get("dragon_fruit"))
        DayOfWeek.SUNDAY -> listOf(Foods.get("mango"))
    }

    fun weekend(d: DayOfWeek) = d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY

    /** The salad has carrot instead of egg on Tuesdays and Thursdays. */
    fun salad(d: DayOfWeek): Food =
        if (d == DayOfWeek.TUESDAY || d == DayOfWeek.THURSDAY) Foods.get("salad_carrot") else Foods.get("salad_egg")

    /** Main-meal choices for lunch and dinner (order is flexible). */
    fun mains(d: DayOfWeek): List<Food> =
        if (weekend(d)) listOf(Foods.get("chicken_rice"), Foods.get("mixed_rice"))
        else listOf(salad(d), Foods.get("chicken_rice"))

    /** The plan for one day, slot by slot. */
    fun day(date: LocalDate): List<PlannedItem> {
        val d = date.dayOfWeek
        val (lunch, dinner) = mains(d)
        val fruits = fruit(d)
        return buildList {
            add(PlannedItem(Slot.BREAKFAST, Foods.get("coconut_water")))
            add(PlannedItem(Slot.BREAKFAST, Foods.get("bread")))
            add(PlannedItem(Slot.BREAKFAST, Foods.get("honey20")))
            add(PlannedItem(Slot.BREAKFAST, Foods.get("banana")))
            add(PlannedItem(Slot.BREAKFAST, Foods.get("caffeine")))
            add(PlannedItem(Slot.POST_WORKOUT, Foods.get("shake")))
            add(PlannedItem(Slot.LUNCH, lunch))
            add(PlannedItem(Slot.SNACK, Foods.get("bread")))
            add(PlannedItem(Slot.SNACK, Foods.get("mayvers10")))
            add(PlannedItem(Slot.SNACK, Foods.get("honey10")))
            add(PlannedItem(Slot.DINNER, dinner))
            add(PlannedItem(Slot.YOGHURT, Foods.get("clusters")))
            add(PlannedItem(Slot.YOGHURT, Foods.get("yoghurt")))
            fruits.forEachIndexed { i, f -> add(PlannedItem(Slot.YOGHURT, f, optional = i > 0)) }
            add(PlannedItem(Slot.SUPPLEMENTS, Foods.get("supplements")))
            add(PlannedItem(Slot.SUPPLEMENTS, Foods.get("psyllium")))
        }
    }

    /** Planned totals for a day (first fruit option, every main as planned). */
    fun plannedTotal(date: LocalDate): Macros =
        day(date).filter { !it.optional }.fold(Macros.ZERO) { a, p -> a + p.food.macros }

    /** The plan as text, for the chat's context. */
    fun describe(): String = """
        Daily targets: protein 120-125 g, fat ~60-70 g. Activity: average 3,000 kcal burned/day (tracker estimate), Google Health Cardio Load target 800, walk 15 min after every meal. Plan review date: ${Targets.reviewDate}.
        Breakfast (pre-workout): 250 ml UFC Refresh coconut water on waking, 1 slice FairPrice Enriched White Bread, 20 g honey, 1 banana, caffeine pill 200 mg.
        Post-workout: ${Foods.get("shake").amounts["whey"]?.toInt() ?: 32} g Optimum Nutrition Gold Standard Whey (Mocha Cappuccino) + 3 g creatine in 100 ml Marigold HL Green + 100 ml NutriSoy No Sugar Added (~32 g protein, ~4.8 g fat). Measure everything.
        Mains: (1) Salad bowl: citrus-lime chicken breast, Japanese roasted sesame dressing (ask for less), brown rice + broccoli, chickpeas + edamame + egg (carrot instead of egg on Tue/Thu). (2) Steamed chicken rice set + extra skinless chicken (ask for breast), extra rice, bok choy, half a boiled egg. (3) Mixed rice: rice, 2 portions curry chicken, tofu, egg, cabbage or broccoli.
        Rotation: Mon-Fri salad + chicken rice; Sat and Sun chicken rice + mixed rice. Salad is usually weekday lunch and should be early (shop may close); chicken rice can be lunch or dinner; order flexible.
        Afternoon snack: 1 slice bread, 10 g unsalted peanut butter, 10 g honey.
        9 PM yoghurt bowl: 50 g Red Tractor protein clusters + 125 g Marigold mixed berries non-fat yoghurt + fruit (Mon 2 kiwis, Tue 125 g blueberries, Wed 1 cup apple/orange/guava, Thu 2 kiwis, Fri 125 g blueberries, Sat 1 cup guava or red dragon fruit, Sun 1 cup mango).
        10 PM supplements: NOW L-OptiZinc 30 mg, NOW magnesium glycinate 200 mg, NOW D3/K2 1,000 IU + 45 mcg, NOW Ultra Omega-3 500 mg EPA + 250 mg DHA.
        Psyllium husk 5 g once daily around a low-fibre meal with water. Honey 30 g/day. Peanut butter 20 g/day per plan (the snack lists 10 g). Coconut water 250 ml on waking.
        Going out: Meiji 30 g protein drink, skip afternoon snack, enjoy dessert. Lab days: no unnecessary snacking, konjac jelly if needed, no candy or gum.
        Monitor morning weight and compare completed 7-day averages. Review: compare 7-day averages, strength progression, recovery, hunger; if gaining too fast, small calorie cut (peanut butter first); if on track, keep the plan.
    """.trimIndent()
}
