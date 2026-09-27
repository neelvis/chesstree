package com.chesstree.server

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import java.util.UUID

sealed interface MoveEvaluation {
    data class Accepted(val move: GameMoveRecord, val state: GameState, val finished: Boolean) : MoveEvaluation
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
    cachedState: GameState? = null,
    existingCommand: GameMoveRecord? = null,
): MoveEvaluation {
    val player = state.game.players.firstOrNull { it.user.id == userId }
        ?: return MoveEvaluation.NotParticipant
    (existingCommand ?: state.moves.firstOrNull { it.commandId == command.commandId })?.let { existing ->
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
    if (command.expectedMoveCount != null && command.expectedMoveCount != state.moveOffset + state.moves.size) {
        return MoveEvaluation.Stale
    }
    val gameState = cachedState ?: checkNotNull(
        GameSession.replay(
            StandardGame.scenario,
            state.moves.map(GameMoveRecord::intent)
        )?.state
    ) {
        "Stored move history is invalid"
    }
    if (gameState.phase is GamePhase.Finished) return MoveEvaluation.NotActive
    val actor = PlayerId.valueOf(player.color.name)
    if (gameState.turn?.player != actor) return MoveEvaluation.NotTurn
    val intent = MoveIntent(actor, command.from, command.to, command.promotion)
    val session = GameSession(StandardGame.scenario, state = gameState)
    return when (val result = session.apply(intent)) {
        SessionMoveResult.Rejected -> MoveEvaluation.IllegalMove
        is SessionMoveResult.Applied -> MoveEvaluation.Accepted(
            move = GameMoveRecord(
                commandId = command.commandId,
                userId = userId,
                expectedRevision = command.expectedRevision,
                intent = result.session.moves.last(),
            ),
            state = result.session.state,
            finished = result.session.state.phase is GamePhase.Finished,
        )
    }
}
