package com.chesstree.bot.wire

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.EnPassantTarget
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BotStateWireCodecTest {
    private val initial = StandardGame.scenario.initialState

    @Test
    fun standardPositionRoundTripsWithCastlingRights() {
        val encoded = BotStateWireCodec.encode(initial)

        assertEquals(initial, BotStateWireCodec.decode(encoded))
        assertEquals(encoded, BotStateWireCodec.encode(checkNotNull(BotStateWireCodec.decode(encoded))))
    }

    @Test
    fun controlledArmyStatusesAndMultipleEnPassantEligibilitiesRoundTrip() {
        val redKing = initial.position.pieces.values.single {
            it.army == ArmyColor.RED && it.type == PieceType.KING
        }
        val redPawn = initial.position.pieces.values.first {
            it.army == ArmyColor.RED && it.type == PieceType.PAWN
        }
        val position = Position(
            pieces = initial.position.pieces - redKing.id,
            castlingRights = initial.position.castlingRights,
            enPassantTargets = listOf(
                EnPassantTarget(
                    pawnId = redPawn.id,
                    captureCoordinate = BoardCoordinate(0, 0, 0),
                    eligiblePlayers = setOf(PlayerId.WHITE, PlayerId.BLACK),
                ),
            ),
        )
        val state = GameState(
            position = position,
            participants = initial.participants +
                (PlayerId.RED to Participant(PlayerId.RED, ParticipantStatus.Checkmated(PlayerId.WHITE, 2))),
            armies = initial.armies +
                (ArmyColor.RED to initial.armies.getValue(ArmyColor.RED).copy(controller = PlayerId.WHITE)),
            turn = initial.turn,
            phase = initial.phase,
        )

        assertEquals(state, BotStateWireCodec.decode(BotStateWireCodec.encode(state)))
    }

    @Test
    fun unsupportedVersionAndMalformedStateAreRejected() {
        val encoded = BotStateWireCodec.encode(initial)

        assertNull(BotStateWireCodec.decode(encoded.replace("\"version\":1", "\"version\":2")))
        assertNull(BotStateWireCodec.decode("{}"))
    }

    @Test
    fun finishedOutcomeRoundTripsWithoutATurn() {
        val finished = GameState(
            position = initial.position,
            participants = initial.participants,
            armies = initial.armies,
            turn = null,
            phase = GamePhase.Finished(GameOutcome.ThreeWayDraw(DrawReason.AGREEMENT)),
        )

        assertEquals(finished, BotStateWireCodec.decode(BotStateWireCodec.encode(finished)))
    }
}
