package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
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
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.ThreePlayerBoardTopology
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Evaluation and legality guards; these synthetic states do not establish playing strength. */
class BotEvaluationModeTest {
    @Test
    fun controlRetainsExactOriginalScoresAndKnownMoveHandling() {
        val states = listOf(StandardGame.scenario.initialState, fixture(), fixture("K9", defend = true))
        val policies = listOf(BotPolicy.DEFAULT, materialOnly(), BotPolicy(emptyList()))
        states.forEach { state ->
            val legal = LegalMoveGenerator.legalMoves(state)
            policies.forEach { policy ->
                assertContentEquals(evaluate(state, policy), BotEvaluationMode.CONTROL.evaluate(state, policy))
                assertContentEquals(
                    evaluate(state, policy, legal),
                    BotEvaluationMode.CONTROL.evaluate(state, policy, legal),
                )
            }
        }
        assertEquals(1, BotEvaluationMode.CONTROL.version)
        assertEquals(2, BotEvaluationMode.PAWN_PROGRESS.version)
    }

    @Test
    fun terminalPlacementScoresAreExactInBothModes() {
        val initial = fixture("K9")
        val outcomes = listOf(
            GameOutcome.Ranked(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK),
            GameOutcome.TwoWayDraw(PlayerId.RED, PlayerId.BLACK, PlayerId.WHITE, DrawReason.SECOND_STALEMATE),
            GameOutcome.ThreeWayDraw(DrawReason.AGREEMENT),
        )
        outcomes.forEach { outcome ->
            val finished = GameState(
                initial.position, initial.participants, initial.armies, null, GamePhase.Finished(outcome),
            )
            BotEvaluationMode.entries.forEach { mode ->
                assertContentEquals(evaluate(finished, BotPolicy.DEFAULT), mode.evaluate(finished, BotPolicy.DEFAULT))
            }
        }
    }

    @Test
    fun changedFileForwardMoveIncreasesProgressSeparatelyFromMobility() {
        val before = fixture("K9")
        val intent = MoveIntent(PlayerId.WHITE, at("K9"), at("K10"))
        assertTrue(LegalMoveGenerator.legalMoves(before).any { it.from == intent.from && it.to == intent.to })
        val after = apply(before, intent)
        assertEquals(3, remainingSteps(at("K9"), ArmyColor.WHITE))
        assertEquals(2, remainingSteps(at("K10"), ArmyColor.WHITE))

        val beforeBonus = progressBonus(before, PlayerId.WHITE)
        val afterBonus = progressBonus(after, PlayerId.WHITE)
        assertTrue(afterBonus > beforeBonus)
        val maximum = maximumSteps(ArmyColor.WHITE)
        assertEquals(90.0 / maximum, afterBonus - beforeBonus, 0.0000001)
        assertEquals(progressBonus(before, PlayerId.RED), progressBonus(after, PlayerId.RED), 0.0000001)
        assertEquals(progressBonus(before, PlayerId.BLACK), progressBonus(after, PlayerId.BLACK), 0.0000001)
    }

    @Test
    fun transferredPawnsUseOriginalArmyRoutesAndRewardTheirActiveController() {
        val initial = fixture("K9")
        val transferred = GameState(
            position = Position(initial.position.pieces - PieceId("white-back-king")),
            participants = initial.participants + (
                PlayerId.WHITE to Participant(PlayerId.WHITE, ParticipantStatus.Checkmated(PlayerId.BLACK, 292))
            ),
            armies = initial.armies + (ArmyColor.WHITE to ArmyControl(ArmyColor.WHITE, PlayerId.BLACK)),
            turn = Turn(PlayerId.BLACK, 293),
            phase = GamePhase.InProgress,
        )
        assertTrue(remainingSteps(at("K9"), ArmyColor.WHITE) != remainingSteps(at("K9"), ArmyColor.BLACK))
        assertEquals(0.0, progressBonus(transferred, PlayerId.WHITE))
        assertEquals(
            expectedBonus(at("K9"), ArmyColor.WHITE) + expectedBonus(at("K6"), ArmyColor.BLACK),
            progressBonus(transferred, PlayerId.BLACK),
            0.0000001,
        )
    }

