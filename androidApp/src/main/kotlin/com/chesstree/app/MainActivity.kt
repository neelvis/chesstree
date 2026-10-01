package com.chesstree.app

import com.chesstree.game.presentation.bot.LocalBotActivity
import com.chesstree.game.presentation.bot.BackgroundLocalBotRunner

import android.content.Intent
import android.graphics.Color
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.chesstree.multiplayer.data.KtorChessTreeApi
import com.chesstree.multiplayer.data.PRODUCTION_SERVER_BASE_URL
import com.chesstree.multiplayer.data.gameCodeFromUrl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val onlineApi = KtorChessTreeApi(PRODUCTION_SERVER_BASE_URL)
    private val botActivity = LocalBotActivity(false)
    private val linkedGameCode = MutableStateFlow<String?>(null)
    private var notificationPermissionRequest: CompletableDeferred<Boolean>? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationPermissionRequest?.complete(granted)
        notificationPermissionRequest = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(
                CHESS_TREE_CANVAS_ARGB,
                CHESS_TREE_CANVAS_ARGB,
            ),
        )
        createNotificationChannel()
        acceptGameLink(intent)
        val saveStore = AndroidGameSaveStore(applicationContext)
        val onlineSessionStore = AndroidOnlineSessionStore(applicationContext)
        val gameLogExporter = AndroidGameLogExporter(this)
        val pushTokenProvider = AndroidPushTokenProvider {
            requestNotificationPermission()
        }
        setContent {
            val initialGameCode by linkedGameCode.collectAsState()
            App(
                botRunner = BackgroundLocalBotRunner,
                botActivity = botActivity,
                gameSaveStore = saveStore,
                onlineApi = onlineApi,
                onlineSessionStore = onlineSessionStore,
                pushTokenProvider = pushTokenProvider,
                initialGameCode = initialGameCode,
                gameLinkSharer = AndroidGameLinkSharer(this@MainActivity),
                gameLogExporter = gameLogExporter,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        botActivity.setActive(true)
    }

    override fun onPause() {
        botActivity.setActive(false)
        super.onPause()
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
        val link = intent.dataString ?: intent.getStringExtra("deepLink")
        link?.let(::gameCodeFromUrl)?.let { linkedGameCode.value = it }
    }

    private suspend fun requestNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true
        }
        val request = CompletableDeferred<Boolean>()
        notificationPermissionRequest = request
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        return request.await()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            "game_events",
            "Game notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
