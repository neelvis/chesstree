package com.chesstree.game.domain

/** A direction through opposite edges of the quadrilateral board cells. */
enum class OrthogonalDirection {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM,
}

data class TopologyStep(
    val coordinate: BoardCoordinate,
    val continuingDirection: OrthogonalDirection,
)

/**
 * Pure topology of the six joined four-by-four sectors.
 *
 * Orthogonal lines cross opposite cell edges. Diagonals cross opposite cell
 * corners; the common centre is intentionally a branch point shared by six cells.
 */
object ThreePlayerBoardTopology {
    val coordinates: Set<BoardCoordinate> = buildSet {
        BoardCoordinate.VERTICES.forEach { vertex ->
            BoardCoordinate.SECTOR_INDICES.forEach { column ->
                BoardCoordinate.SECTOR_INDICES.forEach { row ->
                    add(BoardCoordinate(vertex, column, row))
                }
            }
        }
    }

    fun orthogonalStep(
        from: BoardCoordinate,
        direction: OrthogonalDirection,
    ): TopologyStep? = when (direction) {
        OrthogonalDirection.LEFT -> when {
            from.column > 0 -> TopologyStep(
                coordinate = from.copy(column = from.column - 1),
                continuingDirection = OrthogonalDirection.LEFT,
            )

            else -> TopologyStep(
                coordinate = BoardCoordinate(
                    vertex = previousVertex(from.vertex),
                    column = LAST_INDEX - from.row,
                    row = LAST_INDEX,
                ),
                continuingDirection = OrthogonalDirection.TOP,
            )
        }

        OrthogonalDirection.TOP -> when {
            from.row > 0 -> TopologyStep(
                coordinate = from.copy(row = from.row - 1),
                continuingDirection = OrthogonalDirection.TOP,
            )

            else -> null
        }

        OrthogonalDirection.RIGHT -> when {
            from.column < LAST_INDEX -> TopologyStep(
                coordinate = from.copy(column = from.column + 1),
                continuingDirection = OrthogonalDirection.RIGHT,
            )

            else -> null
        }

        OrthogonalDirection.BOTTOM -> when {
            from.row < LAST_INDEX -> TopologyStep(
                coordinate = from.copy(row = from.row + 1),
                continuingDirection = OrthogonalDirection.BOTTOM,
            )

            else -> TopologyStep(
                coordinate = BoardCoordinate(
                    vertex = nextVertex(from.vertex),
                    column = 0,
                    row = LAST_INDEX - from.column,
                ),
                continuingDirection = OrthogonalDirection.RIGHT,
            )
        }
    }

    fun orthogonalNeighbours(coordinate: BoardCoordinate): Set<BoardCoordinate> =
        OrthogonalDirection.entries.mapNotNullTo(linkedSetOf()) { direction ->
            orthogonalStep(coordinate, direction)?.coordinate
        }

    fun orthogonalRays(origin: BoardCoordinate): List<List<BoardCoordinate>> =
        OrthogonalDirection.entries.mapNotNull { direction ->
            straightRay(origin, direction).takeIf { it.size > 1 }
        }

    fun diagonalRays(origin: BoardCoordinate): List<List<BoardCoordinate>> =
        CellCorner.entries.flatMap { corner -> diagonalRays(origin, corner) }
            .distinct()

    fun diagonalNeighbours(coordinate: BoardCoordinate): Set<BoardCoordinate> =
        diagonalRays(coordinate).mapTo(linkedSetOf()) { route -> route[1] }

    private fun straightRay(
        origin: BoardCoordinate,
        initialDirection: OrthogonalDirection,
    ): List<BoardCoordinate> {
        val route = mutableListOf(origin)
        var current = origin
        var direction = initialDirection
        while (true) {
            val step = orthogonalStep(current, direction) ?: break
            check(step.coordinate !in route) { "An orthogonal ray must not contain a cycle" }
            route += step.coordinate
            current = step.coordinate
            direction = step.continuingDirection
        }
        return route
    }

