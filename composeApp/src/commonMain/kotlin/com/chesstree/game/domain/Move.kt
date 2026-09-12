package com.chesstree.game.domain

data class MoveIntent(
    val actor: PlayerId,
    val from: BoardCoordinate,
    val to: BoardCoordinate,
    val promotion: PromotionChoice? = null,
) {
    init {
        require(from != to) { "A move must change the square" }
    }
}

enum class MoveType {
    QUIET,
    CAPTURE,
    CASTLING,
    EN_PASSANT,
}

data class RookDisplacement(
    val pieceId: PieceId,
    val from: BoardCoordinate,
    val to: BoardCoordinate,
) {
    init {
        require(from != to) { "A castling rook must change the square" }
    }
}

data class Move(
    val ply: Int,
    val actor: PlayerId,
    val pieceId: PieceId,
    val from: BoardCoordinate,
    val to: BoardCoordinate,
    val type: MoveType,
    val capturedPieceId: PieceId? = null,
    val promotion: PromotionChoice? = null,
    val rookDisplacement: RookDisplacement? = null,
) {
    init {
        require(ply > 0) { "Ply must be positive: $ply" }
        require(from != to) { "A move must change the square" }
        require(type == MoveType.CAPTURE || type == MoveType.EN_PASSANT || capturedPieceId == null) {
            "Only a capture may contain a captured piece"
        }
        require(type != MoveType.CAPTURE && type != MoveType.EN_PASSANT || capturedPieceId != null) {
            "A capture must identify the captured piece"
        }
        require(type == MoveType.CASTLING || rookDisplacement == null) {
            "Only castling may contain a rook displacement"
        }
        require(type != MoveType.CASTLING || rookDisplacement != null) {
            "Castling must identify the rook displacement"
        }
    }
}
