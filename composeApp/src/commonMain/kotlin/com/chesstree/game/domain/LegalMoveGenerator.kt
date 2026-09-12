package com.chesstree.game.domain

/** Generates deterministic moves from immutable state without platform dependencies. */
object LegalMoveGenerator {
    fun pseudoLegalMoves(state: GameState): List<Move> =
        state.turn?.player?.let { pseudoLegalMoves(state, it) }.orEmpty()

    fun legalMoves(state: GameState): List<Move> =
        state.turn?.player?.let { legalMoves(state, it) }.orEmpty()

    fun legalMoves(state: GameState, player: PlayerId): List<Move> {
        if (state.phase != GamePhase.InProgress ||
            state.participants.getValue(player).status != ParticipantStatus.Active
        ) return emptyList()
        return pseudoLegalMoves(state, player).filterNot { move ->
            isKingInCheck(state.withPosition(applyToPosition(state.position, move)), player)
        }
    }

    fun legalMoves(state: GameState, pieceId: PieceId): List<Move> =
        legalMoves(state).filter { it.pieceId == pieceId }

    fun isKingInCheck(state: GameState, player: PlayerId): Boolean =
        attackingPlayers(state, player).isNotEmpty()

    internal fun attackingPlayers(state: GameState, player: PlayerId): Set<PlayerId> {
        val kingSquares = state.position.pieces.values
            .filter { it.type == PieceType.KING && state.controllerOf(it) == player }
            .mapTo(hashSetOf(), Piece::coordinate)
        if (kingSquares.isEmpty()) return emptySet()
        return state.position.pieces.values.asSequence()
            .filter { state.isActiveAttacker(it, player) }
            .filter { piece -> attackCoordinates(state.position, piece).any(kingSquares::contains) }
            .map { piece -> state.controllerOf(piece) }
            .toSet()
    }

    internal fun applyToPosition(position: Position, move: Move): Position {
        val movingPiece = position.pieces.getValue(move.pieceId)
        val pieces = position.pieces.toMutableMap()
        move.capturedPieceId?.let(pieces::remove)
        pieces[move.pieceId] = movingPiece.copy(
            type = move.promotion?.pieceType ?: movingPiece.type,
            coordinate = move.to,
            hasMoved = true,
        )
        move.rookDisplacement?.let { displacement ->
            val rook = pieces.getValue(displacement.pieceId)
            check(rook.type == PieceType.ROOK && rook.coordinate == displacement.from)
            pieces[rook.id] = rook.copy(coordinate = displacement.to, hasMoved = true)
        }
        val affected = setOfNotNull(move.pieceId, move.capturedPieceId) +
                listOfNotNull(move.rookDisplacement?.pieceId)
        val rights = position.castlingRights.filterTo(linkedSetOf()) { right ->
            val kingMoved = movingPiece.type == PieceType.KING && movingPiece.army == right.army
            val tiedRookAffected = if (right.rookId != null) {
                right.rookId in affected
            } else {
                affected.any { id ->
                    position.pieces[id]?.let { it.type == PieceType.ROOK && it.army == right.army } == true
                }
            }
            !kingMoved && !tiedRookAffected
        }
        return Position(
            pieces = pieces,
            castlingRights = rights,
            enPassantTargets = position.enPassantTargets.values.filter { it.pawnId in pieces },
        )
    }

    private fun pseudoLegalMoves(state: GameState, player: PlayerId): List<Move> {
        if (state.phase != GamePhase.InProgress ||
            state.participants.getValue(player).status != ParticipantStatus.Active
        ) return emptyList()
        val ply = state.turn?.ply ?: return emptyList()
        return state.position.pieces.values.asSequence()
            .filter { state.controllerOf(it) == player }
            .sortedBy { it.id.value }
            .flatMap { piece -> pieceMoves(state, piece, player, ply).asSequence() }
            .distinct()
            .toList()
    }