    private fun diagonalRays(
        origin: BoardCoordinate,
        initialCorner: CellCorner,
    ): List<List<BoardCoordinate>> = buildList {
        fun continueRoute(
            current: BoardCoordinate,
            exitCorner: CellCorner,
            route: List<BoardCoordinate>,
        ) {
            val sharedVertex = vertexAt(current, exitCorner)
            val candidates = cellsAt(sharedVertex)
                .filterNot { candidate ->
                    candidate.coordinate == current ||
                            candidate.coordinate in orthogonalNeighbours(current) ||
                            candidate.coordinate in route
                }
                .filter { candidate -> sameCellColour(current, candidate.coordinate) }

            if (candidates.isEmpty()) {
                if (route.size > 1) add(route)
                return
            }

            candidates.forEach { candidate ->
                continueRoute(
                    current = candidate.coordinate,
                    exitCorner = candidate.corner.opposite,
                    route = route + candidate.coordinate,
                )
            }
        }

        continueRoute(origin, initialCorner, listOf(origin))
    }

    private fun cellsAt(vertex: MeshVertex): List<CellCornerAtVertex> =
        coordinates.flatMap { coordinate ->
            CellCorner.entries.mapNotNull { corner ->
                CellCornerAtVertex(coordinate, corner).takeIf {
                    vertexAt(coordinate, corner) == vertex
                }
            }
        }

    private fun vertexAt(
        coordinate: BoardCoordinate,
        corner: CellCorner,
    ): MeshVertex {
        val localColumn = coordinate.column + if (corner.isRight) 1 else 0
        val localRow = coordinate.row + if (corner.isBottom) 1 else 0
        return when {
            localColumn == 0 && localRow == SECTOR_SIZE -> MeshVertex.Centre
            localRow == SECTOR_SIZE -> MeshVertex.Seam(
                seam = coordinate.vertex,
                offset = localColumn,
            )

            localColumn == 0 -> MeshVertex.Seam(
                seam = previousVertex(coordinate.vertex),
                offset = SECTOR_SIZE - localRow,
            )

            else -> MeshVertex.Interior(
                vertex = coordinate.vertex,
                column = localColumn,
                row = localRow,
            )
        }
    }

    private fun previousVertex(vertex: Int): Int = (vertex + VERTEX_COUNT - 1) % VERTEX_COUNT

    private fun nextVertex(vertex: Int): Int = (vertex + 1) % VERTEX_COUNT

    private fun sameCellColour(
        first: BoardCoordinate,
        second: BoardCoordinate,
    ): Boolean = cellColour(first) == cellColour(second)

    private fun cellColour(coordinate: BoardCoordinate): Int =
        (coordinate.vertex + coordinate.column + coordinate.row) % 2

    private enum class CellCorner(
        val isRight: Boolean,
        val isBottom: Boolean,
    ) {
        TOP_LEFT(isRight = false, isBottom = false),
        TOP_RIGHT(isRight = true, isBottom = false),
        BOTTOM_RIGHT(isRight = true, isBottom = true),
        BOTTOM_LEFT(isRight = false, isBottom = true),
        ;

        val opposite: CellCorner
            get() = entries[(ordinal + 2) % entries.size]
    }

    private sealed interface MeshVertex {
        data object Centre : MeshVertex

        data class Seam(
            val seam: Int,
            val offset: Int,
        ) : MeshVertex

        data class Interior(
            val vertex: Int,
            val column: Int,
            val row: Int,
        ) : MeshVertex
    }

    private data class CellCornerAtVertex(
        val coordinate: BoardCoordinate,
        val corner: CellCorner,
    )

    private const val VERTEX_COUNT: Int = 6
    private const val SECTOR_SIZE: Int = 4
    private const val LAST_INDEX: Int = SECTOR_SIZE - 1
}
