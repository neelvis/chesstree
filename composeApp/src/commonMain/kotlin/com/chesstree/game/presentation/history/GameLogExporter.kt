package com.chesstree.game.presentation.history

enum class GameLogExportResult {
    COPIED,
    FILE_SAVED,
    FILE_DIALOG_OPENED,
    UNAVAILABLE,
}

interface GameLogExporter {
    suspend fun copy(contents: String): GameLogExportResult

    suspend fun save(contents: String): GameLogExportResult
}

object NoOpGameLogExporter : GameLogExporter {
    override suspend fun copy(contents: String): GameLogExportResult =
        GameLogExportResult.UNAVAILABLE

    override suspend fun save(contents: String): GameLogExportResult =
        GameLogExportResult.UNAVAILABLE
}
