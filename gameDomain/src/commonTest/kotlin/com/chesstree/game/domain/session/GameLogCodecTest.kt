package com.chesstree.game.domain.session

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardTopology
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.scenario.gameScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameLogCodecTest {
    @Test
    fun encodedMovesUsePortableArmyAndStandardAlgebraicNotation() {
        val initial = GameSession(StandardGame.scenario)
        val move = LegalMoveGenerator.legalMoves(initial.state).first()
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(move.actor, move.from, move.to, move.promotion)),
        ).session

        val log = GameLogCodec.encode(moved)

        assertEquals(1, log.lineSequence().count())
        assertTrue(log.startsWith("1: W.N"))
        assertTrue("->" !in log)
        assertEquals(moved, GameLogCodec.restore(StandardGame.scenario, log).session)
    }

    @Test
    fun castlingUsesStandardNotationAndRoundTrips() {
        val scenario = gameScenario("log-castling") {
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
            piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, cell(0, 3, 0))
            piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 3, 0))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 3, 0))
            castlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE, "white-rook")
        }
        val initial = GameSession(scenario)
        val castle =
            LegalMoveGenerator.legalMoves(initial.state).single { it.type == MoveType.CASTLING }
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(castle.actor, castle.from, castle.to)),
        ).session

        assertEquals("1: W.O-O", GameLogCodec.encode(moved))
        assertEquals(moved, GameLogCodec.restore(scenario, "1: W.O-O").session)
    }

    @Test
    fun queenSideCastlingWithoutExplicitRookIdKeepsItsSide() {
        val scenario = gameScenario("log-queen-side-castling") {
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
            piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, cell(0, 3, 0))
            piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 3, 0))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 3, 0))
            castlingRight(ArmyColor.WHITE, CastlingSide.QUEEN_SIDE)
        }
        val initial = GameSession(scenario)
        val castle =
            LegalMoveGenerator.legalMoves(initial.state).single { it.type == MoveType.CASTLING }
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(castle.actor, castle.from, castle.to)),
        ).session

        assertEquals("1: W.O-O-O", GameLogCodec.encode(moved))
        assertEquals(moved, GameLogCodec.restore(scenario, "1: W.O-O-O").session)
    }

    @Test
    fun promotionChoiceIsRecordedAndRoundTrips() {
        val (origin, target) = promotionStep(ArmyColor.WHITE)
        val scenario = gameScenario("log-promotion") {
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 3, 0))
            piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 3, 0))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 3, 0))
            piece("white-pawn", ArmyColor.WHITE, PieceType.PAWN, origin, hasMoved = true)
        }
        val initial = GameSession(scenario)
        val promotion = LegalMoveGenerator.legalMoves(initial.state).single { move ->
            move.to == target && move.promotion == PromotionChoice.KNIGHT
        }
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(
                MoveIntent(
                    promotion.actor,
                    promotion.from,
                    promotion.to,
                    promotion.promotion
                )
            ),
        ).session
        val log = GameLogCodec.encode(moved)

        assertTrue("=N" in log)
        assertEquals(moved, GameLogCodec.restore(scenario, log).session)
    }

    @Test
    fun restorationIgnoresMetadataAndSkipsLinesThatCannotBeApplied() {
        val initial = GameSession(StandardGame.scenario)
        val first = LegalMoveGenerator.legalMoves(initial.state).first()
        val afterFirst = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(first.actor, first.from, first.to, first.promotion)),
        ).session
        val second = LegalMoveGenerator.legalMoves(afterFirst.state).first()
        val complete = assertIs<SessionMoveResult.Applied>(
            afterFirst.apply(MoveIntent(second.actor, second.from, second.to, second.promotion)),
        ).session
        val validLines = GameLogCodec.encode(complete).lines()
        val input = listOf(
            validLines[0].replaceBeforeLast(':', "999: B.Q"),
            "это не ход",
            validLines[1].replaceBeforeLast(':', "-1: W.K"),
        ).joinToString("\n")

        val restored = GameLogCodec.restore(StandardGame.scenario, input)

        assertEquals(2, restored.restoredMoves)
        assertEquals(3, restored.totalMoves)
        assertEquals(complete, restored.session)
    }

    @Test
    fun emptyFragmentRestoresTheInitialPosition() {
        val restored = GameLogCodec.restore(StandardGame.scenario, "\n \n")

        assertEquals(0, restored.restoredMoves)
        assertEquals(0, restored.totalMoves)
        assertEquals(GameSession(StandardGame.scenario), restored.session)
    }

    private fun promotionStep(army: ArmyColor): Pair<BoardCoordinate, BoardCoordinate> =
        ThreePlayerBoardTopology.coordinates.asSequence()
            .mapNotNull { origin ->
                MovementDirections.forPiece(PieceType.PAWN, origin, army)
                    .firstOrNull { direction ->
                        direction.kind == DirectionKind.MOVE && direction.route.size == 2
                    }
                    ?.let { direction -> origin to direction.target }
            }
            .first()

    private fun cell(vertex: Int, column: Int, row: Int): BoardCoordinate =
        BoardCoordinate(vertex, column, row)
}