    @Test
    fun opponentMajorMaterialDoesNotDisablePawnOnlyPlayers() {
        val state = fixture("K9", redType = PieceType.QUEEN)
        assertTrue(progressBonus(state, PlayerId.WHITE) > 0.0)
        assertTrue(progressBonus(state, PlayerId.BLACK) > 0.0)
        assertEquals(0.0, progressBonus(state, PlayerId.RED))
        val initial = StandardGame.scenario.initialState
        assertContentEquals(
            evaluate(initial, BotPolicy.DEFAULT),
            BotEvaluationMode.PAWN_PROGRESS.evaluate(initial, BotPolicy.DEFAULT),
        )
    }

    @Test
    fun emptyOrInactiveControlledMaterialHasNoProgressBonus() {
        val initial = fixture("K9")
        val empty = withPieces(initial, initial.position.pieces.values.filter { it.id != PieceId("white-pawn-progress") })
        assertEquals(0.0, progressBonus(empty, PlayerId.WHITE))
        val inactive = GameState(
            initial.position,
            initial.participants + (PlayerId.RED to Participant(PlayerId.RED, ParticipantStatus.Stalemated(292))),
            initial.armies,
            initial.turn,
            initial.phase,
        )
        assertEquals(0.0, progressBonus(inactive, PlayerId.RED))
    }

    @Test
    fun nonpositiveOrDisabledMaterialWeightDisablesProgress() {
        val state = fixture("K9")
        val policies = listOf(materialOnly(0), materialOnly(-100), BotPolicy(listOf(
            BotRule(BotFeature.MATERIAL, weight = 100, enabled = false),
        )))
        policies.forEach { policy ->
            assertContentEquals(evaluate(state, policy), BotEvaluationMode.PAWN_PROGRESS.evaluate(state, policy))
        }
    }

    @Test
    fun eachPawnBonusIsBoundedBelowItsMaterialValue() {
        val state = fixture("K12")
        val bonus = progressBonus(state, PlayerId.WHITE)
        assertEquals(0, remainingSteps(at("K12"), ArmyColor.WHITE))
        assertEquals(90.0, bonus, 0.0000001)
        assertTrue(bonus in 0.0..90.0)
        assertTrue(bonus < materialOnly().weight(BotFeature.MATERIAL))
    }

    @Test
    fun losingAPawnReducesTotalProgressWithoutIncreasingRemainingPawnBonus() {
        val initial = fixture("K9")
        val stronger = moved("white-pawn-nearer", ArmyColor.WHITE, PieceType.PAWN, "K11")
        val before = withPieces(initial, initial.position.pieces.values + stronger)
        val afterLoss = withPieces(before, before.position.pieces.values.filter {
            it.id != PieceId("white-pawn-progress")
        })
        val beforeBonus = progressBonus(before, PlayerId.WHITE)
        val afterBonus = progressBonus(afterLoss, PlayerId.WHITE)
        assertTrue(afterBonus < beforeBonus)
        assertEquals(expectedBonus(at("K11"), ArmyColor.WHITE), afterBonus, 0.0000001)
        assertEquals(expectedBonus(at("K9"), ArmyColor.WHITE), beforeBonus - afterBonus, 0.0000001)
        val scoreLoss = BotEvaluationMode.PAWN_PROGRESS.evaluate(before, materialOnly())[PlayerId.WHITE.ordinal] -
                BotEvaluationMode.PAWN_PROGRESS.evaluate(afterLoss, materialOnly())[PlayerId.WHITE.ordinal]
        assertEquals(100.0 + beforeBonus - afterBonus, scoreLoss, 0.0000001)
    }

