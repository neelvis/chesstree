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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import com.chesstree.game.presentation.history.IosGameLogExporter
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.PRODUCTION_SERVER_BASE_URL
import com.chesstree.multiplayer.data.gameCodeFromUrl
import com.chesstree.multiplayer.presentation.IosGameLinkSharer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIScreen
import platform.UIKit.UIViewController
import platform.posix.uname
import platform.posix.utsname

fun MainViewController(): UIViewController {
    val saveStore = IosGameSaveStore()
    val onlineSessionStore = IosOnlineSessionStore()
    val onlineApi = KtorChessTreeApi(PRODUCTION_SERVER_BASE_URL)
    val notchArtworkSize = dynamicIslandArtworkSize()
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
            notchArtworkSize?.let { size ->
                IosNotchArtwork(
                    size = size,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = 15.dp),
                )
            }
        }
    }
    return rootViewController
}

@OptIn(ExperimentalForeignApi::class)
private fun dynamicIslandArtworkSize(): DpSize? {
    val model = (NSProcessInfo.processInfo.environment
        .get("SIMULATOR_MODEL_IDENTIFIER") as? String)
        ?: memScoped {
            val systemInfo = alloc<utsname>()
            if (uname(systemInfo.ptr) == 0) systemInfo.machine.toKString() else return null
        }
    val isDynamicIslandModel = model in dynamicIslandModels ||
        model.startsWith("iPhone19,")
    if (!isDynamicIslandModel) return null

    val isSmallerIslandGeneration = model.startsWith("iPhone19,")
    val screenWidth = UIScreen.mainScreen.bounds.useContents { size.width.toFloat() }
    val width = if (isSmallerIslandGeneration) 92.dp else 111.dp
    val screenWidthLimit = screenWidth * if (isSmallerIslandGeneration) 0.235f else 0.283f
    val responsiveWidth = if (screenWidthLimit > 0f) {
        minOf(width.value, screenWidthLimit)
    } else {
        width.value
    }
    return DpSize(responsiveWidth.dp, (responsiveWidth * 0.294f).dp)
}

private val dynamicIslandModels = setOf(
    "iPhone15,2", "iPhone15,3", // iPhone 14 Pro models
    "iPhone15,4", "iPhone15,5", // iPhone 15 and 15 Plus
    "iPhone16,1", "iPhone16,2", // iPhone 15 Pro models
    "iPhone17,1", "iPhone17,2", "iPhone17,3", "iPhone17,4", // iPhone 16 family
    "iPhone18,1", "iPhone18,2", "iPhone18,3", "iPhone18,4", // iPhone 17 family
)

private val linkedGameCode = MutableStateFlow<String?>(null)
internal val iosPushToken = MutableStateFlow<String?>(null)

fun OpenGameLink(url: String) {
    gameCodeFromUrl(url)?.let { linkedGameCode.value = it }
}

fun PushTokenUpdated(token: String?) {
    iosPushToken.value = token
}
