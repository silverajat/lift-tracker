package com.silverajat.lifttracker.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ChatMessage(val role: String, val text: String, val timeMs: Long)

/**
 * GPT via OpenAI's Responses API, called straight from the phone with your
 * own API key (platform.openai.com → API keys). The key stays in the app's
 * private settings on this phone; you pay per use on your OpenAI account.
 *
 * The app's data goes in as the instructions (see ChatContext). OpenAI
 * caches long repeated prompts automatically, so follow-ups are cheaper.
 */
object Gpt {
    /** Used until you pick one from your key's model list. */
    const val DEFAULT_MODEL = "gpt-5.4-mini"

    class ApiError(msg: String) : Exception(msg)

    private fun open(path: String, key: String): HttpURLConnection =
        (URL("https://api.openai.com/v1/$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrDefault(text.take(300))
            throw ApiError(when (code) {
                401 -> "The API key was rejected (401). Check it in brainstorm settings."
                404 -> "Model not available to this key (404): $msg. Pick another in settings."
                429 -> "Rate limited or out of credit (429): $msg"
                else -> "OpenAI error $code: $msg"
            })
        }
        return text
    }

    /** Chat models this key can use (newest first), for the settings list. */
    fun models(key: String): List<String> {
        val c = open("models", key)
        try {
            val data = JSONObject(read(c)).getJSONArray("data")
            return (0 until data.length()).map { data.getJSONObject(it) }
                .filter { o ->
                    val id = o.getString("id")
                    (id.startsWith("gpt-") || id.matches(Regex("^o\\d.*"))) &&
                        listOf("audio", "realtime", "tts", "transcribe", "image", "search", "embedding", "instruct").none { it in id }
                }
                .sortedByDescending { it.optLong("created") }
                .map { it.getString("id") }
        } finally { c.disconnect() }
    }

    /** Blocking call: run it off the main thread. */
    fun send(key: String, model: String, instructions: String, history: List<ChatMessage>, maxTokens: Int = 6000): String {
        val body = JSONObject().apply {
            put("model", model)
            put("instructions", instructions)
            put("max_output_tokens", maxTokens)
            put("store", false)
            put("input", JSONArray().apply {
                history.forEach { m -> put(JSONObject().put("role", if (m.role == "user") "user" else "assistant").put("content", m.text)) }
            })
        }
        val c = open("responses", key)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            return text(JSONObject(read(c)))
        } finally { c.disconnect() }
    }

    /** The reply's text from a Responses API result. */
    fun text(o: JSONObject): String {
        val out = o.optJSONArray("output") ?: return ""
        val parts = ArrayList<String>()
        for (i in 0 until out.length()) {
            val item = out.getJSONObject(i)
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val p = content.getJSONObject(j)
                if (p.optString("type") == "output_text") parts += p.getString("text")
            }
        }
        if (parts.isEmpty() && o.optString("status") == "incomplete")
            throw ApiError("The reply was cut off (${o.optJSONObject("incomplete_details")?.optString("reason") ?: "limit"}). Try a shorter question.")
        return parts.joinToString("\n")
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
