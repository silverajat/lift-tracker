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
}
