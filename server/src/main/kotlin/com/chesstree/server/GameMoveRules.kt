package com.chesstree.server

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import java.util.UUID

sealed interface MoveEvaluation {
    data class Accepted(val move: GameMoveRecord, val finished: Boolean) : MoveEvaluation
    data object Duplicate : MoveEvaluation
    data object Stale : MoveEvaluation
    data object NotActive : MoveEvaluation
    data object NotParticipant : MoveEvaluation
    data object NotTurn : MoveEvaluation
    data object IllegalMove : MoveEvaluation
    data object CommandConflict : MoveEvaluation
    data object UndoPending : MoveEvaluation
}

fun evaluateMove(
    state: GameStateRecord,
    userId: UUID,
    command: GameMoveCommand,
): MoveEvaluation {
    val player = state.game.players.firstOrNull { it.user.id == userId }
        ?: return MoveEvaluation.NotParticipant
    state.moves.firstOrNull { it.commandId == command.commandId }?.let { existing ->
        return if (
            existing.userId == userId &&
            existing.expectedRevision == command.expectedRevision &&
            existing.intent.from == command.from &&
            existing.intent.to == command.to &&
            existing.intent.promotion == command.promotion
        ) {
            MoveEvaluation.Duplicate
        } else {
            MoveEvaluation.CommandConflict
        }
    }
    if (state.undoRequest != null) return MoveEvaluation.UndoPending
    if (state.game.status != GameStatus.ACTIVE || player.color == null) return MoveEvaluation.NotActive
    if (command.expectedRevision != state.revision) return MoveEvaluation.Stale
    val session = checkNotNull(
        GameSession.replay(
            StandardGame.scenario,
            state.moves.map(GameMoveRecord::intent)
        )
    ) {
        "Stored move history is invalid"
    }
    if (session.state.phase is GamePhase.Finished) return MoveEvaluation.NotActive
    val actor = PlayerId.valueOf(player.color.name)
    if (session.state.turn?.player != actor) return MoveEvaluation.NotTurn
    val intent = MoveIntent(actor, command.from, command.to, command.promotion)
    return when (val result = session.apply(intent)) {
        SessionMoveResult.Rejected -> MoveEvaluation.IllegalMove
        is SessionMoveResult.Applied -> MoveEvaluation.Accepted(
            move = GameMoveRecord(
                commandId = command.commandId,
                userId = userId,
                expectedRevision = command.expectedRevision,
                intent = result.session.moves.last(),
            ),
            finished = result.session.state.phase is GamePhase.Finished,
        )
    }
}
