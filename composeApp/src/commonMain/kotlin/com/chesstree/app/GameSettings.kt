package com.chesstree.app

import com.chesstree.game.presentation.board.PieceSet

internal data class GameSettings(
    val showCurrentPossibleMoves: Boolean = true,
    val showMoveLines: Boolean = false,
    val showGameHistory: Boolean = false,
    val zoomBeforeMove: Boolean = false,
    val pieceSet: PieceSet = PieceSet.FAIRY,
)

internal fun encodeGameSettings(settings: GameSettings): String = listOf(
    SETTINGS_VERSION,
    settings.showCurrentPossibleMoves.toString(),
    settings.showMoveLines.toString(),
    settings.showGameHistory.toString(),
    settings.zoomBeforeMove.toString(),
    settings.pieceSet.name,
).joinToString(SETTINGS_SEPARATOR)

internal fun restoreGameSettings(saved: String): GameSettings {
    val fields = saved.split(SETTINGS_SEPARATOR)
    if (fields.firstOrNull() == LEGACY_SETTINGS_VERSION && fields.size == LEGACY_FIELD_COUNT) {
        val showCurrentPossibleMoves = fields[1].toBooleanStrictOrNull() ?: return GameSettings()
        val showMoveLines = fields[2].toBooleanStrictOrNull() ?: return GameSettings()
        return GameSettings(
            showCurrentPossibleMoves = showCurrentPossibleMoves,
            showMoveLines = showMoveLines,
            pieceSet = PieceSet.FAIRY,
        )
    }
    if (fields.firstOrNull() == PREVIOUS_SETTINGS_VERSION && fields.size == PREVIOUS_FIELD_COUNT) {
        val showCurrentPossibleMoves = fields[1].toBooleanStrictOrNull() ?: return GameSettings()
        val showMoveLines = fields[2].toBooleanStrictOrNull() ?: return GameSettings()
        val showGameHistory = fields[3].toBooleanStrictOrNull() ?: return GameSettings()
        return GameSettings(
            showCurrentPossibleMoves = showCurrentPossibleMoves,
            showMoveLines = showMoveLines,
            showGameHistory = showGameHistory,
            pieceSet = PieceSet.FAIRY,
        )
    }
    if (fields.size != SETTINGS_FIELD_COUNT || fields[0] != SETTINGS_VERSION) return GameSettings()
    val showCurrentPossibleMoves = fields[1].toBooleanStrictOrNull() ?: return GameSettings()
    val showMoveLines = fields[2].toBooleanStrictOrNull() ?: return GameSettings()
    val showGameHistory = fields[3].toBooleanStrictOrNull() ?: return GameSettings()
    val zoomBeforeMove = fields[4].toBooleanStrictOrNull() ?: return GameSettings()
    return GameSettings(
        showCurrentPossibleMoves = showCurrentPossibleMoves,
        showMoveLines = showMoveLines,
        showGameHistory = showGameHistory,
        zoomBeforeMove = zoomBeforeMove,
        pieceSet = PieceSet.FAIRY,
    )
}

private const val SETTINGS_VERSION = "3"
private const val PREVIOUS_SETTINGS_VERSION = "2"
private const val LEGACY_SETTINGS_VERSION = "1"
private const val SETTINGS_SEPARATOR = "|"
private const val SETTINGS_FIELD_COUNT = 6
private const val PREVIOUS_FIELD_COUNT = 5
private const val LEGACY_FIELD_COUNT = 4
