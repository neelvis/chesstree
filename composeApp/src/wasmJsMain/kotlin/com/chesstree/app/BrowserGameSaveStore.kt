package com.chesstree.app

import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.SaveGameResult
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotPolicyCodec
import kotlinx.browser.localStorage

class BrowserGameSaveStore : GameSaveStore {
    override fun save(contents: String): SaveGameResult = runCatching {
        localStorage.setItem(SAVE_KEY, contents)
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    override fun load(): LoadGameResult = runCatching {
        localStorage.getItem(SAVE_KEY)
            ?.let(LoadGameResult::Loaded)
            ?: LoadGameResult.Missing
    }.getOrElse { error ->
        LoadGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    override fun loadBotPolicy(): BotPolicy = runCatching {
        BotPolicyCodec.decode(localStorage.getItem(POLICY_KEY)) ?: BotPolicy.DEFAULT
    }.getOrDefault(BotPolicy.DEFAULT)

    override fun saveBotPolicy(policy: BotPolicy): SaveGameResult = runCatching {
        localStorage.setItem(POLICY_KEY, BotPolicyCodec.encode(policy))
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    private companion object {
        const val SAVE_KEY = "chess_tree.last_game_v1"
        const val POLICY_KEY = "chess_tree.bot_policy_v1"
    }
}
