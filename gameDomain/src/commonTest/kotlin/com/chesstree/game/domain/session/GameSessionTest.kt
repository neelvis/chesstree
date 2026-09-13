package com.chesstree.game.domain.session

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.scenario.gameScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class GameSessionTest {
    @Test
    fun captureRecordsTrayDataForTheMovingArmy() {
        val initial = GameSession(capturePractice)
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
        val initial = GameSession(capturePractice)
        val capture = LegalMoveGenerator.legalMoves(initial.state)
            .first { it.type == MoveType.CAPTURE || it.type == MoveType.EN_PASSANT }
        val applied = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(capture.actor, capture.from, capture.to, capture.promotion)),
        ).session

        val replayed = assertNotNull(GameSession.replay(initial.scenario, applied.moves))

        assertEquals(applied.state, replayed.state)
        assertEquals(applied.capturedPieces, replayed.capturedPieces)
    }

    private companion object {
        val capturePractice = gameScenario("capture-practice") {
            piece("white-king", ArmyColor.WHITE, PieceType.KING, BoardCoordinate(0, 3, 0))
            piece("red-king", ArmyColor.RED, PieceType.KING, BoardCoordinate(2, 3, 0))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, BoardCoordinate(4, 3, 0))
            piece("white-queen", ArmyColor.WHITE, PieceType.QUEEN, BoardCoordinate(0, 0, 3))
            piece("red-rook", ArmyColor.RED, PieceType.ROOK, BoardCoordinate(1, 0, 3))
            piece(
                "black-pawn",
                ArmyColor.BLACK,
                PieceType.PAWN,
                BoardCoordinate(5, 0, 3),
                hasMoved = true,
            )
        }
    }
}
