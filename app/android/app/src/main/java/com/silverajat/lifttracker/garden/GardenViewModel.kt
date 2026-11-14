package com.silverajat.lifttracker.garden

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The focus timer and the saved study / work blocks. */
class GardenViewModel(app: Application) : AndroidViewModel(app) {
    private val store = FocusStore(app.filesDir)
    private val _sessions = MutableStateFlow(store.sessions())
    val sessions: StateFlow<List<FocusSession>> = _sessions
    private val _active = MutableStateFlow(store.active())
    val active: StateFlow<ActiveFocus?> = _active

    private fun setActive(a: ActiveFocus?) { store.setActive(a); _active.value = a }

    fun start(area: Area, label: String) {
        if (_active.value != null) return
        setActive(ActiveFocus(area, label.trim(), System.currentTimeMillis()))
    }

    /** Saves the block (if it ran at least a minute) and returns its minutes. */
    fun stop(): Int {
        val a = _active.value ?: return 0
        val now = System.currentTimeMillis()
        val awayS = a.awayS + (a.awaySince?.let { (now - it) / 1000 } ?: 0)
        val s = FocusSession(area = a.area, label = a.label, startMs = a.startMs, endMs = now, awayS = awayS)
        setActive(null)
        if (s.minutes >= 1) { _sessions.value = listOf(s) + _sessions.value; store.saveSessions(_sessions.value) }
        return s.minutes
    }

    fun discard() = setActive(null)

    fun delete(id: String) { _sessions.value = _sessions.value.filter { it.id != id }; store.saveSessions(_sessions.value) }

    /** You switched to another app (not just locked the phone): growth pauses. */
    fun leftApp() {
        if (!getApplication<Application>().getSharedPreferences("hub", android.content.Context.MODE_PRIVATE).getBoolean(PAUSE_AWAY, true)) return
        _active.value?.takeIf { it.awaySince == null }?.let { setActive(it.copy(awaySince = System.currentTimeMillis())) }
    }

    companion object { const val PAUSE_AWAY = "focus_pause_away" }

    /** Back in the app: the time away is set aside. */
    fun cameBack() {
        val a = _active.value ?: return
        val since = a.awaySince ?: return
        setActive(a.copy(awayS = a.awayS + (System.currentTimeMillis() - since) / 1000, awaySince = null))
    }
}
