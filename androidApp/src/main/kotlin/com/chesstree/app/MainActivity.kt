package com.chesstree.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.chesstree.multiplayer.data.KtorChessTreeApi

class MainActivity : ComponentActivity() {
    private val onlineApi = KtorChessTreeApi("http://10.0.2.2:8081")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saveStore = AndroidGameSaveStore(applicationContext)
        setContent { App(saveStore, onlineApi) }
    }

    override fun onDestroy() {
        onlineApi.close()
        super.onDestroy()
    }
}
