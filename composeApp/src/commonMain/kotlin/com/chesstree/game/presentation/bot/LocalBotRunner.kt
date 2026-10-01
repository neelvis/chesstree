package com.chesstree.game.presentation.bot

import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.bot.BotEvaluationMode
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BoundedBotResult

data class LocalBotIdentity(
    val requestId: String,
    val gameId: String,
    val positionRevision: Long,
)

data class LocalBotRequest(
    val identity: LocalBotIdentity,
    val state: GameState,
    val policy: BotPolicy,
    val budgetMs: Int = 1_200,
    val maxDepth: Int = 3,
    val maxNodes: Int = 20_000,
    val maxEvaluations: Int = 20_000,
    val evaluation: BotEvaluationMode = BotEvaluationMode.CONTROL,
    val openingBookVersion: Int = 0,
    val seed: Long = 0L,
    val tacticalDepth: Int = 0,
    val recentPositions: List<GameState> = emptyList(),
    val searchModel: com.chesstree.game.domain.bot.BotSearchModel = com.chesstree.game.domain.bot.BotSearchModel.MAX_N,
    val profile: com.chesstree.game.domain.bot.BotPlayingProfile = com.chesstree.game.domain.bot.BotPlayingProfile.UNIVERSAL,
    val difficulty: com.chesstree.game.domain.bot.BotDifficulty? = null,
    val profileCatalogVersion: Int = 1,
    val softBudgetMs: Int = (budgetMs - budgetMs / 4).coerceAtLeast(1),
) {
    val searchBudgetMs: Int = softBudgetMs
    val engineVersion: Int get() = if (searchModel == com.chesstree.game.domain.bot.BotSearchModel.PARANOID) 3
        else if (tacticalDepth == 0) 1 else 2

    init {
        require(softBudgetMs in 1..budgetMs)
        require(profileCatalogVersion in 1..2)
        require(budgetMs > 0 && maxDepth > 0 && maxNodes > 0 && maxEvaluations > 0)
        require(identity.requestId.isNotBlank() && identity.gameId.isNotBlank())
        require(identity.positionRevision >= 0)
        require(openingBookVersion in 0..1)
        require(tacticalDepth in 0..6)
        require(recentPositions.size <= com.chesstree.game.domain.bot.MAX_BOT_RECENT_POSITIONS && (engineVersion != 1 || recentPositions.isEmpty()))
    }
}

sealed interface LocalBotReply {
    val identity: LocalBotIdentity

    data class Completed(
        override val identity: LocalBotIdentity,
        val result: BoundedBotResult,
    ) : LocalBotReply

    data class Failed(override val identity: LocalBotIdentity) : LocalBotReply
}

/** Cancellation must stop owned computation and must never deliver a move. */
fun interface LocalBotRunner {
    suspend fun choose(request: LocalBotRequest): LocalBotReply
}

internal fun LocalBotReply.matchesCurrentRequest(
    request: LocalBotRequest,
    activeIdentity: LocalBotIdentity?,
    gameId: String,
    positionRevision: Long,
    state: GameState,
): Boolean = identity == request.identity && identity == activeIdentity &&
    identity.gameId == gameId && identity.positionRevision == positionRevision &&
    request.state == state && request.state.turn?.player == state.turn?.player
