package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId

/** A bounded, typed strategy rule. Rules only tune position evaluation. */
data class BotRule(
    val feature: BotFeature,
    val weight: Int,
    val enabled: Boolean = true,
)

enum class BotFeature {
    MATERIAL,
    KING_SAFETY,
    CHECK_PRESSURE,
    MOBILITY,
}

data class BotPolicy(
    val rules: List<BotRule> = DEFAULT_BOT_RULES,
) {
    init {
        require(rules.map(BotRule::feature).distinct().size == rules.size) {
            "A policy may contain only one rule for each feature"
        }
        require(rules.all { it.weight in -MAX_RULE_WEIGHT..MAX_RULE_WEIGHT }) {
            "Bot rule weights must be between -$MAX_RULE_WEIGHT and $MAX_RULE_WEIGHT"
        }
    }

    fun weight(feature: BotFeature): Int =
        rules.firstOrNull { it.feature == feature && it.enabled }?.weight ?: 0

    companion object {
        const val MAX_RULE_WEIGHT = 10_000
        val DEFAULT = BotPolicy()
    }
}

val DEFAULT_BOT_RULES = listOf(
    BotRule(BotFeature.MATERIAL, weight = 100),
    BotRule(BotFeature.KING_SAFETY, weight = 280),
    BotRule(BotFeature.CHECK_PRESSURE, weight = 40),
    BotRule(BotFeature.MOBILITY, weight = 3),
)

data class BotTrainingSample(
    val player: PlayerId,
    val featureDeltas: Map<BotFeature, Int>,
) {
    companion object {
        fun fromTransition(before: GameState, after: GameState, player: PlayerId): BotTrainingSample =
            fromTransition(before, after, player, beforeLegalMoves = null)

        internal fun fromTransition(
            before: GameState,
            after: GameState,
            player: PlayerId,
            beforeLegalMoves: List<Move>?,
        ): BotTrainingSample {
            val beforeFeatures = featureValues(before, player, beforeLegalMoves)
            val afterFeatures = featureValues(after, player)
            return BotTrainingSample(
                player = player,
                featureDeltas = BotFeature.entries.associateWith { feature ->
                    (afterFeatures.getValue(feature) - beforeFeatures.getValue(feature))
                        .coerceIn(-FEATURE_DELTA_LIMIT, FEATURE_DELTA_LIMIT)
                },
            )
        }
    }
}

data class BotMoveDecision(
    val intent: MoveIntent,
    val trainingSample: BotTrainingSample,
)

internal data class BotSearchStats(
    val expandedNodes: Int,
    val leafEvaluations: Int,
)

internal data class BotDecisionSearchResult(
    val decision: BotMoveDecision,
    val stats: BotSearchStats,
)

private data class RootChoice(
    val move: Move,
    val state: GameState,
    val score: DoubleArray,
)

/**
 * Selects a move using MaxN: at each node, the player to move maximizes their
 * own component of the three-player evaluation vector.
 *
 * This implementation deliberately reuses the authoritative reducer and move
 * generator. A policy can change the bot's preferences but cannot make an
 * illegal move legal. [maxNodes] caps expanded search positions and
 * [maxEvaluations] caps scored positions; both limits are shared across root
 * moves so one early candidate cannot consume the whole search budget.
 */
