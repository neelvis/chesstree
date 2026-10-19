package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.scenario.StandardGame
import java.util.Random

/** Frozen before tuning; independent trajectories, not independent evidence of strength. */
internal object BotQualityHeldOutCases {
    data class Case(val id: String, val seed: Long, val history: List<GameState>, val moves: List<MoveIntent>) {
        val state: GameState get() = history.last()
    }

    // The selection rule does not inspect the recorded game, bot scores or bot choices.
    fun all(): List<Case> = listOf(
        104729L to 24, 130363L to 25, 155921L to 26,
        181081L to 42, 206369L to 43, 232003L to 44,
    ).map { (seed, plies) ->
        val random = Random(seed)
        val history = mutableListOf(StandardGame.scenario.initialState)
        val moves = mutableListOf<MoveIntent>()
        repeat(plies) {
            val before = history.last()
            check(before.phase == GamePhase.InProgress) { "Held-out trajectory $seed terminated before $plies" }
            val legal = LegalMoveGenerator.legalMoves(before)
            val move = legal[random.nextInt(legal.size)]
            val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
            val applied = GameReducer.reduce(before, intent)
            check(applied is MoveReduction.Applied) { "Held-out replay $seed rejected $intent" }
            moves += intent
            history += applied.state
        }
        Case("held-out-$seed-$plies", seed, history.toList(), moves.toList())
    }
}
