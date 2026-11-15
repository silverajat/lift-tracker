package com.silverajat.lifttracker.garden

import com.silverajat.lifttracker.activity.ActivityItem
import com.silverajat.lifttracker.activity.Sport
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.journal.DayPage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The six parts of your life, one plant each. */
enum class Area(val label: String, val plant: String) {
    STUDY("Study", "Bamboo"),
    WORK("Work", "Pine"),
    GYM("Gym", "Maple"),
    SPORTS("Sports", "Palm"),
    SLEEP("Sleep", "Wisteria"),
    MIND("Mind", "Cherry"),
}

/** A study or work block from the focus timer. [awayS] is time spent in other apps (it doesn't count). */
data class FocusSession(
    val id: String = UUID.randomUUID().toString().take(8),
    val area: Area,
    val label: String,
    val startMs: Long,
    val endMs: Long,
    val awayS: Long = 0,
) {
    val minutes get() = (((endMs - startMs) / 1000 - awayS) / 60).toInt().coerceAtLeast(0)
}

/** The block running now. [awaySince] is set while you're in another app. */
data class ActiveFocus(val area: Area, val label: String, val startMs: Long, val awayS: Long = 0, val awaySince: Long? = null) {
    fun focusedS(now: Long): Long = ((now - startMs) / 1000 - awayS - (awaySince?.let { (now - it) / 1000 } ?: 0)).coerceAtLeast(0)
    val away get() = awaySince != null
}

object Garden {
    /** Level n needs 100 x n(n-1)/2 xp in total: 0, 100, 300, 600, 1000, ... */
    fun level(xp: Int): Int { var n = 1; while (100 * n * (n + 1) / 2 <= xp) n++; return n }
    fun levelStart(n: Int) = 100 * n * (n - 1) / 2
    fun progress(xp: Int): Float { val n = level(xp); return (xp - levelStart(n)).toFloat() / (levelStart(n + 1) - levelStart(n)) }

    /** One plant: total xp, this week's amount (in that area's own units), and when it last grew. */
    data class Plant(val area: Area, val xp: Int, val level: Int, val progress: Float, val week: String, val lastGrew: LocalDate?, val dryDays: Int)

    data class Inputs(
        val focus: List<FocusSession> = emptyList(),
        val gym: List<Session> = emptyList(),
        val sports: List<ActivityItem> = emptyList(),
        val days: List<Analysis.Day> = emptyList(),
        val pages: Map<LocalDate, DayPage> = emptyMap(),
        val active: ActiveFocus? = null,
        val reads: List<com.silverajat.lifttracker.journal.ReadSession> = emptyList(),
        val books: List<com.silverajat.lifttracker.journal.Book> = emptyList(),
    )

    /** XP earned per day for each area. */
    fun daily(i: Inputs, zone: ZoneId, now: Long = System.currentTimeMillis()): Map<Area, Map<LocalDate, Int>> {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val out = Area.entries.associateWith { HashMap<LocalDate, Int>() }
        fun add(a: Area, d: LocalDate, xp: Int) { if (xp > 0) out[a]!!.merge(d, xp, Int::plus) }

        // study / work: 1 xp per focused minute
        i.focus.forEach { add(it.area, day(it.startMs), it.minutes) }
        i.active?.let { add(it.area, day(it.startMs), (it.focusedS(now) / 60).toInt()) }
        // gym: 60 per finished session + 3 per working set
        i.gym.filter { it.endMs != null && it.sets.isNotEmpty() }.forEach { s -> add(Area.GYM, day(s.startMs), 60 + 3 * s.sets.count { !it.warmup }) }
        // sports: from strain, so a hard badminton game counts more than a stroll
        sportsXp(i.sports, zone).forEach { (a, xp) -> add(Area.SPORTS, a.start.atZone(zone).toLocalDate(), xp) }
        // sleep: by sleep performance, bonus for a green recovery
        i.days.forEach { d ->
            val perf = d.sleep?.performance ?: return@forEach
            add(Area.SLEEP, d.date, (if (perf >= 85) 60 else if (perf >= 70) 40 else 20) + (if ((d.recovery?.score ?: 0) >= 67) 20 else 0))
        }
        // mind: planning, the main 3, and reflecting
        i.pages.values.forEach { p ->
            add(Area.MIND, p.date, (if (p.planned) 10 else 0) + 15 * p.mains.count { it.done } + (if (p.reflected) 25 else 0))
        }
        // and reading: a point a minute (capped a day), a bonus per book finished
        com.silverajat.lifttracker.journal.Reading.xp(i.reads).forEach { (s, x) -> add(Area.MIND, s.date, x) }
        i.books.forEach { b -> b.finished?.let { add(Area.MIND, it, com.silverajat.lifttracker.journal.Reading.FINISH_BONUS) } }
        return out
    }

