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
import com.silverajat.lifttracker.data.RawStore
import com.silverajat.lifttracker.data.Routines
import com.silverajat.lifttracker.data.Session
import com.silverajat.lifttracker.data.SessionStore
import com.silverajat.lifttracker.data.SetLog
import com.silverajat.lifttracker.reps.RepCounter
import com.silverajat.lifttracker.reps.RepEngine
import com.silverajat.lifttracker.reps.RepModel
import com.silverajat.lifttracker.reps.RomRef
import com.silverajat.lifttracker.reps.SetAnalysis
import com.silverajat.lifttracker.reps.SetEnd
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Workout(val day: Day) : Screen
    data class ExerciseInfo(val exerciseId: Int, val fromHome: Boolean = false) : Screen
    /** A past workout, in full. */
    data class Past(val sessionId: Long) : Screen
}

enum class Phase { READY, COUNTDOWN, LIFTING, CONFIRM }

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
    /** Started by itself after rest (10 s countdown) rather than by tapping ▶. */
    val auto: Boolean = false,
)

/** The set that comes after the rest: same exercise until its sets are done, then the next one. */
data class NextSet(val ex: Exercise, val setNo: Int, val weight: Double, val addons: Int, val warmup: Boolean)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val pod = PodClient(app, viewModelScope)
    private val store = SessionStore(app)
    private val raw = RawStore(app)
    /** Full-rate recordings downloaded from pod v2 (dump_logs format). */
    val podRecs = com.silverajat.lifttracker.data.PodRecStore(app.filesDir)
    /** Recordings saved since the last export (the button only shows when there are new ones). */
    private val _podSaved = MutableStateFlow(runCatching {
        podRecs.count() - app.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("pod_exported", 0)
    }.getOrDefault(0).coerceAtLeast(0))
    val podSaved: StateFlow<Int> = _podSaved
    private val _podSync = MutableStateFlow<String?>(null)
    /** "Saving sets 3/12" while recordings come off the pod. */
    val podSync: StateFlow<String?> = _podSync
    private var syncJob: Job? = null
    private val modelFile = File(app.filesDir, "rep_model.json")
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _history = MutableStateFlow(store.also { st ->
        runCatching { st.removeNov13().forEach { id -> runCatching { raw.deleteSession(id) } } }
    }.all())
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

    /** Pause during a set (someone bumps you, you re-rack): those samples aren't counted. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused
    /** Pieces of the set between pauses; each is counted on its own, then added up. */
    private val segments = ArrayList<List<FloatArray>>()
    private val hold = com.silverajat.lifttracker.reps.PeakHold()
    /** Both sides in one recording (single-leg calf raise): show and save half the count. */
    private var halve = false
    private fun perSide(n: Int) = if (halve) (n + 1) / 2 else n
    private var segmentReps = 0
    private var pausedTotalMs = 0L
    private var pausedAt: Long? = null
    /** Time spent paused in the current set (the set timer leaves it out). */
    fun pausedMs(now: Long): Long = pausedTotalMs + (pausedAt?.let { now - it } ?: 0L)

    /** After rest: 10 s countdown, then the next set starts by itself. */
    private val _autoNext = MutableStateFlow(prefs.getBoolean("auto_next", true))
    val autoNext: StateFlow<Boolean> = _autoNext
    fun setAutoNext(on: Boolean) { _autoNext.value = on; prefs.edit().putBoolean("auto_next", on).apply() }
    private val _nextUp = MutableStateFlow<NextSet?>(null)
    val nextUp: StateFlow<NextSet?> = _nextUp
    private var restJob: Job? = null
    private var countJob: Job? = null
    private var collecting = false

    // ---- auto-end: the set stops itself when the motion changes (racking, hands off the bar) ----
    private val _autoEnd = MutableStateFlow(prefs.getBoolean("auto_end", true))
    val autoEnd: StateFlow<Boolean> = _autoEnd
    fun setAutoEnd(on: Boolean) { _autoEnd.value = on; prefs.edit().putBoolean("auto_end", on).apply() }
    private val _autoEnding = MutableStateFlow<Long?>(null)
    /** When the set will stop by itself (a couple of seconds to tap Keep going), or null. */
    val autoEnding: StateFlow<Long?> = _autoEnding
    private var autoEndJob: Job? = null
    private var autoEndAfterReps = 0      // Keep going: not again until a new rep
    private var endCheckAt = 0
    /** Lifts where replaying your saved sets would have stopped one early: auto-end stays off for them. */
    @Volatile private var autoEndOff: Set<Int> = emptySet()
    private val endReplayFile = File(app.filesDir, "autoend_replay.json")
    /** The finished set's 25 Hz data, kept from Stop until Save. */
    private var setData: List<FloatArray> = emptyList()

    /** The rep counter's learned settings (refitted after every saved set). */
    private val _model = MutableStateFlow(
        RepModel.Model.fromJson(runCatching { modelFile.readText() }.getOrNull())
    )
    val model: StateFlow<RepModel.Model> = _model
    private val _training = MutableStateFlow(false)
    val training: StateFlow<Boolean> = _training
    private var trainJob: Job? = null

    init {
        // Pick up a workout that was still open (app killed, phone restarted).
        _session.value = _history.value.firstOrNull {
            it.endMs == null && System.currentTimeMillis() - it.startMs < 4 * 3600_000L
        }
        viewModelScope.launch {
            pod.samples.collect { s ->
                if (collecting && counter.add(s) && countJob?.isActive != true) {
                    countJob = viewModelScope.launch {
                        val snap = counter.snapshot()
                        val n = withContext(Dispatchers.Default) { RepCounter.count(snap, PodProtocol.STREAM_HZ, counter.params, counter.oneArm, counter.firstSide) }
                        if (collecting) _liveReps.value = segmentReps + perSide(hold.update(n, snap.size))
                        if (collecting && snap.size >= endCheckAt + 10 && autoEndFor()) {
                            endCheckAt = snap.size
                            val end = withContext(Dispatchers.Default) { SetEnd.detect(snap, PodProtocol.STREAM_HZ, counter.params) }
                            if (end != null && collecting && _autoEnding.value == null) beginAutoEnd(end)
                        }
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) { runCatching { autoEndOff = checkAutoEnd(_model.value) } }
        viewModelScope.launch {
            // pod v2: tapping it twice ends the set (a reconnect only sets the starting count)
            var last: Int? = null
            var wasReady = false
            pod.status.collect { st ->
                val ready = pod.conn.value == PodConn.READY
                if (!ready || !wasReady) last = null
                wasReady = ready
                val t = st?.taps ?: return@collect
                val prev = last
                last = t
                if (prev != null && t != prev && _phase.value == Phase.LIFTING) stopSet()
            }
        }
        viewModelScope.launch {
            // Whenever the pod (re)connects, make sure it isn't auto-recording.
            pod.conn.collect {
                if (it != PodConn.READY) return@collect
                pod.send(PodProtocol.auto(false))
                val st = withTimeoutOrNull(3_000) { pod.status.first { s -> s != null } }
                if (st?.v2 == true) {
                    // pod v2: back in gym mode if a workout is open, otherwise collect what it recorded
                    if (_session.value != null) pod.send(PodProtocol.gym(true)) else syncPod()
                }
            }
        }
    }

    fun exportJson(s: Session): String = store.exportJson(s)
    fun shareText(s: Session): String = com.silverajat.lifttracker.data.ShareText.build(s)

    // ---- your notes per exercise ------------------------------------------------
    private val noteStore = com.silverajat.lifttracker.data.ExerciseNotes(app.filesDir)
    private val _notes = MutableStateFlow(noteStore.all())
    val notes: StateFlow<Map<Int, com.silverajat.lifttracker.data.ExerciseNote>> = _notes
    fun setNote(exerciseId: Int, text: String) { noteStore.set(exerciseId, text); _notes.value = noteStore.all() }

    // ---- which day is upper / lower / rest -------------------------------------------
    private val planStore = com.silverajat.lifttracker.data.GymPlan(app.filesDir)
    private val _plan = MutableStateFlow(planStore.week())
    val plan: StateFlow<Map<java.time.DayOfWeek, Day?>> = _plan
    fun setPlan(week: Map<java.time.DayOfWeek, Day?>) { planStore.set(week); _plan.value = planStore.week() }

    // ---- navigation -----------------------------------------------------------

    fun open(s: Screen) { _screen.value = s }

    fun back(): Boolean {
        when (val s = _screen.value) {
            is Screen.ExerciseInfo -> _screen.value = if (s.fromHome) Screen.Home else Screen.Workout(Routines.byId(s.exerciseId).day)
            is Screen.Workout -> _screen.value = Screen.Home
            is Screen.Past -> _screen.value = Screen.Home
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

    /** Weights you've typed into sets you haven't done yet, so the auto-start uses them. Key: exercise id to set no. */
    private val typed = HashMap<Pair<Int, Int>, Pair<Double, Int>>()

    fun typeWeight(exerciseId: Int, setNo: Int, weight: Double?, addons: Int) {
        if (weight == null) typed.remove(exerciseId to setNo) else typed[exerciseId to setNo] = weight to addons
        val n = _nextUp.value
        if (weight != null && n != null && n.ex.id == exerciseId && n.setNo == setNo) _nextUp.value = n.copy(weight = weight, addons = addons)
    }

    fun typedWeight(exerciseId: Int, setNo: Int): Pair<Double, Int>? = typed[exerciseId to setNo]

    fun addRow(exerciseId: Int) {
        _extraRows.value = _extraRows.value + (exerciseId to ((_extraRows.value[exerciseId] ?: 0) + 1))
    }

    /** Take away a set you haven't done (rows below the done ones only; done sets are removed with deleteSet). */
    fun removeRow(exerciseId: Int, rowsNow: Int, lastDone: Int) {
        if (rowsNow <= lastDone || rowsNow <= 0) return
        _extraRows.value = _extraRows.value + (exerciseId to ((_extraRows.value[exerciseId] ?: 0) - 1))
        // if the rest timer was pointing at the row that's gone, point it at the next real one
        if (_nextUp.value?.ex?.id == exerciseId && (_nextUp.value?.setNo ?: 0) >= rowsNow) _nextUp.value = nextSet(exerciseId)
    }

    /** Fix a set after the fact: the weight, add-ons or reps you actually did. */
    fun editSet(log: SetLog, weight: Double, addons: Int, reps: Int) {
        val session = _session.value ?: return
        val fixed = log.copy(weight = weight, addons = addons, reps = reps)
        val updated = session.copy(sets = session.sets.map { if (it == log) fixed else it })
        _session.value = updated
        saveSession(updated)
        if (reps != log.reps) retrain()
        // the next set copies this one's weight: keep the rest panel in step
        if (_nextUp.value?.ex?.id == log.exerciseId) _nextUp.value = nextSet(log.exerciseId)
    }

    /**
     * What 100% ROM is for an exercise (in degrees or cm, matching the set):
     * the median of your last 6 sets' ROM, so it recalibrates after every set.
     * No sets yet: null, and the set is scored against its own best reps.
     * Only sets measured by the current counter count.
     */
    private val ROM_SETS = 6

    fun romRef(exerciseId: Int, unit: String): RomRef? {
        // Recalibrates after every set: your usual full range is the median of your
        // last 6 sets' ROM (each set's top 3 reps), so one odd set can't move it much
        // and it follows you as your range changes.
        val recent = allSets(exerciseId).filter { it.counter >= RepEngineVersion && it.romUnit == unit && it.rom.size >= 2 }
            .sortedByDescending { it.startMs }.take(ROM_SETS)
        if (recent.isEmpty()) return null
        val v = recent.map { top3(it.rom) }.sorted().let { r -> if (r.size % 2 == 1) r[r.size / 2] else (r[r.size / 2 - 1] + r[r.size / 2]) / 2 }
        return RomRef(v, unit, RomRef.Source.RECENT)
    }

    /** Every logged set of an exercise, this workout included. */
    private fun allSets(exerciseId: Int): List<SetLog> {
        val cur = _session.value
        val past = _history.value.filter { it.id != cur?.id }.flatMap { it.sets }
        return (past + (cur?.sets ?: emptyList())).filter { it.exerciseId == exerciseId }
    }

    /** The latest calibration (C set) of an exercise: its full ROM, unit and date. */
    data class Calibration(val rom: Float, val unit: String, val atMs: Long)

    fun calibration(exerciseId: Int): Calibration? =
        allSets(exerciseId).filter { it.calibration && it.counter >= RepEngineVersion && it.rom.size >= 2 }
            .maxByOrNull { it.startMs }?.let { Calibration(top3(it.rom), it.romUnit, it.startMs) }

    private fun top3(x: List<Float>): Float = x.sortedDescending().take(3).let { t -> t.sorted()[t.size / 2] }

    /** Every finished session, oldest first (for charts and the history tab). */
    fun sessionsWith(exerciseId: Int): List<Session> =
        _history.value.filter { s -> s.sets.any { it.exerciseId == exerciseId } }.sortedBy { it.startMs }

    // ---- the set flow ------------------------------------------------------------

    // ---- 10 s countdown before a set (time to get into position) ----------
    private val _countdownEndsAt = MutableStateFlow<Long?>(null)
    val countdownEndsAt: StateFlow<Long?> = _countdownEndsAt
    private val _countdownSeconds = MutableStateFlow(3)
    val countdownSeconds: StateFlow<Int> = _countdownSeconds
    private var countdownJob: Job? = null
    private var pendingStart: (() -> Unit)? = null

    /** ▶ pressed: count down 10 s, then start the set (and the pod recording). */
    fun requestSet(
        ex: Exercise, setNo: Int, weight: Double, addons: Int, warmup: Boolean, calibration: Boolean = false,
        countdownMs: Long = COUNTDOWN_MS, auto: Boolean = false,
    ) {
        if (_phase.value != Phase.READY) return
        restJob?.cancel()
        _restEndsAt.value = null
        syncJob?.cancel()
        podV2 { pod.send(PodProtocol.gym(true)) }       // wake the full-rate sensor during the countdown
        // weight and add-ons are read when it starts: they can be changed during the countdown
        pendingStart = {
            val a = _active.value
            startSet(ex, setNo, a?.weight ?: weight, a?.addons ?: addons, warmup, calibration)
        }
        _active.value = ActiveSet(ex.id, setNo, weight, addons, warmup || calibration,
            startMs = System.currentTimeMillis(), calibration = calibration, auto = auto)
        _phase.value = Phase.COUNTDOWN
        val secs = (countdownMs / 1000).toInt()
        _countdownSeconds.value = secs
        val end = System.currentTimeMillis() + countdownMs
        _countdownEndsAt.value = end
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (left in secs downTo 1) {
                if (left <= 3 || left == secs) tick(40)
                delay((end - System.currentTimeMillis()) - (left - 1) * 1000L)
            }
            goNow()
        }
    }

    /** Change the weight of the set that's counting down (auto-start carries the last set's weight). */
    fun adjustPending(deltaKg: Double) {
        if (_phase.value != Phase.COUNTDOWN) return
        val a = _active.value ?: return
        _active.value = a.copy(weight = (a.weight + deltaKg).coerceAtLeast(0.0))
    }

    /** What comes after [lastExId]'s set: the same exercise until its rows are done, then the next unfinished one. */
    fun nextSet(lastExId: Int): NextSet? {
        val s = _session.value ?: return null
        val exs = Routines.forDay(s.day)
        val i = exs.indexOfFirst { it.id == lastExId }.coerceAtLeast(0)
        for (ex in exs.drop(i) + exs.take(i)) {
            val plan = lastTime(ex)
            val done = s.sets.filter { it.exerciseId == ex.id }
            val rows = maxOf(plan.size, done.maxOfOrNull { it.setNo } ?: 0) + (_extraRows.value[ex.id] ?: 0)
            val doneNos = done.map { it.setNo }.toSet()
            val n = (1..rows).firstOrNull { it !in doneNos } ?: continue
            val p = plan.getOrNull(n - 1) ?: plan.lastOrNull()
            val warm = p?.warmup ?: false
            // same weight as the working set you just did ("same sets throughout")
            val prev = done.filter { !it.warmup && it.setNo < n }.maxByOrNull { it.setNo }
            val (w, add) = typed[ex.id to n]
                ?: if (!warm && prev != null) prev.weight to prev.addons else (p?.weight ?: 0.0) to (p?.addons ?: 0)
            return NextSet(ex, n, w, add, warm)
        }
        return null
    }

    private fun autoStartNext() {
        val n = _nextUp.value ?: return
        if (!_autoNext.value || _phase.value != Phase.READY) return
        // only with the workout on screen, so a set never records out of sight
        val scr = _screen.value
        if (scr !is Screen.Workout || scr.day != n.ex.day) return
        requestSet(n.ex, n.setNo, n.weight, n.addons, n.warmup, countdownMs = AUTO_COUNTDOWN_MS, auto = true)
    }

    /** Skip the rest of the countdown. */
    fun goNow() {
        if (_phase.value != Phase.COUNTDOWN) return
        countdownJob?.cancel()
        _countdownEndsAt.value = null
        _phase.value = Phase.READY
        tick(250)
        podV2 { pod.send(PodProtocol.buzz(2)) }
        pendingStart?.invoke()
        pendingStart = null
    }

    fun cancelCountdown() {
        if (_phase.value != Phase.COUNTDOWN) return
        countdownJob?.cancel()
        _nextUp.value = null
        _countdownEndsAt.value = null
        pendingStart = null
        _active.value = null
        _phase.value = Phase.READY
    }

    private fun tick(ms: Long) {
        val vm = getApplication<Application>().getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

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
        counter.reset(_model.value.params(ex.id, set.warmup && !calibration), Routines.isOneArm(ex) && !Routines.podSeesBothSides(ex.id), Routines.podSideFirst(ex.id))
        halve = Routines.podSeesBothSides(ex.id)
        segments.clear()
        segmentReps = 0
        hold.reset()
        autoEndJob?.cancel(); _autoEnding.value = null; autoEndAfterReps = 0; endCheckAt = 0
        pausedTotalMs = 0
        pausedAt = null
        _paused.value = false
        _nextUp.value = null
        _liveReps.value = 0
        collecting = true
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

    private fun autoEndFor(): Boolean {
        val a = _active.value ?: return false
        return _autoEnd.value && !_paused.value && _autoEnding.value == null && _liveReps.value >= 3 &&
            _liveReps.value > autoEndAfterReps && !counter.oneArm && !halve &&
            Routines.podCounts(a.exerciseId) && a.exerciseId !in autoEndOff
    }

    private fun beginAutoEnd(end: SetEnd.End) {
        _autoEnding.value = System.currentTimeMillis() + AUTO_END_GRACE_MS
        tick(60)
        autoEndJob?.cancel()
        autoEndJob = viewModelScope.launch {
            delay(AUTO_END_GRACE_MS)
            if (_autoEnding.value != null && _phase.value == Phase.LIFTING) stopSet(cutAt = end.cut)
            _autoEnding.value = null
        }
    }

    /** The set isn't over: carry on, and don't stop it again until another rep. */
    fun keepGoing() {
        autoEndJob?.cancel()
        _autoEnding.value = null
        autoEndAfterReps = _liveReps.value
    }

    /** Pause the set: stop counting until resume (the set timer stops too). */
    fun pauseSet() {
        if (_phase.value != Phase.LIFTING || _paused.value) return
        collecting = false
        countJob?.cancel()
        _paused.value = true
        pausedAt = System.currentTimeMillis()
        val raw = counter.snapshot()
        val p = counter.params
        val oneArm = counter.oneArm
        val firstSide = counter.firstSide
        val idx = segments.size
        segments += raw
        viewModelScope.launch {
            val full = if (raw.size < PodProtocol.STREAM_HZ) 0
                else withContext(Dispatchers.Default) { RepCounter.count(raw, PodProtocol.STREAM_HZ, p, oneArm, firstSide) }
            if (hold.collapsed(full) && idx < segments.size) segments[idx] = hold.cut(raw, full)
            val n = perSide(hold.shown(full))
            hold.reset()
            segmentReps += n
            _liveReps.value = segmentReps
        }
    }

    fun resumeSet() {
        if (_phase.value != Phase.LIFTING || !_paused.value) return
        pausedAt?.let { pausedTotalMs += System.currentTimeMillis() - it }
        pausedAt = null
        counter.reset(counter.params, counter.oneArm, counter.firstSide)
        _paused.value = false
        collecting = true
    }

    /** [cutAt]: the set stopped itself, so its data ends there (where the racking began). */
    fun stopSet(cutAt: Int? = null) {
        val set = _active.value ?: return
        if (_phase.value != Phase.LIFTING) return
        collecting = false
        countJob?.cancel()
        autoEndJob?.takeIf { cutAt == null }?.cancel()
        _autoEnding.value = null
        // the hand going to the phone to press stop isn't a rep: cut that off the end
        val last = if (!_paused.value) counter.snapshot().let {
            if (cutAt != null && cutAt in PodProtocol.STREAM_HZ..it.size) it.subList(0, cutAt)
            else RepCounter.cutReach(it, PodProtocol.STREAM_HZ)
        } else null
        val lastHold = com.silverajat.lifttracker.reps.PeakHold().also { if (last != null) { it.update(hold.peak, hold.peakLen) } }
        pausedAt?.let { pausedTotalMs += System.currentTimeMillis() - it }
        pausedAt = null
        _paused.value = false
        _active.value = set.copy(endMs = System.currentTimeMillis())
        val p = counter.params
        val oneArm = counter.oneArm
        val firstSide = counter.firstSide
        viewModelScope.launch {
            // the end of the set (re-racking, picking up the phone) can wipe the count: cut it back
            if (last != null) {
                val full = if (last.size < PodProtocol.STREAM_HZ) 0
                    else withContext(Dispatchers.Default) { RepCounter.count(last, PodProtocol.STREAM_HZ, p, oneArm, firstSide) }
                segments += lastHold.cut(last, full)
            }
            hold.reset()
            val segs = segments.toList()
            val data = segs.flatten()
            // A set with a pause has a jump where the pause was: don't train on it.
            setData = if (segs.size == 1) segs[0] else emptyList()
            // Final count and quality, each piece on its own. Warm-up and
            // calibration sets are the baseline, so they're scored on their own reps.
            val ref: (String) -> RomRef? = if (set.warmup) { _ -> null } else { u -> romRef(set.exerciseId, u) }
            val (n, a) = withContext(Dispatchers.Default) {
                val hz = PodProtocol.STREAM_HZ
                val parts = segs.filter { it.size >= hz }.map { seg ->
                    val a = RepCounter.analyze(seg, hz, p, oneArm, firstSide, ref)
                    Triple(seg.size, a?.reps ?: RepCounter.count(seg, hz, p, oneArm, firstSide), a)
                }
                // both sides in one recording: reps per side, and no quality (it's the body bobbing)
                if (halve) Pair(parts.sumOf { perSide(it.second) }, null)
                else Pair(parts.sumOf { it.second }, mergeAnalyses(parts.mapNotNull { it.third }))
            }
            if (!Routines.podCounts(set.exerciseId)) {
                // wrist stays still on this one: no count or quality from the pod, start from the plan
                _liveReps.value = Routines.byId(set.exerciseId).last.getOrNull(set.setNo - 1)?.reps ?: 0
                _analysis.value = null
            } else {
                if (data.isNotEmpty()) _liveReps.value = n
                _analysis.value = a
            }
            _phase.value = Phase.CONFIRM
            sendStop(set)
        }
    }

    /** One analysis from the pieces of a paused set (reps in order; the longest piece's reference). */
    private fun mergeAnalyses(list: List<SetAnalysis>): SetAnalysis? {
        if (list.size <= 1) return list.firstOrNull()
        val main = list.maxBy { it.perRep.size }
        var k = 0
        val reps = list.flatMap { a -> a.perRep.map { it.copy(rep = ++k) } }
        return main.copy(
            reps = list.sumOf { it.reps }, perRep = reps,
            avgTempoS = list.map { it.avgTempoS }.average().toFloat(),
        )
    }

    private fun sendStop(set: ActiveSet) {
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
            liveReps = if (Routines.podCounts(set.exerciseId) && (set.podStarted || setData.isNotEmpty())) _liveReps.value else null, podRec = set.podRec,
            quality = _analysis.value?.overall,
            repScores = _analysis.value?.perRep?.map { it.score } ?: emptyList(),
            speedDrop = _analysis.value?.speedDropPct,
            rom = _analysis.value?.perRep?.map { it.rom } ?: emptyList(),
            romPct = _analysis.value?.perRep?.map { it.romPct } ?: emptyList(),
            calibration = set.calibration,
            romUnit = _analysis.value?.unit ?: "deg",
            counter = RepEngineVersion,
        )
        // Keep the set's data: with the count you confirmed, it's what the counter learns from.
        if (setData.size >= PodProtocol.STREAM_HZ && Routines.podCounts(set.exerciseId)) {
            val d = setData
            viewModelScope.launch(Dispatchers.IO) { raw.save(session.id, log.exerciseId, log.setNo, d) }
        }
        setData = emptyList()
        val updated = session.copy(sets = session.sets.filterNot {
            it.exerciseId == log.exerciseId && it.setNo == log.setNo
        } + log)
        _session.value = updated
        saveSession(updated)
        set.podRec?.let { rec -> viewModelScope.launch { pod.send(PodProtocol.confirm(rec, reps)) } }
        _active.value = null
        _analysis.value = null
        _phase.value = Phase.READY
        _nextUp.value = nextSet(set.exerciseId)
        startRest(if (log.warmup) Routines.WARMUP_REST_S else Routines.restSeconds(Routines.byId(set.exerciseId)))
        retrain()
    }

    fun discardSet() {
        setData = emptyList()
        segments.clear()
        _active.value = null
        _analysis.value = null
        _phase.value = Phase.READY
    }

    /** Delete a saved workout for good (its pod recordings stay on the pod). */
    fun deleteWorkout(id: Long) {
        if (_session.value?.id == id) {
            if (_phase.value != Phase.READY) return
            _session.value = null
            _extraRows.value = emptyMap(); typed.clear()
            restJob?.cancel()
            _restEndsAt.value = null
        }
        store.delete(id)
        raw.deleteSession(id)
        _history.value = store.all()
        if (_session.value == null) podV2 { pod.send(PodProtocol.gym(false)) }
        retrain()
    }

    fun deleteSet(log: SetLog) {
        val session = _session.value ?: return
        val updated = session.copy(sets = session.sets - log)
        _session.value = updated
        saveSession(updated)
        raw.delete(session.id, log.exerciseId, log.setNo)
        retrain()
    }

    // ---- learning from your corrections ---------------------------------------------

    /**
     * Refit the rep counter on every saved set that has pod data, using the
     * counts you confirmed as the truth (RepModel). Runs in the background
     * after each saved set; a newer call replaces one still running.
     */
    fun retrain() {
        trainJob?.cancel()
        trainJob = viewModelScope.launch {
            _training.value = true
            val m = withContext(Dispatchers.Default) {
                val hz = PodProtocol.STREAM_HZ
                val examples = store.all().flatMap { s ->
                    s.sets.filter { it.liveReps != null }.mapNotNull { l ->
                        val d = raw.load(s.id, l.exerciseId, l.setNo) ?: return@mapNotNull null
                        val cut = RepCounter.side(d, hz, Routines.isOneArm(l.exerciseId), Routines.podSideFirst(l.exerciseId))
                        if (cut.size < hz) null
                        else RepModel.Example(l.exerciseId, RepEngine.signals(cut, hz), l.reps, l.warmup && !l.calibration)
                    }
                }
                RepModel.train(examples)
            }
            _model.value = m
            runCatching { modelFile.writeText(m.toJson()) }
            autoEndOff = withContext(Dispatchers.Default) { checkAutoEnd(m) }
            _training.value = false
        }
    }

    /**
     * Replay every saved set through auto-end as it streamed in. Any lift where it would have stopped a set
     * before its last rep keeps auto-end off. Each set is replayed once (results kept in autoend_replay.json).
     */
    private fun checkAutoEnd(m: RepModel.Model): Set<Int> {
        val hz = PodProtocol.STREAM_HZ
        val seen = runCatching { org.json.JSONObject(endReplayFile.readText()) }.getOrElse { org.json.JSONObject() }
        val off = HashSet<Int>()
        for (s in store.all()) for (l in s.sets) {
            if (l.liveReps == null || Routines.isOneArm(l.exerciseId) || !Routines.podCounts(l.exerciseId)) continue
            val key = "${s.id}_${l.exerciseId}_${l.setNo}"
            val early = if (seen.has(key)) seen.getBoolean(key) else {
                val d = raw.load(s.id, l.exerciseId, l.setNo)
                val e = if (d == null || d.size < 4 * hz) false else {
                    val p = m.params(l.exerciseId, l.warmup && !l.calibration)
                    val end = SetEnd.replay(d, hz, p, step = 10)
                    end != null && (RepEngine.count(d.subList(0, end.cut), hz, p)?.reps ?: 0) < (RepEngine.count(d, hz, p)?.reps ?: 0)
                }
                seen.put(key, e)
                e
            }
            if (early) off += l.exerciseId
        }
        runCatching { endReplayFile.writeText(seen.toString()) }
        return off
    }

    fun finishWorkout() {
        val s = _session.value ?: return
        val done = s.copy(endMs = System.currentTimeMillis())
        if (done.sets.isEmpty()) store.delete(done.id) else store.save(done)
        _history.value = store.all()
        if (done.sets.isNotEmpty()) {
            val app = getApplication<Application>()
            val hist = _history.value
            viewModelScope.launch(Dispatchers.Default) {
                runCatching {
                    com.silverajat.lifttracker.insights.InsightEngine.post(app,
                        com.silverajat.lifttracker.insights.Insights.workout(done, hist, java.time.ZoneId.systemDefault(),
                            com.silverajat.lifttracker.insights.InsightStore(app.filesDir).all().take(15).map { it.title + " " + it.body }))
                }
            }
        }
        _session.value = null
        _extraRows.value = emptyMap(); typed.clear()
        restJob?.cancel()
        _restEndsAt.value = null
        _nextUp.value = null
        // pod v2: back to sleep, then copy the workout's full-rate recordings to the phone
        podV2 {
            pod.send(PodProtocol.gym(false))
            syncPod()
        }
        // The workout screen shows the "saved, share it?" dialog, then goes home.
    }

    // ---- pod v2 ---------------------------------------------------------------------

    private var releaseJob: Job? = null

    /**
     * The pod stays connected only while you're in the gym part of the app or a workout is open: a phone holding the
     * link all day costs the pod far more battery than anything else it does. Leaving lets any sync finish first.
     */
    fun wantPod(on: Boolean) {
        releaseJob?.cancel()
        if (on) return
        releaseJob = viewModelScope.launch {
            delay(3_000)              // a sync that's about to start (after Finish) gets going first
            syncJob?.join()
            if (_session.value == null) pod.disconnect()
        }
    }

    private fun podV2(block: suspend () -> Unit) {
        if (pod.conn.value == PodConn.READY && pod.status.value?.v2 == true) viewModelScope.launch { block() }
    }

    /**
     * Copy every recording the phone doesn't have yet off pod v2, then erase the pod once it's all here.
     * Only between workouts; starting a set stops it (the recording being sent finishes on the pod first).
     */
    fun syncPod() {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            try {
                val st = pod.status.value ?: return@launch
                if (!st.v2 || st.recordings == 0 || _session.value != null) return@launch
                var all = true
                for (id in 0 until st.recordings) {
                    if (_session.value != null) { all = false; break }
                    _podSync.value = "Saving sets ${id + 1}/${st.recordings}"
                    val head = pod.readRecording(id, headerOnly = true)
                    if (head == null) { all = false; break }
                    if (podRecs.has(head.first.key)) continue
                    val full = pod.readRecording(id)
                    if (full == null) { all = false; break }
                    withContext(Dispatchers.IO) { podRecs.save(full.first, full.second) }
                    _podSaved.value = (podRecs.count() - prefs.getInt("pod_exported", 0)).coerceAtLeast(0)
                }
                if (all && _session.value == null) pod.send(PodProtocol.erase())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("AppViewModel", "pod sync", e)
            } finally {
                _podSync.value = null
            }
        }
    }

    /** Save every pod recording as a zip in Downloads; the file name, or null if it failed. */
    suspend fun exportPodRecordings(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val app = getApplication<Application>()
            val name = "lift-pod-recordings-${java.time.LocalDate.now()}.zip"
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/zip")
            }
            val uri = app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            app.contentResolver.openOutputStream(uri)!!.use { podRecs.zip(it) }
            prefs.edit().putInt("pod_exported", podRecs.count()).apply()
            _podSaved.value = 0
            name
        }.getOrNull()
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
            autoStartNext()
        }
    }

    fun addRest(seconds: Int) {
        val end = (_restEndsAt.value ?: return) + seconds * 1000L
        _restEndsAt.value = end
        restJob?.cancel()
        restJob = viewModelScope.launch {
            delay((end - System.currentTimeMillis()).coerceAtLeast(0))
            buzz()
            autoStartNext()
        }
    }

    /** Skip: straight to the next set's countdown (or just close the timer with auto-start off). */
    fun skipRest() {
        restJob?.cancel()
        _restEndsAt.value = null
        autoStartNext()
    }

    /** Close the rest panel without starting anything. */
    fun closeRest() {
        restJob?.cancel()
        _restEndsAt.value = null
        _nextUp.value = null
    }

    fun setDefaultRest(seconds: Int) {
        val s = seconds.coerceIn(30, 600)
        _restSeconds.value = s
        prefs.edit().putInt("rest_s", s).apply()
    }

    companion object {
        const val COUNTDOWN_MS = 10_000L   // same as after rest: time to get into position
        const val AUTO_COUNTDOWN_MS = 10_000L
        const val AUTO_END_GRACE_MS = 2_000L
        /** Bump when the counter's method changes (older ROM numbers then aren't compared). */
        const val RepEngineVersion = 1
    }

    private fun buzz() {
        podV2 { pod.send(PodProtocol.buzz(1)) }
        val vm = getApplication<Application>().getSystemService(VibratorManager::class.java) ?: return
        vm.defaultVibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 350, 150, 350, 150, 600), -1))
    }

    /**
     * Fix a past workout: each pair is (set as saved, corrected set) or (set, null) to remove it.
     * Next-time weights and the PREVIOUS column come from history, so they follow the fix.
     */
    fun fixPast(sessionId: Long, changes: List<Pair<SetLog, SetLog?>>) {
        if (changes.isEmpty()) return
        val s = _history.value.firstOrNull { it.id == sessionId } ?: return
        var sets = s.sets
        changes.forEach { (old, new) -> sets = if (new == null) sets.filter { it != old } else sets.map { if (it == old) new else it } }
        val fixed = s.copy(sets = sets)
        if (fixed.sets.isEmpty()) store.delete(fixed.id) else store.save(fixed)
        _history.value = store.all()
        if (_session.value?.id == sessionId) _session.value = fixed
        if (changes.any { (o, n) -> n == null || n.reps != o.reps }) retrain()
    }

    private fun saveSession(s: Session) {
        store.save(s)
        _history.value = store.all()
    }

    override fun onCleared() {
        pod.disconnect()
    }
}
