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

    /** The workout name the rest of the app uses for this sport. */
    val fitbitType: String get() = when (this) {
        RUN -> "Run"; WALK -> "Walk"; SWIM -> "Swim"; CYCLE -> "Bike"; GYM -> "Strength training"
        FOOTBALL -> "Football"; BADMINTON -> "Badminton"; OTHER -> "Workout"
    }

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
/** One live heart-rate reading. */
data class HrPoint(val t: Long, val bpm: Int)

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
    val hr: List<HrPoint> = emptyList(),    // live heart rate, if a monitor was shared during it
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
            if (x.hr.isNotEmpty()) put("hr", JSONArray().apply { x.hr.forEach { h -> put((h.t - x.startMs) / 1000); put(h.bpm) } })
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
            val h = o.optJSONArray("hr") ?: JSONArray()
            return Activity(
                o.getString("id"), runCatching { Sport.valueOf(o.getString("sport")) }.getOrDefault(Sport.OTHER), start,
                o.getInt("dur"), if (o.has("km")) o.getDouble("km") else null, o.optString("note"), pts,
                (0 until sp.length()).map { sp.getInt(it) }, o.optInt("elapsed", o.getInt("dur")),
                (0 until h.length() / 2).map { i -> HrPoint(start + h.getLong(i * 2) * 1000, h.getInt(i * 2 + 1)) },
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

    /** The Fitbit's workouts with your corrections applied (renamed, or hidden ones removed). */
    fun apply(days: List<HealthDay>): List<HealthDay> {
        val o = all()
        if (o.isEmpty()) return days
        return days.map { d -> d.copy(workouts = d.workouts.mapNotNull { w -> fix(w, o) }) }
    }

    fun fix(w: Workout, o: Map<Long, Sport?> = all()): Workout? {
        val k = w.start.toEpochMilli()
        if (!o.containsKey(k)) return w
        val s = o[k] ?: return null
        return w.copy(type = s.fitbitType, title = null)
    }

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
    /** No heart rate for most of it (yet): strain estimated from pace (RunEffort). */
    val strainEstimated: Boolean = false,
)

/**
 * Strain for a run before (or without) its heart rate: from pace. Running costs VO2 = 0.2 x speed + 3.5 ml/kg/min
 * (ACSM, flat; walking 0.1 x speed + 3.5), and the share of your heart-rate reserve tracks the share of your VO2
 * reserve (Swain & Leutholtz 1997). VO2 max from the Fitbit, else 15.3 x max HR / resting HR (Uth et al. 2004).
 * Each km split counts on its own (Banister TRIMP is steeper at higher effort).
 */
object RunEffort {
    fun hrr(sport: Sport, metersPerMin: Double, vo2max: Double): Double {
        val vo2 = (if (sport == Sport.WALK) 0.1 else 0.2) * metersPerMin + 3.5
        return ((vo2 - 3.5) / (vo2max - 3.5)).coerceIn(0.0, 0.95)
    }

    private fun trimp(min: Double, f: Double) = if (f < 0.3) 0.0 else min * f * 0.64 * kotlin.math.exp(1.92 * f)

    fun trimp(a: Activity, vo2max: Double?, restingHr: Int?, hrMax: Int): Int? {
        val km = a.distanceKm ?: return null
        if (a.durationS < 120 || km < 0.2) return null
        val max = vo2max ?: (15.3 * hrMax / (restingHr ?: 60))
        var t = 0.0
        var doneS = 0; var doneKm = 0.0
        for (s in a.splitsS) if (s > 0) { t += trimp(s / 60.0, hrr(a.sport, 1000.0 / (s / 60.0), max)); doneS += s; doneKm += 1.0 }
        val restS = a.durationS - doneS; val restKm = km - doneKm
        if (restS > 30 && restKm > 0.05) t += trimp(restS / 60.0, hrr(a.sport, restKm * 1000 / (restS / 60.0), max))
        return t.roundToInt()
    }

