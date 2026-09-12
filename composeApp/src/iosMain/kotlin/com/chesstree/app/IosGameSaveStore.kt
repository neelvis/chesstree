package com.chesstree.app

import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.SaveGameResult
import platform.Foundation.NSUserDefaults

class IosGameSaveStore : GameSaveStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun save(contents: String): SaveGameResult = runCatching {
        defaults.setObject(contents, forKey = SAVE_KEY)
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    override fun load(): LoadGameResult = runCatching {
        defaults.stringForKey(SAVE_KEY)
            ?.let(LoadGameResult::Loaded)
            ?: LoadGameResult.Missing
    }.getOrElse { error ->
        LoadGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    private companion object {
        const val SAVE_KEY = "chess_tree.last_game_v1"
    }
}
