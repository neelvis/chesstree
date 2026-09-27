package com.chesstree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.chesstree.game.presentation.history.IosGameLogExporter
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.PRODUCTION_SERVER_BASE_URL
import com.chesstree.multiplayer.data.gameCodeFromUrl
import com.chesstree.multiplayer.presentation.IosGameLinkSharer
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val saveStore = IosGameSaveStore()
    val onlineSessionStore = IosOnlineSessionStore()
    val onlineApi = KtorChessTreeApi(PRODUCTION_SERVER_BASE_URL)
    lateinit var rootViewController: UIViewController
    rootViewController = ComposeUIViewController {
        val initialGameCode by linkedGameCode.collectAsState()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF7F4EF)),
        ) {
            App(
                gameSaveStore = saveStore,
                onlineApi = onlineApi,
                onlineSessionStore = onlineSessionStore,
                pushTokenProvider = IosPushTokenProvider,
                initialGameCode = initialGameCode,
                gameLinkSharer = IosGameLinkSharer { rootViewController },
                gameLogExporter = IosGameLogExporter { rootViewController },
                topContentPadding = 40.dp,
            )
            IosNotchArtwork(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 15.dp),
            )
        }
    }
    return rootViewController
}

private val linkedGameCode = MutableStateFlow<String?>(null)
internal val iosPushToken = MutableStateFlow<String?>(null)

fun OpenGameLink(url: String) {
    gameCodeFromUrl(url)?.let { linkedGameCode.value = it }
}

fun PushTokenUpdated(token: String?) {
    iosPushToken.value = token
}
