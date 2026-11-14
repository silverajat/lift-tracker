package com.silverajat.lifttracker.insights

import com.silverajat.lifttracker.chat.ChatMessage
import com.silverajat.lifttracker.chat.Gpt
import org.json.JSONObject

/**
 * With an OpenAI key, GPT rewrites each notification so it doesn't read the
 * same every day: same facts and numbers, fresh wording. It sees the recent
 * notifications so it can avoid repeating them. Anything off (a number that
 * wasn't in the draft, a bad reply, no network) falls back to the draft.
 */
object Voice {
    val KINDS = setOf(Kind.MORNING, Kind.NIGHT, Kind.ACTIVITY, Kind.FOOD, Kind.WORKOUT, Kind.PLAN)

    val INSTRUCTIONS = """
        You write the push notifications for Rajat's personal health, gym and day-planning app. He's an NUS
        electrical engineering student in Singapore who lifts, plays badminton and tracks net calories for a recomp.
        Rewrite the DRAFT notification as a fresh one:
        - Keep the facts and numbers exactly as given. Never add a number, time or fact that isn't in the draft.
          You may drop minor details to keep it short. Keep any times (e.g. lights-out time) and targets.
        - Title: at most 45 characters, starting with one fitting emoji.
        - Body: 1 to 3 short sentences, at most 230 characters.
        - Make it clearly different from the RECENT notifications: different opening, structure, phrasing,
          jokes and comparisons. Don't reuse a comparison that appears in RECENT. If the draft's fun comparison
          (e.g. "laps of a running track") appears in RECENT, drop it instead of reusing it.
        - Tone: a friend who happens to be a good coach. Casual, warm, a little witty, never cringe or hype.
          Light Singapore flavour is fine occasionally (not every time). No hashtags. No protein/carb/fat talk.
        Reply with JSON only: {"title": "...", "body": "..."}
    """.trimIndent()

    /** Numbers in a text (commas and signs stripped), for checking GPT didn't invent any. */
    fun numbers(s: String): Set<String> = Regex("""\d[\d,]*(\.\d+)?""").findAll(s).map { it.value.replace(",", "").trimEnd('0').trimEnd('.') }.toSet()

    /** The rewrite, or null to keep the draft. */
    fun check(draft: Insight, reply: String): Insight? {
        val json = reply.substringAfter("{", "").substringBeforeLast("}", "")
        if (json.isEmpty()) return null
        val o = runCatching { JSONObject("{$json}") }.getOrNull() ?: return null
        val title = o.optString("title").trim().replace("**", "")
        val body = o.optString("body").trim().replace("**", "")
        if (title.isEmpty() || body.isEmpty() || title.length > 70 || body.length > 400) return null
        val allowed = numbers(draft.title + " " + draft.body)
        if (!allowed.containsAll(numbers("$title $body"))) return null
        return draft.copy(title = title, body = body)
    }

    /** Blocking (network). */
    fun rewrite(key: String, model: String, draft: Insight, recent: List<Insight>): Insight {
        if (draft.kind !in KINDS || key.isEmpty()) return draft
        val prompt = buildString {
            appendLine("KIND: ${draft.kind.title}")
            appendLine("DRAFT title: ${draft.title}")
            appendLine("DRAFT body: ${draft.body}")
            appendLine()
            appendLine("RECENT (newest first):")
            recent.take(10).forEach { appendLine("- [${it.kind.title}] ${it.title} | ${it.body}") }
        }
        return runCatching {
            check(draft, Gpt.send(key, model, INSTRUCTIONS, listOf(ChatMessage("user", prompt, System.currentTimeMillis())), maxTokens = 1500))
        }.getOrNull() ?: draft
    }
}
