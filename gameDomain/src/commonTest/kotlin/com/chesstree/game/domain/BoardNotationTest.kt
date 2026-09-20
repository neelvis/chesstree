package com.chesstree.game.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoardNotationTest {
    @Test
    fun everyBoardCoordinateHasAUniqueRoundTrippableSquare() {
        val squares = ThreePlayerBoardTopology.coordinates.map(ThreePlayerBoardNotation::square)

        assertEquals(96, squares.size)
        assertEquals(96, squares.toSet().size)
        ThreePlayerBoardTopology.coordinates.forEach { coordinate ->
            val square = ThreePlayerBoardNotation.square(coordinate)
            assertEquals(coordinate, ThreePlayerBoardNotation.coordinate(square))
            assertEquals(coordinate, ThreePlayerBoardNotation.parse(square.toString()))
        }
    }

    @Test
    fun notationMatchesAllThreeBoardRegions() {
        assertEquals(Square(BoardFile.E, 12), ThreePlayerBoardNotation.square(BoardCoordinate(0, 0, 0)))
        assertEquals(Square(BoardFile.E, 1), ThreePlayerBoardNotation.square(BoardCoordinate(1, 3, 3)))
        assertEquals(Square(BoardFile.A, 1), ThreePlayerBoardNotation.square(BoardCoordinate(2, 3, 0)))
        assertEquals(Square(BoardFile.A, 5), ThreePlayerBoardNotation.square(BoardCoordinate(3, 0, 0)))
        assertEquals(Square(BoardFile.K, 8), ThreePlayerBoardNotation.square(BoardCoordinate(4, 0, 0)))
        assertEquals(Square(BoardFile.K, 9), ThreePlayerBoardNotation.square(BoardCoordinate(5, 0, 3)))
    }

    @Test
    fun parserIsCaseInsensitiveAndRejectsCellsOutsideTheBoard() {
        assertEquals(BoardCoordinate(2, 3, 0), ThreePlayerBoardNotation.parse(" a1 "))
        assertNull(ThreePlayerBoardNotation.parse("i1"))
        assertNull(ThreePlayerBoardNotation.parse("a9"))
        assertNull(ThreePlayerBoardNotation.parse("a0"))
    }
}
