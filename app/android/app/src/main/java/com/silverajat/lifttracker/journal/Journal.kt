package com.silverajat.lifttracker.journal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** One thing to do. Main things are the day's top 3; the rest are side things. */
data class Task(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String,
    val main: Boolean,
    val done: Boolean = false,
    val carried: Boolean = false,      // brought over from an earlier day
)

/** A day: the plan you set in the morning, what got done, and the night's reflection. */
data class DayPage(
    val date: LocalDate,
    val tasks: List<Task> = emptyList(),
    val mood: Int? = null,             // 1..5
    val reflection: String = "",
    val carryDecided: Boolean = false, // yesterday's leftovers were brought over or dropped
) {
    val mains get() = tasks.filter { it.main }
    val sides get() = tasks.filter { !it.main }
    val planned get() = tasks.isNotEmpty()
    val reflected get() = reflection.isNotBlank() || mood != null
}

/** A brainstorm / note, kept separately from the days. */
data class Note(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String,
    val created: Long,
    val updated: Long = created,
    val pinned: Boolean = false,
)

/** Something to buy; ticked when bought, then cleared. */
data class ShopItem(val id: String = UUID.randomUUID().toString().take(8), val text: String, val bought: Boolean = false)

/** A one-off nudge at a set time: just a notification popup (no alarm, no vibration). */
data class Reminder(
    val id: String = UUID.randomUUID().toString().take(8),
    val text: String,
    val at: LocalDateTime,
    val fired: Boolean = false,
)

/** Reads a time out of what you typed: "umbrella at 3:30", "call mum at 9", "gym bag tmr 8am", "laundry in 20 min". */
object ReminderText {
    /** [day] is set when you named a day but no time (the app then asks for the time on that day). */
    data class Parsed(val text: String, val at: LocalDateTime?, val day: LocalDate? = null)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private val inRe = Regex("""\bin\s+(\d{1,3})\s*(m|mins?|minutes?|h|hrs?|hours?)\b""", ic)
    private val ampmRe = Regex("""\b(?:at\s+)?(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b""", ic)
    private val atRe = Regex("""\bat\s+(\d{1,2})(?:[:.](\d{2}))?\b""", ic)
    private val clockRe = Regex("""\b(\d{1,2})[:.](\d{2})\b""")
    private val tmrRe = Regex("""\b(tmr|tmrw|tomorrow)\b""", ic)
    private val todayRe = Regex("""\b(today|tonight)\b""", ic)
    private val inDaysRe = Regex("""\bin\s+(\d{1,2})\s*days?\b""", ic)
    private val monthRe = Regex("""\b(?:on\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\b""", ic)
    private val slashRe = Regex("""\b(?:on\s+)?(\d{1,2})/(\d{1,2})\b""")
    private val weekRe = Regex("""\b(on\s+|next\s+|this\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tues|tue|wed|thurs|thur|thu|fri|sat|sun)\b""", ic)
    private val leadRe = Regex("""^\s*(remind me( to)?|reminder:?|to)\s+""", ic)
    private val tailRe = Regex("""[\s,.:;-]*\b(at|by|to|on)?[\s,.:;-]*$""", ic)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

