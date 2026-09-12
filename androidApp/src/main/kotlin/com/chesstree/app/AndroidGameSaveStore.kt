package com.chesstree.app

import android.content.Context
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.SaveGameResult

class AndroidGameSaveStore(context: Context) : GameSaveStore {
    private val preferences = context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    override fun save(contents: String): SaveGameResult = runCatching {
        preferences.edit().putString(SAVE_KEY, contents).apply()
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    override fun load(): LoadGameResult = runCatching {
        preferences.getString(SAVE_KEY, null)
            ?.let(LoadGameResult::Loaded)
            ?: LoadGameResult.Missing
    }.getOrElse { error ->
        LoadGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    private companion object {
        const val STORE_NAME = "chess_tree_game"
        const val SAVE_KEY = "last_game_v1"
    }
}