    fun plants(i: Inputs, today: LocalDate, zone: ZoneId, now: Long = System.currentTimeMillis()): List<Plant> {
        val d = daily(i, zone, now)
        val weekStart = today.minusDays(6)
        return Area.entries.map { a ->
            val xp = d[a]!!.values.sum()
            val last = d[a]!!.keys.filter { it <= today }.maxOrNull()
            Plant(a, xp, level(xp), progress(xp), weekText(a, i, d[a]!!, weekStart, today, zone, now), last,
                last?.let { (today.toEpochDay() - it.toEpochDay()).toInt() } ?: 99)
        }
    }

    private fun weekText(a: Area, i: Inputs, xp: Map<LocalDate, Int>, from: LocalDate, to: LocalDate, zone: ZoneId, now: Long): String {
        fun inWeek(d: LocalDate) = d >= from && d <= to
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return when (a) {
            Area.STUDY, Area.WORK -> {
                val min = i.focus.filter { it.area == a && inWeek(day(it.startMs)) }.sumOf { it.minutes } +
                    (i.active?.takeIf { it.area == a && inWeek(day(it.startMs)) }?.let { (it.focusedS(now) / 60).toInt() } ?: 0)
                hm(min)
            }
            Area.GYM -> "${i.gym.count { it.endMs != null && it.sets.isNotEmpty() && inWeek(day(it.startMs)) }} sessions"
            Area.SPORTS -> i.sports.filter { it.sport != Sport.GYM && inWeek(it.start.atZone(zone).toLocalDate()) }.let { "${it.size} · ${hm(it.sumOf { s -> s.minutes })}" }
            Area.SLEEP -> "${i.days.count { inWeek(it.date) && (it.sleep?.performance ?: 0) >= 80 }}/7 good nights"
            Area.MIND -> i.pages.values.filter { inWeek(it.date) }.let { ps -> "${ps.sumOf { p -> p.mains.count { it.done } }}/${ps.sumOf { it.mains.size }} main" } +
                (i.reads.filter { inWeek(it.date) }.sumOf { it.minutes }.takeIf { it > 0 }?.let { " · ${hm(it)} read" } ?: "")
        }
    }

