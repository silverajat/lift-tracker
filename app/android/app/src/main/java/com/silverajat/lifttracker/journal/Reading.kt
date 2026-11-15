package com.silverajat.lifttracker.journal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** A book on the shelf: [page] is where you're up to, [pages] its length if you gave it. */
data class Book(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val author: String = "",
    val pages: Int? = null,
    val page: Int = 0,
    val started: LocalDate,
    val finished: LocalDate? = null,
) {
    val progress: Float? get() = pages?.takeIf { it > 0 }?.let { (page.toFloat() / it).coerceIn(0f, 1f) }
}

/** One sitting: how long, and the page you got to (if you said). */
data class ReadSession(
    val id: String = UUID.randomUUID().toString().take(8),
    val bookId: String,
    val date: LocalDate,
    val minutes: Int,
    val toPage: Int? = null,
    val pagesRead: Int = 0,
)

object Reading {
    /** The Mind plant: a point per minute read, up to this many a day, and a bonus for each book finished. */
    const val DAILY_CAP = 90
    const val FINISH_BONUS = 50

    /** Reading points per session, oldest first, with the daily cap shared across a day's sittings. */
    fun xp(sessions: List<ReadSession>): List<Pair<ReadSession, Int>> {
        val used = HashMap<LocalDate, Int>()
        return sessions.sortedBy { it.date }.map { s ->
            val so = used[s.date] ?: 0
            val x = s.minutes.coerceAtMost(DAILY_CAP - so).coerceAtLeast(0)
            used[s.date] = so + x
            s to x
        }
    }

    /** Days in a row with some reading, up to today (or yesterday, if today has none yet). */
    fun streak(sessions: List<ReadSession>, today: LocalDate): Int {
        val days = sessions.filter { it.minutes > 0 }.map { it.date }.toSet()
        var d = if (today in days) today else today.minusDays(1)
        var n = 0
        while (d in days) { n++; d = d.minusDays(1) }
        return n
    }

    /** "Atomic Habits by James Clear, 320" or "Dune 600 pages" into a title, author and page count. */
    fun parseBook(s: String): Triple<String, String, Int?> {
        var rest = s.trim()
        var pages: Int? = null
        // a number at the end after a comma ("…, 320"), or with "pages" ("… 320 pages"); so "Catch 22" stays a title
        Regex("""(?:,\s*(\d{2,4})\s*(?:pages|pgs|pg|p)?|\s+(\d{2,4})\s*(?:pages|pgs|pg|p))\.?$""", RegexOption.IGNORE_CASE).find(rest)?.let {
            pages = (it.groupValues[1].ifEmpty { it.groupValues[2] }).toInt(); rest = rest.removeRange(it.range).trim()
        }
        val by = Regex("""\s+by\s+""", RegexOption.IGNORE_CASE).find(rest)
        val title = (if (by != null) rest.substring(0, by.range.first) else rest).trim().trim(',')
        val author = (if (by != null) rest.substring(by.range.last + 1) else "").trim().trim(',')
        return Triple(title.replaceFirstChar { it.uppercase() }, author, pages)
    }
}

/** Books and reading sessions, in reading.json. */
class ReadingStore(root: File) {
    private val f = File(root, "reading.json")
    private fun read(): JSONObject = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
    private fun write(o: JSONObject) { runCatching { f.writeText(o.toString()) } }

    fun books(): List<Book> = runCatching {
        val a = read().optJSONArray("books") ?: return emptyList()
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Book(it.getString("id"), it.getString("title"), it.optString("author"),
                    if (it.has("pages")) it.getInt("pages") else null, it.optInt("page"),
                    LocalDate.parse(it.getString("started")), it.optString("finished").takeIf { s -> s.isNotEmpty() }?.let(LocalDate::parse))
            }
        }
    }.getOrDefault(emptyList())

    fun saveBooks(bs: List<Book>) {
        val o = read()
        val a = JSONArray()
        bs.forEach { b ->
            val j = JSONObject().put("id", b.id).put("title", b.title).put("author", b.author).put("page", b.page).put("started", b.started.toString())
            b.pages?.let { j.put("pages", it) }
            b.finished?.let { j.put("finished", it.toString()) }
            a.put(j)
        }
        o.put("books", a)
        write(o)
    }

    fun sessions(): List<ReadSession> = runCatching {
        val a = read().optJSONArray("sessions") ?: return emptyList()
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                ReadSession(it.getString("id"), it.getString("book"), LocalDate.parse(it.getString("date")), it.getInt("min"),
                    if (it.has("to")) it.getInt("to") else null, it.optInt("read"))
            }
        }.sortedByDescending { it.date }
    }.getOrDefault(emptyList())

    fun saveSessions(ss: List<ReadSession>) {
        val o = read()
        val a = JSONArray()
        ss.forEach { s ->
            val j = JSONObject().put("id", s.id).put("book", s.bookId).put("date", s.date.toString()).put("min", s.minutes).put("read", s.pagesRead)
            s.toPage?.let { j.put("to", it) }
            a.put(j)
        }
        o.put("sessions", a)
        write(o)
    }
}
