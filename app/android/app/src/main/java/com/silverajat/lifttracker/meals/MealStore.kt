package com.silverajat.lifttracker.meals

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** Something you ate, when, and its (estimated) macros. */
data class MealEntry(
    val id: String,
    val date: LocalDate,
    val slot: Slot,
    val foodId: String?,      // null for a custom item
    val name: String,
    val macros: Macros,       // for the portion eaten
    val timeMs: Long,
    val portion: Double = 1.0,
)

data class WeightEntry(val date: LocalDate, val kg: Double, val timeMs: Long)

/** One completed week (Mon-Sun) of morning weights. */
data class WeekAverage(val weekStart: LocalDate, val avgKg: Double, val days: Int)

/**
 * Meal log (files/meals/<yyyy-mm-dd>.json, one file per day) and morning
 * weights (files/weights.json). Plain files in the app's private storage.
 */
class MealStore(root: File) {
    private val dir = File(root, "meals").apply { mkdirs() }
    private val weightFile = File(root, "weights.json")

    private fun file(d: LocalDate) = File(dir, "$d.json")

    fun day(d: LocalDate): List<MealEntry> {
        val f = file(d)
        if (!f.exists()) return emptyList()
        return runCatching {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { fromJson(a.getJSONObject(it)) }.sortedBy { it.timeMs }
        }.getOrDefault(emptyList())
    }

    fun days(from: LocalDate, to: LocalDate): Map<LocalDate, List<MealEntry>> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.associateWith { day(it) }

    fun add(e: MealEntry) = write(e.date, day(e.date) + e)

    fun remove(e: MealEntry) = write(e.date, day(e.date).filterNot { it.id == e.id })

    fun update(e: MealEntry) = write(e.date, day(e.date).map { if (it.id == e.id) e else it })

    private fun write(d: LocalDate, list: List<MealEntry>) {
        val tmp = File(dir, "$d.tmp")
        tmp.writeText(JSONArray(list.map(::toJson)).toString(1))
        tmp.renameTo(file(d))
    }

    // ---- weight -------------------------------------------------------------

    fun weights(): List<WeightEntry> = runCatching {
        val a = JSONArray(weightFile.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            WeightEntry(LocalDate.parse(o.getString("date")), o.getDouble("kg"), o.getLong("timeMs"))
        }.sortedBy { it.date }
    }.getOrDefault(emptyList())

    /** One weight per day: a new one replaces that day's. */
    fun setWeight(w: WeightEntry) {
        val list = weights().filterNot { it.date == w.date } + w
        weightFile.writeText(JSONArray(list.sortedBy { it.date }.map {
            JSONObject().put("date", it.date.toString()).put("kg", it.kg).put("timeMs", it.timeMs)
        }).toString(1))
    }

    fun deleteWeight(d: LocalDate) {
        val list = weights().filterNot { it.date == d }
        weightFile.writeText(JSONArray(list.map {
            JSONObject().put("date", it.date.toString()).put("kg", it.kg).put("timeMs", it.timeMs)
        }).toString(1))
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        fun totals(list: List<MealEntry>): Macros = list.fold(Macros.ZERO) { a, e -> a + e.macros }

        /**
         * Averages of COMPLETED weeks (Monday to Sunday, before the week
         * that contains today), newest first. Weeks with fewer than 3
         * weigh-ins are still listed, with their day count.
         */
        fun weekAverages(weights: List<WeightEntry>, today: LocalDate): List<WeekAverage> {
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return weights.filter { it.date.isBefore(thisWeek) }
                .groupBy { it.date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
                .map { (start, ws) -> WeekAverage(start, ws.map { it.kg }.average(), ws.size) }
                .sortedByDescending { it.weekStart }
        }

        private fun toJson(e: MealEntry) = JSONObject().apply {
            put("id", e.id); put("date", e.date.toString()); put("slot", e.slot.name)
            put("foodId", e.foodId ?: JSONObject.NULL); put("name", e.name)
            put("kcal", e.macros.kcal); put("protein", e.macros.protein); put("carbs", e.macros.carbs)
            put("fat", e.macros.fat); put("fibre", e.macros.fibre); put("timeMs", e.timeMs); put("portion", e.portion)
        }

        private fun fromJson(o: JSONObject) = MealEntry(
            id = o.getString("id"),
            date = LocalDate.parse(o.getString("date")),
            slot = runCatching { Slot.valueOf(o.getString("slot")) }.getOrDefault(Slot.EXTRA),
            foodId = if (o.isNull("foodId")) null else o.getString("foodId"),
            name = o.getString("name"),
            macros = Macros(o.getDouble("kcal"), o.getDouble("protein"), o.getDouble("carbs"),
                o.getDouble("fat"), o.optDouble("fibre", 0.0)),
            timeMs = o.getLong("timeMs"),
            portion = o.optDouble("portion", 1.0),
        )
    }
}
