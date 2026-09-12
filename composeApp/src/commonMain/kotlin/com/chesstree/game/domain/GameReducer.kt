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
    data class Applied(
        val state: GameState,
        val move: Move,
    ) : MoveReduction

    data class Rejected(
        val reason: MoveRejectionReason,
    ) : MoveReduction
}

/** The single immutable state-transition entry point for player move intents. */
object GameReducer {
    fun reduce(
        state: GameState,
        intent: MoveIntent,
    ): MoveReduction {
        val turn = state.turn
        if (state.phase != GamePhase.InProgress || turn == null) {
            return MoveReduction.Rejected(MoveRejectionReason.GAME_FINISHED)
        }
        if (intent.actor != turn.player) {
            return MoveReduction.Rejected(MoveRejectionReason.NOT_ACTORS_TURN)
        }

        val piece = state.position.pieces.values
            .firstOrNull { candidate -> candidate.coordinate == intent.from }
            ?: return MoveReduction.Rejected(MoveRejectionReason.NO_PIECE_AT_SOURCE)
        if (state.armies.getValue(piece.army).controller != intent.actor) {
            return MoveReduction.Rejected(MoveRejectionReason.PIECE_NOT_CONTROLLED)
        }

        val destinationCandidates = LegalMoveGenerator.legalMoves(state, piece.id)
            .filter { move -> move.from == intent.from && move.to == intent.to }
        if (destinationCandidates.isEmpty()) {
            return MoveReduction.Rejected(MoveRejectionReason.ILLEGAL_MOVE)
        }

        val requiresPromotion = destinationCandidates.any { move -> move.promotion != null }
        if (requiresPromotion && intent.promotion == null) {
            return MoveReduction.Rejected(MoveRejectionReason.PROMOTION_REQUIRED)
        }
        if (!requiresPromotion && intent.promotion != null) {
            return MoveReduction.Rejected(MoveRejectionReason.UNEXPECTED_PROMOTION)
        }
        val move = destinationCandidates.singleOrNull { candidate ->
            candidate.promotion == intent.promotion
        } ?: return MoveReduction.Rejected(MoveRejectionReason.ILLEGAL_MOVE)

        val nextState = GameState(
            position = LegalMoveGenerator.applyToPosition(state.position, move),
            participants = state.participants,
            armies = state.armies,
            turn = Turn(
                player = nextActivePlayer(state, turn.player),
                ply = turn.ply + 1,
            ),
            phase = state.phase,
        )
        return MoveReduction.Applied(state = nextState, move = move)
    }

    private fun nextActivePlayer(
        state: GameState,
        current: PlayerId,
    ): PlayerId {
        val players = PlayerId.entries
        for (offset in 1..players.size) {
            val candidate = players[(current.ordinal + offset) % players.size]
            if (state.participants.getValue(candidate).status == ParticipantStatus.Active) {
                return candidate
            }
        }
        error("An in-progress game must contain an active participant")
    }
}
