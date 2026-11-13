package com.silverajat.lifttracker.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The workout as plain text, written the way I log it by hand, for pasting
 * into ChatGPT or a notes app. Example:
 *
 *   UPPER DAY · Fri 13 Nov 2026 · 7:15–8:40 (85 min)
 *
 *   Incline bench (3D Smith): 20kg × 12 (warm-up), 55kg × 10, 55kg × 8
 *     pod: ROM 97% avg, 1 partial rep · speed drop 18%
 */
object ShareText {
    private val date = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault())
    private val time = SimpleDateFormat("H:mm", Locale.getDefault())

    private fun kg(w: Double): String {
        val r = Math.round(w * 100)
        return when {
            r % 100 == 0L -> (r / 100).toString()
            r % 10 == 0L -> "%.1f".format(w)
            else -> "%.2f".format(w)
        }
    }

    private fun set(l: SetLog, ex: Exercise): String {
        val w = when {
            ex.bodyweight && l.weight == 0.0 -> "BW"
            else -> kg(l.weight) + "kg" + when (l.addons) {
                0 -> ""
                1 -> " + add-on"
                else -> " + ${l.addons} add-ons"
            }
        }
        return "$w × ${l.reps}" + (if (Routines.isOneArm(ex)) " each arm" else "") + when {
            l.calibration -> " (ROM calibration)"
            l.warmup -> " (warm-up)"
            else -> ""
        }
    }

    fun build(s: Session): String = buildString {
        val start = Date(s.startMs)
        val end = s.endMs?.let { Date(it) }
        val mins = s.endMs?.let { ((it - s.startMs) / 60_000).toInt() }
        append("${s.day.title.uppercase()} DAY · ${date.format(start)} · ${time.format(start)}")
        if (end != null) append("–${time.format(end)} ($mins min)")
        append("\n")

        val byEx = s.sets.groupBy { it.exerciseId }
        Routines.forDay(s.day).filter { it.id in byEx }.forEach { ex ->
            val sets = byEx.getValue(ex.id).sortedBy { it.setNo }
            append("\n")
            append(ex.name)
            if (ex.detail.isNotEmpty()) append(" (${ex.detail})")
            append(": ")
            append(sets.joinToString(", ") { set(it, ex) })
            append("\n")
            val pod = sets.filter { it.romPct.isNotEmpty() || it.quality != null }
            if (pod.isNotEmpty()) {
                val work = pod.filter { !it.warmup }.ifEmpty { pod }
                val rom = work.flatMap { it.romPct }
                val partials = rom.count { it < 90 }
                val drop = work.mapNotNull { it.speedDrop }.maxOrNull()
                val parts = mutableListOf<String>()
                if (rom.isNotEmpty()) parts += "ROM ${rom.average().toInt()}% of full" +
                    if (partials > 0) ", $partials partial rep${if (partials > 1) "s" else ""}" else ", no partials"
                if (drop != null && drop > 0) parts += "speed drop up to $drop%"
                work.mapNotNull { it.quality }.takeIf { it.isNotEmpty() }?.let {
                    parts += "rep quality ${it.average().toInt()}/100"
                }
                if (parts.isNotEmpty()) append("  pod: ${parts.joinToString(" · ")}\n")
            }
        }

        val volume = s.sets.sumOf { it.weight * it.reps }
        append("\n${s.sets.size} sets · volume ${"%,.0f".format(volume)} kg")
        val q = s.sets.mapNotNull { it.quality }
        if (q.isNotEmpty()) append(" · avg rep quality ${q.average().toInt()}/100")
        append("\n(logged with Spotter and my wrist pod)")
    }
}
