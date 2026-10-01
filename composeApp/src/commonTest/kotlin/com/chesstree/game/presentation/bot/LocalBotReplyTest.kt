package com.chesstree.game.presentation.bot

import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalBotReplyTest {
    private val state = ManualGameScenarios.standard.initialState
    private val identity = LocalBotIdentity(
        requestId = "request-1",
        gameId = "game-1",
        positionRevision = 4,
    )
    private val request = LocalBotRequest(
        identity = identity,
        state = state,
        policy = BotPolicy.DEFAULT,
    )
    private val reply = LocalBotReply.Failed(identity)

    @Test
    fun requestRejectsUnsupportedOpeningBookVersions() {
        assertFailsWith<IllegalArgumentException> {
            request.copy(openingBookVersion = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            request.copy(openingBookVersion = 2)
        }
    }

    @Test
    fun acceptsReplyForCurrentRequestAndPosition() {
        assertTrue(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = identity,
                gameId = identity.gameId,
                positionRevision = identity.positionRevision,
                state = state,
            ),
        )
    }

    @Test
    fun rejectsSameStateAfterUndoWhenRevisionChanged() {
        assertFalse(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = identity,
                gameId = identity.gameId,
                positionRevision = identity.positionRevision + 1,
                state = state,
            ),
        )
    }

    @Test
    fun rejectsReplyForDifferentGame() {
        assertFalse(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = identity,
                gameId = "game-2",
                positionRevision = identity.positionRevision,
                state = state,
            ),
        )
    }

    @Test
    fun rejectsReplyAfterRequestIsRetired() {
        assertFalse(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = null,
                gameId = identity.gameId,
                positionRevision = identity.positionRevision,
                state = state,
            ),
        )
    }

    @Test
    fun rejectsReplyAfterRequestIsReplaced() {
        val replacement = identity.copy(requestId = "request-2")

        assertFalse(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = replacement,
                gameId = identity.gameId,
                positionRevision = identity.positionRevision,
                state = state,
            ),
        )
    }

    @Test
    fun rejectsReplyWhenCurrentStateChanged() {
        val changedState = ManualGameScenarios.sparseMovement.initialState

        assertFalse(
            reply.matchesCurrentRequest(
                request = request,
                activeIdentity = identity,
                gameId = identity.gameId,
                positionRevision = identity.positionRevision,
                state = changedState,
            ),
        )
    }
}
