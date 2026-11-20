package com.silverajat.lifttracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrengthTest {
    /** No weight changes yet: nothing to learn from, so the research prior. */
    @Test fun priorUntilTheWeightChanges() {
        assertEquals(38.0, Strength.fit(38.0, listOf(62.0 to 10, 62.0 to 11, 62.0 to 12)), 0.0)
    }

    /** Pec dec: 62×12 then 63×10. A 1.6% heavier weight cost 2 reps, so a rep is worth less here than Epley thinks. */
    @Test fun learnsFromAWeightChange() {
        val k = Strength.fit(38.0, listOf(62.0 to 10, 62.0 to 11, 62.0 to 12, 63.0 to 10))
        assertTrue("k $k", k in 40.0..50.0)
        // as strong as 62×12, you'd expect about 11 at 63
        val expect = Strength.repsAt(Strength.oneRm(62.0, 12, k), 63.0, k)
        assertTrue("expected $expect", expect in 10.5..11.8)
    }

    /** The same +1.25 kg step costs far more reps on a light lift than a heavy one. */
    @Test fun stepCostIsRelative() {
        val light = Strength.stepCost(8.3, 11, 1.25, 38.0)
        val heavy = Strength.stepCost(62.0, 12, 1.25, 38.0)
        assertTrue("light $light", light >= 5)
        assertTrue("heavy $heavy", heavy <= 1)
    }

    /** Lower-body compounds trade more reps per kilo (leg press ~13 reps at 80% vs bench ~9). */
    @Test fun priorsFollowTheResearch() {
        assertEquals(Strength.Kind.LOWER_COMPOUND, Strength.kind(21))
        assertEquals(Strength.Kind.UPPER_COMPOUND, Strength.kind(1))
        val legPress80 = Strength.repsAt(100.0, 80.0, Strength.Kind.LOWER_COMPOUND.prior)
        val bench80 = Strength.repsAt(100.0, 80.0, Strength.Kind.UPPER_COMPOUND.prior)
        assertTrue("leg press $legPress80", legPress80 in 11.5..14.0)
        assertTrue("bench $bench80", bench80 in 8.0..9.5)
    }
}
