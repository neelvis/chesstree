package com.chesstree.game.domain

enum class CastlingSide {
    KING_SIDE,
    QUEEN_SIDE,
}

data class CastlingRight(
    val army: ArmyColor,
    val side: CastlingSide,
    val rookId: PieceId? = null,
)

class EnPassantTarget(
    pawnId: PieceId,
    captureCoordinate: BoardCoordinate,
    eligiblePlayers: Set<PlayerId>,
) {
    val pawnId: PieceId = pawnId
    val captureCoordinate: BoardCoordinate = captureCoordinate
    val eligiblePlayers: Set<PlayerId> = eligiblePlayers.toSet()

    init {
        require(this.eligiblePlayers.isNotEmpty()) {
            "An en passant target must have at least one eligible player"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is EnPassantTarget &&
                pawnId == other.pawnId &&
                captureCoordinate == other.captureCoordinate &&
                eligiblePlayers == other.eligiblePlayers

    override fun hashCode(): Int {
        var result = pawnId.hashCode()
        result = 31 * result + captureCoordinate.hashCode()
        result = 31 * result + eligiblePlayers.hashCode()
        return result
    }

    override fun toString(): String =
        "EnPassantTarget(pawnId=$pawnId, captureCoordinate=$captureCoordinate, eligiblePlayers=$eligiblePlayers)"
}

class Position(
    pieces: Map<PieceId, Piece>,
    castlingRights: Set<CastlingRight> = emptySet(),
    enPassantTarget: EnPassantTarget? = null,
    enPassantTargets: Collection<EnPassantTarget> = listOfNotNull(enPassantTarget),
) {
    val pieces: Map<PieceId, Piece> = pieces.toMap()
    val castlingRights: Set<CastlingRight> = castlingRights.toSet()
    val enPassantTargets: Map<PieceId, EnPassantTarget> = enPassantTargets.associate { target ->
        target.pawnId to EnPassantTarget(
            pawnId = target.pawnId,
            captureCoordinate = target.captureCoordinate,
            eligiblePlayers = target.eligiblePlayers,
        )
    }

    /** Compatibility view for callers that can represent only one target. */
    val enPassantTarget: EnPassantTarget? = this.enPassantTargets.values.singleOrNull()?.let {
        EnPassantTarget(
            pawnId = it.pawnId,
            captureCoordinate = it.captureCoordinate,
            eligiblePlayers = it.eligiblePlayers,
        )
    }

    init {
        require(
            enPassantTargets.map(EnPassantTarget::pawnId).distinct().size == enPassantTargets.size
        ) {
            "A pawn may have only one en passant target"
        }
        require(this.pieces.all { (id, piece) -> id == piece.id }) {
            "Every piece map key must match the piece id"
        }
        require(this.pieces.values.map(Piece::coordinate).toSet().size == this.pieces.size) {
            "Two pieces cannot occupy the same board coordinate"
        }
        require(this.enPassantTargets.keys.all(this.pieces::containsKey)) {
            "Every en passant pawn must be present in the position"
        }
        require(this.castlingRights.groupBy { it.army to it.side }.values.all { it.size == 1 }) {
            "An army may have only one castling right per side"
        }
        require(this.castlingRights.all { right ->
            right.rookId == null || this.pieces[right.rookId]?.let { rook ->
                rook.type == PieceType.ROOK && rook.army == right.army && !rook.hasMoved
            } == true
        }) {
            "An explicit castling right must identify its army's unmoved rook"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Position &&
                pieces == other.pieces &&
                castlingRights == other.castlingRights &&
                enPassantTargets == other.enPassantTargets

    override fun hashCode(): Int {
        var result = pieces.hashCode()
        result = 31 * result + castlingRights.hashCode()
        result = 31 * result + enPassantTargets.hashCode()
        return result
    }

    override fun toString(): String =
        "Position(pieces=$pieces, castlingRights=$castlingRights, enPassantTargets=$enPassantTargets)"
}
