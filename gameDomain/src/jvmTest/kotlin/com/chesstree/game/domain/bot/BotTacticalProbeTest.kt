package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Opt-in descriptive resource comparison; no tournament or strength assertion. */
class BotTacticalProbeTest {
    private val model = System.getenv("CHESSTREE_BOT_TACTICAL_MODEL")?.let(BotSearchModel::valueOf) ?: BotSearchModel.MAX_N
    @Test
    fun optInMatchedTacticalResourceComparison() {
        val enabled = System.getProperty("chesstree.bot.tacticalProbe").equals("true", ignoreCase = true) ||
            System.getenv("CHESSTREE_BOT_TACTICAL_PROBE").equals("true", ignoreCase = true)
        if (!enabled) {
            println("Tactical resource probe skipped; enable CHESSTREE_BOT_TACTICAL_PROBE=true")
            return
        }
        val fixtures = reachableOpeningFixtures() + strategicTrajectoryFixtures() + listOf(
            Fixture("forced-third-recapture", "synthetic;StandardGame reachability unproven", forcedRecapture()),
            Fixture(
                "late-white-changed-file", "synthetic ArenaPilotFixtures.changedFile;reachability unproven",
                ArenaPilotFixtures.starts().single { it.id == "white-changed-file" }.state,
            ),
        )
        val report = mutableListOf(
            "# report_schema\tbot-tactical-resource-v1",
            "# scope\tseven first-legal opening states,six positional-policy trajectory states,two sparse synthetic states;one sample per configuration;no strength assertion",
            "# baseline_trajectory\tStandardGame then six first legal moves in generator order;matches BotBaselineExperimentTest trajectory",
            "# search\tBoundedMaxNBot;model=$model;normal_depth=3;tactical_depth=0,2,4;node_and_evaluation_caps=128,20000;soft_deadline_ms=900;seed=0",
            "# evaluation\tPOSITIONAL;version=${BotEvaluationMode.POSITIONAL.version};book_version=${BotOpeningBook.VERSION};no learning",
            "# policy\t${BotPolicyCodec.encode(BotPolicy.DEFAULT).trim().replace('\n', ';')}",
            "# forced_fixture\tWHITE rook G10/king F12;RED pawn G12/king A2;BLACK bishop H11/king H12;all pieces moved;no special rights",
            "# late_fixture\tArenaPilotFixtures.starts white-changed-file;WHITE pawn K9;WHITE/RED/BLACK kings H12/A1/N8;RED/BLACK pawns D3/K6;ply293",
            "# timing\tTimeSource.Monotonic;root legality and evaluator work are synchronous;900ms is a cooperative soft deadline, elapsed time may exceed it",
            "# order\ttactical configuration order alternates by fixture;one unrecorded initial q0 warmup;JIT and thermal drift remain possible",
            "# runtime\tjava=${System.getProperty("java.version")};os=${System.getProperty("os.name")};arch=${System.getProperty("os.arch")}",
            "fixture\tprovenance\tactor\troot_legal\twork_cap\ttactical_depth\tcompleted_horizon\telapsed_ns\texpanded_nodes\tleaf_evaluations\tcompleted_depth\tsource\tstop_reason\tbook_version\tbook_influenced\tmove\tpublic_legal\treplay_prefix",
        )

        runSearch(fixtures.first().state, workCap = 128, tacticalDepth = 0)
        fixtures.forEachIndexed { index, fixture ->
            val original = fixture.state.toString()
            val rootLegal = LegalMoveGenerator.legalMoves(fixture.state).size
            assertTrue(rootLegal > 0)
            val depths = if (index % 2 == 0) listOf(0, 2, 4) else listOf(4, 2, 0)
            listOf(128, 20_000).forEach { workCap ->
                depths.forEach { tacticalDepth ->
                    val (result, elapsed) = runSearch(fixture.state, workCap, tacticalDepth)
                    assertTrue(result.stats.expandedNodes <= workCap)
                    assertTrue(result.stats.leafEvaluations <= workCap)
                    assertTrue(result.stats.completedDepth in 0..3)
                    assertIs<MoveReduction.Applied>(GameReducer.reduce(fixture.state, result.intent))
                    assertEquals(original, fixture.state.toString())
                    report += listOf(
                        fixture.id, fixture.provenance, checkNotNull(fixture.state.turn).player.name,
                        rootLegal, workCap, tacticalDepth, result.stats.completedHorizon.name, elapsed, result.stats.expandedNodes,
                        result.stats.leafEvaluations, result.stats.completedDepth, result.source.name,
                        result.reason.name, result.openingBook.version, result.openingBook.influenced,
                        result.intent.token(), true, fixture.prefix.joinToString(";") { it.token() },
                    ).joinToString("\t")
                }
            }
        }
        val destination = File("build/reports/bot-baseline/tactical-resource.tsv")
        destination.parentFile.mkdirs()
        destination.writeText(report.joinToString("\n", postfix = "\n"))
        println("Tactical resource comparison: ${fixtures.size * 6} legal choices; ${destination.path}")
    }

