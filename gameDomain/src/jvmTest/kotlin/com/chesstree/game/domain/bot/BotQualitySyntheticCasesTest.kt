package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.MoveRejectionReason
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardNotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BotQualitySyntheticCasesTest {
    @Test
    fun corpusHasDistinctIdentifiedCasesAllRootSeatsAndRealTwoActiveStates() {
        val cases = BotQualitySyntheticCases.all()
        assertTrue(cases.size >= 6)
        assertEquals(cases.size, cases.map { it.id }.distinct().size)
        assertEquals(PlayerId.entries.toSet(), cases.map { checkNotNull(it.state.turn).player }.toSet())
        assertTrue(cases.any { case ->
            case.state.participants.values.count { it.status == ParticipantStatus.Active } == 2
        })
        cases.forEach { case ->
            assertTrue(case.classification in setOf("verified-tactical-expectation", "terminal-invariant"))
            assertTrue(case.expectation.isNotBlank())
            assertEquals(case.state, case.history.last())
            assertEquals(GamePhase.InProgress, case.state.phase)
            val moves = LegalMoveGenerator.legalMoves(case.state)
            assertTrue(moves.isNotEmpty(), case.id)
            moves.forEach { move -> apply(case.state, move.intent()) }
        }
    }

    @Test
    fun forcedRookForPawnLossIncludesEveryLegalInterveningRedMove() {
        val initial = case("synthetic-forced-recapture")
        assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(initial, it) })
        val capture = apply(initial, intent(PlayerId.WHITE, "G10", "G12"))
        assertEquals(MoveType.CAPTURE, capture.move.type)
        assertFalse(PieceId("red-pawn") in capture.state.position.pieces)
        assertEquals(PlayerId.RED, capture.state.turn?.player)
        assertFalse(LegalMoveGenerator.isKingInCheck(capture.state, PlayerId.RED))
        assertTrue(LegalMoveGenerator.isKingInCheck(capture.state, PlayerId.BLACK))
        val redReplies = LegalMoveGenerator.legalMoves(capture.state)
        assertEquals(4, redReplies.size)
        assertTrue(redReplies.all { it.type == MoveType.QUIET })
        redReplies.forEach { reply ->
            val afterRed = apply(capture.state, reply.intent()).state
            assertEquals(PlayerId.BLACK, afterRed.turn?.player)
            val recapture = LegalMoveGenerator.legalMoves(afterRed).single()
            assertEquals(intent(PlayerId.BLACK, "H11", "G12"), recapture.intent())
            val settled = apply(afterRed, recapture.intent()).state
            assertFalse(PieceId("a-white-rook") in settled.position.pieces)
            assertTrue(PieceId("black-bishop") in settled.position.pieces)
            assertEquals(PlayerId.WHITE, settled.turn?.player)
            assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(settled, it) })
        }
    }

    @Test
    fun uncheckedRookForPawnLossHasAnExplicitLegalResponseFromBothOpponents() {
        val initial = case("synthetic-unchecked-recapture")
        val capture = apply(initial, intent(PlayerId.WHITE, "D4", "E4"))
        assertEquals(MoveType.CAPTURE, capture.move.type)
        assertFalse(PieceId("red-pawn") in capture.state.position.pieces)
        assertTrue(PlayerId.entries.none { LegalMoveGenerator.isKingInCheck(capture.state, it) })
        assertEquals(PlayerId.RED, capture.state.turn?.player)
        val afterRed = apply(capture.state, intent(PlayerId.RED, "B1", "A1")).state
        assertEquals(PlayerId.BLACK, afterRed.turn?.player)
        val recapture = apply(afterRed, intent(PlayerId.BLACK, "F4", "E4"))
        assertEquals(MoveType.CAPTURE, recapture.move.type)
        assertFalse(PieceId("a-white-rook") in recapture.state.position.pieces)
        assertTrue(PieceId("black-rook") in recapture.state.position.pieces)
        assertEquals(PlayerId.WHITE, recapture.state.turn?.player)
    }

    @Test
    fun checkedRedHasOnlyLegalQuietKingEscapes() {
        val initial = case("synthetic-red-king-evasion")
        assertTrue(LegalMoveGenerator.isKingInCheck(initial, PlayerId.RED))
        val defenses = LegalMoveGenerator.legalMoves(initial)
        assertTrue(defenses.isNotEmpty())
        defenses.forEach { move ->
            assertEquals(MoveType.QUIET, move.type)
            assertEquals(PieceType.KING, initial.position.pieces.getValue(move.pieceId).type)
            assertEquals(PlayerId.RED, move.actor)
            val after = apply(initial, move.intent()).state
            assertFalse(LegalMoveGenerator.isKingInCheck(after, PlayerId.RED))
            assertEquals(PlayerId.BLACK, after.turn?.player)
        }
    }

    @Test
    fun matingRedChangesControlRemovesKingAndSkipsEliminatedActor() {
        val initial = case("synthetic-mate-army-transfer")
        val after = apply(initial, intent(PlayerId.WHITE, "G11", "G12")).state
        assertEquals(case("synthetic-black-after-transfer"), after)
        val eliminated = assertIs<ParticipantStatus.Checkmated>(after.participants.getValue(PlayerId.RED).status)
        assertEquals(PlayerId.WHITE, eliminated.by)
        assertEquals(PlayerId.WHITE, after.armies.getValue(ArmyColor.RED).controller)
        assertFalse(PieceId("red-king") in after.position.pieces)
        assertEquals(PlayerId.BLACK, after.turn?.player)
        assertEquals(GamePhase.InProgress, after.phase)
        assertEquals(2, after.participants.values.count { it.status == ParticipantStatus.Active })
        val replies = LegalMoveGenerator.legalMoves(after)
        assertTrue(replies.isNotEmpty())
        replies.forEach { reply ->
            val next = apply(after, reply.intent()).state
            assertEquals(PlayerId.WHITE, next.turn?.player)
            assertEquals(eliminated, next.participants.getValue(PlayerId.RED).status)
            assertEquals(PlayerId.WHITE, next.armies.getValue(ArmyColor.RED).controller)
        }
    }

    @Test
    fun mateWithOnlyTwoActivePlayersPreservesFinalRankingAndTerminalSearchResult() {
        val initial = case("synthetic-two-active-final-mate")
        assertEquals(2, initial.participants.values.count { it.status == ParticipantStatus.Active })
        val finished = apply(initial, intent(PlayerId.WHITE, "G11", "G12")).state
        val phase = assertIs<GamePhase.Finished>(finished.phase)
        assertEquals(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.BLACK, PlayerId.RED), phase.outcome)
        assertEquals(PlayerId.WHITE, finished.armies.getValue(ArmyColor.BLACK).controller)
        assertFalse(PieceId("black-king") in finished.position.pieces)
        val result = assertIs<BoundedBotResult.Terminal>(BoundedMaxNBot(tacticalDepth = 6).choose(finished))
        assertEquals(phase, result.phase)
    }

    @Test
    fun promotionRequiresAChoiceAndEveryOfferedChoiceApplies() {
        val initial = case("synthetic-promotion-choices")
        val missing = intent(PlayerId.WHITE, "K11", "K12")
        assertEquals(
            MoveReduction.Rejected(MoveRejectionReason.PROMOTION_REQUIRED),
            GameReducer.reduce(initial, missing),
        )
        val promotions = LegalMoveGenerator.legalMoves(initial).filter { move ->
            move.from == missing.from && move.to == missing.to
        }
        assertEquals(4, promotions.size)
        assertEquals(PromotionChoice.entries.toSet(), promotions.mapNotNull { it.promotion }.toSet())
        promotions.forEach { move ->
            val after = apply(initial, move.intent()).state
            assertEquals(
                checkNotNull(move.promotion).pieceType,
                after.position.pieces.getValue(PieceId("white-pawn")).type,
            )
            assertEquals(PlayerId.RED, after.turn?.player)
            assertFalse(LegalMoveGenerator.isKingInCheck(after, PlayerId.WHITE))
        }
    }

    private fun case(id: String): GameState = BotQualitySyntheticCases.all().single { it.id == id }.state

    private fun intent(actor: PlayerId, from: String, to: String): MoveIntent = MoveIntent(
        actor, checkNotNull(ThreePlayerBoardNotation.parse(from)), checkNotNull(ThreePlayerBoardNotation.parse(to)),
    )

    private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)

    private fun apply(state: GameState, intent: MoveIntent): MoveReduction.Applied =
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent))
}
