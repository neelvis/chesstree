package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MaxNBotTest {
    @Test
    fun choosesALegalMoveForThePlayerWhoseTurnItIs() {
        val state = StandardGame.scenario.initialState
        val result = MaxNBot().chooseDecisionWithStats(state)

        assertNotNull(result)
        val intent = result.decision.intent
        assertEquals(state.turn?.player, intent.actor)
        assertTrue(result.stats.expandedNodes <= 128)
        assertTrue(result.stats.leafEvaluations <= 128)
        assertTrue(
            LegalMoveGenerator.legalMoves(state).any { move ->
                move.from == intent.from && move.to == intent.to && move.promotion == intent.promotion
            },
        )
        assertTrue(GameReducer.reduce(state, intent) is com.chesstree.game.domain.MoveReduction.Applied)
    }

    @Test
    fun searchHonorsExpandedNodeAndLeafEvaluationBudgets() {
        val state = StandardGame.scenario.initialState
        val result = MaxNBot(maxDepth = 4, maxNodes = 1, maxEvaluations = 3)
            .chooseDecisionWithStats(state)

        assertNotNull(result)
        assertEquals(1, result.stats.expandedNodes)
        assertEquals(3, result.stats.leafEvaluations)
        assertTrue(GameReducer.reduce(state, result.decision.intent) is com.chesstree.game.domain.MoveReduction.Applied)
    }

    @Test
    fun returnsNoMoveWhenTheGameIsFinished() {
        val initial = StandardGame.scenario.initialState
        val finished = GameState(
            position = initial.position,
            participants = initial.participants,
            armies = initial.armies,
            turn = null,
            phase = GamePhase.Finished(GameOutcome.ThreeWayDraw(DrawReason.AGREEMENT)),
        )

        assertEquals(null, MaxNBot().chooseMove(finished))
    }

    @Test
    fun policyCanDisableOrAdjustTypedEvaluationRules() {
        val policy = BotPolicy(
            rules = listOf(
                BotRule(BotFeature.MATERIAL, weight = 500),
                BotRule(BotFeature.KING_SAFETY, weight = 0, enabled = false),
            ),
        )

        assertEquals(500, policy.weight(BotFeature.MATERIAL))
        assertEquals(0, policy.weight(BotFeature.KING_SAFETY))
        assertEquals(0, policy.weight(BotFeature.MOBILITY))
    }

    @Test
    fun policyCodecRoundTripsAndRejectsUnsupportedOrInvalidData() {
        val policy = BotPolicy(
            listOf(
                BotRule(BotFeature.MATERIAL, weight = 731),
                BotRule(BotFeature.KING_SAFETY, weight = 450, enabled = false),
            ),
        )

        assertEquals(policy, BotPolicyCodec.decode(BotPolicyCodec.encode(policy)))
        assertEquals(null, BotPolicyCodec.decode("unknown-policy-version"))
        assertEquals(null, BotPolicyCodec.decode("chesstree-bot-policy-v1\nMATERIAL|99999|1"))
    }

    @Test
    fun trainingSamplesRoundTripWithBoundedTypedFeatures() {
        val sample = BotTrainingSample(
            player = PlayerId.RED,
            featureDeltas = mapOf(
                BotFeature.MATERIAL to -8,
                BotFeature.KING_SAFETY to 0,
                BotFeature.CHECK_PRESSURE to 1,
                BotFeature.MOBILITY to 0,
            ),
        )

        assertEquals(sample, BotTrainingSampleCodec.decode(BotTrainingSampleCodec.encode(sample)))
        assertEquals(null, BotTrainingSampleCodec.decode("sample-v1|RED|MATERIAL:1000"))
    }

    @Test
    fun completedRankedGameAdjustsTypedRulesFromMoveFeatures() {
        val outcome = GameOutcome.Ranked(
            first = PlayerId.WHITE,
            second = PlayerId.RED,
            third = PlayerId.BLACK,
        )
        val samples = listOf(
            BotTrainingSample(PlayerId.WHITE, mapOf(BotFeature.MATERIAL to 10)),
            BotTrainingSample(PlayerId.RED, mapOf(BotFeature.MATERIAL to 0)),
            BotTrainingSample(PlayerId.BLACK, mapOf(BotFeature.MATERIAL to -10)),
        )

        val learned = BotPolicyLearner.learn(BotPolicy.DEFAULT, outcome, samples)

        assertTrue(learned.weight(BotFeature.MATERIAL) > BotPolicy.DEFAULT.weight(BotFeature.MATERIAL))
        assertEquals(
            BotPolicy.DEFAULT.weight(BotFeature.KING_SAFETY),
            learned.weight(BotFeature.KING_SAFETY),
        )
    }
}
