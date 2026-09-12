package com.chesstree.app

import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.SaveGameResult
import kotlinx.browser.localStorage

class BrowserGameSaveStore : GameSaveStore {
    override fun save(contents: String): SaveGameResult = runCatching {
        localStorage.setItem(SAVE_KEY, contents)
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    override fun load(): LoadGameResult = runCatching {
        localStorage.getItem(SAVE_KEY)
            ?.let(LoadGameResult::Loaded)
            ?: LoadGameResult.Missing
    }.getOrElse { error ->
        LoadGameResult.Failed(error.message ?: "неизвестная ошибка")
    }

    private companion object {
        const val SAVE_KEY = "chess_tree.last_game_v1"
    }
}
