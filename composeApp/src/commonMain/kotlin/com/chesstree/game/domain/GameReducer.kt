package com.chesstree.game.domain

enum class MoveRejectionReason {
    GAME_FINISHED,
    NOT_ACTORS_TURN,
    NO_PIECE_AT_SOURCE,
    PIECE_NOT_CONTROLLED,
    PROMOTION_REQUIRED,
    UNEXPECTED_PROMOTION,
    ILLEGAL_MOVE,
}

sealed interface MoveReduction {
    data class Applied(val state: GameState, val move: Move) : MoveReduction
    data class Rejected(val reason: MoveRejectionReason) : MoveReduction
}

/** The single immutable state-transition entry point for player move intents. */
object GameReducer {
    fun reduce(state: GameState, intent: MoveIntent): MoveReduction {
        val turn = state.turn
        if (state.phase != GamePhase.InProgress || turn == null) {
            return MoveReduction.Rejected(MoveRejectionReason.GAME_FINISHED)
        }
        if (intent.actor != turn.player) {
            return MoveReduction.Rejected(MoveRejectionReason.NOT_ACTORS_TURN)
        }
        val piece = state.position.pieces.values.firstOrNull { it.coordinate == intent.from }
            ?: return MoveReduction.Rejected(MoveRejectionReason.NO_PIECE_AT_SOURCE)
        if (state.armies.getValue(piece.army).controller != intent.actor) {
            return MoveReduction.Rejected(MoveRejectionReason.PIECE_NOT_CONTROLLED)
        }
        val candidates = LegalMoveGenerator.legalMoves(state, piece.id)
            .filter { it.from == intent.from && it.to == intent.to }
        if (candidates.isEmpty()) return MoveReduction.Rejected(MoveRejectionReason.ILLEGAL_MOVE)
        val requiresPromotion = candidates.any { it.promotion != null }
        if (requiresPromotion && intent.promotion == null) {
            return MoveReduction.Rejected(MoveRejectionReason.PROMOTION_REQUIRED)
        }
        if (!requiresPromotion && intent.promotion != null) {
            return MoveReduction.Rejected(MoveRejectionReason.UNEXPECTED_PROMOTION)
        }
        val move = candidates.singleOrNull { it.promotion == intent.promotion }
            ?: return MoveReduction.Rejected(MoveRejectionReason.ILLEGAL_MOVE)

        val movedPosition = updateSpecialMoveState(state, piece, move)
        val next = nextActivePlayer(state.participants, turn.player)
        val provisional = GameState(
            position = movedPosition,
            participants = state.participants,
            armies = state.armies,
            turn = Turn(next, turn.ply + 1),
            phase = GamePhase.InProgress,
        )
        return MoveReduction.Applied(
            resolveForcedOutcomes(
                initial = provisional,
                lastMover = move.actor,
                stateBeforeMove = state,
            ),
            move,
        )
    }

    private fun updateSpecialMoveState(state: GameState, movingPiece: Piece, move: Move): Position {
        val applied = LegalMoveGenerator.applyToPosition(state.position, move)
        val retainedTargets = applied.enPassantTargets.values.mapNotNull { target ->
            val pawn = applied.pieces[target.pawnId] ?: return@mapNotNull null
            val controller = state.armies.getValue(pawn.army).controller
            if (controller == move.actor) return@mapNotNull null
            val remaining = target.eligiblePlayers - move.actor
            if (remaining.isEmpty()) null else EnPassantTarget(
                pawnId = target.pawnId,
                captureCoordinate = target.captureCoordinate,
                eligiblePlayers = remaining,
            )
        }.toMutableList()

        if (movingPiece.type == PieceType.PAWN) {
            val route = MovementDirections.forPiece(
                PieceType.PAWN,
                movingPiece.coordinate,
                movingPiece.army,
            ).firstOrNull { direction ->
                direction.kind == DirectionKind.MOVE &&
                        direction.route.getOrNull(2) == move.to
            }
            if (route != null) {
                val eligible = state.participants.values.asSequence()
                    .filter { it.id != move.actor && it.status == ParticipantStatus.Active }
                    .map(Participant::id)
                    .toSet()
                if (eligible.isNotEmpty()) {
                    retainedTargets.removeAll { it.pawnId == movingPiece.id }
                    retainedTargets += EnPassantTarget(
                        pawnId = movingPiece.id,
                        captureCoordinate = route.route[1],
                        eligiblePlayers = eligible,
                    )
                }
            }
        }
        return Position(
            pieces = applied.pieces,
            castlingRights = applied.castlingRights,
            enPassantTargets = retainedTargets,
        )
    }

    private fun resolveForcedOutcomes(
        initial: GameState,
        lastMover: PlayerId,
        stateBeforeMove: GameState,
    ): GameState {
        var state = initial
        repeat(PlayerId.entries.size) {
            val turn = state.turn ?: return state
            if (LegalMoveGenerator.legalMoves(state, turn.player).isNotEmpty()) return state
            state = if (LegalMoveGenerator.isKingInCheck(state, turn.player)) {
                applyCheckmate(state, turn.player, lastMover, stateBeforeMove)
            } else {
                applyStalemate(state, turn.player)
            }
            if (state.phase is GamePhase.Finished) return state
        }
        return state
    }

