package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An opening-trajectory regression experiment, not a tactical-position corpus. */
class BotBaselineExperimentTest {
    @Test
    fun fixedBudgetSearchIsRepeatableAndLegalAcrossTenReachablePositions() {
        val fixtures = openingTrajectory()
        assertEquals(10, fixtures.size)
        assertEquals(PlayerId.entries.toSet(), fixtures.map { it.state.turn?.player }.toSet())

        val report = mutableListOf(
            "# report_schema\tbot-baseline-v1",
            "# scope\tten consecutive opening positions from StandardGame; no tactical coverage claim",
            "# trajectory_selector\tfirst move in LegalMoveGenerator.legalMoves(state) order",
            "# search\tMaxNBot(maxDepth=2,maxNodes=128,maxEvaluations=128); BotPolicy.DEFAULT",
            "# policy\t${BotPolicyCodec.encode(BotPolicy.DEFAULT).trim().replace('\n', ';')}",
            "# first_invocation\tfirst search in this test; not a process-cold measurement",
            "# runtime\tjava=${System.getProperty("java.version")};os=${System.getProperty("os.name")};os_version=${System.getProperty("os.version")};arch=${System.getProperty("os.arch")}",
            "fixture\tply_count\tactor\tmove_prefix\tchosen_move\texpanded_nodes\tleaf_evaluations\tfirst_invocation\trun1_ns\trun2_ns",
        )

        fixtures.forEachIndexed { index, fixture ->
            val before = fixture.state.toString()
            val bot = MaxNBot(maxDepth = 2, maxNodes = 128, maxEvaluations = 128)
            val firstStarted = System.nanoTime()
            val first = checkNotNull(bot.chooseDecisionWithStats(fixture.state, BotPolicy.DEFAULT))
            val firstElapsed = System.nanoTime() - firstStarted
            assertEquals(before, fixture.state.toString(), "Search changed fixture $index")

            val secondStarted = System.nanoTime()
            val second = checkNotNull(bot.chooseDecisionWithStats(fixture.state, BotPolicy.DEFAULT))
            val secondElapsed = System.nanoTime() - secondStarted
            assertEquals(before, fixture.state.toString(), "Repeat search changed fixture $index")

            assertEquals(first.decision.intent, second.decision.intent, "Intent differs at fixture $index")
            assertEquals(first.decision.trainingSample, second.decision.trainingSample, "Sample differs at fixture $index")
            assertEquals(first.stats, second.stats, "Counters differ at fixture $index")
            assertTrue(first.stats.expandedNodes <= 128, "Node budget exceeded at fixture $index")
            assertTrue(first.stats.leafEvaluations <= 128, "Leaf budget exceeded at fixture $index")
            assertTrue(
                GameReducer.reduce(fixture.state, first.decision.intent) is MoveReduction.Applied,
                "Chosen move is illegal at fixture $index",
            )
            assertEquals(before, fixture.state.toString(), "Validation changed fixture $index")

            report += listOf(
                "opening-$index",
                fixture.prefix.size.toString(),
                checkNotNull(fixture.state.turn).player.name,
                fixture.prefix.joinToString(";") { it.replayToken() },
                first.decision.intent.replayToken(),
                first.stats.expandedNodes.toString(),
                first.stats.leafEvaluations.toString(),
                if (index == 0) "yes" else "no",
                firstElapsed.toString(),
                secondElapsed.toString(),
            ).joinToString("\t")
        }

        val destination = File("build/reports/bot-baseline/experiment.tsv")
        destination.parentFile.mkdirs()
        destination.writeText(report.joinToString("\n", postfix = "\n"))
    }

    private fun openingTrajectory(): List<Fixture> {
        var state = StandardGame.scenario.initialState
        val prefix = mutableListOf<MoveIntent>()
        return buildList {
            repeat(10) { index ->
                add(Fixture(state, prefix.toList()))
                if (index == 9) return@repeat
                val move = LegalMoveGenerator.legalMoves(state).firstOrNull()
                    ?: error("Opening trajectory ended before fixture ${index + 1}")
                val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
                val reduction = GameReducer.reduce(state, intent) as? MoveReduction.Applied
                    ?: error("Opening trajectory move $index was rejected")
                prefix += intent
                state = reduction.state
            }
        }
    }

    private fun MoveIntent.replayToken(): String =
        "${actor.name}:${from.vertex},${from.column},${from.row}>" +
                "${to.vertex},${to.column},${to.row}:${promotion?.name ?: "-"}"

    private data class Fixture(val state: GameState, val prefix: List<MoveIntent>)
}
