package com.chesstree.app

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import kotlin.test.Test
import kotlin.test.assertIs

class GameStatusTextTest {
    @Test
    fun bareKingsPositionProducesDrawOutcome() {
        val state = GameState(
            position = Position(emptyMap()),
            participants = PlayerId.entries.associateWith(::Participant),
            armies = ArmyColor.entries.associateWith { army ->
                ArmyControl(army, army.originalPlayer)
            },
            turn = null,
            phase = GamePhase.Finished(
                GameOutcome.TwoWayDraw(
                    first = PlayerId.WHITE,
                    second = PlayerId.RED,
                    third = PlayerId.BLACK,
                    reason = DrawReason.INSUFFICIENT_MATERIAL,
                ),
            ),
        )

        val finished = assertIs<GamePhase.Finished>(state.phase)
        assertIs<GameOutcome.TwoWayDraw>(finished.outcome)
    }
}
