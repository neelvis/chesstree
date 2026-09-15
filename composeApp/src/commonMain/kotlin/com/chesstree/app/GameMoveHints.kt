package com.chesstree.app

import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.PieceId
import com.chesstree.game.presentation.board.MoveHint
import com.chesstree.game.presentation.board.educationalMoveHintsFor
import com.chesstree.game.presentation.board.legalMoveHintsFor

internal data class SelectedMoveHints(
    val currentPossibleMoves: List<MoveHint>,
    val moveLines: List<MoveHint>,
)

internal fun movementHintsForSelection(
    state: GameState,
    selectedPieceId: String?,
    showCurrentPossibleMoves: Boolean,
    showMoveLines: Boolean,
): SelectedMoveHints {
    val pieceId = selectedPieceId?.let(::PieceId)
        ?: return SelectedMoveHints(emptyList(), emptyList())
    return SelectedMoveHints(
        currentPossibleMoves = if (showCurrentPossibleMoves) {
            legalMoveHintsFor(state, pieceId)
        } else {
            emptyList()
        },
        moveLines = if (showMoveLines) {
            educationalMoveHintsFor(state, pieceId)
        } else {
            emptyList()
        },
    )
}
