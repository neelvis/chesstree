package com.chesstree.game.domain

/** Deterministic attribution for a terminal position with one or more checking controllers. */
object MateAttributionPolicy {
    fun author(
        attackers: Set<PlayerId>,
        attackersBeforeMove: Set<PlayerId>,
        lastMover: PlayerId,
    ): PlayerId {
        require(attackers.isNotEmpty()) { "A checkmate must have at least one attacking player" }
        val newlyRevealedAttackers = attackers - attackersBeforeMove
        return when {
            attackers.size == 1 -> attackers.single()
            newlyRevealedAttackers.size == 1 -> newlyRevealedAttackers.single()
            lastMover in attackers -> lastMover
            else -> attackers.minBy(PlayerId::ordinal)
        }
    }
}
