package com.chesstree.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.gameCodeFromUrl
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

class MainActivity : ComponentActivity() {
    private val onlineApi = KtorChessTreeApi("http://10.0.2.2:8081")
    private val linkedGameCode = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acceptGameLink(intent)
        val saveStore = AndroidGameSaveStore(applicationContext)
        val onlineSessionStore = AndroidOnlineSessionStore(applicationContext)
        val gameLogExporter = AndroidGameLogExporter(this)
        setContent {
            val initialGameCode by linkedGameCode.collectAsState()
            App(
                gameSaveStore = saveStore,
                onlineApi = onlineApi,
                onlineSessionStore = onlineSessionStore,
                initialGameCode = initialGameCode,
                gameLinkSharer = AndroidGameLinkSharer(this),
                gameLogExporter = gameLogExporter,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptGameLink(intent)
    }

    override fun onDestroy() {
        onlineApi.close()
        super.onDestroy()
    }

    private fun acceptGameLink(intent: Intent) {
        intent.dataString?.let(::gameCodeFromUrl)?.let { linkedGameCode.value = it }
    }
}
