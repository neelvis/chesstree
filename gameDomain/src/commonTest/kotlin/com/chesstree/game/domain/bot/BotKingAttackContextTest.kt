package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BotKingAttackContextTest {
    @Test
    fun attackerSetsAreLazyAndRepeatedQueriesDoNotRecomputeEmptyOrNonemptyResults() {
        for (state in listOf(safe(), checked())) {
            val context = BotKingAttackContext(state)
            assertEquals(0, context.computedPlayerCount)
            val whiteAttackers = context.attackers(PlayerId.WHITE)
            assertEquals(LegalMoveGenerator.attackingPlayers(state, PlayerId.WHITE), whiteAttackers)
            assertEquals(1, context.computedPlayerCount)
            assertSame(whiteAttackers, context.attackers(PlayerId.WHITE))
            assertEquals(1, context.computedPlayerCount)
            PlayerId.entries.forEach { player ->
                assertEquals(LegalMoveGenerator.attackingPlayers(state, player), context.attackers(player))
            }
            assertEquals(3, context.computedPlayerCount)
            PlayerId.entries.forEach { context.attackers(it) }
            assertEquals(3, context.computedPlayerCount)
        }
    }

    @Test
    fun frontierAndEvaluationUseThreeComputedPlayerQueriesInsteadOfSix() {
        val state = safe()
        val context = BotKingAttackContext(state)
        val activeCheck = PlayerId.entries.any { player ->
            state.participants.getValue(player).status == ParticipantStatus.Active &&
                context.attackers(player).isNotEmpty()
        }
        assertFalse(activeCheck)
        assertEquals(3, context.computedPlayerCount)
        val control = BotEvaluationMode.CONTROL.evaluate(state, BotPolicy.DEFAULT)
        val reused = BotEvaluationMode.CONTROL.evaluate(state, BotPolicy.DEFAULT, knownKingAttacks = context)
        assertContentEquals(control, reused)
        // The following three evaluator lookups reused the frontier's actual computations.
        assertEquals(3, context.computedPlayerCount)
    }

    @Test
    fun checkPredicateRetainsItsShortCircuitAndDiagnosticsReuseQueriedPlayers() {
        val state = checked()
        val context = BotKingAttackContext(state)
        assertTrue(PlayerId.entries.any { context.attackers(it).isNotEmpty() })
        assertEquals(1, context.computedPlayerCount)
        val checkedPlayers = PlayerId.entries.filterTo(linkedSetOf()) { context.attackers(it).isNotEmpty() }
        assertEquals(setOf(PlayerId.WHITE), checkedPlayers)
        assertEquals(3, context.computedPlayerCount)
        assertEquals(checkedPlayers, PlayerId.entries.filterTo(linkedSetOf()) {
            LegalMoveGenerator.isKingInCheck(state, it)
        })
    }

    @Test
    fun everyModeAndVariedPolicyKeepExactControlScoresAcrossRuleContexts() {
        val policies = listOf(
            BotPolicy.DEFAULT,
            BotPolicy(emptyList()),
            BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 100))),
            BotPolicy(listOf(
                BotRule(BotFeature.KING_SAFETY, -280),
                BotRule(BotFeature.CHECK_PRESSURE, -40),
            )),
            BotPolicy(listOf(
                BotRule(BotFeature.MATERIAL, 100),
                BotRule(BotFeature.KING_SAFETY, 280, enabled = false),
                BotRule(BotFeature.CHECK_PRESSURE, 40, enabled = false),
                BotRule(BotFeature.MOBILITY, 3),
            )),
        )
        val states = listOf(safe(), checked(), doubleAttacked(), transferred(), frozenKing(), frozenRook())
        states.forEach { state ->
            val context = BotKingAttackContext(state)
            val legal = LegalMoveGenerator.legalMoves(state)
            PlayerId.entries.forEach { player ->
                assertEquals(LegalMoveGenerator.attackingPlayers(state, player), context.attackers(player))
            }
            policies.forEach { policy ->
                assertContentEquals(
                    evaluate(state, policy, legal),
                    evaluate(state, policy, legal, knownKingAttacks = context),
                )
                BotEvaluationMode.entries.forEach { mode ->
                    assertContentEquals(
                        mode.evaluate(state, policy, legal),
                        mode.evaluate(state, policy, legal, context),
                        "$mode / $policy / $state",
                    )
                }
            }
            assertEquals(3, context.computedPlayerCount)
        }
    }

    @Test
    fun frozenKingsStillAttackButFrozenNonKingsDoNot() {
        val kingState = frozenKing()
        assertEquals(setOf(PlayerId.RED), BotKingAttackContext(kingState).attackers(PlayerId.WHITE))
        val rookState = frozenRook()
        assertTrue(BotKingAttackContext(rookState).attackers(PlayerId.WHITE).isEmpty())
    }

    @Test
    fun twoOpponentsCheckingOneKingBothRetainTheirPressureCredit() {
        val state = doubleAttacked()
        val context = BotKingAttackContext(state)
        assertEquals(setOf(PlayerId.RED, PlayerId.BLACK), context.attackers(PlayerId.WHITE))
        assertContentEquals(
            evaluate(state, BotPolicy.DEFAULT),
            evaluate(state, BotPolicy.DEFAULT, knownKingAttacks = context),
        )
    }

    @Test
    fun transferredArmyAttacksUseTheirNewController() {
        val state = transferred()
        assertEquals(PlayerId.WHITE, state.armies.getValue(ArmyColor.RED).controller)
        assertIs<ParticipantStatus.Checkmated>(state.participants.getValue(PlayerId.RED).status)
        assertFalse(PieceId("red-king") in state.position.pieces)
        val context = BotKingAttackContext(state)
        PlayerId.entries.forEach { player ->
            assertEquals(LegalMoveGenerator.attackingPlayers(state, player), context.attackers(player))
            assertFalse(PlayerId.RED in context.attackers(player))
        }
    }

    @Test
    fun terminalScoresStayExactWithoutComputingAttackerSets() {
        val initial = safe()
        val state = GameState(
            initial.position, initial.participants, initial.armies, null,
            GamePhase.Finished(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.BLACK, PlayerId.RED)),
        )
        val context = BotKingAttackContext(state)
        BotEvaluationMode.entries.forEach { mode ->
            assertContentEquals(
                mode.evaluate(state, BotPolicy.DEFAULT),
                mode.evaluate(state, BotPolicy.DEFAULT, knownKingAttacks = context),
            )
        }
        assertEquals(0, context.computedPlayerCount)
    }

    @Test
    fun anotherStateInstanceIsRejectedEvenWhenItsValueIsEqual() {
        val state = safe()
        val other = GameState(state.position, state.participants, state.armies, state.turn, state.phase)
        assertEquals(state, other)
        val context = BotKingAttackContext(other)
        assertFailsWith<IllegalArgumentException> {
            evaluate(state, BotPolicy.DEFAULT, knownKingAttacks = context)
        }
        BotEvaluationMode.entries.forEach { mode ->
            assertFailsWith<IllegalArgumentException> {
                mode.evaluate(state, BotPolicy.DEFAULT, knownKingAttacks = context)
            }
        }
        assertEquals(0, context.computedPlayerCount)
    }

    private fun safe(): GameState = state(
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "A1"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
    )

    private fun checked(): GameState = state(
        *safe().position.pieces.values.toTypedArray(),
        piece("red-rook", PieceType.ROOK, ArmyColor.RED, "H10"),
    )

    private fun doubleAttacked(): GameState = state(
        *checked().position.pieces.values.toTypedArray(),
        piece("black-rook", PieceType.ROOK, ArmyColor.BLACK, "F12"),
    )

    private fun transferred(): GameState {
        val initial = state(
            piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
            piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, "G11"),
            piece("red-king", PieceType.KING, ArmyColor.RED, "H12"),
            piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "A2"),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
        )
        return assertIs<MoveReduction.Applied>(GameReducer.reduce(
            initial, MoveIntent(PlayerId.WHITE, at("G11"), at("G12")),
        )).state
    }

    private fun frozenKing(): GameState = state(
        piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
        piece("red-king", PieceType.KING, ArmyColor.RED, "H11"),
        piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
        redStatus = ParticipantStatus.Stalemated(atPly = 1),
    )

    private fun frozenRook(): GameState = state(
        *checked().position.pieces.values.toTypedArray(),
        redStatus = ParticipantStatus.Stalemated(atPly = 1),
    )

    private fun state(
        vararg pieces: Piece,
        redStatus: ParticipantStatus = ParticipantStatus.Active,
    ): GameState = GameState(
        position = Position(pieces.associateBy(Piece::id)),
        participants = PlayerId.entries.associateWith {
            Participant(it, if (it == PlayerId.RED) redStatus else ParticipantStatus.Active)
        },
        armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
        turn = Turn(PlayerId.WHITE, ply = 1),
        phase = GamePhase.InProgress,
    )

    private fun piece(id: String, type: PieceType, army: ArmyColor, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)

    private fun at(label: String) = checkNotNull(ThreePlayerBoardNotation.parse(label))
}
