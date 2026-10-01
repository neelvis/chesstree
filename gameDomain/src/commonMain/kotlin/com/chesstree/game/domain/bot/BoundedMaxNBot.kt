package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
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
    TACTICAL_LIMIT,
}

enum class BotSearchModel { MAX_N, PARANOID }

enum class BotSearchHorizon { STATIC, CHECKS, EXCHANGES }

const val MAX_BOT_RECENT_POSITIONS = 36

data class BoundedBotStats(
    val expandedNodes: Int,
    val leafEvaluations: Int,
    val completedDepth: Int,
    val completedHorizon: BotSearchHorizon = BotSearchHorizon.STATIC,
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
        val repetitionPenalty: Double = 0.0,
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
 * Iterative search for a runner with an externally owned deadline and lifecycle.
 * MaxN is the default; the opt-in Paranoid experiment uses one fixed root component.
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
    private val tacticalDepth: Int = 0,
    private val searchModel: BotSearchModel = BotSearchModel.MAX_N,
    private val profile: BotPlayingProfile = BotPlayingProfile.UNIVERSAL,
    private val profileCatalogVersion: Int = 1,
) {
    init {
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxEvaluations > 0)
        require(openingBookVersion == 0 || openingBookVersion == BotOpeningBook.VERSION)
        require(tacticalDepth in 0..6)
        require(profileCatalogVersion in 1..2)
    }

    fun choose(
        state: GameState,
        policy: BotPolicy = BotPolicy.DEFAULT,
        stop: BotStopProbe = BotStopProbe { BotStopSignal.CONTINUE },
        onFallbackReady: (MoveIntent) -> Unit = {},
        onCompletedDepth: (BoundedBotResult.Move) -> Unit = {},
        recentPositions: List<GameState> = emptyList(),
    ): BoundedBotResult {
        require(recentPositions.size <= MAX_BOT_RECENT_POSITIONS)
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
        val fallback = (if (tacticalDepth == 0 && searchModel == BotSearchModel.MAX_N) rootMoves.first() else preferredFallback(state, rootMoves)).intent()
        onFallbackReady(fallback)
        val preferred = if (openingBookVersion == BotOpeningBook.VERSION) {
            BotOpeningBook.preferredMoves(state, seed).filter { intent -> rootMoves.any { it.intent() == intent } }
        } else emptyList()
        val search = Search(
            policy, maxDepth, maxNodes, maxEvaluations, stop, evaluation, onCompletedDepth,
            openingBookVersion, preferred,
            tacticalDepth,
            recentPositions.toList(), searchModel, profile, profileCatalogVersion,
        )
        return search.choose(state, actor, rootMoves, fallback)
    }

    private fun preferredFallback(state: GameState, moves: List<Move>): Move {
        val actor = checkNotNull(state.turn).player
        val threatened = state.position.pieces.values.filter { piece ->
            val controller = state.armies.getValue(piece.army).controller
            controller != actor && state.participants.getValue(controller).status == ParticipantStatus.Active
        }.flatMapTo(hashSetOf()) { LegalMoveGenerator.attackCoordinates(state.position, it) }
        return checkNotNull(moves.maxByOrNull { move ->
            val piece = state.position.pieces.getValue(move.pieceId)
            val gain = move.capturedPieceId?.let { state.position.pieces.getValue(it).type.fallbackValue() } ?: 0
            val promotion = move.promotion?.pieceType?.fallbackValue()?.minus(1) ?: 0
            val exposure = if (move.to in threatened) (move.promotion?.pieceType ?: piece.type).fallbackValue() else 0
            val activity = when (piece.type) {
                PieceType.KNIGHT, PieceType.BISHOP -> if (piece.hasMoved) 4 else 20
                PieceType.PAWN -> 10
                PieceType.ROOK -> 8
                PieceType.QUEEN -> 5
                PieceType.KING -> 0
            }
            (gain + promotion - exposure) * 100 + activity
        })
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
        private val tacticalDepth: Int,
        private val recentPositions: List<GameState>,
        private val searchModel: BotSearchModel,
        private val profile: BotPlayingProfile,
        private val profileCatalogVersion: Int,
    ) {
        private var expandedNodes = 0
        private var leafEvaluations = 0
        private var completedDepth = 0
        private var completedHorizon = BotSearchHorizon.STATIC
        private var rootActor = PlayerId.WHITE
        private var currentHorizon = BotSearchHorizon.STATIC
        private var interruption: Interruption? = null
        private var failure: BotFailureReason? = null

        private data class Candidate(val move: Move, val score: Double, val penalty: Double, val style: Double)

        private fun stats() = BoundedBotStats(expandedNodes, leafEvaluations, completedDepth, completedHorizon)

        fun choose(
            state: GameState,
            actor: PlayerId,
            rootMoves: List<Move>,
            fallback: MoveIntent,
        ): BoundedBotResult {
            rootActor = actor
            val activityHistory = recentPositions.map { previous -> previous to controlledPieces(previous, actor) }
            var chosen: MoveIntent? = null
            var openingInfluenced = false
            var chosenPenalty = 0.0
            var lastAttemptCapped = false
            val horizons = if (tacticalDepth == 0) listOf(BotSearchHorizon.STATIC)
                else listOf(BotSearchHorizon.CHECKS, BotSearchHorizon.EXCHANGES)
            iterations@ for (depth in 1..maxDepth) {
                for (horizon in horizons) {
                    currentHorizon = horizon
                    if (!enterNode()) break@iterations
                    var bestMove: Move? = null
                    var bestScore: DoubleArray? = null
                    var firstBestMove: Move? = null
                    var bestPenalty = 0.0
                    val candidates = mutableListOf<Candidate>()
                    val styleEnabled = profile != BotPlayingProfile.UNIVERSAL || profileCatalogVersion == 2
                    val closeThreshold = if (!styleEnabled) 0.0
                        else policy.weight(BotFeature.MATERIAL).coerceAtLeast(0) * 0.10
                    val orderedRoot = if (searchModel == BotSearchModel.MAX_N) rootMoves else {
                        val principal = rootMoves.first { it.intent() == (chosen ?: fallback) }
                        listOf(principal) + orderedMoves(state, rootMoves).filter { it != principal }
                    }
                    for (move in orderedRoot) {
                        if (!checkStop()) break
                        val transition = GameReducer.reduceGeneratedLegalMove(state, move)
                        if (transition == null) {
                            return BoundedBotResult.Failed(BotFailureReason.GENERATED_MOVE_REJECTED)
                        }
                        val score = evaluateAfter(
                            transition.state,
                            depth - 1,
                            transition.nextLegalMoves,
                            respondersAfter(transition.state, move, emptySet()),
                            if (searchModel == BotSearchModel.PARANOID) bestScore?.get(actor.ordinal)?.minus(closeThreshold) ?: Double.NEGATIVE_INFINITY
                            else Double.NEGATIVE_INFINITY,
                            Double.POSITIVE_INFINITY,
                        ) ?: break
                        val after = transition.state
                        val ownPieces = controlledPieces(after, actor)
                        val repeated = activityHistory.count { (previous, previousOwnPieces) ->
                            val exact = previous.position == after.position
                            // Opponent activity must not hide a quiet return to our own layout.
                            val ownReturn = move.capturedPieceId == null && move.promotion == null &&
                                after.phase == GamePhase.InProgress && previousOwnPieces == ownPieces &&
                                previous.position.pieces.keys == after.position.pieces.keys &&
                                previous.position.pieces.all { (id, piece) ->
                                    val current = after.position.pieces.getValue(id)
                                    piece.type == current.type && piece.army == current.army
                                } && previous.position.enPassantTargets == after.position.enPassantTargets &&
                                previous.position.castlingRights.filterTo(hashSetOf()) { previous.armies.getValue(it.army).controller == actor } ==
                                after.position.castlingRights.filterTo(hashSetOf()) { after.armies.getValue(it.army).controller == actor }
                            (exact || ownReturn) && previous.participants == after.participants &&
                                previous.armies == after.armies && previous.phase == after.phase &&
                                previous.turn?.player == after.turn?.player
                        }.coerceAtMost(2)
                        val penalty = repeated * policy.weight(BotFeature.MATERIAL).coerceAtLeast(0) * 0.12
                        score[actor.ordinal] -= penalty
                        if (styleEnabled) {
                            candidates += Candidate(move, score[actor.ordinal], penalty, profile.preference(transition.state, actor, profileCatalogVersion))
                        }
                        if (bestScore == null || score[actor.ordinal] > bestScore[actor.ordinal]) {
                            bestMove = move
                            firstBestMove = move
                            bestScore = score
                            bestPenalty = penalty
                        } else if (score[actor.ordinal] == bestScore[actor.ordinal]) {
                            if (searchModel == BotSearchModel.PARANOID &&
                                rootMoves.indexOf(move) < rootMoves.indexOf(firstBestMove)
                            ) firstBestMove = move
                            if (preference(move) < preference(checkNotNull(bestMove)) ||
                                (searchModel == BotSearchModel.PARANOID && preference(move) == preference(checkNotNull(bestMove)) &&
                                    rootMoves.indexOf(move) < rootMoves.indexOf(bestMove))
                            ) {
                                bestMove = move
                                bestPenalty = penalty
                            }
                        }
                    }
                    failure?.let { return BoundedBotResult.Failed(it) }
                    if (interruption == Interruption.TACTICAL_LIMIT) {
                        // Discard the entire attempt; deeper checks may settle this frontier.
                        lastAttemptCapped = true
                        interruption = null
                        if (!checkStop()) break@iterations
                        continue@iterations
                    }
                    if (!checkStop()) break@iterations
                    if (styleEnabled) {
                        val nearby = candidates.filter { it.score >= checkNotNull(bestScore)[actor.ordinal] - closeThreshold }
                        val withoutBook = checkNotNull(nearby.maxWithOrNull(
                            compareBy<Candidate>({ it.style }, { it.score }, { -rootMoves.indexOf(it.move) }),
                        ))
                        val selected = checkNotNull(nearby.maxWithOrNull(
                            compareBy<Candidate>({ it.style }, { it.score }, { -preference(it.move) }, { -rootMoves.indexOf(it.move) }),
                        ))
                        bestMove = selected.move
                        firstBestMove = withoutBook.move
                        bestPenalty = selected.penalty
                    }
                    chosen = bestMove?.intent()
                        ?: return BoundedBotResult.Failed(BotFailureReason.NO_CANDIDATE)
                    completedDepth = depth
                    completedHorizon = horizon
                    lastAttemptCapped = false
                    openingInfluenced = bestMove != firstBestMove
                    chosenPenalty = bestPenalty
                    onCompletedDepth(BoundedBotResult.Move(
                        intent = checkNotNull(chosen),
                        source = BotMoveSource.COMPLETED_DEPTH,
                        reason = BotStopReason.DEPTH_COMPLETE,
                        stats = stats(),
                        openingBook = BotOpeningDiagnostics(openingBookVersion, openingInfluenced),
                        repetitionPenalty = chosenPenalty,
                    ))
                }
            }

            val reason = when (interruption) {
                Interruption.TIMED_OUT -> BotStopReason.TIMEOUT
                Interruption.NODE_LIMIT -> BotStopReason.NODE_LIMIT
                Interruption.EVALUATION_LIMIT -> BotStopReason.EVALUATION_LIMIT
                Interruption.CANCELLED -> return BoundedBotResult.Cancelled(stats())
                Interruption.TACTICAL_LIMIT -> BotStopReason.TACTICAL_LIMIT
                null -> if (lastAttemptCapped) BotStopReason.TACTICAL_LIMIT else BotStopReason.DEPTH_COMPLETE
            }
            return BoundedBotResult.Move(
                intent = chosen ?: fallback,
                source = if (chosen == null) BotMoveSource.LEGAL_FALLBACK else BotMoveSource.COMPLETED_DEPTH,
                reason = reason,
                stats = stats(),
                openingBook = BotOpeningDiagnostics(openingBookVersion, openingInfluenced),
                repetitionPenalty = chosenPenalty,
            )
        }

        private fun preference(move: Move): Int = preferred.indexOf(move.intent()).let {
            if (it < 0) Int.MAX_VALUE else it
        }

        private fun controlledPieces(state: GameState, actor: PlayerId) =
            state.position.pieces.filterValues { state.armies.getValue(it.army).controller == actor }

        private fun evaluateAfter(
            state: GameState,
            depth: Int,
            knownLegalMoves: List<Move>,
            responders: Set<PlayerId>,
            alpha: Double,
            beta: Double,
        ): DoubleArray? {
            if (!checkStop()) return null
            if (state.phase != GamePhase.InProgress) return staticScores(state, knownLegalMoves)
            if (depth == 0) return tacticalFrontier(state, knownLegalMoves, tacticalDepth, responders, true, alpha, beta)
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
            var lower = alpha
            var upper = beta
            val minimizing = searchModel == BotSearchModel.PARANOID && actor != rootActor
            val component = if (searchModel == BotSearchModel.PARANOID) rootActor.ordinal else actor.ordinal
            for (move in orderedMoves(state, moves)) {
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
                    respondersAfter(transition.state, move, responders), lower, upper,
                ) ?: return null
                if (best == null || (if (minimizing) score[component] < best[component] else score[component] > best[component])) best = score
                if (searchModel == BotSearchModel.PARANOID) {
                    if (minimizing) upper = minOf(upper, score[component]) else lower = maxOf(lower, score[component])
                    // Strict bounds preserve exact root ties, including repertoire preferences.
                    if (lower > upper) break
                }
            }
            return best
        }

        private fun tacticalFrontier(
            state: GameState,
            knownLegalMoves: List<Move>,
            remaining: Int,
            responders: Set<PlayerId>,
            allowNewExchange: Boolean,
            alpha: Double,
            beta: Double,
        ): DoubleArray? {
            if (!checkStop()) return null
            if (state.phase != GamePhase.InProgress || tacticalDepth == 0) {
                return staticScores(state, knownLegalMoves)
            }
            val activeCheck = PlayerId.entries.any { player ->
                state.participants.getValue(player).status == ParticipantStatus.Active &&
                    LegalMoveGenerator.isKingInCheck(state, player)
            }
            if (!checkStop()) return null
            if (remaining == 0) {
                if (activeCheck) {
                    interruption = Interruption.TACTICAL_LIMIT
                    return null
                }
                return staticScores(state, knownLegalMoves)
            }
            val newExchange = currentHorizon == BotSearchHorizon.EXCHANGES && allowNewExchange && knownLegalMoves.any {
                it.capturedPieceId != null || it.promotion != null
            }
            if (!activeCheck && responders.isEmpty() && !newExchange) return staticScores(state, knownLegalMoves)
            if (!enterNode()) return null
            val actor = state.turn?.player ?: run {
                failure = BotFailureReason.INVALID_STATE
                return null
            }
            val moves = knownLegalMoves.ifEmpty { LegalMoveGenerator.legalMoves(state, actor) }
            if (moves.isEmpty()) {
                failure = BotFailureReason.INVALID_STATE
                return null
            }
            var best: DoubleArray? = null
            // Every intermediate actor makes a real legal move; there is no hypothetical pass.
            var lower = alpha
            var upper = beta
            val minimizing = searchModel == BotSearchModel.PARANOID && actor != rootActor
            val component = if (searchModel == BotSearchModel.PARANOID) rootActor.ordinal else actor.ordinal
            for (move in orderedMoves(state, moves)) {
                if (!checkStop()) return null
                val transition = GameReducer.reduceGeneratedLegalMove(state, move) ?: run {
                    failure = BotFailureReason.GENERATED_MOVE_REJECTED
                    return null
                }
                val score = tacticalFrontier(
                    transition.state, transition.nextLegalMoves, remaining - 1,
                    respondersAfter(transition.state, move, responders),
                    false, lower, upper,
                ) ?: return null
                if (best == null || (if (minimizing) score[component] < best[component] else score[component] > best[component])) best = score
                if (searchModel == BotSearchModel.PARANOID) {
                    if (minimizing) upper = minOf(upper, score[component]) else lower = maxOf(lower, score[component])
                    // Strict bounds preserve exact root ties, including repertoire preferences.
                    if (lower > upper) break
                }
            }
            return best
        }

        private fun orderedMoves(state: GameState, moves: List<Move>): List<Move> =
            if (searchModel == BotSearchModel.MAX_N) moves else moves.sortedByDescending { move ->
                val captured = move.capturedPieceId?.let { state.position.pieces.getValue(it).type.fallbackValue() } ?: 0
                val promotion = move.promotion?.pieceType?.fallbackValue()?.minus(1) ?: 0
                (captured + promotion) * 100 - state.position.pieces.getValue(move.pieceId).type.fallbackValue()
            }

        private fun respondersAfter(state: GameState, move: Move, pending: Set<PlayerId>): Set<PlayerId> {
            if (currentHorizon != BotSearchHorizon.EXCHANGES) return emptySet()
            val active = PlayerId.entries.filterTo(linkedSetOf()) {
                state.participants.getValue(it).status == ParticipantStatus.Active
            }
            return if (move.capturedPieceId != null || move.promotion != null) {
                active - move.actor
            } else {
                (pending - move.actor).intersect(active)
            }
        }

        private fun staticScores(state: GameState, knownLegalMoves: List<Move>): DoubleArray? {
            if (!checkStop()) return null
            if (leafEvaluations >= maxEvaluations) {
                interruption = Interruption.EVALUATION_LIMIT
                return null
            }
            leafEvaluations++
            val score = evaluation.evaluate(state, policy, knownLegalMoves)
            return score.takeIf { checkStop() }
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
        TACTICAL_LIMIT,
    }
}

private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)

private fun PieceType.fallbackValue(): Int = when (this) {
    PieceType.PAWN -> 1
    PieceType.KNIGHT, PieceType.BISHOP -> 3
    PieceType.ROOK -> 5
    PieceType.QUEEN -> 9
    PieceType.KING -> 0
}
