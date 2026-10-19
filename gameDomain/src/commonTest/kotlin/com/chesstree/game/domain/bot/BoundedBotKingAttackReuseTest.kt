package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BoundedBotKingAttackReuseTest {
    private data class Observation(
        val result: BoundedBotResult,
        val completed: List<BoundedBotResult.Move>,
        val diagnostics: BotSearchDiagnostics,
        val cache: BotSearchCacheStats?,
        val probes: Int,
    )

    @Test
    fun fixedWorkChoicesAttemptsCacheAndStopCallsAreUnchanged() {
        var completed = 0
        for (state in listOf(quietPosition(), forcedRecapture())) {
            for (model in BotSearchModel.entries) {
                for (mode in BotEvaluationMode.entries) {
                    for (cached in listOf(false, true)) {
                        val control = observe(state, model, mode, cached, reuse = false)
                        val candidate = observe(state, model, mode, cached, reuse = true)
                        assertEquals(control, candidate, "$model/$mode/cache=$cached")
                        completed += candidate.completed.size
                        val result = assertIs<BoundedBotResult.Move>(candidate.result)
                        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, result.intent))
                    }
                }
            }
        }
        assertTrue(completed > 0, "Comparison must include completed searches, not only fallback")
    }

    @Test
    fun localCapsAndGlobalInterruptionsRetainExactlyTheSameAuthority() {
        val state = forcedRecapture()
        for (tacticalDepth in listOf(0, 1, 2, 4)) {
            for (signal in listOf(BotStopSignal.CONTINUE, BotStopSignal.CANCEL, BotStopSignal.TIMEOUT)) {
                val control = observe(state, BotSearchModel.PARANOID, BotEvaluationMode.POSITIONAL,
                    cached = true, reuse = false, tacticalDepth = tacticalDepth, signal = signal)
                val candidate = observe(state, BotSearchModel.PARANOID, BotEvaluationMode.POSITIONAL,
                    cached = true, reuse = true, tacticalDepth = tacticalDepth, signal = signal)
                assertEquals(control, candidate, "q=$tacticalDepth/$signal")
            }
        }
    }

    @Test
    fun publicSearchUsesTheVerifiedReusePath() {
        val state = quietPosition()
        val public = BoundedMaxNBot(maxDepth = 2, maxNodes = 512, maxEvaluations = 512,
            evaluation = BotEvaluationMode.POSITIONAL, tacticalDepth = 2,
            searchModel = BotSearchModel.PARANOID).choose(state)
        val candidate = observe(state, BotSearchModel.PARANOID, BotEvaluationMode.POSITIONAL,
            cached = false, reuse = true)
        assertEquals(candidate.result, public)
    }

    private fun observe(
        state: GameState,
        model: BotSearchModel,
        mode: BotEvaluationMode,
        cached: Boolean,
        reuse: Boolean,
        tacticalDepth: Int = 2,
        signal: BotStopSignal = BotStopSignal.CONTINUE,
    ): Observation {
        val cache = if (cached) BotSearchCache() else null
        val completed = mutableListOf<BoundedBotResult.Move>()
        var probes = 0
        var diagnostics: BotSearchDiagnostics? = null
        val result = BoundedMaxNBot(maxDepth = 2, maxNodes = 512, maxEvaluations = 512,
            evaluation = mode, tacticalDepth = tacticalDepth, searchModel = model, cache = cache)
            .chooseWithDiagnostics(state,
                stop = BotStopProbe {
                    probes++
                    if (signal == BotStopSignal.CONTINUE || completed.isEmpty()) BotStopSignal.CONTINUE else signal
                },
                onCompletedDepth = completed::add,
                reuseKingAttackContext = reuse,
                diagnostics = { diagnostics = it },
            )
        return Observation(result, completed.toList(), checkNotNull(diagnostics), cache?.stats, probes)
    }

    private fun quietPosition() = state(
        piece("wk", PieceType.KING, ArmyColor.WHITE, "H12"),
        piece("rk", PieceType.KING, ArmyColor.RED, "A1"),
        piece("bk", PieceType.KING, ArmyColor.BLACK, "N8"),
        piece("wp", PieceType.PAWN, ArmyColor.WHITE, "K9"),
    )

    private fun forcedRecapture() = state(
        piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "G10"),
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
        piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "G12"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "A2"),
        piece("black-bishop", PieceType.BISHOP, ArmyColor.BLACK, "H11"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
    )

    private fun state(vararg pieces: Piece) = GameState(
        Position(pieces.associateBy(Piece::id)), PlayerId.entries.associateWith(::Participant),
        ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
        Turn(PlayerId.WHITE, 1), GamePhase.InProgress,
    )

    private fun piece(id: String, type: PieceType, army: ArmyColor, square: String) = Piece(
        PieceId(id), type, army, checkNotNull(ThreePlayerBoardNotation.parse(square)), hasMoved = true,
    )
}
