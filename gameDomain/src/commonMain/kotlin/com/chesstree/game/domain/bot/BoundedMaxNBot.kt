package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId

enum class BotStopSignal {
    CONTINUE,
    CANCEL,
    TIMEOUT,
}

fun interface BotStopProbe {
    fun signal(): BotStopSignal
}

enum class BotMoveSource {
    COMPLETED_DEPTH,
    LEGAL_FALLBACK,
}

enum class BotStopReason {
    DEPTH_COMPLETE,
    TIMEOUT,
    NODE_LIMIT,
    EVALUATION_LIMIT,
    EXECUTION_FAILURE,
}

data class BoundedBotStats(
    val expandedNodes: Int,
    val leafEvaluations: Int,
    val completedDepth: Int,
)

data class BotOpeningDiagnostics(
    val version: Int = 0,
    val influenced: Boolean = false,
)

enum class BotFailureReason {
    INVALID_STATE,
    GENERATED_MOVE_REJECTED,
    NO_CANDIDATE,
}

sealed interface BoundedBotResult {
    data class Move(
        val intent: MoveIntent,
        val source: BotMoveSource,
        val reason: BotStopReason,
        val stats: BoundedBotStats,
        val openingBook: BotOpeningDiagnostics = BotOpeningDiagnostics(),
    ) : BoundedBotResult

    data class Terminal(
        val phase: GamePhase.Finished,
    ) : BoundedBotResult

    data class Cancelled(
        val stats: BoundedBotStats,
    ) : BoundedBotResult

    data class Failed(
        val reason: BotFailureReason,
    ) : BoundedBotResult
}

/**
 * Iterative MaxN for a runner with an externally owned deadline and lifecycle.
 * Only a fully searched root depth replaces the authoritative legal fallback.
 * The legacy [MaxNBot] remains the fixed-depth comparison control.
 */
