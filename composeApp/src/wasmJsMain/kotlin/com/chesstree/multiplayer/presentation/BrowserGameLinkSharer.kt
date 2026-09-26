package com.chesstree.multiplayer.presentation

import kotlinx.browser.window
import kotlinx.coroutines.await

@OptIn(ExperimentalWasmJsInterop::class)
class BrowserGameLinkSharer : GameLinkSharer {
    override suspend fun share(url: String): GameLinkShareResult {
        window.navigator.clipboard.writeText(url).await()
        return GameLinkShareResult.COPIED
    }
}
