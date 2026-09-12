package com.chesstree.game.presentation.board

import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.pieceAppearance

fun GameState.toBoardPieces(): List<BoardPiece> =
    position.pieces.values.map { piece ->
        val appearance = pieceAppearance(piece.id)
        BoardPiece(
            id = piece.id.value,
            type = piece.type,
            army = piece.army,
            cellId = piece.coordinate,
            bodyArmy = appearance.bodyColor,
        )
    }
