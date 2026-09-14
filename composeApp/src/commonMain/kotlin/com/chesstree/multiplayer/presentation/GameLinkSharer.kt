package com.chesstree.multiplayer.presentation

enum class GameLinkShareResult {
    COPIED,
    SHARE_SHEET_OPENED,
}

fun interface GameLinkSharer {
    suspend fun share(url: String): GameLinkShareResult
}
