package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs

/** Opt-in measurements, not an assertion that a different move is stronger. */
class BotReportedCacheProbeTest {
    @Test
    fun compareReportedPositions() {
        if (System.getenv("CHESSTREE_BOT_CACHE_PROBE") != "true") return
        val input = File(checkNotNull(System.getenv("CHESSTREE_BOT_CACHE_REPORT")))
        val history = mutableListOf(StandardGame.scenario.initialState)
        input.readLines().filter { it.startsWith("move|") }.forEach { row ->
            val fields = row.split('|')
            val intent = BotOpeningBook.decode(
                "${PlayerId.valueOf(fields[1]).ordinal},${fields.subList(2, 8).joinToString(",")}",
            ).copy(promotion = fields[8].takeUnless { it == "-" }?.let(PromotionChoice::valueOf))
            history += assertIs<MoveReduction.Applied>(GameReducer.reduce(history.last(), intent)).state
        }
        val policy = BotPolicy(listOf(
            BotRule(BotFeature.MATERIAL, 100), BotRule(BotFeature.KING_SAFETY, 280),
            BotRule(BotFeature.CHECK_PRESSURE, 40), BotRule(BotFeature.MOBILITY, 3),
        ))
        val destination = File("build/reports/bot-baseline/oct17-cache-comparison.tsv")
        destination.parentFile.mkdirs()
        destination.bufferedWriter().use { writer ->
            fun log(line: String) { writer.appendLine(line); writer.flush() }
            log("# JVM; single timed samples; maxDepth4;q4;Paranoid;Universal2;repetition0.50;1m work caps;history36")
            log("ply\tvariant\telapsedMs\tdepth\thorizon\tnodes\tevaluations\tmove\tstop\tcache")
            for (prefix in listOf(48, 138, 248)) {
                val state = history[prefix]
                fun name(intent: MoveIntent): String {
                    val piece = state.position.pieces.values.single { it.coordinate == intent.from }
                    return "${piece.type}:${ThreePlayerBoardNotation.square(intent.from)}>${ThreePlayerBoardNotation.square(intent.to)}"
                }
                val selectedVariant = System.getenv("CHESSTREE_BOT_CACHE_VARIANT")
                val variants = listOf("old-time" to 2_500L, "long-uncached" to 30_000L, "long-cached" to 30_000L)
                    .filter { selectedVariant == null || it.first == selectedVariant }
                for ((variant, budget) in variants) {
                    val cache = if (variant == "long-cached") BotSearchCache() else null
                    val start = System.nanoTime()
                    val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                        maxDepth = 4, maxNodes = 1_000_000, maxEvaluations = 1_000_000,
                        evaluation = BotEvaluationMode.POSITIONAL, tacticalDepth = 4,
                        searchModel = BotSearchModel.PARANOID, profileCatalogVersion = 2,
                        cache = cache,
                    ).choose(state, policy, stop = BotStopProbe {
                        if ((System.nanoTime() - start) / 1_000_000 >= budget) BotStopSignal.TIMEOUT else BotStopSignal.CONTINUE
                    }, recentPositions = history.take(prefix + 1).takeLast(MAX_BOT_RECENT_POSITIONS)))
                    assertIs<MoveReduction.Applied>(GameReducer.reduce(state, result.intent))
                    log(listOf(prefix + 1, variant, (System.nanoTime() - start) / 1_000_000,
                        result.stats.completedDepth, result.stats.completedHorizon, result.stats.expandedNodes,
                        result.stats.leafEvaluations, name(result.intent), result.reason, cache?.stats,
                    ).joinToString("\t"))
                }
                log("# rootLegal=$prefix:${LegalMoveGenerator.legalMoves(state).size}")
            }
        }
    }
}
