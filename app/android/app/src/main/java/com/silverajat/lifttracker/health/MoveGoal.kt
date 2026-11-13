package com.silverajat.lifttracker.health

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Is how much you move enough for your weight goal?
 *
 * Net calories (from the scale-checked burn) tell how far your days are from
 * the goal. If you're eating the same, the difference has to come from
 * moving more (or less). That gap is turned into things you actually do,
 * sized to your body weight:
 *   running: ~1.0 kcal per kg per km
 *   football: ~7 METs, badminton: ~5.5 METs (kcal/min = METs × kg / 60)
 */
object MoveGoal {
    data class Result(
        val gapPerDay: Int,          // + = you'd need to burn this much more a day (or eat less)
        val weekKcal: Int,           // the same over a week
        val runKm: Double,           // as extra running
        val footballMin: Int,
        val badmintonMin: Int,
        val burnPerDay: Int,         // scale-checked burn, typical day
        val burnGoalPerDay: Int,     // what you'd need to burn at your current eating
        val weightKg: Double,
        val verdict: String,
    )

    fun compute(e: Energy.Result, goalKgPerWeek: Double, weightKg: Double?): Result? {
        val net = e.avgNet ?: return null
        val kg = weightKg ?: e.trendKg ?: return null
        val goal = e.netFor(goalKgPerWeek)
        val gap = net - goal
        val burn = (e.typicalFitbit * e.k).roundToInt()
        val week = gap * 7
        val run = abs(week) / (kg * 1.0)
        val foot = (abs(week) / (7.0 * kg / 60)).roundToInt()
        val bad = (abs(week) / (5.5 * kg / 60)).roundToInt()
        val what = if (goalKgPerWeek == 0.0) "staying at maintenance" else if (goalKgPerWeek > 0) "your lean-gain goal" else "your cut"
        val verdict = when {
            abs(gap) <= 100 -> "You're moving the right amount for $what. Keep this week like the last two."
            gap > 0 -> "To hit $what without eating less, move about ${"%,d".format(week)} kcal more this week: roughly %.1f km of extra running, %d min of football or %d min of badminton.".format(run, foot, bad)
            else -> "You're burning about ${"%,d".format(-week)} kcal a week more than $what needs. Eat a bit more on active days, or this is room for an easy week."
        }
        return Result(gap, week, run, foot, bad, burn, burn + gap, kg, verdict)
    }
}
