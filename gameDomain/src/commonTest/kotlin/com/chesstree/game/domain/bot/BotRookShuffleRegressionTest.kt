package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BotRookShuffleRegressionTest {
    @Test
    fun continuingTheReportedPositionBreaksBothTwoSquareLoops() {
        val history = mutableListOf(StandardGame.scenario.initialState)
        reportMoves.take(48).forEach { token ->
            history += assertIs<MoveReduction.Applied>(
                GameReducer.reduce(history.last(), BotOpeningBook.decode(token)),
            ).state
        }
        val layouts = mutableMapOf<PlayerId, MutableSet<Map<PieceId, Piece>>>()
        val engine = BoundedMaxNBot(
            maxDepth = 1, maxNodes = 20_000, maxEvaluations = 20_000,
            evaluation = BotEvaluationMode.POSITIONAL, tacticalDepth = 4,
            searchModel = BotSearchModel.PARANOID, profileCatalogVersion = 2,
        )
        repeat(18) {
            val before = history.last()
            val result = assertIs<BoundedBotResult.Move>(engine.choose(
                before, recentPositions = history.takeLast(MAX_BOT_RECENT_POSITIONS),
            ))
            val after = assertIs<MoveReduction.Applied>(GameReducer.reduce(before, result.intent)).state
            history += after
            layouts.getOrPut(result.intent.actor) { mutableSetOf() } +=
                after.position.pieces.filterValues { after.armies.getValue(it.army).controller == result.intent.actor }
        }
        for (actor in listOf(PlayerId.WHITE, PlayerId.RED)) {
            assertTrue(layouts.getValue(actor).size >= 3, "$actor must leave its two-layout loop")
        }
    }

    @Test
    fun opponentMovesDoNotHideWhiteAndRedRookReturns() {
        val history = mutableListOf(StandardGame.scenario.initialState)
        reportMoves.forEach { token ->
            history += assertIs<MoveReduction.Applied>(
                GameReducer.reduce(history.last(), BotOpeningBook.decode(token)),
            ).state
        }
        for (ply in listOf(40, 49)) {
            val before = history[ply - 1]
            val recent = history.take(ply).takeLast(MAX_BOT_RECENT_POSITIONS)
            val engine = BoundedMaxNBot(
                maxDepth = 1, maxNodes = 20_000, maxEvaluations = 20_000,
                evaluation = BotEvaluationMode.POSITIONAL, tacticalDepth = 4,
                searchModel = BotSearchModel.PARANOID, profileCatalogVersion = 2,
            )
            val baseline = assertIs<BoundedBotResult.Move>(engine.choose(before))
            assertEquals(BotOpeningBook.decode(reportMoves[ply - 1]), baseline.intent)
            val result = assertIs<BoundedBotResult.Move>(engine.choose(before, recentPositions = recent))
            assertNotEquals(baseline.intent, result.intent)
            assertEquals(0.0, result.repetitionPenalty)
            assertIs<MoveReduction.Applied>(GameReducer.reduce(before, result.intent))
            assertEquals(result, engine.choose(before, recentPositions = recent))
        }
    }

    // Public-reducer replay of the supplied 2026-10-01 report through the loop onset.
    private val reportMoves = listOf(
        "0,1,2,3,1,1,3",
        "1,3,2,3,3,1,3",
        "2,0,0,1,0,0,2",
        "0,1,3,2,2,1,3",
        "1,4,2,0,4,1,2",
        "2,5,3,1,5,1,2",
        "0,2,2,0,2,1,2",
        "1,3,3,2,4,1,3",
        "2,0,1,0,5,0,2",
        "0,2,0,0,0,3,3",
        "1,3,3,1,3,1,2",
        "2,5,2,3,5,0,3",
        "0,1,3,1,1,1,0",
        "1,4,1,3,5,0,1",
        "2,5,3,2,5,2,3",
        "0,1,1,0,1,0,2",
        "1,3,1,2,2,2,3",
        "2,0,2,0,0,1,2",
        "0,0,3,3,2,0,0",
        "1,4,2,1,4,2,2",
        "2,0,0,2,0,0,3",
        "0,2,3,1,2,3,2",
        "1,2,2,3,3,1,2",
        "2,0,0,3,1,0,2",
        "0,1,1,3,1,0,2",
        "1,4,1,0,4,2,1",
        "2,5,2,0,5,0,0",
        "0,1,2,1,1,1,1",
        "1,3,1,2,2,0,3",
        "2,0,2,1,0,2,2",
        "0,1,2,0,1,0,0",
        "1,3,2,0,3,0,0",
        "2,0,3,1,0,3,2",
        "0,1,3,0,1,2,0",
        "1,3,2,1,3,1,1",
        "2,5,2,1,5,1,1",
        "0,1,2,0,1,3,0",
        "1,4,0,1,4,0,3",
        "2,5,0,2,5,1,3",
        "0,1,3,0,1,2,0",
        "1,4,1,2,4,3,3",
        "2,0,0,0,0,1,0",
        "0,1,2,0,1,3,0",
        "1,4,1,1,4,1,2",
        "2,0,1,0,0,2,1",
        "0,1,3,0,1,2,0",
        "1,3,3,0,3,2,0",
        "2,5,3,3,5,3,2",
        "0,1,2,0,1,3,0",
        "1,3,2,0,3,3,0",
    )
}