    /** Share of [from]..[to] that heart-rate readings cover (gaps over 5 min don't count). */
    fun coverage(times: List<Long>, from: Long, to: Long): Double {
        if (to <= from || times.size < 2) return 0.0
        val t = times.sorted()
        var covered = 0L
        for (i in 0 until t.size - 1) { val g = t[i + 1] - t[i]; if (g in 1..300_000) covered += g }
        return covered.toDouble() / (to - from)
    }
}

object ActivityLog {
    /**
     * Fitbit workouts, things you logged, GPS runs and gym sessions, newest
     * first. Where a logged activity overlaps a Fitbit workout, they're one
     * item: your name and distance, the Fitbit's heart rate.
     */
    fun merge(days: List<HealthDay>, manual: List<Activity>, manualHr: Map<String, Workout>, sessions: List<Session>,
              overrides: Map<Long, Sport?> = emptyMap(), hrMax: Int = com.silverajat.lifttracker.health.Scores.DEFAULT_HR_MAX): List<ActivityItem> {
        val rest = days.lastOrNull { it.restingHr != null }?.restingHr
        val vo2max = days.lastOrNull { it.vo2max != null }?.vo2max
        // hidden ones are dropped; the rest keep your corrected type
        val fitbit = days.flatMap { it.workouts }.filter { w -> !(overrides.containsKey(w.start.toEpochMilli()) && overrides[w.start.toEpochMilli()] == null) }
        val used = HashSet<Workout>()
        val out = ArrayList<ActivityItem>()
        for (a in manual) {
            val s = Instant.ofEpochMilli(a.startMs); val e = Instant.ofEpochMilli(a.endMs)
            val fw = fitbit.firstOrNull { it.start < e && it.end > s }?.also { used += it }
            // heart rate: the live Bluetooth readings if they cover the run, else the Fitbit's (once synced and
            // covering it), else, for a GPS run, strain estimated from pace until the heart rate arrives
            val live = a.hr.takeIf { RunEffort.coverage(it.map { p -> p.t }, a.startMs, a.endMs) >= 0.8 }
            val liveLoad = live?.let { pts ->
                com.silverajat.lifttracker.health.Scores.load(pts.map { com.silverajat.lifttracker.health.HrSample(Instant.ofEpochMilli(it.t), it.bpm) }, rest, hrMax)
            }
            val hr = manualHr[a.id] ?: fw
            val est = if (liveLoad == null && hr?.trimp == null && a.route.isNotEmpty()) RunEffort.trimp(a, vo2max, rest, hrMax) else null
            out += ActivityItem("m-${a.id}", a.sport, a.sport.label + (if (a.note.isNotBlank()) ": ${a.note}" else ""), s, e,
                a.durationS / 60, a.distanceKm ?: fw?.distanceKm,
                live?.map { it.bpm }?.average()?.roundToInt() ?: hr?.avgHr, live?.maxOf { it.bpm } ?: hr?.maxHr,
                (liveLoad?.trimp ?: hr?.trimp ?: est)?.let { Analysis.strainOf(it) },
                if (a.route.isNotEmpty()) "GPS run" else "Logged", manualId = a.id, strainEstimated = est != null)
        }
        for (s in sessions.filter { it.sets.isNotEmpty() && it.endMs != null }) {
            val st = Instant.ofEpochMilli(s.startMs); val en = Instant.ofEpochMilli(s.endMs!!)
            val fw = fitbit.firstOrNull { it.start < en && it.end > st }?.also { used += it }
            // the session's strain: its heart rate plus the muscular load from the pod
            val ml = com.silverajat.lifttracker.health.MuscleLoad.score(s, sessions)
            out += ActivityItem("g-${s.id}", Sport.GYM, "${s.day.title} day", st, en, Duration.between(st, en).toMinutes().toInt(), null,
                fw?.avgHr, fw?.maxHr, Analysis.strainOf((fw?.trimp ?: 0) + com.silverajat.lifttracker.health.MuscleLoad.trimpOf(ml.load)), "Pod", sessionId = s.id)
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
