package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BoardTopologyTest {
    @Test
    fun topologyContainsNinetySixUniqueCoordinates() {
        assertEquals(96, ThreePlayerBoardTopology.coordinates.size)
    }

    @Test
    fun orthogonalNeighbourshipIsSymmetricAcrossEverySectorSeam() {
        ThreePlayerBoardTopology.coordinates.forEach { coordinate ->
            ThreePlayerBoardTopology.orthogonalNeighbours(coordinate).forEach { neighbour ->
                assertTrue(
                    coordinate in ThreePlayerBoardTopology.orthogonalNeighbours(neighbour),
                    "$coordinate must also be a neighbour of $neighbour",
                )
            }
        }
    }

    @Test
    fun everyGeneratedRouteStaysOnBoardAndDoesNotCycle() {
        PieceType.entries.forEach { type ->
            ThreePlayerBoardTopology.coordinates.forEach { origin ->
                MovementDirections.forPiece(type, origin, ArmyColor.WHITE).forEach { direction ->
                    assertEquals(origin, direction.route.first())
                    assertTrue(direction.route.all { it in ThreePlayerBoardTopology.coordinates })
                    assertEquals(direction.route.size, direction.route.distinct().size)
                    assertNotEquals(origin, direction.target)
                }
            }
        }
    }

    @Test
    fun slidingPiecesAndKingKeepOrdinaryDirectionCountsAwayFromSeams() {
        val origin = BoardCoordinate(vertex = 0, column = 1, row = 1)

        assertEquals(4, MovementDirections.forPiece(PieceType.ROOK, origin, ArmyColor.WHITE).size)
        assertEquals(4, MovementDirections.forPiece(PieceType.BISHOP, origin, ArmyColor.WHITE).size)
        assertEquals(8, MovementDirections.forPiece(PieceType.QUEEN, origin, ArmyColor.WHITE).size)
        assertEquals(8, MovementDirections.forPiece(PieceType.KING, origin, ArmyColor.WHITE).size)
        val knightDirections =
            MovementDirections.forPiece(PieceType.KNIGHT, origin, ArmyColor.WHITE)
        assertTrue(knightDirections.isNotEmpty())
        assertTrue(knightDirections.size <= 8)
        assertEquals(
            knightDirections.size,
            knightDirections.map(MovementDirection::target).toSet().size
        )
    }

    @Test
    fun bishopDirectionsBranchAtTheCommonCentre() {
        val centreCell = BoardCoordinate(vertex = 0, column = 0, row = 3)

        assertTrue(
            MovementDirections.forPiece(PieceType.BISHOP, centreCell, ArmyColor.WHITE).size > 4,
        )
    }

    @Test
    fun bishopRoutesKeepTheBoardCellColour() {
        ThreePlayerBoardTopology.coordinates.forEach { origin ->
            MovementDirections.forPiece(PieceType.BISHOP, origin, ArmyColor.WHITE)
                .flatMap(MovementDirection::route)
                .forEach { coordinate ->
                    assertEquals(cellColour(origin), cellColour(coordinate))
                }
        }
    }

    @Test
    fun aPawnHasTwoCapturesBeforeItReachesTheCentre() {
        val pawn = BoardCoordinate(vertex = 1, column = 2, row = 1)
        val directions = MovementDirections.forPiece(PieceType.PAWN, pawn, ArmyColor.WHITE)

        assertEquals(2, directions.count { it.kind == DirectionKind.CAPTURE })
        assertTrue(directions.any { it.kind == DirectionKind.MOVE && it.route.size > 2 })
    }

    @Test
    fun pawnsCanGainAThirdCaptureDirectionInTheCentralRegion() {
        ArmyColor.entries.forEach { army ->
            val captureCounts = ThreePlayerBoardTopology.coordinates.associateWith { coordinate ->
                MovementDirections.forPiece(PieceType.PAWN, coordinate, army)
                    .count { direction -> direction.kind == DirectionKind.CAPTURE }
            }

            assertEquals(3, captureCounts.values.max())
            assertTrue(captureCounts.values.all { count -> count in 0..3 })
        }
    }

    private fun cellColour(coordinate: BoardCoordinate): Int =
        (coordinate.vertex + coordinate.column + coordinate.row) % 2
}
