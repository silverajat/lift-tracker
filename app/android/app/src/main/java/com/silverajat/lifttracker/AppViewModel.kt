package com.silverajat.lifttracker

import android.app.Application
import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silverajat.lifttracker.ble.PodClient
import com.silverajat.lifttracker.ble.PodConn
import com.silverajat.lifttracker.ble.PodProtocol
import com.silverajat.lifttracker.data.Day
import com.silverajat.lifttracker.data.Exercise
import com.silverajat.lifttracker.data.PlanSet
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SessionStore
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.reps.RepCounter
import com.silverajat.lifttracker.reps.SetAnalysis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Screen {
    data object Home : Screen
    data class Workout(val day: Day) : Screen
    data class ExerciseInfo(val exerciseId: Int) : Screen
}

enum class Phase { READY, LIFTING, CONFIRM }

/** The set between Start and Save. */
data class ActiveSet(
    val exerciseId: Int,
    val setNo: Int,
    val weight: Double,
    val addons: Int,
    val warmup: Boolean,
    val startMs: Long,
    val endMs: Long? = null,
    val podStarted: Boolean = false,
    val podRec: Int? = null,
    val calibration: Boolean = false,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val pod = PodClient(app, viewModelScope)
    private val store = SessionStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _history = MutableStateFlow(store.all())
    val history: StateFlow<List<Session>> = _history

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen

    private val _phase = MutableStateFlow(Phase.READY)
    val phase: StateFlow<Phase> = _phase

    private val _active = MutableStateFlow<ActiveSet?>(null)
    val active: StateFlow<ActiveSet?> = _active

    private val _liveReps = MutableStateFlow(0)
    val liveReps: StateFlow<Int> = _liveReps

    private val _restEndsAt = MutableStateFlow<Long?>(null)
    val restEndsAt: StateFlow<Long?> = _restEndsAt

    /** Length of the rest that's running (120 s main lifts, 90 s isolation). */
    private val _restSeconds = MutableStateFlow(120)
    val restSeconds: StateFlow<Int> = _restSeconds

    /** Rep quality of the set just stopped (null without the pod). */
    private val _analysis = MutableStateFlow<SetAnalysis?>(null)
    val analysis: StateFlow<SetAnalysis?> = _analysis

    /** Clock for timers on screen. */
    val now: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(200)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), System.currentTimeMillis())

    private val counter = RepCounter(PodProtocol.STREAM_HZ)
    private var restJob: Job? = null

    init {
        // Pick up a workout that was still open (app killed, phone restarted).
        _session.value = _history.value.firstOrNull {
            it.endMs == null && System.currentTimeMillis() - it.startMs < 4 * 3600_000L
        }
        viewModelScope.launch {
            pod.samples.collect { s ->
                if (_phase.value == Phase.LIFTING) _liveReps.value = counter.add(s)
            }
        }
        viewModelScope.launch {
            // Whenever the pod (re)connects, make sure it isn't auto-recording.
            pod.conn.collect { if (it == PodConn.READY) pod.send(PodProtocol.auto(false)) }
        }
    }

    fun exportJson(s: Session): String = store.exportJson(s)
    fun shareText(s: Session): String = com.silverajat.lifttracker.data.ShareText.build(s)

    // ---- navigation -----------------------------------------------------------

    fun open(s: Screen) { _screen.value = s }

    fun back(): Boolean {
        when (val s = _screen.value) {
            is Screen.ExerciseInfo -> _screen.value = Screen.Workout(Routines.byId(s.exerciseId).day)
            is Screen.Workout -> _screen.value = Screen.Home
            Screen.Home -> return false
        }
        return true
    }

    // ---- history / suggestions -------------------------------------------------

    fun setsToday(exerciseId: Int): List<SetLog> =
        _session.value?.sets?.filter { it.exerciseId == exerciseId } ?: emptyList()

    /** What you did last time (app history first, then the routine's numbers). */
    fun lastTime(ex: Exercise): List<PlanSet> {
        val current = _session.value?.id
        val past = _history.value.firstOrNull { s ->
            s.id != current && s.sets.any { it.exerciseId == ex.id }
        }
        return past?.sets?.filter { it.exerciseId == ex.id }
            ?.sortedBy { it.setNo }
            ?.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
            ?: ex.last
    }

    fun lastTimeDate(ex: Exercise): Long? {
        val current = _session.value?.id
        return _history.value.firstOrNull { s ->
            s.id != current && s.sets.any { it.exerciseId == ex.id }
        }?.startMs
    }

    /** Extra set rows added with "+ Add set", per exercise. */
    private val _extraRows = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val extraRows: StateFlow<Map<Int, Int>> = _extraRows

    fun addRow(exerciseId: Int) {
        _extraRows.value = _extraRows.value + (exerciseId to ((_extraRows.value[exerciseId] ?: 0) + 1))
    }

    /**
     * Your full ROM for an exercise, in degrees (rotation lifts only):
     *  1. today's warm-up set, if you did one with the pod: light weight is
     *     where your range is best, so working sets are scored against it;
     *  2. otherwise your best logged set (median of its 3 biggest reps);
     *  3. otherwise null, and the set is scored against its own best reps.
     */
    fun fullRomDeg(exerciseId: Int): Float? {
        calibration(exerciseId)?.let { return it.first }
        val warm = _session.value?.sets
            ?.filter { it.exerciseId == exerciseId && it.warmup && it.romDeg.size >= 2 }
            ?.maxOfOrNull { top3(it.romDeg) }
        return warm ?: bestRomDeg(exerciseId)
    }

    /** Every logged set of an exercise, this workout included. */
    private fun allSets(exerciseId: Int): List<SetLog> {
        val cur = _session.value
        val past = _history.value.filter { it.id != cur?.id }.flatMap { it.sets }
        return (past + (cur?.sets ?: emptyList())).filter { it.exerciseId == exerciseId }
    }

    /**
     * The calibrated full ROM (degrees) and when it was set: the latest C set,
     * raised by any later set that went further (your range improved).
     * Null if the exercise was never calibrated with the pod.
     */
    fun calibration(exerciseId: Int): Pair<Float, Long>? {
        val sets = allSets(exerciseId).filter { it.romDeg.size >= 2 }
        val cal = sets.filter { it.calibration }.maxByOrNull { it.startMs } ?: return null
        val later = sets.filter { it.startMs > cal.startMs }.map { top3(it.romDeg) }
        return Pair((later + top3(cal.romDeg)).max(), cal.startMs)
    }

    private fun top3(x: List<Float>): Float = x.sortedDescending().take(3).let { t -> t.sorted()[t.size / 2] }

    private fun bestRomDeg(exerciseId: Int): Float? =
        _history.value.flatMap { it.sets }
            .filter { it.exerciseId == exerciseId && it.romDeg.size >= 2 }
            .map { s -> s.romDeg.sortedDescending().take(3).let { t -> t.sorted()[t.size / 2] } }
            .maxOrNull()

    /** Every finished session, oldest first (for charts and the history tab). */
    fun sessionsWith(exerciseId: Int): List<Session> =
        _history.value.filter { s -> s.sets.any { it.exerciseId == exerciseId } }.sortedBy { it.startMs }

    // ---- the set flow ------------------------------------------------------------

    fun startSet(ex: Exercise, setNo: Int, weight: Double, addons: Int, warmup: Boolean, calibration: Boolean = false) {
        if (_phase.value != Phase.READY) return
        val now = System.currentTimeMillis()
        val session = _session.value?.takeIf { it.day == ex.day }
            ?: Session(id = now / 1000, day = ex.day, startMs = now, endMs = null, sets = emptyList())
                .also { _session.value = it; saveSession(it) }
        val set = ActiveSet(
            exerciseId = ex.id,
            setNo = setNo,
            weight = weight, addons = addons, warmup = warmup || calibration, startMs = now,
            calibration = calibration,
        )
        _active.value = set
        counter.reset()
        _liveReps.value = 0
        _phase.value = Phase.LIFTING
        restJob?.cancel()
        _restEndsAt.value = null
        viewModelScope.launch {
            val ok = pod.send(
                PodProtocol.start(
                    exercise = ex.id, day = ex.day.id, setNo = set.setNo, warmup = set.warmup,
                    calibration = calibration,
                    weightKg = weight, unixTime = now / 1000, sessionId = session.id,
                )
            )
            _active.value = _active.value?.copy(podStarted = ok)
        }
    }

    fun stopSet() {
        val set = _active.value ?: return
        if (_phase.value != Phase.LIFTING) return
        _active.value = set.copy(endMs = System.currentTimeMillis())
        // Warm-up and calibration sets are the baseline, so they're scored on their own reps.
        _analysis.value = counter.analyze(if (set.warmup) null else fullRomDeg(set.exerciseId))
        _phase.value = Phase.CONFIRM
        val live = _liveReps.value
        // Send Stop whenever the pod is there (even if Start's reply was slow):
        // Stop with nothing recording is harmless.
        if (pod.conn.value == PodConn.READY) {
            viewModelScope.launch {
                if (pod.send(PodProtocol.stop(live))) {
                    // The pod reports the new recording's id once it has closed it.
                    val rec = withTimeoutOrNull(3_000) {
                        pod.status.first { it != null && !it.recording }
                    }?.lastRec
                    val cur = _active.value
                    if (cur != null && cur.podStarted) _active.value = cur.copy(podRec = rec)
                }
            }
        }
    }

    fun saveSet(reps: Int) {
        val set = _active.value ?: return
        val session = _session.value ?: return
        val log = SetLog(
            exerciseId = set.exerciseId, setNo = set.setNo, weight = set.weight,
            addons = set.addons, reps = reps, warmup = set.warmup,
            startMs = set.startMs, endMs = set.endMs ?: System.currentTimeMillis(),
            liveReps = if (set.podStarted) _liveReps.value else null, podRec = set.podRec,
            quality = _analysis.value?.overall,
            repScores = _analysis.value?.perRep?.map { it.score } ?: emptyList(),
            speedDrop = _analysis.value?.speedDropPct,
            romDeg = _analysis.value?.perRep?.mapNotNull { it.romDeg } ?: emptyList(),
            romPct = _analysis.value?.perRep?.map { it.romPct } ?: emptyList(),
            calibration = set.calibration,
        )
        val updated = session.copy(sets = session.sets.filterNot {
            it.exerciseId == log.exerciseId && it.setNo == log.setNo
        } + log)
        _session.value = updated
        saveSession(updated)
        set.podRec?.let { rec -> viewModelScope.launch { pod.send(PodProtocol.confirm(rec, reps)) } }
        _active.value = null
        _analysis.value = null
        _phase.value = Phase.READY
        startRest(Routines.restSeconds(Routines.byId(set.exerciseId)))
    }

    fun discardSet() {
        _active.value = null
        _analysis.value = null
        _phase.value = Phase.READY
    }

    fun deleteSet(log: SetLog) {
        val session = _session.value ?: return
        val updated = session.copy(sets = session.sets - log)
        _session.value = updated
        saveSession(updated)
    }

    fun finishWorkout() {
        val s = _session.value ?: return
        val done = s.copy(endMs = System.currentTimeMillis())
        if (done.sets.isEmpty()) store.delete(done.id) else store.save(done)
        _history.value = store.all()
        _session.value = null
        _extraRows.value = emptyMap()
        restJob?.cancel()
        _restEndsAt.value = null
        // The workout screen shows the "saved, share it?" dialog, then goes home.
    }

    // ---- rest timer ---------------------------------------------------------------

    private fun startRest(seconds: Int) {
        _restSeconds.value = seconds
        val end = System.currentTimeMillis() + seconds * 1000L
        _restEndsAt.value = end
        restJob?.cancel()
        restJob = viewModelScope.launch {
            delay(end - System.currentTimeMillis())
            buzz()
        }
    }

    fun addRest(seconds: Int) {
        val end = (_restEndsAt.value ?: return) + seconds * 1000L
        _restEndsAt.value = end
        restJob?.cancel()
        restJob = viewModelScope.launch {
            delay((end - System.currentTimeMillis()).coerceAtLeast(0))
            buzz()
        }
    }

    fun skipRest() {
        restJob?.cancel()
        _restEndsAt.value = null
    }

    fun setDefaultRest(seconds: Int) {
        val s = seconds.coerceIn(30, 600)
        _restSeconds.value = s
        prefs.edit().putInt("rest_s", s).apply()
    }

    private fun buzz() {
        val vm = getApplication<Application>().getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 600), -1))
    }

    private fun saveSession(s: Session) {
        store.save(s)
        _history.value = store.all()
    }

    override fun onCleared() {
        pod.disconnect()
    }
}
