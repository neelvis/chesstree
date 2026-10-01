package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Synthetic late-game diagnostics, not a forced-win or strength corpus. */
class BotEndgameProbeTest {
    @Test
    fun recordsLegalLateGameControlsAndBoundedRollouts() {
        val fixtures = listOf(
            Fixture("three-free-pawns", fixture(), "E10", "E11", defensive = false),
            Fixture("white-changed-file", fixture(whitePawn = "K9"), "K9", "K10", defensive = false),
            Fixture("king-defense-first", fixture(defend = true), "E10", "E11", defensive = true),
        )
        val report = mutableListOf(
            "# report_schema\tbot-endgame-probe-v2",
            "# provenance\tsynthetic full-state constructors; StandardGame reachability unproven; not reconstructed from video",
            "# purpose\trecord positive or negative king-shuffle reproduction; no objectively forced win or strength claim",
            "# search\tMaxNBot and BoundedMaxNBot maxDepth=2,maxNodes=128,maxEvaluations=128; no learning",
            "# evaluation\tCONTROL/version=1;PAWN_PROGRESS/version=2;every search/evaluation row identifies its mode",
            "# comparison\tbounded CONTROL and PAWN_PROGRESS use identical full initial states, policies, iterative allocation and work caps; legacy MaxN is a separate diagnostic",
            "# scope\tsynthetic comparison only; independent of app depth/time/work settings; no strength acceptance claim",
            "# policy\t${BotPolicyCodec.encode(BotPolicy.DEFAULT).trim().replace('\n', ';')}",
            "# root_leaf\tstatic evaluation after public reducer; not depth-2 backed-up root values",
            "# recurrence\tfull Position/participants/armies/actor/phase equality, excluding Turn.ply; diagnostic only, not a draw",
            "# runtime\tjava=${System.getProperty("java.version")};os=${System.getProperty("os.name")};arch=${System.getProperty("os.arch")}",
            "kind\tfixture\tply\tactor\tmove\tpiece_type\tscore_white\tscore_red\tscore_black\tnodes\tevaluations\tcompleted_depth\tsource\tstop_reason\telapsed_ns\tdetail",
        )
        val criterionFailures = mutableListOf<String>()

        fixtures.forEach { fixture ->
            val original = fixture.state.toString()
            record(report, "initial_state", fixture, fixture.state, detail = original)
            verifyFixture(fixture)
            record(report, "fixture_validation", fixture, fixture.state, detail = "passed;defensive=${fixture.defensive}")

            LegalMoveGenerator.legalMoves(fixture.state).forEach { move ->
                val after = apply(fixture.state, move.intent())
                val knownLegalMoves = if (after.phase == GamePhase.InProgress) {
                    LegalMoveGenerator.legalMoves(after)
                } else emptyList()
                evaluationModes.forEach { evaluation ->
                    record(
                        report, "root_leaf", fixture, fixture.state, move.intent(),
                        score = evaluation.evaluate(after, BotPolicy.DEFAULT, knownLegalMoves),
                        detail = "${evaluation.tag()};result_phase=${after.phase};pawn_steps=${pawnSteps(after)}",
                    )
                }
            }

            val legacyStarted = System.nanoTime()
            val legacy = assertNotNull(
                MaxNBot(maxDepth = 2, maxNodes = 128, maxEvaluations = 128)
                    .chooseDecisionWithStats(fixture.state, BotPolicy.DEFAULT),
            )
            val legacyElapsed = System.nanoTime() - legacyStarted
            apply(fixture.state, legacy.decision.intent)
            assertTrue(legacy.stats.expandedNodes <= 128)
            assertTrue(legacy.stats.leafEvaluations <= 128)
            record(
                report, "legacy_choice", fixture, fixture.state, legacy.decision.intent,
                nodes = legacy.stats.expandedNodes, evaluations = legacy.stats.leafEvaluations,
                elapsed = legacyElapsed,
                detail = "${BotEvaluationMode.CONTROL.tag()};fixed_depth=2;partial_budget_allocation;not_completed_iteration_metadata",
            )

            evaluationModes.forEach { evaluation ->
                val boundedStarted = System.nanoTime()
                val bounded = assertMove(boundedBot(evaluation).choose(fixture.state, BotPolicy.DEFAULT))
                val boundedElapsed = System.nanoTime() - boundedStarted
                verifyBoundedChoice(fixture, fixture.state, bounded)
                recordBounded(
                    report, "bounded_choice", fixture, fixture.state, bounded, boundedElapsed,
                    detail = evaluation.tag(),
                )
                val firstWhitePawnTurn = recordRollout(report, fixture, evaluation)
                if (fixture.id == "white-changed-file" && evaluation == BotEvaluationMode.PAWN_PROGRESS) {
                    val passed = firstWhitePawnTurn != null && firstWhitePawnTurn <= 3
                    record(
                        report, "candidate_criterion", fixture, fixture.state,
                        detail = "${evaluation.tag()};criterion=first_white_pawn_turn<=3;" +
                                "observed=${firstWhitePawnTurn ?: "none"};passed=$passed;not_a_strength_guarantee",
                    )
                    if (!passed) criterionFailures += "${fixture.id}: candidate first WHITE pawn turn=$firstWhitePawnTurn"
                }
            }
            assertEquals(original, fixture.state.toString(), "Probe mutated ${fixture.id}")
        }

        val destination = File("build/reports/bot-baseline/endgame-probe.tsv")
        destination.parentFile.mkdirs()
        destination.writeText(report.joinToString("\n", postfix = "\n"))
        assertTrue(criterionFailures.isEmpty(), criterionFailures.joinToString("; "))
    }