class BoundedMaxNBot(
    private val maxDepth: Int = 2,
    private val maxNodes: Int = 128,
    private val maxEvaluations: Int = 128,
    private val evaluation: BotEvaluationMode = BotEvaluationMode.CONTROL,
    private val openingBookVersion: Int = 0,
    private val seed: Long = 0,
) {
    init {
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxEvaluations > 0)
        require(openingBookVersion == 0 || openingBookVersion == BotOpeningBook.VERSION)
    }

    fun choose(
        state: GameState,
        policy: BotPolicy = BotPolicy.DEFAULT,
        stop: BotStopProbe = BotStopProbe { BotStopSignal.CONTINUE },
        onFallbackReady: (MoveIntent) -> Unit = {},
        onCompletedDepth: (BoundedBotResult.Move) -> Unit = {},
    ): BoundedBotResult {
        val phase = state.phase
        if (phase is GamePhase.Finished) return BoundedBotResult.Terminal(phase)
        val actor = state.turn?.player
            ?: return BoundedBotResult.Failed(BotFailureReason.INVALID_STATE)
        if (stop.signal() == BotStopSignal.CANCEL) {
            return BoundedBotResult.Cancelled(BoundedBotStats(0, 0, 0))
        }

        val rootMoves = LegalMoveGenerator.legalMoves(state, actor)
        if (rootMoves.isEmpty()) {
            return BoundedBotResult.Failed(BotFailureReason.INVALID_STATE)
        }
        if (stop.signal() == BotStopSignal.CANCEL) {
            return BoundedBotResult.Cancelled(BoundedBotStats(0, 0, 0))
        }
        val fallback = rootMoves.first().intent()
        onFallbackReady(fallback)
        val preferred = if (openingBookVersion == BotOpeningBook.VERSION) {
            BotOpeningBook.preferredMoves(state, seed).filter { intent -> rootMoves.any { it.intent() == intent } }
        } else emptyList()
        val search = Search(
            policy, maxDepth, maxNodes, maxEvaluations, stop, evaluation, onCompletedDepth,
            openingBookVersion, preferred,
        )
        return search.choose(state, actor, rootMoves, fallback)
    }

    private class Search(
        private val policy: BotPolicy,
        private val maxDepth: Int,
        private val maxNodes: Int,
        private val maxEvaluations: Int,
        private val stop: BotStopProbe,
        private val evaluation: BotEvaluationMode,
        private val onCompletedDepth: (BoundedBotResult.Move) -> Unit,
        private val openingBookVersion: Int,
        private val preferred: List<MoveIntent>,
    ) {
        private var expandedNodes = 0
        private var leafEvaluations = 0
        private var completedDepth = 0
        private var interruption: Interruption? = null
        private var failure: BotFailureReason? = null

        private fun stats() = BoundedBotStats(expandedNodes, leafEvaluations, completedDepth)

        fun choose(
            state: GameState,
            actor: PlayerId,
            rootMoves: List<Move>,
            fallback: MoveIntent,
        ): BoundedBotResult {
            var chosen: MoveIntent? = null
            var openingInfluenced = false
            for (depth in 1..maxDepth) {
                if (!enterNode()) break
                var bestMove: Move? = null
                var bestScore: DoubleArray? = null
                var firstBestMove: Move? = null
                for (move in rootMoves) {
                    if (!checkStop()) break
                    val transition = GameReducer.reduceGeneratedLegalMove(state, move)
                    if (transition == null) {
                        return BoundedBotResult.Failed(BotFailureReason.GENERATED_MOVE_REJECTED)
                    }
                    val score = evaluateAfter(
                        transition.state,
                        depth - 1,
                        transition.nextLegalMoves,
                    ) ?: break
                    if (bestScore == null || score[actor.ordinal] > bestScore[actor.ordinal]) {
                        bestMove = move
                        firstBestMove = move
                        bestScore = score
                    } else if (score[actor.ordinal] == bestScore[actor.ordinal] &&
                        preference(move) < preference(checkNotNull(bestMove))
                    ) {
                        bestMove = move
                    }
                }
                failure?.let { return BoundedBotResult.Failed(it) }
                if (!checkStop()) break
                chosen = bestMove?.intent()
                    ?: return BoundedBotResult.Failed(BotFailureReason.NO_CANDIDATE)
                completedDepth = depth
                openingInfluenced = bestMove != firstBestMove
                onCompletedDepth(BoundedBotResult.Move(
                    intent = checkNotNull(chosen),
                    source = BotMoveSource.COMPLETED_DEPTH,
                    reason = BotStopReason.DEPTH_COMPLETE,
                    stats = stats(),
                    openingBook = BotOpeningDiagnostics(openingBookVersion, openingInfluenced),
                ))
            }

            val reason = when (interruption) {
                Interruption.TIMED_OUT -> BotStopReason.TIMEOUT
                Interruption.NODE_LIMIT -> BotStopReason.NODE_LIMIT
                Interruption.EVALUATION_LIMIT -> BotStopReason.EVALUATION_LIMIT
                Interruption.CANCELLED -> return BoundedBotResult.Cancelled(stats())
                null -> BotStopReason.DEPTH_COMPLETE
            }
            return BoundedBotResult.Move(
                intent = chosen ?: fallback,
                source = if (chosen == null) BotMoveSource.LEGAL_FALLBACK else BotMoveSource.COMPLETED_DEPTH,
                reason = reason,
                stats = stats(),
                openingBook = BotOpeningDiagnostics(openingBookVersion, openingInfluenced),
            )
        }

        private fun preference(move: Move): Int = preferred.indexOf(move.intent()).let {
            if (it < 0) Int.MAX_VALUE else it
        }

        private fun evaluateAfter(
            state: GameState,
            depth: Int,
            knownLegalMoves: List<Move>,
        ): DoubleArray? {
            if (!checkStop()) return null
            if (state.phase != GamePhase.InProgress || depth == 0) {
                if (leafEvaluations >= maxEvaluations) {
                    interruption = Interruption.EVALUATION_LIMIT
                    return null
                }
                leafEvaluations++
                val score = evaluation.evaluate(state, policy, knownLegalMoves)
                return score.takeIf { checkStop() }
            }
            if (!enterNode()) return null
            val actor = state.turn?.player
            if (actor == null) {
                failure = BotFailureReason.INVALID_STATE
                return null
            }
            val moves = knownLegalMoves.ifEmpty { LegalMoveGenerator.legalMoves(state, actor) }
            if (moves.isEmpty()) {
                failure = BotFailureReason.INVALID_STATE
                return null
            }
            var best: DoubleArray? = null
            for (move in moves) {
                if (!checkStop()) return null
                val transition = GameReducer.reduceGeneratedLegalMove(state, move)
                if (transition == null) {
                    failure = BotFailureReason.GENERATED_MOVE_REJECTED
                    return null
                }
                val score = evaluateAfter(
                    transition.state,
                    depth - 1,
                    transition.nextLegalMoves,
                ) ?: return null
                if (best == null || score[actor.ordinal] > best[actor.ordinal]) best = score
            }
            return best
        }

        private fun enterNode(): Boolean {
            if (!checkStop()) return false
            if (expandedNodes >= maxNodes) {
                interruption = Interruption.NODE_LIMIT
                return false
            }
            expandedNodes++
            return true
        }

        private fun checkStop(): Boolean {
            if (interruption != null) return false
            interruption = when (stop.signal()) {
                BotStopSignal.CONTINUE -> null
                BotStopSignal.CANCEL -> Interruption.CANCELLED
                BotStopSignal.TIMEOUT -> Interruption.TIMED_OUT
            }
            return interruption == null
        }
    }

    private enum class Interruption {
        CANCELLED,
        TIMED_OUT,
        NODE_LIMIT,
        EVALUATION_LIMIT,
    }
}

private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)
