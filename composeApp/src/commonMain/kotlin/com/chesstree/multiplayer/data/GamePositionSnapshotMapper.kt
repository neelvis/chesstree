package com.chesstree.multiplayer.data

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
import com.chesstree.game.domain.session.CapturedPiece
import com.chesstree.game.domain.session.GameSession
import com.chesstree.multiplayer.contract.GamePositionSnapshotResponse

internal fun GamePositionSnapshotResponse.toSession(
    scenario: com.chesstree.game.domain.scenario.GameScenario,
    moves: List<com.chesstree.game.domain.MoveIntent>,
): GameSession? = runCatching {
    require(version == 1)
    GameSession(
        scenario = scenario,
        state = GameState(
            position = Position(
                pieces = pieces.associate { dto ->
                    val id = PieceId(dto.id)
                    id to Piece(id, PieceType.valueOf(dto.type), ArmyColor.valueOf(dto.army), dto.coordinate.toDomain(), dto.hasMoved)
                },
                castlingRights = castlingRights.map { CastlingRight(ArmyColor.valueOf(it.army), CastlingSide.valueOf(it.side), it.rookId?.let(::PieceId)) }.toSet(),
                enPassantTargets = enPassantTargets.map { EnPassantTarget(PieceId(it.pawnId), it.captureCoordinate.toDomain(), it.eligiblePlayers.mapTo(linkedSetOf(), PlayerId::valueOf)) },
            ),
            participants = participants.associate { dto ->
                val id = PlayerId.valueOf(dto.id)
                val status = when (dto.status.kind) {
                    "ACTIVE" -> ParticipantStatus.Active
                    "CHECKMATED" -> ParticipantStatus.Checkmated(PlayerId.valueOf(checkNotNull(dto.status.by)), checkNotNull(dto.status.atPly))
                    "STALEMATED" -> ParticipantStatus.Stalemated(checkNotNull(dto.status.atPly))
                    else -> error("Unknown participant status")
                }
                id to Participant(id, status)
            },
            armies = armies.associate { dto ->
                val army = ArmyColor.valueOf(dto.army)
                army to ArmyControl(army, PlayerId.valueOf(dto.controller))
            },
            turn = turn?.let { Turn(PlayerId.valueOf(it.player), it.ply) },
            phase = outcome?.let { GamePhase.Finished(it.toDomain()) } ?: GamePhase.InProgress,
        ),
        moves = moves,
        capturedPieces = capturedPieces.map {
            CapturedPiece(it.id, PieceType.valueOf(it.type), ArmyColor.valueOf(it.army), ArmyColor.valueOf(it.bodyArmy), ArmyColor.valueOf(it.capturedByArmy))
        },
    )
}.getOrNull()

private fun com.chesstree.multiplayer.contract.SnapshotCoordinateResponse.toDomain() =
    BoardCoordinate(vertex, column, row)

private fun com.chesstree.multiplayer.contract.SnapshotOutcomeResponse.toDomain(): GameOutcome = when (type) {
    "RANKED" -> GameOutcome.Ranked(PlayerId.valueOf(checkNotNull(first)), PlayerId.valueOf(checkNotNull(second)), PlayerId.valueOf(checkNotNull(third)))
    "THREE_WAY_DRAW" -> GameOutcome.ThreeWayDraw(DrawReason.valueOf(checkNotNull(reason)))
    "TWO_WAY_DRAW" -> GameOutcome.TwoWayDraw(PlayerId.valueOf(checkNotNull(first)), PlayerId.valueOf(checkNotNull(second)), PlayerId.valueOf(checkNotNull(third)), DrawReason.valueOf(checkNotNull(reason)))
    else -> error("Unknown outcome")
}
