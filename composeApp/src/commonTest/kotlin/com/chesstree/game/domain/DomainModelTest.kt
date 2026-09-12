package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DomainModelTest {
    @Test
    fun newGameStartsWithWhiteAndThreeActiveParticipants() {
        val game = GameState.new(position = Position(pieces = emptyMap()))

        assertEquals(PlayerId.WHITE, game.turn?.player)
        assertEquals(1, game.turn?.ply)
        assertEquals(PlayerId.entries.toSet(), game.participants.keys)
        game.participants.values.forEach { participant ->
            assertIs<ParticipantStatus.Active>(participant.status)
        }
    }

    @Test
    fun armyTransferChangesOneControlRecordForEveryPieceInArmy() {
        val rook = piece(id = "black-rook", coordinate = coordinate(0, 0, 0))
        val bishop = Piece(
            id = PieceId("black-bishop"),
            type = PieceType.BISHOP,
            army = ArmyColor.BLACK,
            coordinate = coordinate(0, 0, 1),
        )
        val position = Position(pieces = mapOf(rook.id to rook, bishop.id to bishop))
        val game = GameState.new(position)

        val transferredArmies = game.armies.toMutableMap().apply {
            this[ArmyColor.BLACK] = ArmyControl(ArmyColor.BLACK, PlayerId.WHITE)
        }

        assertEquals(PlayerId.WHITE, transferredArmies.getValue(rook.army).controller)
        assertEquals(PlayerId.WHITE, transferredArmies.getValue(bishop.army).controller)
    }

    @Test
    fun initialPieceHasMatchingBaseAndBodyColors() {
        val rook = piece(id = "black-rook", coordinate = coordinate(0, 0, 0))
        val game = GameState.new(
            position = Position(pieces = mapOf(rook.id to rook)),
        )

        val appearance = game.pieceAppearance(rook.id)

        assertEquals(ArmyColor.BLACK, appearance.baseColor)
        assertEquals(ArmyColor.BLACK, appearance.bodyColor)
        assertEquals(false, appearance.isTwoTone)
    }

    @Test
    fun transferredPieceKeepsDefeatedBaseAndUsesWinnerBodyColor() {
        val rook = Piece(
            id = PieceId("red-rook"),
            type = PieceType.ROOK,
            army = ArmyColor.RED,
            coordinate = coordinate(0, 0, 0),
        )
        val participants = PlayerId.entries.associateWith { id ->
            if (id == PlayerId.RED) {
                Participant(
                    id = id,
                    status = ParticipantStatus.Checkmated(
                        by = PlayerId.WHITE,
                        atPly = 4,
                    ),
                )
            } else {
                Participant(id = id)
            }
        }
        val game = GameState(
            position = Position(pieces = mapOf(rook.id to rook)),
            participants = participants,
            armies = defaultArmies().toMutableMap().apply {
                this[ArmyColor.RED] = ArmyControl(ArmyColor.RED, PlayerId.WHITE)
            },
            turn = Turn(player = PlayerId.BLACK, ply = 5),
            phase = GamePhase.InProgress,
        )

        val appearance = game.pieceAppearance(rook.id)

        assertEquals(ArmyColor.RED, appearance.baseColor)
        assertEquals(ArmyColor.WHITE, appearance.bodyColor)
        assertEquals(true, appearance.isTwoTone)
    }

    @Test
    fun positionRejectsTwoPiecesOnOneSquare() {
        val coordinate = coordinate(0, 0, 0)
        val first = piece(id = "first", coordinate = coordinate)
        val second = piece(id = "second", coordinate = coordinate)

        assertFailsWith<IllegalArgumentException> {
            Position(pieces = mapOf(first.id to first, second.id to second))
        }
    }

    @Test
    fun eliminatedParticipantCannotReceiveTurn() {
        val participants = PlayerId.entries.associateWith { id ->
            if (id == PlayerId.RED) {
                Participant(
                    id = id,
                    status = ParticipantStatus.Checkmated(
                        by = PlayerId.WHITE,
                        atPly = 4,
                    ),
                )
            } else {
                Participant(id = id)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            GameState(
                position = Position(pieces = emptyMap()),
                participants = participants,
                armies = defaultArmies().toMutableMap().apply {
                    this[ArmyColor.RED] = ArmyControl(ArmyColor.RED, PlayerId.WHITE)
                },
                turn = Turn(player = PlayerId.RED, ply = 5),
                phase = GamePhase.InProgress,
            )
        }
    }

    @Test
    fun checkmatedArmyMustPassToTheMatingPlayerWhileGameContinues() {
        val participants = PlayerId.entries.associateWith { id ->
            if (id == PlayerId.RED) {
                Participant(
                    id = id,
                    status = ParticipantStatus.Checkmated(
                        by = PlayerId.WHITE,
                        atPly = 4,
                    ),
                )
            } else {
                Participant(id = id)
            }
        }

        assertFailsWith<IllegalArgumentException> {
            GameState(
                position = Position(pieces = emptyMap()),
                participants = participants,
                armies = defaultArmies().toMutableMap().apply {
                    this[ArmyColor.RED] = ArmyControl(ArmyColor.RED, PlayerId.BLACK)
                },
                turn = Turn(player = PlayerId.BLACK, ply = 5),
                phase = GamePhase.InProgress,
            )
        }
    }

    @Test
    fun positionDoesNotChangeWhenInputMapIsMutated() {
        val rook = piece(id = "rook", coordinate = coordinate(0, 0, 0))
        val mutablePieces = mutableMapOf(rook.id to rook)
        val position = Position(pieces = mutablePieces)

        mutablePieces.clear()

        assertEquals(mapOf(rook.id to rook), position.pieces)
    }

    @Test
    fun rankedOutcomeRequiresEveryPlayerExactlyOnce() {
        assertFailsWith<IllegalArgumentException> {
            GameOutcome.Ranked(
                first = PlayerId.WHITE,
                second = PlayerId.WHITE,
                third = PlayerId.BLACK,
            )
        }
    }

    private fun piece(
        id: String,
        coordinate: BoardCoordinate,
    ): Piece = Piece(
        id = PieceId(id),
        type = PieceType.ROOK,
        army = ArmyColor.BLACK,
        coordinate = coordinate,
    )

    private fun coordinate(
        vertex: Int,
        column: Int,
        row: Int,
    ): BoardCoordinate = BoardCoordinate(vertex, column, row)

    private fun defaultArmies(): Map<ArmyColor, ArmyControl> =
        ArmyColor.entries.associateWith { color ->
            ArmyControl(army = color, controller = color.originalPlayer)
        }
}
