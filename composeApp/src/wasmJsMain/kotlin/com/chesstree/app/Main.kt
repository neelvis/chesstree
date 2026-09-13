package com.chesstree.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.gameCodeFromUrl
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val saveStore = BrowserGameSaveStore()
    val onlineApi = KtorChessTreeApi(serverBaseUrl())
    val initialGameCode = gameCodeFromUrl(window.location.href)
    ComposeViewport(viewportContainerId = "webApp") {
        App(
            gameSaveStore = saveStore,
            onlineApi = onlineApi,
            initialGameCode = initialGameCode,
        )
    }
}

private fun serverBaseUrl(): String = if (window.location.hostname in setOf("localhost", "127.0.0.1")) {
    "${window.location.protocol}//${window.location.hostname}:8081"
} else {
    window.location.origin
}
