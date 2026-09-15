package com.chesstree.app

import com.chesstree.game.presentation.board.PieceSet

internal data class GameSettings(
    val showCurrentPossibleMoves: Boolean = true,
    val showMoveLines: Boolean = false,
    val pieceSet: PieceSet = PieceSet.STANDARD,
)

internal fun encodeGameSettings(settings: GameSettings): String = listOf(
    SETTINGS_VERSION,
    settings.showCurrentPossibleMoves.toString(),
    settings.showMoveLines.toString(),
    settings.pieceSet.name,
).joinToString(SETTINGS_SEPARATOR)

internal fun restoreGameSettings(saved: String): GameSettings {
    val fields = saved.split(SETTINGS_SEPARATOR)
    if (fields.size != SETTINGS_FIELD_COUNT || fields[0] != SETTINGS_VERSION) {
        return GameSettings()
    }
    val showCurrentPossibleMoves = fields[1].toBooleanStrictOrNull() ?: return GameSettings()
    val showMoveLines = fields[2].toBooleanStrictOrNull() ?: return GameSettings()
    return GameSettings(
        showCurrentPossibleMoves = showCurrentPossibleMoves,
        showMoveLines = showMoveLines,
        pieceSet = restorePieceSet(fields[3]),
    )
}

internal fun restorePieceSet(savedName: String): PieceSet =
    PieceSet.entries.firstOrNull { it.name == savedName } ?: PieceSet.STANDARD

private const val SETTINGS_VERSION = "1"
private const val SETTINGS_SEPARATOR = "|"
private const val SETTINGS_FIELD_COUNT = 4