    private fun verifyFixture(fixture: Fixture) {
        val state = fixture.state
        assertEquals(PlayerId.entries.toSet(), state.participants.filterValues {
            it.status == ParticipantStatus.Active
        }.keys)
        val pawnIntent = MoveIntent(PlayerId.WHITE, at(fixture.pawnFrom), at(fixture.pawnTo))
        val legal = LegalMoveGenerator.legalMoves(state)
        if (fixture.defensive) {
            assertTrue(LegalMoveGenerator.isKingInCheck(state, PlayerId.WHITE))
            assertFalse(legal.any { it.intent() == pawnIntent })
            assertTrue(GameReducer.reduce(state, pawnIntent) is MoveReduction.Rejected)
            val escape = MoveIntent(PlayerId.WHITE, at("H12"), at("G12"))
            assertTrue(legal.any { it.intent() == escape })
            assertFalse(LegalMoveGenerator.isKingInCheck(apply(state, escape), PlayerId.WHITE))
        } else {
            assertFalse(LegalMoveGenerator.isKingInCheck(state, PlayerId.WHITE))
            assertTrue(legal.any { it.intent() == pawnIntent }, "Pawn progression absent: ${fixture.id}")
            apply(state, pawnIntent)
            val kingCycle = listOf(
                MoveIntent(PlayerId.WHITE, at("H12"), at("G12")),
                MoveIntent(PlayerId.RED, at("A1"), at("B1")),
                MoveIntent(PlayerId.BLACK, at("N8"), at("M8")),
                MoveIntent(PlayerId.WHITE, at("G12"), at("H12")),
                MoveIntent(PlayerId.RED, at("B1"), at("A1")),
                MoveIntent(PlayerId.BLACK, at("M8"), at("N8")),
            )
            var cycled = state
            kingCycle.forEach { intent ->
                assertTrue(LegalMoveGenerator.legalMoves(cycled).any { it.intent() == intent })
                cycled = apply(cycled, intent)
            }
            assertEquals(state.key(), cycled.key(), "Script did not return: ${fixture.id}")
            assertEquals(checkNotNull(state.turn).ply + 6, checkNotNull(cycled.turn).ply)
        }
    }

