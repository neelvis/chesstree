package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.PlayerId

/** Exact rules-derived king attackers, reused only for this immutable state. */
internal class BotKingAttackContext(val state: GameState) {
    private val attackersByPlayer = arrayOfNulls<Set<PlayerId>>(PlayerId.entries.size)

    internal var computedPlayerCount: Int = 0
        private set

    fun attackers(player: PlayerId): Set<PlayerId> {
        attackersByPlayer[player.ordinal]?.let { return it }
        return LegalMoveGenerator.attackingPlayers(state, player).also {
            attackersByPlayer[player.ordinal] = it
            computedPlayerCount++
        }
    }
}
