package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GameEngineTest {
    @Test
    fun mateAttributionPrefersTheLastMoverAmongMultipleAttackers() {
        assertEquals(
            PlayerId.BLACK,
            MateAttributionPolicy.author(
                attackers = setOf(PlayerId.WHITE, PlayerId.BLACK),
                attackersBeforeMove = emptySet(),
                lastMover = PlayerId.BLACK,
            ),
        )
        assertEquals(
            PlayerId.WHITE,
            MateAttributionPolicy.author(
                attackers = setOf(PlayerId.WHITE, PlayerId.BLACK),
                attackersBeforeMove = setOf(PlayerId.WHITE, PlayerId.BLACK),
                lastMover = PlayerId.RED,
            ),
        )
        assertEquals(
            PlayerId.BLACK,
            MateAttributionPolicy.author(
                attackers = setOf(PlayerId.WHITE, PlayerId.BLACK),
                attackersBeforeMove = setOf(PlayerId.WHITE),
                lastMover = PlayerId.RED,
            ),
        )
    }

    @Test
    fun castlingMovesKingAndItsExplicitlyTiedRook() {
        val king = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 0, 0))
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 3, 0))
        val state = state(
            king,
            rook,
            piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
            rights = setOf(CastlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE, rook.id)),
        )

        val castling = LegalMoveGenerator.legalMoves(state).single { it.type == MoveType.CASTLING }
        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(state, MoveIntent(PlayerId.WHITE, king.coordinate, castling.to)),
        )

        assertEquals(castling.to, applied.state.position.pieces.getValue(king.id).coordinate)
        assertEquals(castling.rookDisplacement?.to, applied.state.position.pieces.getValue(rook.id).coordinate)
        assertTrue(applied.state.position.castlingRights.isEmpty())
    }

    @Test
    fun castlingIsForbiddenThroughAnAttackedSquare() {
        val king = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 0, 0))
        val rook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 3, 0))
        val attacker = piece("red-rook", PieceType.ROOK, ArmyColor.RED, cell(0, 1, 3))
        val state = state(
            king,
            rook,
            attacker,
            piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
            rights = setOf(CastlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE, rook.id)),
        )

        assertFalse(LegalMoveGenerator.legalMoves(state).any { it.type == MoveType.CASTLING })
    }

    @Test
    fun enPassantCapturesThePawnFromItsLandingSquare() {
        val whitePawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, cell(1, 2, 1))
        val route = MovementDirections.forPiece(whitePawn.type, whitePawn.coordinate, whitePawn.army)
            .single { it.kind == DirectionKind.MOVE }
            .route
        val captureAt = route[1]
        val redOrigin = ThreePlayerBoardTopology.coordinates.first { origin ->
            origin !in route.take(3) && MovementDirections.forPiece(PieceType.PAWN, origin, ArmyColor.RED)
                .any { it.kind == DirectionKind.CAPTURE && it.target == captureAt }
        }
        val redPawn = piece("red-pawn", PieceType.PAWN, ArmyColor.RED, redOrigin, hasMoved = true)
        val initial = stateWithDefaultKings(whitePawn, redPawn)

        val afterDouble = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                initial,
                MoveIntent(PlayerId.WHITE, whitePawn.coordinate, route[2]),
            ),
        ).state
        val enPassant = LegalMoveGenerator.legalMoves(afterDouble).single { move ->
            move.type == MoveType.EN_PASSANT && move.pieceId == redPawn.id
        }
        val captured = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                afterDouble,
                MoveIntent(PlayerId.RED, redPawn.coordinate, enPassant.to),
            ),
        )

        assertFalse(whitePawn.id in captured.state.position.pieces)
        assertEquals(captureAt, captured.state.position.pieces.getValue(redPawn.id).coordinate)
        assertTrue(captured.state.position.enPassantTargets.isEmpty())
    }

    @Test
    fun eachOpponentGetsOnlyItsOwnNextTurnForEnPassant() {
        val pawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, cell(1, 2, 1))
        val route = MovementDirections.forPiece(pawn.type, pawn.coordinate, pawn.army)
            .single { it.kind == DirectionKind.MOVE }.route
        var current = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(stateWithDefaultKings(pawn), MoveIntent(PlayerId.WHITE, pawn.coordinate, route[2])),
        ).state
        assertEquals(setOf(PlayerId.RED, PlayerId.BLACK), current.position.enPassantTargets[pawn.id]?.eligiblePlayers)

        val redMove = LegalMoveGenerator.legalMoves(current).first()
        current = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(current, MoveIntent(PlayerId.RED, redMove.from, redMove.to, redMove.promotion)),
        ).state
        assertEquals(setOf(PlayerId.BLACK), current.position.enPassantTargets[pawn.id]?.eligiblePlayers)

        val blackMove = LegalMoveGenerator.legalMoves(current).first()
        current = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(current, MoveIntent(PlayerId.BLACK, blackMove.from, blackMove.to, blackMove.promotion)),
        ).state
        assertFalse(pawn.id in current.position.enPassantTargets)
    }

    @Test
    fun automaticStalemateRemovesThatPlayerFromEnPassantEligibility() {
        val pawn = piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, cell(1, 2, 1))
        val route = MovementDirections.forPiece(pawn.type, pawn.coordinate, pawn.army)
            .single { it.kind == DirectionKind.MOVE }.route
        val game = state(
            pawn,
            piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
        )

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, pawn.coordinate, route[2]),
            ),
        )

        assertIs<ParticipantStatus.Stalemated>(
            applied.state.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(
            setOf(PlayerId.BLACK),
            applied.state.position.enPassantTargets.getValue(pawn.id).eligiblePlayers,
        )
    }

    @Test
    fun movingPinnedPieceIsRejectedBySelfCheckFiltering() {
        val king = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 0, 0))
        val shield = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 0))
        val attacker = piece("red-rook", PieceType.ROOK, ArmyColor.RED, cell(0, 3, 0))
        val game = state(
            king,
            shield,
            attacker,
            piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
        )

        assertFalse(LegalMoveGenerator.legalMoves(game).any {
            it.pieceId == shield.id && it.to == cell(0, 1, 1)
        })
    }

    @Test
    fun noLegalMovesWithoutCheckAutomaticallyMarksTheNextPlayerStalemated() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0))
        val whiteRook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val blackKing = piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0))
        val game = state(whiteKing, whiteRook, blackKing)

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteRook.coordinate, cell(0, 2, 1)),
            ),
        )

        assertIs<ParticipantStatus.Stalemated>(
            applied.state.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(PlayerId.BLACK, applied.state.turn?.player)
    }

    @Test
    fun checkmateAutomaticallyRemovesTheKingAndTransfersTheArmy() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 1, 0))
        val whiteQueen = piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, cell(0, 2, 1))
        val redKing = piece("red-king", PieceType.KING, ArmyColor.RED, cell(0, 3, 0))
        val blackKing = piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0))
        val game = state(whiteKing, whiteQueen, redKing, blackKing)

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteQueen.coordinate, cell(0, 2, 0)),
            ),
        )

        val checkmate = assertIs<ParticipantStatus.Checkmated>(
            applied.state.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(PlayerId.WHITE, checkmate.by)
        assertFalse(redKing.id in applied.state.position.pieces)
        assertEquals(PlayerId.WHITE, applied.state.armies.getValue(ArmyColor.RED).controller)
        assertEquals(PlayerId.BLACK, applied.state.turn?.player)
    }

    @Test
    fun secondStalemateAutomaticallyFinishesAsThreeWayDraw() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0))
        val whiteRook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val participants = PlayerId.entries.associateWith { player ->
            if (player == PlayerId.RED) {
                Participant(player, ParticipantStatus.Stalemated(atPly = 1))
            } else Participant(player)
        }
        val game = GameState(
            position = Position(listOf(whiteKing, whiteRook).associateBy(Piece::id)),
            participants = participants,
            armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            turn = Turn(PlayerId.WHITE, 2),
            phase = GamePhase.InProgress,
        )

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteRook.coordinate, cell(0, 2, 1)),
            ),
        )

        val finished = assertIs<GamePhase.Finished>(applied.state.phase)
        assertEquals(
            GameOutcome.ThreeWayDraw(DrawReason.SECOND_STALEMATE),
            finished.outcome,
        )
    }

    @Test
    fun capturingTheLastNonKingPieceAutomaticallyFinishesAsDraw() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 1, 1))
        val blackKnight = piece("black-knight", PieceType.KNIGHT, ArmyColor.BLACK, cell(0, 2, 1))
        val blackKing = piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0))
        val participants = mapOf(
            PlayerId.WHITE to Participant(PlayerId.WHITE),
            PlayerId.RED to Participant(
                PlayerId.RED,
                ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 1),
            ),
            PlayerId.BLACK to Participant(PlayerId.BLACK),
        )
        val armies = mapOf(
            ArmyColor.WHITE to ArmyControl(ArmyColor.WHITE, PlayerId.WHITE),
            ArmyColor.RED to ArmyControl(ArmyColor.RED, PlayerId.WHITE),
            ArmyColor.BLACK to ArmyControl(ArmyColor.BLACK, PlayerId.BLACK),
        )
        val game = GameState(
            position = Position(listOf(whiteKing, blackKnight, blackKing).associateBy(Piece::id)),
            participants = participants,
            armies = armies,
            turn = Turn(PlayerId.WHITE, 2),
            phase = GamePhase.InProgress,
        )

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteKing.coordinate, blackKnight.coordinate),
            ),
        )

        val finished = assertIs<GamePhase.Finished>(applied.state.phase)
        assertEquals(
            GameOutcome.TwoWayDraw(
                first = PlayerId.WHITE,
                second = PlayerId.BLACK,
                third = PlayerId.RED,
                reason = DrawReason.INSUFFICIENT_MATERIAL,
            ),
            finished.outcome,
        )
    }

    @Test
    fun kingAndKnightAgainstKingAutomaticallyFinishesAsDraw() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 1, 1))
        val blackPawn = piece("black-pawn", PieceType.PAWN, ArmyColor.BLACK, cell(0, 2, 1))
        val blackKnight = piece("black-knight", PieceType.KNIGHT, ArmyColor.BLACK, cell(4, 1, 1))
        val blackKing = piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0))
        val participants = mapOf(
            PlayerId.WHITE to Participant(PlayerId.WHITE),
            PlayerId.RED to Participant(
                PlayerId.RED,
                ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 1),
            ),
            PlayerId.BLACK to Participant(PlayerId.BLACK),
        )
        val armies = mapOf(
            ArmyColor.WHITE to ArmyControl(ArmyColor.WHITE, PlayerId.WHITE),
            ArmyColor.RED to ArmyControl(ArmyColor.RED, PlayerId.WHITE),
            ArmyColor.BLACK to ArmyControl(ArmyColor.BLACK, PlayerId.BLACK),
        )
        val game = GameState(
            position = Position(
                listOf(whiteKing, blackPawn, blackKnight, blackKing).associateBy(Piece::id),
            ),
            participants = participants,
            armies = armies,
            turn = Turn(PlayerId.WHITE, 2),
            phase = GamePhase.InProgress,
        )

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteKing.coordinate, blackPawn.coordinate),
            ),
        )

        assertEquals(PieceType.KNIGHT, applied.state.position.pieces.getValue(blackKnight.id).type)
        val finished = assertIs<GamePhase.Finished>(applied.state.phase)
        assertEquals(
            GameOutcome.TwoWayDraw(
                first = PlayerId.WHITE,
                second = PlayerId.BLACK,
                third = PlayerId.RED,
                reason = DrawReason.INSUFFICIENT_MATERIAL,
            ),
            finished.outcome,
        )
    }

    @Test
    fun checkmateThenStalemateRanksTheOnlyRemainingActivePlayer() {
        val whiteKing = piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0))
        val whiteRook = piece("white-rook", PieceType.ROOK, ArmyColor.WHITE, cell(0, 1, 1))
        val participants = mapOf(
            PlayerId.WHITE to Participant(PlayerId.WHITE),
            PlayerId.RED to Participant(
                PlayerId.RED,
                ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 1),
            ),
            PlayerId.BLACK to Participant(PlayerId.BLACK),
        )
        val armies = mapOf(
            ArmyColor.WHITE to ArmyControl(ArmyColor.WHITE, PlayerId.WHITE),
            ArmyColor.RED to ArmyControl(ArmyColor.RED, PlayerId.WHITE),
            ArmyColor.BLACK to ArmyControl(ArmyColor.BLACK, PlayerId.BLACK),
        )
        val game = GameState(
            position = Position(listOf(whiteKing, whiteRook).associateBy(Piece::id)),
            participants = participants,
            armies = armies,
            turn = Turn(PlayerId.WHITE, 2),
            phase = GamePhase.InProgress,
        )

        val applied = assertIs<MoveReduction.Applied>(
            GameReducer.reduce(
                game,
                MoveIntent(PlayerId.WHITE, whiteRook.coordinate, cell(0, 2, 1)),
            ),
        )

        val finished = assertIs<GamePhase.Finished>(applied.state.phase)
        assertEquals(
            GameOutcome.Ranked(
                first = PlayerId.WHITE,
                second = PlayerId.BLACK,
                third = PlayerId.RED,
            ),
            finished.outcome,
        )
    }

    private fun stateWithDefaultKings(vararg pieces: Piece): GameState = state(
        *pieces,
        piece("white-king", PieceType.KING, ArmyColor.WHITE, cell(0, 3, 0)),
        piece("red-king", PieceType.KING, ArmyColor.RED, cell(2, 3, 0)),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, cell(4, 3, 0)),
    )

    private fun state(
        vararg pieces: Piece,
        rights: Set<CastlingRight> = emptySet(),
    ): GameState = GameState(
        position = Position(pieces.associateBy(Piece::id), castlingRights = rights),
        participants = PlayerId.entries.associateWith(::Participant),
        armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
        turn = Turn(PlayerId.WHITE, 1),
        phase = GamePhase.InProgress,
    )

    private fun piece(
        id: String,
        type: PieceType,
        army: ArmyColor,
        at: BoardCoordinate,
        hasMoved: Boolean = false,
    ): Piece = Piece(PieceId(id), type, army, at, hasMoved)

    private fun cell(vertex: Int, column: Int, row: Int): BoardCoordinate =
        BoardCoordinate(vertex, column, row)
}
