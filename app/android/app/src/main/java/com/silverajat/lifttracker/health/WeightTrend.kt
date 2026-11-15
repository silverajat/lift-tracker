package com.silverajat.lifttracker.health

import com.silverajat.lifttracker.meals.WeightEntry
import java.time.LocalDate

/**
 * The one smoothed body weight used everywhere: each day moves 10% of the way
 * towards that day's weigh-in (the Hacker's Diet method); days without one carry it on.
 */
object WeightTrend {
    data class Point(val date: LocalDate, val scale: Double?, val trend: Double)

    fun points(w: List<WeightEntry>, today: LocalDate = LocalDate.now()): List<Point> {
        if (w.isEmpty()) return emptyList()
        val byDay = w.associate { it.date to it.kg }
        val first = w.minOf { it.date }
        val last = maxOf(w.maxOf { it.date }, today)
        var t = byDay.getValue(first)
        val out = ArrayList<Point>()
        var d = first
        while (!d.isAfter(last)) {
            byDay[d]?.let { t += 0.1 * (it - t) }
            out += Point(d, byDay[d], t)
            d = d.plusDays(1)
        }
        return out
    }

    fun now(w: List<WeightEntry>, today: LocalDate = LocalDate.now()): Double? = points(w, today).lastOrNull()?.trend
}
