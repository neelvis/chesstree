package com.chesstree.game.presentation.bot

import com.chesstree.game.data.LocalBotGameConfig
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.bot.BotEvaluationMode
import com.chesstree.game.domain.bot.BotSearchModel
import com.chesstree.game.domain.bot.MAX_BOT_RECENT_POSITIONS
import com.chesstree.game.domain.session.GameSession

/** Each seat searches the public session independently with its frozen game settings. */
internal fun LocalBotGameConfig.requestForTurn(
    identity: LocalBotIdentity,
    session: GameSession,
): LocalBotRequest? {
    if (session.state.phase != GamePhase.InProgress) return null
    val player = session.state.turn?.player ?: return null
    val seat = seatFor(player) ?: return null
    return LocalBotRequest(
        identity = identity,
        state = session.state,
        policy = basePolicy,
        budgetMs = seat.difficulty.hardBudgetMs,
        softBudgetMs = seat.difficulty.softBudgetMs,
        maxDepth = seat.difficulty.maxDepth,
        evaluation = BotEvaluationMode.POSITIONAL,
        openingBookVersion = bookVersion,
        seed = seat.seed,
        tacticalDepth = 4,
        searchModel = BotSearchModel.PARANOID,
        profile = seat.profile,
        profileCatalogVersion = profileCatalogVersion,
        difficulty = seat.difficulty,
        recentPositions = ((session.moves.size - MAX_BOT_RECENT_POSITIONS + 1).coerceAtLeast(0)..session.moves.size)
            .mapNotNull { session.atMoveCount(it)?.state },
    )
}

/** Undo a human decision and the bot replies that followed it. */
internal fun GameSession.beforeLatestHumanMove(config: LocalBotGameConfig): GameSession? {
    val human = config.humanSeat ?: return null
    val index = moves.indexOfLast { it.actor == human }
    return if (index >= 0) atMoveCount(index) else null
}
