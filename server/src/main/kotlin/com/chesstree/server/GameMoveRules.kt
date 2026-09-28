package com.chesstree.server

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.domain.bot.BotTrainingSample
import java.util.UUID

sealed interface MoveEvaluation {
    data class Accepted(
        val move: GameMoveRecord,
        val state: GameState,
        val capturedPieces: List<com.chesstree.game.domain.session.CapturedPiece>,
        val finished: Boolean,
    ) : MoveEvaluation
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
    isBotCommand: Boolean = false,
): MoveEvaluation {
    val belongsToGame = state.game.players.any { player ->
        if (isBotCommand) player.isBot && player.user.id == userId
        else !player.isBot && player.user.id == userId
    }
    if (!belongsToGame) return MoveEvaluation.NotParticipant
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
    if (state.game.status != GameStatus.ACTIVE) return MoveEvaluation.NotActive
    if (command.expectedRevision != state.revision) return MoveEvaluation.Stale
    if (command.expectedMoveCount != null && command.expectedMoveCount != state.moveOffset + state.moves.size) {
        return MoveEvaluation.Stale
    }
    val session = if (cachedState != null && state.capturedPieces != null) {
        GameSession(StandardGame.scenario, state = cachedState, capturedPieces = state.capturedPieces)
    } else {
        checkNotNull(GameSession.replay(StandardGame.scenario, state.moves.map(GameMoveRecord::intent))) {
            "Stored move history is invalid"
        }
    }
    val gameState = session.state
    if (gameState.phase is GamePhase.Finished) return MoveEvaluation.NotActive
    val actor = gameState.turn?.player ?: return MoveEvaluation.NotActive
    val turnSlot = state.game.players.firstOrNull { it.color?.name == actor.name }
        ?: return MoveEvaluation.NotActive
    if (turnSlot.isBot != isBotCommand || turnSlot.user.id != userId) return MoveEvaluation.NotTurn
    if (isBotCommand && command.trainingSample?.player != actor) return MoveEvaluation.IllegalMove
    if (!isBotCommand && command.trainingSample != null) return MoveEvaluation.IllegalMove
    val intent = MoveIntent(actor, command.from, command.to, command.promotion)
    return when (val result = session.apply(intent)) {
        SessionMoveResult.Rejected -> MoveEvaluation.IllegalMove
        is SessionMoveResult.Applied -> MoveEvaluation.Accepted(
            move = GameMoveRecord(
                commandId = command.commandId,
                userId = turnSlot.user.id,
                expectedRevision = command.expectedRevision,
                intent = result.session.moves.last(),
                trainingSample = command.trainingSample?.takeIf { isBotCommand },
            ),
            state = result.session.state,
            capturedPieces = result.session.capturedPieces,
            finished = result.session.state.phase is GamePhase.Finished,
        )
    }
}
