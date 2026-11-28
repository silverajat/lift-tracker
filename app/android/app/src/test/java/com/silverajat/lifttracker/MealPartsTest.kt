package com.silverajat.lifttracker

import com.silverajat.lifttracker.meals.Foods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MealPartsTest {
    @Test
    fun mixedRiceAddsUpAndLosesTheTofu() {
        val usual = Foods.withAmounts(Foods.base("mixed_rice_red")!!, null)
        assertEquals(700.0, usual.macros.kcal, 10.0)
        assertEquals(43.0, usual.macros.protein, 0.5)
        assertTrue(usual.name, usual.name.startsWith("Mixed rice with red rice:"))
        val noTofu = Foods.withAmounts(Foods.base("mixed_rice_red")!!, usual.amounts + ("tofu" to 0.0))
        assertEquals("Mixed rice with red rice (no braised tofu)", noTofu.name)
        assertEquals(37.0, noTofu.macros.protein, 0.5)
    }

    @Test
    fun saladSwapsChickpeasForKidneyBeans() {
        val b = Foods.base("salad_egg")!!
        val usual = Foods.withAmounts(b, null)
        assertEquals(700.0, usual.macros.kcal, 15.0)
        val swap = Foods.withAmounts(b, usual.amounts + ("chickpeas" to 0.0) + ("kidney" to 1.0))
        assertEquals("Salad bowl (no chickpeas, +kidney beans)", swap.name)
        val carrot = Foods.withAmounts(Foods.base("salad_carrot")!!, null)
        assertTrue(carrot.name, carrot.name.startsWith("Salad bowl:"))
    }

    @Test
    fun weetBixTwiceADay() {
        val w = Foods.get("weetbix")
        assertEquals(110.0, w.macros.kcal, 0.5)
        assertEquals(4.0, w.macros.fibre, 0.05)
        assertEquals("Weet-Bix Original, 2 biscuits", w.name)
        assertEquals(220.0, Foods.withAmounts(Foods.base("weetbix")!!, mapOf("biscuits" to 4.0)).macros.kcal, 0.5)
        val day = com.silverajat.lifttracker.meals.MealPlan.day(java.time.LocalDate.of(2026, 11, 30))
        assertTrue(day.none { it.food.id == "clusters" })
        assertEquals(2, day.count { it.food.id.startsWith("weetbix") })
    }
}
