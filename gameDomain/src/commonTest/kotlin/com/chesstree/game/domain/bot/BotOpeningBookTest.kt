package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingRight
import com.chesstree.game.domain.EnPassantTarget
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BotOpeningBookTest {
    @Test
    fun everyBundledBranchAndCandidateReplaysThroughPublicReducer() {
        assertEquals(1, BotOpeningBook.VERSION)
        assertEquals(6, BotOpeningBookData.lines.size)
        val visited = mutableSetOf<String>()
        BotOpeningBookData.lines.forEachIndexed { branch, line ->
            assertEquals(9, line.size)
            var state = StandardGame.scenario.initialState
            val developments = mutableMapOf<PlayerId, MutableSet<PieceType>>()
            line.forEachIndexed { ply, token ->
                val intent = BotOpeningBook.decode(token)
                assertEquals(PlayerId.entries[ply % 3], intent.actor)
                developments.getOrPut(intent.actor) { mutableSetOf() } +=
                    state.position.pieces.values.single { it.coordinate == intent.from }.type
                visited += checkNotNull(BotOpeningBook.signature(state))
                val choices = BotOpeningBook.preferredMoves(state, seed = 7)
                assertTrue(intent in choices, "Missing branch=$branch ply=$ply")
                choices.forEach { candidate ->
                    assertTrue(GameReducer.reduce(state, candidate) is MoveReduction.Applied)
                }
                val applied = GameReducer.reduce(state, intent)
                assertTrue(applied is MoveReduction.Applied)
                state = applied.state
            }
            PlayerId.entries.forEach { actor ->
                assertEquals(setOf(PieceType.PAWN, PieceType.KNIGHT, PieceType.BISHOP), checkNotNull(developments[actor]).toSet())
            }
            assertTrue(BotOpeningBook.preferredMoves(state, 7).isEmpty())
        }
        assertEquals(BotOpeningBookData.entries.keys, visited, "Every static entry needs replay provenance")
    }

    @Test
    fun bothPawnDistancesStayInBookForEveryActor() {
        BotOpeningBookData.lines.forEachIndexed { branch, line ->
            var state = StandardGame.scenario.initialState
            line.take(3).forEach { token ->
                state = apply(state, BotOpeningBook.decode(token))
                assertEquals(if (branch < 3) 1 else 0,
                    state.position.enPassantTargets.values.count { target ->
                        target.pawnId == state.position.pieces.values.single {
                            it.coordinate == BotOpeningBook.decode(token).to
                        }.id
                    })
                assertTrue(BotOpeningBook.preferredMoves(state, 0).isNotEmpty())
            }
        }
    }

    @Test
    fun seededOrderingIsReproducibleWithoutSharedActorState() {
        val initial = StandardGame.scenario.initialState
        val expected = BotOpeningBook.preferredMoves(initial, Long.MIN_VALUE)
        assertTrue(expected.size >= 3)
        val next = apply(initial, expected.first())
        repeat(5) {
            BotOpeningBook.preferredMoves(next, it.toLong())
            assertEquals(expected, BotOpeningBook.preferredMoves(initial, Long.MIN_VALUE))
        }
        val orderings = (0L..8L).map { BotOpeningBook.preferredMoves(initial, it) }.toSet()
        assertTrue(orderings.size > 1)
        orderings.forEach { assertEquals(expected.toSet(), it.toSet()) }
    }

    @Test
    fun completeStateGuardsRejectChangedPiecesRightsParticipantsAndTurn() {
        val original = StandardGame.scenario.initialState
        assertTrue(BotOpeningBook.preferredMoves(original, 1).isNotEmpty())
        val pawn = original.position.pieces.values.first { it.type == PieceType.PAWN }
        fun changedPawn(change: com.chesstree.game.domain.Piece) {
            val pieces = original.position.pieces.toMutableMap()
            pieces.remove(pawn.id)
            pieces[change.id] = change
            assertUnknown(copy(original, position = Position(pieces, original.position.castlingRights)))
        }
        changedPawn(pawn.copy(hasMoved = true))
        changedPawn(pawn.copy(type = PieceType.KNIGHT))
        changedPawn(pawn.copy(army = ArmyColor.RED))
        changedPawn(pawn.copy(id = PieceId("different:id|pawn")))
        val vacant = BoardCoordinate(0, 0, 3)
        assertTrue(original.position.pieces.values.none { it.coordinate == vacant })
        changedPawn(pawn.copy(coordinate = vacant))
        assertUnknown(copy(original, position = Position(original.position.pieces)))
        val right = original.position.castlingRights.first()
        assertUnknown(copy(original, position = Position(
            original.position.pieces,
            original.position.castlingRights - right + CastlingRight(right.army, right.side),
        )))
        assertUnknown(copy(original, armies = original.armies +
            (ArmyColor.RED to ArmyControl(ArmyColor.RED, PlayerId.WHITE))))
        assertUnknown(copy(original, participants = original.participants +
            (PlayerId.RED to Participant(PlayerId.RED, ParticipantStatus.Stalemated(1)))))
        assertUnknown(copy(original, turn = Turn(PlayerId.RED, 1)))
        assertUnknown(copy(original, turn = Turn(PlayerId.WHITE, 2)))
        assertUnknown(GameState(original.position, original.participants, original.armies, null,
            GamePhase.Finished(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK))))
    }

    @Test
    fun everyEnPassantFieldAndMapOrderingAreAccountedFor() {
        val initial = StandardGame.scenario.initialState
        val state = apply(initial, BotOpeningBook.decode(BotOpeningBookData.lines.first().first()))
        val target = state.position.enPassantTargets.values.single()
        assertTrue(BotOpeningBook.preferredMoves(state, 0).isNotEmpty())
        fun targetChanged(replacement: EnPassantTarget?) = assertUnknown(copy(state, position = Position(
            state.position.pieces, state.position.castlingRights,
            enPassantTargets = listOfNotNull(replacement),
        )))
        targetChanged(null)
        targetChanged(EnPassantTarget(target.pawnId, target.captureCoordinate, setOf(PlayerId.RED)))
        targetChanged(EnPassantTarget(target.pawnId, BoardCoordinate(0, 0, 3), target.eligiblePlayers))
        val otherPawn = state.position.pieces.values.first { it.type == PieceType.PAWN && it.id != target.pawnId }
        targetChanged(EnPassantTarget(otherPawn.id, target.captureCoordinate, target.eligiblePlayers))
        assertUnknown(copy(state, position = Position(
            state.position.pieces, state.position.castlingRights,
            enPassantTargets = state.position.enPassantTargets.values +
                EnPassantTarget(otherPawn.id, BoardCoordinate(0, 0, 3), setOf(PlayerId.BLACK)),
        )))
        val twoTargets = apply(state, BotOpeningBook.decode(BotOpeningBookData.lines.first()[1]))
        assertEquals(2, twoTargets.position.enPassantTargets.size)
        assertTrue(BotOpeningBook.preferredMoves(twoTargets, 0).isNotEmpty())
        assertUnknown(copy(twoTargets, position = Position(
            twoTargets.position.pieces, twoTargets.position.castlingRights,
            enPassantTargets = twoTargets.position.enPassantTargets.values.take(1),
        )))
        val reordered = copy(state, position = Position(
            state.position.pieces.entries.reversed().associate { it.toPair() },
            state.position.castlingRights.reversed().toSet(),
            enPassantTargets = state.position.enPassantTargets.values.map {
                EnPassantTarget(it.pawnId, it.captureCoordinate, it.eligiblePlayers.reversed().toSet())
            },
        ), participants = state.participants.entries.reversed().associate { it.toPair() },
            armies = state.armies.entries.reversed().associate { it.toPair() })
        assertEquals(BotOpeningBook.preferredMoves(state, 55), BotOpeningBook.preferredMoves(reordered, 55))
        assertNotEquals(BotOpeningBook.signature(state), BotOpeningBook.signature(initial))
    }

    @Test
    fun unexpectedOpponentReplyFallsBackToSearch() {
        val initial = StandardGame.scenario.initialState
        val state = apply(initial, BotOpeningBook.preferredMoves(initial, 0).first())
        val legal = LegalMoveGenerator.legalMoves(state, checkNotNull(state.turn).player)
        val unexpected = legal.map { MoveIntent(it.actor, it.from, it.to, it.promotion) }
            .first { it !in BotOpeningBook.preferredMoves(state, 0) }
        assertUnknown(apply(state, unexpected))
    }

    private fun assertUnknown(state: GameState) {
        assertTrue(BotOpeningBook.preferredMoves(state, 0).isEmpty())
    }

    private fun apply(state: GameState, intent: MoveIntent): GameState {
        val result = GameReducer.reduce(state, intent)
        assertTrue(result is MoveReduction.Applied)
        return result.state
    }

    private fun copy(
        state: GameState,
        position: Position = state.position,
        participants: Map<PlayerId, Participant> = state.participants,
        armies: Map<ArmyColor, ArmyControl> = state.armies,
        turn: Turn? = state.turn,
    ): GameState = GameState(position, participants, armies, turn, state.phase)
}