class MaxNBot(
    private val maxDepth: Int = 2,
    private val maxNodes: Int = 128,
    private val maxEvaluations: Int = 128,
) {
    init {
        require(maxDepth > 0)
        require(maxNodes > 0)
        require(maxEvaluations > 0)
    }

    fun chooseMove(state: GameState, policy: BotPolicy = BotPolicy.DEFAULT): MoveIntent? {
        return chooseDecision(state, policy)?.intent
    }

    fun chooseDecision(state: GameState, policy: BotPolicy = BotPolicy.DEFAULT): BotMoveDecision? =
        chooseDecisionWithStats(state, policy)?.decision

    internal fun chooseDecisionWithStats(
        state: GameState,
        policy: BotPolicy = BotPolicy.DEFAULT,
    ): BotDecisionSearchResult? {
        val actor = state.turn?.player ?: return null
        if (state.phase != GamePhase.InProgress) return null
        val moves = LegalMoveGenerator.legalMoves(state, actor)
        if (moves.isEmpty()) return null

        val search = Search(
            policy = policy,
            maxDepth = maxDepth,
            maxNodes = maxNodes,
            maxEvaluations = maxEvaluations,
        )
        val choice = search.chooseRoot(state, actor, moves) ?: return null
        val intent = MoveIntent(choice.move.actor, choice.move.from, choice.move.to, choice.move.promotion)
        return BotDecisionSearchResult(
            decision = BotMoveDecision(
                intent = intent,
                trainingSample = BotTrainingSample.fromTransition(
                    state,
                    choice.state,
                    actor,
                    beforeLegalMoves = moves,
                ),
            ),
            stats = search.stats(),
        )
    }

    private class Search(
        private val policy: BotPolicy,
        private val maxDepth: Int,
        private val maxNodes: Int,
        private val maxEvaluations: Int,
    ) {
        private var expandedNodes = 0
        private var leafEvaluations = 0

        fun stats(): BotSearchStats = BotSearchStats(
            expandedNodes = expandedNodes,
            leafEvaluations = leafEvaluations,
        )

        fun chooseRoot(state: GameState, actor: PlayerId, moves: List<Move>): RootChoice? {
            expandedNodes++
            var best: RootChoice? = null
            for ((index, move) in moves.withIndex()) {
                val remainingMoves = moves.size - index
                val remainingEvaluations = maxEvaluations - leafEvaluations
                if (remainingEvaluations <= 0) break
                val candidateLimit = leafEvaluations +
                        (remainingEvaluations + remainingMoves - 1) / remainingMoves
                val remainingNodes = maxNodes - expandedNodes
                val candidateNodeLimit = expandedNodes +
                        (remainingNodes + remainingMoves - 1).coerceAtLeast(0) / remainingMoves
                val transition = GameReducer.reduceGeneratedLegalMove(state, move) ?: continue
                val score = evaluateAfter(
                    state = transition.state,
                    depth = maxDepth - 1,
                    knownLegalMoves = transition.nextLegalMoves,
                    evaluationLimit = candidateLimit,
                    nodeLimit = candidateNodeLimit,
                ) ?: continue
                val currentBest = best
                if (currentBest == null || score[actor.ordinal] > currentBest.score[actor.ordinal]) {
                    best = RootChoice(move, transition.state, score)
                }
            }
            return best
        }

        private fun evaluateAfter(
            state: GameState,
            depth: Int,
            knownLegalMoves: List<Move>,
            evaluationLimit: Int,
            nodeLimit: Int,
        ): DoubleArray? {
            if (state.phase != GamePhase.InProgress || depth <= 0 || expandedNodes >= nodeLimit) {
                return evaluateLeaf(state, knownLegalMoves, evaluationLimit)
            }
            if (leafEvaluations >= evaluationLimit) return null
            expandedNodes++
            val actor = state.turn?.player ?: return evaluateLeaf(state, knownLegalMoves, evaluationLimit)
            val moves = if (knownLegalMoves.isNotEmpty()) {
                knownLegalMoves
            } else {
                LegalMoveGenerator.legalMoves(state, actor)
            }
            if (moves.isEmpty()) return evaluateLeaf(state, knownLegalMoves, evaluationLimit)

            var best: DoubleArray? = null
            for (move in moves) {
                if (leafEvaluations >= evaluationLimit) break
                val transition = GameReducer.reduceGeneratedLegalMove(state, move) ?: continue
                val score = evaluateAfter(
                    state = transition.state,
                    depth = depth - 1,
                    knownLegalMoves = transition.nextLegalMoves,
                    evaluationLimit = evaluationLimit,
                    nodeLimit = nodeLimit,
                ) ?: continue
                if (best == null || score[actor.ordinal] > best[actor.ordinal]) best = score
            }
            return best ?: evaluateLeaf(state, moves, evaluationLimit)
        }

        private fun evaluateLeaf(
            state: GameState,
            knownLegalMoves: List<Move>,
            evaluationLimit: Int,
        ): DoubleArray? {
            if (leafEvaluations >= minOf(maxEvaluations, evaluationLimit)) return null
            leafEvaluations++
            return evaluate(state, policy, knownLegalMoves)
        }
    }
}

