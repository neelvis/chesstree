package com.chesstree.game.presentation.board

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PieceType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardGeometryTest {
    @Test
    fun boardContainsNinetySixUniqueCells() {
        val cells = ThreePlayerBoardGeometry.cells

        assertEquals(ThreePlayerBoardGeometry.CELL_COUNT, cells.size)
        assertEquals(cells.size, cells.map(BoardCell::id).toSet().size)
        assertTrue(cells.all { it.corners.size == 4 })
    }

    @Test
    fun a1AnchorIsDark() {
        val a1 = BoardCellId(
            vertex = ThreePlayerBoardGeometry.A1_VERTEX,
            column = ThreePlayerBoardGeometry.A1_COLUMN,
            row = ThreePlayerBoardGeometry.A1_ROW,
        )

        assertTrue(ThreePlayerBoardGeometry.cells.first { it.id == a1 }.isDark)
    }

    @Test
    fun coloursAreBalancedAndAlternateInsideEveryKite() {
        val cells = ThreePlayerBoardGeometry.cells

        assertEquals(48, cells.count(BoardCell::isDark))
        repeat(6) { vertex ->
            repeat(4) { column ->
                repeat(4) { row ->
                    val current = cells.cell(vertex, column, row)
                    if (column < 3) {
                        assertFalse(current.isDark == cells.cell(vertex, column + 1, row).isDark)
                    }
                    if (row < 3) {
                        assertFalse(current.isDark == cells.cell(vertex, column, row + 1).isDark)
                    }
                }
            }
        }

        repeat(6) { vertex ->
            repeat(4) { column ->
                val current = cells.cell(vertex, column, 3)
                val acrossSeam = cells.cell((vertex + 1) % 6, 0, 3 - column)
                assertFalse(current.isDark == acrossSeam.isDark)
            }
        }
    }

    @Test
    fun initialPositionContainsThreeCompleteArmiesOnUniqueCells() {
        val pieces = initialBoardPieces()

        assertEquals(48, pieces.size)
        assertEquals(48, pieces.map(BoardPiece::cellId).toSet().size)
        assertEquals(3, pieces.groupBy(BoardPiece::army).size)
        pieces.groupBy(BoardPiece::army).values.forEach { army ->
            assertEquals(16, army.size)
        }
    }

    @Test
    fun queensStartOnTheRequiredCellColours() {
        val cells = ThreePlayerBoardGeometry.cells.associateBy(BoardCell::id)
        val queens = initialBoardPieces().filter { it.type == PieceType.QUEEN }

        assertEquals(
            false,
            cells.getValue(queens.first { it.army == ArmyColor.WHITE }.cellId).isDark
        )
        assertEquals(false, cells.getValue(queens.first { it.army == ArmyColor.RED }.cellId).isDark)
        assertEquals(
            true,
            cells.getValue(queens.first { it.army == ArmyColor.BLACK }.cellId).isDark
        )
    }

    @Test
    fun everyInitialPieceExposesEducationalMovementHints() {
        initialBoardPieces().forEach { piece ->
            val hints = movementHintsFor(piece)

            assertTrue(hints.isNotEmpty(), "${piece.type} at ${piece.cellId} must have directions")
            assertTrue(hints.all { hint -> hint.route.first() == piece.cellId })
        }
    }

    @Test
    fun initialPawnHintsSeparateForwardMovementFromCaptures() {
        val pawnHints = initialBoardPieces()
            .filter { piece -> piece.army == ArmyColor.WHITE && piece.type == PieceType.PAWN }
            .map(::movementHintsFor)

        assertEquals(
            2,
            pawnHints.maxOf { hints -> hints.count { it.kind == MoveHintKind.CAPTURE } })
        assertTrue(
            pawnHints.flatten()
                .any { hint -> hint.kind == MoveHintKind.MOVE && hint.route.size > 2 })
    }

    @Test
    fun geometricCellIdRejectsCoordinatesOutsideTheBoard() {
        assertFailsWith<IllegalArgumentException> { BoardCellId(vertex = 6, column = 0, row = 0) }
        assertFailsWith<IllegalArgumentException> { BoardCellId(vertex = 0, column = 4, row = 0) }
        assertFailsWith<IllegalArgumentException> { BoardCellId(vertex = 0, column = 0, row = -1) }
    }

    @Test
    fun perimeterLabelsMatchTheReferenceSvgClockwise() {
        val expected = listOf(
            "12", "11", "10", "9", "4", "3", "2", "1",
            "H", "G", "F", "E", "D", "C", "B", "A",
            "1", "2", "3", "4", "5", "6", "7", "8",
            "A", "B", "C", "D", "K", "L", "M", "N",
            "8", "7", "6", "5", "9", "10", "11", "12",
            "N", "M", "L", "K", "E", "F", "G", "H",
        )

        assertEquals(expected, ThreePlayerBoardGeometry.labels.map(BoardEdgeLabel::text))
    }

    @Test
    fun a1AnchorMatchesItsExplicitSvgAnnotation() {
        val anchor = ThreePlayerBoardGeometry.cells.first {
            it.id == BoardCellId(
                ThreePlayerBoardGeometry.A1_VERTEX,
                ThreePlayerBoardGeometry.A1_COLUMN,
                ThreePlayerBoardGeometry.A1_ROW,
            )
        }

        assertTrue(abs(anchor.center.x - 0.7421875f) < 0.0001f)
        assertTrue(abs(anchor.center.y) < 0.0001f)
    }

    private fun List<BoardCell>.cell(vertex: Int, column: Int, row: Int): BoardCell =
        first { it.id == BoardCellId(vertex, column, row) }
}
