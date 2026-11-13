package com.silverajat.lifttracker

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.silverajat.lifttracker.chat.ChatContext
import com.silverajat.lifttracker.chat.ChatMessage
import com.silverajat.lifttracker.chat.ChatStore
import com.silverajat.lifttracker.chat.Gpt
import com.silverajat.lifttracker.data.SessionStore
import com.silverajat.lifttracker.health.HealthDay
import com.silverajat.lifttracker.health.HealthRepo
import com.silverajat.lifttracker.health.Analysis
import com.silverajat.lifttracker.health.Scores
import com.silverajat.lifttracker.insights.Insight
import com.silverajat.lifttracker.insights.InsightEngine
import com.silverajat.lifttracker.insights.InsightStore
import com.silverajat.lifttracker.meals.Food
import com.silverajat.lifttracker.meals.Macros
import com.silverajat.lifttracker.meals.MealEntry
import com.silverajat.lifttracker.meals.MealStore
import com.silverajat.lifttracker.meals.Slot
import com.silverajat.lifttracker.meals.WeightEntry
import com.silverajat.lifttracker.reps.RepModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class HealthState { UNAVAILABLE, NEEDS_PERMISSION, LOADING, OK }

/** Fitbit sync: everything the first time, then the last few days every 30 minutes (background and on open). */
const val FIRST_DAYS = 90
const val SYNC_EVERY_MS = 30 * 60_000L

