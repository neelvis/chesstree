package com.chesstree.game.data

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
