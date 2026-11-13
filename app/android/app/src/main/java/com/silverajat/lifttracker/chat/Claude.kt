package com.silverajat.lifttracker.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val text: String, val timeMs: Long)

/**
 * Claude via the Anthropic Messages API, called straight from the phone
 * with your own API key (console.anthropic.com). The key stays in the
 * app's private settings on this phone. Billed per use, separately from a
 * Claude subscription.
 *
 * The app's data goes in the system prompt (see ChatContext); it's marked
 * for prompt caching, so follow-up questions within ~5 minutes reuse it
 * at a fraction of the cost.
 */
object Claude {
    val MODELS = listOf(
        "claude-sonnet-5-5" to "Sonnet (balanced)",
        "claude-opus-5-5" to "Opus (smartest)",
        "claude-haiku-5-5" to "Haiku (fastest, cheapest)",
    )

    class ApiError(msg: String) : Exception(msg)

    /** Blocking call: run it off the main thread. */
    fun send(apiKey: String, model: String, system: String, history: List<ChatMessage>, maxTokens: Int = 1500): String {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", JSONArray().put(JSONObject().apply {
                put("type", "text")
                put("text", system)
                put("cache_control", JSONObject().put("type", "ephemeral"))
            }))
            put("messages", JSONArray().apply {
                history.forEach { m -> put(JSONObject().put("role", m.role).put("content", m.text)) }
            })
        }
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("x-api-key", apiKey)
            c.setRequestProperty("anthropic-version", "2023-06-01")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(300))
                throw ApiError(when (code) {
                    401 -> "The API key was rejected (401). Check it in Chat settings."
                    429 -> "Rate limited or out of credit (429): $msg"
                    else -> "Claude API error $code: $msg"
                })
            }
            val content = JSONObject(text).getJSONArray("content")
            return (0 until content.length()).map { content.getJSONObject(it) }
                .filter { it.optString("type") == "text" }.joinToString("\n") { it.getString("text") }
        } finally {
            c.disconnect()
        }
    }
}

/** The conversation, kept on the phone (files/chat.json). */
class ChatStore(root: File) {
    private val f = File(root, "chat.json")

    fun load(): List<ChatMessage> = runCatching {
        val a = JSONArray(f.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map { ChatMessage(it.getString("role"), it.getString("text"), it.getLong("t")) }
    }.getOrDefault(emptyList())

    fun save(list: List<ChatMessage>) {
        f.writeText(JSONArray(list.map { JSONObject().put("role", it.role).put("text", it.text).put("t", it.timeMs) }).toString())
    }

    fun clear() { f.delete() }
}
