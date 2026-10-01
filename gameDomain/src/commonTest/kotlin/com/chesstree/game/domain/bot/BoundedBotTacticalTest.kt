package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Synthetic legal positions; their reachability from StandardGame is not asserted. */
class BoundedBotTacticalTest {
    private val pressurePolicy = BotPolicy(listOf(
        BotRule(BotFeature.MATERIAL, weight = 100),
        BotRule(BotFeature.CHECK_PRESSURE, weight = 40),
    ))

    @Test
    fun tacticalDepthIsBoundedAndZeroPreservesTheControl() {
        assertFailsWith<IllegalArgumentException> { BoundedMaxNBot(tacticalDepth = -1) }
        assertFailsWith<IllegalArgumentException> { BoundedMaxNBot(tacticalDepth = 7) }
        val state = forcedRecapture()
        val implicitCallbacks = mutableListOf<BoundedBotResult.Move>()
        val explicitCallbacks = mutableListOf<BoundedBotResult.Move>()
        val implicit = BoundedMaxNBot(maxDepth = 2).choose(
            state, pressurePolicy, onCompletedDepth = implicitCallbacks::add,
        )
        val explicit = BoundedMaxNBot(maxDepth = 2, tacticalDepth = 0).choose(
            state, pressurePolicy, onCompletedDepth = explicitCallbacks::add,
        )
        assertEquals(implicit, explicit)
        assertEquals(implicitCallbacks, explicitCallbacks)
    }