    fun hm(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

    /** Where a day's time went (minutes) and what each plant earned that day. */
    data class DaySummary(val date: LocalDate, val minutes: Map<Area, Int>, val xp: Map<Area, Int>) {
        val totalMin get() = minutes.values.sum()
    }

    fun summary(date: LocalDate, i: Inputs, zone: ZoneId, now: Long = System.currentTimeMillis()): DaySummary {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val m = LinkedHashMap<Area, Int>()
        fun add(a: Area, v: Int) { if (v > 0) m[a] = (m[a] ?: 0) + v }
        i.focus.filter { day(it.startMs) == date }.forEach { add(it.area, it.minutes) }
        i.active?.takeIf { day(it.startMs) == date }?.let { add(it.area, (it.focusedS(now) / 60).toInt()) }
        i.gym.filter { it.endMs != null && it.sets.isNotEmpty() && day(it.startMs) == date }.forEach { add(Area.GYM, ((it.endMs!! - it.startMs) / 60_000).toInt()) }
        i.sports.filter { it.sport != Sport.GYM && it.sport != Sport.WALK && it.start.atZone(zone).toLocalDate() == date }.forEach { add(Area.SPORTS, it.minutes) }
        i.reads.filter { it.date == date }.forEach { add(Area.MIND, it.minutes) }
        val xp = daily(i, zone, now).mapValues { it.value[date] ?: 0 }.filterValues { it > 0 }
        return DaySummary(date, m, xp)
    }

    /** Most a day's walks can add to the Sports plant, all together: they help a little, games and runs do the growing. */
    const val WALK_CAP = 20

    /** Points per game, run or walk: strain x 6 (minutes / 2 without strain); walks a third of that, capped per day. */
    fun sportsXp(list: List<ActivityItem>, zone: ZoneId): List<Pair<ActivityItem, Int>> {
        val walked = HashMap<LocalDate, Int>()
        return list.filter { it.sport != Sport.GYM }.sortedBy { it.start }.map { a ->
            val full = a.strain?.let { (it * 6).toInt() } ?: (a.minutes / 2)
            if (a.sport != Sport.WALK) return@map a to full
            val d = a.start.atZone(zone).toLocalDate()
            val so = walked[d] ?: 0
            val xp = (full / 3).coerceAtMost(WALK_CAP - so).coerceAtLeast(0)
            walked[d] = so + xp
            a to xp
        }
    }

    /** What grew a plant, newest first: one line per session, game, night or day. [focusId] lets a study/work line be edited. */
    data class Entry(val date: LocalDate, val text: String, val sub: String, val xp: Int, val focusId: String? = null)

    /** How each plant grows, in a line. */
    fun howItGrows(a: Area) = when (a) {
        Area.STUDY -> "1 point per focused minute of study (time in other apps doesn't count)"
        Area.WORK -> "1 point per focused minute of work or projects"
        Area.GYM -> "60 points per session, plus 3 per working set"
        Area.SPORTS -> "Points from each game's or run's strain (strain x 6), so hard games count more. Walks add a little, at most $WALK_CAP a day"
        Area.SLEEP -> "60 for an 85%+ night, 40 for 70%+, 20 otherwise; +20 for a green recovery"
        Area.MIND -> "10 for planning the day, 15 per main thing done, 25 for reflecting; reading gives 1 per minute " +
            "(up to ${com.silverajat.lifttracker.journal.Reading.DAILY_CAP} a day) and ${com.silverajat.lifttracker.journal.Reading.FINISH_BONUS} for finishing a book"
    }

    fun log(a: Area, i: Inputs, zone: ZoneId): List<Entry> {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
        val tf = java.time.format.DateTimeFormatter.ofPattern("H:mm")
        val out = when (a) {
            Area.STUDY, Area.WORK -> i.focus.filter { it.area == a }.map { f ->
                Entry(day(f.startMs).toLocalDate(), f.label.ifBlank { a.label }, "${day(f.startMs).format(tf)} · ${hm(f.minutes)}", f.minutes, f.id)
            }
            Area.GYM -> i.gym.filter { it.endMs != null && it.sets.isNotEmpty() }.map { s ->
                val sets = s.sets.count { !it.warmup }
                Entry(day(s.startMs).toLocalDate(), "${s.day.title} day", "$sets working sets", 60 + 3 * sets)
            }
            Area.SPORTS -> sportsXp(i.sports, zone).map { (x, xp) ->
                Entry(x.start.atZone(zone).toLocalDate(), "${x.sport.emoji} ${x.title}",
                    "${x.minutes} min" + (x.strain?.let { " · strain %.1f".format(it) } ?: ""), xp)
            }
            Area.SLEEP -> i.days.mapNotNull { d ->
                val s = d.sleep ?: return@mapNotNull null
                val rec = d.recovery?.score
                Entry(d.date, "Slept ${hm(s.asleepMin)}", "${s.performance}% of need" + (rec?.let { " · recovery $it%" } ?: ""),
                    (if (s.performance >= 85) 60 else if (s.performance >= 70) 40 else 20) + (if ((rec ?: 0) >= 67) 20 else 0))
            }
            Area.MIND -> i.pages.values.filter { it.planned || it.reflected }.map { p ->
                Entry(p.date, "${p.mains.count { it.done }}/${p.mains.size} main things",
                    listOfNotNull("planned".takeIf { p.planned }, "reflected".takeIf { p.reflected }).joinToString(" · "),
                    (if (p.planned) 10 else 0) + 15 * p.mains.count { it.done } + (if (p.reflected) 25 else 0))
            } + com.silverajat.lifttracker.journal.Reading.xp(i.reads).map { (s, x) ->
                val b = i.books.firstOrNull { it.id == s.bookId }
                Entry(s.date, "📖 ${b?.title ?: "Reading"}", "${hm(s.minutes)}" + (s.toPage?.let { " · to p. $it" } ?: ""), x)
            } + i.books.mapNotNull { b -> b.finished?.let { Entry(it, "Finished ${b.title}", "book done", com.silverajat.lifttracker.journal.Reading.FINISH_BONUS) } }
        }
        return out.filter { it.xp > 0 }.sortedByDescending { it.date }
    }
}

/** Focus sessions and the running one, in the app's private storage. */
class FocusStore(root: File) {
    private val f = File(root, "focus.json")
    private fun read() = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    private fun write(o: JSONObject) { runCatching { f.writeText(o.toString()) } }

    fun sessions(): List<FocusSession> = runCatching {
        val a = read().optJSONArray("done") ?: return emptyList()
        (0 until a.length()).map { k ->
            val o = a.getJSONObject(k)
            FocusSession(o.getString("id"), Area.valueOf(o.getString("area")), o.optString("label"), o.getLong("start"), o.getLong("end"), o.optLong("away"))
        }
    }.getOrDefault(emptyList())

    fun saveSessions(ss: List<FocusSession>) {
        val o = read()
        val a = JSONArray()
        ss.forEach { s -> a.put(JSONObject().put("id", s.id).put("area", s.area.name).put("label", s.label).put("start", s.startMs).put("end", s.endMs).put("away", s.awayS)) }
        o.put("done", a); write(o)
    }

    fun active(): ActiveFocus? = runCatching {
        val o = read().optJSONObject("active") ?: return null
        ActiveFocus(Area.valueOf(o.getString("area")), o.optString("label"), o.getLong("start"), o.optLong("away"),
            if (o.has("awaySince")) o.getLong("awaySince") else null)
    }.getOrNull()

    fun setActive(a: ActiveFocus?) {
        val o = read()
        if (a == null) o.remove("active")
        else o.put("active", JSONObject().put("area", a.area.name).put("label", a.label).put("start", a.startMs).put("away", a.awayS)
            .apply { a.awaySince?.let { put("awaySince", it) } })
        write(o)
    }
}
