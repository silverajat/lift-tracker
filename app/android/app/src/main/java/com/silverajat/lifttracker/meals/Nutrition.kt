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

data class Food(val id: String, val name: String, val macros: Macros, val note: String = "")

object Foods {
    val all: List<Food> = listOf(
        Food("coconut_water", "UFC Refresh coconut water, 250 ml", Macros(48.0, 0.5, 11.0, 0.0)),
        Food("bread", "FairPrice Enriched White Bread, 1 slice", Macros(72.0, 2.5, 13.5, 0.9, 0.7)),
        Food("honey20", "Honey, 20 g", Macros(61.0, 0.1, 16.5, 0.0)),
        Food("honey10", "Honey, 10 g", Macros(30.0, 0.0, 8.2, 0.0)),
        Food("banana", "Banana, 1 medium", Macros(105.0, 1.3, 27.0, 0.4, 3.1)),
        Food("caffeine", "Caffeine pill, 200 mg", Macros.ZERO),
        Food("shake", "Whey 29 g + creatine 3 g, 100 ml HL milk + 100 ml NutriSoy", Macros(205.0, 32.0, 9.0, 4.8, 0.5),
            "protein and fat from your labels"),
        Food("salad_egg", "Salad bowl: citrus-lime chicken, brown rice, broccoli, chickpeas, edamame, egg, sesame dressing",
            Macros(650.0, 42.0, 62.0, 24.0, 12.0), "estimate"),
        Food("salad_carrot", "Salad bowl, carrot instead of egg", Macros(590.0, 36.0, 64.0, 19.0, 13.0), "estimate"),
        Food("chicken_rice", "Steamed chicken rice + extra skinless chicken, extra rice, bok choy, ½ egg",
            Macros(950.0, 55.0, 125.0, 24.0, 3.0), "estimate"),
        Food("mixed_rice", "Mixed rice: rice, 2× curry chicken, tofu, egg, cabbage/broccoli",
            Macros(1000.0, 60.0, 85.0, 42.0, 5.0), "estimate"),
        Food("mayvers10", "Mayver's choc hazelnut peanut butter, 10 g", Macros(62.0, 2.4, 2.2, 5.0, 0.8)),
        Food("clusters", "Red Tractor protein clusters, 50 g", Macros(215.0, 10.0, 28.0, 7.0, 4.0)),
        Food("yoghurt", "Marigold mixed berries non-fat yoghurt, 125 g", Macros(105.0, 5.5, 20.0, 0.2)),
        Food("kiwi2", "2 green kiwis", Macros(85.0, 1.6, 20.0, 0.7, 4.2)),
        Food("blueberries", "Blueberries, 125 g", Macros(71.0, 0.9, 18.0, 0.4, 3.0)),
        Food("mixed_fruit", "Mixed fruit (apple, orange, guava), 1 cup", Macros(80.0, 1.5, 20.0, 0.4, 4.0)),
        Food("guava", "Guava, 1 cup", Macros(112.0, 4.2, 24.0, 1.6, 9.0)),
        Food("dragon_fruit", "Red dragon fruit, 1 cup", Macros(100.0, 2.0, 22.0, 0.5, 5.0)),
        Food("mango", "Mango, 1 cup", Macros(99.0, 1.4, 25.0, 0.6, 2.6)),
        Food("apple", "Apple, 1 medium", Macros(95.0, 0.5, 25.0, 0.3, 4.4)),
        Food("orange", "Orange, 1 medium", Macros(62.0, 1.2, 15.0, 0.2, 3.1)),
        Food("supplements", "Zinc 30 mg, magnesium glycinate 200 mg, D3/K2, omega-3", Macros(10.0, 0.0, 0.0, 1.0)),
        Food("psyllium", "Psyllium husk, 5 g (with water)", Macros(15.0, 0.1, 4.5, 0.0, 4.0)),
        Food("meiji", "Meiji protein drink (30 g protein)", Macros(170.0, 30.0, 10.0, 1.5), "estimate"),
        Food("konjac", "Konjac jelly", Macros(15.0, 0.0, 4.0, 0.0, 0.5)),
    )
    private val byId = all.associateBy { it.id }
    fun get(id: String): Food = byId.getValue(id)
    fun find(id: String): Food? = byId[id]
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
        Post-workout: 29 g Optimum Nutrition Gold Standard Whey (Mocha Cappuccino) + 3 g creatine in 100 ml Marigold HL Green + 100 ml NutriSoy No Sugar Added (~32 g protein, ~4.8 g fat). Measure everything.
        Mains: (1) Salad bowl: citrus-lime chicken breast, Japanese roasted sesame dressing (ask for less), brown rice + broccoli, chickpeas + edamame + egg (carrot instead of egg on Tue/Thu). (2) Steamed chicken rice set + extra skinless chicken (ask for breast), extra rice, bok choy, half a boiled egg. (3) Mixed rice: rice, 2 portions curry chicken, tofu, egg, cabbage or broccoli.
        Rotation: Mon-Fri salad + chicken rice; Sat and Sun chicken rice + mixed rice. Salad is usually weekday lunch and should be early (shop may close); chicken rice can be lunch or dinner; order flexible.
        Afternoon snack: 1 slice bread, 10 g Mayver's Chocolate Hazelnut Peanut Butter, 10 g honey.
        9 PM yoghurt bowl: 50 g Red Tractor protein clusters + 125 g Marigold mixed berries non-fat yoghurt + fruit (Mon 2 kiwis, Tue 125 g blueberries, Wed 1 cup apple/orange/guava, Thu 2 kiwis, Fri 125 g blueberries, Sat 1 cup guava or red dragon fruit, Sun 1 cup mango).
        10 PM supplements: NOW L-OptiZinc 30 mg, NOW magnesium glycinate 200 mg, NOW D3/K2 1,000 IU + 45 mcg, NOW Ultra Omega-3 500 mg EPA + 250 mg DHA.
        Psyllium husk 5 g once daily around a low-fibre meal with water. Honey 30 g/day. Peanut butter 20 g/day per plan (the snack lists 10 g). Coconut water 250 ml on waking.
        Going out: Meiji 30 g protein drink, skip afternoon snack, enjoy dessert. Lab days: no unnecessary snacking, konjac jelly if needed, no candy or gum.
        Monitor morning weight and compare completed 7-day averages. Review: compare 7-day averages, strength progression, recovery, hunger; if gaining too fast, small calorie cut (peanut butter first); if on track, keep the plan.
    """.trimIndent()
}
