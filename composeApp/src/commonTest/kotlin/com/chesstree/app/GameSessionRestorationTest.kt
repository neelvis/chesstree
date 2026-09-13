package com.chesstree.app

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameSessionRestorationTest {
    @Test
    fun savedSessionRestoresScenarioAndAppliedMovesAfterRecreation() {
        val initial = GameSession(ManualGameScenarios.capturePractice)
        val move = LegalMoveGenerator.legalMoves(initial.state).first()
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(move.actor, move.from, move.to, move.promotion)),
        ).session

        val restored = assertNotNull(
            restoreSession(
                encodeSessionForRestoration(moved),
                ManualGameScenarios.all,
            ),
        )

        assertEquals(moved.scenario.id, restored.scenario.id)
        assertEquals(moved.moves, restored.moves)
        assertEquals(moved.state, restored.state)
        assertEquals(moved.capturedPieces, restored.capturedPieces)
    }

    @Test
    fun invalidOrUnknownSavedSessionIsIgnored() {
        assertNull(restoreSession("invalid", ManualGameScenarios.all))
        assertNull(
            restoreSession(
                "CHESSTREE|1\nscenario|unknown-scenario\n",
                ManualGameScenarios.all,
            ),
        )
        assertEquals(
            ManualGameScenarios.standard.id,
            restoreSessionOrDefault("invalid", ManualGameScenarios.all).scenario.id,
        )
    }
}
