package com.chesstree.game.presentation.board

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.scenario.StandardGame

data class BoardPiece(
    val id: String,
    val type: PieceType,
    val army: ArmyColor,
    val cellId: BoardCellId,
    val bodyArmy: ArmyColor = army,
)

enum class PieceSet {
    STANDARD,
    FAIRY,
}

enum class MoveHintKind {
    MOVE,
    CAPTURE,
}

data class MoveHint(
    val target: BoardCellId,
    val kind: MoveHintKind,
    val route: List<BoardCellId> = emptyList(),
    val attackedPieceId: String? = null,
    val showLandingMarker: Boolean = false,
)

data class BoardTrophy(
    val id: String,
    val type: PieceType,
    val army: ArmyColor,
    val bodyArmy: ArmyColor,
    val capturedByArmy: ArmyColor,
)

fun initialBoardPieces(): List<BoardPiece> = StandardGame.pieces.map { piece ->
    BoardPiece(piece.id, piece.type, piece.army, piece.coordinate)
}

fun movementHintsFor(piece: BoardPiece): List<MoveHint> =
    MovementDirections.forPiece(
        type = piece.type,
        origin = piece.cellId,
        army = piece.army,
    ).map { direction ->
        MoveHint(
            target = direction.target,
            kind = when (direction.kind) {
                DirectionKind.MOVE -> MoveHintKind.MOVE
                DirectionKind.CAPTURE -> MoveHintKind.CAPTURE
            },
            route = direction.route,
        )
    }

fun legalMoveHintsFor(
    state: GameState,
    pieceId: PieceId,
): List<MoveHint> = LegalMoveGenerator.legalMoves(state, pieceId)
    .distinctBy { move -> move.to }
    .map { move ->
        MoveHint(
            target = move.to,
            kind = when (move.type) {
                MoveType.CAPTURE,
                MoveType.EN_PASSANT,
                    -> MoveHintKind.CAPTURE

                MoveType.QUIET,
                MoveType.CASTLING,
                    -> MoveHintKind.MOVE
            },
            attackedPieceId = move.capturedPieceId?.value,
            showLandingMarker = move.type == MoveType.EN_PASSANT,
        )
    }

fun educationalMoveHintsFor(
    state: GameState,
    pieceId: PieceId,
): List<MoveHint> {
    val piece = state.position.pieces[pieceId] ?: return emptyList()
    val boardPiece = BoardPiece(
        id = piece.id.value,
        type = piece.type,
        army = piece.army,
        cellId = piece.coordinate,
    )
    val controller = state.armies.getValue(piece.army).controller
    return movementHintsFor(boardPiece).map { hint ->
        val candidateCoordinates = when (piece.type) {
            PieceType.KNIGHT -> listOf(hint.target)
            PieceType.PAWN -> if (hint.kind == MoveHintKind.CAPTURE) {
                listOf(hint.target)
            } else {
                emptyList()
            }
            else -> hint.route.drop(1)
        }
        val firstOccupiedPiece = candidateCoordinates.firstNotNullOfOrNull { coordinate ->
            state.position.pieces.values.firstOrNull { candidate ->
                candidate.coordinate == coordinate
            }
        }
        val attackedPiece = firstOccupiedPiece?.takeIf { candidate ->
            state.armies.getValue(candidate.army).controller != controller
        }
        hint.copy(attackedPieceId = attackedPiece?.id?.value)
    }
}
