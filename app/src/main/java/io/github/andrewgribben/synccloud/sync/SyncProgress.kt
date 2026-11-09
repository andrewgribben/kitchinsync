package io.github.andrewgribben.synccloud.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide "what is the sync adapter doing right now" signal. SyncService/CalendarSyncer/
 * ContactsSyncer run in the app's default process (no android:process override), so a plain
 * singleton StateFlow is enough for the UI to collect without IPC.
 */
object SyncProgress {
    enum class Phase { CALENDARS, CONTACTS }

    /**
     * Distinguishes what (current, total) actually count, so the UI never shows the same shape
     * for two different meanings (e.g. "calendar 1 of 3" vs "120 of 400 events downloaded"):
     * - [LISTING]: current/total is "which calendar are we on" while we wait for its REPORT to
     *   come back. There's no meaningful fraction of *that calendar's* work done yet, so the bar
     *   is always indeterminate here even though total is non-null.
     * - [ITEMS]: current/total is "items of this phase downloaded so far", so the bar is
     *   determinate once total is known.
     */
    enum class Stage { LISTING, ITEMS }

    /** @param total null when the item count isn't known yet (e.g. before the initial listing). */
    data class State(
        val accountName: String,
        val phase: Phase,
        val stage: Stage,
        val label: String,
        val current: Int,
        val total: Int?,
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun update(accountName: String, phase: Phase, stage: Stage, label: String, current: Int, total: Int?) {
        _state.value = State(accountName, phase, stage, label, current, total)
    }

    /** Clears the indicator, but only if it's still showing this account (another sync may have started). */
    fun clear(accountName: String) {
        if (_state.value?.accountName == accountName) _state.value = null
    }
}

/** Fraction in [0,1] for a determinate LinearProgressIndicator, or null for an indeterminate one. */
fun SyncProgress.State.fraction(): Float? {
    if (stage != SyncProgress.Stage.ITEMS) return null
    return total?.takeIf { it > 0 }?.let { (current.toFloat() / it).coerceIn(0f, 1f) }
}
