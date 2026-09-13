package com.chesstree.game.domain.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.PieceType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class InitialPiece(
    val id: String,
    val army: ArmyColor,
    val type: PieceType,
    val coordinate: BoardCoordinate,
)

object StandardGame {
    val pieces: List<InitialPiece> = buildList {
        addArmy(ArmyColor.WHITE, homeEdge = 1)
        addArmy(ArmyColor.RED, homeEdge = 3)
        addArmy(ArmyColor.BLACK, homeEdge = 5)
    }

    val scenario: GameScenario = gameScenario("standard") {
        title = "Обычное начало"
        description = "Три полных армии; ход белых."
        pieces.forEach { piece ->
            piece(piece.id, piece.army, piece.type, piece.coordinate)
        }
        castlingRight(ArmyColor.WHITE, CastlingSide.KING_SIDE, "white-back-0")
        castlingRight(ArmyColor.WHITE, CastlingSide.QUEEN_SIDE, "white-back-7")
        castlingRight(ArmyColor.RED, CastlingSide.KING_SIDE, "red-back-0")
        castlingRight(ArmyColor.RED, CastlingSide.QUEEN_SIDE, "red-back-7")
        castlingRight(ArmyColor.BLACK, CastlingSide.KING_SIDE, "black-back-7")
        castlingRight(ArmyColor.BLACK, CastlingSide.QUEEN_SIDE, "black-back-0")
    }
}

private fun MutableList<InitialPiece>.addArmy(army: ArmyColor, homeEdge: Int) {
    val backRow = edgeRow(homeEdge, depth = 0)
    val pawnRow = edgeRow(homeEdge, depth = 1)
    val backTypes = mutableListOf(
        PieceType.ROOK,
        PieceType.KNIGHT,
        PieceType.BISHOP,
        PieceType.QUEEN,
        PieceType.KING,
        PieceType.BISHOP,
        PieceType.KNIGHT,
        PieceType.ROOK,
    )
    val queenNeedsDarkCell = army == ArmyColor.BLACK
    val queenIndex = listOf(3, 4).first { index ->
        backRow[index].isDark == queenNeedsDarkCell
    }
    if (queenIndex == 4) {
        backTypes[3] = PieceType.KING
        backTypes[4] = PieceType.QUEEN
    }
    backRow.forEachIndexed { index, coordinate ->
        add(InitialPiece("${army.name.lowercase()}-back-$index", army, backTypes[index], coordinate))
    }
    pawnRow.forEachIndexed { index, coordinate ->
        add(InitialPiece("${army.name.lowercase()}-pawn-$index", army, PieceType.PAWN, coordinate))
    }
}

private fun edgeRow(edge: Int, depth: Int): List<BoardCoordinate> {
    require(depth in 0..3)
    val startVertex = edge
    val endVertex = (edge + 1) % 6
    val coordinates = (0 until 4).map { row -> BoardCoordinate(startVertex, 3 - depth, row) } +
        (0 until 4).map { column -> BoardCoordinate(endVertex, column, depth) }
    val start = hexVertex(startVertex)
    val end = hexVertex(endVertex)
    val direction = Point(end.x - start.x, end.y - start.y)
    return coordinates.sortedBy { coordinate ->
        val center = cellCenter(coordinate)
        (center.x - start.x) * direction.x + (center.y - start.y) * direction.y
    }
}

private val BoardCoordinate.isDark: Boolean
    get() = (vertex + column + row) % 2 == 1

private fun cellCenter(coordinate: BoardCoordinate): Point {
    val vertex = hexVertex(coordinate.vertex)
    val previous = hexVertex((coordinate.vertex + 5) % 6)
    val next = hexVertex((coordinate.vertex + 1) % 6)
    val previousMidpoint = midpoint(previous, vertex)
    val nextMidpoint = midpoint(vertex, next)
    val left = coordinate.column / 4.0
    val right = (coordinate.column + 1) / 4.0
    val top = coordinate.row / 4.0
    val bottom = (coordinate.row + 1) / 4.0
    val corners = listOf(
        interpolateKite(previousMidpoint, vertex, nextMidpoint, left, top),
        interpolateKite(previousMidpoint, vertex, nextMidpoint, right, top),
        interpolateKite(previousMidpoint, vertex, nextMidpoint, right, bottom),
        interpolateKite(previousMidpoint, vertex, nextMidpoint, left, bottom),
    )
    return Point(corners.sumOf(Point::x) / 4.0, corners.sumOf(Point::y) / 4.0)
}

private fun interpolateKite(
    previousMidpoint: Point,
    vertex: Point,
    nextMidpoint: Point,
    column: Double,
    row: Double,
): Point = lerp(lerp(previousMidpoint, vertex, column), lerp(Point(0.0, 0.0), nextMidpoint, column), row)

private fun hexVertex(index: Int): Point {
    val angle = index * PI / 3.0
    return Point(cos(angle), sin(angle))
}

private fun midpoint(first: Point, second: Point) = Point((first.x + second.x) / 2.0, (first.y + second.y) / 2.0)

private fun lerp(first: Point, second: Point, fraction: Double) = Point(
    first.x * (1.0 - fraction) + second.x * fraction,
    first.y * (1.0 - fraction) + second.y * fraction,
)

private data class Point(val x: Double, val y: Double)
