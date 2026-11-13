package com.silverajat.lifttracker.activity

import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.Workout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

enum class Sport(val label: String, val emoji: String) {
    RUN("Run", "🏃"), FOOTBALL("Football", "⚽"), BADMINTON("Badminton", "🏸"), GYM("Gym", "🏋️"),
    WALK("Walk", "🚶"), SWIM("Swim", "🏊"), CYCLE("Cycle", "🚴"), OTHER("Other", "⚡");

    companion object {
        fun ofFitbit(type: String) = when (type) {
            "Run" -> RUN; "Walk" -> WALK; "Swim" -> SWIM; "Bike" -> CYCLE
            "Football", "Soccer" -> FOOTBALL; "Badminton" -> BADMINTON
            "Strength training", "Weightlifting" -> GYM
            else -> OTHER
        }
    }
}

/** One GPS point of a run. */
data class RoutePoint(val lat: Double, val lng: Double, val t: Long, val alt: Double? = null)

/** Something you logged yourself, or a run you tracked with the phone's GPS. */
data class Activity(
    val id: String,
    val sport: Sport,
    val startMs: Long,
    val durationS: Int,             // moving time (pauses left out)
    val distanceKm: Double? = null,
    val note: String = "",
    val route: List<RoutePoint> = emptyList(),
    val splitsS: List<Int> = emptyList(),   // seconds for each full km
    val elapsedS: Int = durationS,          // wall time, start to finish
) {
    val endMs get() = startMs + elapsedS * 1000L
    val paceSPerKm: Int? get() = distanceKm?.takeIf { it > 0.2 }?.let { (durationS / it).roundToInt() }
}

class ActivityStore(root: File) {
    private val f = File(root, "activities.json")

    fun all(): List<Activity> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(x: Activity) = save(all().filter { it.id != x.id } + x)
    fun delete(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<Activity>) {
        f.writeText(JSONArray(list.sortedBy { it.startMs }.map { toJson(it) }).toString())
    }

    companion object {
        fun toJson(x: Activity): JSONObject = JSONObject().apply {
            put("id", x.id); put("sport", x.sport.name); put("start", x.startMs); put("dur", x.durationS); put("elapsed", x.elapsedS)
            x.distanceKm?.let { put("km", it) }
            put("note", x.note)
            put("splits", JSONArray(x.splitsS))
            // route as a flat array to keep the file small: lat, lng, t(s from start), alt
            put("route", JSONArray().apply {
                x.route.forEach { p -> put(p.lat); put(p.lng); put((p.t - x.startMs) / 1000.0); put(p.alt ?: Double.NaN.let { 0.0 }) }
            })
        }

        fun fromJson(o: JSONObject): Activity {
            val start = o.getLong("start")
            val r = o.optJSONArray("route") ?: JSONArray()
            val pts = (0 until r.length() / 4).map { i ->
                RoutePoint(r.getDouble(i * 4), r.getDouble(i * 4 + 1), start + (r.getDouble(i * 4 + 2) * 1000).toLong(), r.getDouble(i * 4 + 3))
            }
            val sp = o.optJSONArray("splits") ?: JSONArray()
            return Activity(
                o.getString("id"), runCatching { Sport.valueOf(o.getString("sport")) }.getOrDefault(Sport.OTHER), start,
                o.getInt("dur"), if (o.has("km")) o.getDouble("km") else null, o.optString("note"), pts,
                (0 until sp.length()).map { sp.getInt(it) }, o.optInt("elapsed", o.getInt("dur")),
            )
        }
    }
}

/**
 * Your corrections to what the Fitbit detected ("that soccer was a walk"),
 * keyed by the workout's start time. null sport = hidden (not an activity).
 */
class ActivityOverrides(root: File) {
    private val f = File(root, "activity_overrides.json")

    fun all(): Map<Long, Sport?> = runCatching {
        val o = JSONObject(f.readText())
        o.keys().asSequence().associate { k -> k.toLong() to o.getString(k).let { v -> if (v == "HIDDEN") null else Sport.valueOf(v) } }
    }.getOrDefault(emptyMap())

