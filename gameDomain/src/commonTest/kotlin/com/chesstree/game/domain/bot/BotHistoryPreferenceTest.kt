package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.gameScenario
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotHistoryPreferenceTest {
    private val material = BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 100)))
    private val state = gameScenario("history-preference") {
        piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
        piece("rk", ArmyColor.RED, PieceType.KING, at("A1"))
        piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
        piece("wq", ArmyColor.WHITE, PieceType.QUEEN, at("K9"), hasMoved = true)
    }.initialState

    @Test
    fun repeatedPositionChangesAnOtherwiseEqualChoiceEvenWithAnotherPly() {
        val engine = BoundedMaxNBot(maxDepth = 1, maxEvaluations = 256)
        val baseline = assertIs<BoundedBotResult.Move>(engine.choose(state, material))
        val after = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, baseline.intent)).state
        val earlier = GameState(after.position, after.participants, after.armies,
            after.turn?.copy(ply = 17), after.phase)
        val candidate = assertIs<BoundedBotResult.Move>(engine.choose(state, material,
            recentPositions = listOf(earlier)))
        assertTrue(candidate.intent != baseline.intent)
        assertEquals(0.0, candidate.repetitionPenalty)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, candidate.source)
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, candidate.intent))
        assertEquals(baseline, engine.choose(state, material))
    }

    @Test
    fun opponentMovementDoesNotHideAnOtherwiseEqualOwnReturn() {
        val engine = BoundedMaxNBot(maxDepth = 1, maxEvaluations = 256)
        val baseline = assertIs<BoundedBotResult.Move>(engine.choose(state, material))
        val after = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, baseline.intent)).state
        val earlier = GameState(
            Position(after.position.pieces.mapValues { (_, piece) ->
                if (piece.army == ArmyColor.BLACK) piece.copy(coordinate = at("N7")) else piece
            }), after.participants, after.armies, after.turn?.copy(ply = 17), after.phase,
        )
        val result = assertIs<BoundedBotResult.Move>(engine.choose(state, material, recentPositions = listOf(earlier)))
        assertTrue(result.intent != baseline.intent)
        assertEquals(0.0, result.repetitionPenalty)
    }

    @Test
    fun aDifferentActorIsNotTheSamePositionAndHistoryIsBounded() {
        val engine = BoundedMaxNBot(maxDepth = 1, maxEvaluations = 256)
        val baseline = assertIs<BoundedBotResult.Move>(engine.choose(state, material))
        val after = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, baseline.intent)).state
        val otherActor = GameState(after.position, after.participants, after.armies,
            Turn(PlayerId.BLACK, 17), after.phase)
        assertEquals(baseline, engine.choose(state, material, recentPositions = listOf(otherActor)))
        assertFailsWith<IllegalArgumentException> {
            engine.choose(state, material, recentPositions = List(MAX_BOT_RECENT_POSITIONS + 1) { state })
        }
    }

    private fun at(label: String) = checkNotNull(ThreePlayerBoardNotation.parse(label))
}
