package com.chesstree.app

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameMoveHintsTest {
    private val state = ManualGameScenarios.standard.initialState
    private val movablePieceId = LegalMoveGenerator.legalMoves(state).first().pieceId.value

    @Test
    fun currentPossibleMovesAreShownByDefault() {
        val expectedTargets = LegalMoveGenerator.legalMoves(
            state,
            com.chesstree.game.domain.PieceId(movablePieceId),
        ).map { it.to }.toSet()

        val hints = movementHintsForSelection(
            state = state,
            selectedPieceId = movablePieceId,
            showCurrentPossibleMoves = true,
            showMoveLines = false,
        )

        assertEquals(expectedTargets, hints.currentPossibleMoves.map { it.target }.toSet())
        assertTrue(hints.moveLines.isEmpty())
    }

    @Test
    fun currentPossibleMovesCanBeHiddenWithoutChangingSelection() {
        assertEquals(
            SelectedMoveHints(emptyList(), emptyList()),
            movementHintsForSelection(
                state = state,
                selectedPieceId = movablePieceId,
                showCurrentPossibleMoves = false,
                showMoveLines = false,
            ),
        )
    }

    @Test
    fun educationalDirectionsRemainIndependentFromCurrentMoveSetting() {
        val hints = movementHintsForSelection(
            state = state,
            selectedPieceId = movablePieceId,
            showCurrentPossibleMoves = false,
            showMoveLines = true,
        )

        assertTrue(hints.currentPossibleMoves.isEmpty())
        assertTrue(hints.moveLines.isNotEmpty())
        assertTrue(hints.moveLines.all { it.route.isNotEmpty() })
    }

    @Test
    fun possibleMovesAndDirectionLinesCanBeShownTogether() {
        val hints = movementHintsForSelection(
            state = state,
            selectedPieceId = movablePieceId,
            showCurrentPossibleMoves = true,
            showMoveLines = true,
        )

        assertTrue(hints.currentPossibleMoves.isNotEmpty())
        assertTrue(hints.moveLines.isNotEmpty())
    }
}
