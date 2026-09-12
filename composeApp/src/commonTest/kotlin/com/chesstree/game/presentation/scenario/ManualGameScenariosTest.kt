package com.chesstree.game.presentation.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.presentation.board.toBoardPieces
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ManualGameScenariosTest {
    @Test
    fun standardScenarioStartsWithBothCastlingRightsForEveryArmy() {
        val rights = ManualGameScenarios.standard.initialState.position.castlingRights

        assertEquals(6, rights.size)
        ArmyColor.entries.forEach { army ->
            assertEquals(
                CastlingSide.entries.toSet(),
                rights.filter { right -> right.army == army }.map { right -> right.side }.toSet(),
            )
        }
    }

    @Test
    fun catalogueHasStableUniqueIds() {
        val scenarios = ManualGameScenarios.all

        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
    }

    @Test
    fun standardScenarioContainsThreeCompleteArmies() {
        val pieces = ManualGameScenarios.standard.initialState.position.pieces.values

        assertEquals(48, pieces.size)
        ArmyColor.entries.forEach { army ->
            assertEquals(16, pieces.count { it.army == army })
        }
    }

    @Test
    fun postMateScenarioTransfersDefeatedArmyAndRemovesKing() {
        val state = ManualGameScenarios.afterRedCheckmate.initialState

        assertIs<ParticipantStatus.Checkmated>(
            state.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(PlayerId.WHITE, state.armies.getValue(ArmyColor.RED).controller)
        assertEquals(
            ArmyColor.WHITE,
            state.toBoardPieces().single { it.id == "red-rook" }.bodyArmy,
        )
        assertEquals(
            0,
            state.position.pieces.values.count { piece ->
                piece.army == ArmyColor.RED && piece.type == PieceType.KING
            },
        )
    }
}
