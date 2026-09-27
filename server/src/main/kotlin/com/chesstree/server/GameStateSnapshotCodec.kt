package com.chesstree.server

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

/** Versioned database representation of the current rules state; the move log remains authoritative. */
internal object GameStateSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(state: GameState): String = json.encodeToString(state.toSnapshot())

    fun decode(contents: String): GameState? = runCatching {
        json.decodeFromString<PersistedGameState>(contents)
            .takeIf { it.version == SNAPSHOT_VERSION }
            ?.toDomain()
    }.getOrNull()

    private fun GameState.toSnapshot() = PersistedGameState(
        pieces = position.pieces.values.map { piece ->
            PersistedPiece(piece.id.value, piece.type.name, piece.army.name, piece.coordinate.toDto(), piece.hasMoved)
        },
        castlingRights = position.castlingRights.map { PersistedCastlingRight(it.army.name, it.side.name, it.rookId?.value) },
        enPassantTargets = position.enPassantTargets.values.map { target ->
            PersistedEnPassantTarget(
                target.pawnId.value,
                target.captureCoordinate.toDto(),
                target.eligiblePlayers.map(PlayerId::name),
            )
        },
        participants = participants.values.map { participant ->
            PersistedParticipant(
                participant.id.name,
                when (val status = participant.status) {
                    ParticipantStatus.Active -> PersistedParticipantStatus("ACTIVE")
                    is ParticipantStatus.Checkmated -> PersistedParticipantStatus("CHECKMATED", status.by.name, status.atPly)
                    is ParticipantStatus.Stalemated -> PersistedParticipantStatus("STALEMATED", atPly = status.atPly)
                },
            )
        },
        armies = armies.values.map { PersistedArmyControl(it.army.name, it.controller.name) },
        turn = turn?.let { PersistedTurn(it.player.name, it.ply) },
        outcome = (phase as? GamePhase.Finished)?.outcome?.toDto(),
    )

    private fun PersistedGameState.toDomain(): GameState {
        val pieces = pieces.associate { item ->
            val id = PieceId(item.id)
            id to Piece(
                id = id,
                type = PieceType.valueOf(item.type),
                army = ArmyColor.valueOf(item.army),
                coordinate = item.coordinate.toDomain(),
                hasMoved = item.hasMoved,
            )
        }
        val rights = castlingRights.map { item ->
            CastlingRight(ArmyColor.valueOf(item.army), CastlingSide.valueOf(item.side), item.rookId?.let(::PieceId))
        }.toSet()
        val targets = enPassantTargets.map { item ->
            EnPassantTarget(
                PieceId(item.pawnId),
                item.captureCoordinate.toDomain(),
                item.eligiblePlayers.mapTo(linkedSetOf(), PlayerId::valueOf),
            )
        }
        val participants = participants.associate { item ->
            val status = when (item.status.kind) {
                "ACTIVE" -> ParticipantStatus.Active
                "CHECKMATED" -> ParticipantStatus.Checkmated(
                    PlayerId.valueOf(checkNotNull(item.status.by)),
                    checkNotNull(item.status.atPly),
                )
                "STALEMATED" -> ParticipantStatus.Stalemated(checkNotNull(item.status.atPly))
                else -> error("Unknown participant status")
            }
            PlayerId.valueOf(item.id) to Participant(PlayerId.valueOf(item.id), status)
        }
        val armies = armies.associate { item ->
            val color = ArmyColor.valueOf(item.army)
            color to ArmyControl(color, PlayerId.valueOf(item.controller))
        }
        val phase = outcome?.let { GamePhase.Finished(it.toDomain()) } ?: GamePhase.InProgress
        return GameState(
            position = Position(pieces, rights, enPassantTargets = targets),
            participants = participants,
            armies = armies,
            turn = turn?.let { Turn(PlayerId.valueOf(it.player), it.ply) },
            phase = phase,
        )
    }

    private fun GameOutcome.toDto(): PersistedOutcome = when (this) {
        is GameOutcome.Ranked -> PersistedOutcome("RANKED", first.name, second.name, third.name)
        is GameOutcome.ThreeWayDraw -> PersistedOutcome("THREE_WAY_DRAW", reason = reason.name)
        is GameOutcome.TwoWayDraw -> PersistedOutcome("TWO_WAY_DRAW", first.name, second.name, third.name, reason.name)
    }

    private fun PersistedOutcome.toDomain(): GameOutcome = when (type) {
        "RANKED" -> GameOutcome.Ranked(PlayerId.valueOf(checkNotNull(first)), PlayerId.valueOf(checkNotNull(second)), PlayerId.valueOf(checkNotNull(third)))
        "THREE_WAY_DRAW" -> GameOutcome.ThreeWayDraw(DrawReason.valueOf(checkNotNull(reason)))
        "TWO_WAY_DRAW" -> GameOutcome.TwoWayDraw(
            PlayerId.valueOf(checkNotNull(first)),
            PlayerId.valueOf(checkNotNull(second)),
            PlayerId.valueOf(checkNotNull(third)),
            DrawReason.valueOf(checkNotNull(reason)),
        )
        else -> error("Unknown game outcome")
    }

    private fun BoardCoordinate.toDto() = PersistedCoordinate(vertex, column, row)
    private fun PersistedCoordinate.toDomain() = BoardCoordinate(vertex, column, row)

    private const val SNAPSHOT_VERSION = 1

    @Serializable
    private data class PersistedGameState(
        val version: Int = SNAPSHOT_VERSION,
        val pieces: List<PersistedPiece>,
        val castlingRights: List<PersistedCastlingRight>,
        val enPassantTargets: List<PersistedEnPassantTarget>,
        val participants: List<PersistedParticipant>,
        val armies: List<PersistedArmyControl>,
        val turn: PersistedTurn?,
        val outcome: PersistedOutcome?,
    )

    @Serializable private data class PersistedCoordinate(val vertex: Int, val column: Int, val row: Int)
    @Serializable private data class PersistedPiece(val id: String, val type: String, val army: String, val coordinate: PersistedCoordinate, val hasMoved: Boolean)
    @Serializable private data class PersistedCastlingRight(val army: String, val side: String, val rookId: String?)
    @Serializable private data class PersistedEnPassantTarget(val pawnId: String, val captureCoordinate: PersistedCoordinate, val eligiblePlayers: List<String>)
    @Serializable private data class PersistedParticipant(val id: String, val status: PersistedParticipantStatus)
    @Serializable private data class PersistedParticipantStatus(val kind: String, val by: String? = null, val atPly: Int? = null)
    @Serializable private data class PersistedArmyControl(val army: String, val controller: String)
    @Serializable private data class PersistedTurn(val player: String, val ply: Int)
    @Serializable private data class PersistedOutcome(val type: String, val first: String? = null, val second: String? = null, val third: String? = null, val reason: String? = null)
}