    private fun runSearch(
        state: GameState,
        workCap: Int,
        tacticalDepth: Int,
    ): Pair<BoundedBotResult.Move, Long> {
        val started = TimeSource.Monotonic.markNow()
        val result = BoundedMaxNBot(
            maxDepth = 3,
            maxNodes = workCap,
            maxEvaluations = workCap,
            evaluation = BotEvaluationMode.POSITIONAL,
            openingBookVersion = BotOpeningBook.VERSION,
            seed = 0,
            tacticalDepth = tacticalDepth,
            searchModel = model,
        ).choose(
            state,
            stop = BotStopProbe {
                if (started.elapsedNow() >= 900.milliseconds) BotStopSignal.TIMEOUT else BotStopSignal.CONTINUE
            },
        )
        return assertIs<BoundedBotResult.Move>(result) to started.elapsedNow().inWholeNanoseconds
    }

    private fun reachableOpeningFixtures(): List<Fixture> {
        var state = StandardGame.scenario.initialState
        val prefix = mutableListOf<MoveIntent>()
        return buildList {
            repeat(7) { index ->
                add(Fixture("opening-$index", "reachable;first-legal public-reducer replay", state, prefix.toList()))
                if (index == 6) return@repeat
                val move = LegalMoveGenerator.legalMoves(state).first()
                val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
                state = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent)).state
                prefix += intent
            }
        }
    }

    private fun strategicTrajectoryFixtures(): List<Fixture> {
        var state = StandardGame.scenario.initialState
        val prefix = mutableListOf<MoveIntent>()
        return buildList {
            repeat(36) { index ->
                if (state.phase != GamePhase.InProgress) return@repeat
                val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                    maxDepth = 2, maxNodes = 128, maxEvaluations = 128,
                    evaluation = BotEvaluationMode.POSITIONAL, openingBookVersion = 1,
                ).choose(state))
                state = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, result.intent)).state
                prefix += result.intent
                if ((index + 1) % 6 == 0 && state.phase == GamePhase.InProgress) {
                    add(Fixture("strategic-${index + 1}", "reachable;positional q0 fixed128 replay", state, prefix.toList()))
                }
            }
        }
    }

    private fun forcedRecapture(): GameState {
        val pieces = listOf(
            piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "G10"),
            piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
            piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "G12"),
            piece("red-king", PieceType.KING, ArmyColor.RED, "A2"),
            piece("black-bishop", PieceType.BISHOP, ArmyColor.BLACK, "H11"),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
        )
        return GameState(
            position = Position(pieces.associateBy(Piece::id)),
            participants = PlayerId.entries.associateWith(::Participant),
            armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            turn = Turn(PlayerId.WHITE, ply = 1),
            phase = GamePhase.InProgress,
        )
    }

    private fun piece(id: String, type: PieceType, army: ArmyColor, label: String): Piece =
        Piece(PieceId(id), type, army, checkNotNull(ThreePlayerBoardNotation.parse(label)), hasMoved = true)

    private fun MoveIntent.token(): String =
        "$actor:${ThreePlayerBoardNotation.square(from)}>${ThreePlayerBoardNotation.square(to)}:" +
            (promotion?.name ?: "-")

    private data class Fixture(
        val id: String,
        val provenance: String,
        val state: GameState,
        val prefix: List<MoveIntent> = emptyList(),
    )
}
