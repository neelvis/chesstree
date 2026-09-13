package com.chesstree.game.domain.session

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.pieceAppearance
import com.chesstree.game.domain.scenario.GameScenario

data class CapturedPiece(
    val id: String,
    val type: PieceType,
    val army: ArmyColor,
    val bodyArmy: ArmyColor,
    val capturedByArmy: ArmyColor,
)

data class GameSession(
    val scenario: GameScenario,
    val state: GameState = scenario.initialState,
    val moves: List<MoveIntent> = emptyList(),
    val capturedPieces: List<CapturedPiece> = emptyList(),
) {
    fun apply(intent: MoveIntent): SessionMoveResult {
        val reduction = GameReducer.reduce(state, intent)
        if (reduction is MoveReduction.Rejected) {
            return SessionMoveResult.Rejected
        }
        reduction as MoveReduction.Applied

        val captured = capturedPiece(state, reduction.move)
        val appliedIntent = MoveIntent(
            actor = reduction.move.actor,
            from = reduction.move.from,
            to = reduction.move.to,
            promotion = reduction.move.promotion,
        )
        return SessionMoveResult.Applied(
            copy(
                state = reduction.state,
                moves = moves + appliedIntent,
                capturedPieces = capturedPieces + listOfNotNull(captured),
            ),
        )
    }

    companion object {
        fun replay(scenario: GameScenario, moves: List<MoveIntent>): GameSession? {
            var state = scenario.initialState
            val capturedPieces = mutableListOf<CapturedPiece>()
            moves.forEach { intent ->
                val reduction = GameReducer.reduce(state, intent)
                if (reduction !is MoveReduction.Applied) return null
                capturedPiece(state, reduction.move)?.let(capturedPieces::add)
                state = reduction.state
            }
            return GameSession(
                scenario = scenario,
                state = state,
                moves = moves.toList(),
                capturedPieces = capturedPieces,
            )
        }
    }
}

private fun capturedPiece(state: GameState, move: com.chesstree.game.domain.Move): CapturedPiece? {
    val movingPiece = state.position.pieces.getValue(move.pieceId)
    return move.capturedPieceId?.let { capturedPieceId ->
        val piece = state.position.pieces.getValue(capturedPieceId)
        val appearance = state.pieceAppearance(capturedPieceId)
        CapturedPiece(
            id = piece.id.value,
            type = piece.type,
            army = appearance.baseColor,
            bodyArmy = appearance.bodyColor,
            capturedByArmy = movingPiece.army,
        )
    }
}

sealed interface SessionMoveResult {
    data class Applied(val session: GameSession) : SessionMoveResult
    data object Rejected : SessionMoveResult
}
