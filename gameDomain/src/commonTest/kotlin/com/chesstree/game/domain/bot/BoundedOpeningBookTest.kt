package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedOpeningBookTest {
    private val initial = StandardGame.scenario.initialState

    @Test
    fun exactScoreTiesUseSeededBookWithoutChangingWorkBudget() {
        val policy = BotPolicy(emptyList())
        val control = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(maxDepth = 1).choose(initial, policy))
        for (seed in 0L..2L) {
            val candidate = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 1, openingBookVersion = 1, seed = seed,
            ).choose(initial, policy))
            assertEquals(BotOpeningBook.preferredMoves(initial, seed).first(), candidate.intent)
            assertEquals(control.stats, candidate.stats)
            assertTrue(candidate.intent != control.intent)
            assertEquals(BotOpeningDiagnostics(1, true), candidate.openingBook)
            assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, candidate.intent))
        }
    }

    @Test
    fun bookCannotOverrideHigherCompletedSearchScore() {
        val policy = BotPolicy.DEFAULT
        val moves = LegalMoveGenerator.legalMoves(initial)
        val scores = moves.associate { move ->
            val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
            val state = assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, intent)).state
            intent to BotEvaluationMode.POSITIONAL.evaluate(state, policy)[0]
        }
        val best = checkNotNull(scores.values.maxOrNull())
        for (seed in 0L..2L) {
            val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 1, evaluation = BotEvaluationMode.POSITIONAL, openingBookVersion = 1, seed = seed,
            ).choose(initial, policy))
            assertEquals(best, scores.getValue(result.intent))
        }
    }

    @Test
    fun interruptedDepthRetainsCompletedBookChoiceAndDiagnostics() {
        val publications = mutableListOf<BoundedBotResult.Move>()
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 2, maxEvaluations = LegalMoveGenerator.legalMoves(initial).size + 1,
            openingBookVersion = 1,
        ).choose(initial, BotPolicy(emptyList()), onCompletedDepth = { publications += it }))
        assertEquals(1, publications.size)
        assertEquals(publications.single().intent, result.intent)
        assertEquals(publications.single().openingBook, result.openingBook)
        assertEquals(BotOpeningDiagnostics(1, true), result.openingBook)
        assertEquals(1, result.stats.completedDepth)
    }

    @Test
    fun noCompletedDepthKeepsOrdinaryFallbackWithoutBookInfluence() {
        val control = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(maxEvaluations = 1).choose(initial))
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxEvaluations = 1, openingBookVersion = 1,
        ).choose(initial))
        assertEquals(control.intent, result.intent)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, result.source)
        assertEquals(0, result.stats.completedDepth)
        assertFalse(result.openingBook.influenced)
        assertEquals(1, result.openingBook.version)
    }
}