/** Today, Meals, Insights and the brainstorm chat: Fitbit data, the food log, weights and GPT. */
class HubViewModel(app: Application) : AndroidViewModel(app) {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    val health = HealthRepo(app)
    private val meals = MealStore(app.filesDir)
    private val chatStore = ChatStore(app.filesDir)
    private val sessions = SessionStore(app)
    private val prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE)

    // ---- settings ---------------------------------------------------------------
    private val _apiKey = MutableStateFlow(prefs.getString("openai_key", "") ?: "")
    val apiKey: StateFlow<String> = _apiKey
    private val _model = MutableStateFlow(prefs.getString("openai_model", Gpt.DEFAULT_MODEL) ?: Gpt.DEFAULT_MODEL)
    val model: StateFlow<String> = _model
    private val _hrMax = MutableStateFlow(prefs.getInt("hr_max", Scores.DEFAULT_HR_MAX))
    val hrMax: StateFlow<Int> = _hrMax

    fun setApiKey(k: String) { _apiKey.value = k.trim(); prefs.edit().putString("openai_key", k.trim()).apply() }
    fun setModel(m: String) { _model.value = m; prefs.edit().putString("openai_model", m).apply() }

    /** Models your key can use (filled when you open brainstorm settings). */
    private val _models = MutableStateFlow<List<String>>(emptyList())
    val models: StateFlow<List<String>> = _models
    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError

    fun loadModels(key: String = _apiKey.value) {
        if (key.isBlank()) return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Gpt.models(key) } }
            r.onSuccess { _models.value = it; _modelsError.value = null }.onFailure { _modelsError.value = it.message }
        }
    }
    /** Weekly weight goal (kg/week): sets the net-calorie goal. */
    private val _goal = MutableStateFlow(prefs.getFloat("goal_kg_week", 0.25f).toDouble())
    val goal: StateFlow<Double> = _goal
    fun setGoal(kgPerWeek: Double) { _goal.value = kgPerWeek; prefs.edit().putFloat("goal_kg_week", kgPerWeek.toFloat()).apply(); refreshAnalysis() }

    fun setHrMax(v: Int) { _hrMax.value = v.coerceIn(150, 230); prefs.edit().putInt("hr_max", _hrMax.value).apply(); refreshHealth(full = true) }

    // ---- Health Connect -----------------------------------------------------------
    /** Last Fitbit days, saved on the phone so the app opens with data instead of an empty screen. */
    private val cache = com.silverajat.lifttracker.health.HealthCache(app.filesDir)
    private val cached = cache.load()
    private val _healthState = MutableStateFlow(
        if (!health.available()) HealthState.UNAVAILABLE else if (cached != null) HealthState.OK else HealthState.LOADING)
    val healthState: StateFlow<HealthState> = _healthState
    private val _days = MutableStateFlow(cached?.days.orEmpty())
    val days: StateFlow<List<HealthDay>> = _days
    private val _analysis = MutableStateFlow<Analysis.Result?>(null)
    val analysis: StateFlow<Analysis.Result?> = _analysis
    private val _syncedAt = MutableStateFlow<Long?>(cached?.savedMs)
    val syncedAt: StateFlow<Long?> = _syncedAt
    private var healthJob: Job? = null
    private var scaleWeights: List<WeightEntry> = emptyList()

    /** Your logged morning weights, plus smart-scale ones for days you didn't log (yours win). */
    private fun allWeights(): List<WeightEntry> {
        val mine = meals.weights()
        val have = mine.map { it.date }.toSet()
        return (mine + scaleWeights.filter { it.date !in have }).sortedBy { it.date }
    }

    /**
     * Sync from Health Connect. The first time ever: a full read (90 days).
     * After that: the last 3 days on top of the saved copy, every time the app
     * opens (the saved data stays on screen meanwhile). [force] restarts a running sync;
     * [full] re-reads everything (after changing max heart rate).
     */
    fun refreshHealth(force: Boolean = false, full: Boolean = false) {
        if (!health.available()) { _healthState.value = HealthState.UNAVAILABLE; return }
        // a sync already running (opening the app fires this from two places): let it finish
        if (healthJob?.isActive == true && !force && !full) return
        healthJob?.cancel()
        healthJob = viewModelScope.launch {
            val granted = health.granted()
            if (granted.isEmpty()) { _healthState.value = HealthState.NEEDS_PERMISSION; return@launch }
            if (_days.value.isEmpty()) _healthState.value = HealthState.LOADING
            // quick: re-read only the last 3 days (late sleep and workouts sync in), keep older days from the saved copy.
            // full 31 days at most once a day, or when there's no saved copy yet.
            val saved = cache.load()
            val all = full || saved == null || saved.days.size < 20
            val fresh = withContext(Dispatchers.IO) { runCatching { health.days(if (all) FIRST_DAYS else 3, _hrMax.value) }.getOrDefault(emptyList()) }
            if (fresh.isEmpty() && _days.value.isNotEmpty()) { _healthState.value = HealthState.OK; return@launch }
            val d = if (all || saved == null) fresh else cache.merge(saved.days, fresh)
            _days.value = d
            withContext(Dispatchers.IO) { cache.save(d, all) }
            // weigh-ins from a smart scale (via Google Health), one per day
            scaleWeights = withContext(Dispatchers.IO) { health.weights(70) }
                .groupBy { it.first.atZone(zone).toLocalDate() }.map { (day, v) -> WeightEntry(day, v.minBy { it.first }.second, 0) }
            refreshAnalysis()
            _syncedAt.value = System.currentTimeMillis()
            _healthState.value = HealthState.OK
            refreshWalks()
            refreshActivities()
            checkInsights()
        }
    }

    // ---- insights (notifications) -------------------------------------------------------
    private val insightStore = InsightStore(app.filesDir)
    private val _insights = MutableStateFlow(insightStore.all())
    val insights: StateFlow<List<Insight>> = _insights
    private val _notifyOn = MutableStateFlow(prefs.getBoolean(InsightEngine.NOTIFY_ON, true))
    val notifyOn: StateFlow<Boolean> = _notifyOn
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking

    fun setNotifyOn(on: Boolean) { _notifyOn.value = on; prefs.edit().putBoolean(InsightEngine.NOTIFY_ON, on).apply() }

    fun reloadInsights() { _insights.value = insightStore.all() }

    /** Send anything that's due (the app opening counts as a check). [force]: a fresh morning summary now. */
    fun checkInsights(force: Boolean = false) {
        if (_checking.value) return
        _checking.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { InsightEngine.run(getApplication(), force) } }
            reloadInsights()
            _checking.value = false
        }
    }

    // ---- meals ---------------------------------------------------------------------
    private val _date = MutableStateFlow(LocalDate.now(zone))
    val date: StateFlow<LocalDate> = _date
    private val _entries = MutableStateFlow(meals.day(_date.value))
    val entries: StateFlow<List<MealEntry>> = _entries
    private val _todayEntries = MutableStateFlow(meals.day(LocalDate.now(zone)))
    val todayEntries: StateFlow<List<MealEntry>> = _todayEntries
    private val _weights = MutableStateFlow(meals.weights())
    val weights: StateFlow<List<WeightEntry>> = _weights
    /** Steps in the 45 min after each main meal (entry id -> steps), from Health Connect. */
    private val _walks = MutableStateFlow<Map<String, Long>>(emptyMap())
    val walks: StateFlow<Map<String, Long>> = _walks

    fun setDate(d: LocalDate) {
        _date.value = d
        reloadMeals()
    }

    private fun reloadMeals() {
        _entries.value = meals.day(_date.value)
        _todayEntries.value = meals.day(LocalDate.now(zone))
        refreshWalks()
        refreshAnalysis()
    }

    /**
     * Re-run the analysis (Fitbit days + gym log + food + weight). Cheap:
     * call it whenever the Today tab is shown or data changes.
     */
    fun refreshAnalysis() {
        viewModelScope.launch {
            val today = LocalDate.now(zone)
            _analysis.value = withContext(Dispatchers.Default) {
                runCatching {
                    Analysis.run(_days.value, sessions.all(), meals.days(today.minusDays(60), today), allWeights(), today, zone, _goal.value)
                }.getOrNull()
            }
            if (_analysis.value != null && _healthState.value != HealthState.LOADING) refreshTake()
            // keep the home-screen widget in step (only once Fitbit data has loaded, so it never shows blanks)
            _analysis.value?.takeIf { _healthState.value == HealthState.OK || _days.value.isNotEmpty() }?.let { a ->
                withContext(Dispatchers.IO) {
                    com.silverajat.lifttracker.widget.WidgetData.publish(getApplication(),
                        com.silverajat.lifttracker.widget.WidgetData.from(a, meals.day(today), today, java.time.LocalTime.now(zone), _days.value))
                }
            }
        }
    }

    /** Log a food at a time (default: now, or noon on another day). */
    fun log(slot: Slot, food: Food, timeMs: Long? = null) {
        val d = _date.value
        val t = timeMs ?: if (d == LocalDate.now(zone)) System.currentTimeMillis()
            else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        meals.add(MealEntry(MealStore.newId(), d, slot, food.id, food.name, food.macros, t))
        reloadMeals()
    }

    fun logCustom(slot: Slot, name: String, macros: Macros) {
        val d = _date.value
        val t = if (d == LocalDate.now(zone)) System.currentTimeMillis() else d.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        meals.add(MealEntry(MealStore.newId(), d, slot, null, name, macros, t))
        reloadMeals()
    }

    fun remove(e: MealEntry) { meals.remove(e); reloadMeals() }

    fun setTime(e: MealEntry, hour: Int, minute: Int) {
        val t = e.date.atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59)).atZone(zone).toInstant().toEpochMilli()
        meals.update(e.copy(timeMs = t))
        reloadMeals()
    }

    fun setPortion(e: MealEntry, portion: Double) {
        val base = e.macros * (1.0 / e.portion)
        meals.update(e.copy(portion = portion, macros = base * portion))
        reloadMeals()
    }

    fun setWeight(kg: Double, d: LocalDate = LocalDate.now(zone)) {
        meals.setWeight(WeightEntry(d, kg, System.currentTimeMillis()))
        _weights.value = meals.weights()
        refreshAnalysis()
    }

    fun deleteWeight(d: LocalDate) { meals.deleteWeight(d); _weights.value = meals.weights(); refreshAnalysis() }

    private val mains = setOf("salad_egg", "salad_carrot", "chicken_rice", "mixed_rice")

    private fun refreshWalks() {
        val list = _entries.value.filter { it.foodId in mains }
        if (list.isEmpty()) return
        viewModelScope.launch {
            val out = HashMap(_walks.value)
            for (e in list) {
                val from = Instant.ofEpochMilli(e.timeMs)
                if (from.isAfter(Instant.now())) continue
                val s = withContext(Dispatchers.IO) { health.steps(from, from.plus(Duration.ofMinutes(45))) } ?: continue
                out[e.id] = s
            }
            _walks.value = out
        }
    }

    // ---- activities (runs, football, badminton, gym, Fitbit workouts) -------------------
    private val activityStore = com.silverajat.lifttracker.activity.ActivityStore(app.filesDir)
    private val _activities = MutableStateFlow(activityStore.all())
    val activities: StateFlow<List<com.silverajat.lifttracker.activity.Activity>> = _activities
    private val manualHr = HashMap<String, com.silverajat.lifttracker.health.Workout>()
    private val overrides = com.silverajat.lifttracker.activity.ActivityOverrides(app.filesDir)
    private val _items = MutableStateFlow<List<com.silverajat.lifttracker.activity.ActivityItem>>(emptyList())
    /** Everything you did, newest first (the Strain page and the Sports tab). */
    val items: StateFlow<List<com.silverajat.lifttracker.activity.ActivityItem>> = _items

    fun refreshActivities() {
        _activities.value = activityStore.all()
        viewModelScope.launch {
            val list = _activities.value
            // heart rate for things you logged or tracked (once each), from the Fitbit
            if (_healthState.value == HealthState.OK) for (a in list.filter { it.id !in manualHr }.takeLast(30)) {
                val rest = _days.value.lastOrNull { it.restingHr != null }?.restingHr
                withContext(Dispatchers.IO) {
                    health.windowStats(Instant.ofEpochMilli(a.startMs), Instant.ofEpochMilli(a.endMs), rest, _hrMax.value)
                }?.let { manualHr[a.id] = it }
            }
            _items.value = withContext(Dispatchers.Default) {
                com.silverajat.lifttracker.activity.ActivityLog.merge(_days.value, list, manualHr, sessions.all(), overrides.all())
            }
        }
    }

    fun addActivity(sport: com.silverajat.lifttracker.activity.Sport, startMs: Long, minutes: Int, km: Double?, note: String) {
        activityStore.add(com.silverajat.lifttracker.activity.Activity("a$startMs", sport, startMs, minutes * 60, km, note))
        refreshActivities()
    }

    /** Fix what an activity was (the Fitbit called a walk "soccer"); null hides a Fitbit one that wasn't an activity. */
    fun changeType(item: com.silverajat.lifttracker.activity.ActivityItem, sport: com.silverajat.lifttracker.activity.Sport?) {
        val manual = item.manualId?.let { id -> activityStore.all().firstOrNull { it.id == id } }
        when {
            manual != null -> if (sport == null) activityStore.delete(manual.id) else activityStore.add(manual.copy(sport = sport))
            item.key.startsWith("f-") -> {
                overrides.set(item.start.toEpochMilli(), sport)
                // rewrite its summary in the Insights feed too (no new notification), or remove it if hidden
                val key = "act-${item.start.toEpochMilli()}"
                val old = insightStore.all().firstOrNull { it.key == key }
                if (old != null) viewModelScope.launch {
                    val w = _days.value.flatMap { it.workouts }.firstOrNull { it.start == item.start }?.let { overrides.fix(it) }
                    val updated = if (w == null) null else {
                        val st = withContext(Dispatchers.IO) { health.activityStats(w.start, w.end) }
                        com.silverajat.lifttracker.insights.Insights.activity(w, st, meals.day(w.start.atZone(zone).toLocalDate()), old.timeMs)
                    }
                    withContext(Dispatchers.IO) { insightStore.replace(key, updated) }
                    reloadInsights()
                }
            }
        }
        refreshActivities()
    }

    fun deleteActivity(id: String) { activityStore.delete(id); manualHr.remove(id); refreshActivities() }

    // ---- GPT's daily take ------------------------------------------------------------------
    private val _take = MutableStateFlow(com.silverajat.lifttracker.chat.Coach.load(app.filesDir))
    val take: StateFlow<com.silverajat.lifttracker.chat.Coach.Take?> = _take
    private val _taking = MutableStateFlow(false)
    val taking: StateFlow<Boolean> = _taking
    private val _takeError = MutableStateFlow<String?>(null)
    val takeError: StateFlow<String?> = _takeError

    /** Ask GPT for today's analysis: once a day by itself, or now with [force]. */
    fun refreshTake(force: Boolean = false) {
        val key = _apiKey.value
        if (key.isEmpty() || _taking.value) return
        val today = LocalDate.now(zone)
        if (!force && _take.value?.date == today) return
        if (!force && _healthState.value == HealthState.LOADING) return
        _taking.value = true
        _takeError.value = null
        viewModelScope.launch {
            val r = runCatching {
                val ctx = contextText()
                withContext(Dispatchers.IO) { com.silverajat.lifttracker.chat.Coach.ask(key, _model.value, ctx, com.silverajat.lifttracker.chat.Coach.DAILY) }
            }
            r.onSuccess { text ->
                val t = com.silverajat.lifttracker.chat.Coach.Take(today, text, System.currentTimeMillis())
                com.silverajat.lifttracker.chat.Coach.save(getApplication<Application>().filesDir, t)
                _take.value = t
            }.onFailure { _takeError.value = it.message }
            _taking.value = false
        }
    }

    // ---- chat --------------------------------------------------------------------------
    private val _chat = MutableStateFlow(chatStore.load())
    val chat: StateFlow<List<ChatMessage>> = _chat
    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending
    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError

    fun clearChat() { chatStore.clear(); _chat.value = emptyList(); _chatError.value = null }

    /** The text GPT gets about you (also viewable in Chat settings). */
    suspend fun contextText(): String = withContext(Dispatchers.IO) {
        val today = LocalDate.now(zone)
        val modelFile = File(getApplication<Application>().filesDir, "rep_model.json")
        ChatContext.build(
            now = Instant.now(), zone = zone,
            sessions = sessions.all(),
            model = RepModel.Model.fromJson(runCatching { modelFile.readText() }.getOrNull()),
            health = overrides.apply(_days.value.takeLast(14)),
            analysis = _analysis.value,
            meals = meals.days(today.minusDays(13), today),
            weights = meals.weights(),
            hrMax = _hrMax.value,
            healthNote = when (_healthState.value) {
                HealthState.OK -> "synced this session"
                HealthState.NEEDS_PERMISSION -> "not connected yet"
                HealthState.UNAVAILABLE -> "Health Connect not available"
                HealthState.LOADING -> "loading"
            },
        )
    }

    fun send(text: String) {
        val key = _apiKey.value
        if (text.isBlank() || _sending.value) return
        if (key.isEmpty()) { _chatError.value = "Add your OpenAI API key in brainstorm settings first (gear icon)."; return }
        val history = _chat.value + ChatMessage("user", text.trim(), System.currentTimeMillis())
        _chat.value = history
        chatStore.save(history)
        _sending.value = true
        _chatError.value = null
        viewModelScope.launch {
            val result = runCatching {
                val system = contextText()
                withContext(Dispatchers.IO) { Gpt.send(key, _model.value, system, history.takeLast(30)) }
            }
            result.onSuccess { reply ->
                val updated = _chat.value + ChatMessage("assistant", reply, System.currentTimeMillis())
                _chat.value = updated
                chatStore.save(updated)
            }.onFailure { _chatError.value = it.message ?: "Couldn't reach OpenAI" }
            _sending.value = false
        }
    }

    init {
        viewModelScope.launch { com.silverajat.lifttracker.activity.RunTracker.saved.collect { if (it != null) refreshActivities() } }
        refreshActivities()
        refreshHealth()
        refreshAnalysis()
    }
}
