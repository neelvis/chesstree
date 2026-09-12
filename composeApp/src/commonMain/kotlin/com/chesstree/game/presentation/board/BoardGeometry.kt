package com.chesstree.game.presentation.board

import com.chesstree.game.domain.BoardCoordinate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class BoardPoint(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: BoardPoint): BoardPoint = BoardPoint(x + other.x, y + other.y)

    operator fun times(scale: Float): BoardPoint = BoardPoint(x * scale, y * scale)
}

typealias BoardCellId = BoardCoordinate

data class BoardCell(
    val id: BoardCellId,
    val corners: List<BoardPoint>,
    val center: BoardPoint,
    val isDark: Boolean,
)

data class BoardEdgeLabel(
    val text: String,
    val position: BoardPoint,
)

object ThreePlayerBoardGeometry {
    const val CELL_COUNT: Int = 96
    const val A1_VERTEX: Int = 0
    const val A1_COLUMN: Int = 2
    const val A1_ROW: Int = 1

    private val vertices: List<BoardPoint> = List(6) { index ->
        val angle = index * PI / 3.0
        BoardPoint(
            x = cos(angle).toFloat(),
            y = sin(angle).toFloat(),
        )
    }

    val cells: List<BoardCell> = createCells()
    val labels: List<BoardEdgeLabel> = createLabels()

    internal fun trophyPosition(
        army: com.chesstree.game.domain.ArmyColor,
        index: Int,
        count: Int,
    ): BoardPoint {
        require(index >= 0) { "Trophy index must not be negative" }
        require(index < count) { "Trophy index must be smaller than the trophy count" }
        val edge = when (army) {
            com.chesstree.game.domain.ArmyColor.WHITE -> 1
            com.chesstree.game.domain.ArmyColor.RED -> 3
            com.chesstree.game.domain.ArmyColor.BLACK -> 5
        }
        val start = vertices[edge]
        val end = vertices[(edge + 1) % vertices.size]
        val edgeMidpoint = midpoint(start, end)
        val tangent = BoardPoint(end.x - start.x, end.y - start.y)
        val spacing = if (count <= 1) 0f else minOf(0.078f, 0.72f / (count - 1))
        val centeredItem = index - (count - 1) / 2f
        return edgeMidpoint * 1.19f + tangent * (centeredItem * spacing)
    }

    private fun createCells(): List<BoardCell> {
        val drafts = buildList {
            vertices.forEachIndexed { vertexIndex, vertex ->
                val previous = vertices[(vertexIndex + 5) % 6]
                val next = vertices[(vertexIndex + 1) % 6]
                val previousMidpoint = midpoint(previous, vertex)
                val nextMidpoint = midpoint(vertex, next)

                repeat(4) { column ->
                    repeat(4) { row ->
                        val left = column / 4f
                        val right = (column + 1) / 4f
                        val top = row / 4f
                        val bottom = (row + 1) / 4f
                        val corners = listOf(
                            interpolateKite(previousMidpoint, vertex, nextMidpoint, left, top),
                            interpolateKite(previousMidpoint, vertex, nextMidpoint, right, top),
                            interpolateKite(previousMidpoint, vertex, nextMidpoint, right, bottom),
                            interpolateKite(previousMidpoint, vertex, nextMidpoint, left, bottom),
                        )
                        add(
                            CellDraft(
                                id = BoardCellId(vertexIndex, column, row),
                                corners = corners,
                                center = average(corners),
                            ),
                        )
                    }
                }
            }
        }

        val colors = alternatingColors(drafts)
        val anchorIndex = drafts.indexOfFirst {
            it.id == BoardCellId(A1_VERTEX, A1_COLUMN, A1_ROW)
        }
        val invert = colors.getValue(anchorIndex) != DARK

        return drafts.mapIndexed { index, draft ->
            BoardCell(
                id = draft.id,
                corners = draft.corners,
                center = draft.center,
                isDark = if (invert) colors.getValue(index) != DARK else colors.getValue(index) == DARK,
            )
        }
    }

