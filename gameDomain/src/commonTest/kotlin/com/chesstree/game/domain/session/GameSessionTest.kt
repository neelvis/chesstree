package com.chesstree.game.domain.session

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.gameScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        assertEquals(
            initial.state.position.pieces.getValue(capture.pieceId).army,
            trophy.capturedByArmy
        )
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

    @Test
    fun replayKeepsLegacyMovesAfterBareKingsLoadableAndFinishesOnTheNextMove() {
        val capture = MoveIntent(
            actor = PlayerId.WHITE,
            from = BoardCoordinate(0, 1, 1),
            to = BoardCoordinate(0, 2, 1),
        )
        val currentHistory = assertNotNull(GameSession.replay(bareKingsPractice, listOf(capture)))
        assertIs<GamePhase.Finished>(currentHistory.state.phase)
        val historicalPosition = assertNotNull(
            GameSession.replayPosition(bareKingsPractice, listOf(capture)),
        )
        assertIs<GamePhase.InProgress>(historicalPosition.state.phase)

        val legacyAfterCapture = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                bareKingsPractice.initialState,
                capture,
                finishInsufficientMaterial = false,
            ),
        ).state
        val historicalKingMove = LegalMoveGenerator.legalMoves(legacyAfterCapture).first()
        val history = listOf(
            capture,
            MoveIntent(
                actor = historicalKingMove.actor,
                from = historicalKingMove.from,
                to = historicalKingMove.to,
                promotion = historicalKingMove.promotion,
            ),
        )

        val replayed = assertNotNull(GameSession.replay(bareKingsPractice, history))
        assertIs<GamePhase.InProgress>(replayed.state.phase)

        val nextMove = LegalMoveGenerator.legalMoves(replayed.state).first()
        val finished = assertIs<SessionMoveResult.Applied>(
            replayed.apply(
                MoveIntent(
                    actor = nextMove.actor,
                    from = nextMove.from,
                    to = nextMove.to,
                    promotion = nextMove.promotion,
                ),
            ),
        ).session
        assertIs<GamePhase.Finished>(finished.state.phase)
    }

    @Test
    fun undoLastMoveRemovesItFromHistoryAndRestoresThePreviousPosition() {
        val initial = GameSession(capturePractice)
        val firstMove = LegalMoveGenerator.legalMoves(initial.state).first()
        val afterFirstMove = assertIs<SessionMoveResult.Applied>(
            initial.apply(firstMove.toIntent()),
        ).session
        val secondMove = LegalMoveGenerator.legalMoves(afterFirstMove.state).first()
        val afterSecondMove = assertIs<SessionMoveResult.Applied>(
            afterFirstMove.apply(secondMove.toIntent()),
        ).session

        val undone = assertNotNull(afterSecondMove.undoLastMove())

        assertEquals(afterFirstMove, undone)
        assertEquals(listOf(firstMove.toIntent()), undone.moves)
        assertNull(initial.undoLastMove())
    }

    private fun com.chesstree.game.domain.Move.toIntent() = MoveIntent(
        actor = actor,
        from = from,
        to = to,
        promotion = promotion,
    )

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

        val bareKingsPractice = gameScenario("bare-kings-practice") {
            piece("white-king", ArmyColor.WHITE, PieceType.KING, BoardCoordinate(0, 1, 1))
            piece("black-knight", ArmyColor.BLACK, PieceType.KNIGHT, BoardCoordinate(0, 2, 1))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, BoardCoordinate(4, 3, 0))
            checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 1)
            turn(PlayerId.WHITE, ply = 2)
        }
    }
}
