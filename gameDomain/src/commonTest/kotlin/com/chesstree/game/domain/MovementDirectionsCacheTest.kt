package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MovementDirectionsCacheTest {
    @Test
    fun cachedGeometryEqualsUncachedRoutesForEveryPieceArmyAndCoordinate() {
        assertEquals(96, ThreePlayerBoardTopology.coordinates.size)
        PieceType.entries.forEach { type ->
            ArmyColor.entries.forEach { army ->
                ThreePlayerBoardTopology.coordinates.forEach { origin ->
                    val expected = MovementDirections.uncachedForPiece(type, origin, army)
                    val actual = MovementDirections.forPiece(type, origin, army)

                    assertEquals(expected, actual, "$type of $army at $origin")
                }
            }
        }
    }

    @Test
    fun repeatedCallsReuseTheSameOrderedGeometry() {
        PieceType.entries.forEach { type ->
            ArmyColor.entries.forEach { army ->
                ThreePlayerBoardTopology.coordinates.forEach { origin ->
                    val first = MovementDirections.forPiece(type, origin, army)
                    repeat(3) {
                        assertSame(first, MovementDirections.forPiece(type, origin, army))
                    }
                }
            }
        }
    }

    @Test
    fun nonPawnGeometryIsIdenticalAndSharedAcrossOriginalArmies() {
        PieceType.entries.filter { it != PieceType.PAWN }.forEach { type ->
            ThreePlayerBoardTopology.coordinates.forEach { origin ->
                val white = MovementDirections.forPiece(type, origin, ArmyColor.WHITE)
                ArmyColor.entries.forEach { army ->
                    assertEquals(white, MovementDirections.uncachedForPiece(type, origin, army))
                    assertSame(white, MovementDirections.forPiece(type, origin, army))
                }
            }
        }
    }

    @Test
    fun cachedDirectionsAndTheirRoutesCannotBeChangedThroughMutableListCasts() {
        val origin = BoardCoordinate(vertex = 0, column = 1, row = 1)
        val directions = MovementDirections.forPiece(PieceType.ROOK, origin, ArmyColor.WHITE)
        val expected = MovementDirections.uncachedForPiece(PieceType.ROOK, origin, ArmyColor.WHITE)

        assertMutationRejected(directions)
        directions.forEach { direction -> assertMutationRejected(direction.route) }

        assertEquals(expected, MovementDirections.forPiece(PieceType.ROOK, origin, ArmyColor.WHITE))
        assertEquals(expected, MovementDirections.forPiece(PieceType.ROOK, origin, ArmyColor.BLACK))
    }

    private fun <T> assertMutationRejected(values: List<T>) {
        val attempt = runCatching {
            @Suppress("UNCHECKED_CAST")
            (values as MutableList<T>).clear()
        }
        assertTrue(attempt.isFailure, "Shared geometry must reject mutable-list operations")
    }
}
