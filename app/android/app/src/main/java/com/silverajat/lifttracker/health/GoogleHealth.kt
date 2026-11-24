package com.silverajat.lifttracker.health

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ln

/**
 * Extra Fitbit data from the Google Health API (the old Fitbit Web API), on top of Health Connect:
 *  - HRV through the night (one reading per few minutes, not one number), so the app can make its own nightly HRV:
 *    the median of the night's readings after throwing out wild ones, which one noisy stretch can't drag around
 *  - blood oxygen, minute by minute (Fitbit doesn't share it with Health Connect at all)
 *  - VO2 max
 *  - second-by-second heart rate, for activities whose heart rate Health Connect doesn't cover
 *
 * Read-only, your own account (project "Life", testing mode). Signing in is Google's own Android flow (AuthorizationClient),
 * matched to this app by its package name and signing certificate.
 */
object GoogleHealth {
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
        "https://www.googleapis.com/auth/googlehealth.sleep.readonly",
        "https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
        "https://www.googleapis.com/auth/googlehealth.settings.readonly",   // paired devices: the watch's battery
    )
    private const val BASE = "https://health.googleapis.com/v4/users/me/dataTypes/"

    class ApiError(val code: Int, msg: String) : Exception(msg)

    /** One night, built from the API's readings. */
    data class Night(
        val date: LocalDate,              // the morning the sleep ended
        val hrvOurs: Double? = null,      // median of the night's HRV readings (outliers out), ms
        val hrvReadings: Int = 0,
        val hrvFitbit: Double? = null,    // Fitbit's own nightly average, for comparison
        val hrvDeep: Double? = null,      // Fitbit's deep-sleep RMSSD
        val spo2: Double? = null,         // average %
        val spo2Low: Double? = null,      // lower bound %
        val vo2max: Double? = null,
    )

    private fun get(token: String, url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw ApiError(code, "$code " +
                (runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull() ?: body).take(300))
            return JSONObject(body)
        } finally { c.disconnect() }
    }

    /**
     * Every data point of [type] (path id, e.g. "heart-rate-variability"), newest first, matching [filter] if given.
     * Without a filter it pages back until [stop] says a point is older than wanted.
     */
    fun list(token: String, type: String, filter: String?, pageSize: Int = 10_000, maxPages: Int = 8,
             stop: (JSONObject) -> Boolean = { false }): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        var page: String? = null
        repeat(maxPages) {
            val url = BASE + type + "/dataPoints?pageSize=$pageSize" + (filter?.let { "&filter=" + URLEncoder.encode(it, "UTF-8") } ?: "") +
                (page?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val o = get(token, url)
            var past = false
            o.optJSONArray("dataPoints")?.let { a -> for (i in 0 until a.length()) { val p = a.getJSONObject(i); if (stop(p)) past = true else out += p } }
            if (past) return out
            page = o.optString("nextPageToken").takeIf { it.isNotBlank() } ?: return out
        }
        return out
    }

    /**
     * With the documented filter (prefix = the type's JSON name, e.g. "dailyHeartRateVariability.date"); if the API
     * turns that down (400), no filter: page back from the newest until older than wanted (it lists newest first).
     */
    private fun listEither(token: String, path: String, json: String, filter: (String) -> String, pageSize: Int = 10_000,
                           older: (JSONObject) -> Boolean): List<JSONObject> =
        try { list(token, path, filter(json), pageSize) } catch (e: ApiError) {
            if (e.code == 400) list(token, path, null, pageSize, maxPages = 20, stop = older) else throw e
        }

    private fun rfc(t: Instant) = t.toString()
    private fun day(d: LocalDate) = d.toString()

    private fun samples(token: String, path: String, json: String, from: Instant, to: Instant) =
        listEither(token, path, json, { p -> "$p.sample_time.physical_time >= \"${rfc(from)}\" AND $p.sample_time.physical_time < \"${rfc(to)}\"" },
            older = { p -> time(p.optJSONObject(json))?.let { it < from } == true })
            .filter { p -> time(p.optJSONObject(json))?.let { it >= from && it < to } == true }

    private fun daily(token: String, path: String, json: String, from: LocalDate, to: LocalDate) =
        listEither(token, path, json, { p -> "$p.date >= \"${day(from)}\" AND $p.date < \"${day(to.plusDays(1))}\"" }, 1000,
            older = { p -> date(p.optJSONObject(json))?.let { it < from } == true })
            .filter { p -> date(p.optJSONObject(json))?.let { !it.isBefore(from) && !it.isAfter(to) } == true }

    private fun time(o: JSONObject?) = o?.optJSONObject("sampleTime")?.optString("physicalTime")?.let { runCatching { Instant.parse(it) }.getOrNull() }
    private fun date(o: JSONObject?) = o?.optJSONObject("date")?.let { d -> runCatching { LocalDate.of(d.getInt("year"), d.getInt("month"), d.getInt("day")) }.getOrNull() }

    /** The median of [x] after dropping readings more than 3 robust spreads from it (a few noisy windows can't move it). */
    fun robustMedian(x: List<Double>): Double? {
        if (x.size < 6) return null
        val m = Analysis.median(x)
        val mad = Analysis.median(x.map { abs(it - m) }) * 1.4826
        val kept = if (mad <= 0) x else x.filter { abs(it - m) <= 3 * mad }
        return Analysis.median(kept)
    }

    /**
     * Nights from [from] to [to], each from its sleep ([sleeps]: the morning it ended -> asleep from/to).
     * Each kind of data is fetched on its own: one failing (not on this watch, not allowed) doesn't stop the others.
     */
    fun nights(token: String, from: LocalDate, to: LocalDate, sleeps: Map<LocalDate, Pair<Instant, Instant>>, zone: ZoneId,
               errors: MutableList<String>): Map<LocalDate, Night> {
        val start = from.minusDays(1).atStartOfDay(zone).toInstant()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant()
        fun <T> safe(what: String, f: () -> T): T? = runCatching(f).onFailure { errors += "$what: ${it.message?.take(160)}" }.getOrNull()

        val hrv = safe("HRV") { samples(token, "heart-rate-variability", "heartRateVariability", start, end) }.orEmpty().mapNotNull { p ->
            val h = p.optJSONObject("heartRateVariability") ?: return@mapNotNull null
            val v = h.optDouble("rootMeanSquareOfSuccessiveDifferencesMilliseconds", Double.NaN)
            time(h)?.takeIf { !v.isNaN() && v > 0 }?.let { it to v }
        }
        val o2 = safe("Blood oxygen") { daily(token, "daily-oxygen-saturation", "dailyOxygenSaturation", from, to) }.orEmpty().mapNotNull { p ->
            val s = p.optJSONObject("dailyOxygenSaturation") ?: return@mapNotNull null
            date(s)?.let { it to (s.optDouble("averagePercentage", Double.NaN) to s.optDouble("lowerBoundPercentage", Double.NaN)) }
        }.toMap()
        val dHrv = safe("Nightly HRV") { daily(token, "daily-heart-rate-variability", "dailyHeartRateVariability", from, to) }.orEmpty().mapNotNull { p ->
            val s = p.optJSONObject("dailyHeartRateVariability") ?: return@mapNotNull null
            date(s)?.let { it to (s.optDouble("averageHeartRateVariabilityMilliseconds", Double.NaN) to
                s.optDouble("deepSleepRootMeanSquareOfSuccessiveDifferencesMilliseconds", Double.NaN)) }
        }.toMap()
        val vo2 = safe("VO2 max") { daily(token, "daily-vo2-max", "dailyVo2Max", from, to) }.orEmpty().mapNotNull { p ->
            val s = p.optJSONObject("dailyVo2Max") ?: return@mapNotNull null
            date(s)?.let { it to s.optDouble("vo2Max", Double.NaN) }
        }.toMap()

        fun ok(v: Double?) = v?.takeIf { !it.isNaN() && it > 0 }
        val out = HashMap<LocalDate, Night>()
        var d = from
        while (!d.isAfter(to)) {
            val window = sleeps[d]
            val inNight = if (window == null) emptyList() else hrv.filter { (t, _) -> t >= window.first && t <= window.second }.map { it.second }
            val n = Night(d, robustMedian(inNight), inNight.size, ok(dHrv[d]?.first), ok(dHrv[d]?.second),
                ok(o2[d]?.first), ok(o2[d]?.second), ok(vo2[d]))
            if (n != Night(d)) out[d] = n
            d = d.plusDays(1)
        }
        return out
    }

    /** A paired Fitbit and its battery, as the Fitbit app last saw it. */
    data class Device(val name: String, val battery: Int?, val status: String?, val lastSync: Instant?)

    /** Your paired trackers (not scales), each with its battery. */
    fun devices(token: String): List<Device> {
        val o = get(token, BASE.substringBefore("dataTypes/") + "pairedDevices")
        val a = o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull() ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("deviceType", "TRACKER") != "SCALE" }.map { d ->
            Device(d.optString("deviceVersion", "Fitbit").ifBlank { "Fitbit" },
                d.opt("batteryLevel")?.toString()?.toIntOrNull(), d.optString("batteryStatus").ifBlank { null },
                d.optString("lastSyncTime").takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() })
        }
    }

    /** Second-by-second heart rate between [from] and [to] (for an activity). */
    fun heartRate(token: String, from: Instant, to: Instant): List<HrSample> =
        samples(token, "heart-rate", "heartRate", from, to).mapNotNull { p ->
            val h = p.optJSONObject("heartRate") ?: return@mapNotNull null
            val bpm = h.optString("beatsPerMinute").toIntOrNull() ?: h.optInt("beatsPerMinute", 0)
            time(h)?.takeIf { bpm > 0 }?.let { HrSample(it, bpm) }
        }.sortedBy { it.time }

    /**
     * Is our nightly HRV steadier than Fitbit's? The typical night-to-night swing (median |change| on a log scale)
     * of each, over nights that have both. Ours is used once there are 7 such nights and it swings at least 10% less.
     */
    data class Steadiness(val nights: Int, val fitbitSwingPct: Double?, val oursSwingPct: Double?) {
        val useOurs get() = nights >= 7 && fitbitSwingPct != null && oursSwingPct != null && oursSwingPct <= fitbitSwingPct * 0.9
    }

    fun steadiness(nights: Map<LocalDate, Night>, fitbit: Map<LocalDate, Double>): Steadiness {
        val both = nights.values.filter { it.hrvOurs != null && fitbit[it.date] != null }.sortedBy { it.date }
        fun swing(v: List<Double>) = v.zipWithNext().map { (a, b) -> abs(ln(b / a)) }.takeIf { it.size >= 3 }
            ?.let { (kotlin.math.exp(Analysis.median(it)) - 1) * 100 }
        return Steadiness(both.size, swing(both.map { fitbit.getValue(it.date) }), swing(both.map { it.hrvOurs!! }))
    }

    /** Saved nights and connection state. */
    class Store(root: File) {
        private val f = File(root, "google_health.json")
        data class Saved(val connected: Boolean, val nights: Map<LocalDate, Night>, val syncedMs: Long, val errors: List<String>)

        fun load(): Saved = runCatching {
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("nights") ?: JSONArray()
            val n = (0 until a.length()).map { a.getJSONObject(it) }.associate { j ->
                fun d(k: String) = j.optDouble(k, Double.NaN).takeIf { !it.isNaN() }
                val date = LocalDate.parse(j.getString("date"))
                date to Night(date, d("ours"), j.optInt("n"), d("fitbit"), d("deep"), d("spo2"), d("spo2low"), d("vo2"))
            }
            val e = o.optJSONArray("errors")?.let { x -> (0 until x.length()).map { x.getString(it) } }.orEmpty()
            Saved(o.optBoolean("connected"), n, o.optLong("synced"), e)
        }.getOrDefault(Saved(false, emptyMap(), 0, emptyList()))

        fun save(s: Saved) {
            val a = JSONArray()
            s.nights.values.sortedBy { it.date }.takeLast(120).forEach { n ->
                val j = JSONObject().put("date", n.date.toString()).put("n", n.hrvReadings)
                fun p(k: String, v: Double?) { if (v != null) j.put(k, v) }
                p("ours", n.hrvOurs); p("fitbit", n.hrvFitbit); p("deep", n.hrvDeep); p("spo2", n.spo2); p("spo2low", n.spo2Low); p("vo2", n.vo2max)
                a.put(j)
            }
            f.writeText(JSONObject().put("connected", s.connected).put("synced", s.syncedMs).put("nights", a)
                .put("errors", JSONArray(s.errors.take(10))).toString())
        }
    }
}
