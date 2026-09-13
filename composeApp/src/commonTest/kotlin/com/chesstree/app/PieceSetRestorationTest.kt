package com.chesstree.app

import com.chesstree.game.presentation.board.PieceSet
import kotlin.test.Test
import kotlin.test.assertEquals

class PieceSetRestorationTest {
    @Test
    fun fairyPieceSetNameRestores() {
        assertEquals(PieceSet.FAIRY, restorePieceSet(PieceSet.FAIRY.name))
    }

    @Test
    fun unknownPieceSetFallsBackToStandard() {
        assertEquals(PieceSet.STANDARD, restorePieceSet("REMOVED_SET"))
    }
}