    /** The day you named, if any: tmr, today, in 3 days, 20 nov, 20/11, fri / on saturday / next sunday. */
    private fun dayIn(s: String, today: LocalDate): Pair<LocalDate, IntRange>? {
        tmrRe.find(s)?.let { return today.plusDays(1) to it.range }
        todayRe.find(s)?.let { return today to it.range }
        inDaysRe.find(s)?.let { return today.plusDays(it.groupValues[1].toLong()) to it.range }
        fun future(d: LocalDate) = if (d.isBefore(today)) d.plusYears(1) else d
        monthRe.find(s)?.let { m ->
            runCatching { LocalDate.of(today.year, months.indexOf(m.groupValues[2].lowercase().take(3)) + 1, m.groupValues[1].toInt()) }
                .getOrNull()?.let { return future(it) to m.range }
        }
        slashRe.find(s)?.let { m ->
            runCatching { LocalDate.of(today.year, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
                .getOrNull()?.let { return future(it) to m.range }
        }
        for (m in weekRe.findAll(s)) {
            val name = m.groupValues[2].lowercase()
            // "wed", "sat" and "sun" are words too ("sun cream"), so only with on/next/this in front
            if (name in setOf("wed", "sat", "sun") && m.groupValues[1].isEmpty()) continue
            val target = days.indexOf(name.take(3)) + 1
            var diff = (target - today.dayOfWeek.value + 7) % 7
            if (diff == 0) diff = 7       // naming today's weekday means next week's
            return today.plusDays(diff.toLong()) to m.range
        }
        return null
    }

    fun parse(s: String, now: LocalDateTime): Parsed {
        val today = now.toLocalDate()
        var rest = s
        val found = dayIn(rest, today)
        val day = found?.first
        if (found != null) rest = rest.removeRange(found.second)
        var at: LocalDateTime? = null
        val inM = if (day == null) inRe.find(rest) else null
        if (inM != null) {
            val n = inM.groupValues[1].toLong()
            val t = if (inM.groupValues[2].startsWith("h", ignoreCase = true)) now.plusHours(n) else now.plusMinutes(n)
            at = t.withSecond(0).withNano(0)
            rest = rest.removeRange(inM.range)
        } else {
            val m = ampmRe.find(rest) ?: atRe.find(rest) ?: clockRe.find(rest)
            if (m != null) {
                val ampm = if (m.groupValues.size > 3) m.groupValues[3].ifEmpty { null } else null
                at = resolve(m.groupValues[1].toInt(), m.groupValues[2].ifEmpty { "0" }.toInt(), ampm, day, now)
                if (at != null) rest = rest.removeRange(m.range)
            }
        }
        var text = leadRe.replace(rest.replace(Regex("\\s+"), " ").trim(), "")
        text = tailRe.replace(text, "").trim()
        text = text.replaceFirstChar { it.uppercase() }
        return Parsed(text, at, if (at == null) day else null)
    }

    /**
     * A clock time to a moment. With no day: the next sensible one ("3:30" at 2:48 PM is 3:30 PM, "9" in the evening is 9 PM).
     * On a named later day without AM/PM, 1 to 6 means the afternoon.
     */
    fun resolve(h: Int, m: Int, ampm: String?, day: LocalDate?, now: LocalDateTime): LocalDateTime? {
        if (h > 23 || m > 59) return null
        val today = now.toLocalDate()
        if (ampm != null && h !in 1..12) return null
        val h24 = ampm?.let { h % 12 + if (it.equals("pm", ignoreCase = true)) 12 else 0 }
        if (day != null && day.isAfter(today)) return day.atTime(h24 ?: if (h in 1..6) h + 12 else h, m)
        fun next(t: LocalDateTime) = if (!t.isAfter(now)) t.plusDays(1) else t
        if (h24 != null) return next(today.atTime(h24, m))
        if (h == 0 || h > 12) return next(today.atTime(h, m))
        // 1..12 with no AM/PM: the sooner of the two today, else tomorrow (1 to 6 meaning the afternoon)
        listOf(h % 12, h % 12 + 12).map { today.atTime(it, m) }.firstOrNull { it.isAfter(now) }?.let { return it }
        return today.plusDays(1).atTime(if (h <= 6) h + 12 else h, m)
    }
}

object Journal {
    const val MAINS = 3

    /** The journal day: before 4 AM still counts as the day before (late-night reflecting). */
    fun dayOf(t: LocalDateTime): LocalDate = if (t.hour < 4) t.toLocalDate().minusDays(1) else t.toLocalDate()

    /** Unfinished things from [prev] to bring over (main stays main while there is room). */
    fun carry(prev: DayPage?, into: DayPage): DayPage {
        if (prev == null) return into.copy(carryDecided = true)
        val have = into.tasks.map { it.text.lowercase() }.toSet()
        var mains = into.mains.size
        val moved = prev.tasks.filter { !it.done && it.text.lowercase() !in have }.map { t ->
            val main = t.main && mains < MAINS
            if (main) mains++
            Task(text = t.text, main = main, carried = true)
        }
        return into.copy(tasks = sortTasks(into.tasks + moved), carryDecided = true)
    }

    fun add(p: DayPage, text: String, main: Boolean): DayPage {
        val t = text.trim()
        if (t.isEmpty()) return p
        val asMain = main && p.mains.size < MAINS
        return p.copy(tasks = sortTasks(p.tasks + Task(text = t, main = asMain)))
    }

    fun toggle(p: DayPage, id: String) = p.copy(tasks = p.tasks.map { if (it.id == id) it.copy(done = !it.done) else it })
    fun edit(p: DayPage, id: String, text: String) = p.copy(tasks = p.tasks.map { if (it.id == id) it.copy(text = text.trim()) else it }.filter { it.text.isNotEmpty() })
    fun remove(p: DayPage, id: String) = p.copy(tasks = p.tasks.filter { it.id != id })

    /** Swap a task between main and side (main only if there's a free slot). */
    fun promote(p: DayPage, id: String): DayPage {
        val t = p.tasks.firstOrNull { it.id == id } ?: return p
        if (!t.main && p.mains.size >= MAINS) return p
        return p.copy(tasks = sortTasks(p.tasks.map { if (it.id == id) it.copy(main = !it.main) else it }))
    }

    private fun sortTasks(ts: List<Task>) = ts.filter { it.main } + ts.filter { !it.main }

    /** Last [n] days ending [today]: how many main things got done, and how many nights you reflected. */
    data class Week(val mainsDone: Int, val mainsSet: Int, val reflected: Int, val days: Int, val streak: Int)

    fun week(pages: Map<LocalDate, DayPage>, today: LocalDate, n: Int = 7): Week {
        val ds = (0 until n).map { today.minusDays(it.toLong()) }
        val ps = ds.mapNotNull { pages[it] }
        // streak: consecutive days (ending today or yesterday) where you planned and reflected
        var streak = 0
        var d = if (pages[today]?.let { it.planned && it.reflected } == true) today else today.minusDays(1)
        while (pages[d]?.let { it.planned && it.reflected } == true) { streak++; d = d.minusDays(1) }
        return Week(ps.sumOf { p -> p.mains.count { it.done } }, ps.sumOf { it.mains.size }, ps.count { it.reflected }, n, streak)
    }
}

/** Everything in one small JSON file in the app's private storage. */
class JournalStore(root: File) {
    private val f = File(root, "journal.json")

    private fun read(): JSONObject = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    private fun write(o: JSONObject) { runCatching { f.writeText(o.toString()) } }

    fun pages(): Map<LocalDate, DayPage> = runCatching {
        val d = read().optJSONObject("days") ?: return emptyMap()
        d.keys().asSequence().associate { k -> LocalDate.parse(k) to page(LocalDate.parse(k), d.getJSONObject(k)) }
    }.getOrDefault(emptyMap())

    fun page(date: LocalDate): DayPage = pages()[date] ?: DayPage(date)

    fun save(p: DayPage) {
        val o = read()
        val d = o.optJSONObject("days") ?: JSONObject()
        val tasks = JSONArray()
        p.tasks.forEach { t ->
            tasks.put(JSONObject().put("id", t.id).put("text", t.text).put("main", t.main).put("done", t.done).put("carried", t.carried))
        }
        val j = JSONObject().put("tasks", tasks).put("reflection", p.reflection).put("carry", p.carryDecided)
        p.mood?.let { j.put("mood", it) }
        d.put(p.date.toString(), j)
        o.put("days", d)
        write(o)
    }

    private fun page(date: LocalDate, o: JSONObject): DayPage {
        val a = o.optJSONArray("tasks") ?: JSONArray()
        val tasks = (0 until a.length()).map { i ->
            val t = a.getJSONObject(i)
            Task(t.getString("id"), t.getString("text"), t.optBoolean("main"), t.optBoolean("done"), t.optBoolean("carried"))
        }
        return DayPage(date, tasks, if (o.has("mood")) o.getInt("mood") else null, o.optString("reflection"), o.optBoolean("carry"))
    }

    fun shopping(): List<ShopItem> = runCatching {
        val a = read().optJSONArray("shopping") ?: return emptyList()
        (0 until a.length()).map { i -> a.getJSONObject(i).let { ShopItem(it.getString("id"), it.getString("text"), it.optBoolean("bought")) } }
    }.getOrDefault(emptyList())

    fun saveShopping(items: List<ShopItem>) {
        val o = read()
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("bought", it.bought)) }
        o.put("shopping", a)
        write(o)
    }

