package com.chesstree.game.domain

/** Stable algebraic names for all 96 cells of the three-player board. */
object ThreePlayerBoardNotation {
    private val FILES_BY_VERTEX: List<List<BoardFile>> = listOf(
        listOf(BoardFile.E, BoardFile.F, BoardFile.G, BoardFile.H),
        listOf(BoardFile.H, BoardFile.G, BoardFile.F, BoardFile.E),
        listOf(BoardFile.D, BoardFile.C, BoardFile.B, BoardFile.A),
        listOf(BoardFile.A, BoardFile.B, BoardFile.C, BoardFile.D),
        listOf(BoardFile.K, BoardFile.L, BoardFile.M, BoardFile.N),
        listOf(BoardFile.N, BoardFile.M, BoardFile.L, BoardFile.K),
    )

    private val RANKS_BY_VERTEX: List<List<Int>> = listOf(
        listOf(12, 11, 10, 9),
        listOf(4, 3, 2, 1),
        listOf(1, 2, 3, 4),
        listOf(5, 6, 7, 8),
        listOf(8, 7, 6, 5),
        listOf(9, 10, 11, 12),
    )

    private val squaresByCoordinate: Map<BoardCoordinate, Square> =
        ThreePlayerBoardTopology.coordinates.associateWith(::squareFor)
    private val coordinatesBySquare: Map<Square, BoardCoordinate> =
        squaresByCoordinate.entries.associate { (coordinate, square) -> square to coordinate }

    init {
        check(squaresByCoordinate.size == ThreePlayerBoardTopology.coordinates.size)
        check(coordinatesBySquare.size == ThreePlayerBoardTopology.coordinates.size) {
            "Every board coordinate must have a unique algebraic name"
        }
    }

    fun square(coordinate: BoardCoordinate): Square = squaresByCoordinate.getValue(coordinate)

    fun coordinate(square: Square): BoardCoordinate? = coordinatesBySquare[square]

    fun parse(value: String): BoardCoordinate? {
        val normalized = value.trim().uppercase()
        val file = normalized.firstOrNull()?.let { letter ->
            BoardFile.entries.firstOrNull { it.name.single() == letter }
        } ?: return null
        val rank = normalized.drop(1).toIntOrNull() ?: return null
        if (rank !in Square.MIN_RANK..Square.MAX_RANK) return null
        return coordinate(Square(file, rank))
    }

    private fun squareFor(coordinate: BoardCoordinate): Square {
        val file = FILES_BY_VERTEX[coordinate.vertex][
            if (coordinate.vertex % 2 == 0) coordinate.column else coordinate.row
        ]
        val rank = RANKS_BY_VERTEX[coordinate.vertex][
            if (coordinate.vertex % 2 == 0) coordinate.row else coordinate.column
        ]
        return Square(file, rank)
    }
}