    private fun recordRollout(
        report: MutableList<String>, fixture: Fixture, evaluation: BotEvaluationMode,
    ): Int? {
        var state = fixture.state
        val seen = mutableSetOf(state.key())
        var repeats = 0
        var kingMoves = 0
        var pawnMoves = 0
        var promotions = 0
        var whiteTurns = 0
        var firstWhitePawnTurn: Int? = null
        var played = 0
        repeat(12) {
            if (state.phase != GamePhase.InProgress) return@repeat
            val actor = checkNotNull(state.turn).player
            val started = System.nanoTime()
            val result = assertMove(boundedBot(evaluation).choose(state, BotPolicy.DEFAULT))
            val elapsed = System.nanoTime() - started
            verifyBoundedChoice(fixture, state, result)
            val moving = state.position.pieces.values.single { it.coordinate == result.intent.from }
            if (actor == PlayerId.WHITE) whiteTurns++
            if (moving.type == PieceType.KING) kingMoves++
            if (moving.type == PieceType.PAWN) {
                pawnMoves++
                if (actor == PlayerId.WHITE && firstWhitePawnTurn == null) firstWhitePawnTurn = whiteTurns
            }
            if (result.intent.promotion != null) promotions++
            val after = apply(state, result.intent)
            if (!seen.add(after.key())) repeats++
            recordBounded(
                report, "rollout_move", fixture, state, result, elapsed,
                "${evaluation.tag()};step=${played + 1};pawn_steps_after=${pawnSteps(after)};phase_after=${after.phase}",
            )
            played++
            state = after
        }
        record(
            report, "rollout_summary", fixture, state,
            detail = "${evaluation.tag()};plies=$played;king_moves=$kingMoves;pawn_moves=$pawnMoves;promotions=$promotions;" +
                    "repeated_positions=$repeats;white_turns=$whiteTurns;" +
                    "first_white_pawn_turn=${firstWhitePawnTurn ?: "none"};" +
                    "king_only_no_pawn_progress=${played > 0 && kingMoves == played && pawnMoves == 0};" +
                    "final_pawn_steps=${pawnSteps(state)};final_phase=${state.phase}",
        )
        return firstWhitePawnTurn
    }

    private fun boundedBot(evaluation: BotEvaluationMode): BoundedMaxNBot =
        BoundedMaxNBot(maxDepth = 2, maxNodes = 128, maxEvaluations = 128, evaluation = evaluation)

    private fun BotEvaluationMode.tag(): String = "evaluation=$name;eval_version=$version"

    private fun verifyBoundedChoice(fixture: Fixture, state: GameState, result: BoundedBotResult.Move) {
        assertTrue(result.stats.expandedNodes <= 128)
        assertTrue(result.stats.leafEvaluations <= 128)
        assertTrue(result.stats.completedDepth in 0..2)
        assertTrue(LegalMoveGenerator.legalMoves(state).any { it.intent() == result.intent })
        val after = apply(state, result.intent)
        assertFalse(LegalMoveGenerator.isKingInCheck(after, result.intent.actor))
        if (fixture.defensive && state == fixture.state) {
            assertEquals(PieceType.KING, state.position.pieces.values.single {
                it.coordinate == result.intent.from
            }.type)
        }
    }

    private fun recordBounded(
        report: MutableList<String>, kind: String, fixture: Fixture, state: GameState,
        result: BoundedBotResult.Move, elapsed: Long, detail: String = "",
    ) = record(
        report, kind, fixture, state, result.intent,
        nodes = result.stats.expandedNodes, evaluations = result.stats.leafEvaluations,
        depth = result.stats.completedDepth, source = result.source.name,
        reason = result.reason.name, elapsed = elapsed, detail = detail,
    )

