package com.chesstree.server

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.session.CapturedPiece
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameStateSnapshotCodecTest {
    @Test
    fun versionTwoSnapshotRoundTripsPositionAndCapturedPieceHistory() {
        val state = StandardGame.scenario.initialState
        val captures = listOf(
            CapturedPiece("red-pawn-1", PieceType.PAWN, ArmyColor.RED, ArmyColor.BLACK, ArmyColor.WHITE),
        )

        val restored = assertNotNull(GameStateSnapshotCodec.decode(GameStateSnapshotCodec.encode(state, captures)))

        assertEquals(state, restored.state)
        assertEquals(captures, restored.capturedPieces)
    }

    @Test
    fun versionOneSnapshotRemainsReadableAndSignalsMissingCaptureHistory() {
        val state = StandardGame.scenario.initialState
        val legacy = GameStateSnapshotCodec.encode(state, emptyList())
            .replace("\"version\":2", "\"version\":1")
            .replace(Regex(",\"capturedPieces\":\\[.*?\\]"), "")

        // Old records have the same position fields but no captured-piece list.
        val decoded = assertNotNull(GameStateSnapshotCodec.decode(legacy))
        assertEquals(state, decoded.state)
        assertNull(decoded.capturedPieces)
    }
}
