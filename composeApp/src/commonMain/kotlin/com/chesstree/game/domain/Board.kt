package com.chesstree.game.domain

enum class BoardFile {
    A,
    B,
    C,
    D,
    E,
    F,
    G,
    H,
    I,
    J,
    K,
    L,
    M,
    N,
}

data class Square(
    val file: BoardFile,
    val rank: Int,
) {
    init {
        require(rank in MIN_RANK..MAX_RANK) {
            "Rank must be between $MIN_RANK and $MAX_RANK: $rank"
        }
    }

    override fun toString(): String = "${file.name.lowercase()}$rank"

    companion object {
        const val MIN_RANK: Int = 1
        const val MAX_RANK: Int = 12
    }
}

/**
 * A stable topological address for one of the 96 cells on the three-player board.
 *
 * The board is made from six four-by-four sectors joined around the centre. This
 * address deliberately does not pretend that every [Square] file/rank pair exists;
 * notation can be mapped separately once the complete source table is verified.
 */
data class BoardCoordinate(
    val vertex: Int,
    val column: Int,
    val row: Int,
) {
    init {
        require(vertex in VERTICES) { "Vertex must be between 0 and 5: $vertex" }
        require(column in SECTOR_INDICES) { "Column must be between 0 and 3: $column" }
        require(row in SECTOR_INDICES) { "Row must be between 0 and 3: $row" }
    }

    companion object {
        val VERTICES: IntRange = 0..5
        val SECTOR_INDICES: IntRange = 0..3
    }
}
