package com.chesstree.game.domain.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PieceType
import kotlin.test.Test
import kotlin.test.assertEquals

class StandardGameTest {
    @Test
    fun standardPositionHasThreeCompleteArmiesOnUniqueCoordinates() {
        assertEquals(48, StandardGame.pieces.size)
        assertEquals(48, StandardGame.pieces.map(InitialPiece::coordinate).toSet().size)
        ArmyColor.entries.forEach { army ->
            assertEquals(16, StandardGame.pieces.count { it.army == army })
        }
    }

    @Test
    fun queensKeepRequiredStartingCellColours() {
        val queens = StandardGame.pieces.filter { it.type == PieceType.QUEEN }

        assertEquals(false, queens.single { it.army == ArmyColor.WHITE }.coordinate.isDark)
        assertEquals(false, queens.single { it.army == ArmyColor.RED }.coordinate.isDark)
        assertEquals(true, queens.single { it.army == ArmyColor.BLACK }.coordinate.isDark)
    }

    private val com.chesstree.game.domain.BoardCoordinate.isDark: Boolean
        get() = (vertex + column + row) % 2 == 1
}