/** Updates typed rule weights from per-move feature changes and final placements. */
object BotPolicyLearner {
    fun learn(
        policy: BotPolicy,
        outcome: GameOutcome,
        samples: List<BotTrainingSample>,
        learningRate: Int = DEFAULT_LEARNING_RATE,
    ): BotPolicy {
        require(learningRate in 0..MAX_LEARNING_RATE)
        if (samples.isEmpty() || learningRate == 0) return policy
        val utility = outcomeUtilities(outcome)
        val grouped = samples.groupBy(BotTrainingSample::player)
        val rulesByFeature = policy.rules.associateBy(BotRule::feature).toMutableMap()
        BotFeature.entries.forEach { feature ->
            val gradient = PlayerId.entries.sumOf { player ->
                val playerSamples = grouped[player].orEmpty()
                if (playerSamples.isEmpty()) return@sumOf 0.0
                val meanDelta = playerSamples.sumOf { it.featureDeltas[feature] ?: 0 }.toDouble() /
                        playerSamples.size
                utility.getValue(player) * meanDelta
            }
            if (gradient == 0.0) return@forEach
            val current = rulesByFeature[feature]
                ?: BotRule(feature, weight = 0, enabled = true)
            val adjustment = (gradient * learningRate / PlayerId.entries.size).toInt()
            rulesByFeature[feature] = current.copy(
                weight = (current.weight + adjustment)
                    .coerceIn(-BotPolicy.MAX_RULE_WEIGHT, BotPolicy.MAX_RULE_WEIGHT),
            )
        }
        return BotPolicy(BotFeature.entries.mapNotNull(rulesByFeature::get))
    }

    private fun outcomeUtilities(outcome: GameOutcome): Map<PlayerId, Double> = when (outcome) {
        is GameOutcome.Ranked -> mapOf(
            outcome.first to 1.0,
            outcome.second to 0.0,
            outcome.third to -1.0,
        )

        is GameOutcome.ThreeWayDraw -> PlayerId.entries.associateWith { 0.0 }

        is GameOutcome.TwoWayDraw -> mapOf(
            outcome.first to 0.5,
            outcome.second to 0.5,
            outcome.third to -1.0,
        )
    }

    private const val DEFAULT_LEARNING_RATE = 2
    private const val MAX_LEARNING_RATE = 100
}

internal const val FEATURE_DELTA_LIMIT = 100

private val TRAINING_PIECE_VALUES = mapOf(
    PieceType.PAWN to 10,
    PieceType.KNIGHT to 30,
    PieceType.BISHOP to 32,
    PieceType.ROOK to 50,
    PieceType.QUEEN to 90,
    PieceType.KING to 0,
)

private val EVALUATION_PIECE_VALUES = mapOf(
    PieceType.PAWN to 1.0,
    PieceType.KNIGHT to 3.0,
    PieceType.BISHOP to 3.2,
    PieceType.ROOK to 5.0,
    PieceType.QUEEN to 9.0,
    PieceType.KING to 0.0,
)

