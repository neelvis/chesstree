package com.chesstree.game.data

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GameSnapshotCodecTest {
    @Test
    fun snapshotRoundTripsMovesAndPromotion() {
        val snapshot = GameSnapshot(
            scenarioId = "capture-practice",
            moves = listOf(
                MoveIntent(
                    actor = PlayerId.WHITE,
                    from = BoardCoordinate(0, 1, 1),
                    to = BoardCoordinate(0, 2, 1),
                ),
                MoveIntent(
                    actor = PlayerId.RED,
                    from = BoardCoordinate(2, 2, 2),
                    to = BoardCoordinate(2, 3, 2),
                    promotion = PromotionChoice.QUEEN,
                ),
            ),
        )

        assertEquals(snapshot, GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun incompatibleOrMalformedSnapshotsAreRejected() {
        assertNull(GameSnapshotCodec.decode("CHESSTREE|2\nscenario|standard\n"))
        assertNull(GameSnapshotCodec.decode("CHESSTREE|1\nscenario|../standard\n"))
        assertNull(GameSnapshotCodec.decode("CHESSTREE|1\nscenario|standard\nmove|WHITE|9|0|0|0|0|0|-\n"))
    }

    @Test
    fun legacyMoveOnlySavesRestoreAsManualGamesWithoutInventedBotSettings() {
        val restored = GameSnapshotCodec.decode(
            "CHESSTREE|1\nscenario|standard\nmove|WHITE|0|1|1|0|2|1|-\n",
        )!!
        assertEquals("standard", restored.scenarioId)
        assertEquals(1, restored.moves.size)
        assertNull(restored.botGame)
        assertNull(restored.initialState)
        assertFalse(restored.botsRunning)
        assertEquals(restored, GameSnapshotCodec.decode(GameSnapshotCodec.encode(restored)))
    }

    @Test
    fun fullStartingStateAndBotConfigurationRoundTrip() {
        val snapshot = GameSnapshot(
            scenarioId = "standard",
            moves = emptyList(),
            botGame = LocalBotGameConfig.watchGame(PlayerId.entries.associateWith { 50L + it.ordinal }),
            botsRunning = true,
            initialState = StandardGame.scenario.initialState,
        )
        assertEquals(snapshot, GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun malformedBotConfigurationRejectsTheWholeSnapshot() {
        val snapshot = GameSnapshot(
            scenarioId = "standard",
            moves = emptyList(),
            botGame = LocalBotGameConfig.humanGame(PlayerId.WHITE, mapOf(PlayerId.RED to 21L, PlayerId.BLACK to 22L)),
            initialState = StandardGame.scenario.initialState,
        )
        val encoded = GameSnapshotCodec.encode(snapshot)
        assertNull(GameSnapshotCodec.decode(encoded.replace("versions|1|3|3|1|2", "versions|2|3|3|1|2")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("seat|BLACK", "seat|RED")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("UNIVERSAL", "UNKNOWN")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("\"vertex\":", "\"unknownVertex\":")))
    }

    @Test
    fun unexpectedRecordsFlagsAndOversizedSnapshotsAreRejected() {
        val encoded = GameSnapshotCodec.encode(GameSnapshot("standard", emptyList()))
        assertNull(GameSnapshotCodec.decode(encoded.replace("bots-running|0", "bots-running|1")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("bots-running|0", "bots-running|true")))
        assertNull(GameSnapshotCodec.decode(encoded + "unknown|record\n"))
        assertNull(GameSnapshotCodec.decode(encoded.replace("scenario|standard\n", "scenario|standard\n\n")))
        assertNull(GameSnapshotCodec.decode(encoded + "move|WHITE|0|1|1|0|2|1|-\n".repeat(10_001)))
        assertNull(GameSnapshotCodec.decode("x".repeat(1_000_001)))
        val move = MoveIntent(PlayerId.WHITE, BoardCoordinate(0, 1, 1), BoardCoordinate(0, 2, 1))
        assertNull(GameSnapshotCodec.encodeOrNull(GameSnapshot("standard", List(10_001) { move })))
    }
}
