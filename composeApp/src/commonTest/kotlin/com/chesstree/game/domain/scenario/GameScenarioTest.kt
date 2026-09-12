package com.chesstree.game.domain.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class GameScenarioTest {
    @Test
    fun arbitraryUnreachableStartingPositionIsAcceptedWithoutTacticalProof() {
        val scenario = gameScenario("trusted-checkmate") {
            checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 7)
            turn(PlayerId.BLACK, ply = 8)
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
            piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(0, 0, 1))
            piece("red-queen", ArmyColor.RED, PieceType.QUEEN, cell(0, 0, 2))
        }

        assertIs<ParticipantStatus.Checkmated>(
            scenario.initialState.participants.getValue(PlayerId.RED).status,
        )
        assertEquals(
            PlayerId.WHITE,
            scenario.initialState.armies.getValue(ArmyColor.RED).controller,
        )
    }

    @Test
    fun activeArmyMustHaveExactlyOneKing() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("missing-king") {
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun armyCannotExceedSixteenPieces() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("too-many-white-pieces") {
                piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
                repeat(16) { index ->
                    piece(
                        id = "white-rook-$index",
                        army = ArmyColor.WHITE,
                        type = PieceType.ROOK,
                        at = cell(1, index / 4, index % 4),
                    )
                }
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun twoPiecesCannotShareCoordinate() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("occupied-coordinate") {
                piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
                piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, cell(0, 0, 0))
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun finishedScenarioHasNoTurn() {
        val scenario = gameScenario("finished") {
            checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 4)
            checkmated(PlayerId.BLACK, by = PlayerId.WHITE, atPly = 9)
            ranked(PlayerId.WHITE, PlayerId.BLACK, PlayerId.RED)
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
        }

        assertIs<GamePhase.Finished>(scenario.initialState.phase)
        assertNull(scenario.initialState.turn)
    }

    @Test
    fun activePlayerCannotGiveAwayItsOwnArmy() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("active-with-transferred-army") {
                control(ArmyColor.RED, by = PlayerId.WHITE)
                piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun rankedOutcomeRejectsPlayersWhoAreStillActive() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("ranked-active-players") {
                ranked(PlayerId.WHITE, PlayerId.BLACK, PlayerId.RED)
                piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun aLaterCheckmateTransfersEveryArmyControlledByTheDefeatedPlayer() {
        val scenario = gameScenario("chained-army-transfer") {
            checkmated(PlayerId.BLACK, by = PlayerId.RED, atPly = 4)
            checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 9)
            ranked(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK)
            piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
            piece("red-rook", ArmyColor.RED, PieceType.ROOK, cell(2, 0, 0))
            piece("black-bishop", ArmyColor.BLACK, PieceType.BISHOP, cell(4, 0, 0))
        }

        assertEquals(
            setOf(PlayerId.WHITE),
            scenario.initialState.armies.values.map { it.controller }.toSet(),
        )
    }

    @Test
    fun castlingRightRequiresAnUnmovedKingAndRook() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("invalid-castling-right") {
                castlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE)
                piece(
                    "white-king",
                    ArmyColor.WHITE,
                    PieceType.KING,
                    cell(0, 0, 0),
                    hasMoved = true,
                )
                piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, cell(0, 0, 1))
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
            }
        }
    }

    @Test
    fun enPassantRequiresAPreviouslyMovedPawn() {
        assertFailsWith<IllegalArgumentException> {
            gameScenario("invalid-en-passant") {
                piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 0, 0))
                piece("white-pawn", ArmyColor.WHITE, PieceType.PAWN, cell(0, 0, 1))
                piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 0, 0))
                piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 0, 0))
                enPassant(
                    pawnId = "white-pawn",
                    captureAt = cell(0, 0, 2),
                    eligiblePlayers = setOf(PlayerId.RED),
                )
            }
        }
    }

    private fun cell(
        vertex: Int,
        column: Int,
        row: Int,
    ): BoardCoordinate = BoardCoordinate(vertex, column, row)
}
