package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class GameReducerTest {
    @Test
    fun legalMoveProducesANewPositionAndAdvancesTheTurn() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val state = stateWith(rook)
        val target = cell(0, 2, 1)

        val reduction = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, rook.coordinate, target),
        )

        val applied = assertIs<MoveReduction.Applied>(reduction)
        assertEquals(rook.coordinate, state.position.pieces.getValue(rook.id).coordinate)
        assertEquals(target, applied.state.position.pieces.getValue(rook.id).coordinate)
        assertEquals(true, applied.state.position.pieces.getValue(rook.id).hasMoved)
        assertEquals(Turn(PlayerId.RED, ply = 2), applied.state.turn)
        assertEquals(applied.move, LegalMoveGenerator.legalMoves(state).single { move ->
            move.pieceId == rook.id && move.to == target
        })
    }

    @Test
    fun rejectedMoveDoesNotMutateTheInputState() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val state = stateWith(rook)

        val reduction = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.RED, rook.coordinate, cell(0, 2, 1)),
        )

        assertEquals(
            MoveReduction.Rejected(MoveRejectionReason.NOT_ACTORS_TURN),
            reduction,
        )
        assertEquals(rook, state.position.pieces.getValue(rook.id))
        assertEquals(Turn(PlayerId.WHITE, ply = 1), state.turn)
    }

    @Test
    fun captureRemovesTheOpponentAndRecordsItsStableIdentity() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val bishop = piece("red-bishop", PieceType.BISHOP, ArmyColor.RED, cell(0, 2, 1))
        val state = stateWith(rook, bishop)

        val reduction = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, rook.coordinate, bishop.coordinate),
        )

        val applied = assertIs<MoveReduction.Applied>(reduction)
        assertEquals(MoveType.CAPTURE, applied.move.type)
        assertEquals(bishop.id, applied.move.capturedPieceId)
        assertFalse(bishop.id in applied.state.position.pieces)
        assertEquals(bishop, state.position.pieces.getValue(bishop.id))
    }

    @Test
    fun turnSkipsAStalematedParticipant() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val participants = defaultParticipants().toMutableMap().apply {
            this[PlayerId.RED] = Participant(
                PlayerId.RED,
                ParticipantStatus.Stalemated(atPly = 1),
            )
        }
        val state = stateWith(rook, participants = participants)

        val reduction = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, rook.coordinate, cell(0, 2, 1)),
        )

        val applied = assertIs<MoveReduction.Applied>(reduction)
        assertEquals(Turn(PlayerId.BLACK, ply = 2), applied.state.turn)
    }

    @Test
    fun promotionChoiceIsRequiredAndApplied() {
        val (origin, target) = promotionStep(ArmyColor.WHITE)
        val pawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, origin, hasMoved = true)
        val kingCoordinate = ThreePlayerBoardTopology.coordinates.first { coordinate ->
            coordinate != origin && coordinate != target
        }
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, kingCoordinate)
        val participants = mapOf(
            PlayerId.WHITE to Participant(PlayerId.WHITE),
            PlayerId.RED to Participant(
                PlayerId.RED,
                ParticipantStatus.Checkmated(PlayerId.WHITE, atPly = 2),
            ),
            PlayerId.BLACK to Participant(
                PlayerId.BLACK,
                ParticipantStatus.Checkmated(PlayerId.WHITE, atPly = 3),
            ),
        )
        val armies = ArmyColor.entries.associateWith { army ->
            ArmyControl(army, PlayerId.WHITE)
        }
        val state = GameState(
            position = Position(listOf(pawn, whiteKing).associateBy(Piece::id)),
            participants = participants,
            armies = armies,
            turn = Turn(PlayerId.WHITE, ply = 4),
            phase = GamePhase.InProgress,
        )

        val missingChoice = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, origin, target),
        )
        val promoted = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, origin, target, PromotionChoice.QUEEN),
        )

        assertEquals(
            MoveReduction.Rejected(MoveRejectionReason.PROMOTION_REQUIRED),
            missingChoice,
        )
        val applied = assertIs<MoveReduction.Applied>(promoted)
        assertEquals(PieceType.QUEEN, applied.state.position.pieces.getValue(pawn.id).type)
        assertNull(applied.state.position.enPassantTarget)
    }

    @Test
    fun movingAReferencedRookAndMissingEnPassantRevokeThoseRights() {
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0))
        val redKing = piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0))
        val blackKing = piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0))
        val redPawn = piece(
            "red-pawn",
            PieceType.PAWN,
            ArmyColor.RED,
            cell(2, 1, 1),
            hasMoved = true,
        )
        val castlingRights = setOf(
            CastlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE, rook.id),
        )
        val enPassantTarget = EnPassantTarget(
            pawnId = redPawn.id,
            captureCoordinate = cell(2, 1, 2),
            eligiblePlayers = setOf(PlayerId.WHITE),
        )
        val state = GameState(
            position = Position(
                pieces = listOf(rook, whiteKing, redKing, blackKing, redPawn)
                    .associateBy(Piece::id),
                castlingRights = castlingRights,
                enPassantTarget = enPassantTarget,
            ),
            participants = defaultParticipants(),
            armies = defaultArmies(),
            turn = Turn(PlayerId.WHITE, ply = 1),
            phase = GamePhase.InProgress,
        )

        val reduction = GameReducer.reduce(
            state,
            MoveIntent(PlayerId.WHITE, rook.coordinate, cell(0, 2, 1)),
        )

        val applied = assertIs<MoveReduction.Applied>(reduction)
        assertEquals(emptySet(), applied.state.position.castlingRights)
        assertNull(applied.state.position.enPassantTarget)
    }

    private fun stateWith(
        vararg suppliedPieces: Piece,
        participants: Map<PlayerId, Participant> = defaultParticipants(),
    ): GameState {
        val pieces = suppliedPieces.associateBy(Piece::id).toMutableMap()
        listOf(
            piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0)),
            piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
        ).forEach { king ->
            if (king.id !in pieces) pieces[king.id] = king
        }
        return GameState(
            position = Position(pieces),
            participants = participants,
            armies = defaultArmies(),
            turn = Turn(PlayerId.WHITE, ply = 1),
            phase = GamePhase.InProgress,
        )
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
