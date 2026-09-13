package com.chesstree.game.domain

data class PieceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Piece id must not be blank" }
    }

    override fun toString(): String = value
}

enum class PieceType {
    KING,
    QUEEN,
    ROOK,
    BISHOP,
    KNIGHT,
    PAWN,
}

enum class PromotionChoice(
    val pieceType: PieceType,
) {
    QUEEN(PieceType.QUEEN),
    ROOK(PieceType.ROOK),
    BISHOP(PieceType.BISHOP),
    KNIGHT(PieceType.KNIGHT),
}

data class Piece(
    val id: PieceId,
    val type: PieceType,
    val army: ArmyColor,
    val coordinate: BoardCoordinate,
    val hasMoved: Boolean = false,
)