    private fun record(
        report: MutableList<String>, kind: String, fixture: Fixture, state: GameState,
        intent: MoveIntent? = null, score: DoubleArray? = null, nodes: Int? = null,
        evaluations: Int? = null, depth: Int? = null, source: String = "-",
        reason: String = "-", elapsed: Long? = null, detail: String = "",
    ) {
        val moving = intent?.let { move -> state.position.pieces.values.single { it.coordinate == move.from } }
        report += listOf(
            kind, fixture.id, state.turn?.ply ?: "-", intent?.actor?.name ?: state.turn?.player?.name ?: "-",
            intent?.token() ?: "-", moving?.type?.name ?: "-",
            score?.get(PlayerId.WHITE.ordinal) ?: "-", score?.get(PlayerId.RED.ordinal) ?: "-",
            score?.get(PlayerId.BLACK.ordinal) ?: "-", nodes ?: "-", evaluations ?: "-",
            depth ?: "-", source, reason, elapsed ?: "-", detail.replace('\t', ' ').replace('\n', ' '),
        ).joinToString("\t")
    }

    private fun pawnSteps(state: GameState): String = state.position.pieces.values
        .filter { it.type == PieceType.PAWN }.sortedBy { it.id.value }.joinToString(",") { pawn ->
            val remaining = MovementDirections.forPiece(PieceType.PAWN, pawn.coordinate, pawn.army)
                .singleOrNull { it.kind == DirectionKind.MOVE }?.route?.let { it.size - 1 } ?: 0
            "${pawn.id.value}:$remaining"
        }.ifEmpty { "none" }

    private fun assertMove(result: BoundedBotResult): BoundedBotResult.Move {
        assertTrue(result is BoundedBotResult.Move, "Expected move, got $result")
        return result
    }

    private fun apply(state: GameState, intent: MoveIntent): GameState {
        val result = GameReducer.reduce(state, intent)
        assertTrue(result is MoveReduction.Applied, "Rejected ${intent.token()}: $result")
        return result.state
    }

    private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)

    private fun MoveIntent.token(): String =
        "${actor.name}:${ThreePlayerBoardNotation.square(from)}>${ThreePlayerBoardNotation.square(to)}:" +
                (promotion?.name ?: "-")

    private fun at(label: String): BoardCoordinate = requireNotNull(ThreePlayerBoardNotation.parse(label))

    private fun moved(id: String, army: ArmyColor, type: PieceType, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)

    private fun fixture(whitePawn: String = "E10", defend: Boolean = false): GameState {
        val pieces = listOfNotNull(
            moved("white-back-king", ArmyColor.WHITE, PieceType.KING, "H12"),
            moved("red-back-king", ArmyColor.RED, PieceType.KING, "A1"),
            moved("black-back-king", ArmyColor.BLACK, PieceType.KING, "N8"),
            moved("white-pawn-progress", ArmyColor.WHITE, PieceType.PAWN, whitePawn),
            moved("red-pawn-progress", ArmyColor.RED, PieceType.PAWN, "D3"),
            moved("black-pawn-progress", ArmyColor.BLACK, PieceType.PAWN, "K6"),
            if (defend) moved("red-back-rook", ArmyColor.RED, PieceType.ROOK, "H10") else null,
        )
        return GameState(
            position = Position(
                pieces = pieces.associateBy(Piece::id),
                castlingRights = emptySet(),
                enPassantTargets = emptyList(),
            ),
            participants = PlayerId.entries.associateWith { Participant(it, ParticipantStatus.Active) },
            armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            turn = Turn(PlayerId.WHITE, ply = 293),
            phase = GamePhase.InProgress,
        )
    }

    private fun GameState.key(): DiagnosticPosition =
        DiagnosticPosition(position, participants, armies, turn?.player, phase)

    private data class DiagnosticPosition(
        val position: Position,
        val participants: Map<PlayerId, Participant>,
        val armies: Map<ArmyColor, ArmyControl>,
        val actor: PlayerId?,
        val phase: GamePhase,
    )

    private data class Fixture(
        val id: String,
        val state: GameState,
        val pawnFrom: String,
        val pawnTo: String,
        val defensive: Boolean,
    )

    private companion object {
        val evaluationModes = listOf(BotEvaluationMode.CONTROL, BotEvaluationMode.PAWN_PROGRESS)
    }
}
