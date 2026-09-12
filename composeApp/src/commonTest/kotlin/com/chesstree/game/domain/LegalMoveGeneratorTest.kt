package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LegalMoveGeneratorTest {
    @Test
    fun slidingPieceCapturesFirstOpponentAndCannotMoveBeyondIt() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val firstOpponent = piece("red-bishop", PieceType.BISHOP, ArmyColor.RED, cell(0, 2, 1))
        val hiddenOpponent = piece("black-bishop", PieceType.BISHOP, ArmyColor.BLACK, cell(0, 3, 1))
        val state = activeState(rook, firstOpponent, hiddenOpponent)

        val rookMoves = LegalMoveGenerator.pseudoLegalMoves(state)
            .filter { move -> move.pieceId == rook.id }

        assertTrue(rookMoves.any { move ->
            move.to == firstOpponent.coordinate && move.capturedPieceId == firstOpponent.id
        })
        assertFalse(rookMoves.any { move -> move.to == hiddenOpponent.coordinate })
    }

    @Test
    fun transferredArmyIsAlliedAndBlocksItsControllersPieces() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val transferredPiece = piece("red-bishop", PieceType.BISHOP, ArmyColor.RED, cell(0, 2, 1))
        val state = activeState(
            rook,
            transferredPiece,
            participants = mapOf(
                PlayerId.WHITE to Participant(PlayerId.WHITE),
                PlayerId.RED to Participant(
                    PlayerId.RED,
                    ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 4),
                ),
                PlayerId.BLACK to Participant(PlayerId.BLACK),
            ),
            armies = mapOf(
                ArmyColor.WHITE to ArmyControl(ArmyColor.WHITE, PlayerId.WHITE),
                ArmyColor.RED to ArmyControl(ArmyColor.RED, PlayerId.WHITE),
                ArmyColor.BLACK to ArmyControl(ArmyColor.BLACK, PlayerId.BLACK),
            ),
        )

        val rookMoves = LegalMoveGenerator.pseudoLegalMoves(state)
            .filter { move -> move.pieceId == rook.id }

        assertFalse(rookMoves.any { move -> move.to == transferredPiece.coordinate })
    }

    @Test
    fun legalMovesExcludeMoveThatExposesTheKing() {
        val king = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 0, 0))
        val shield = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 0))
        val attacker = piece("red-rook", PieceType.ROOK, ArmyColor.RED, cell(0, 3, 0))
        val state = activeState(
            king,
            shield,
            attacker,
            piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
            includeDefaultKings = false,
        )
        val exposingTarget = cell(0, 1, 1)

        assertTrue(LegalMoveGenerator.pseudoLegalMoves(state).any { move ->
            move.pieceId == shield.id && move.to == exposingTarget
        })
        assertFalse(LegalMoveGenerator.legalMoves(state).any { move ->
            move.pieceId == shield.id && move.to == exposingTarget
        })
    }

    @Test
    fun unmovedPawnMayAdvanceOneOrTwoEmptyCoordinates() {
        val pawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, cell(1, 2, 1))
        val forwardRoute = MovementDirections.forPiece(pawn.type, pawn.coordinate, pawn.army)
            .single { direction -> direction.kind == DirectionKind.MOVE }
            .route
        val state = activeState(pawn)

        val pawnTargets = LegalMoveGenerator.pseudoLegalMoves(state)
            .filter { move -> move.pieceId == pawn.id }
            .map(Move::to)
            .toSet()

        assertTrue(forwardRoute[1] in pawnTargets)
        assertTrue(forwardRoute[2] in pawnTargets)
        assertFalse(forwardRoute.drop(3).any { target -> target in pawnTargets })
    }

    @Test
    fun promotionTargetProducesExactlyTheFourSupportedChoices() {
        val (origin, target) = promotionStep(ArmyColor.WHITE)
        val pawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, origin, hasMoved = true)
        val state = activeState(pawn)

        val promotions = LegalMoveGenerator.pseudoLegalMoves(state)
            .filter { move -> move.pieceId == pawn.id && move.to == target }
            .mapNotNull(Move::promotion)
            .toSet()

        assertEquals(PromotionChoice.entries.toSet(), promotions)
    }

    @Test
    fun stalematedKingStillPreventsAnotherKingFromApproaching() {
        val stalematedKing = piece("red-king", PieceType.KING, ArmyColor.RED, cell(0, 0, 0))
        val forbiddenTarget = cell(0, 1, 0)
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 2, 0))
        val participants = defaultParticipants().toMutableMap().apply {
            this[PlayerId.RED] = Participant(
                PlayerId.RED,
                ParticipantStatus.Stalemated(atPly = 1),
            )
        }
        val state = activeState(
            whiteKing,
            stalematedKing,
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
            participants = participants,
            includeDefaultKings = false,
        )

        assertTrue(LegalMoveGenerator.pseudoLegalMoves(state).any { move ->
            move.pieceId == whiteKing.id && move.to == forbiddenTarget
        })
        assertFalse(LegalMoveGenerator.legalMoves(state).any { move ->
            move.pieceId == whiteKing.id && move.to == forbiddenTarget
        })
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

    private fun activeState(
        vararg suppliedPieces: Piece,
        participants: Map<PlayerId, Participant> = defaultParticipants(),
        armies: Map<ArmyColor, ArmyControl> = defaultArmies(),
        includeDefaultKings: Boolean = true,
    ): GameState {
        val pieces = suppliedPieces.associateBy(Piece::id).toMutableMap()
        if (includeDefaultKings) {
            listOf(
                piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0)),
                piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
                piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
            ).forEach { king ->
                if (king.army.originalPlayer.let(participants::get)?.status == ParticipantStatus.Active) {
                    if (king.id !in pieces) pieces[king.id] = king
                }
            }
        }
        return GameState(
            position = Position(pieces),
            participants = participants,
            armies = armies,
            turn = Turn(PlayerId.WHITE, ply = 1),
            phase = GamePhase.InProgress,
        )
    }

    private fun piece(
        id: String,
        type: PieceType,
        army: ArmyColor,
        coordinate: BoardCoordinate,
        hasMoved: Boolean = false,
    ): Piece = Piece(PieceId(id), type, army, coordinate, hasMoved)

    private fun cell(vertex: Int, column: Int, row: Int): BoardCoordinate =
        BoardCoordinate(vertex, column, row)

    private companion object {
        fun defaultParticipants(): Map<PlayerId, Participant> =
            PlayerId.entries.associateWith(::Participant)

        fun defaultArmies(): Map<ArmyColor, ArmyControl> =
            ArmyColor.entries.associateWith { army -> ArmyControl(army, army.originalPlayer) }
    }
}
