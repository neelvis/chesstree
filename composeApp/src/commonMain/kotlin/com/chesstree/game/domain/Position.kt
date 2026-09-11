package com.chesstree.game.domain

enum class CastlingSide {
    KING_SIDE,
    QUEEN_SIDE,
}

data class CastlingRight(
    val army: ArmyColor,
    val side: CastlingSide,
)

class EnPassantTarget(
    pawnId: PieceId,
    captureSquare: Square,
    eligiblePlayers: Set<PlayerId>,
) {
    val pawnId: PieceId = pawnId
    val captureSquare: Square = captureSquare
    val eligiblePlayers: Set<PlayerId> = eligiblePlayers.toSet()

    init {
        require(this.eligiblePlayers.isNotEmpty()) {
            "An en passant target must have at least one eligible player"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is EnPassantTarget &&
                pawnId == other.pawnId &&
                captureSquare == other.captureSquare &&
                eligiblePlayers == other.eligiblePlayers

    override fun hashCode(): Int {
        var result = pawnId.hashCode()
        result = 31 * result + captureSquare.hashCode()
        result = 31 * result + eligiblePlayers.hashCode()
        return result
    }

    override fun toString(): String =
        "EnPassantTarget(pawnId=$pawnId, captureSquare=$captureSquare, eligiblePlayers=$eligiblePlayers)"
}

class Position(
    pieces: Map<PieceId, Piece>,
    castlingRights: Set<CastlingRight> = emptySet(),
    enPassantTarget: EnPassantTarget? = null,
) {
    val pieces: Map<PieceId, Piece> = pieces.toMap()
    val castlingRights: Set<CastlingRight> = castlingRights.toSet()
    val enPassantTarget: EnPassantTarget? = enPassantTarget?.let {
        EnPassantTarget(
            pawnId = it.pawnId,
            captureSquare = it.captureSquare,
            eligiblePlayers = it.eligiblePlayers,
        )
    }

    init {
        require(this.pieces.all { (id, piece) -> id == piece.id }) {
            "Every piece map key must match the piece id"
        }
        require(this.pieces.values.map(Piece::square).toSet().size == this.pieces.size) {
            "Two pieces cannot occupy the same square"
        }
        require(this.enPassantTarget == null || this.pieces.containsKey(this.enPassantTarget.pawnId)) {
            "The en passant pawn must be present in the position"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Position &&
                pieces == other.pieces &&
                castlingRights == other.castlingRights &&
                enPassantTarget == other.enPassantTarget

    override fun hashCode(): Int {
        var result = pieces.hashCode()
        result = 31 * result + castlingRights.hashCode()
        result = 31 * result + (enPassantTarget?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "Position(pieces=$pieces, castlingRights=$castlingRights, enPassantTarget=$enPassantTarget)"
}
