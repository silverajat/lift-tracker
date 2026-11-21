package com.silverajat.lifttracker.activity

import android.content.Context
import android.content.Intent
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/**
 * A live activity: a GPS run, or a timed football/badminton session.
 * Lives in the process (held alive by RunService while it's going) so the
 * screen can be off or the app in the background.
 */
object RunTracker {
    data class State(
        val sport: Sport = Sport.RUN,
        val active: Boolean = false,
        val paused: Boolean = false,
        val startMs: Long = 0,
        val pausedTotalMs: Long = 0,
        val pausedAt: Long? = null,
        val distanceM: Double = 0.0,
        val points: List<RoutePoint> = emptyList(),
        val splitsS: List<Int> = emptyList(),
        val paceSPerKm: Int? = null,      // last ~200 m
        val accuracyM: Float? = null,
        val gpsOk: Boolean = false,
        val hr: List<HrPoint> = emptyList(),   // live heart rate (every 2 s at most), while not paused
    ) {
        val avgHr get() = hr.takeIf { it.size >= 5 }?.map { it.bpm }?.average()?.roundToInt()
        val maxHr get() = hr.maxOfOrNull { it.bpm }
        fun movingMs(now: Long): Long = if (!active) 0 else now - startMs - pausedTotalMs - (pausedAt?.let { now - it } ?: 0L)
        val usesGps get() = sport == Sport.RUN || sport == Sport.WALK
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The last activity saved (the app reloads its lists when this changes). */
    private val _saved = MutableStateFlow<Activity?>(null)
    val saved: StateFlow<Activity?> = _saved

    /** Called at each new km (for the buzz and the spoken split). */
    var onSplit: ((km: Int, splitS: Int, totalS: Int) -> Unit)? = null

    private var last: Location? = null
    private var splitStartMoving = 0L

    fun start(ctx: Context, sport: Sport) {
        if (_state.value.active) return
        last = null
        splitStartMoving = 0
        _state.value = State(sport = sport, active = true, startMs = System.currentTimeMillis())
        listenHr(ctx)
        // a run needs GPS with the screen off (a foreground service); a timed game only needs its start time
        persist(ctx)
        if (sport == Sport.RUN || sport == Sport.WALK) ctx.startForegroundService(Intent(ctx, RunService::class.java))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("live", Context.MODE_PRIVATE)

    private fun liveFile(ctx: Context) = java.io.File(ctx.filesDir, "live_run.json")

    /**
     * A GPS run in progress, saved to a file (RunService does it every 15 s, and on pause/resume), so if the phone
     * kills the app mid-run, the run carries on from where it was when the app or the service comes back.
     */
    fun snapshot(ctx: Context) {
        val s = _state.value
        val f = liveFile(ctx)
        if (!s.active || !s.usesGps) { f.delete(); return }
        runCatching {
            val a = Activity(id = "live", sport = s.sport, startMs = s.startMs, durationS = 0, distanceKm = null, note = "",
                route = s.points, splitsS = s.splitsS, elapsedS = 0, hr = s.hr)
            val o = ActivityStore.toJson(a).put("distanceM", s.distanceM).put("pausedTotal", s.pausedTotalMs)
                .put("pausedAt", s.pausedAt ?: 0L).put("splitStart", splitStartMoving)
            val tmp = java.io.File(ctx.filesDir, "live_run.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        }
    }

    /** Timed sessions survive the app being closed: their state is saved. */
    private fun persist(ctx: Context) {
        snapshot(ctx)
        val s = _state.value
        prefs(ctx).edit().apply {
            if (!s.active || s.usesGps) clear()
            else { putString("sport", s.sport.name); putLong("start", s.startMs); putLong("pausedTotal", s.pausedTotalMs); putLong("pausedAt", s.pausedAt ?: 0) }
        }.apply()
    }

    /** Call when the app opens (or RunService restarts): brings back a run or a football/badminton session that was going. */
    fun restore(ctx: Context, fromService: Boolean = false) {
        if (_state.value.active) return
        val f = liveFile(ctx)
        if (f.exists()) {
            runCatching {
                val o = org.json.JSONObject(f.readText())
                val a = ActivityStore.fromJson(o)
                val pausedAt = o.optLong("pausedAt", 0).takeIf { it > 0 }
                // carry on from the last saved point: a gap while the app was dead counts as a straight line
                last = a.route.lastOrNull()?.let { r -> Location("saved").apply { latitude = r.lat; longitude = r.lng; time = r.t } }
                splitStartMoving = o.optLong("splitStart", 0)
                _state.value = State(sport = a.sport, active = true, startMs = a.startMs, pausedTotalMs = o.optLong("pausedTotal", 0),
                    paused = pausedAt != null, pausedAt = pausedAt, distanceM = o.optDouble("distanceM", 0.0),
                    points = a.route, splitsS = a.splitsS, hr = a.hr)
                listenHr(ctx)
                if (!fromService) ctx.startForegroundService(Intent(ctx, RunService::class.java))
            }
            return
        }
        val p = prefs(ctx)
        val sport = p.getString("sport", null)?.let { runCatching { Sport.valueOf(it) }.getOrNull() } ?: return
        val pausedAt = p.getLong("pausedAt", 0).takeIf { it > 0 }
        _state.value = State(sport = sport, active = true, startMs = p.getLong("start", System.currentTimeMillis()),
            pausedTotalMs = p.getLong("pausedTotal", 0), paused = pausedAt != null, pausedAt = pausedAt)
        listenHr(ctx)
    }

    /** Live heart rate from a shared monitor (the Fitbit Air), kept with the session. */
    private fun listenHr(ctx: Context) {
        HrMonitor.onReading = { t, bpm ->
            val s = _state.value
            if (s.active && !s.paused && (s.hr.lastOrNull()?.let { t - it.t >= 2000 } != false)) _state.value = s.copy(hr = s.hr + HrPoint(t, bpm))
        }
        HrMonitor.start(ctx)
    }

    private fun stopHr() { HrMonitor.onReading = null; HrMonitor.stop() }

    fun pause(ctx: Context) {
        val s = _state.value
        if (!s.active || s.paused) return
        _state.value = s.copy(paused = true, pausedAt = System.currentTimeMillis())
        last = null
        persist(ctx)
    }

    fun resume(ctx: Context) {
        val s = _state.value
        if (!s.active || !s.paused) return
        val now = System.currentTimeMillis()
        _state.value = s.copy(paused = false, pausedAt = null, pausedTotalMs = s.pausedTotalMs + (now - (s.pausedAt ?: now)))
        persist(ctx)
    }

    /** Finish and save. Returns what was saved (null if it was too short to keep). */
    fun stop(ctx: Context, note: String = ""): Activity? {
        val s = _state.value
        if (!s.active) return null
        val now = System.currentTimeMillis()
        val moving = (s.movingMs(now) / 1000).toInt()
        val a = Activity(
            id = "a${s.startMs}", sport = s.sport, startMs = s.startMs, durationS = moving,
            distanceKm = if (s.usesGps && s.distanceM > 50) s.distanceM / 1000 else null,
            note = note, route = s.points, splitsS = s.splitsS, elapsedS = ((now - s.startMs) / 1000).toInt(), hr = s.hr,
        )
        _state.value = State()
        stopHr()
        persist(ctx)
        ctx.stopService(Intent(ctx, RunService::class.java))
        if (moving < 60) return null
        ActivityStore(ctx.filesDir).add(a)
        _saved.value = a
        return a
    }

    fun discard(ctx: Context) {
        _state.value = State()
        stopHr()
        persist(ctx)
        ctx.stopService(Intent(ctx, RunService::class.java))
    }

    /** A GPS fix from RunService. Noisy fixes and jumps are dropped. */
    fun onLocation(loc: Location) {
        val s = _state.value
        if (!s.active || !s.usesGps) return
        val acc = if (loc.hasAccuracy()) loc.accuracy else 99f
        if (s.paused || acc > 25f) { _state.value = s.copy(accuracyM = acc, gpsOk = acc <= 25f); return }
        val p = RoutePoint(loc.latitude, loc.longitude, loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(), if (loc.hasAltitude()) loc.altitude else null)
        val prev = last
        if (prev == null) {
            last = loc
            _state.value = s.copy(points = s.points + p, accuracyM = acc, gpsOk = true)
            return
        }
        val d = prev.distanceTo(loc).toDouble()
        val dt = (loc.time - prev.time) / 1000.0
        if (dt > 0 && d / dt > 9.0) return              // faster than a sprinter: a GPS jump
        if (d < maxOf(3.0, acc / 4.0)) { _state.value = s.copy(accuracyM = acc, gpsOk = true); return }   // standing still / jitter
        last = loc
        val dist = s.distanceM + d
        val now = System.currentTimeMillis()
        var splits = s.splitsS
        val km = (dist / 1000).toInt()
        if (km > splits.size) {
            val moving = s.copy(distanceM = dist).movingMs(now)
            val split = ((moving - splitStartMoving) / 1000).toInt()
            splitStartMoving = moving
            splits = splits + split
            onSplit?.invoke(km, split, (moving / 1000).toInt())
        }
        val pts = s.points + p
        _state.value = s.copy(distanceM = dist, points = pts, splitsS = splits, accuracyM = acc, gpsOk = true, paceSPerKm = recentPace(pts))
    }

    /** Pace over roughly the last 200 m. */
    private fun recentPace(pts: List<RoutePoint>): Int? {
        if (pts.size < 3) return null
        var d = 0.0
        var i = pts.size - 1
        val out = FloatArray(1)
        while (i > 0 && d < 200) {
            Location.distanceBetween(pts[i - 1].lat, pts[i - 1].lng, pts[i].lat, pts[i].lng, out)
            d += out[0]; i--
        }
        val dt = (pts.last().t - pts[i].t) / 1000.0
        if (d < 50 || dt <= 0) return null
        return (dt / d * 1000).roundToInt().takeIf { it in 120..1500 }
    }
}
