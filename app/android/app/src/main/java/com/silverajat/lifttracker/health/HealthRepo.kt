package com.silverajat.lifttracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

/**
 * Fitbit data via Health Connect: the Google Health (Fitbit) app writes your
 * watch's data there, and this app reads it on the phone. No account, no
 * API key, no server. Read-only.
 */
class HealthRepo(private val context: Context) {
    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(RespiratoryRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    /** Lets the hourly check read new data while the app is closed (Android 14+). */
    val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    /** What the "Connect" button asks for. */
    /** Lets the first sync read further back than Health Connect's default 30 days. */
    val historyPermission = "android.permission.health.READ_HEALTH_DATA_HISTORY"

    val requested: Set<String> get() = permissions + backgroundPermission + historyPermission

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> = if (!available()) emptySet()
        else runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    private suspend fun <T : Record> readAll(type: KClass<T>, start: Instant, end: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        do {
            val r = client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(start, end), pageSize = 1000, pageToken = token))
            out += r.records
            token = r.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    /** The last [n] days (oldest first), today included. Missing permissions just give nulls. */
    suspend fun days(n: Int, hrMax: Int, zone: ZoneId = ZoneId.systemDefault()): List<HealthDay> {
        val g = granted()
        fun has(t: KClass<out Record>) = HealthPermission.getReadPermission(t) in g
        val today = LocalDate.now(zone)
        val first = today.minusDays((n - 1).toLong())
        val start = first.atStartOfDay(zone).toInstant()
        val end = Instant.now()
        fun dayOf(t: Instant) = t.atZone(zone).toLocalDate()

        val steps = HashMap<LocalDate, Long>()
        val kcalTotal = HashMap<LocalDate, Double>()
        val kcalActive = HashMap<LocalDate, Double>()
        val metrics = buildSet {
            if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
        }
        if (metrics.isNotEmpty()) {
            runCatching {
                client.aggregateGroupByPeriod(
                    AggregateGroupByPeriodRequest(
                        metrics = metrics,
                        timeRangeFilter = TimeRangeFilter.between(first.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                        timeRangeSlicer = Period.ofDays(1),
                    )
                ).forEach { grp ->
                    val d = grp.startTime.toLocalDate()
                    grp.result[StepsRecord.COUNT_TOTAL]?.let { steps[d] = it }
                    grp.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { kcalTotal[d] = it.inKilocalories }
                    grp.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let { kcalActive[d] = it.inKilocalories }
                }
            }
        }

        val rhr = if (has(RestingHeartRateRecord::class)) runCatching {
            readAll(RestingHeartRateRecord::class, start, end).groupBy { dayOf(it.time) }
                .mapValues { (_, v) -> v.last().beatsPerMinute.toInt() }
        }.getOrDefault(emptyMap()) else emptyMap()

        val hrv = if (has(HeartRateVariabilityRmssdRecord::class)) runCatching {
            readAll(HeartRateVariabilityRmssdRecord::class, start, end).groupBy { dayOf(it.time) }
                .mapValues { (_, v) -> v.map { it.heartRateVariabilityMillis }.average() }
        }.getOrDefault(emptyMap()) else emptyMap()

        val spo2 = if (has(OxygenSaturationRecord::class)) runCatching {
            readAll(OxygenSaturationRecord::class, start, end).groupBy { dayOf(it.time) }
                .mapValues { (_, v) -> v.map { it.percentage.value }.average() }
        }.getOrDefault(emptyMap()) else emptyMap()

        val resp = if (has(RespiratoryRateRecord::class)) runCatching {
            readAll(RespiratoryRateRecord::class, start, end).groupBy { dayOf(it.time) }
                .mapValues { (_, v) -> v.map { it.rate }.average() }
        }.getOrDefault(emptyMap()) else emptyMap()

        // sleep counts for the day you wake up; start a day early to catch last night
        val sleep = if (has(SleepSessionRecord::class)) runCatching {
            readAll(SleepSessionRecord::class, start.minus(Duration.ofDays(1)), end)
                .groupBy { dayOf(it.endTime) }
                .mapValues { (_, v) -> summarise(v.maxBy { Duration.between(it.startTime, it.endTime) }) }
        }.getOrDefault(emptyMap()) else emptyMap()

        val hr = if (has(HeartRateRecord::class)) runCatching {
            readAll(HeartRateRecord::class, start, end).flatMap { r -> r.samples.map { HrSample(it.time, it.beatsPerMinute.toInt()) } }
                .groupBy { dayOf(it.time) }
        }.getOrDefault(emptyMap()) else emptyMap()

        val distances = if (has(DistanceRecord::class)) runCatching { readAll(DistanceRecord::class, start, end) }.getOrDefault(emptyList()) else emptyList()

        // each workout with its own heart rate, strain and distance
        val workouts = if (has(ExerciseSessionRecord::class)) runCatching {
            readAll(ExerciseSessionRecord::class, start, end).groupBy { dayOf(it.startTime) }
                .mapValues { (d, v) ->
                    v.map { w ->
                        val inside = hr[d].orEmpty().filter { it.time >= w.startTime && it.time <= w.endTime }
                        val km = distances.filter { it.startTime < w.endTime && it.endTime > w.startTime }
                            .sumOf { it.distance.inMeters } / 1000.0
                        Workout(
                            w.startTime, w.endTime, exerciseName(w.exerciseType), w.title,
                            avgHr = inside.takeIf { it.size >= 3 }?.map { it.bpm }?.average()?.toInt(),
                            maxHr = inside.maxOfOrNull { it.bpm },
                            trimp = Scores.load(inside, rhr[d], hrMax)?.trimp,
                            distanceKm = km.takeIf { it > 0.05 },
                        )
                    }
                }
        }.getOrDefault(emptyMap()) else emptyMap()

        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { d ->
            HealthDay(
                date = d, steps = steps[d], kcalTotal = kcalTotal[d], kcalActive = kcalActive[d],
                // if the Fitbit doesn't share resting HR, work it out from last night's heart rate
                restingHr = rhr[d] ?: restingFromHr(hr[d].orEmpty() + hr[d.minusDays(1)].orEmpty(), sleep[d]), hrvMs = hrv[d], spo2 = spo2[d], respRate = resp[d], sleep = sleep[d],
                workouts = workouts[d] ?: emptyList(),
                load = hr[d]?.let { Scores.load(it, rhr[d], hrMax) },
            )
        }.toList()
    }

    /** Lowest 10-minute average heart rate while asleep (or all day, without sleep data). */
    private fun restingFromHr(samples: List<HrSample>, s: SleepSummary?): Int? {
        val inWindow = if (s != null) samples.filter { it.time >= s.start && it.time <= s.end } else emptyList()
        val use = (inWindow.takeIf { it.size >= 20 } ?: samples).sortedBy { it.time }
        if (use.size < 20) return null
        var best = Double.MAX_VALUE
        var j = 0
        var sum = 0.0
        for (i in use.indices) {
            sum += use[i].bpm
            while (use[i].time.epochSecond - use[j].time.epochSecond > 600) { sum -= use[j].bpm; j++ }
            if (i - j + 1 >= 5) best = minOf(best, sum / (i - j + 1))
        }
        return if (best == Double.MAX_VALUE) null else best.toInt()
    }

    /** What's actually arriving from the Fitbit, per kind of data (last 3 days). */
    data class DataCheck(val name: String, val allowed: Boolean, val count: Int, val latest: Instant?, val sources: Set<String>, val more: Boolean)

    suspend fun check(): List<DataCheck> {
        val g = granted()
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(3))
        val types: List<Pair<String, KClass<out Record>>> = listOf(
            "Heart rate" to HeartRateRecord::class, "HRV" to HeartRateVariabilityRmssdRecord::class,
            "Resting heart rate" to RestingHeartRateRecord::class, "Sleep" to SleepSessionRecord::class,
            "Steps" to StepsRecord::class, "Calories burned" to TotalCaloriesBurnedRecord::class,
            "Workouts" to ExerciseSessionRecord::class, "Breathing rate" to RespiratoryRateRecord::class,
            "Blood oxygen" to OxygenSaturationRecord::class, "Weight" to WeightRecord::class,
        )
        return types.map { (name, t) ->
            if (HealthPermission.getReadPermission(t) !in g) return@map DataCheck(name, false, 0, null, emptySet(), false)
            @Suppress("UNCHECKED_CAST") val kc = t as KClass<Record>
            val r = runCatching { client.readRecords(ReadRecordsRequest(kc, TimeRangeFilter.between(start, end), pageSize = 1000)) }.getOrNull()
            val recs = r?.records.orEmpty()
            val latest = recs.mapNotNull { rec ->
                when (rec) {
                    is HeartRateRecord -> rec.endTime
                    is HeartRateVariabilityRmssdRecord -> rec.time
                    is RestingHeartRateRecord -> rec.time
                    is SleepSessionRecord -> rec.endTime
                    is StepsRecord -> rec.endTime
                    is TotalCaloriesBurnedRecord -> rec.endTime
                    is ExerciseSessionRecord -> rec.endTime
                    is RespiratoryRateRecord -> rec.time
                    is OxygenSaturationRecord -> rec.time
                    is WeightRecord -> rec.time
                    else -> null
                }
            }.maxOrNull()
            DataCheck(name, true, recs.size, latest, recs.map { sourceName(it.metadata.dataOrigin.packageName) }.toSet(), !r?.pageToken.isNullOrEmpty())
        }
    }

    private fun sourceName(pkg: String) = when {
        pkg.startsWith("com.fitbit") -> "Fitbit"
        pkg.startsWith("com.google.android.apps.fitness") -> "Google Fit"
        pkg.contains("healthdata") || pkg.contains("google.android.apps.health") -> "Google Health"
        pkg.startsWith("com.sec.android.app.shealth") -> "Samsung Health"
        pkg == "android" || pkg.startsWith("com.android") -> "your phone"
        else -> pkg
    }

    /** Heart rate during a window (a run you tracked, a football game you logged). */
    suspend fun windowStats(from: Instant, to: Instant, restingHr: Int?, hrMax: Int): Workout? {
        if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted()) return null
        val s = runCatching { readAll(HeartRateRecord::class, from, to).flatMap { r -> r.samples.map { HrSample(it.time, it.beatsPerMinute.toInt()) } } }
            .getOrDefault(emptyList()).filter { it.time >= from && it.time <= to }
        if (s.size < 3) return null
        return Workout(from, to, "", null, avgHr = s.map { it.bpm }.average().toInt(), maxHr = s.maxOf { it.bpm },
            trimp = Scores.load(s, restingHr, hrMax)?.trimp)
    }

