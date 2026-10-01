package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.TimeSource

/** Opt-in fixed-work opening observation, not a playing-strength tournament. */
class BotStrategicOpeningProbeTest {
    @Test
    fun recordMatchedOpeningDecisions() {
        if (System.getenv("CHESSTREE_STRATEGY_PROBE") != "1") return
        val output = File("build/reports/bot-baseline/strategic-opening.tsv")
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine("config\tseed\tply\tactor\tpiece\tfrom\tto\tdepth\tnodes\tevaluations\tbookInfluenced\telapsedMs")
            // Warm immutable geometry and evaluator before the descriptive timing rows.
            BotEvaluationMode.POSITIONAL.evaluate(StandardGame.scenario.initialState, BotPolicy.DEFAULT)
            for ((config, evaluation, book) in listOf(
                Triple("control", BotEvaluationMode.CONTROL, 0),
                Triple("positional", BotEvaluationMode.POSITIONAL, 0),
                Triple("positional-book", BotEvaluationMode.POSITIONAL, 1),
            )) {
                for (seed in 0L..2L) {
                    var state = StandardGame.scenario.initialState
                    repeat(9) {
                        if (state.phase != GamePhase.InProgress) return@repeat
                        val before = state
                        val clock = TimeSource.Monotonic.markNow()
                        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                            maxDepth = 2, maxNodes = 128, maxEvaluations = 128,
                            evaluation = evaluation, openingBookVersion = book, seed = seed,
                        ).choose(before))
                        val elapsedMs = clock.elapsedNow().inWholeMicroseconds / 1_000.0
                        val replay = assertIs<MoveReduction.Applied>(GameReducer.reduce(before, result.intent))
                        assertEquals(before.turn?.player, result.intent.actor)
                        val piece = before.position.pieces.values.single { it.coordinate == result.intent.from }
                        writer.appendLine(listOf(
                            config, seed, before.turn?.ply, result.intent.actor, piece.type,
                            ThreePlayerBoardNotation.square(result.intent.from),
                            ThreePlayerBoardNotation.square(result.intent.to), result.stats.completedDepth,
                            result.stats.expandedNodes, result.stats.leafEvaluations,
                            result.openingBook.influenced, elapsedMs,
                        ).joinToString("\t"))
                        state = replay.state
                    }
                }
            }
        }
    }
}
