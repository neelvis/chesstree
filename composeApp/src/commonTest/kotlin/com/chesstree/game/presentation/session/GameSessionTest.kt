package com.chesstree.game.presentation.session

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveType
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GameSessionTest {
    @Test
    fun captureRecordsTrayDataForTheMovingArmy() {
        val initial = GameSession(ManualGameScenarios.capturePractice)
        val capture = LegalMoveGenerator.legalMoves(initial.state)
            .first { it.type == MoveType.CAPTURE || it.type == MoveType.EN_PASSANT }

        val result = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(capture.actor, capture.from, capture.to, capture.promotion)),
        )
        val trophy = result.session.capturedPieces.single()

        assertEquals(capture.capturedPieceId?.value, trophy.id)
        assertEquals(initial.state.position.pieces.getValue(capture.pieceId).army, trophy.capturedByArmy)
    }

    @Test
    fun replayRestoresStateAndCapturedPiecesAtomically() {
        val initial = GameSession(ManualGameScenarios.capturePractice)
        val capture = LegalMoveGenerator.legalMoves(initial.state)
            .first { it.type == MoveType.CAPTURE || it.type == MoveType.EN_PASSANT }
        val applied = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(capture.actor, capture.from, capture.to, capture.promotion)),
        ).session

        val replayed = assertNotNull(GameSession.replay(initial.scenario, applied.moves))

        assertEquals(applied.state, replayed.state)
        assertEquals(applied.capturedPieces, replayed.capturedPieces)
    }
}
