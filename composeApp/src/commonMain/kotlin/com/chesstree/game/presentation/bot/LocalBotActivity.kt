package com.chesstree.game.presentation.bot

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LocalBotActivityState(val active: Boolean, val revision: Long)

/** Platform entrypoints update this on their UI event thread. */
class LocalBotActivity(initiallyActive: Boolean) {
    private val mutableState = MutableStateFlow(LocalBotActivityState(initiallyActive, 0))
    val state: StateFlow<LocalBotActivityState> = mutableState.asStateFlow()

    fun setActive(active: Boolean) {
        val before = mutableState.value
        if (before.active != active) {
            mutableState.value = LocalBotActivityState(active, before.revision + 1)
        }
    }
}
