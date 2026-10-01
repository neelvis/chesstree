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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotPlayingProfileTest {
    private val initial = gameScenario("profile-reference") {
        piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
        piece("rk", ArmyColor.RED, PieceType.KING, at("B1"))
        piece("bk", ArmyColor.BLACK, PieceType.KING, at("N7"))
        piece("wr", ArmyColor.WHITE, PieceType.ROOK, at("D4"), hasMoved = true)
        piece("rp", ArmyColor.RED, PieceType.PAWN, at("E4"), hasMoved = true)
        piece("br", ArmyColor.BLACK, PieceType.ROOK, at("F4"), hasMoved = true)
    }.initialState

    @Test
    fun everyProfileStaysWithinTheIndependentScalarCloseZoneForEverySeat() {
        PlayerId.entries.forEach { root ->
            val state = GameState(initial.position, initial.participants, initial.armies, Turn(root, 1), initial.phase)
            val scores = LegalMoveGenerator.legalMoves(state).associate { move ->
                val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
                intent to reference(apply(state, intent), 1, root)
            }
            BotPlayingProfile.entries.forEach { profile ->
                for (catalog in 1..2) {
                        val bot = BoundedMaxNBot(maxDepth = 2, maxNodes = 100_000, maxEvaluations = 100_000,
                            evaluation = BotEvaluationMode.POSITIONAL, searchModel = BotSearchModel.PARANOID, profile = profile, profileCatalogVersion = catalog)
                        val result = assertIs<BoundedBotResult.Move>(bot.choose(state, BotPolicy.DEFAULT))
                        assertEquals(BotMoveSource.COMPLETED_DEPTH, result.source)
                        assertEquals(2, result.stats.completedDepth)
                        val allowance = if (profile == BotPlayingProfile.UNIVERSAL && catalog == 1) 0.0 else 0.1 * BotPolicy.DEFAULT.weight(BotFeature.MATERIAL)
                        assertTrue(scores.getValue(result.intent) >= scores.values.max() - allowance - 1e-9,
                            "${root.name}/${profile.name} left the common score close zone")
                        assertEquals(result, bot.choose(state, BotPolicy.DEFAULT))
                    }
            }
        }
    }

    @Test
    fun profilesNeverExchangeATerminalWinForStyle() {
        val state = gameScenario("profile-terminal-priority") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("F12"))
            piece("wq", ArmyColor.WHITE, PieceType.QUEEN, at("G11"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("H12"))
            piece("rp", ArmyColor.RED, PieceType.PAWN, at("D7"))
            checkmated(PlayerId.BLACK, by = PlayerId.WHITE, atPly = 1)
            turn(PlayerId.WHITE, ply = 2)
        }.initialState
        val scores = LegalMoveGenerator.legalMoves(state).associate { move ->
            val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
            intent to reference(apply(state, intent), 0, PlayerId.WHITE)
        }
        assertTrue(scores.keys.any { apply(state, it).phase is GamePhase.Finished })
        BotPlayingProfile.entries.forEach { profile ->
            for (catalog in 1..2) {
            val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 1, maxNodes = 100_000, maxEvaluations = 100_000,
                evaluation = BotEvaluationMode.POSITIONAL, searchModel = BotSearchModel.PARANOID, profile = profile, profileCatalogVersion = catalog,
            ).choose(state, BotPolicy.DEFAULT))
            assertEquals(scores.values.max(), scores.getValue(result.intent))
            assertIs<GamePhase.Finished>(apply(state, result.intent).phase)
            }
        }
    }

    @Test
    fun difficultyPresetsKeepTheApprovedInitialSoftAndHardBudgets() {
        assertEquals(listOf(100 to 150, 350 to 500, 900 to 1200), BotDifficulty.entries.map { it.softBudgetMs to it.hardBudgetMs })
        assertEquals(listOf(2, 3, 4), BotDifficulty.entries.map { it.maxDepth })
    }

    private fun reference(state: GameState, remaining: Int, root: PlayerId): Double {
        if (remaining == 0 || state.phase != GamePhase.InProgress) return BotEvaluationMode.POSITIONAL.evaluate(state, BotPolicy.DEFAULT)[root.ordinal]
        val scores = LegalMoveGenerator.legalMoves(state).map { move ->
            reference(apply(state, MoveIntent(move.actor, move.from, move.to, move.promotion)), remaining - 1, root)
        }
        return if (state.turn?.player == root) scores.max() else scores.min()
    }

    private fun apply(state: GameState, move: MoveIntent): GameState = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, move)).state
    private fun at(label: String) = checkNotNull(ThreePlayerBoardNotation.parse(label))
}