    fun notes(): List<Note> = runCatching {
        val a = read().optJSONArray("notes") ?: return emptyList()
        (0 until a.length()).map { i ->
            val n = a.getJSONObject(i)
            Note(n.getString("id"), n.getString("text"), n.getLong("created"), n.optLong("updated", n.getLong("created")), n.optBoolean("pinned"))
        }
    }.getOrDefault(emptyList())

    fun saveNotes(ns: List<Note>) {
        val o = read()
        val a = JSONArray()
        ns.forEach { n -> a.put(JSONObject().put("id", n.id).put("text", n.text).put("created", n.created).put("updated", n.updated).put("pinned", n.pinned)) }
        o.put("notes", a)
        write(o)
    }

    fun reminders(): List<Reminder> = runCatching {
        val a = read().optJSONArray("reminders") ?: return emptyList()
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Reminder(it.getString("id"), it.getString("text"), LocalDateTime.parse(it.getString("at")), it.optBoolean("fired")) }
        }.sortedBy { it.at }
    }.getOrDefault(emptyList())

    fun saveReminders(rs: List<Reminder>) {
        val o = read()
        val a = JSONArray()
        rs.sortedBy { it.at }.forEach { a.put(JSONObject().put("id", it.id).put("text", it.text).put("at", it.at.toString()).put("fired", it.fired)) }
        o.put("reminders", a)
        write(o)
    }
}
