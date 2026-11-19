package com.silverajat.lifttracker.health

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * BODY: what last night's signals say together, and what could explain them.
 *
 * Each night, five signals are compared with YOUR normal (median and robust spread of the
 * nights 4 to 33 days before, so a few rough days don't become the new normal):
 *   breathing rate (up), skin temperature (up), resting HR (up), HRV (off either way), blood oxygen (down), time asleep (up).
 * Each is normal, a bit off, or well off (needs both a big-enough change and a big-enough z).
 *
 * Then the pattern is read with what you did (strain, gym, sleep, last meal) and turned into
 * likely reasons, each with its evidence:
 *   getting sick   breathing rate up is the strongest sign; with resting HR up, HRV off, oxygen down,
 *                  more sleep, and 2+ nights in a row it gets more likely
 *   hard training  resting HR up / HRV down the night after a big day, breathing normal
 *   overreaching   HRV's 7-day average down (or very high) while the week's load is 30%+ above usual
 *   short sleep    the same heart signs after a short night
 *   late meal      the same, one night only, after eating late
 *   something else heart signs off with nothing above to explain them: stress, alcohol, an injury
 *                  or inflammation; more worth noticing the longer it lasts
 *   settling down  after a sick-looking spell, which signs are back to normal and which aren't yet
 *   adapting well  HRV up a little, resting HR down or normal, breathing normal
 * Full write-up: docs/HOW_IT_WORKS.md.
 */
object BodyCheck {
    enum class Signal(val label: String) {
        BREATHING("Breathing rate"), TEMPERATURE("Skin temperature"), RESTING_HR("Resting HR"), HRV("HRV"), OXYGEN("Blood oxygen"), SLEEP("Time asleep")
    }

    enum class Level { NORMAL, MILD, STRONG }

    /** [change] is in the signal's unit, except HRV, where it's a percentage. */
    data class Reading(val signal: Signal, val value: Double, val normal: Double, val change: Double, val z: Double, val level: Level) {
        val off get() = level != Level.NORMAL
        val points get() = when (level) { Level.STRONG -> 2; Level.MILD -> 1; Level.NORMAL -> 0 }
        fun valueText() = when (signal) {
            Signal.BREATHING -> "%.1f".format(value)
            Signal.TEMPERATURE -> "%+.1f°C".format(value)
            Signal.RESTING_HR -> "${value.roundToInt()} bpm"
            Signal.HRV -> "${value.roundToInt()} ms"
            Signal.OXYGEN -> "%.1f%%".format(value)
            Signal.SLEEP -> hm(value.roundToInt())
        }
        fun normalText() = when (signal) {
            Signal.BREATHING -> "%.1f".format(normal)
            Signal.TEMPERATURE -> "%+.1f".format(normal)
            Signal.RESTING_HR -> "${normal.roundToInt()}"
            Signal.HRV -> "${normal.roundToInt()}"
            Signal.OXYGEN -> "%.1f%%".format(normal)
            Signal.SLEEP -> hm(normal.roundToInt())
        }
        fun changeText() = when (signal) {
            Signal.BREATHING -> "%+.1f".format(change)
            Signal.TEMPERATURE -> "%+.1f°C".format(change)
            Signal.RESTING_HR -> "%+d".format(change.roundToInt())
            Signal.HRV -> "%+d%%".format(change.roundToInt())
            Signal.OXYGEN -> "%+.1f".format(change)
            Signal.SLEEP -> (if (change >= 0) "+" else "−") + hm(abs(change).roundToInt())
        }
        /** "Breathing rate 16.4, normal 15.1 (+1.3)" */
        fun line() = "${signal.label} ${valueText()}, normal ${normalText()} (${changeText()})"
    }

    data class Night(val date: LocalDate, val readings: List<Reading>) {
        operator fun get(s: Signal) = readings.firstOrNull { it.signal == s }
        fun off(s: Signal) = get(s)?.off == true
        val rrUp get() = off(Signal.BREATHING)
        val rhrUp get() = off(Signal.RESTING_HR)
        val hrvOff get() = off(Signal.HRV)
        val hrvDown get() = get(Signal.HRV)?.let { it.off && it.change < 0 } == true
        val o2Down get() = off(Signal.OXYGEN)
        val tempUp get() = off(Signal.TEMPERATURE)
        val heartOff get() = rhrUp || hrvOff
        val anyOff get() = rrUp || tempUp || rhrUp || hrvOff || o2Down
        fun pts(s: Signal) = get(s)?.points ?: 0
    }

    enum class State(val label: String) {
        NORMAL("All normal"), ADAPTING("Recovering well"), WATCH("Something's off"),
        STRAINED("Carrying hard training"), SICK("Fighting something off"), SETTLING("Settling down"),
    }

    /** [likely]: the strongest explanation; otherwise one possibility. */
    data class Reason(val title: String, val likely: Boolean, val evidence: List<String>, val advice: String)

    data class Result(
        val state: State, val headline: String, val since: LocalDate?,
        val night: Night?, val nights: List<Night>, val reasons: List<Reason>,
    )

    /** What you did, per date, for the explanations. */
    data class Context(val strain: Double?, val gymPct: Int?, val liftStrain: Double?, val sleepPerf: Int?, val owedMin: Int?, val lastFood: LocalTime?)

    // ---- per night --------------------------------------------------------------------------

    private fun median(x: List<Double>): Double {
        val s = x.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Median and robust spread (1.4826 × median absolute deviation), never below [floor]. */
    private fun normal(x: List<Double>, floor: Double): Pair<Double, Double>? {
        if (x.size < 7) return null
        val m = median(x)
        return m to (1.4826 * median(x.map { abs(it - m) })).coerceAtLeast(floor)
    }

    private fun level(s: Signal, change: Double, z: Double): Level = when (s) {
        Signal.BREATHING -> when { change >= 1.0 && z >= 2.5 -> Level.STRONG; change >= 0.5 && z >= 1.5 -> Level.MILD; else -> Level.NORMAL }
        Signal.TEMPERATURE -> when { change >= 1.0 && z >= 2.5 -> Level.STRONG; change >= 0.5 && z >= 1.5 -> Level.MILD; else -> Level.NORMAL }
        Signal.RESTING_HR -> when { change >= 5 && z >= 2.5 -> Level.STRONG; change >= 3 && z >= 1.5 -> Level.MILD; else -> Level.NORMAL }
        Signal.HRV -> when { abs(change) >= 25 && abs(z) >= 2.5 -> Level.STRONG; abs(change) >= 12 && abs(z) >= 1.5 -> Level.MILD; else -> Level.NORMAL }
        Signal.OXYGEN -> when { change <= -2 && z <= -2.5 -> Level.STRONG; change <= -1 && z <= -1.5 -> Level.MILD; else -> Level.NORMAL }
        Signal.SLEEP -> if (change >= 60 && z >= 1.5) Level.MILD else Level.NORMAL
    }

    fun night(days: List<HealthDay>, i: Int): Night? {
        val d = days[i]
        val past = days.subList(maxOf(0, i - 33), maxOf(0, i - 3))
        val out = ArrayList<Reading>()
        fun add(s: Signal, today: Double?, hist: List<Double>, floor: Double) {
            if (today == null) return
            val (m, sd) = normal(hist, floor) ?: return
            val z = (today - m) / sd
            val change = today - m
            out += Reading(s, today, m, change, z, level(s, change, z))
        }
        add(Signal.BREATHING, d.respRate, past.mapNotNull { it.respRate }, 0.3)
        add(Signal.TEMPERATURE, d.skinTempC, past.mapNotNull { it.skinTempC }, 0.15)
        add(Signal.RESTING_HR, d.restingHr?.toDouble(), past.mapNotNull { it.restingHr?.toDouble() }, 1.5)
        // HRV on a log scale (it's skewed), shown as a percentage change
        d.hrvMs?.takeIf { it > 0 }?.let { v ->
            normal(past.mapNotNull { it.hrvMs?.takeIf { x -> x > 0 }?.let { x -> ln(x) } }, 0.08)?.let { (m, sd) ->
                val z = (ln(v) - m) / sd
                val pct = (v / exp(m) - 1) * 100
                out += Reading(Signal.HRV, v, exp(m), pct, z, level(Signal.HRV, pct, z))
            }
        }
        add(Signal.OXYGEN, d.spo2, past.mapNotNull { it.spo2 }, 0.5)
        add(Signal.SLEEP, d.sleep?.totalMin?.toDouble(), past.mapNotNull { it.sleep?.totalMin?.toDouble() }, 30.0)
        return if (out.isEmpty()) null else Night(d.date, out)
    }

    /** How sick a night looks on its own: breathing and temperature count most; heart signs need one of those, oxygen, or each other. */
    fun illness(n: Night, prev: Night?): Int {
        fun big(s: Signal) = when (n[s]?.level) { Level.STRONG -> 3; Level.MILD -> 2; else -> 0 }
        val core = n.rrUp || n.tempUp || n.o2Down || (n.rhrUp && n.hrvOff)
        if (!core) return 0
        return big(Signal.BREATHING) + big(Signal.TEMPERATURE) + n.pts(Signal.RESTING_HR) + n.pts(Signal.HRV) + n.pts(Signal.OXYGEN) + n.pts(Signal.SLEEP) +
            (if (prev != null && (prev.rrUp || prev.tempUp || prev.rhrUp || prev.o2Down)) 1 else 0)
    }

    private fun prevOf(nights: List<Night>, n: Night) = nights.firstOrNull { it.date == n.date.minusDays(1) }

    // ---- reading the pattern ---------------------------------------------------------------

    fun run(health: List<HealthDay>, ctx: Map<LocalDate, Context>, today: LocalDate): Result? {
        val all = health.indices.mapNotNull { night(health, it) }
        if (all.isEmpty()) return null
        val n = all.last().takeIf { it.date >= today.minusDays(1) } ?: return Result(State.NORMAL, "No recent night from the Fitbit.", null, null, all.takeLast(14), emptyList())
        val prev = prevOf(all, n)
        val reasons = ArrayList<Reason>()
        val y = ctx[n.date.minusDays(1)]
        val c = ctx[n.date]
        fun offLines(vararg s: Signal) = s.mapNotNull { n[it]?.takeIf { r -> r.off }?.line() }

        // how long it's been going: nights in a row (one normal night allowed in between) with a sign off
        fun runStart(): LocalDate {
            var start = n.date
            var d = n.date.minusDays(1)
            var gap = 0
            while (true) {
                val k = all.firstOrNull { it.date == d }
                if (k != null && k.anyOff) { start = d; gap = 0 } else if (++gap > 1) break
                d = d.minusDays(1)
            }
            return start
        }
        val start = runStart()
        val nightsOff = all.count { it.date >= start && it.anyOff }

        // 1. getting sick
        val ill = illness(n, prev)
        val sick = ill >= 3
        if (sick) {
            val ev = offLines(Signal.BREATHING, Signal.TEMPERATURE, Signal.RESTING_HR, Signal.HRV, Signal.OXYGEN, Signal.SLEEP).toMutableList()
            if (nightsOff >= 2) ev += "$nightsOff nights in a row"
            reasons += Reason("Getting sick", ill >= 5 && (n.rrUp || n.tempUp || n.o2Down), ev,
                "Rest day: no hard training. Sleep long, drink plenty. If it's still up after 3 nights or you feel worse, see a doctor.")
        }

        // 2. yesterday's training
        val bigDay = y != null && ((y.strain ?: 0.0) >= 14 || (y.gymPct ?: 0) >= 120 || (y.liftStrain ?: 0.0) >= 12)
        if (n.heartOff && !n.rrUp && !n.tempUp && bigDay) {
            val ev = offLines(Signal.RESTING_HR, Signal.HRV).toMutableList()
            y?.strain?.let { ev += "Yesterday's strain %.1f".format(it) }
            y?.gymPct?.takeIf { it >= 100 }?.let { ev += "Yesterday's gym: $it% of your usual" }
            reasons += Reason("Recovering from yesterday's training",
                !sick && ((y?.strain ?: 0.0) >= 16 || (y?.gymPct ?: 0) >= 130 || (y?.liftStrain ?: 0.0) >= 14), ev,
                "Normal after a big day. Train lighter or other muscles today; it should settle in 1-2 nights.")
        }

        // 3. the week: overreaching (HRV's 7-day average vs normal, with the week's load vs the month's)
        val recentHrv = health.filter { it.date > n.date.minusDays(7) && it.date <= n.date }.mapNotNull { it.hrvMs?.takeIf { v -> v > 0 } }
        val hrvNormal = n[Signal.HRV]?.normal
        val week = (0 until 7).mapNotNull { ctx[n.date.minusDays(it.toLong())]?.strain }
        val month = (7 until 35).mapNotNull { ctx[n.date.minusDays(it.toLong())]?.strain }
        val loadRatio = if (week.size >= 4 && month.size >= 14 && month.average() > 0) week.average() / month.average() else null
        if (recentHrv.size >= 4 && hrvNormal != null && loadRatio != null && loadRatio >= 1.3) {
            val weekPct = (exp(recentHrv.map { ln(it) }.average()) / hrvNormal - 1) * 100
            if (weekPct <= -10 || weekPct >= 25) reasons += Reason(
                if (weekPct < 0) "A heavy week adding up" else "A heavy week: HRV unusually high",
                weekPct <= -15 && !sick,
                listOf("HRV, 7-day average: %+d%% vs normal".format(weekPct.roundToInt()), "This week's strain ${((loadRatio - 1) * 100).roundToInt()}% above your usual"),
                "Take 2-3 easy days, or make this a lighter week.")
        }

        // 4. short sleep
        val shortNight = c?.sleepPerf?.let { it < 75 } == true || (c?.owedMin ?: 0) >= 120
        if (n.heartOff && shortNight) {
            val ev = offLines(Signal.RESTING_HR, Signal.HRV).toMutableList()
            c?.sleepPerf?.let { ev += "Slept $it% of what you needed" }
            c?.owedMin?.takeIf { it >= 60 }?.let { ev += "${hm(it)} short over the last 3 nights" }
            reasons += Reason("Short on sleep", false, ev, "An early night tonight fixes most of this.")
        }

        // 5. late meal (one night only)
        val late = y?.lastFood?.let { it >= LocalTime.of(21, 30) } == true
        val oneNight = prev == null || !prev.heartOff
        if (n.heartOff && !n.rrUp && !n.tempUp && late && oneNight) {
            reasons += Reason("Late meal", false, offLines(Signal.RESTING_HR, Signal.HRV) + "Last food at ${y!!.lastFood!!.let { "%d:%02d".format(it.hour, it.minute) }}",
                "Digesting keeps your heart rate up overnight. Finish eating by 9:30 PM.")
        }

        // 6. nothing above explains it
        val heartPts = n.pts(Signal.RESTING_HR) + n.pts(Signal.HRV)
        if (heartPts >= 2 && !sick && reasons.none { it.likely }) {
            val longRun = nightsOff >= 3
            reasons += Reason(if (longRun) "Something else, for $nightsOff nights" else "Stress, alcohol or an injury",
                longRun, offLines(Signal.RESTING_HR, Signal.HRV),
                if (longRun) "Several nights off with no hard training behind it: if something hurts or you feel run down, go easy until it settles."
                else "One night like this is common. If it's still off tomorrow, go easy.")
        }

        // 7. settling down after a sick-looking spell in the last 10 days
        val recentSick = all.filter { it.date < n.date && it.date >= n.date.minusDays(10) }.filter { illness(it, prevOf(all, it)) >= 3 }
        if (!sick && recentSick.isNotEmpty()) {
            val peak = recentSick.maxBy { illness(it, prevOf(all, it)) }
            val ev = listOf(Signal.BREATHING, Signal.TEMPERATURE, Signal.RESTING_HR, Signal.HRV, Signal.OXYGEN).mapNotNull { s ->
                val was = peak[s]?.takeIf { it.off } ?: return@mapNotNull null
                val now = n[s]
                if (now == null || !now.off) "${s.label} back to normal (was ${was.changeText()})" else "${s.label} still ${now.changeText()}"
            }
            val allBack = ev.none { it.contains(" still ") }
            reasons.add(0, Reason(if (allBack) "Back to normal after being run down" else "Settling down after being run down", true, ev,
                if (allBack) "Ease back in: one normal session, then build up." else "Keep training light until everything's back to normal."))
        }

        // 8. all good
        val hrv = n[Signal.HRV]
        val rhr = n[Signal.RESTING_HR]
        if (reasons.isEmpty() && hrv != null && hrv.change > 0 && hrv.z >= 1.0 && (rhr == null || rhr.change <= 0) && !n.rrUp && !n.tempUp) {
            reasons += Reason("Recovering well", true, listOfNotNull("HRV ${hrv.valueText()}, %+d%% vs normal".format(hrv.change.roundToInt()),
                rhr?.let { "Resting HR ${it.valueText()}, normal ${it.normalText()}" }), "A good day to push.")
        }

        val top = reasons.sortedByDescending { it.likely }.firstOrNull()
        val state = when {
            sick && reasons.first { it.title == "Getting sick" }.likely -> State.SICK
            sick -> State.WATCH
            top == null -> State.NORMAL
            top.title.startsWith("Back to normal") -> State.NORMAL
            top.title.startsWith("Settling") -> State.SETTLING
            top.title.startsWith("Recovering from") || top.title.startsWith("A heavy week") -> if (top.likely) State.STRAINED else State.WATCH
            top.title == "Recovering well" -> State.ADAPTING
            else -> State.WATCH
        }
        val headline = when (state) {
            State.SICK -> "Several signs are off at once."
            State.SETTLING -> "Coming back after being run down."
            State.STRAINED -> "Your body is carrying hard training."
            State.WATCH -> if (n.anyOff) "A few signs are off." else "Nothing big, worth a look."
            State.ADAPTING -> "Signs point to good recovery."
            State.NORMAL -> if (top != null) "Everything's back in your usual range." else "Breathing, heart rate and HRV are all in your usual range."
        }
        val since = if (state == State.SICK || state == State.WATCH || state == State.STRAINED) start.takeIf { it < n.date }
            else if (state == State.SETTLING) recentSick.minOfOrNull { it.date } else null
        return Result(state, headline, since, n, all.filter { it.date > n.date.minusDays(14) },
            reasons.sortedByDescending { it.likely })
    }

    private fun hm(m: Int) = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}
