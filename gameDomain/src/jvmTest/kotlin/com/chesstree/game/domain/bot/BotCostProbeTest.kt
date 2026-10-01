package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Small diagnostic cost probe on the same opening trajectory as the E0 control. */
class BotCostProbeTest {
    @Test
    fun compareSearchCostsWithoutChangingTheControl() {
        val fixtures = openingTrajectory()
        val noMobility = BotPolicy.DEFAULT.copy(
            rules = BotPolicy.DEFAULT.rules.map { rule ->
                if (rule.feature == BotFeature.MOBILITY) rule.copy(weight = 0) else rule
            },
        )
        val policies = mapOf("default" to BotPolicy.DEFAULT, "mobility-zero" to noMobility)
        val report = mutableListOf(
            "# report_schema\tbot-cost-probe-v1",
            "# scope\tten consecutive reachable opening states; descriptive JVM measurements",
            "# search\tMaxNBot(maxDepth=2,maxNodes=128,maxEvaluations=128)",
            "# default_policy\t${policyToken(BotPolicy.DEFAULT)}",
            "# diagnostic_policy\t${policyToken(noMobility)}",
            "# runtime\tjava=${System.getProperty("java.version")};os=${System.getProperty("os.name")};os_version=${System.getProperty("os.version")};arch=${System.getProperty("os.arch")}",
            "kind\tfixture\tactor\tvariant\trepetition\telapsed_ns\texpanded_nodes\tleaf_evaluations\tlegal_moves\tchosen_move",
        )

        // Exclude one initial search from the recorded sample; later JIT/thermal drift remains possible.
        assertNotNull(MaxNBot().chooseDecision(fixtures.first()))

        fixtures.forEachIndexed { index, state ->
            val original = state.toString()
            val actor = assertNotNull(state.turn).player
            val legal = LegalMoveGenerator.legalMoves(state)
            assertTrue(legal.isNotEmpty())

            repeat(5) { repetition ->
                val (rootNs, moves) = timed { LegalMoveGenerator.legalMoves(state) }
                assertEquals(legal, moves)
                report += row("root-legal", index, actor.name, "baseline", repetition, rootNs, legal.size)
            }

            val move = legal.first()
            val transition = assertNotNull(GameReducer.reduceGeneratedLegalMove(state, move))
            repeat(5) { repetition ->
                val (transitionNs, result) = timed {
                    GameReducer.reduceGeneratedLegalMove(state, move)
                }
                assertEquals(transition, result)
                report += row("transition", index, actor.name, "baseline", repetition, transitionNs, legal.size)
            }
            repeat(5) { repetition ->
                val (sampleNs, _) = timed {
                    BotTrainingSample.fromTransition(
                        before = state,
                        after = transition.state,
                        player = actor,
                        beforeLegalMoves = legal,
                    )
                }
                report += row("training-sample", index, actor.name, "baseline", repetition, sampleNs, legal.size)
            }

            val order = if (index % 2 == 0) {
                listOf("default", "mobility-zero", "mobility-zero", "default")
            } else {
                listOf("mobility-zero", "default", "default", "mobility-zero")
            }
            val choices = mutableMapOf<String, BotDecisionSearchResult>()
            order.forEachIndexed { repetition, variant ->
                val started = System.nanoTime()
                val decision = assertNotNull(
                    MaxNBot().chooseDecisionWithStats(state, policies.getValue(variant)),
                )
                val elapsedNs = System.nanoTime() - started
                assertEquals(original, state.toString())
                assertTrue(decision.stats.expandedNodes <= 128)
                assertTrue(decision.stats.leafEvaluations <= 128)
                assertTrue(GameReducer.reduce(state, decision.decision.intent) is MoveReduction.Applied)
                choices[variant]?.let { previous ->
                    assertEquals(previous.decision, decision.decision)
                    assertEquals(previous.stats, decision.stats)
                }
                choices[variant] = decision
                report += row(
                    "full-search", index, actor.name, variant, repetition, elapsedNs,
                    legal.size, decision.stats.expandedNodes, decision.stats.leafEvaluations,
                    decision.decision.intent.replayToken(),
                )
            }
            assertEquals(original, state.toString())
        }

        val destination = File("build/reports/bot-baseline/cost-probe.tsv")
        destination.parentFile.mkdirs()
        destination.writeText(report.joinToString("\n", postfix = "\n"))
    }

    private fun openingTrajectory(): List<GameState> {
        var state = StandardGame.scenario.initialState
        return buildList {
            repeat(10) { index ->
                add(state)
                if (index == 9) return@repeat
                val move = LegalMoveGenerator.legalMoves(state).first()
                val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
                state = (GameReducer.reduce(state, intent) as MoveReduction.Applied).state
            }
        }
    }

    private fun <T> timed(block: () -> T): Pair<Long, T> {
        val started = System.nanoTime()
        val value = block()
        return (System.nanoTime() - started) to value
    }

    private fun policyToken(policy: BotPolicy): String =
        BotPolicyCodec.encode(policy).trim().replace('\n', ';')

    private fun MoveIntent.replayToken(): String =
        "${actor.name}:${from.vertex},${from.column},${from.row}>" +
                "${to.vertex},${to.column},${to.row}:${promotion?.name ?: "-"}"

    private fun row(
        kind: String,
        fixture: Int,
        actor: String,
        variant: String,
        repetition: Int,
        elapsedNs: Long,
        legalMoves: Int,
        nodes: Int? = null,
        leaves: Int? = null,
        chosenMove: String = "",
    ): String = listOf(
        kind, "opening-$fixture", actor, variant, repetition.toString(), elapsedNs.toString(),
        nodes?.toString().orEmpty(), leaves?.toString().orEmpty(), legalMoves.toString(), chosenMove,
    ).joinToString("\t")
}
