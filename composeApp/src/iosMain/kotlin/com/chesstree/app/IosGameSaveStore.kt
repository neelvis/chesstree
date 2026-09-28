package com.chesstree.app

import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.SaveGameResult
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotPolicyCodec
import platform.Foundation.NSUserDefaults

class IosGameSaveStore : GameSaveStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun save(contents: String): SaveGameResult = runCatching {
        defaults.setObject(contents, forKey = SAVE_KEY)
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    override fun load(): LoadGameResult = runCatching {
        defaults.stringForKey(SAVE_KEY)
            ?.let(LoadGameResult::Loaded)
            ?: LoadGameResult.Missing
    }.getOrElse { error ->
        LoadGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    override fun loadBotPolicy(): BotPolicy = runCatching {
        BotPolicyCodec.decode(defaults.stringForKey(POLICY_KEY)) ?: BotPolicy.DEFAULT
    }.getOrDefault(BotPolicy.DEFAULT)

    override fun saveBotPolicy(policy: BotPolicy): SaveGameResult = runCatching {
        defaults.setObject(BotPolicyCodec.encode(policy), forKey = POLICY_KEY)
        SaveGameResult.Saved
    }.getOrElse { error ->
        SaveGameResult.Failed(error.message ?: "i18n:unknown_error")
    }

    private companion object {
        const val SAVE_KEY = "chess_tree.last_game_v1"
        const val POLICY_KEY = "chess_tree.bot_policy_v1"
    }
}
