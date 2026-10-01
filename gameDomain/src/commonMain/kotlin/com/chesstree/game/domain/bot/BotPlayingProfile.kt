package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.ThreePlayerBoardTopology

enum class BotPlayingProfile { UNIVERSAL, ATTACKING, POSITIONAL, ENDGAME }

/** A style preference only among the completed search's close alternatives. */
internal fun BotPlayingProfile.preference(state: GameState, actor: PlayerId, catalogVersion: Int = 1): Double {
    if (this == BotPlayingProfile.UNIVERSAL && catalogVersion == 1) return 0.0
    val active = state.position.pieces.values.filter {
        state.participants.getValue(state.armies.getValue(it.army).controller).status == ParticipantStatus.Active
    }
    val own = active.filter { state.armies.getValue(it.army).controller == actor }
    val enemies = active.filter { state.armies.getValue(it.army).controller != actor }
    val attacks = active.associate { it.id to LegalMoveGenerator.attackCoordinates(state.position, it) }
    val enemyAttacks = enemies.flatMapTo(hashSetOf()) { attacks.getValue(it.id) }
    val ownAttacks = own.flatMapTo(hashSetOf()) { attacks.getValue(it.id) }
    return when (this) {
        BotPlayingProfile.UNIVERSAL -> {
            val late = (1.0 - active.sumOf { if (it.type == PieceType.PAWN) 0.0 else it.type.profileMaterial() } / 94.2)
                .coerceIn(0.0, 1.0)
            val occupied = state.position.pieces.values.mapTo(hashSetOf()) { it.coordinate }
            val progress = own.filter { it.type == PieceType.PAWN }.sumOf { pawn ->
                val route = MovementDirections.forPiece(pawn.type, pawn.coordinate, pawn.army)
                    .singleOrNull { it.kind == DirectionKind.MOVE }?.route.orEmpty()
                val remaining = (route.size - 1).coerceAtLeast(0)
                val advance = 1.0 - remaining.toDouble() / profilePawnSteps.getValue(pawn.army)
                advance * (if (pawn.coordinate in enemyAttacks) 0.25 else 1.0) *
                    (if (route.getOrNull(1) in occupied) 0.35 else 1.0)
            }
            val developed = own.count { (it.type == PieceType.KNIGHT || it.type == PieceType.BISHOP) &&
                it.hasMoved && it.coordinate !in enemyAttacks }
            val supported = own.count { it.type == PieceType.PAWN && it.coordinate in ownAttacks }
            val exposed = own.filter { it.type != PieceType.KING && it.coordinate in enemyAttacks }
                .sumOf { it.type.profileMaterial() }
            late * (progress + supported * 0.1) + (1.0 - late) * developed * 0.05 - exposed * 0.1
        }
        BotPlayingProfile.ATTACKING -> {
            val safePressure = own.filter { it.type != PieceType.KING && it.coordinate !in enemyAttacks }
                .flatMapTo(hashSetOf()) { attacks.getValue(it.id) }
            enemies.filter { it.type == PieceType.KING }.sumOf { king ->
                MovementDirections.forPiece(king.type, king.coordinate, king.army)
                    .count { it.target in safePressure }.toDouble()
            }
        }
        BotPlayingProfile.POSITIONAL -> {
            val exposed = own.filter { it.type != PieceType.KING && it.coordinate in enemyAttacks }
                .sumOf { it.type.profileMaterial() }
            val supportedPawns = own.count { it.type == PieceType.PAWN && it.coordinate in ownAttacks }
            supportedPawns - exposed
        }
        BotPlayingProfile.ENDGAME -> {
            val late = (1.0 - active.sumOf { if (it.type == PieceType.PAWN) 0.0 else it.type.profileMaterial() } / 94.2)
                .coerceIn(0.0, 1.0)
            val progress = own.filter { it.type == PieceType.PAWN }.sumOf { pawn ->
                val remaining = MovementDirections.forPiece(pawn.type, pawn.coordinate, pawn.army)
                    .singleOrNull { it.kind == DirectionKind.MOVE }?.route?.size?.minus(1) ?: 0
                1.0 - remaining.toDouble() / profilePawnSteps.getValue(pawn.army)
            }
            late * (progress + own.count { it.type == PieceType.PAWN && it.coordinate in ownAttacks } * 0.1)
        }
    }
}

private val profilePawnSteps by lazy {
    ArmyColor.entries.associateWith { army ->
        ThreePlayerBoardTopology.coordinates.maxOf { coordinate ->
            MovementDirections.forPiece(PieceType.PAWN, coordinate, army)
                .singleOrNull { it.kind == DirectionKind.MOVE }?.route?.size?.minus(1) ?: 0
        }.coerceAtLeast(1)
    }
}

private fun PieceType.profileMaterial(): Double = when (this) {
    PieceType.KING -> 0.0
    PieceType.PAWN -> 1.0
    PieceType.KNIGHT -> 3.0
    PieceType.BISHOP -> 3.2
    PieceType.ROOK -> 5.0
    PieceType.QUEEN -> 9.0
}
