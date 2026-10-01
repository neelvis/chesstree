package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.gameScenario
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BoundedParanoidTest {
    private val material = BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 100)))
    private val initial = gameScenario("paranoid-reference") {
        piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
        piece("rk", ArmyColor.RED, PieceType.KING, at("B1"))
        piece("bk", ArmyColor.BLACK, PieceType.KING, at("N7"))
        piece("wr", ArmyColor.WHITE, PieceType.ROOK, at("D4"), hasMoved = true)
        piece("rp", ArmyColor.RED, PieceType.PAWN, at("E4"), hasMoved = true)
        piece("br", ArmyColor.BLACK, PieceType.ROOK, at("F4"), hasMoved = true)
    }.initialState

    @Test
    fun scalarAlphaBetaMatchesAnIndependentPublicReducerTreeForEveryRootSeat() {
        PlayerId.entries.forEach { root ->
            val state = GameState(initial.position, initial.participants, initial.armies,
                Turn(root, 1), initial.phase)
            val expected = LegalMoveGenerator.legalMoves(state).associate { move ->
                val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
                intent to reference(apply(state, intent), 2, root)
            }
            val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 3, maxNodes = 100_000, maxEvaluations = 100_000,
                searchModel = BotSearchModel.PARANOID,
            ).choose(state, material))
            assertEquals(BotMoveSource.COMPLETED_DEPTH, result.source)
            assertEquals(3, result.stats.completedDepth)
            assertEquals(expected.values.max(), expected.getValue(result.intent))
        }
    }

    private fun reference(state: GameState, remaining: Int, root: PlayerId): Double {
        if (remaining == 0 || state.phase != GamePhase.InProgress) {
            return BotEvaluationMode.CONTROL.evaluate(state, material)[root.ordinal]
        }
        val scores = LegalMoveGenerator.legalMoves(state).map { move ->
            reference(apply(state, MoveIntent(move.actor, move.from, move.to, move.promotion)), remaining - 1, root)
        }
        return if (state.turn?.player == root) scores.max() else scores.min()
    }

    @Test
    fun principalOrderingPreservesStableRootTiesAndBookDiagnostics() {
        val state = StandardGame.scenario.initialState
        val first = LegalMoveGenerator.legalMoves(state).first().let {
            MoveIntent(it.actor, it.from, it.to, it.promotion)
        }
        val control = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000,
            searchModel = BotSearchModel.PARANOID,
        ).choose(state, material))
        assertEquals(first, control.intent)
        for (seed in 0L..2L) {
            val preferred = BotOpeningBook.preferredMoves(state, seed).first()
            val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000,
                openingBookVersion = 1, seed = seed, searchModel = BotSearchModel.PARANOID,
            ).choose(state, material))
            assertEquals(preferred, result.intent)
            assertEquals(preferred != first, result.openingBook.influenced)
        }
    }

    @Test
    fun opponentInterestConflictIsKeptDistinctFromTheCoalitionModel() {
        val state = gameScenario("opponent-interest-conflict") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
            piece("wp", ArmyColor.WHITE, PieceType.PAWN, at("E2"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A1"))
            piece("rr", ArmyColor.RED, PieceType.ROOK, at("D6"), hasMoved = true)
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("B3"))
            piece("bq", ArmyColor.BLACK, PieceType.QUEEN, at("D2"), hasMoved = true)
        }.initialState
        val maxN = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 3, maxNodes = 100_000, maxEvaluations = 100_000,
        ).choose(state, material))
        val paranoid = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 3, maxNodes = 100_000, maxEvaluations = 100_000,
            searchModel = BotSearchModel.PARANOID,
        ).choose(state, material))
        assertEquals(PieceType.KING, state.position.pieces.values.single { it.coordinate == maxN.intent.from }.type)
        assertEquals(MoveIntent(PlayerId.WHITE, at("E2"), at("E4")), paranoid.intent)
        // The scalar coalition anticipates a pawn loss after a quiet king move;
        // independent actors can prefer other continuations. This is a model distinction.
        assertEquals(0.0, reference(apply(state, maxN.intent), 2, PlayerId.WHITE))
        assertEquals(100.0, reference(apply(state, paranoid.intent), 2, PlayerId.WHITE))
    }

    private fun apply(state: GameState, intent: MoveIntent): GameState =
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent)).state

    private fun at(label: String) = checkNotNull(ThreePlayerBoardNotation.parse(label))
}