private fun featureValues(
    state: GameState,
    player: PlayerId,
    knownLegalMoves: List<Move>? = null,
): Map<BotFeature, Int> {
    val material = state.position.pieces.values
        .asSequence()
        .filter { it.type != PieceType.KING && state.armies.getValue(it.army).controller == player }
        .sumOf { TRAINING_PIECE_VALUES.getValue(it.type) }
    val kingSafety = if (
        state.participants.getValue(player).status == ParticipantStatus.Active &&
        LegalMoveGenerator.isKingInCheck(state, player)
    ) -1 else 0
    val checkPressure = PlayerId.entries.asSequence()
        .filter { it != player && state.participants.getValue(it).status == ParticipantStatus.Active }
        .sumOf { target ->
            if (player in LegalMoveGenerator.attackingPlayers(state, target)) 1 else 0
        }
    val mobility = if (state.participants.getValue(player).status == ParticipantStatus.Active) {
        knownLegalMoves?.size ?: LegalMoveGenerator.legalMoves(state, player).size
    } else 0
    return mapOf(
        BotFeature.MATERIAL to material,
        BotFeature.KING_SAFETY to kingSafety,
        BotFeature.CHECK_PRESSURE to checkPressure,
        BotFeature.MOBILITY to mobility,
    )
}

private fun evaluate(
    state: GameState,
    policy: BotPolicy,
    knownLegalMoves: List<Move>? = null,
): DoubleArray {
    val outcome = (state.phase as? GamePhase.Finished)?.outcome
    if (outcome != null) return terminalScores(outcome)

    val scores = DoubleArray(PlayerId.entries.size)
    val materialWeight = policy.weight(BotFeature.MATERIAL).toDouble()
    val kingSafetyWeight = policy.weight(BotFeature.KING_SAFETY).toDouble()
    val checkWeight = policy.weight(BotFeature.CHECK_PRESSURE).toDouble()
    val mobilityWeight = policy.weight(BotFeature.MOBILITY).toDouble()
    state.position.pieces.values.forEach { piece ->
        val controller = state.armies.getValue(piece.army).controller
        if (piece.type != PieceType.KING) {
            scores[controller.ordinal] += EVALUATION_PIECE_VALUES.getValue(piece.type) * materialWeight
        }
    }
    PlayerId.entries.forEach { player ->
        when (state.participants.getValue(player).status) {
            ParticipantStatus.Active -> Unit
            is ParticipantStatus.Stalemated -> scores[player.ordinal] -= 2_000.0
            is ParticipantStatus.Checkmated -> scores[player.ordinal] -= 10_000.0
        }
        if (LegalMoveGenerator.isKingInCheck(state, player)) {
            scores[player.ordinal] -= kingSafetyWeight
            LegalMoveGenerator.attackingPlayers(state, player).forEach { attacker ->
                scores[attacker.ordinal] += checkWeight
            }
        }
    }
    if (mobilityWeight != 0.0) {
        PlayerId.entries.forEach { player ->
            if (state.participants.getValue(player).status == ParticipantStatus.Active) {
                val moveCount = if (player == state.turn?.player && knownLegalMoves != null) {
                    knownLegalMoves.size
                } else {
                    LegalMoveGenerator.legalMoves(state, player).size
                }
                scores[player.ordinal] += moveCount * mobilityWeight
            }
        }
    }
    return scores
}

private fun terminalScores(outcome: GameOutcome): DoubleArray = when (outcome) {
    is GameOutcome.Ranked -> DoubleArray(PlayerId.entries.size) { index ->
        val player = PlayerId.entries[index]
        when {
            player == outcome.first -> 1_000_000.0
            player == outcome.second -> 0.0
            else -> -1_000_000.0
        }
    }

    is GameOutcome.ThreeWayDraw -> DoubleArray(PlayerId.entries.size)

    is GameOutcome.TwoWayDraw -> DoubleArray(PlayerId.entries.size) { index ->
        val player = PlayerId.entries[index]
        when {
            player == outcome.first || player == outcome.second -> 500_000.0
            else -> -1_000_000.0
        }
    }
}
