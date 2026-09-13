package com.chesstree.app

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.gameCodeFromUrl
import kotlinx.coroutines.flow.MutableStateFlow
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val saveStore = IosGameSaveStore()
    val onlineSessionStore = IosOnlineSessionStore()
    val onlineApi = KtorChessTreeApi("http://127.0.0.1:8081")
    return ComposeUIViewController {
        val initialGameCode by linkedGameCode.collectAsState()
        App(saveStore, onlineApi, onlineSessionStore, initialGameCode)
    }
}

private val linkedGameCode = MutableStateFlow<String?>(null)

fun OpenGameLink(url: String) {
    gameCodeFromUrl(url)?.let { linkedGameCode.value = it }
}
