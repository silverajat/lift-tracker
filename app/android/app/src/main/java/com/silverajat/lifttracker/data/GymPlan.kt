package com.silverajat.lifttracker.data

import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which session goes on which weekday. Starts as Mon/Wed/Fri upper,
 * Tue/Sat lower, Thu/Sun rest; change it from the gym tab.
 * A null day is a rest day.
 */
class GymPlan(root: File) {
    private val f = File(root, "gym_plan.json")

    fun week(): Map<DayOfWeek, Day?> = runCatching {
        val o = JSONObject(f.readText())
        DayOfWeek.entries.associateWith { d -> o.optString(d.name).takeIf { it.isNotEmpty() && it != "REST" }?.let { Day.valueOf(it) } }
    }.getOrDefault(DEFAULT)

    fun set(week: Map<DayOfWeek, Day?>) {
        val o = JSONObject()
        DayOfWeek.entries.forEach { o.put(it.name, week[it]?.name ?: "REST") }
        runCatching { f.writeText(o.toString()) }
    }

    companion object {
        val DEFAULT: Map<DayOfWeek, Day?> = mapOf(
            DayOfWeek.MONDAY to Day.UPPER, DayOfWeek.TUESDAY to Day.LOWER, DayOfWeek.WEDNESDAY to Day.UPPER,
            DayOfWeek.THURSDAY to null, DayOfWeek.FRIDAY to Day.UPPER, DayOfWeek.SATURDAY to Day.LOWER,
            DayOfWeek.SUNDAY to null,
        )

        /** The next planned session after [from] (not counting [from] itself), within a week. */
        fun next(week: Map<DayOfWeek, Day?>, from: LocalDate): Pair<LocalDate, Day>? =
            (1..7).map { from.plusDays(it.toLong()) }.firstNotNullOfOrNull { d -> week[d.dayOfWeek]?.let { d to it } }
    }
}
