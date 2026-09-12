package com.chesstree.game.domain

enum class DirectionKind {
    MOVE,
    CAPTURE,
}

data class MovementDirection(
    val route: List<BoardCoordinate>,
    val kind: DirectionKind = DirectionKind.MOVE,
) {
    init {
        require(route.size >= 2) { "A movement direction must contain an origin and a target" }
        require(route.distinct().size == route.size) { "A movement direction must not contain a cycle" }
    }

    val target: BoardCoordinate = route.last()
}

/** Generates educational movement routes without occupancy or check validation. */
object MovementDirections {
    private val pawnDistances: Map<ArmyColor, Map<BoardCoordinate, Int>> =
        ArmyColor.entries.associateWith(::calculateDistancesFromHome)
    private val pawnFilesByVertex: List<List<BoardFile>> = listOf(
        listOf(BoardFile.E, BoardFile.F, BoardFile.G, BoardFile.H),
        listOf(BoardFile.H, BoardFile.G, BoardFile.F, BoardFile.E),
        listOf(BoardFile.D, BoardFile.C, BoardFile.B, BoardFile.A),
        listOf(BoardFile.A, BoardFile.B, BoardFile.C, BoardFile.D),
        listOf(BoardFile.K, BoardFile.L, BoardFile.M, BoardFile.N),
        listOf(BoardFile.N, BoardFile.M, BoardFile.L, BoardFile.K),
    )

    fun forPiece(
        type: PieceType,
        origin: BoardCoordinate,
        army: ArmyColor,
    ): List<MovementDirection> = when (type) {
        PieceType.ROOK -> sliding(ThreePlayerBoardTopology.orthogonalRays(origin))
        PieceType.BISHOP -> sliding(ThreePlayerBoardTopology.diagonalRays(origin))
        PieceType.QUEEN -> sliding(
            ThreePlayerBoardTopology.orthogonalRays(origin) +
                    ThreePlayerBoardTopology.diagonalRays(origin),
        )

        PieceType.KING -> immediate(
            ThreePlayerBoardTopology.orthogonalRays(origin) +
                    ThreePlayerBoardTopology.diagonalRays(origin),
        )

        PieceType.KNIGHT -> knight(origin)
        PieceType.PAWN -> pawn(origin, army)
    }.distinctBy { direction -> direction.kind to direction.route }

    private fun sliding(rays: List<List<BoardCoordinate>>): List<MovementDirection> =
        rays.map(::MovementDirection)

    private fun immediate(rays: List<List<BoardCoordinate>>): List<MovementDirection> =
        rays.map { ray -> MovementDirection(ray.take(2)) }
            .distinctBy(MovementDirection::target)

    private fun knight(origin: BoardCoordinate): List<MovementDirection> = buildList {
        OrthogonalDirection.entries.forEach { initialDirection ->
            val first =
                ThreePlayerBoardTopology.orthogonalStep(origin, initialDirection) ?: return@forEach
            val second = ThreePlayerBoardTopology.orthogonalStep(
                first.coordinate,
                first.continuingDirection,
            ) ?: return@forEach
            perpendicularTo(second.continuingDirection).forEach { perpendicular ->
                ThreePlayerBoardTopology.orthogonalStep(second.coordinate, perpendicular)
                    ?.let { last ->
                        add(
                            MovementDirection(
                                route = listOf(
                                    origin,
                                    first.coordinate,
                                    second.coordinate,
                                    last.coordinate
                                ),
                            ),
                        )
                    }
            }
        }
    }.distinctBy(MovementDirection::target)

    private fun pawn(
        origin: BoardCoordinate,
        army: ArmyColor,
    ): List<MovementDirection> {
        val distances = pawnDistances.getValue(army)
        val originDistance = distances.getValue(origin)
        val captures = ThreePlayerBoardTopology.diagonalNeighbours(origin)
            .filter { coordinate -> distances.getValue(coordinate) >= originDistance }

        return pawnForwardRoutes(origin, distances).map(::MovementDirection) +
                captures.map { target ->
                    MovementDirection(
                        route = listOf(origin, target),
                        kind = DirectionKind.CAPTURE,
                    )
                }
    }

    private fun pawnForwardRoutes(
        origin: BoardCoordinate,
        distances: Map<BoardCoordinate, Int>,
    ): List<List<BoardCoordinate>> {
        val file = pawnFile(origin)
        val route = mutableListOf(origin)

        while (true) {
            val current = route.last()
            val candidates = ThreePlayerBoardTopology.orthogonalNeighbours(current)
                .filter { coordinate ->
                    coordinate !in route &&
                            pawnFile(coordinate) == file &&
                            distances.getValue(coordinate) == distances.getValue(current) + 1
                }
            check(candidates.size <= 1) { "A pawn file must not branch" }
            val next = candidates.singleOrNull() ?: break
            route += next
        }

        return listOf(route).filter { it.size > 1 }
    }

    private fun pawnFile(coordinate: BoardCoordinate): BoardFile =
        pawnFilesByVertex[coordinate.vertex][
            if (coordinate.vertex % 2 == 0) coordinate.column else coordinate.row
        ]

    private fun calculateDistancesFromHome(army: ArmyColor): Map<BoardCoordinate, Int> {
        val home = homeEdge(army)
        val distances = home.associateWith { 0 }.toMutableMap()
        val pending = ArrayDeque<BoardCoordinate>().apply { addAll(home) }
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            val nextDistance = distances.getValue(current) + 1
            ThreePlayerBoardTopology.orthogonalNeighbours(current).forEach { neighbour ->
                if (neighbour !in distances) {
                    distances[neighbour] = nextDistance
                    pending.add(neighbour)
                }
            }
        }
        check(distances.size == ThreePlayerBoardTopology.coordinates.size) {
            "Every board coordinate must be reachable from an army home edge"
        }
        return distances
    }

    private fun homeEdge(army: ArmyColor): Set<BoardCoordinate> {
        val edge = when (army) {
            ArmyColor.WHITE -> 1
            ArmyColor.RED -> 3
            ArmyColor.BLACK -> 5
        }
        val next = (edge + 1) % 6
        return buildSet {
            BoardCoordinate.SECTOR_INDICES.forEach { index ->
                add(BoardCoordinate(edge, column = 3, row = index))
                add(BoardCoordinate(next, column = index, row = 0))
            }
        }
    }

    private fun perpendicularTo(direction: OrthogonalDirection): List<OrthogonalDirection> =
        when (direction) {
            OrthogonalDirection.LEFT,
            OrthogonalDirection.RIGHT,
                -> listOf(OrthogonalDirection.TOP, OrthogonalDirection.BOTTOM)

            OrthogonalDirection.TOP,
            OrthogonalDirection.BOTTOM,
                -> listOf(OrthogonalDirection.LEFT, OrthogonalDirection.RIGHT)
    }
}