    @Test
    fun aQuietInterveningActorMovesBeforeTheForcedRecapture() {
        val initial = forcedRecapture()
        assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(initial, it) })
        val capture = apply(initial, intent(PlayerId.WHITE, "G10", "G12"))
        assertEquals(MoveType.CAPTURE, capture.move.type)
        assertEquals(PlayerId.RED, capture.state.turn?.player)
        assertFalse(LegalMoveGenerator.isKingInCheck(capture.state, PlayerId.RED))
        assertTrue(LegalMoveGenerator.isKingInCheck(capture.state, PlayerId.BLACK))
        val redResponses = LegalMoveGenerator.legalMoves(capture.state)
        assertEquals(4, redResponses.size)
        assertTrue(redResponses.all { it.type == MoveType.QUIET })

        redResponses.forEach { response ->
            val afterQuiet = apply(capture.state, response.intent()).state
            assertEquals(PlayerId.BLACK, afterQuiet.turn?.player)
            assertTrue(LegalMoveGenerator.isKingInCheck(afterQuiet, PlayerId.BLACK))
            val recapture = LegalMoveGenerator.legalMoves(afterQuiet).single()
            assertEquals(intent(PlayerId.BLACK, "H11", "G12"), recapture.intent())
            val settled = apply(afterQuiet, recapture.intent()).state
            assertEquals(PlayerId.WHITE, settled.turn?.player)
            assertFalse(PieceId("a-white-rook") in settled.position.pieces)
            assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(settled, it) })
        }
    }

    @Test
    fun tacticalContinuationRejectsTheShallowCheckingCapture() {
        val state = forcedRecapture()
        val losingCapture = intent(PlayerId.WHITE, "G10", "G12")
        val shallow = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 256)
                .choose(state, pressurePolicy),
        )
        assertEquals(losingCapture, shallow.intent)

        // Independent complete three-ply MaxN through public intents establishes
        // which choices preserve the rook, without reproducing tactical horizons.
        val reference = LegalMoveGenerator.legalMoves(state).associate { move ->
            move.intent() to exhaustive(apply(state, move.intent()).state, remaining = 2)
        }
        assertEquals(0.0, reference.getValue(losingCapture)[PlayerId.WHITE.ordinal])
        val preserving = reference.filterValues { it[PlayerId.WHITE.ordinal] >= 500.0 }.keys
        assertTrue(preserving.isNotEmpty())
        BotSearchModel.entries.forEach { model ->
            for (catalog in 1..2) {
            val candidate = assertIs<BoundedBotResult.Move>(
                BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 2,
                    searchModel = model, profileCatalogVersion = catalog)
                    .choose(state, pressurePolicy),
            )
            assertEquals(BotMoveSource.COMPLETED_DEPTH, candidate.source)
            assertEquals(BotStopReason.DEPTH_COMPLETE, candidate.reason)
            assertEquals(1, candidate.stats.completedDepth)
            assertTrue(candidate.intent in preserving)
            assertTrue(candidate.intent != losingCapture)
            apply(state, candidate.intent)
            }
        }
    }

    @Test
    fun uncheckedCaptureStillRequiresBothActualOpponentsToRespond() {
        val state = uncheckedRecapture()
        val first = LegalMoveGenerator.legalMoves(state).first()
        assertEquals(intent(PlayerId.WHITE, "D4", "E4"), first.intent())
        val afterCapture = apply(state, first.intent()).state
        assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(afterCapture, it) })
        val afterRed = apply(afterCapture, intent(PlayerId.RED, "B1", "A1")).state
        assertEquals(PlayerId.BLACK, afterRed.turn?.player)
        val afterBlack = apply(afterRed, intent(PlayerId.BLACK, "F4", "E4")).state
        assertFalse(PieceId("a-white-rook") in afterBlack.position.pieces)

        val result = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 2, maxEvaluations = 256, tacticalDepth = 2)
                .choose(state),
        )
        // The check-safe pass also shares the same global node cap.
        assertEquals(BotStopReason.NODE_LIMIT, result.reason)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, result.source)
        assertEquals(2, result.stats.expandedNodes)
        assertEquals(7, result.stats.leafEvaluations)
        assertEquals(0, result.stats.completedDepth)
    }

    @Test
    fun unresolvedThirdPlayerCheckDiscardsTheWholeRootIteration() {
        val state = forcedRecapture()
        val completed = mutableListOf<BoundedBotResult.Move>()
        val result = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 1)
                .choose(state, pressurePolicy, onCompletedDepth = completed::add),
        )
        assertEquals(BotStopReason.TACTICAL_LIMIT, result.reason)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, result.source)
        assertTrue(result.intent != intent(PlayerId.WHITE, "G10", "G12"))
        apply(state, result.intent)
        assertEquals(0, result.stats.completedDepth)
        assertTrue(completed.isEmpty())
        // Seven earlier quiet root moves were evaluated. The checking capture
        // reaches BLACK through a quiet RED move and contributes no static leaf.
        assertEquals(7, result.stats.leafEvaluations)
    }

    @Test
    fun aCappedDeeperTacticalIterationRetainsTheCompletedDefense() {
        val state = defensivePosition()
        assertTrue(LegalMoveGenerator.isKingInCheck(state, PlayerId.WHITE))
        assertTrue(LegalMoveGenerator.legalMoves(state).all { it.type == MoveType.QUIET })
        val completed = mutableListOf<BoundedBotResult.Move>()
        val shallow = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 1)
                .choose(state),
        )
        val deeper = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 2, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 1)
                .choose(state, onCompletedDepth = completed::add),
        )
        assertEquals(BotStopReason.TACTICAL_LIMIT, deeper.reason)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, deeper.source)
        assertEquals(1, deeper.stats.completedDepth)
        assertEquals(shallow.intent, deeper.intent)
        assertEquals(shallow.openingBook, deeper.openingBook)
        assertEquals(listOf(BotSearchHorizon.CHECKS, BotSearchHorizon.EXCHANGES),
            completed.map { it.stats.completedHorizon })
        assertEquals(shallow, completed.last())
    }

    @Test
    fun timeoutBeforeExchangePassPreservesTheCompletedCheckSafeChoice() {
        val state = forcedRecapture()
        val completed = mutableListOf<BoundedBotResult.Move>()
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 2,
        ).choose(state, pressurePolicy,
            stop = BotStopProbe {
                if (completed.isEmpty()) BotStopSignal.CONTINUE else BotStopSignal.TIMEOUT
            }, onCompletedDepth = completed::add,
        ))
        assertEquals(1, completed.size)
        assertEquals(BotSearchHorizon.CHECKS, result.stats.completedHorizon)
        assertEquals(BotMoveSource.COMPLETED_DEPTH, result.source)
        assertEquals(BotStopReason.TIMEOUT, result.reason)
        assertEquals(completed.single().intent, result.intent)
        assertTrue(result.intent != intent(PlayerId.WHITE, "G10", "G12"))
        apply(state, result.intent)
    }

    @Test
    fun workLimitsDuringContinuationPublishNoPartialDepth() {
        val state = forcedRecapture()
        val completed = mutableListOf<BoundedBotResult.Move>()
        val nodeLimited = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 2, maxEvaluations = 256, tacticalDepth = 2)
                .choose(state, pressurePolicy, onCompletedDepth = completed::add),
        )
        assertEquals(BotStopReason.NODE_LIMIT, nodeLimited.reason)
        assertEquals(2, nodeLimited.stats.expandedNodes)
        assertEquals(7, nodeLimited.stats.leafEvaluations)
        val evaluationLimited = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 8, tacticalDepth = 2)
                .choose(state, pressurePolicy, onCompletedDepth = completed::add),
        )
        assertEquals(BotStopReason.EVALUATION_LIMIT, evaluationLimited.reason)
        assertEquals(8, evaluationLimited.stats.leafEvaluations)
        listOf(nodeLimited, evaluationLimited).forEach { result ->
            assertEquals(BotMoveSource.LEGAL_FALLBACK, result.source)
            assertEquals(0, result.stats.completedDepth)
            apply(state, result.intent)
        }
        assertTrue(completed.isEmpty())
    }

    @Test
    fun cancellationDuringContinuationReturnsCancellation() {
        var probes = 0
        val completed = mutableListOf<BoundedBotResult.Move>()
        val result = BoundedMaxNBot(
            maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 2,
        ).choose(
            forcedRecapture(), pressurePolicy,
            stop = BotStopProbe {
                probes++
                if (probes >= 54) BotStopSignal.CANCEL else BotStopSignal.CONTINUE
            },
            onCompletedDepth = completed::add,
        )
        val cancelled = assertIs<BoundedBotResult.Cancelled>(result)
        assertTrue(cancelled.stats.expandedNodes > 1)
        assertEquals(0, cancelled.stats.completedDepth)
        assertTrue(completed.isEmpty())
    }

    @Test
    fun timeoutDuringContinuationRetainsOnlyTheLegalFallback() {
        var probes = 0
        val state = forcedRecapture()
        val completed = mutableListOf<BoundedBotResult.Move>()
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 2,
        ).choose(
            state, pressurePolicy,
            stop = BotStopProbe {
                probes++
                if (probes >= 54) BotStopSignal.TIMEOUT else BotStopSignal.CONTINUE
            },
            onCompletedDepth = completed::add,
        ))
        assertEquals(BotStopReason.TIMEOUT, result.reason)
        assertEquals(BotMoveSource.LEGAL_FALLBACK, result.source)
        assertTrue(result.stats.expandedNodes > 1)
        assertEquals(0, result.stats.completedDepth)
        assertTrue(completed.isEmpty())
        apply(state, result.intent)
    }

    @Test
    fun mateTransitionTransfersTheArmyAndSkipsTheDefeatedActor() {
        val state = state(
            piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
            piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, "G11"),
            piece("red-king", PieceType.KING, ArmyColor.RED, "H12"),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
        )
        val afterMate = apply(state, intent(PlayerId.WHITE, "G11", "G12")).state
        val defeated = assertIs<ParticipantStatus.Checkmated>(
            afterMate.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(PlayerId.WHITE, defeated.by)
        assertEquals(PlayerId.WHITE, afterMate.armies.getValue(ArmyColor.RED).controller)
        assertEquals(PlayerId.BLACK, afterMate.turn?.player)
        assertFalse(PieceId("red-king") in afterMate.position.pieces)
        val decision = assertIs<BoundedBotResult.Move>(
            BoundedMaxNBot(maxDepth = 1, maxNodes = 256, maxEvaluations = 256, tacticalDepth = 2)
                .choose(afterMate),
        )
        assertEquals(PlayerId.BLACK, decision.intent.actor)
        val afterBlack = apply(afterMate, decision.intent).state
        assertEquals(PlayerId.WHITE, afterBlack.turn?.player)
    }

    @Test
    fun terminalMateKeepsTheAuthoritativeRanking() {
        val pieces = listOf(
            piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
            piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, "G11"),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
        )
        val state = GameState(
            position = Position(pieces.associateBy(Piece::id)),
            participants = PlayerId.entries.associateWith { player ->
                Participant(player, if (player == PlayerId.RED) {
                    ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 1)
                } else ParticipantStatus.Active)
            },
            armies = ArmyColor.entries.associateWith { army ->
                ArmyControl(army, if (army == ArmyColor.BLACK) PlayerId.BLACK else PlayerId.WHITE)
            },
            turn = Turn(PlayerId.WHITE, ply = 2),
            phase = GamePhase.InProgress,
        )
        val finished = apply(state, intent(PlayerId.WHITE, "G11", "G12")).state
        val phase = assertIs<GamePhase.Finished>(finished.phase)
        assertEquals(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.BLACK, PlayerId.RED), phase.outcome)
        val result = assertIs<BoundedBotResult.Terminal>(
            BoundedMaxNBot(tacticalDepth = 6).choose(finished),
        )
        assertEquals(phase, result.phase)
    }

    private fun exhaustive(state: GameState, remaining: Int): DoubleArray {
        if (remaining == 0 || state.phase != GamePhase.InProgress) {
            return BotEvaluationMode.CONTROL.evaluate(state, pressurePolicy)
        }
        val actor = checkNotNull(state.turn).player
        var best: DoubleArray? = null
        LegalMoveGenerator.legalMoves(state).forEach { move ->
            val score = exhaustive(apply(state, move.intent()).state, remaining - 1)
            if (best == null || score[actor.ordinal] > checkNotNull(best)[actor.ordinal]) best = score
        }
        return checkNotNull(best)
    }

    private fun forcedRecapture(): GameState = state(
        piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "G10"),
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
        piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "G12"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "A2"),
        piece("black-bishop", PieceType.BISHOP, ArmyColor.BLACK, "H11"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
    )

    private fun uncheckedRecapture(): GameState = state(
        piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "D4"),
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
        piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "E4"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "B1"),
        piece("black-rook", PieceType.ROOK, ArmyColor.BLACK, "F4"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "N7"),
    )

    private fun defensivePosition(): GameState = state(
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "A1"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
        piece("red-rook", PieceType.ROOK, ArmyColor.RED, "H10"),
    )

    private fun state(vararg pieces: Piece): GameState = GameState(
        position = Position(pieces.associateBy(Piece::id)),
        participants = PlayerId.entries.associateWith(::Participant),
        armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
        turn = Turn(PlayerId.WHITE, ply = 1),
        phase = GamePhase.InProgress,
    )

    private fun piece(id: String, type: PieceType, army: ArmyColor, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)

    private fun at(label: String): BoardCoordinate = checkNotNull(ThreePlayerBoardNotation.parse(label))

    private fun intent(actor: PlayerId, from: String, to: String): MoveIntent =
        MoveIntent(actor, at(from), at(to))

    private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)

    private fun apply(state: GameState, intent: MoveIntent): MoveReduction.Applied =
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent))
}