    private fun pieceMoves(
        state: GameState,
        piece: Piece,
        player: PlayerId,
        ply: Int,
    ): List<Move> {
        val directions = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
        return when (piece.type) {
            PieceType.ROOK, PieceType.BISHOP, PieceType.QUEEN ->
                slidingMoves(state, piece, player, ply, directions)
            PieceType.PAWN -> pawnMoves(state, piece, player, ply, directions)
            PieceType.KNIGHT -> jumpingMoves(state, piece, player, ply, directions)
            PieceType.KING -> jumpingMoves(state, piece, player, ply, directions) +
                    castlingMoves(state, piece, player, ply)
        }
    }

    private fun slidingMoves(
        state: GameState,
        piece: Piece,
        player: PlayerId,
        ply: Int,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.forEach { direction ->
            for (target in direction.route.drop(1)) {
                val occupant = state.pieceAt(target)
                when {
                    occupant == null -> addMovesForTarget(piece, player, ply, target, null)
                    state.canCapture(player, occupant) -> {
                        addMovesForTarget(piece, player, ply, target, occupant)
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
        player: PlayerId,
        ply: Int,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.forEach { direction ->
            val occupant = state.pieceAt(direction.target)
            if (occupant == null || state.canCapture(player, occupant)) {
                addMovesForTarget(piece, player, ply, direction.target, occupant)
            }
        }
    }

    private fun pawnMoves(
        state: GameState,
        piece: Piece,
        player: PlayerId,
        ply: Int,
        directions: List<MovementDirection>,
    ): List<Move> = buildList {
        directions.filter { it.kind == DirectionKind.MOVE }.forEach { direction ->
            val maximumDistance = if (piece.hasMoved) 1 else 2
            for (target in direction.route.drop(1).take(maximumDistance)) {
                if (state.pieceAt(target) != null) break
                addMovesForTarget(piece, player, ply, target, null)
            }
        }
        directions.filter { it.kind == DirectionKind.CAPTURE }.forEach { direction ->
            val occupant = state.pieceAt(direction.target)
            if (occupant != null && state.canCapture(player, occupant)) {
                addMovesForTarget(piece, player, ply, direction.target, occupant)
            } else if (occupant == null) {
                state.position.enPassantTargets.values.asSequence()
                    .filter { player in it.eligiblePlayers && it.captureCoordinate == direction.target }
                    .mapNotNull { state.position.pieces[it.pawnId] }
                    .filter { it.type == PieceType.PAWN && state.canCapture(player, it) }
                    .sortedBy { it.id.value }
                    .forEach { pawn ->
                        add(
                            Move(
                                ply = ply,
                                actor = player,
                                pieceId = piece.id,
                                from = piece.coordinate,
                                to = direction.target,
                                type = MoveType.EN_PASSANT,
                                capturedPieceId = pawn.id,
                            ),
                        )
                    }
            }
        }
    }

    private fun castlingMoves(
        state: GameState,
        king: Piece,
        player: PlayerId,
        ply: Int,
    ): List<Move> {
        if (king.hasMoved || isKingInCheck(state, player)) return emptyList()
        return state.position.castlingRights.filter { it.army == king.army }
            .sortedBy { it.side.ordinal }
            .mapNotNull { right ->
                val rook = resolveCastlingRook(state, king, right) ?: return@mapNotNull null
                if (rook.hasMoved || state.controllerOf(rook) != player) return@mapNotNull null
                val route = ThreePlayerBoardTopology.orthogonalRays(king.coordinate)
                    .firstOrNull { rook.coordinate in it.drop(1) } ?: return@mapNotNull null
                val rookIndex = route.indexOf(rook.coordinate)
                if (rookIndex < 3 || route.subList(1, rookIndex).any { state.pieceAt(it) != null }) {
                    return@mapNotNull null
                }
                val through = route[1]
                val destination = route[2]
                if (isKingInCheck(state.withPosition(moveKingOnly(state.position, king, through)), player)) {
                    return@mapNotNull null
                }
                Move(
                    ply = ply,
                    actor = player,
                    pieceId = king.id,
                    from = king.coordinate,
                    to = destination,
                    type = MoveType.CASTLING,
                    rookDisplacement = RookDisplacement(rook.id, rook.coordinate, through),
                )
            }
    }

    private fun resolveCastlingRook(state: GameState, king: Piece, right: CastlingRight): Piece? {
        right.rookId?.let { id ->
            return state.position.pieces[id]?.takeIf {
                it.type == PieceType.ROOK && it.army == right.army
            }
        }
        val candidates = state.position.pieces.values.filter { rook ->
            rook.type == PieceType.ROOK && rook.army == right.army && !rook.hasMoved &&
                    ThreePlayerBoardTopology.orthogonalRays(king.coordinate)
                        .any { ray -> rook.coordinate in ray.drop(1) }
        }.sortedBy { it.id.value }
        return when {
            candidates.size == 1 -> candidates.single()
            right.side == CastlingSide.QUEEN_SIDE -> candidates.firstOrNull()
            else -> candidates.lastOrNull()
        }
    }

    private fun moveKingOnly(position: Position, king: Piece, target: BoardCoordinate): Position =
        Position(
            pieces = position.pieces.toMutableMap().apply {
                this[king.id] = king.copy(coordinate = target, hasMoved = true)
            },
            castlingRights = position.castlingRights,
            enPassantTargets = position.enPassantTargets.values,
        )

    private fun MutableList<Move>.addMovesForTarget(
        piece: Piece,
        player: PlayerId,
        ply: Int,
        target: BoardCoordinate,
        captured: Piece?,
    ) {
        val promotions = if (piece.type == PieceType.PAWN && isPromotionTarget(piece, target)) {
            PromotionChoice.entries.map<PromotionChoice, PromotionChoice?> { it }
        } else listOf(null)
        promotions.forEach { promotion ->
            add(
                Move(
                    ply = ply,
                    actor = player,
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

    private fun isPromotionTarget(pawn: Piece, target: BoardCoordinate): Boolean =
        MovementDirections.forPiece(PieceType.PAWN, target, pawn.army)
            .none { it.kind == DirectionKind.MOVE }

    private fun attackCoordinates(position: Position, piece: Piece): Set<BoardCoordinate> {
        val directions = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
        return when (piece.type) {
            PieceType.ROOK, PieceType.BISHOP, PieceType.QUEEN -> buildSet {
                directions.forEach { direction ->
                    for (target in direction.route.drop(1)) {
                        add(target)
                        if (position.pieces.values.any { it.coordinate == target }) break
                    }
                }
            }
            PieceType.PAWN -> directions.filter { it.kind == DirectionKind.CAPTURE }
                .mapTo(linkedSetOf(), MovementDirection::target)
            PieceType.KING, PieceType.KNIGHT -> directions.mapTo(linkedSetOf(), MovementDirection::target)
        }
    }

    private fun GameState.isActiveAttacker(piece: Piece, target: PlayerId): Boolean {
        val controller = controllerOf(piece)
        if (controller == target) return false
        return when (participants.getValue(controller).status) {
            ParticipantStatus.Active -> true
            is ParticipantStatus.Stalemated -> piece.type == PieceType.KING
            is ParticipantStatus.Checkmated -> false
        }
    }

    private fun GameState.controllerOf(piece: Piece): PlayerId = armies.getValue(piece.army).controller
    private fun GameState.pieceAt(coordinate: BoardCoordinate): Piece? =
        position.pieces.values.firstOrNull { it.coordinate == coordinate }
    private fun GameState.canCapture(actor: PlayerId, target: Piece): Boolean =
        target.type != PieceType.KING && controllerOf(target) != actor
    private fun GameState.withPosition(position: Position): GameState = GameState(
        position = position,
        participants = participants,
        armies = armies,
        turn = turn,
        phase = phase,
    )
}
