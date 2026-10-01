package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoundedMaxNProgressTest {
    private val initial = StandardGame.scenario.initialState

    @Test
    fun evaluationBudgetPublishesOnlyFinishedDepthAndRetainsItsMove() {
        val rootSize = LegalMoveGenerator.legalMoves(initial).size
        val publications = mutableListOf<BoundedBotResult.Move>()
        val bot = BoundedMaxNBot(maxDepth = 2, maxEvaluations = rootSize + 1)

        val result = bot.choose(initial, onCompletedDepth = { publication ->
            assertCompletedPublication(initial, publication, maxEvaluations = rootSize + 1)
            publications += publication
        })

        val finalMove = assertIs<BoundedBotResult.Move>(result)
        assertEquals(listOf(1), publications.map { it.stats.completedDepth })
        assertEquals(publications.single().intent, finalMove.intent)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, finalMove.source)
        assertEquals(BotStopReason.EVALUATION_LIMIT, finalMove.reason)
        assertEquals(1, finalMove.stats.completedDepth)
    }

    @Test
    fun cancellationAfterPublishedDepthReturnsCancelledWithoutMove() {
        var cancelled = false
        val publications = mutableListOf<BoundedBotResult.Move>()
        val result = BoundedMaxNBot(maxDepth = 2).choose(
            initial,
            stop = BotStopProbe {
                if (cancelled) BotStopSignal.CANCEL else BotStopSignal.CONTINUE
            },
            onCompletedDepth = { publication ->
                assertCompletedPublication(initial, publication)
                publications += publication
                cancelled = true
            },
        )

        val finalResult = assertIs<BoundedBotResult.Cancelled>(result)
        assertEquals(listOf(1), publications.map { it.stats.completedDepth })
        assertEquals(1, finalResult.stats.completedDepth)
    }

    @Test
    fun completedDepthOnePublishesOnceAndMatchesFinalMove() {
        val publications = mutableListOf<BoundedBotResult.Move>()
        val result = BoundedMaxNBot(maxDepth = 1).choose(
            initial,
            onCompletedDepth = { publication ->
                assertCompletedPublication(initial, publication)
                publications += publication
            },
        )

        val finalMove = assertIs<BoundedBotResult.Move>(result)
        assertEquals(listOf(1), publications.map { it.stats.completedDepth })
        assertEquals(publications.single().intent, finalMove.intent)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, finalMove.source)
        assertEquals(BotStopReason.DEPTH_COMPLETE, finalMove.reason)
    }

    private fun assertCompletedPublication(
        state: GameState,
        publication: BoundedBotResult.Move,
        maxNodes: Int = 128,
        maxEvaluations: Int = 128,
    ) {
        val actor = assertNotNull(state.turn?.player)
        assertEquals(actor, publication.intent.actor)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, publication.source)
        assertEquals(BotStopReason.DEPTH_COMPLETE, publication.reason)
        assertTrue(publication.stats.completedDepth > 0)
        assertTrue(publication.stats.expandedNodes in 1..maxNodes)
        assertTrue(publication.stats.leafEvaluations in 1..maxEvaluations)
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, publication.intent))
    }
}
