package com.chesstree.game.presentation.bot

import com.chesstree.game.data.BotSeatConfig
import com.chesstree.game.data.LocalBotGameConfig
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalBotGameRequestsTest {
    private val identity = LocalBotIdentity("request", "game", 0)

    @Test
    fun eachSeatUsesItsOwnFrozenSettingsAndHumanTurnsAreNotAutomated() {
        val config = LocalBotGameConfig(PlayerId.WHITE, listOf(
            BotSeatConfig(PlayerId.RED, BotPlayingProfile.ATTACKING, BotDifficulty.BEGINNER, 12),
            BotSeatConfig(PlayerId.BLACK, BotPlayingProfile.ENDGAME, BotDifficulty.STRONG, 34),
        ))
        var session = GameSession(ManualGameScenarios.standard)
        assertNull(config.requestForTurn(identity, session))
        session = next(session)
        val red = assertNotNull(config.requestForTurn(identity, session))
        assertEquals(PlayerId.RED, red.state.turn?.player)
        assertEquals(BotPlayingProfile.ATTACKING, red.profile)
        assertEquals(BotDifficulty.BEGINNER, red.difficulty)
        assertEquals(100, red.searchBudgetMs)
        assertEquals(150, red.budgetMs)
        assertEquals(12, red.seed)
        session = next(session)
        val black = assertNotNull(config.requestForTurn(identity, session))
        assertEquals(BotPlayingProfile.ENDGAME, black.profile)
        assertEquals(BotDifficulty.STRONG, black.difficulty)
        assertEquals(900, black.searchBudgetMs)
        assertEquals(1200, black.budgetMs)
        assertEquals(34, black.seed)
        assertEquals(config.basePolicy, black.policy)
        assertEquals(3, black.engineVersion)
        assertEquals(2, black.profileCatalogVersion)
        assertEquals(4, black.tacticalDepth)
        assertEquals(session.state, black.recentPositions.last())
        assertEquals(session.atMoveCount(0)?.state, black.recentPositions.first())
    }

    @Test
    fun undoRemovesTheLastHumanDecisionAndBothBotResponses() {
        val config = LocalBotGameConfig.humanGame(PlayerId.WHITE, mapOf(PlayerId.RED to 12L, PlayerId.BLACK to 34L))
        val initial = GameSession(ManualGameScenarios.standard)
        assertNull(initial.beforeLatestHumanMove(config))
        var session = initial
        repeat(3) { session = next(session) }
        val undone = assertNotNull(session.beforeLatestHumanMove(config))
        assertEquals(initial.state, undone.state)
        assertEquals(emptyList(), undone.moves)
        val firstRound = session
        repeat(2) { session = next(session) }
        assertEquals(firstRound.state, assertNotNull(session.beforeLatestHumanMove(config)).state)
    }

    @Test
    fun terminalAndWatchGamesNeverExposeAHumanUndo() {
        val config = LocalBotGameConfig.watchGame(PlayerId.entries.associateWith { it.ordinal.toLong() })
        assertNull(config.requestForTurn(identity, GameSession(ManualGameScenarios.finishedGame)))
        assertNull(next(GameSession(ManualGameScenarios.standard)).beforeLatestHumanMove(config))
    }

    private fun next(session: GameSession): GameSession {
        val move = LegalMoveGenerator.legalMoves(session.state).first()
        return assertIs<SessionMoveResult.Applied>(session.apply(MoveIntent(move.actor, move.from, move.to, move.promotion))).session
    }
}
