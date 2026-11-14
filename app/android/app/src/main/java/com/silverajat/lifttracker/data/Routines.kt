package com.silverajat.lifttracker.data

/**
 * The two training days and their exercises, with the last session's sets
 * as the starting point. Once a set is logged in the app, the app's own
 * history replaces these.
 *
 * Exercise ids go into every pod recording (firmware set_label.exercise),
 * so never renumber them: add new ones at the end of a day's range.
 * Upper = 1..19, lower = 21..39. python/tools/dump_logs.py has the same table.
 */
enum class Day(val id: Int, val title: String, val usual: String) {
    UPPER(0, "Upper", "7:15 – 8:40"),
    LOWER(1, "Lower", "7:15 – 8:35"),
}

/** One set as planned or done. weight is in kg; addons = machine add-on plates. */
data class PlanSet(
    val weight: Double,
    val reps: Int,
    val warmup: Boolean = false,
    val addons: Int = 0,
)

/** Muscle group: sets the colour of the exercise's badge. */
enum class Group(val label: String) {
    CHEST("Chest"), BACK("Back"), SHOULDERS("Shoulders"), ARMS("Arms"), LEGS("Legs"), CORE("Core")
}

data class Exercise(
    val id: Int,
    val day: Day,
    val name: String,
    val detail: String = "",
    val last: List<PlanSet>,
    val step: Double = 2.5,          // +/- weight step in kg
    val bodyweight: Boolean = false,
    val note: String = "",
)

private fun w(weight: Double, reps: Int, addons: Int = 0) = PlanSet(weight, reps, warmup = true, addons = addons)
private fun s(weight: Double, reps: Int, addons: Int = 0) = PlanSet(weight, reps, addons = addons)

object Routines {
    val upper = listOf(
        Exercise(1, Day.UPPER, "Incline bench", "3D Smith",
            listOf(w(20.0, 12), s(55.0, 10), s(55.0, 8))),
        Exercise(2, Day.UPPER, "Upper back row", "",
            listOf(w(20.0, 12), s(45.0, 9), s(45.0, 10))),
        Exercise(3, Day.UPPER, "Overhead press", "3D Smith",
            listOf(w(20.0, 12), s(50.0, 8), s(50.0, 8))),
        Exercise(4, Day.UPPER, "Lat pulldown", "",
            listOf(w(33.0, 12), s(68.0, 8, addons = 1))),
        Exercise(5, Day.UPPER, "Lateral raises", "",
            listOf(s(6.25, 10, addons = 2), s(6.25, 9, addons = 2)), step = 1.25),
        Exercise(6, Day.UPPER, "Reverse pec dec", "",
            listOf(s(47.0, 12), s(47.0, 12))),
        Exercise(7, Day.UPPER, "Pec dec", "",
            listOf(s(61.0, 10, addons = 1), s(61.0, 8, addons = 1))),
        Exercise(8, Day.UPPER, "Rows machine", "",
            listOf(s(60.0, 10), s(60.0, 10))),
        Exercise(9, Day.UPPER, "Tricep extension", "Single-arm cable",
            listOf(s(11.25, 12)), step = 1.25),
        Exercise(10, Day.UPPER, "Hammer curl", "Cable",
            listOf(s(60.0, 12))),
        Exercise(11, Day.UPPER, "Overhead tricep extension", "",
            listOf(s(45.0, 12))),
        Exercise(12, Day.UPPER, "Preacher curl", "Single-arm",
            listOf(s(32.0, 8))),
    )

    val lower = listOf(
        Exercise(21, Day.LOWER, "Leg press", "",
            listOf(w(79.0, 15), s(142.0, 13), s(142.0, 13)), step = 5.0),
        Exercise(22, Day.LOWER, "Romanian deadlift", "RDL",
            listOf(w(20.0, 12), s(60.0, 12), s(60.0, 12))),
        Exercise(23, Day.LOWER, "Ab crunch machine", "",
            listOf(w(39.0, 12), s(74.0, 8), s(74.0, 8))),
        Exercise(24, Day.LOWER, "Reverse ab crunches", "Bodyweight",
            listOf(s(0.0, 12), s(0.0, 12)), bodyweight = true),
        Exercise(25, Day.LOWER, "Cable woodchoppers", "",
            listOf(s(20.0, 12), s(20.0, 12))),
        Exercise(26, Day.LOWER, "Leg extension", "",
            listOf(s(70.0, 12), s(77.0, 8))),
        Exercise(27, Day.LOWER, "Leg curl", "",
            listOf(s(54.0, 12), s(61.0, 8))),
        Exercise(28, Day.LOWER, "Single-leg calf raise", "",
            listOf(s(61.0, 10), s(61.0, 10))),
        Exercise(29, Day.LOWER, "Back extension", "",
            listOf(s(30.0, 11), s(30.0, 11))),
    )

    // Rest: 3 min after the big lifts (incline bench, lat pulldown), 90 s
    // after the last four arm moves, 2 min after everything else.
    private val big = setOf(1, 4, 21, 22)          // 3 min: heavy compounds (lower: leg press, RDL)
    private val arms = setOf(9, 10, 11, 12, 25, 28) // 1:30: arms, woodchoppers, calf raises

    // Done one arm at a time, left arm first. The pod is on the right wrist,
    // so it counts the right arm's reps (you match them on the left).
    private val oneArm = setOf(5, 9, 12, 25)
    // pod arm done first: woodchoppers (left side with the pod, then right without)
    private val podSideFirst = setOf(25)
    fun podSideFirst(exerciseId: Int) = exerciseId in podSideFirst
    // the wrist hardly moves (legs or hips do the work): the pod can't count these
    private val wristStill = setOf(24, 26, 27)   // reverse ab crunches, leg extension, leg curl
    fun podCounts(exerciseId: Int) = exerciseId !in wristStill

    private val groups = mapOf(
        1 to Group.CHEST, 2 to Group.BACK, 3 to Group.SHOULDERS, 4 to Group.BACK,
        5 to Group.SHOULDERS, 6 to Group.SHOULDERS, 7 to Group.CHEST, 8 to Group.BACK,
        9 to Group.ARMS, 10 to Group.ARMS, 11 to Group.ARMS, 12 to Group.ARMS,
        21 to Group.LEGS, 22 to Group.LEGS, 23 to Group.CORE, 24 to Group.CORE,
        25 to Group.CORE, 26 to Group.LEGS, 27 to Group.LEGS, 28 to Group.LEGS, 29 to Group.BACK,
    )

    /** Rest after a warm-up set (they're light). */
    const val WARMUP_REST_S = 60

    fun restSeconds(ex: Exercise) = when (ex.id) {
        in big -> 180
        in arms -> 90
        else -> 120
    }
    fun isOneArm(ex: Exercise) = ex.id in oneArm
    fun isOneArm(exerciseId: Int) = exerciseId in oneArm
    fun group(ex: Exercise) = groups[ex.id] ?: Group.CORE

    fun forDay(day: Day) = if (day == Day.UPPER) upper else lower
    fun byId(id: Int) = (upper + lower).first { it.id == id }
}
