package com.silverajat.lifttracker.data

import org.json.JSONObject
import java.io.File

/** Your own note on an exercise (seat setting, cue, "go heavier next time"). Shows every session until you change it. */
data class ExerciseNote(val text: String, val updatedMs: Long)

class ExerciseNotes(root: File) {
    private val f = File(root, "exercise_notes.json")

    fun all(): Map<Int, ExerciseNote> = runCatching {
        val o = JSONObject(f.readText())
        o.keys().asSequence().associate { k ->
            val n = o.getJSONObject(k)
            k.toInt() to ExerciseNote(n.getString("text"), n.getLong("t"))
        }
    }.getOrDefault(emptyMap())

    fun set(exerciseId: Int, text: String) {
        val m = all().toMutableMap()
        if (text.isBlank()) m.remove(exerciseId) else m[exerciseId] = ExerciseNote(text.trim(), System.currentTimeMillis())
        val o = JSONObject()
        m.forEach { (id, n) -> o.put(id.toString(), JSONObject().put("text", n.text).put("t", n.updatedMs)) }
        f.writeText(o.toString())
    }
}
