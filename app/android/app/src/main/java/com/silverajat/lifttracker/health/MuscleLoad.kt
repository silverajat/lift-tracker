package com.silverajat.lifttracker.health

import com.silverajat.lifttracker.data.Group
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.data.Stats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Muscular load: how hard a gym session was on your muscles, which heart
 * rate badly undercounts (a heavy set barely moves it).
 *
 * Each set scores  reps × intensity² × effort × muscle size
 *   intensity  = weight ÷ your estimated 1RM on that lift (from earlier sessions),
 *                squared so heavy sets count much more than light ones
 *   effort     = how close to failure. From the pod: the more your reps slowed
 *                (speed drop), the closer you were. Without it: reps left in the
 *                tank from your 1RM (Epley).  1 + 1.5·e^(−RIR/2): ~2.5 at failure,
 *                ~1.55 with 2 left, ~1.1 when easy
 *   muscle     = legs 1.3, back/chest 1.0, shoulders 0.75, arms 0.6, core 0.5
 *   warm-ups   × 0.3
 *
 * Session score: muscular strain 0-21 = 21(1 − e^(−load/140)); a normal hard
 * session lands around 11-13. For the day's Strain, a quarter of the load is
 * added to the heart-rate load (TRIMP), so a hard session adds ~3-5 points.
 */
object MuscleLoad {
    const val TO_TRIMP = 0.25
    private const val SCALE = 140.0

    data class SetScore(val log: SetLog, val load: Double, val rir: Double?, val intensity: Double)
    data class SessionLoad(val load: Double, val strain: Double, val sets: List<SetScore>, val byGroup: Map<Group, Double>)

    fun strainOf(load: Double) = 21.0 * (1 - exp(-load / SCALE))

    private fun muscle(g: Group) = when (g) {
        Group.LEGS -> 1.3; Group.BACK, Group.CHEST -> 1.0; Group.SHOULDERS -> 0.75; Group.ARMS -> 0.6; Group.CORE -> 0.5
    }

    /** Reps in reserve from how much the reps slowed (pod), else from your 1RM. */
    fun rir(l: SetLog, e1rm: Double?): Double? {
        l.speedDrop?.let { return (5.0 - it / 10.0).coerceIn(0.0, 5.0) }
        if (e1rm == null || l.weight <= 0) return null
        val maxReps = 30 * (e1rm / l.weight - 1)
        return (maxReps - l.reps).coerceIn(0.0, 8.0)
    }

    fun score(s: Session, history: List<Session>): SessionLoad {
        // best estimated 1RM per lift from earlier sessions; this session's best if it's new
        val before = history.filter { it.startMs < s.startMs && it.id != s.id }.flatMap { it.sets }.filter { !it.warmup && it.weight > 0 }
            .groupBy { it.exerciseId }.mapValues { (_, v) -> v.maxOf { Stats.oneRm(it.weight, it.reps) } }
        val own = s.sets.filter { !it.warmup && it.weight > 0 }.groupBy { it.exerciseId }
            .mapValues { (_, v) -> v.maxOf { Stats.oneRm(it.weight, it.reps) } }
        val scores = s.sets.map { l ->
            val ex = runCatching { Routines.byId(l.exerciseId) }.getOrNull()
            val e1 = before[l.exerciseId] ?: own[l.exerciseId]
            val bodyweight = ex?.bodyweight == true || l.weight <= 0
            val inten = if (bodyweight || e1 == null) 0.7 else (l.weight / e1).coerceIn(0.2, 1.05)
            val r = rir(l, if (bodyweight) null else e1)
            val effort = 1 + 1.5 * exp(-(r ?: 3.0) / 2)
            val m = ex?.let { muscle(Routines.group(it)) } ?: 1.0
            val sides = if (ex != null && Routines.isOneArm(ex)) 2 else 1
            val load = l.reps * sides * inten * inten * effort * m * (if (l.warmup || l.calibration) 0.3 else 1.0)
            SetScore(l, load, r, inten)
        }
        val total = scores.sumOf { it.load }
        val groups = scores.groupBy { s2 -> runCatching { Routines.group(Routines.byId(s2.log.exerciseId)) }.getOrDefault(Group.CORE) }
            .mapValues { (_, v) -> v.sumOf { it.load } }
        return SessionLoad(total, strainOf(total), scores, groups)
    }

    /** Muscular load per day (all gym sessions that day). */
    fun byDay(sessions: List<Session>, zone: ZoneId): Map<LocalDate, SessionLoad> =
        sessions.filter { it.sets.isNotEmpty() }.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
            .mapValues { (_, list) ->
                val parts = list.map { score(it, sessions) }
                val load = parts.sumOf { it.load }
                SessionLoad(load, strainOf(load), parts.flatMap { it.sets },
                    parts.flatMap { it.byGroup.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() })
            }

    fun trimpOf(load: Double): Int = (load * TO_TRIMP).roundToInt()
}
