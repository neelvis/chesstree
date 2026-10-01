package com.chesstree.bot.wire

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingRight
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.EnPassantTarget
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.Turn
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Full, versioned rules state for browser-worker requests. */
object BotStateWireCodec {
    const val VERSION = 1
    private const val MAX_LENGTH = 1_000_000
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(state: GameState): String = json.encodeToString(state.toWire())

    fun decode(contents: String): GameState? = runCatching {
        if (contents.length > MAX_LENGTH) return null
        val wire = json.decodeFromString<WireState>(contents)
        if (wire.version != VERSION) return null
        wire.toDomain()
    }.getOrNull()
}

@Serializable
internal data class WireCoordinate(val vertex: Int, val column: Int, val row: Int) {
    fun toDomain() = BoardCoordinate(vertex, column, row)
}

@Serializable
internal data class WirePiece(
    val id: String,
    val type: String,
    val army: String,
    val coordinate: WireCoordinate,
    val hasMoved: Boolean,
)

@Serializable
internal data class WireCastlingRight(val army: String, val side: String, val rookId: String?)

@Serializable
internal data class WireEnPassantTarget(
    val pawnId: String,
    val captureCoordinate: WireCoordinate,
    val eligiblePlayers: List<String>,
)

@Serializable
internal data class WireParticipant(
    val id: String,
    val status: String,
    val by: String? = null,
    val atPly: Int? = null,
)

@Serializable
internal data class WireArmy(val army: String, val controller: String)

@Serializable
internal data class WireTurn(val player: String, val ply: Int)

@Serializable
internal data class WireOutcome(
    val type: String,
    val first: String? = null,
    val second: String? = null,
    val third: String? = null,
    val reason: String? = null,
)

@Serializable
internal data class WireState(
    val version: Int,
    val pieces: List<WirePiece>,
    val castlingRights: List<WireCastlingRight>,
    val enPassantTargets: List<WireEnPassantTarget>,
    val participants: List<WireParticipant>,
    val armies: List<WireArmy>,
    val turn: WireTurn?,
    val outcome: WireOutcome?,
)

private fun BoardCoordinate.toWire() = WireCoordinate(vertex, column, row)

private fun GameState.toWire() = WireState(
    version = BotStateWireCodec.VERSION,
    pieces = position.pieces.values.sortedBy { it.id.value }.map { piece ->
        WirePiece(piece.id.value, piece.type.name, piece.army.name, piece.coordinate.toWire(), piece.hasMoved)
    },
    castlingRights = position.castlingRights.sortedWith(
        compareBy<CastlingRight> { it.army.ordinal }.thenBy { it.side.ordinal },
    ).map { WireCastlingRight(it.army.name, it.side.name, it.rookId?.value) },
    enPassantTargets = position.enPassantTargets.values.sortedBy { it.pawnId.value }.map { target ->
        WireEnPassantTarget(
            target.pawnId.value,
            target.captureCoordinate.toWire(),
            target.eligiblePlayers.sortedBy(PlayerId::ordinal).map(PlayerId::name),
        )
    },
    participants = PlayerId.entries.map { id ->
        val participant = participants.getValue(id)
        when (val status = participant.status) {
            ParticipantStatus.Active -> WireParticipant(id.name, "ACTIVE")
            is ParticipantStatus.Checkmated -> WireParticipant(id.name, "CHECKMATED", status.by.name, status.atPly)
            is ParticipantStatus.Stalemated -> WireParticipant(id.name, "STALEMATED", atPly = status.atPly)
        }
    },
    armies = ArmyColor.entries.map { army ->
        WireArmy(army.name, armies.getValue(army).controller.name)
    },
    turn = turn?.let { WireTurn(it.player.name, it.ply) },
    outcome = (phase as? GamePhase.Finished)?.outcome?.toWire(),
)

private fun GameOutcome.toWire(): WireOutcome = when (this) {
    is GameOutcome.Ranked -> WireOutcome("RANKED", first.name, second.name, third.name)
    is GameOutcome.ThreeWayDraw -> WireOutcome("THREE_WAY_DRAW", reason = reason.name)
    is GameOutcome.TwoWayDraw -> WireOutcome("TWO_WAY_DRAW", first.name, second.name, third.name, reason.name)
}

private fun WireState.toDomain(): GameState {
    require(pieces.map(WirePiece::id).distinct().size == pieces.size)
    require(castlingRights.map { it.army to it.side }.distinct().size == castlingRights.size)
    require(enPassantTargets.map(WireEnPassantTarget::pawnId).distinct().size == enPassantTargets.size)
    require(participants.map(WireParticipant::id).distinct().size == participants.size)
    require(armies.map(WireArmy::army).distinct().size == armies.size)
    val domainPieces = pieces.associate { item ->
        val id = PieceId(item.id)
        id to Piece(
            id,
            PieceType.valueOf(item.type),
            ArmyColor.valueOf(item.army),
            item.coordinate.toDomain(),
            item.hasMoved,
        )
    }
    val domainParticipants = participants.associate { item ->
        val id = PlayerId.valueOf(item.id)
        val status = when (item.status) {
            "ACTIVE" -> ParticipantStatus.Active
            "CHECKMATED" -> ParticipantStatus.Checkmated(
                PlayerId.valueOf(checkNotNull(item.by)),
                checkNotNull(item.atPly),
            )
            "STALEMATED" -> ParticipantStatus.Stalemated(checkNotNull(item.atPly))
            else -> error("Unknown participant status")
        }
        id to Participant(id, status)
    }
    val domainArmies = armies.associate { item ->
        val army = ArmyColor.valueOf(item.army)
        army to ArmyControl(army, PlayerId.valueOf(item.controller))
    }
    val domainOutcome = outcome?.toDomain()
    return GameState(
        position = Position(
            pieces = domainPieces,
            castlingRights = castlingRights.map { item ->
                CastlingRight(ArmyColor.valueOf(item.army), CastlingSide.valueOf(item.side), item.rookId?.let(::PieceId))
            }.toSet(),
            enPassantTargets = enPassantTargets.map { item ->
                EnPassantTarget(
                    PieceId(item.pawnId),
                    item.captureCoordinate.toDomain(),
                    item.eligiblePlayers.mapTo(linkedSetOf(), PlayerId::valueOf),
                )
            },
        ),
        participants = domainParticipants,
        armies = domainArmies,
        turn = turn?.let { Turn(PlayerId.valueOf(it.player), it.ply) },
        phase = domainOutcome?.let(GamePhase::Finished) ?: GamePhase.InProgress,
    )
}

private fun WireOutcome.toDomain(): GameOutcome = when (type) {
    "RANKED" -> GameOutcome.Ranked(
        PlayerId.valueOf(checkNotNull(first)),
        PlayerId.valueOf(checkNotNull(second)),
        PlayerId.valueOf(checkNotNull(third)),
    )
    "THREE_WAY_DRAW" -> GameOutcome.ThreeWayDraw(DrawReason.valueOf(checkNotNull(reason)))
    "TWO_WAY_DRAW" -> GameOutcome.TwoWayDraw(
        PlayerId.valueOf(checkNotNull(first)),
        PlayerId.valueOf(checkNotNull(second)),
        PlayerId.valueOf(checkNotNull(third)),
        DrawReason.valueOf(checkNotNull(reason)),
    )
    else -> error("Unknown outcome")
}