    /** Steps in a window (e.g. the 45 min after a meal), or null without permission. */
    suspend fun steps(from: Instant, to: Instant): Long? {
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted()) return null
        return runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(from, to)))[StepsRecord.COUNT_TOTAL] ?: 0L
        }.getOrNull()
    }

    /** Workouts (Fitbit exercise sessions) that ended between [from] and [to]. */
    suspend fun workouts(from: Instant, to: Instant): List<Workout> {
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) !in granted()) return emptyList()
        return runCatching {
            readAll(ExerciseSessionRecord::class, from.minus(Duration.ofHours(4)), to)
                .filter { it.endTime >= from && it.endTime <= to }
                .map { Workout(it.startTime, it.endTime, exerciseName(it.exerciseType), it.title) }
        }.getOrDefault(emptyList())
    }

    /** Heart rate, steps and calories during a window. */
    suspend fun activityStats(from: Instant, to: Instant): com.silverajat.lifttracker.insights.ActivityStats {
        val g = granted()
        val metrics = buildSet {
            if (HealthPermission.getReadPermission(HeartRateRecord::class) in g) { add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX) }
            if (HealthPermission.getReadPermission(StepsRecord::class) in g) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in g) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
        }
        if (metrics.isEmpty()) return com.silverajat.lifttracker.insights.ActivityStats(null, null, null, null)
        val r = runCatching { client.aggregate(AggregateRequest(metrics, TimeRangeFilter.between(from, to))) }.getOrNull()
            ?: return com.silverajat.lifttracker.insights.ActivityStats(null, null, null, null)
        return com.silverajat.lifttracker.insights.ActivityStats(
            steps = r[StepsRecord.COUNT_TOTAL],
            avgHr = r[HeartRateRecord.BPM_AVG]?.toInt(),
            maxHr = r[HeartRateRecord.BPM_MAX]?.toInt(),
            kcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
        )
    }

    /** Weights logged in Google Health (e.g. from a smart scale), newest last. */
    suspend fun weights(days: Long): List<Pair<Instant, Double>> {
        if (HealthPermission.getReadPermission(WeightRecord::class) !in granted()) return emptyList()
        return runCatching {
            readAll(WeightRecord::class, Instant.now().minus(Duration.ofDays(days)), Instant.now())
                .map { it.time to it.weight.inKilograms }
        }.getOrDefault(emptyList())
    }

    private fun summarise(s: SleepSessionRecord): SleepSummary {
        fun mins(types: Set<Int>) = s.stages.filter { it.stage in types }
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
        val total = Duration.between(s.startTime, s.endTime).toMinutes().toInt()
        val timeline = s.stages.sortedBy { it.startTime }.mapNotNull {
            val type = when (it.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> 2
                SleepSessionRecord.STAGE_TYPE_REM -> 3
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_SLEEPING -> 1
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> 0
                else -> null
            }
            type?.let { t -> SleepStage(it.startTime, it.endTime, t) }
        }
        val awake = mins(setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED))
        return SleepSummary(
            start = s.startTime, end = s.endTime,
            totalMin = if (s.stages.isEmpty()) total else total - awake,
            deepMin = mins(setOf(SleepSessionRecord.STAGE_TYPE_DEEP)),
            remMin = mins(setOf(SleepSessionRecord.STAGE_TYPE_REM)),
            lightMin = mins(setOf(SleepSessionRecord.STAGE_TYPE_LIGHT)),
            awakeMin = awake,
            stages = timeline,
        )
    }

    private fun exerciseName(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Bike"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stairs"
        else -> "Workout"
    }
}
