package com.chesstree.game.presentation.board

import androidx.compose.ui.graphics.Color
import com.chesstree.game.domain.ArmyColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardPiecePresentationTest {
    @Test
    fun boardPiecesAreFifteenPercentLarger() {
        assertEquals(
            expected = 0.084f * 1.15f,
            actual = boardPieceRadius(boardScale = 1f, isSelected = false),
            absoluteTolerance = 0.000001f,
        )
    }

    @Test
    fun selectedPieceIsThirtyPercentLargerThanOtherPieces() {
        val regularRadius = boardPieceRadius(boardScale = 100f, isSelected = false)
        val selectedRadius = boardPieceRadius(boardScale = 100f, isSelected = true)

        assertEquals(regularRadius * 1.3f, selectedRadius)
    }

    @Test
    fun premiumPieceScalingPreservesSourceProportions() {
        val size = scaledImageSize(
            sourceWidth = 384,
            sourceHeight = 512,
            maxWidth = 200f,
            maxHeight = 200f,
        )

        assertEquals(150, size.width)
        assertEquals(200, size.height)
    }

    @Test
    fun premiumPieceOutlineMatchesArmyColor() {
        assertEquals(Color.White, premiumPieceOutlineColor(ArmyColor.WHITE))
        assertEquals(Color.Red, premiumPieceOutlineColor(ArmyColor.RED))
        assertEquals(Color.Black, premiumPieceOutlineColor(ArmyColor.BLACK))
    }

    @Test
    fun tappingSelectedPieceClearsSelection() {
        assertTrue(isRepeatedPieceTap(selectedPieceId = "piece-1", tappedPieceId = "piece-1"))
    }

    @Test
    fun tappingAnotherPieceDoesNotClearSelectionBeforeScreenHandlesIt() {
        assertFalse(isRepeatedPieceTap(selectedPieceId = "piece-1", tappedPieceId = "piece-2"))
    }
}
