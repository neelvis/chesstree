package com.chesstree.app

import com.chesstree.game.domain.PlayerId
import com.chesstree.game.presentation.board.PieceSet
import kotlin.test.Test
import kotlin.test.assertEquals

class PieceSetRestorationTest {
    @Test
    fun previouslySavedPieceSetsMigrateToPremium() {
        val standardSettings = GameSettings(pieceSet = PieceSet.STANDARD)
        assertEquals(PieceSet.FAIRY, restoreGameSettings(encodeGameSettings(standardSettings)).pieceSet)
        assertEquals(PieceSet.FAIRY, restoreGameSettings("1|true|false|STANDARD").pieceSet)
    }

    @Test
    fun allSettingsRestoreTogether() {
        val settings = GameSettings(
            showCurrentPossibleMoves = false,
            showMoveLines = true,
            pieceSet = PieceSet.FAIRY,
        )

        assertEquals(settings, restoreGameSettings(encodeGameSettings(settings)))
    }

    @Test
    fun invalidSettingsFallBackToDocumentedDefaults() {
        assertEquals(GameSettings(), restoreGameSettings("invalid"))
        assertEquals(GameSettings(), restoreGameSettings("1|yes|false|STANDARD"))
    }

    @Test
    fun turnIndicatorUsesBirdsForPremium() {
        assertEquals("king_0", turnIndicatorAssetName(PlayerId.WHITE, PieceSet.STANDARD))
        assertEquals("king_1", turnIndicatorAssetName(PlayerId.RED, PieceSet.STANDARD))
        assertEquals("bird_2", turnIndicatorAssetName(PlayerId.BLACK, PieceSet.FAIRY))
    }
}
