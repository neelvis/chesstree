package com.chesstree.game.presentation.history

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameHistoryNavigationTest {
    @Test
    fun backAndForwardBrowseHistoryWithoutChangingTheCommittedSession() {
        val initial = GameSession(ManualGameScenarios.capturePractice)
        val afterFirstMove = initial.applyFirstLegalMove()
        val committed = afterFirstMove.applyFirstLegalMove()
        val initialNavigation = GameHistoryNavigation.latest()

        val oneMoveBack = initialNavigation.back(committed)

        assertEquals(1, oneMoveBack.displayedMoveCount(committed))
        assertEquals(afterFirstMove.state, oneMoveBack.displayedSession(committed).state)
        assertEquals(2, committed.moves.size)
        assertFalse(oneMoveBack.isAtLatest(committed))

        val latest = oneMoveBack.forward(committed)
        assertTrue(latest.isAtLatest(committed))
        assertEquals(committed, latest.displayedSession(committed))
    }

    @Test
    fun navigationStopsAtBothEndsOfHistory() {
        val committed = GameSession(ManualGameScenarios.capturePractice).applyFirstLegalMove()
        val start = GameHistoryNavigation.latest().back(committed).back(committed)

        assertEquals(0, start.displayedMoveCount(committed))
        assertFalse(start.canGoBack(committed))
        assertTrue(start.canGoForward(committed))
        assertEquals(committed.scenario.initialState, start.displayedSession(committed).state)
    }

    private fun GameSession.applyFirstLegalMove(): GameSession {
        val move = LegalMoveGenerator.legalMoves(state).first()
        return assertIs<SessionMoveResult.Applied>(apply(move.toIntent())).session
    }

    private fun Move.toIntent() = MoveIntent(actor, from, to, promotion)
}
