package com.chesstree.game.domain.session

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardFile
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.MoveType
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.ThreePlayerBoardTopology
import com.chesstree.game.domain.scenario.GameScenario

data class GameLogRestoration(
    val session: GameSession,
    val restoredMoves: Int,
    val totalMoves: Int,
)

/** Portable three-player extension of standard algebraic notation (SAN). */
object GameLogCodec {
    fun encode(session: GameSession): String = buildString {
        var state = session.scenario.initialState
        session.moves.forEachIndexed { index, intent ->
            val legalMoves = LegalMoveGenerator.legalMoves(state)
            val reduction = GameReducer.reduce(
                state,
                intent,
                finishInsufficientMaterial = false,
            ) as? MoveReduction.Applied ?: return@forEachIndexed
            val piece = state.position.pieces.getValue(reduction.move.pieceId)
            if (isNotEmpty()) appendLine()
            append(index + 1)
            append(": ")
            append(piece.army.logCode)
            append('.')
            append(san(state, reduction.move, legalMoves, reduction))
            state = reduction.state
        }
    }

    fun restore(scenario: GameScenario, contents: String): GameLogRestoration {
        val lines = contents.lineSequence().filter(String::isNotBlank).toList()
        var state = scenario.initialState
        val acceptedMoves = mutableListOf<MoveIntent>()

        lines.forEach { line ->
            val legalMoves = LegalMoveGenerator.legalMoves(state)
            val move = parseLegacyCoordinates(line)?.let { (from, to) ->
                legalMoves.firstOrNull { candidate -> candidate.from == from && candidate.to == to }
            } ?: run {
                val notation = line.substringAfterLast(':').trim().removeArmyPrefix()
                legalMoves.firstOrNull { candidate ->
                    val reduction = reduce(state, candidate) ?: return@firstOrNull false
                    san(state, candidate, legalMoves, reduction).sanKey() == notation.sanKey()
                }
            } ?: return@forEach
            val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
            val reduction = GameReducer.reduce(
                state,
                intent,
                finishInsufficientMaterial = false,
            ) as? MoveReduction.Applied ?: return@forEach
            acceptedMoves += intent
            state = reduction.state
        }

        val session = checkNotNull(GameSession.replay(scenario, acceptedMoves))
        return GameLogRestoration(
            session = session,
            restoredMoves = acceptedMoves.size,
            totalMoves = lines.size,
        )
    }

    private fun san(
        state: GameState,
        move: Move,
        legalMoves: List<Move>,
        reduction: MoveReduction.Applied,
    ): String {
        if (move.type == MoveType.CASTLING) {
            val side = castlingSide(state, move, legalMoves)
            return (if (side == CastlingSide.QUEEN_SIDE) "O-O-O" else "O-O") +
                    checkSuffix(state, move, reduction)
        }

        val piece = state.position.pieces.getValue(move.pieceId)
        val capture = move.type == MoveType.CAPTURE || move.type == MoveType.EN_PASSANT
        return buildString {
            if (piece.type == PieceType.PAWN) {
                if (capture) append(ThreePlayerBoardNotation.square(move.from).file.sanCode)
            } else {
                append(piece.type.sanCode)
                append(disambiguation(state, move, legalMoves))
            }
            if (capture) append('x')
            append(ThreePlayerBoardNotation.square(move.to))
            move.promotion?.let { promotion ->
                append('=')
                append(promotion.pieceType.sanCode)
            }
            append(checkSuffix(state, move, reduction))
        }
    }

    private fun disambiguation(state: GameState, move: Move, legalMoves: List<Move>): String {
        val piece = state.position.pieces.getValue(move.pieceId)
        val alternatives = legalMoves.filter { candidate ->
            candidate.pieceId != move.pieceId && candidate.to == move.to &&
                    state.position.pieces.getValue(candidate.pieceId).type == piece.type
        }
        if (alternatives.isEmpty()) return ""
        val origin = ThreePlayerBoardNotation.square(move.from)
        val alternativeSquares = alternatives.map { ThreePlayerBoardNotation.square(it.from) }
        return when {
            alternativeSquares.none { it.file == origin.file } -> origin.file.sanCode.toString()
            alternativeSquares.none { it.rank == origin.rank } -> origin.rank.toString()
            else -> origin.toString()
        }
    }

