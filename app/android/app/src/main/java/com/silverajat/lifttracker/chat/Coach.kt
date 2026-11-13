package com.silverajat.lifttracker.chat

import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * GPT's written analysis: a short daily take (Today tab) and the Monday
 * review. One request a day at most (cached), so it costs next to nothing.
 */
object Coach {
    data class Take(val date: LocalDate, val text: String, val atMs: Long)

    val DAILY = """
        Write today's analysis for Rajat from his data above. Format, exactly:
        3 short bullet points ("- ") on what stands out right now (recovery, sleep, training, net calories, weight trend),
        then a line "Today:" followed by 2-3 bullet points of specific things to do today or this week.
        Use his real numbers. Under 120 words total. Friendly and a little fun, like a good coach texting, no hype.
        Don't mention protein, carbs or fat: he tracks net calories only. Don't repeat the raw data back; interpret it.
        If data is thin (new user), say what to keep logging and why, briefly.
    """.trimIndent()

    val WEEKLY = """
        Write his Monday review of last week (Monday to Sunday) from the data above.
        Format: one short headline sentence, then 3-4 bullet points ("- ") covering training (volume, PRs, stalls),
        recovery and sleep, net calories vs his goal and what the scale says, then "This week:" with 2-3 specific actions.
        Use real numbers. Under 140 words. Encouraging but honest. No protein, carbs or fat.
    """.trimIndent()

    private fun file(root: File) = File(root, "coach_take.json")

    fun load(root: File): Take? = runCatching {
        val o = JSONObject(file(root).readText())
        Take(LocalDate.parse(o.getString("date")), o.getString("text"), o.getLong("at"))
    }.getOrNull()

    fun save(root: File, t: Take) {
        runCatching { file(root).writeText(JSONObject().put("date", t.date.toString()).put("text", t.text).put("at", t.atMs).toString()) }
    }

    /** Blocking. */
    fun ask(key: String, model: String, context: String, prompt: String): String =
        Gpt.send(key, model, context, listOf(ChatMessage("user", prompt, System.currentTimeMillis())), maxTokens = 3000).trim()
}
