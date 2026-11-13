package com.silverajat.lifttracker.insights

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silverajat.lifttracker.MainActivity
import com.silverajat.lifttracker.R
import com.silverajat.lifttracker.data.SessionStore
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.HealthRepo
import com.silverajat.lifttracker.health.Scores
import com.silverajat.lifttracker.meals.MealStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Past insights (the Insights tab), newest first, and which ones were already sent. */
class InsightStore(root: File) {
    private val f = File(root, "insights.json")
    private val sentF = File(root, "insights_sent.json")

    fun all(): List<Insight> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Insight(o.getString("key"), Kind.valueOf(o.getString("kind")), o.getString("title"), o.getString("body"), o.getLong("t"))
        }
    }.getOrDefault(emptyList())

    /** Replace an insight in place (same position in the feed), or remove it with null. */
    fun replace(key: String, i: Insight?) {
        val list = all().mapNotNull { if (it.key == key) i else it }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("key", it.key).put("kind", it.kind.name).put("title", it.title).put("body", it.body).put("t", it.timeMs)) }
        f.writeText(a.toString())
    }

    fun add(i: Insight) {
        val list = (listOf(i) + all().filter { it.key != i.key }).take(150)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("key", it.key).put("kind", it.kind.name).put("title", it.title).put("body", it.body).put("t", it.timeMs)) }
        f.writeText(a.toString())
    }

    fun sent(): Set<String> = runCatching {
        val a = JSONArray(sentF.readText()); (0 until a.length()).map { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun markSent(key: String) {
        val s = (sent() + key).toList().takeLast(400)
        sentF.writeText(JSONArray(s).toString())
    }

    fun clear() { f.delete() }
}

/** Open-Meteo: free, no key. Defaults to Singapore. */
object WeatherApi {
    const val LAT = 1.2966   // NUS
    const val LON = 103.7764

    fun today(zone: ZoneId, lat: Double = LAT, lon: Double = LON): Weather? = runCatching {
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&hourly=precipitation_probability,temperature_2m" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,uv_index_max" +
            "&forecast_days=1&timezone=${zone.id}")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        val text = c.inputStream.bufferedReader().use { it.readText() }
        parse(JSONObject(text))
    }.getOrNull()

    fun parse(o: JSONObject): Weather {
        val d = o.getJSONObject("daily")
        val h = o.getJSONObject("hourly")
        fun dbl(a: JSONArray, i: Int) = if (a.isNull(i)) null else a.getDouble(i)
        val rain = h.getJSONArray("precipitation_probability")
        val temp = h.getJSONArray("temperature_2m")
        return Weather(
            maxC = dbl(d.getJSONArray("temperature_2m_max"), 0),
            minC = dbl(d.getJSONArray("temperature_2m_min"), 0),
            rainMaxPct = dbl(d.getJSONArray("precipitation_probability_max"), 0)?.toInt(),
            uvMax = dbl(d.getJSONArray("uv_index_max"), 0),
            hourlyRainPct = (0 until rain.length()).map { if (rain.isNull(it)) 0 else rain.getInt(it) },
            hourlyTempC = (0 until temp.length()).map { if (temp.isNull(it)) 0.0 else temp.getDouble(it) },
            date = LocalDate.parse(d.getJSONArray("time").getString(0)),
        )
    }
}

/**
 * Decides which notifications are due and sends them. Runs every hour in the
 * background (WorkManager) and whenever the app opens.
 */
