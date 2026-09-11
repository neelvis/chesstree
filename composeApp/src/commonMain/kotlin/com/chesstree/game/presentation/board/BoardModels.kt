package com.chesstree.game.presentation.board

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.PieceType

data class BoardPiece(
    val id: String,
    val type: PieceType,
    val army: ArmyColor,
    val cellId: BoardCellId,
)

enum class MoveHintKind {
    MOVE,
    CAPTURE,
}

data class MoveHint(
    val target: BoardCellId,
    val kind: MoveHintKind,
    val route: List<BoardCellId> = emptyList(),
)

fun initialBoardPieces(): List<BoardPiece> = buildList {
    addArmy(ArmyColor.WHITE, homeEdge = 1)
    addArmy(ArmyColor.RED, homeEdge = 3)
    addArmy(ArmyColor.BLACK, homeEdge = 5)
}

fun movementHintsFor(piece: BoardPiece): List<MoveHint> =
    MovementDirections.forPiece(
        type = piece.type,
        origin = piece.cellId,
        army = piece.army,
    ).map { direction ->
        MoveHint(
            target = direction.target,
            kind = when (direction.kind) {
                DirectionKind.MOVE -> MoveHintKind.MOVE
                DirectionKind.CAPTURE -> MoveHintKind.CAPTURE
            },
            route = direction.route,
        )
    }

private fun MutableList<BoardPiece>.addArmy(
    army: ArmyColor,
    homeEdge: Int,
) {
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
        val cellId = backRow[index]
        ThreePlayerBoardGeometry.cells.first { it.id == cellId }.isDark == queenNeedsDarkCell
    }
    if (queenIndex == 4) {
        backTypes[3] = PieceType.KING
        backTypes[4] = PieceType.QUEEN
    }

    backRow.forEachIndexed { index, cellId ->
        add(BoardPiece("${army.name.lowercase()}-back-$index", backTypes[index], army, cellId))
    }
    pawnRow.forEachIndexed { index, cellId ->
        add(BoardPiece("${army.name.lowercase()}-pawn-$index", PieceType.PAWN, army, cellId))
    }
}

private fun edgeRow(edge: Int, depth: Int): List<BoardCellId> {
    require(depth in 0..3)
    val startVertex = edge
    val endVertex = (edge + 1) % 6
    val firstHalf = (0 until 4).map { row -> BoardCellId(startVertex, 3 - depth, row) }
    val secondHalf = (0 until 4).map { column -> BoardCellId(endVertex, column, depth) }
    val ids = firstHalf + secondHalf
    val start = hexVertex(startVertex)
    val end = hexVertex(endVertex)
    val direction = BoardPoint(end.x - start.x, end.y - start.y)
    return ids.sortedBy { id ->
        val center = ThreePlayerBoardGeometry.cells.first { it.id == id }.center
        (center.x - start.x) * direction.x + (center.y - start.y) * direction.y
    }
}

private fun hexVertex(index: Int): BoardPoint {
    val angle = index * kotlin.math.PI / 3.0
    return BoardPoint(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
}
