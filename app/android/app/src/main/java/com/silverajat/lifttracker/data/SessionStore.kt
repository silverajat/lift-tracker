package com.silverajat.lifttracker.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A set you did. Times are epoch milliseconds. */
data class SetLog(
    val exerciseId: Int,
    val setNo: Int,
    val weight: Double,
    val addons: Int,
    val reps: Int,
    val warmup: Boolean,
    val startMs: Long,
    val endMs: Long,
    val liveReps: Int?,        // the pod's live count when you hit Stop
    val podRec: Int?,          // recording id on the pod, if it was connected
)

data class Session(
    val id: Long,              // start time, epoch seconds (also sent to the pod)
    val day: Day,
    val startMs: Long,
    val endMs: Long?,
    val sets: List<SetLog>,
)

/**
 * Every session is one JSON file in the app's private storage
 * (files/sessions/<id>.json), rewritten after each set so nothing is lost
 * if the phone dies mid-workout.
 */
class SessionStore(context: Context) {
    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }

    fun save(s: Session) {
        val tmp = File(dir, "${s.id}.json.tmp")
        tmp.writeText(toJson(s).toString(2))
        tmp.renameTo(File(dir, "${s.id}.json"))
    }

    fun all(): List<Session> =
        dir.listFiles { f -> f.name.endsWith(".json") }
            ?.mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            ?.sortedByDescending { it.startMs }
            ?: emptyList()

    fun delete(id: Long) {
        File(dir, "$id.json").delete()
    }

    private fun toJson(s: Session) = JSONObject().apply {
        put("id", s.id)
        put("day", s.day.name)
        put("startMs", s.startMs)
        put("endMs", s.endMs ?: JSONObject.NULL)
        put("sets", JSONArray().apply {
            s.sets.forEach { l ->
                put(JSONObject().apply {
                    put("exerciseId", l.exerciseId)
                    put("setNo", l.setNo)
                    put("weight", l.weight)
                    put("addons", l.addons)
                    put("reps", l.reps)
                    put("warmup", l.warmup)
                    put("startMs", l.startMs)
                    put("endMs", l.endMs)
                    put("liveReps", l.liveReps ?: JSONObject.NULL)
                    put("podRec", l.podRec ?: JSONObject.NULL)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Session {
        val arr = o.getJSONArray("sets")
        val sets = (0 until arr.length()).map { i ->
            val l = arr.getJSONObject(i)
            SetLog(
                exerciseId = l.getInt("exerciseId"),
                setNo = l.getInt("setNo"),
                weight = l.getDouble("weight"),
                addons = l.optInt("addons", 0),
                reps = l.getInt("reps"),
                warmup = l.getBoolean("warmup"),
                startMs = l.getLong("startMs"),
                endMs = l.getLong("endMs"),
                liveReps = if (l.isNull("liveReps")) null else l.getInt("liveReps"),
                podRec = if (l.isNull("podRec")) null else l.getInt("podRec"),
            )
        }
        return Session(
            id = o.getLong("id"),
            day = Day.valueOf(o.getString("day")),
            startMs = o.getLong("startMs"),
            endMs = if (o.isNull("endMs")) null else o.getLong("endMs"),
            sets = sets,
        )
    }
}
