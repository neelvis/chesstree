package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.ThreePlayerBoardTopology

/** Evaluation candidates share the same legal search and terminal placement scores. */
enum class BotEvaluationMode(val version: Int) {
    CONTROL(version = 1),
    PAWN_PROGRESS(version = 2),
    POSITIONAL(version = 3),
    ;

    internal fun evaluate(
        state: GameState,
        policy: BotPolicy,
        knownLegalMoves: List<Move>? = null,
    ): DoubleArray {
        val legalByPlayer = if (this == POSITIONAL && state.phase == GamePhase.InProgress &&
            policy.weight(BotFeature.MOBILITY) > 0
        ) {
            PlayerId.entries.filter { state.participants.getValue(it).status == ParticipantStatus.Active }
                .associateWith { player ->
                    if (player == state.turn?.player && knownLegalMoves != null) knownLegalMoves
                    else LegalMoveGenerator.legalMoves(state, player)
                }
        } else null
        val scores = com.chesstree.game.domain.bot.evaluate(state, policy, knownLegalMoves, legalByPlayer)
        if (this == CONTROL || state.phase is GamePhase.Finished) return scores
        if (this == POSITIONAL) return positionalScores(state, policy, legalByPlayer ?: emptyMap(), scores)
        val materialWeight = policy.weight(BotFeature.MATERIAL).toDouble()
        if (materialWeight <= 0.0) return scores

        val controlledMaterial = state.position.pieces.values
            .filter { it.type != PieceType.KING }
            .groupBy { state.armies.getValue(it.army).controller }
        PlayerId.entries.forEach { player ->
            if (state.participants.getValue(player).status != ParticipantStatus.Active) return@forEach
            val pieces = controlledMaterial[player].orEmpty()
            if (pieces.isEmpty() || pieces.any { it.type != PieceType.PAWN }) return@forEach
            val progressBonus = pieces.sumOf { pawn ->
                val maximum = maximumForwardSteps.getValue(pawn.army)
                val remaining = remainingForwardSteps(pawn.coordinate, pawn.army)
                val progress = (maximum - remaining).toDouble() / maximum
                materialWeight * PAWN_PROGRESS_FRACTION * progress.coerceIn(0.0, 1.0)
            }
            scores[player.ordinal] += progressBonus.coerceAtMost(materialWeight * TOTAL_PROGRESS_FRACTION)
        }
        return scores
    }
}

private const val PAWN_PROGRESS_FRACTION = 0.9
// Losing the whole bonus must cost less than the material gain from pawn-to-knight promotion.
private const val TOTAL_PROGRESS_FRACTION = 1.9

private val maximumForwardSteps: Map<ArmyColor, Int> by lazy {
    ArmyColor.entries.associateWith { army ->
        ThreePlayerBoardTopology.coordinates.maxOf { coordinate ->
            remainingForwardSteps(coordinate, army)
        }.also { maximum -> check(maximum > 0) }
    }
}

private fun remainingForwardSteps(coordinate: BoardCoordinate, army: ArmyColor): Int =
    MovementDirections.forPiece(PieceType.PAWN, coordinate, army)
        .singleOrNull { it.kind == DirectionKind.MOVE }
        ?.route?.let { it.size - 1 } ?: 0