    private fun castlingSide(state: GameState, move: Move, legalMoves: List<Move>): CastlingSide {
        val piece = state.position.pieces.getValue(move.pieceId)
        val rookId = checkNotNull(move.rookDisplacement).pieceId
        state.position.castlingRights
            .filter { it.army == piece.army }
            .firstOrNull { right ->
                resolveCastlingRookId(
                    state,
                    piece.army,
                    right.side,
                    right.rookId
                ) == rookId
            }
            ?.let { return it.side }
        val castles = legalMoves.filter { candidate ->
            candidate.pieceId == move.pieceId && candidate.type == MoveType.CASTLING
        }
        return if (castles.indexOf(move) == 1) CastlingSide.QUEEN_SIDE else CastlingSide.KING_SIDE
    }

    private fun resolveCastlingRookId(
        state: GameState,
        army: ArmyColor,
        side: CastlingSide,
        explicitRookId: PieceId?,
    ): PieceId? {
        explicitRookId?.let { return it }
        val king = state.position.pieces.values.singleOrNull { piece ->
            piece.army == army && piece.type == PieceType.KING
        } ?: return null
        val candidates = state.position.pieces.values.filter { rook ->
            rook.type == PieceType.ROOK && rook.army == army && !rook.hasMoved &&
                    ThreePlayerBoardTopology.orthogonalRays(king.coordinate)
                        .any { ray -> rook.coordinate in ray.drop(1) }
        }.sortedBy { it.id.value }
        return when {
            candidates.size == 1 -> candidates.single().id
            side == CastlingSide.QUEEN_SIDE -> candidates.firstOrNull()?.id
            else -> candidates.lastOrNull()?.id
        }
    }

    private fun checkSuffix(
        before: GameState,
        move: Move,
        reduction: MoveReduction.Applied
    ): String {
        val checkmated = reduction.state.participants.any { (player, participant) ->
            before.participants.getValue(player).status is ParticipantStatus.Active &&
                    participant.status is ParticipantStatus.Checkmated && player != move.actor
        }
        if (checkmated) return "#"
        val givesCheck = reduction.state.participants.any { (player, participant) ->
            player != move.actor && participant.status is ParticipantStatus.Active &&
                    LegalMoveGenerator.isKingInCheck(reduction.state, player)
        }
        return if (givesCheck) "+" else ""
    }

    private fun reduce(state: GameState, move: Move): MoveReduction.Applied? = GameReducer.reduce(
        state,
        MoveIntent(move.actor, move.from, move.to, move.promotion),
        finishInsufficientMaterial = false,
    ) as? MoveReduction.Applied

    private fun parseLegacyCoordinates(line: String) = line
        .substringAfterLast(':')
        .split("->", limit = 2)
        .takeIf { it.size == 2 }
        ?.let { fields ->
            val from = ThreePlayerBoardNotation.parse(fields[0]) ?: return@let null
            val to = ThreePlayerBoardNotation.parse(fields[1]) ?: return@let null
            from to to
        }

    private fun String.removeArmyPrefix(): String =
        if (length >= 2 && this[0].uppercaseChar() in "WRB" && this[1] == '.') drop(2) else this

    private fun String.sanKey(): String = trim()
        .replace('0', 'O')
        .removeSuffix("+")
        .removeSuffix("#")
        .uppercase()

    private val ArmyColor.logCode: Char
        get() = when (this) {
            ArmyColor.WHITE -> 'W'
            ArmyColor.RED -> 'R'
            ArmyColor.BLACK -> 'B'
        }

    private val BoardFile.sanCode: Char
        get() = name.lowercase().single()

    private val PieceType.sanCode: Char
        get() = when (this) {
            PieceType.QUEEN -> 'Q'
            PieceType.KING -> 'K'
            PieceType.KNIGHT -> 'N'
            PieceType.BISHOP -> 'B'
            PieceType.ROOK -> 'R'
            PieceType.PAWN -> error("Pawns do not have a SAN piece prefix")
        }
}
