package com.chesstree.game.domain.bot

import com.chesstree.game.domain.PlayerId

/** Opt-in domain diagnostics; these are not part of worker or export contracts. */
/** Counts actual work only: cache hits add no work; unextended horizon leaves are normal evaluations. */
internal data class BotSearchWork(
    val normalNodes: Int = 0,
    val tacticalNodes: Int = 0,
    val normalEvaluations: Int = 0,
    val tacticalEvaluations: Int = 0,
) {
    operator fun minus(other: BotSearchWork) = BotSearchWork(
        normalNodes - other.normalNodes,
        tacticalNodes - other.tacticalNodes,
        normalEvaluations - other.normalEvaluations,
        tacticalEvaluations - other.tacticalEvaluations,
    )
}

internal enum class BotSearchAttemptStop {
    COMPLETED, TACTICAL_LIMIT, TIMEOUT, NODE_LIMIT, EVALUATION_LIMIT, CANCELLED, FAILURE, NOT_STARTED,
}

internal data class BotTacticalCutoff(
    val ply: Int?,
    val actor: PlayerId?,
    val checkedPlayers: Set<PlayerId>,
    val responders: Set<PlayerId>,
    val remaining: Int,
)

internal data class BotSearchAttemptDiagnostics(
    val depth: Int,
    val horizon: BotSearchHorizon,
    val work: BotSearchWork,
    val stop: BotSearchAttemptStop,
    val tacticalCutoff: BotTacticalCutoff?,
)

internal data class BotSearchDiagnostics(
    val work: BotSearchWork,
    val attempts: List<BotSearchAttemptDiagnostics>,
    val stop: BotSearchAttemptStop,
    val failure: BotFailureReason?,
)

internal class BotSearchDiagnosticCollector {
    private var normalNodes = 0
    private var tacticalNodes = 0
    private var normalEvaluations = 0
    private var tacticalEvaluations = 0
    private val attempts = mutableListOf<BotSearchAttemptDiagnostics>()
    private var currentDepth: Int? = null
    private var currentHorizon = BotSearchHorizon.STATIC
    private var startWork = BotSearchWork()
    private var cutoff: BotTacticalCutoff? = null

    private fun work() = BotSearchWork(normalNodes, tacticalNodes, normalEvaluations, tacticalEvaluations)

    fun node(tactical: Boolean) {
        if (tactical) tacticalNodes++ else normalNodes++
    }

    fun evaluation(tactical: Boolean) {
        if (tactical) tacticalEvaluations++ else normalEvaluations++
    }

    fun beginAttempt(depth: Int, horizon: BotSearchHorizon) {
        check(currentDepth == null)
        currentDepth = depth
        currentHorizon = horizon
        startWork = work()
        cutoff = null
    }

    fun tacticalCutoff(value: BotTacticalCutoff) {
        cutoff = value
    }

    fun endAttempt(stop: BotSearchAttemptStop) {
        val depth = currentDepth ?: return
        attempts += BotSearchAttemptDiagnostics(depth, currentHorizon, work() - startWork, stop, cutoff)
        currentDepth = null
    }

    fun finish(result: BoundedBotResult): BotSearchDiagnostics {
        val stop = when (result) {
            is BoundedBotResult.Cancelled -> BotSearchAttemptStop.CANCELLED
            is BoundedBotResult.Failed -> BotSearchAttemptStop.FAILURE
            is BoundedBotResult.Terminal -> BotSearchAttemptStop.NOT_STARTED
            is BoundedBotResult.Move -> when (result.reason) {
                BotStopReason.DEPTH_COMPLETE -> BotSearchAttemptStop.COMPLETED
                BotStopReason.TIMEOUT -> BotSearchAttemptStop.TIMEOUT
                BotStopReason.NODE_LIMIT -> BotSearchAttemptStop.NODE_LIMIT
                BotStopReason.EVALUATION_LIMIT -> BotSearchAttemptStop.EVALUATION_LIMIT
                BotStopReason.TACTICAL_LIMIT -> BotSearchAttemptStop.TACTICAL_LIMIT
                BotStopReason.EXECUTION_FAILURE -> BotSearchAttemptStop.FAILURE
            }
        }
        endAttempt(stop)
        return BotSearchDiagnostics(work(), attempts.toList(), stop, (result as? BoundedBotResult.Failed)?.reason)
    }
}
