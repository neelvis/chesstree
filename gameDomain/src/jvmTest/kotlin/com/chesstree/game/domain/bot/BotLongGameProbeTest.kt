package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ThreePlayerBoardNotation
import java.io.File
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlin.test.assertIs

/** Descriptive fixed-work trajectories; unfinished games are censored, never draws. */
class BotLongGameProbeTest {
    @Test
    fun recordMatchedLongTrajectories() {
        if (System.getenv("CHESSTREE_BOT_LONG_PROBE") != "true") return
        val appBudget = System.getenv("CHESSTREE_BOT_LONG_APP") == "true"
        val model = System.getenv("CHESSTREE_BOT_LONG_MODEL")?.let(BotSearchModel::valueOf) ?: BotSearchModel.MAX_N
        val catalog = System.getenv("CHESSTREE_BOT_LONG_CATALOG")?.toInt() ?: 1
        val fixedWork = System.getenv("CHESSTREE_BOT_LONG_FIXED") == "true"
        val profile = System.getenv("CHESSTREE_BOT_LONG_PROFILE")?.let(BotPlayingProfile::valueOf) ?: BotPlayingProfile.UNIVERSAL
        val plyLimit = System.getenv("CHESSTREE_BOT_LONG_PLIES")?.toInt()?.also { require(it in 1..500) } ?: if (appBudget) 60 else 240
        val depth = if (appBudget) BotDifficulty.STRONG.maxDepth else 3
        val q = System.getenv("CHESSTREE_BOT_LONG_Q")?.toInt() ?: 2
        val output = File("build/reports/bot-baseline/long-games.tsv")
        output.parentFile.mkdirs()
        val starts = ArenaPilotFixtures.starts() + ArenaPilotFixtures.Start(
            "mixed-material", "synthetic ArenaPilotFixtures.mixedMaterial;reachability unproven",
            ArenaPilotFixtures.mixedMaterial(checked = false),
        )
        output.bufferedWriter().use { writer ->
            writer.appendLine("# schema\tbot-long-game-v1")
            writer.appendLine("# search\tbounded;depth=$depth;seed0;book1;no learning;appBudget=$appBudget;q=$q;historyLimit=$MAX_BOT_RECENT_POSITIONS;model=$model;profile=$profile;catalog=$catalog;plyLimit=$plyLimit;fixedWork=$fixedWork")
            writer.appendLine("# interpretation\tdescriptive paired trajectories;synthetic starts are not video reconstructions or forced wins;one game per condition;no strength inference")
            writer.appendLine("fixture\tprovenance\tevaluation\tply\tactor\tpiece\tmove\tcapture\tpromotion\tdepth\tfallback\trepeatedPosition\trecentRepeat\trepetitionPenalty\telapsedNs\thorizon\treason\tphase")
            starts.forEach { start ->
                listOf(
                    Triple("CONTROL", BotEvaluationMode.CONTROL, false),
                    Triple("POSITIONAL", BotEvaluationMode.POSITIONAL, false),
                    Triple("POSITIONAL_HISTORY", BotEvaluationMode.POSITIONAL, true),
                ).forEach { (label, mode, useHistory) ->
                    if (appBudget && mode == BotEvaluationMode.CONTROL) return@forEach
                    if (System.getenv("CHESSTREE_BOT_LONG_VARIANT")?.let { it != label } == true) return@forEach
                    var state = start.state
                    val recent = mutableListOf(state)
                    val seen = mutableSetOf(state.withoutPly())
                    repeat(plyLimit) {
                        if (state.phase != GamePhase.InProgress) return@repeat
                        val before = state
                        val started = TimeSource.Monotonic.markNow()
                        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                            profile = profile, profileCatalogVersion = catalog,
                            maxDepth = depth, maxNodes = if (appBudget) 20_000 else 128,
                            maxEvaluations = if (appBudget) 20_000 else 128,
                            tacticalDepth = if (appBudget && useHistory) q else 0,
                            searchModel = if (useHistory) model else BotSearchModel.MAX_N,
                            evaluation = mode, openingBookVersion = 1,
                        ).choose(before, recentPositions = if (useHistory) recent.takeLast(MAX_BOT_RECENT_POSITIONS) else emptyList(),
                            stop = BotStopProbe {
                                if (appBudget && !fixedWork && started.elapsedNow().inWholeMilliseconds >= 900) BotStopSignal.TIMEOUT
                                else BotStopSignal.CONTINUE
                            }))
                        val elapsed = started.elapsedNow().inWholeNanoseconds
                        val moving = before.position.pieces.values.single { it.coordinate == result.intent.from }
                        val transition = assertIs<MoveReduction.Applied>(GameReducer.reduce(before, result.intent))
                        state = transition.state
                        recent += state
                        writer.appendLine(listOf(
                            start.id, start.provenance, label, before.turn?.ply, result.intent.actor,
                            moving.type, "${ThreePlayerBoardNotation.square(result.intent.from)}>${ThreePlayerBoardNotation.square(result.intent.to)}",
                            transition.move.capturedPieceId != null, result.intent.promotion != null,
                            result.stats.completedDepth, result.source == BotMoveSource.LEGAL_FALLBACK,
                            !seen.add(state.withoutPly()),
                            recent.dropLast(1).takeLast(MAX_BOT_RECENT_POSITIONS).any { it.withoutPly() == state.withoutPly() },
                            result.repetitionPenalty, elapsed, result.stats.completedHorizon, result.reason, state.phase,
                        ).joinToString("\t"))
                    }
                }
            }
        }
    }

    private fun GameState.withoutPly(): GameState = GameState(
        position = position, participants = participants, armies = armies,
        turn = turn?.copy(ply = 1), phase = phase,
    )
}