object InsightEngine {
    const val NOTIFY_ON = "notify_on"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Kind.entries.forEach { k ->
            nm.createNotificationChannel(NotificationChannel(k.name, k.title,
                if (k == Kind.WEATHER) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_DEFAULT).apply { description = k.about })
        }
    }

    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "insights", ExistingPeriodicWorkPolicy.UPDATE,
            // every 30 min: tops up the saved Fitbit data (last 3 days), the widget and any notifications due
            PeriodicWorkRequestBuilder<InsightWorker>(30, TimeUnit.MINUTES, 10, TimeUnit.MINUTES).build(),
        )
    }

    /** Save to the feed and show it (once per key). */
    fun post(ctx: Context, i: Insight, notify: Boolean = true) {
        val store = InsightStore(ctx.filesDir)
        if (i.key in store.sent()) return
        store.add(i)
        store.markSent(i.key)
        val on = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE).getBoolean(NOTIFY_ON, true)
        if (!notify || !on) return
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(ctx, 0,
            Intent(ctx, MainActivity::class.java).putExtra("tab", "INSIGHTS").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, i.kind.name)
            .setSmallIcon(R.drawable.ic_stat_lift)
            .setContentTitle(i.title)
            .setContentText(i.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(i.body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(i.key.hashCode(), n) }
    }

    /** Check everything that might be due now. [force] sends the morning summary even if it went out already. */
    suspend fun run(ctx: Context, force: Boolean = false) {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val nowMs = System.currentTimeMillis()
        val today = now.toLocalDate()
        val store = InsightStore(ctx.filesDir)
        val sent = store.sent()
        val prefs = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
        val hrMax = prefs.getInt("hr_max", Scores.DEFAULT_HR_MAX)
        val health = HealthRepo(ctx)
        val meals = MealStore(ctx.filesDir)
        val sessions = SessionStore(ctx).all()
        val canHealth = health.available() && health.granted().isNotEmpty()

        // weather once an hour at most (cached in memory by the caller's lifetime is fine)
        val weatherDue = now.hour in 6..21
        val weather = if (weatherDue) WeatherApi.today(zone) else null

        // the full analysis (31 days of Fitbit data) only when something needs it
        var daysCache: List<com.silverajat.lifttracker.health.HealthDay>? = null
        // same saved copy as the app: quick 3-day read on top of it, full read at most once a day
        val hcache = com.silverajat.lifttracker.health.HealthCache(ctx.filesDir)
        suspend fun days(): List<com.silverajat.lifttracker.health.HealthDay> {
            daysCache?.let { return it }
            if (!canHealth) return emptyList()
            val saved = hcache.load()
            // same rule as the app: saved copy under 30 minutes old is used as is
            if (saved != null && saved.days.size >= 20 && System.currentTimeMillis() - saved.savedMs < com.silverajat.lifttracker.SYNC_EVERY_MS) {
                daysCache = saved.days; return saved.days
            }
            val full = saved == null || saved.days.size < 20
            val fresh = runCatching { health.days(if (full) com.silverajat.lifttracker.FIRST_DAYS else 3, hrMax) }.getOrDefault(emptyList())
            val d = if (fresh.isEmpty()) saved?.days.orEmpty() else if (full || saved == null) fresh else hcache.merge(saved.days, fresh)
            if (fresh.isNotEmpty()) hcache.save(d, full)
            daysCache = d
            return d
        }
        val goalKg = 0.0 // maintenance (recomp)
        suspend fun weights(): List<com.silverajat.lifttracker.meals.WeightEntry> {
            val mine = meals.weights()
            val have = mine.map { it.date }.toSet()
            val scale = if (canHealth) health.weights(70).groupBy { it.first.atZone(zone).toLocalDate() }
                .map { (d, v) -> com.silverajat.lifttracker.meals.WeightEntry(d, v.minBy { it.first }.second, 0) } else emptyList()
            return (mine + scale.filter { it.date !in have }).sortedBy { it.date }
        }
        suspend fun analysis() = Analysis.run(days(), sessions, meals.days(today.minusDays(60), today), weights(), today, zone, goalKg)

        // morning summary: after you wake (sleep synced) or by 10 AM at the latest
        val mKey = "morning-$today"
        if (force || (mKey !in sent && now.hour >= 6)) {
            val woke = days().lastOrNull()?.sleep != null
            if (force || woke || now.hour >= 10) {
                val a = analysis()
                Insights.morning(a, weather, today, nowMs)?.let {
                    post(ctx, if (force) it.copy(key = "$mKey-$nowMs") else it)
                }
                // Monday: last week's review
                if (today.dayOfWeek == java.time.DayOfWeek.MONDAY && now.hour >= 8) {
                    Insights.weekly(a, sessions, today, zone, nowMs)?.let { rules ->
                        // with a GPT key, GPT writes the review from the same data (the numbers version is the fallback)
                        val key = prefs.getString("openai_key", "") ?: ""
                        val written = if (key.isEmpty() || rules.key in sent) null else runCatching {
                            val context = com.silverajat.lifttracker.chat.ChatContext.build(
                                now = Instant.now(), zone = zone, sessions = sessions,
                                model = com.silverajat.lifttracker.reps.RepModel.Model.fromJson(runCatching { java.io.File(ctx.filesDir, "rep_model.json").readText() }.getOrNull()),
                                health = com.silverajat.lifttracker.activity.ActivityOverrides(ctx.filesDir).apply(days().takeLast(14)), analysis = a, meals = meals.days(today.minusDays(13), today),
                                weights = meals.weights(), hrMax = hrMax, healthNote = "background check",
                            )
                            com.silverajat.lifttracker.chat.Coach.ask(key, prefs.getString("openai_model", com.silverajat.lifttracker.chat.Gpt.DEFAULT_MODEL)!!,
                                context, com.silverajat.lifttracker.chat.Coach.WEEKLY)
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        post(ctx, if (written != null) rules.copy(body = written.replace("**", "")) else rules)
                    }
                }
            }
        }

        // Fitbit workouts that ended in the last 6 hours
        if (canHealth) {
            val fixes = com.silverajat.lifttracker.activity.ActivityOverrides(ctx.filesDir)
            val recent = runCatching { health.workouts(Instant.now().minus(Duration.ofHours(6)), Instant.now()) }.getOrDefault(emptyList())
                .mapNotNull { fixes.fix(it) }          // your corrections (soccer -> walk), hidden ones dropped
            for (w in recent) {
                val key = "act-${w.start.toEpochMilli()}"
                if (key in sent || Insights.overlapsGym(w, sessions)) continue
                if (Duration.between(w.start, w.end).toMinutes() < 8) continue
                val st = health.activityStats(w.start, w.end)
                val d = w.start.atZone(zone).toLocalDate()
                post(ctx, Insights.activity(w, st, meals.day(d), nowMs))
            }
        }

        // rain in the next 2 hours (at most one every 4 hours)
        weather?.let { w ->
            Insights.rainSoon(w, now)?.let { i ->
                val lastRain = store.all().firstOrNull { it.kind == Kind.WEATHER }?.timeMs ?: 0
                if (nowMs - lastRain > 4 * 3600_000L) post(ctx, i)
            }
        }

        // evening net check at 8:30 PM, day wrap-up at 9:45 PM
        val t = now.toLocalTime()
        if (t >= LocalTime.of(20, 30) && t < LocalTime.of(21, 45) && "food-$today" !in sent) {
            post(ctx, Insights.evening(analysis().energy, meals.day(today), goalKg, today, t, nowMs))
        }
        if (t >= LocalTime.of(21, 45) && "night-$today" !in sent) {
            val a = analysis()
            post(ctx, Insights.night(a, days().lastOrNull { it.date == today }, sessions, meals.day(today), today, zone, t, nowMs))
        }

        // refresh the home-screen widget every run (hourly)
        if (canHealth) runCatching {
            com.silverajat.lifttracker.widget.WidgetData.publish(ctx,
                com.silverajat.lifttracker.widget.WidgetData.from(analysis(), meals.day(today), today, t, days()))
        }
    }
}

class InsightWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { InsightEngine.run(applicationContext) }
        return Result.success()
    }
}
