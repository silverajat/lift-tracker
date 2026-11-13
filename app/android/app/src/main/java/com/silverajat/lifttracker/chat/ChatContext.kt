package com.silverajat.lifttracker.chat

import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Scores
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealPlan
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Targets
import com.silverajat.lifttracker.meals.WeightEntry
import com.silverajat.lifttracker.reps.RepModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Everything the app knows, as text for GPT's instructions: the meal
 * plan, what was eaten (with times and estimated macros), Fitbit data from
 * Health Connect, our load and recovery scores, morning weights, and the
 * gym log from the pod (sets, reps, rep quality, ROM, what the counter
 * learned). Kept compact: 14 days of health and food, 21 days of workouts.
 */
object ChatContext {
    private val hm = DateTimeFormatter.ofPattern("H:mm")
    private val dfmt = DateTimeFormatter.ofPattern("EEE d MMM")

    fun build(
        now: Instant,
        zone: ZoneId,
        sessions: List<Session>,
        model: RepModel.Model,
        health: List<HealthDay>,
        analysis: Analysis.Result?,
        meals: Map<LocalDate, List<MealEntry>>,
        weights: List<WeightEntry>,
        hrMax: Int,
        healthNote: String,
    ): String = buildString {
        val today = now.atZone(zone).toLocalDate()
        fun t(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).format(hm)
        fun r(x: Double) = x.roundToInt()

        appendLine("""
            You are the nutrition and training coach inside Rajat's Spotter app; he comes to you to brainstorm about anything: training, food, sleep, plans (he's a Year 2 EE student in Singapore who lifts
            an upper/lower split and wears a Fitbit Air plus his own wrist pod that counts reps). Everything the
            app knows is below. Use it to answer: be specific, use his numbers, keep it short unless he asks for
            detail. Food macros are estimates (hawker meals +-20%); say so when it matters. Our load and recovery
            scores are our own formulas, not Google's Cardio Load or readiness (those can't be read by other apps).
            Don't give medical diagnoses; suggest a doctor for anything that sounds medical.
        """.trimIndent().replace("\n", ""))
        appendLine()
        appendLine("Now: ${now.atZone(zone).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, H:mm"))} ($zone).")
        appendLine()

        appendLine("## Meal plan")
        appendLine(MealPlan.describe())
        appendLine("Estimated planned totals for today (${today.dayOfWeek}): " + MealPlan.plannedTotal(today).let {
            "${r(it.kcal)} kcal, protein ${r(it.protein)} g, carbs ${r(it.carbs)} g, fat ${r(it.fat)} g, fibre ${r(it.fibre)} g"
        })
        appendLine()

        appendLine("## Food log (newest first; est. kcal / protein / carbs / fat)")
        meals.keys.sortedDescending().forEach { d ->
            val list = meals[d] ?: emptyList()
            if (list.isEmpty()) { appendLine("${d.format(dfmt)}: nothing logged"); return@forEach }
            val tot = MealStore.totals(list)
            appendLine("${d.format(dfmt)}: total ${r(tot.kcal)} kcal, P ${r(tot.protein)} g, C ${r(tot.carbs)} g, F ${r(tot.fat)} g")
            if (d >= today.minusDays(2)) list.forEach { e ->
                appendLine("  ${t(e.timeMs)} ${e.slot.title}: ${e.name}" + (if (e.portion != 1.0) " ×${e.portion}" else "") +
                    " (${r(e.macros.kcal)} kcal, P ${r(e.macros.protein)})")
            }
        }
        appendLine()

        appendLine("## Morning weight")
        if (weights.isEmpty()) appendLine("none logged")
        weights.takeLast(21).forEach { appendLine("${it.date.format(dfmt)}: ${"%.1f".format(it.kg)} kg") }
        val weeks = MealStore.weekAverages(weights, today)
        if (weeks.isNotEmpty()) appendLine("Completed 7-day averages (Mon-Sun): " +
            weeks.take(4).joinToString("; ") { "week of ${it.weekStart.format(dfmt)} ${"%.2f".format(it.avgKg)} kg (${it.days} days)" })
        appendLine()

        appendLine("## Fitbit via Health Connect ($healthNote)")
        if (health.isEmpty()) appendLine("no data")
        health.reversed().forEach { h ->
            val parts = listOfNotNull(
                h.steps?.let { "steps $it" },
                h.kcalTotal?.let { "burned ${r(it)} kcal" },
                h.restingHr?.let { "resting HR $it" },
                h.hrvMs?.let { "HRV ${r(it)} ms" },
                h.spo2?.let { "SpO2 ${r(it)}%" },
                h.sleep?.let { s -> "sleep ${s.totalMin / 60}h${s.totalMin % 60}m (deep ${s.deepMin}m, REM ${s.remMin}m, awake ${s.awakeMin}m, ${s.start.atZone(zone).format(hm)}-${s.end.atZone(zone).format(hm)})" },
                h.load?.let { "load ${it.trimp} (moderate ${it.moderateMin} min, vigorous ${it.vigorousMin} min)" },
                h.workouts.takeIf { it.isNotEmpty() }?.joinToString(", ", "workouts: ") {
                    "${it.title ?: it.type} ${it.start.atZone(zone).format(hm)} ${java.time.Duration.between(it.start, it.end).toMinutes()} min"
                },
            )
            appendLine("${h.date.format(dfmt)}: " + parts.joinToString(", ").ifEmpty { "no data" })
        }
        appendLine("Load = Banister TRIMP from heart rate (HRmax $hrMax). 7-day load: ${Scores.weekLoad(health)}.")
        appendLine()
        if (analysis != null) {
            appendLine("## Analysis (formulas: recovery = HRV 0.5 / resting HR 0.2 / sleep performance 0.2 / breathing 0.1 vs his 30-day baseline, zones green>=67 yellow 34-66 red<34; strain 0-21 = 21(1-e^(-TRIMP/110)); sleep need = 7 h baseline + strain + debt)")
            analysis.headline.forEach { appendLine("- $it") }
            analysis.days.takeLast(14).reversed().forEach { d ->
                val p = listOfNotNull(
                    d.recovery?.let { r -> "recovery ${r.score}% ${r.zone.label}" + r.contributors.joinToString("; ", " [", "]") { "${it.name} ${it.value} (${it.vsNormal})" } },
                    d.strain?.let { "strain ${"%.1f".format(it.strain)}" + (it.target?.let { t -> " (target ${t.start.toInt()}-${t.endInclusive.toInt()})" } ?: "") },
                    d.sleep?.let { "sleep ${it.asleepMin} of ${it.needMin} min need (${it.performance}%), debt ${it.debtMin} min" +
                        (it.consistency?.let { c -> ", consistency $c%" } ?: "") + (it.efficiency?.let { e -> ", efficiency $e%" } ?: "") },
                    d.muscular?.let { "gym volume ${r(it.volumeKg)} kg in ${it.sets} sets" + (it.vsAvgPct?.let { v -> " ($v% of his average gym day)" } ?: "") },
                    d.food?.let { "ate ${r(it.kcal)} kcal, P ${r(it.protein)}" },
                )
                if (p.isNotEmpty()) appendLine("${d.date.format(dfmt)}: " + p.joinToString(", "))
            }
            if (analysis.exercises.isNotEmpty()) {
                appendLine("Strength trends (est. 1RM, Epley; vs ~3 weeks earlier or pre-app numbers) and next-session suggestion (double progression):")
                analysis.exercises.forEach { e ->
                    appendLine("  ${e.name}: e1RM ${r(e.e1rmNow)} kg" + (e.changePct?.let { " (${if (it >= 0) "+" else ""}${"%.1f".format(it)}%)" } ?: "") +
                        "; last ${e.lastSets}; ${e.suggestion}" + (e.qualityAvg?.let { "; quality $it" } ?: "") + (e.romAvg?.let { "; ROM $it%" } ?: ""))
                }
            }
            if (analysis.groupSets7d.isNotEmpty()) appendLine("Working sets per muscle group, last 7 days: " + analysis.groupSets7d.entries.joinToString(", ") { "${it.key.label} ${it.value}" })
            analysis.nutrition?.let { n ->
                appendLine("Nutrition, last ${n.days} logged days: avg ${n.avgKcal} kcal, protein ${n.avgProtein} g (target hit ${n.proteinDaysHit}/${n.days} days)" +
                    (n.proteinPerKg?.let { ", ${"%.1f".format(it)} g/kg" } ?: "") + ", fat ${n.avgFat} g" +
                    (n.avgBalance?.let { ", intake minus burned ${it} kcal/day (predicts ${"%.2f".format(n.predictedKgPerWeek)} kg/week)" } ?: ""))
            }
            appendLine("Weight: ${analysis.weight.verdict}")
            analysis.energy.let { e ->
                appendLine("Net calories (eaten minus burned; burn = Fitbit x ${"%.2f".format(e.k)}, calibrated against his weight trend with a Kalman filter, confidence ${e.confidence}%): " +
                    (e.avgNet?.let { "last 2 weeks average ${it} kcal/day" } ?: "not enough logged days yet") +
                    (e.scaleNet?.let { ", the scale trend alone implies ${it} kcal/day" } ?: "") +
                    (e.trendKgPerWeek?.let { ", trend ${"%.2f".format(it)} kg/week" } ?: "") +
                    ". His goal: maintenance (net ~0 kcal/day) for a muscle recomp, for the foreseeable future. He tracks net calories, not protein or carbs.")
            }
            if (analysis.effects.isNotEmpty()) {
                appendLine("What affects his next-day recovery (with vs without, n days):")
                analysis.effects.forEach { e -> appendLine("  ${e.habit}: ${r(e.with)} vs ${r(e.without)} (${e.nWith}/${e.nWithout} days, ${if (e.clear) "clear" else "early signal"})") }
            }
        }
        appendLine()

        appendLine("## Gym log from the pod (last 21 days, newest first)")
        val since = today.minusDays(21)
        sessions.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() >= since }
            .sortedByDescending { it.startMs }.forEach { s ->
                val start = Instant.ofEpochMilli(s.startMs).atZone(zone)
                appendLine("${start.format(dfmt)} ${start.format(hm)} ${s.day.title} day" + (if (s.endMs == null) " (not finished)" else ""))
                s.sets.groupBy { it.exerciseId }.forEach { (id, sets) ->
                    val ex = runCatching { Routines.byId(id) }.getOrNull()
                    val line = sets.sortedBy { it.setNo }.joinToString(", ") { l ->
                        buildString {
                            append(if (l.calibration) "C " else if (l.warmup) "W " else "")
                            append("${fmtKg(l.weight)}kg")
                            if (l.addons > 0) append("+${l.addons} add-on")
                            append("×${l.reps}")
                            if (ex != null && Routines.isOneArm(ex)) append("/arm")
                            l.quality?.let { append(" Q$it") }
                            if (l.romPct.isNotEmpty()) append(" ROM ${l.romPct.average().roundToInt()}%")
                            l.speedDrop?.let { if (it > 0) append(" speed drop $it%") }
                            if (l.liveReps != null && l.liveReps != l.reps) append(" (pod said ${l.liveReps})")
                        }
                    }
                    appendLine("  ${ex?.name ?: "exercise $id"}: $line")
                }
            }
        appendLine()
        appendLine("## Rep counter (learns from his corrections)")
        model.perExercise.toSortedMap().forEach { (id, f) ->
            val name = runCatching { Routines.byId(id).name }.getOrDefault("exercise $id")
            appendLine("$name: exact on ${f.exactLearned}/${f.sets} corrected sets (default counter ${f.exactDefault}/${f.sets})")
        }
        appendLine()
        appendLine("Targets reminder: protein ${Targets.protein.start.toInt()}-${Targets.protein.endInclusive.toInt()} g, fat ${Targets.fat.start.toInt()}-${Targets.fat.endInclusive.toInt()} g, burn ~${Targets.burnKcal} kcal/day.")
    }

    private fun fmtKg(w: Double) = if (w == Math.floor(w)) "%.0f".format(w) else "%.2f".format(w).trimEnd('0').trimEnd('.')
}
