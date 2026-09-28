package com.chesstree.game.data

import com.chesstree.game.domain.bot.BotPolicy

interface GameSaveStore {
    fun save(contents: String): SaveGameResult
    fun load(): LoadGameResult

    /** Local bot strategy is stored separately from the last game record. */
    fun loadBotPolicy(): BotPolicy = BotPolicy.DEFAULT
    fun saveBotPolicy(policy: BotPolicy): SaveGameResult = SaveGameResult.Saved
}

sealed interface SaveGameResult {
    data object Saved : SaveGameResult
    data class Failed(val message: String) : SaveGameResult
}

sealed interface LoadGameResult {
    data class Loaded(val contents: String) : LoadGameResult
    data object Missing : LoadGameResult
    data class Failed(val message: String) : LoadGameResult
}

object NoOpGameSaveStore : GameSaveStore {
    override fun save(contents: String): SaveGameResult = SaveGameResult.Saved
    override fun load(): LoadGameResult = LoadGameResult.Missing
}
