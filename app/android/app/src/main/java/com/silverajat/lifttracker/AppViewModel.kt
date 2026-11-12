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
    data class DayList(val day: Day) : Screen
    data class Lift(val exerciseId: Int) : Screen
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

    private val _restSeconds = MutableStateFlow(prefs.getInt("rest_s", 120))
    val restSeconds: StateFlow<Int> = _restSeconds

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

    // ---- navigation -----------------------------------------------------------

    fun open(s: Screen) { _screen.value = s }

    fun back(): Boolean {
        when (val s = _screen.value) {
            is Screen.Lift -> {
                if (_phase.value != Phase.READY) return true   // finish the set first
                _screen.value = Screen.DayList(Routines.byId(s.exerciseId).day)
            }
            is Screen.DayList -> _screen.value = Screen.Home
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
            ?.map { PlanSet(it.weight, it.reps, it.warmup, it.addons) }
            ?: ex.last
    }

    fun lastTimeDate(ex: Exercise): Long? {
        val current = _session.value?.id
        return _history.value.firstOrNull { s ->
            s.id != current && s.sets.any { it.exerciseId == ex.id }
        }?.startMs
    }

    /** Prefill for the next set: the same set number from last time. */
    fun suggestion(ex: Exercise): PlanSet {
        val plan = lastTime(ex)
        val k = setsToday(ex.id).size
        return plan.getOrElse(k) { plan.last().copy(warmup = false) }
    }

    // ---- the set flow ------------------------------------------------------------

    fun startSet(ex: Exercise, weight: Double, addons: Int, warmup: Boolean) {
        if (_phase.value != Phase.READY) return
        val now = System.currentTimeMillis()
        val session = _session.value?.takeIf { it.day == ex.day }
            ?: Session(id = now / 1000, day = ex.day, startMs = now, endMs = null, sets = emptyList())
                .also { _session.value = it; saveSession(it) }
        val set = ActiveSet(
            exerciseId = ex.id,
            setNo = setsToday(ex.id).size + 1,
            weight = weight, addons = addons, warmup = warmup, startMs = now,
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
                    exercise = ex.id, day = ex.day.id, setNo = set.setNo, warmup = warmup,
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
        )
        val updated = session.copy(sets = session.sets + log)
        _session.value = updated
        saveSession(updated)
        set.podRec?.let { rec -> viewModelScope.launch { pod.send(PodProtocol.confirm(rec, reps)) } }
        _active.value = null
        _phase.value = Phase.READY
        startRest()
    }

    fun discardSet() {
        _active.value = null
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
        restJob?.cancel()
        _restEndsAt.value = null
        _screen.value = Screen.Home
    }

    // ---- rest timer ---------------------------------------------------------------

    private fun startRest() {
        val end = System.currentTimeMillis() + _restSeconds.value * 1000L
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
