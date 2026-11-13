package com.silverajat.lifttracker.health

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The last Fitbit days read from Health Connect, saved on the phone so the
 * app opens with data straight away (reading 31 days of heart rate takes a
 * while). Refreshed in the background and on every open.
 */
class HealthCache(root: File) {
    private val f = File(root, "health_cache.json")

    data class Saved(val days: List<HealthDay>, val savedMs: Long, val fullMs: Long)

    fun load(): Saved? = runCatching {
        val o = JSONObject(f.readText())
        val a = o.getJSONArray("days")
        Saved((0 until a.length()).map { day(a.getJSONObject(it)) }, o.getLong("saved"), o.optLong("full", 0))
    }.getOrNull()

    fun save(days: List<HealthDay>, full: Boolean) {
        runCatching {
            val prevFull = load()?.fullMs ?: 0
            val o = JSONObject().put("saved", System.currentTimeMillis()).put("full", if (full) System.currentTimeMillis() else prevFull)
                .put("days", JSONArray(days.map { json(it) }))
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    /** Older days from the cache, the most recent ones fresh. */
    fun merge(cached: List<HealthDay>, fresh: List<HealthDay>): List<HealthDay> {
        val byDate = cached.associateBy { it.date }.toMutableMap()
        fresh.forEach { byDate[it.date] = it }
        val today = LocalDate.now()
        return byDate.values.filter { !it.date.isBefore(today.minusDays(100)) }.sortedBy { it.date }
    }

    private fun Instant.ms() = toEpochMilli()
    private fun ins(ms: Long) = Instant.ofEpochMilli(ms)

    private fun json(d: HealthDay): JSONObject = JSONObject().apply {
        put("date", d.date.toString())
        d.steps?.let { put("steps", it) }
        d.kcalTotal?.let { put("kcal", it) }
        d.kcalActive?.let { put("kcalA", it) }
        d.restingHr?.let { put("rhr", it) }
        d.hrvMs?.let { put("hrv", it) }
        d.spo2?.let { put("spo2", it) }
        d.respRate?.let { put("resp", it) }
        d.sleep?.let { s ->
            put("sleep", JSONObject().put("s", s.start.ms()).put("e", s.end.ms()).put("tot", s.totalMin).put("deep", s.deepMin)
                .put("rem", s.remMin).put("light", s.lightMin).put("awake", s.awakeMin)
                .put("st", JSONArray().apply { s.stages.forEach { put(it.start.ms()); put(it.end.ms()); put(it.type) } }))
        }
        put("w", JSONArray(d.workouts.map { w ->
            JSONObject().put("s", w.start.ms()).put("e", w.end.ms()).put("type", w.type).put("title", w.title ?: "")
                .apply { w.avgHr?.let { put("avg", it) }; w.maxHr?.let { put("max", it) }; w.trimp?.let { put("trimp", it) }; w.distanceKm?.let { put("km", it) } }
        }))
        d.load?.let { l -> put("load", JSONObject().put("t", l.trimp).put("m", l.moderateMin).put("v", l.vigorousMin).put("n", l.samples).put("z", JSONArray(l.zones))) }
    }

    private fun day(o: JSONObject): HealthDay {
        fun dbl(k: String) = if (o.has(k)) o.getDouble(k) else null
        fun int(k: String) = if (o.has(k)) o.getInt(k) else null
        val sleep = o.optJSONObject("sleep")?.let { s ->
            val st = s.optJSONArray("st") ?: JSONArray()
            SleepSummary(ins(s.getLong("s")), ins(s.getLong("e")), s.getInt("tot"), s.getInt("deep"), s.getInt("rem"), s.getInt("light"), s.getInt("awake"),
                (0 until st.length() / 3).map { i -> SleepStage(ins(st.getLong(i * 3)), ins(st.getLong(i * 3 + 1)), st.getInt(i * 3 + 2)) })
        }
        val wa = o.optJSONArray("w") ?: JSONArray()
        val workouts = (0 until wa.length()).map { i ->
            val w = wa.getJSONObject(i)
            Workout(ins(w.getLong("s")), ins(w.getLong("e")), w.getString("type"), w.optString("title").ifEmpty { null },
                if (w.has("avg")) w.getInt("avg") else null, if (w.has("max")) w.getInt("max") else null,
                if (w.has("trimp")) w.getInt("trimp") else null, if (w.has("km")) w.getDouble("km") else null)
        }
        val load = o.optJSONObject("load")?.let { l ->
            val z = l.optJSONArray("z") ?: JSONArray()
            Load(l.getInt("t"), l.getInt("m"), l.getInt("v"), l.getInt("n"), (0 until z.length()).map { z.getInt(it) })
        }
        return HealthDay(
            date = LocalDate.parse(o.getString("date")), steps = if (o.has("steps")) o.getLong("steps") else null,
            kcalTotal = dbl("kcal"), kcalActive = dbl("kcalA"), restingHr = int("rhr"), hrvMs = dbl("hrv"), spo2 = dbl("spo2"),
            respRate = dbl("resp"), sleep = sleep, workouts = workouts, load = load,
        )
    }
}