    fun set(startMs: Long, sport: Sport?) {
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        o.put(startMs.toString(), sport?.name ?: "HIDDEN")
        f.writeText(o.toString())
    }
}

/** Everything you did, from all sources, as one list for the Strain page. */
data class ActivityItem(
    val key: String,
    val sport: Sport,
    val title: String,
    val start: Instant,
    val end: Instant,
    val minutes: Int,
    val distanceKm: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val strain: Double?,
    val source: String,            // "Fitbit", "Logged", "GPS run", "Pod"
    val manualId: String? = null,
    val sessionId: Long? = null,
)

object ActivityLog {
    /**
     * Fitbit workouts, things you logged, GPS runs and gym sessions, newest
     * first. Where a logged activity overlaps a Fitbit workout, they're one
     * item: your name and distance, the Fitbit's heart rate.
     */
    fun merge(days: List<HealthDay>, manual: List<Activity>, manualHr: Map<String, Workout>, sessions: List<Session>,
              overrides: Map<Long, Sport?> = emptyMap()): List<ActivityItem> {
        // hidden ones are dropped; the rest keep your corrected type
        val fitbit = days.flatMap { it.workouts }.filter { w -> !(overrides.containsKey(w.start.toEpochMilli()) && overrides[w.start.toEpochMilli()] == null) }
        val used = HashSet<Workout>()
        val out = ArrayList<ActivityItem>()
        for (a in manual) {
            val s = Instant.ofEpochMilli(a.startMs); val e = Instant.ofEpochMilli(a.endMs)
            val fw = fitbit.firstOrNull { it.start < e && it.end > s }?.also { used += it }
            val hr = manualHr[a.id] ?: fw
            out += ActivityItem("m-${a.id}", a.sport, a.sport.label + (if (a.note.isNotBlank()) ": ${a.note}" else ""), s, e,
                a.durationS / 60, a.distanceKm ?: fw?.distanceKm, hr?.avgHr, hr?.maxHr, hr?.trimp?.let { Analysis.strainOf(it) },
                if (a.route.isNotEmpty()) "GPS run" else "Logged", manualId = a.id)
        }
        for (s in sessions.filter { it.sets.isNotEmpty() && it.endMs != null }) {
            val st = Instant.ofEpochMilli(s.startMs); val en = Instant.ofEpochMilli(s.endMs!!)
            val fw = fitbit.firstOrNull { it.start < en && it.end > st }?.also { used += it }
            out += ActivityItem("g-${s.id}", Sport.GYM, "${s.day.title} day", st, en, Duration.between(st, en).toMinutes().toInt(), null,
                fw?.avgHr, fw?.maxHr, fw?.trimp?.let { Analysis.strainOf(it) }, "Pod", sessionId = s.id)
        }
        for (w in fitbit.filter { it !in used }) {
            val fixed = overrides[w.start.toEpochMilli()]
            val sport = fixed ?: Sport.ofFitbit(w.type)
            out += ActivityItem("f-${w.start.toEpochMilli()}", sport, if (fixed != null) sport.label else w.title ?: w.type, w.start, w.end,
                Duration.between(w.start, w.end).toMinutes().toInt(), w.distanceKm, w.avgHr, w.maxHr, w.trimp?.let { Analysis.strainOf(it) }, "Fitbit")
        }
        return out.sortedByDescending { it.start }
    }

    fun pace(sPerKm: Int) = "%d:%02d /km".format(sPerKm / 60, sPerKm % 60)

    fun clock(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    /** Fastest time over [km] inside any run with GPS splits (whole-km windows), or the run itself if it's that distance. */
    fun best(runs: List<Activity>, km: Int): Pair<Activity, Int>? = runs.mapNotNull { r ->
        val t = if (r.splitsS.size >= km) r.splitsS.windowed(km).minOf { it.sum() }
            else if ((r.distanceKm ?: 0.0) >= km - 0.05 && (r.distanceKm ?: 0.0) <= km + 0.3) (r.durationS * km / r.distanceKm!!).roundToInt()
            else null
        t?.let { r to it }
    }.minByOrNull { it.second }

    @Suppress("unused") private fun keep() = Routines.upper.size
}