    @Test
    fun totalControllerBonusIsCappedBelowSmallestPromotionMaterialGain() {
        val state = nearPromotionFixture(listOf("E11", "F11", "G11", "K11"))
        val uncappedBonus = state.position.pieces.values.filter {
            it.army == ArmyColor.WHITE && it.type == PieceType.PAWN
        }.sumOf { expectedBonus(it.coordinate, it.army) }
        assertTrue(uncappedBonus > 190.0)
        assertEquals(190.0, progressBonus(state, PlayerId.WHITE), 0.0000001)
        assertTrue(progressBonus(state, PlayerId.WHITE) < 2.0 * materialOnly().weight(BotFeature.MATERIAL))
        assertEquals(
            19.0,
            BotEvaluationMode.PAWN_PROGRESS.evaluate(state, materialOnly(10))[PlayerId.WHITE.ordinal] -
                    evaluate(state, materialOnly(10))[PlayerId.WHITE.ordinal],
            0.0000001,
        )
    }

    @Test
    fun legalKnightPromotionImprovesMaterialOnlyScoreDespitePawnOnlyGateDisappearing() {
        val before = nearPromotionFixture(listOf("E11", "F11", "G11"))
        val intent = MoveIntent(PlayerId.WHITE, at("E11"), at("E12"), PromotionChoice.KNIGHT)
        assertTrue(LegalMoveGenerator.legalMoves(before).any {
            it.from == intent.from && it.to == intent.to && it.promotion == intent.promotion
        })
        val after = apply(before, intent)
        assertEquals(GamePhase.InProgress, after.phase)
        assertEquals(PieceType.KNIGHT, after.position.pieces.getValue(PieceId("white-near-pawn-0")).type)
        assertEquals(190.0, progressBonus(before, PlayerId.WHITE), 0.0000001)
        assertEquals(0.0, progressBonus(after, PlayerId.WHITE))
        val beforeScore = BotEvaluationMode.PAWN_PROGRESS.evaluate(before, materialOnly())[PlayerId.WHITE.ordinal]
        val afterScore = BotEvaluationMode.PAWN_PROGRESS.evaluate(after, materialOnly())[PlayerId.WHITE.ordinal]
        assertEquals(490.0, beforeScore, 0.0000001)
        assertEquals(500.0, afterScore, 0.0000001)
        assertTrue(afterScore > beforeScore)
    }

    @Test
    fun legalLossOfSoleKnightCannotBeRewardedByEnablingPawnOnlyBonus() {
        val initial = nearPromotionFixture(listOf("E11", "F11", "G11", "K11"))
        val withMinor = withPieces(initial, initial.position.pieces.values + listOf(
            moved("white-only-knight", ArmyColor.WHITE, PieceType.KNIGHT, "E9"),
            moved("red-capturing-rook", ArmyColor.RED, PieceType.ROOK, "E10"),
        ))
        val before = GameState(
            withMinor.position, withMinor.participants, withMinor.armies, Turn(PlayerId.RED, 293), withMinor.phase,
        )
        val intent = MoveIntent(PlayerId.RED, at("E10"), at("E9"))
        assertTrue(LegalMoveGenerator.legalMoves(before).any {
            it.from == intent.from && it.to == intent.to && it.capturedPieceId == PieceId("white-only-knight")
        })
        val after = apply(before, intent)
        assertEquals(GamePhase.InProgress, after.phase)
        assertFalse(PieceId("white-only-knight") in after.position.pieces)
        assertEquals(0.0, progressBonus(before, PlayerId.WHITE))
        assertEquals(190.0, progressBonus(after, PlayerId.WHITE), 0.0000001)
        val beforeScore = BotEvaluationMode.PAWN_PROGRESS.evaluate(before, materialOnly())[PlayerId.WHITE.ordinal]
        val afterScore = BotEvaluationMode.PAWN_PROGRESS.evaluate(after, materialOnly())[PlayerId.WHITE.ordinal]
        assertEquals(700.0, beforeScore, 0.0000001)
        assertEquals(590.0, afterScore, 0.0000001)
        assertTrue(afterScore < beforeScore)
    }

