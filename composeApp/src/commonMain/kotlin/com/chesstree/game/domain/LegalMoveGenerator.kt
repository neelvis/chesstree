package com.chesstree.game.domain

/** Generates deterministic moves for the current participant without UI state. */
object LegalMoveGenerator {
    fun pseudoLegalMoves(state: GameState): List<Move> {
        val turn = state.turn ?: return emptyList()
        if (state.phase != GamePhase.InProgress) return emptyList()

        return state.position.pieces.values
            .asSequence()
            .filter { piece -> state.controllerOf(piece) == turn.player }
            .sortedBy { piece -> piece.id.value }
            .flatMap { piece -> pseudoLegalMoves(state, piece, turn).asSequence() }
            .distinct()
            .toList()
    }

    fun legalMoves(state: GameState): List<Move> =
        pseudoLegalMoves(state).filterNot { move ->
            val positionAfterMove = applyToPosition(state.position, move)
            isKingAttacked(
                state = state.withPosition(positionAfterMove),
                player = move.actor,
            )
        }

    fun legalMoves(
        state: GameState,
        pieceId: PieceId,
    ): List<Move> = legalMoves(state).filter { move -> move.pieceId == pieceId }

    fun isKingInCheck(
        state: GameState,
        player: PlayerId,
    ): Boolean = isKingAttacked(state, player)

    internal fun applyToPosition(
        position: Position,
        move: Move,
    ): Position {
        val movingPiece = position.pieces.getValue(move.pieceId)
        val pieces = position.pieces.toMutableMap()
        move.capturedPieceId?.let(pieces::remove)
        pieces[move.pieceId] = movingPiece.copy(
            type = move.promotion?.pieceType ?: movingPiece.type,
            coordinate = move.to,
            hasMoved = true,
        )

        return Position(
            pieces = pieces,
            castlingRights = position.castlingRights,
            enPassantTarget = position.enPassantTarget?.takeIf { target ->
                target.pawnId in pieces
            },
        )
    }

    private fun pseudoLegalMoves(
        state: GameState,
        piece: Piece,
        turn: Turn,
    ): List<Move> {
        val directions = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
        return when (piece.type) {
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.QUEEN,
                -> slidingMoves(state, piece, turn, directions)

            PieceType.PAWN -> pawnMoves(state, piece, turn, directions)
            PieceType.KING,
            PieceType.KNIGHT,
                -> jumpingMoves(state, piece, turn, directions)
        }
    }

    private fun slidingMoves(
        state: GameState,
        piece: Piece,
        turn: Turn,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.forEach { direction ->
            for (target in direction.route.drop(1)) {
                val occupant = state.pieceAt(target)
                when {
                    occupant == null -> addMovesForTarget(piece, turn, target, null)
                    state.canCapture(turn.player, occupant) -> {
                        addMovesForTarget(piece, turn, target, occupant)
                        break
                    }

                    else -> break
                }
            }
        }
    }

    private fun jumpingMoves(
        state: GameState,
        piece: Piece,
        turn: Turn,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.forEach { direction ->
            val occupant = state.pieceAt(direction.target)
            if (occupant == null || state.canCapture(turn.player, occupant)) {
                addMovesForTarget(piece, turn, direction.target, occupant)
            }
        }
    }

    private fun pawnMoves(
        state: GameState,
        piece: Piece,
        turn: Turn,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.filter { direction -> direction.kind == DirectionKind.MOVE }
            .forEach { direction ->
                val maximumDistance = if (piece.hasMoved) 1 else 2
                for (target in direction.route.drop(1).take(maximumDistance)) {
                    if (state.pieceAt(target) != null) break
                    addMovesForTarget(piece, turn, target, null)
                }
            }

        directions.filter { direction -> direction.kind == DirectionKind.CAPTURE }
            .forEach { direction ->
                val occupant = state.pieceAt(direction.target)
                if (occupant != null && state.canCapture(turn.player, occupant)) {
                    addMovesForTarget(piece, turn, direction.target, occupant)
                }
            }
    }

    private fun MutableList<Move>.addMovesForTarget(
        piece: Piece,
        turn: Turn,
        target: BoardCoordinate,
        captured: Piece?,
    ) {
        val promotions = if (piece.type == PieceType.PAWN && isPromotionTarget(piece, target)) {
            PromotionChoice.entries.map<PromotionChoice, PromotionChoice?> { it }
        } else {
            listOf(null)
        }
        promotions.forEach { promotion ->
            add(
                Move(
                    ply = turn.ply,
                    actor = turn.player,
                    pieceId = piece.id,
                    from = piece.coordinate,
                    to = target,
                    type = if (captured == null) MoveType.QUIET else MoveType.CAPTURE,
                    capturedPieceId = captured?.id,
                    promotion = promotion,
                ),
            )
        }
    }

    private fun isPromotionTarget(
        pawn: Piece,
        target: BoardCoordinate,
    ): Boolean = MovementDirections.forPiece(PieceType.PAWN, target, pawn.army)
        .none { direction -> direction.kind == DirectionKind.MOVE }

    private fun isKingAttacked(
        state: GameState,
        player: PlayerId,
    ): Boolean {
        val kings = state.position.pieces.values.filter { piece ->
            piece.type == PieceType.KING && state.controllerOf(piece) == player
        }
        if (kings.isEmpty()) return false

        val attackedCoordinates = state.position.pieces.values
            .asSequence()
            .filter { piece ->
                val controller = state.controllerOf(piece)
                val controllerStatus = state.participants.getValue(controller).status
                controller != player && when (controllerStatus) {
                    ParticipantStatus.Active -> true
                    is ParticipantStatus.Stalemated -> piece.type == PieceType.KING
                    is ParticipantStatus.Checkmated -> false
                }
            }
            .flatMap { piece -> attackCoordinates(state.position, piece).asSequence() }
            .toSet()
        return kings.any { king -> king.coordinate in attackedCoordinates }
    }

    private fun attackCoordinates(
        position: Position,
        piece: Piece,
    ): Set<BoardCoordinate> {
        val directions = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
        return when (piece.type) {
            PieceType.ROOK,
            PieceType.BISHOP,
            PieceType.QUEEN,
                -> buildSet {
                    directions.forEach { direction ->
                        for (target in direction.route.drop(1)) {
                            add(target)
                            if (position.pieces.values.any { it.coordinate == target }) {
                                break
                            }
                        }
                    }
                }

            PieceType.PAWN -> directions
                .filter { direction -> direction.kind == DirectionKind.CAPTURE }
                .mapTo(linkedSetOf(), MovementDirection::target)

            PieceType.KING,
            PieceType.KNIGHT,
                -> directions.mapTo(linkedSetOf(), MovementDirection::target)
        }
    }

    private fun GameState.controllerOf(piece: Piece): PlayerId =
        armies.getValue(piece.army).controller

    private fun GameState.pieceAt(coordinate: BoardCoordinate): Piece? =
        position.pieces.values.firstOrNull { piece -> piece.coordinate == coordinate }

    private fun GameState.canCapture(
        actor: PlayerId,
        target: Piece,
    ): Boolean = target.type != PieceType.KING && controllerOf(target) != actor

    private fun GameState.withPosition(position: Position): GameState = GameState(
        position = position,
        participants = participants,
        armies = armies,
        turn = turn,
        phase = phase,
    )
}
