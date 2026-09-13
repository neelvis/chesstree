package com.chesstree.game.domain

data class PieceAppearance(
    val baseColor: ArmyColor,
    val bodyColor: ArmyColor,
) {
    val isTwoTone: Boolean
        get() = baseColor != bodyColor
}

fun GameState.pieceAppearance(pieceId: PieceId): PieceAppearance {
    val piece = position.pieces.getValue(pieceId)
    val controller = armies.getValue(piece.army).controller
    val controllerColor = ArmyColor.entries.single { color ->
        color.originalPlayer == controller
    }

    return PieceAppearance(
        baseColor = piece.army,
        bodyColor = controllerColor,
    )
}
