package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BotSearchDiagnosticsTest {
    @Test
    fun optInDiagnosticsPreserveChoicesCallbacksAndStopProbeCountsForBothModels() {
        BotSearchModel.entries.forEach { model ->
            val result = compare(forceRecapture(), BoundedMaxNBot(
                maxDepth = 1, maxNodes = 10_000, maxEvaluations = 10_000,
                tacticalDepth = 2, searchModel = model,
            ))
            assertTrue(result.diagnostics.work.normalNodes > 0)
            assertTrue(result.diagnostics.work.tacticalNodes > 0)
            assertTrue(result.diagnostics.work.normalEvaluations > 0)
            assertTrue(result.diagnostics.work.tacticalEvaluations > 0)
            assertEquals(listOf(BotSearchHorizon.CHECKS, BotSearchHorizon.EXCHANGES),
                result.diagnostics.attempts.map { it.horizon })
            assertTrue(result.diagnostics.attempts.all { it.stop == BotSearchAttemptStop.COMPLETED })
            assertEquals(result.publications.size, result.diagnostics.attempts.size)
        }
    }

    @Test
    fun staticSearchChargesNoTacticalWork() {
        val result = compare(StandardGame.scenario.initialState, BoundedMaxNBot(maxDepth = 1))
        assertEquals(0, result.diagnostics.work.tacticalNodes)
        assertEquals(0, result.diagnostics.work.tacticalEvaluations)
        assertEquals(BotSearchHorizon.STATIC, result.diagnostics.attempts.single().horizon)
    }

    @Test
    fun aLocalCapRecordsTheUnresolvedThirdPlayerAndDoesNotPublishPartialWork() {
        val result = compare(forceRecapture(), BoundedMaxNBot(
            maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 1,
        ))
        assertEquals(BotSearchAttemptStop.TACTICAL_LIMIT, result.diagnostics.stop)
        assertTrue(result.publications.isEmpty())
        val attempt = result.diagnostics.attempts.single()
        assertEquals(BotSearchAttemptStop.TACTICAL_LIMIT, attempt.stop)
        assertEquals(1, attempt.depth)
        assertEquals(BotSearchHorizon.CHECKS, attempt.horizon)
        val cutoff = assertNotNull(attempt.tacticalCutoff)
        assertEquals(PlayerId.BLACK, cutoff.actor)
        assertEquals(setOf(PlayerId.BLACK), cutoff.checkedPlayers)
        assertEquals(emptySet(), cutoff.responders)
        assertEquals(0, cutoff.remaining)
        assertEquals(3, cutoff.ply)
    }

    @Test
    fun retryDiagnosticsSeparateDiscardedAttemptsFromCompletedPublications() {
        val result = compare(forceRecapture(), BoundedMaxNBot(
            maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000, tacticalDepth = 1,
        ))
        assertEquals(BotSearchAttemptStop.TACTICAL_LIMIT, result.diagnostics.attempts.first().stop)
        assertTrue(result.diagnostics.attempts.size <= 4)
        val completed = result.diagnostics.attempts.filter { it.stop == BotSearchAttemptStop.COMPLETED }
        assertEquals(result.publications.map { it.stats.completedDepth to it.stats.completedHorizon },
            completed.map { it.depth to it.horizon })
        assertTrue(result.publications.all { it.stats.completedDepth == 2 })
    }

    @Test
    fun cancellationBeforeSearchStillEmitsAnEmptyReport() {
        var diagnostics: BotSearchDiagnostics? = null
        BoundedMaxNBot().chooseWithDiagnostics(StandardGame.scenario.initialState,
            stop = BotStopProbe { BotStopSignal.CANCEL }, diagnostics = { diagnostics = it })
        val report = assertNotNull(diagnostics)
        assertEquals(BotSearchAttemptStop.CANCELLED, report.stop)
        assertEquals(BotSearchWork(), report.work)
        assertTrue(report.attempts.isEmpty())
    }

    @Test
    fun globalLimitsHavePreciseDiscardReasons() {
        for ((bot, expected) in listOf(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 2, maxEvaluations = 256, tacticalDepth = 2) to
                BotSearchAttemptStop.NODE_LIMIT,
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 8, tacticalDepth = 2) to
                BotSearchAttemptStop.EVALUATION_LIMIT,
        )) {
            val result = compare(forceRecapture(), bot)
            assertEquals(expected, result.diagnostics.stop)
            assertEquals(expected, result.diagnostics.attempts.last().stop)
            assertTrue(result.publications.isEmpty())
        }
    }

    @Test
    fun cancellationAndTimeoutAfterCompletionRetainOnlyCompletedPublications() {
        for ((signal, expected) in listOf(
            BotStopSignal.CANCEL to BotSearchAttemptStop.CANCELLED,
            BotStopSignal.TIMEOUT to BotSearchAttemptStop.TIMEOUT,
        )) {
            val result = compare(StandardGame.scenario.initialState, BoundedMaxNBot(maxDepth = 2), signal)
            assertEquals(expected, result.diagnostics.stop)
            assertEquals(listOf(BotSearchAttemptStop.COMPLETED, expected),
                result.diagnostics.attempts.map { it.stop })
            assertEquals(1, result.publications.size)
            assertEquals(BotSearchWork(), result.diagnostics.attempts.last().work)
        }
    }

    private data class Observation(
        val result: BoundedBotResult,
        val publications: List<BoundedBotResult.Move>,
        val diagnostics: BotSearchDiagnostics,
    )

    private fun compare(
        state: GameState,
        bot: BoundedMaxNBot,
        signalAfterCompletion: BotStopSignal = BotStopSignal.CONTINUE,
    ): Observation {
        val controlPublications = mutableListOf<BoundedBotResult.Move>()
        var controlProbes = 0
        val control = bot.choose(state, stop = BotStopProbe {
            controlProbes++
            if (controlPublications.isEmpty()) BotStopSignal.CONTINUE else signalAfterCompletion
        }, onCompletedDepth = controlPublications::add)
        val publications = mutableListOf<BoundedBotResult.Move>()
        var probes = 0
        var emitted = 0
        var diagnostics: BotSearchDiagnostics? = null
        val result = bot.chooseWithDiagnostics(state, stop = BotStopProbe {
            probes++
            if (publications.isEmpty()) BotStopSignal.CONTINUE else signalAfterCompletion
        }, onCompletedDepth = publications::add, diagnostics = {
            emitted++
            diagnostics = it
        })
        assertEquals(control, result)
        assertEquals(controlPublications, publications)
        assertEquals(controlProbes, probes)
        assertEquals(1, emitted)
        val report = assertNotNull(diagnostics)
        val stats = when (result) {
            is BoundedBotResult.Move -> result.stats
            is BoundedBotResult.Cancelled -> result.stats
            else -> error("Expected search work")
        }
        assertEquals(stats.expandedNodes, report.work.normalNodes + report.work.tacticalNodes)
        assertEquals(stats.leafEvaluations, report.work.normalEvaluations + report.work.tacticalEvaluations)
        assertEquals(report.work.normalNodes, report.attempts.sumOf { it.work.normalNodes })
        assertEquals(report.work.tacticalNodes, report.attempts.sumOf { it.work.tacticalNodes })
        assertEquals(report.work.normalEvaluations, report.attempts.sumOf { it.work.normalEvaluations })
        assertEquals(report.work.tacticalEvaluations, report.attempts.sumOf { it.work.tacticalEvaluations })
        return Observation(result, publications.toList(), report)
    }

    private fun forceRecapture(): GameState {
        fun piece(id: String, type: PieceType, army: ArmyColor, label: String) = Piece(
            PieceId(id), type, army, checkNotNull(ThreePlayerBoardNotation.parse(label)),
        )
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
}