    @Test
    fun necessaryKingDefenseRemainsLegalAndPawnAdvanceCannotEvadeCheck() {
        val before = fixture(defend = true)
        val pawnIntent = MoveIntent(PlayerId.WHITE, at("E10"), at("E11"))
        val kingIntent = MoveIntent(PlayerId.WHITE, at("H12"), at("G12"))
        val legal = LegalMoveGenerator.legalMoves(before)
        assertTrue(LegalMoveGenerator.isKingInCheck(before, PlayerId.WHITE))
        assertFalse(legal.any { it.from == pawnIntent.from && it.to == pawnIntent.to })
        assertTrue(GameReducer.reduce(before, pawnIntent) is MoveReduction.Rejected)
        assertTrue(legal.any { it.from == kingIntent.from && it.to == kingIntent.to })
        val after = apply(before, kingIntent)
        assertFalse(LegalMoveGenerator.isKingInCheck(after, PlayerId.WHITE))
        assertEquals(progressBonus(before, PlayerId.WHITE), progressBonus(after, PlayerId.WHITE), 0.0000001)
    }

    private fun progressBonus(state: GameState, player: PlayerId): Double {
        val policy = materialOnly()
        return BotEvaluationMode.PAWN_PROGRESS.evaluate(state, policy)[player.ordinal] -
                evaluate(state, policy)[player.ordinal]
    }

    private fun materialOnly(weight: Int = 100): BotPolicy =
        BotPolicy(listOf(BotRule(BotFeature.MATERIAL, weight)))

    private fun expectedBonus(coordinate: BoardCoordinate, army: ArmyColor): Double =
        90.0 * (maximumSteps(army) - remainingSteps(coordinate, army)) / maximumSteps(army)

    private fun maximumSteps(army: ArmyColor): Int =
        ThreePlayerBoardTopology.coordinates.maxOf { remainingSteps(it, army) }

    private fun remainingSteps(coordinate: BoardCoordinate, army: ArmyColor): Int =
        MovementDirections.forPiece(PieceType.PAWN, coordinate, army)
            .singleOrNull { it.kind == DirectionKind.MOVE }?.route?.let { it.size - 1 } ?: 0

    private fun apply(state: GameState, intent: MoveIntent): GameState {
        val result = GameReducer.reduce(state, intent)
        assertTrue(result is MoveReduction.Applied, "Rejected $intent: $result")
        return result.state
    }

    private fun withPieces(state: GameState, pieces: Collection<Piece>): GameState = GameState(
        Position(pieces.associateBy(Piece::id), castlingRights = emptySet(), enPassantTargets = emptyList()),
        state.participants,
        state.armies,
        state.turn,
        state.phase,
    )

    private fun nearPromotionFixture(whitePawnSquares: List<String>): GameState {
        val initial = fixture()
        val pieces = initial.position.pieces.values.filter { it.id != PieceId("white-pawn-progress") } +
                whitePawnSquares.mapIndexed { index, square ->
                    moved("white-near-pawn-$index", ArmyColor.WHITE, PieceType.PAWN, square)
                }
        return withPieces(initial, pieces)
    }

    private fun at(label: String): BoardCoordinate = requireNotNull(ThreePlayerBoardNotation.parse(label))

    private fun moved(id: String, army: ArmyColor, type: PieceType, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)

    private fun fixture(
        whitePawn: String = "E10",
        defend: Boolean = false,
        redType: PieceType = PieceType.PAWN,
    ): GameState {
        val pieces = listOfNotNull(
            moved("white-back-king", ArmyColor.WHITE, PieceType.KING, "H12"),
            moved("red-back-king", ArmyColor.RED, PieceType.KING, "A1"),
            moved("black-back-king", ArmyColor.BLACK, PieceType.KING, "N8"),
            moved("white-pawn-progress", ArmyColor.WHITE, PieceType.PAWN, whitePawn),
            moved("red-pawn-progress", ArmyColor.RED, redType, "D3"),
            moved("black-pawn-progress", ArmyColor.BLACK, PieceType.PAWN, "K6"),
            if (defend) moved("red-back-rook", ArmyColor.RED, PieceType.ROOK, "H10") else null,
        )
        return GameState(
            position = Position(pieces.associateBy(Piece::id), castlingRights = emptySet(), enPassantTargets = emptyList()),
            participants = PlayerId.entries.associateWith { Participant(it, ParticipantStatus.Active) },
            armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            turn = Turn(PlayerId.WHITE, 293),
            phase = GamePhase.InProgress,
        )
    }
}
