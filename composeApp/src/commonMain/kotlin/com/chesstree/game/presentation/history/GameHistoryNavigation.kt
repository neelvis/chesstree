package com.chesstree.game.presentation.history

import com.chesstree.game.domain.session.GameSession

internal data class GameHistoryNavigation(
    private val viewedMoveCount: Int? = null,
) {
    fun displayedMoveCount(session: GameSession): Int =
        viewedMoveCount?.coerceIn(0, session.moves.size) ?: session.moves.size

    fun isAtLatest(session: GameSession): Boolean =
        displayedMoveCount(session) == session.moves.size

    fun canGoBack(session: GameSession): Boolean = displayedMoveCount(session) > 0

    fun canGoForward(session: GameSession): Boolean = !isAtLatest(session)

    fun back(session: GameSession): GameHistoryNavigation =
        GameHistoryNavigation((displayedMoveCount(session) - 1).coerceAtLeast(0))

    fun forward(session: GameSession): GameHistoryNavigation {
        val nextMoveCount = displayedMoveCount(session) + 1
        return if (nextMoveCount >= session.moves.size) latest() else GameHistoryNavigation(nextMoveCount)
    }

    fun displayedSession(session: GameSession): GameSession {
        val moveCount = displayedMoveCount(session)
        if (moveCount == session.moves.size) return session
        return checkNotNull(
            GameSession.replayPosition(session.scenario, session.moves.take(moveCount)),
        )
    }

    companion object {
        fun latest(): GameHistoryNavigation = GameHistoryNavigation()
    }
}
