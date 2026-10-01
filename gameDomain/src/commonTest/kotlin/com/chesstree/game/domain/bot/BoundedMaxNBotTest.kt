package com.chesstree.game.domain.bot

import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedMaxNBotTest {
    private val initial = StandardGame.scenario.initialState

    @Test
    fun completedSingleDepthReturnsAnAuthoritativeLegalMove() {
        val result = BoundedMaxNBot(maxDepth = 1).choose(initial)

        val move = assertIs<BoundedBotResult.Move>(result)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, move.source)
        assertEquals(BotStopReason.DEPTH_COMPLETE, move.reason)
        assertEquals(1, move.stats.completedDepth)
        assertTrue(move.stats.expandedNodes <= 128)
        assertTrue(move.stats.leafEvaluations <= 128)
        assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, move.intent))
    }

    @Test
    fun exhaustedEvaluationBudgetReturnsThePrevalidatedFallback() {
        val expected = LegalMoveGenerator.legalMoves(initial).first()
        var readyCount = 0
        val result = BoundedMaxNBot(maxDepth = 2, maxEvaluations = 1).choose(
            initial,
            onFallbackReady = { fallback ->
                readyCount++
                assertEquals(expected.from, fallback.from)
                assertEquals(expected.to, fallback.to)
                assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, fallback))
            },
        )

        val move = assertIs<BoundedBotResult.Move>(result)
        assertEquals(1, readyCount)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, move.source)
        assertEquals(BotStopReason.EVALUATION_LIMIT, move.reason)
        assertEquals(0, move.stats.completedDepth)
        assertEquals(1, move.stats.leafEvaluations)
    }

    @Test
    fun partialDeeperIterationKeepsTheLastCompletedMove() {
        val rootCount = LegalMoveGenerator.legalMoves(initial).size
        val shallow = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1).choose(initial),
        )
        val bounded = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 2, maxEvaluations = rootCount + 1).choose(initial),
        )

        assertEquals(1, bounded.stats.completedDepth)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, bounded.source)
        assertEquals(BotStopReason.EVALUATION_LIMIT, bounded.reason)
        assertEquals(shallow.intent, bounded.intent)
    }

    @Test
    fun explicitCancellationNeverReturnsAFallbackMove() {
        var fallbackReady = false
        var cancelled = false
        val result = BoundedMaxNBot().choose(
            initial,
            stop = BotStopProbe {
                if (cancelled) BotStopSignal.CANCEL else BotStopSignal.CONTINUE
            },
            onFallbackReady = {
                fallbackReady = true
                cancelled = true
            },
        )

        assertTrue(fallbackReady)
        val response = assertIs<BoundedBotResult.Cancelled>(result)
        assertEquals(0, response.stats.completedDepth)
        assertEquals(0, response.stats.expandedNodes)
    }

    @Test
    fun cancellationBeforeMoveGenerationNeverAnnouncesAFallback() {
        var announced = false
        val result = BoundedMaxNBot().choose(
            initial,
            stop = BotStopProbe { BotStopSignal.CANCEL },
            onFallbackReady = { announced = true },
        )

        assertIs<BoundedBotResult.Cancelled>(result)
        assertEquals(false, announced)
    }

    @Test
    fun timeoutAfterFallbackIsDifferentFromCancellation() {
        var timedOut = false
        val result = BoundedMaxNBot().choose(
            initial,
            stop = BotStopProbe {
                if (timedOut) BotStopSignal.TIMEOUT else BotStopSignal.CONTINUE
            },
            onFallbackReady = { timedOut = true },
        )

        val move = assertIs<BoundedBotResult.Move>(result)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, move.source)
        assertEquals(BotStopReason.TIMEOUT, move.reason)
        assertEquals(0, move.stats.completedDepth)
        assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, move.intent))
    }

    @Test
    fun finishedStateReturnsItsExistingRulesOutcome() {
        val phase = GamePhase.Finished(GameOutcome.ThreeWayDraw(DrawReason.AGREEMENT))
        val finished = GameState(
            position = initial.position,
            participants = initial.participants,
            armies = initial.armies,
            turn = null,
            phase = phase,
        )

        val result = assertIs<BoundedBotResult.Terminal>(BoundedMaxNBot().choose(finished))
        assertEquals(phase, result.phase)
    }
}
