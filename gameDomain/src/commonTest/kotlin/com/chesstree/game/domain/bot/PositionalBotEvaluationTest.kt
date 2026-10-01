package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.scenario.gameScenario
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PositionalBotEvaluationTest {
    private val material = BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 100)))

    @Test
    fun safeMinorDevelopmentImprovesPositionWithoutMaterialChange() {
        val before = StandardGame.scenario.initialState
        val move = LegalMoveGenerator.legalMoves(before).first {
            before.position.pieces.getValue(it.pieceId).type == PieceType.KNIGHT
        }
        val after = apply(before, MoveIntent(move.actor, move.from, move.to))
        assertEquals(evaluate(before, material)[0], evaluate(after, material)[0])
        assertTrue(bonus(after) > bonus(before) + 10.0)
    }

    @Test
    fun movementFlagAloneDoesNotEarnDevelopmentCredit() {
        val before = StandardGame.scenario.initialState
        val knight = before.position.pieces.values.first { it.type == PieceType.KNIGHT }
        val after = copy(before, Position(
            before.position.pieces + (knight.id to knight.copy(hasMoved = true)),
            before.position.castlingRights,
        ))
        assertContentEquals(
            BotEvaluationMode.POSITIONAL.evaluate(before, material),
            BotEvaluationMode.POSITIONAL.evaluate(after, material),
        )
    }

    @Test
    fun hangingMinorIsPenalizedRatherThanRewardedForDevelopment() {
        val state = gameScenario("hanging-minor") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("E1"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A8"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("wn", ArmyColor.WHITE, PieceType.KNIGHT, at("H10"), hasMoved = true)
            piece("rr", ArmyColor.RED, PieceType.ROOK, at("H9"), hasMoved = true)
        }.initialState
        assertTrue(LegalMoveGenerator.legalMoves(state, PlayerId.RED).any { it.to == at("H10") })
        val rook = state.position.pieces.getValue(PieceId("rr"))
        val safe = copy(state, Position(state.position.pieces + (rook.id to rook.copy(coordinate = at("A5")))))
        assertTrue(at("H10") !in LegalMoveGenerator.attackCoordinates(safe.position,
            safe.position.pieces.getValue(rook.id)))
        assertTrue(bonus(state) < bonus(safe) - 100.0)
    }

    @Test
    fun mixedMaterialPawnsHaveProgressAndBlockageMatters() {
        fun fixture(blocked: Boolean): GameState = gameScenario("mixed-progress") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A1"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("wp", ArmyColor.WHITE, PieceType.PAWN, at("K9"), hasMoved = true)
            piece("wn", ArmyColor.WHITE, PieceType.KNIGHT, at("A8"), hasMoved = true)
            piece("block", ArmyColor.RED, PieceType.PAWN, at(if (blocked) "K10" else "D3"), hasMoved = true)
        }.initialState
        val free = fixture(false)
        val blocked = fixture(true)
        assertTrue(bonus(free) > bonus(blocked))
        val after = apply(free, MoveIntent(PlayerId.WHITE, at("K9"), at("K10")))
        assertTrue(bonus(after) > bonus(free))
    }

    @Test
    fun checkedMixedMaterialStillChoosesALegalDefense() {
        val state = gameScenario("forced-defense") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A1"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("wp", ArmyColor.WHITE, PieceType.PAWN, at("K9"), hasMoved = true)
            piece("wn", ArmyColor.WHITE, PieceType.KNIGHT, at("A8"), hasMoved = true)
            piece("rr", ArmyColor.RED, PieceType.ROOK, at("H10"), hasMoved = true)
        }.initialState
        assertTrue(LegalMoveGenerator.isKingInCheck(state, PlayerId.WHITE))
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            evaluation = BotEvaluationMode.POSITIONAL, openingBookVersion = BotOpeningBook.VERSION,
        ).choose(state))
        val after = apply(state, result.intent)
        assertTrue(!LegalMoveGenerator.isKingInCheck(after, PlayerId.WHITE))
        assertTrue(!result.openingBook.influenced)
    }

    @Test
    fun emptyPolicyHasNoHiddenPositionalPreferences() {
        val state = StandardGame.scenario.initialState
        assertContentEquals(evaluate(state, BotPolicy(emptyList())),
            BotEvaluationMode.POSITIONAL.evaluate(state, BotPolicy(emptyList())))
    }

    @Test
    fun queenDevelopmentPreferenceDoesNotPreventWinningMaterial() {
        val state = gameScenario("material-over-development") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("E1"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A8"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("wq", ArmyColor.WHITE, PieceType.QUEEN, at("D1"))
            piece("wn1", ArmyColor.WHITE, PieceType.KNIGHT, at("B1"))
            piece("wn2", ArmyColor.WHITE, PieceType.KNIGHT, at("G1"))
            piece("wb1", ArmyColor.WHITE, PieceType.BISHOP, at("C1"))
            piece("wb2", ArmyColor.WHITE, PieceType.BISHOP, at("F1"))
            piece("rr", ArmyColor.RED, PieceType.ROOK, at("D3"), hasMoved = true)
        }.initialState
        val queenCapture = MoveIntent(PlayerId.WHITE, at("D1"), at("D3"))
        val after = apply(state, queenCapture)
        assertEquals(evaluate(state, material)[0], evaluate(after, material)[0])
        assertTrue(BotEvaluationMode.POSITIONAL.evaluate(after, material)[0] >
            BotEvaluationMode.POSITIONAL.evaluate(state, material)[0] + 100.0)
        val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 1, evaluation = BotEvaluationMode.POSITIONAL, openingBookVersion = 1,
        ).choose(state, material))
        assertEquals(at("D3"), result.intent.to)
    }

    @Test
    fun everyPromotionStillImprovesPawnMaterialPosition() {
        val state = gameScenario("positional-promotion") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("H12"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("A1"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("wp", ArmyColor.WHITE, PieceType.PAWN, at("K11"), hasMoved = true)
        }.initialState
        val before = BotEvaluationMode.POSITIONAL.evaluate(state, material)[0]
        PromotionChoice.entries.forEach { promotion ->
            val after = apply(state, MoveIntent(PlayerId.WHITE, at("K11"), at("K12"), promotion))
            assertTrue(BotEvaluationMode.POSITIONAL.evaluate(after, material)[0] > before)
        }
    }

    @Test
    fun matingAWeakOpponentDoesNotCreateAnOpponentCountPenalty() {
        val state = gameScenario("weak-opponent-transfer") {
            piece("wk", ArmyColor.WHITE, PieceType.KING, at("F12"))
            piece("wq", ArmyColor.WHITE, PieceType.QUEEN, at("G11"))
            piece("rk", ArmyColor.RED, PieceType.KING, at("H12"))
            piece("rp", ArmyColor.RED, PieceType.PAWN, at("D7"))
            piece("bk", ArmyColor.BLACK, PieceType.KING, at("N8"))
            piece("bq", ArmyColor.BLACK, PieceType.QUEEN, at("M8"))
            listOf("M7", "L8", "L7", "N7").forEachIndexed { index, square ->
                piece("bp$index", ArmyColor.BLACK, PieceType.PAWN, at(square))
            }
        }.initialState
        val after = apply(state, MoveIntent(PlayerId.WHITE, at("G11"), at("G12")))
        assertIs<ParticipantStatus.Checkmated>(after.participants.getValue(PlayerId.RED).status)
        assertEquals(PlayerId.WHITE, after.armies.getValue(ArmyColor.RED).controller)
        assertTrue(BotEvaluationMode.POSITIONAL.evaluate(after, material)[0] >
            BotEvaluationMode.POSITIONAL.evaluate(state, material)[0])
    }

    private fun bonus(state: GameState): Double =
        BotEvaluationMode.POSITIONAL.evaluate(state, material)[0] - evaluate(state, material)[0]

    private fun at(value: String) = checkNotNull(ThreePlayerBoardNotation.parse(value))

    private fun apply(state: GameState, intent: MoveIntent): GameState =
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent)).state

    private fun copy(state: GameState, position: Position) =
        GameState(position, state.participants, state.armies, state.turn, state.phase)
}
