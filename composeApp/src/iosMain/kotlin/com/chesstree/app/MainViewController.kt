package com.chesstree.app

import androidx.compose.ui.window.ComposeUIViewController
import com.chesstree.multiplayer.data.KtorChessTreeApi
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    val saveStore = IosGameSaveStore()
    val onlineApi = KtorChessTreeApi("http://127.0.0.1:8081")
    return ComposeUIViewController {
        App(saveStore, onlineApi)
    }
}