    private fun applyCheckmate(
        state: GameState,
        defeated: PlayerId,
        lastMover: PlayerId,
        stateBeforeMove: GameState,
    ): GameState {
        val turn = checkNotNull(state.turn)
        val attackers = LegalMoveGenerator.attackingPlayers(state, defeated)
        val author = MateAttributionPolicy.author(
            attackers = attackers,
            attackersBeforeMove = LegalMoveGenerator.attackingPlayers(stateBeforeMove, defeated),
            lastMover = lastMover,
        )
        val participants = state.participants.toMutableMap().apply {
            this[defeated] = Participant(
                defeated,
                ParticipantStatus.Checkmated(by = author, atPly = turn.ply),
            )
        }
        val armies = state.armies.mapValues { (army, control) ->
            if (control.controller == defeated) ArmyControl(army, author) else control
        }
        val pieces = state.position.pieces.filterValues { piece ->
            !(piece.type == PieceType.KING && piece.army.originalPlayer == defeated)
        }
        val position = Position(
            pieces = pieces,
            castlingRights = state.position.castlingRights
                .filterNot { it.army.originalPlayer == defeated }
                .toSet(),
            enPassantTargets = activeEnPassantTargets(
                targets = state.position.enPassantTargets.values.filter { it.pawnId in pieces },
                participants = participants,
            ),
        )
        finishIfOnlyOneActive(position, participants, armies, defeated)?.let { return it }
        val next = nextActivePlayer(participants, defeated)
        return GameState(
            position = position,
            participants = participants,
            armies = armies,
            turn = Turn(next, turn.ply),
            phase = GamePhase.InProgress,
        )
    }

    private fun applyStalemate(state: GameState, player: PlayerId): GameState {
        val turn = checkNotNull(state.turn)
        val participants = state.participants.toMutableMap().apply {
            this[player] = Participant(player, ParticipantStatus.Stalemated(turn.ply))
        }
        val stalemateCount = participants.values.count { it.status is ParticipantStatus.Stalemated }
        if (stalemateCount >= 2) {
            return GameState(
                position = positionAfterElimination(state.position, participants),
                participants = participants,
                armies = state.armies,
                turn = null,
                phase = GamePhase.Finished(GameOutcome.ThreeWayDraw(DrawReason.SECOND_STALEMATE)),
            )
        }
        val position = positionAfterElimination(state.position, participants)
        finishIfOnlyOneActive(position, participants, state.armies, player)?.let { return it }
        val next = nextActivePlayer(participants, player)
        return GameState(
            position = position,
            participants = participants,
            armies = state.armies,
            turn = Turn(next, turn.ply),
            phase = GamePhase.InProgress,
        )
    }

    private fun positionAfterElimination(
        position: Position,
        participants: Map<PlayerId, Participant>,
    ): Position = Position(
        pieces = position.pieces,
        castlingRights = position.castlingRights,
        enPassantTargets = activeEnPassantTargets(
            position.enPassantTargets.values,
            participants,
        ),
    )

    private fun activeEnPassantTargets(
        targets: Collection<EnPassantTarget>,
        participants: Map<PlayerId, Participant>,
    ): List<EnPassantTarget> = targets.mapNotNull { target ->
        val eligible = target.eligiblePlayers.filterTo(linkedSetOf()) { player ->
            participants.getValue(player).status == ParticipantStatus.Active
        }
        if (eligible.isEmpty()) null else EnPassantTarget(
            pawnId = target.pawnId,
            captureCoordinate = target.captureCoordinate,
            eligiblePlayers = eligible,
        )
    }

    private fun finishIfOnlyOneActive(
        position: Position,
        participants: Map<PlayerId, Participant>,
        armies: Map<ArmyColor, ArmyControl>,
        lastEliminated: PlayerId,
    ): GameState? {
        val active = participants.values.filter { it.status == ParticipantStatus.Active }
        if (active.size != 1) return null
        val previousEliminated = participants.values.single { participant ->
            participant.id != lastEliminated && participant.status != ParticipantStatus.Active
        }
        return GameState(
            position = position,
            participants = participants,
            armies = armies,
            turn = null,
            phase = GamePhase.Finished(
                GameOutcome.Ranked(
                    first = active.single().id,
                    second = lastEliminated,
                    third = previousEliminated.id,
                ),
            ),
        )
    }

    private fun nextActivePlayer(
        participants: Map<PlayerId, Participant>,
        current: PlayerId,
    ): PlayerId {
        val players = PlayerId.entries
        for (offset in 1..players.size) {
            val candidate = players[(current.ordinal + offset) % players.size]
            if (participants.getValue(candidate).status == ParticipantStatus.Active) return candidate
        }
        error("An in-progress game must contain an active participant")
    }
}
