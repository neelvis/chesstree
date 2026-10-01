package com.chesstree.game.presentation.bot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LocalBotActivityTest {
    @Test
    fun everyActivityTransitionAdvancesRevisionMonotonically() {
        val activity = LocalBotActivity(initiallyActive = false)
        val revisions = mutableListOf(activity.state.value.revision)

        activity.setActive(true)
        revisions += activity.state.value.revision
        activity.setActive(false)
        revisions += activity.state.value.revision
        activity.setActive(true)
        revisions += activity.state.value.revision

        assertEquals(listOf(0L, 1L, 2L, 3L), revisions)
        assertTrue(activity.state.value.active)
    }

    @Test
    fun settingSameActivityValueDoesNotAdvanceRevision() {
        val activity = LocalBotActivity(initiallyActive = true)

        activity.setActive(true)

        assertEquals(LocalBotActivityState(active = true, revision = 0), activity.state.value)
    }

    @Test
    fun initialActivityValueIsPreserved() {
        val initiallyActive = LocalBotActivity(initiallyActive = true)
        val initiallyInactive = LocalBotActivity(initiallyActive = false)

        assertEquals(LocalBotActivityState(active = true, revision = 0), initiallyActive.state.value)
        assertEquals(LocalBotActivityState(active = false, revision = 0), initiallyInactive.state.value)
    }

    @Test
    fun quickInactiveActiveTransitionDoesNotMatchCapturedRequestState() {
        val activity = LocalBotActivity(initiallyActive = true)
        val capturedRequestState = activity.state.value

        activity.setActive(false)
        activity.setActive(true)

        val currentState = activity.state.value
        assertTrue(currentState.active)
        assertNotEquals(capturedRequestState, currentState)
        assertEquals(capturedRequestState.revision + 2, currentState.revision)
    }
}
