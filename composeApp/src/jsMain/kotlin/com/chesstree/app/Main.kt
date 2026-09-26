package com.chesstree.app

import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.chesstree.game.presentation.history.BrowserGameLogExporter
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.gameCodeFromUrl
import com.chesstree.multiplayer.presentation.BrowserGameLinkSharer
import com.chesstree.resources.Res
import com.chesstree.resources.allFontResources
import kotlinx.browser.window
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.preloadFont

@OptIn(ExperimentalComposeUiApi::class, ExperimentalResourceApi::class)
fun main() {
    val saveStore = BrowserGameSaveStore()
    val onlineSessionStore = BrowserOnlineSessionStore()
    val onlineApi = KtorChessTreeApi(serverBaseUrl())
    val initialGameCode = gameCodeFromUrl(window.location.href)
    ComposeViewport(viewportContainerId = "webApp") {
        val pieceFont by preloadFont(
            Res.allFontResources.getValue("noto_sans_symbols_2_regular"),
        )
        if (pieceFont != null) {
            App(
                gameSaveStore = saveStore,
                onlineApi = onlineApi,
                onlineSessionStore = onlineSessionStore,
                initialGameCode = initialGameCode,
                gameLinkSharer = BrowserGameLinkSharer(),
                gameLogExporter = BrowserGameLogExporter(),
            )
        }
    }
}

private fun serverBaseUrl(): String =
    if (window.location.hostname in setOf("localhost", "127.0.0.1")) {
        "${window.location.protocol}//${window.location.hostname}:8081"
    } else {
        window.location.origin
    }
