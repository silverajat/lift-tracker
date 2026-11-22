package com.silverajat.lifttracker

import com.silverajat.lifttracker.meals.PhotoMeal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoMealTest {
    private fun reply(chicken: Int) = """Sure! {"name":"Mixed rice","match":"mixed_rice_red","items":[
        {"name":"Red rice","grams":210,"protein":5,"carbs":52,"fat":1.5,"fibre":3},
        {"name":"Braised chicken","grams":$chicken,"protein":25,"carbs":9,"fat":10,"fibre":2}],
        "notes":["more gravy than usual"],"questions":[{"q":"Fried tofu?","options":["Fried","Braised"]}]}"""

    @Test
    fun parsesAndAddsUp() {
        val e = PhotoMeal.parse(reply(150))
        assertEquals("mixed_rice_red", e.match)
        assertEquals(2, e.items.size)
        val t = e.total
        assertEquals(4 * t.protein + 4 * t.carbs + 9 * t.fat + 2 * t.fibre, t.kcal, 1e-9)
        assertEquals(1, e.questions.size)
        // half the chicken: half its macros
        val half = e.items[1].at(75.0)
        assertEquals(12.5, half.protein, 1e-9)
        // a made-up meal id isn't a match
        assertNull(PhotoMeal.parse(reply(150).replace("mixed_rice_red", "pizza")).match)
    }

    @Test
    fun keepsTheMiddleRun() {
        val runs = listOf(
            PhotoMeal.parse(reply(150)),
            PhotoMeal.parse(reply(150).replace("\"protein\":25", "\"protein\":45")),
            PhotoMeal.parse(reply(150).replace("\"protein\":25", "\"protein\":5")),
        )
        val m = PhotoMeal.middle(runs)!!
        assertEquals(25.0, m.items[1].protein, 1e-9)
        assertTrue(m.rangeKcal!!.second - m.rangeKcal!!.first >= 150)
    }
}
