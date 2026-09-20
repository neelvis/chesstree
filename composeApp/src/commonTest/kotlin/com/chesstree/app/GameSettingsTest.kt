package com.chesstree.app

import com.chesstree.game.presentation.board.PieceSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class GameSettingsTest {
    @Test
    fun gameHistoryIsHiddenByDefault() {
        assertFalse(GameSettings().showGameHistory)
    }

    @Test
    fun currentSettingsRoundTrip() {
        val settings = GameSettings(
            showCurrentPossibleMoves = false,
            showMoveLines = true,
            showGameHistory = true,
            pieceSet = PieceSet.FAIRY,
        )

        assertEquals(settings, restoreGameSettings(encodeGameSettings(settings)))
    }

    @Test
    fun legacySettingsRestoreWithHistoryHidden() {
        assertEquals(
            GameSettings(
                showCurrentPossibleMoves = false,
                showMoveLines = true,
                showGameHistory = false,
                pieceSet = PieceSet.FAIRY,
            ),
            restoreGameSettings("1|false|true|FAIRY"),
        )
    }
}