    private fun alternatingColors(cells: List<CellDraft>): Map<Int, Int> {
        val edges = mutableMapOf<EdgeKey, MutableList<Int>>()
        cells.forEachIndexed { index, cell ->
            cell.corners.indices.forEach { cornerIndex ->
                val start = cell.corners[cornerIndex]
                val end = cell.corners[(cornerIndex + 1) % cell.corners.size]
                edges.getOrPut(EdgeKey.of(start, end)) { mutableListOf() }.add(index)
            }
        }

        val neighbours = List(cells.size) { mutableSetOf<Int>() }
        edges.values.filter { it.size == 2 }.forEach { touching ->
            neighbours[touching[0]].add(touching[1])
            neighbours[touching[1]].add(touching[0])
        }

        val colors = mutableMapOf(0 to DARK)
        val pending = ArrayDeque<Int>().apply { add(0) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            neighbours[current].forEach { neighbour ->
                if (neighbour !in colors) {
                    colors[neighbour] = 1 - colors.getValue(current)
                    pending.add(neighbour)
                }
            }
        }
        require(colors.size == cells.size) { "The board cell graph must be connected" }
        return colors
    }

    private fun createLabels(): List<BoardEdgeLabel> {
        val values = listOf(
            listOf("12", "11", "10", "9", "4", "3", "2", "1"),
            listOf("H", "G", "F", "E", "D", "C", "B", "A"),
            listOf("1", "2", "3", "4", "5", "6", "7", "8"),
            listOf("A", "B", "C", "D", "K", "L", "M", "N"),
            listOf("8", "7", "6", "5", "9", "10", "11", "12"),
            listOf("N", "M", "L", "K", "E", "F", "G", "H"),
        )
        return buildList {
            repeat(6) { edgeIndex ->
                val start = vertices[edgeIndex]
                val end = vertices[(edgeIndex + 1) % 6]
                val outward = midpoint(start, end) * 1.13f
                repeat(8) { part ->
                    val t = (part + 0.5f) / 8f
                    val edgePoint = lerp(start, end, t)
                    val labelPoint = edgePoint * 1.08f + outward * 0.04f
                    add(
                        BoardEdgeLabel(
                            text = values[edgeIndex][part],
                            position = labelPoint,
                        ),
                    )
                }
            }
        }
    }

    private fun interpolateKite(
        previousMidpoint: BoardPoint,
        vertex: BoardPoint,
        nextMidpoint: BoardPoint,
        column: Float,
        row: Float,
    ): BoardPoint {
        val outer = lerp(previousMidpoint, vertex, column)
        val inner = lerp(BoardPoint(0f, 0f), nextMidpoint, column)
        return lerp(outer, inner, row)
    }

    private fun midpoint(first: BoardPoint, second: BoardPoint): BoardPoint =
        BoardPoint((first.x + second.x) / 2f, (first.y + second.y) / 2f)

    private fun lerp(first: BoardPoint, second: BoardPoint, fraction: Float): BoardPoint =
        first * (1f - fraction) + second * fraction

    private fun average(points: List<BoardPoint>): BoardPoint =
        BoardPoint(
            points.sumOf { it.x.toDouble() }.toFloat() / points.size,
            points.sumOf { it.y.toDouble() }.toFloat() / points.size
        )

    private data class CellDraft(
        val id: BoardCellId,
        val corners: List<BoardPoint>,
        val center: BoardPoint,
    )

    private data class EdgeKey(
        val first: PointKey,
        val second: PointKey,
    ) {
        companion object {
            fun of(first: BoardPoint, second: BoardPoint): EdgeKey {
                val firstKey = PointKey.of(first)
                val secondKey = PointKey.of(second)
                return if (firstKey <= secondKey) EdgeKey(firstKey, secondKey) else EdgeKey(
                    secondKey,
                    firstKey
                )
            }
        }
    }

    private data class PointKey(
        val x: Int,
        val y: Int,
    ) : Comparable<PointKey> {
        override fun compareTo(other: PointKey): Int =
            compareValuesBy(this, other, PointKey::x, PointKey::y)

        companion object {
            fun of(point: BoardPoint): PointKey = PointKey(
                x = (point.x * 1_000_000).roundToInt(),
                y = (point.y * 1_000_000).roundToInt(),
            )
        }
    }

    private const val DARK: Int = 1
}
