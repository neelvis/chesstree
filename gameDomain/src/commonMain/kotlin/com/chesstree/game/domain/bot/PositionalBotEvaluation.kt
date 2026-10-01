package com.chesstree.game.domain.bot

import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame

/** Small position preferences; terminal placement and legal search remain authoritative. */
internal fun positionalScores(
    state: GameState,
    policy: BotPolicy,
    legalByPlayer: Map<PlayerId, List<Move>>,
    scores: DoubleArray,
): DoubleArray {
    val materialWeight = policy.weight(BotFeature.MATERIAL).coerceAtLeast(0).toDouble()
    val mobilityWeight = policy.weight(BotFeature.MOBILITY).coerceAtLeast(0).toDouble()
    val kingSafetyWeight = policy.weight(BotFeature.KING_SAFETY).coerceAtLeast(0).toDouble()
    if (materialWeight == 0.0 && mobilityWeight == 0.0 && kingSafetyWeight == 0.0) return scores
    val pieces = state.position.pieces.values.toList()
    val occupancy = pieces.associateBy { it.coordinate }
    val controllers = pieces.associate { it.id to state.armies.getValue(it.army).controller }
    val activePieces = pieces.filter {
        state.participants.getValue(controllers.getValue(it.id)).status == ParticipantStatus.Active
    }
    // Reuse the rules engine's attack geometry, including occupancy and original pawn armies.
    val attacks = activePieces.associate { it.id to LegalMoveGenerator.attackCoordinates(state.position, it) }
    val byController = activePieces.groupBy { controllers.getValue(it.id) }
    val phase = PlayerId.entries.associateWith { player ->
        (byController[player].orEmpty().sumOf { if (it.type == PieceType.KING) 0.0 else nonPawnValue(it.type) } /
            INITIAL_NON_PAWN_MATERIAL).coerceIn(0.0, 1.0)
    }

    PlayerId.entries.forEach { player ->
        if (state.participants.getValue(player).status != ParticipantStatus.Active) return@forEach
        val controlled = byController[player].orEmpty()
        val opponents = PlayerId.entries.filter {
            it != player && state.participants.getValue(it).status == ParticipantStatus.Active
        }
        val enemyAttacks = opponents.associateWith { opponent ->
            byController[opponent].orEmpty().flatMapTo(hashSetOf()) { attacks.getValue(it.id) }
        }
        val threatened = enemyAttacks.values.flatten().toHashSet()
        val ownPhase = phase.getValue(player)
        val enemyPhase = opponents.maxOfOrNull { phase.getValue(it) } ?: 0.0
        if (opponents.isNotEmpty()) {
            val opponentMaterial = opponents.sumOf { opponent ->
                byController[opponent].orEmpty().sumOf { materialValue(it.type) }
            }
            // Keep the coefficient stable when an opponent is eliminated or stalemated.
            scores[player.ordinal] -= materialWeight * 0.25 * opponentMaterial
        }
        val undevelopedMinors = controlled.count {
            (it.type == PieceType.KNIGHT || it.type == PieceType.BISHOP) &&
                it.coordinate in homeBackRanks.getValue(it.army)
        }.coerceAtMost(4) / 4.0
        var pawnBonus = 0.0

        controlled.forEach { piece ->
            val defenders = controlled.filter { it.id != piece.id && piece.coordinate in attacks.getValue(it.id) }
            val exposed = piece.coordinate in threatened
            if (piece.type != PieceType.KING && exposed) {
                val fraction = if (defenders.isEmpty()) 0.45 else 0.12
                scores[player.ordinal] -= materialValue(piece.type) * materialWeight * fraction
            }
            when (piece.type) {
                PieceType.KNIGHT, PieceType.BISHOP -> {
                    val safeActivity = attacks.getValue(piece.id).count { target ->
                        target !in threatened && occupancy[target]?.let {
                            controllers.getValue(it.id) == player
                        } != true
                    }.coerceAtMost(8)
                    scores[player.ordinal] += safeActivity * mobilityWeight * 0.5
                    if (!exposed && piece.coordinate !in homeBackRanks.getValue(piece.army)) {
                        scores[player.ordinal] += materialWeight * 0.14 * ownPhase
                    }
                }
                PieceType.PAWN -> {
                    val route = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
                        .singleOrNull { it.kind == DirectionKind.MOVE }?.route.orEmpty()
                    val remaining = (route.size - 1).coerceAtLeast(0)
                    val maximum = maximumPawnSteps.getValue(piece.army)
                    val progress = ((maximum - remaining).toDouble() / maximum).coerceIn(0.0, 1.0)
                    val blockedFactor = if (route.getOrNull(1)?.let(occupancy::containsKey) == true) 0.35 else 1.0
                    val exposureFactor = if (exposed) 0.25 else 1.0
                    pawnBonus += materialWeight * (0.9 - 0.6 * ownPhase) * progress * blockedFactor * exposureFactor
                    if (defenders.any { it.type == PieceType.PAWN }) {
                        scores[player.ordinal] += materialWeight * 0.06
                    }
                }
                PieceType.KING -> {
                    val ring = MovementDirections.forPiece(piece.type, piece.coordinate, piece.army)
                        .map { it.target }
                    val ringPressure = enemyAttacks.values.sumOf { attack -> ring.count { it in attack } }
                    val shield = ring.count { target ->
                        occupancy[target]?.let { it.type == PieceType.PAWN && controllers.getValue(it.id) == player } == true
                    }
                    scores[player.ordinal] += kingSafetyWeight * enemyPhase * (shield * 0.01 - ringPressure * 0.02)
                }
                PieceType.QUEEN -> {
                    if (piece.coordinate !in homeBackRanks.getValue(piece.army)) {
                        scores[player.ordinal] -= materialWeight * 0.25 * ownPhase * undevelopedMinors
                    }
                }
                PieceType.ROOK -> Unit
            }
        }
        scores[player.ordinal] += pawnBonus.coerceAtMost(materialWeight * 1.9)
        // Ordinary king mobility becomes useful as opposing major/minor material disappears.
        if (enemyPhase > 0.0 && mobilityWeight > 0.0) {
            val legal = legalByPlayer.getValue(player)
            val kingMoves = legal.count { state.position.pieces.getValue(it.pieceId).type == PieceType.KING }
            scores[player.ordinal] -= kingMoves * mobilityWeight * enemyPhase
        }
    }
    return scores
}

private const val INITIAL_NON_PAWN_MATERIAL = 31.4

private val homeBackRanks by lazy {
    StandardGame.pieces.filter { it.type != PieceType.PAWN }.groupBy { it.army }
        .mapValues { (_, pieces) -> pieces.mapTo(hashSetOf()) { it.coordinate } }
}

private val maximumPawnSteps by lazy {
    homeBackRanks.keys.associateWith { army ->
        com.chesstree.game.domain.ThreePlayerBoardTopology.coordinates.maxOf { coordinate ->
            MovementDirections.forPiece(PieceType.PAWN, coordinate, army)
                .singleOrNull { it.kind == DirectionKind.MOVE }?.route?.let { it.size - 1 } ?: 0
        }.coerceAtLeast(1)
    }
}

private fun nonPawnValue(type: PieceType): Double = if (type == PieceType.PAWN) 0.0 else materialValue(type)

private fun materialValue(type: PieceType): Double = when (type) {
    PieceType.PAWN -> 1.0
    PieceType.KNIGHT -> 3.0
    PieceType.BISHOP -> 3.2
    PieceType.ROOK -> 5.0
    PieceType.QUEEN -> 9.0
    PieceType.KING -> 0.0
}
