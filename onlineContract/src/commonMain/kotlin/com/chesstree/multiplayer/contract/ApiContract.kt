package com.chesstree.multiplayer.contract

import kotlinx.serialization.Serializable

const val API_VERSION: Int = 5
const val API_VERSION_HEADER: String = "X-ChessTree-Protocol-Version"

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class PushDeviceRegistrationRequest(val token: String, val platform: String)

@Serializable
data class PushDeviceRemovalRequest(val token: String)

@Serializable
data class UserResponse(val id: String, val username: String)

@Serializable
data class AuthResponse(val accessToken: String, val user: UserResponse)

@Serializable
data class BrowserAuthResponse(val user: UserResponse)

@Serializable
data class GamePlayerResponse(
    val user: UserResponse,
    val color: String? = null,
    val isBot: Boolean = false,
)

@Serializable
data class CreateBotGameRequest(val botCount: Int)

@Serializable
data class GameResponse(
    val id: String,
    val code: String,
    val shareUrl: String,
    val status: String,
    val players: List<GamePlayerResponse>,
)

@Serializable
data class GameHistoryResponse(
    val id: String,
    val code: String,
    val status: String,
    val players: List<GamePlayerResponse>,
    val startedAt: String,
)

@Serializable
data class CoordinateResponse(val vertex: Int, val column: Int, val row: Int)

@Serializable
data class MoveCommandRequest(
    val commandId: String,
    val expectedRevision: Int,
    val from: CoordinateResponse,
    val to: CoordinateResponse,
    val promotion: String? = null,
    val expectedMoveCount: Int? = null,
)

@Serializable
data class MoveEventResponse(
    val revision: Int,
    val actor: String,
    val from: CoordinateResponse,
    val to: CoordinateResponse,
    val promotion: String? = null,
)

@Serializable
data class GamePositionSnapshotResponse(
    val version: Int = 1,
    val moveCount: Int,
    val pieces: List<SnapshotPieceResponse>,
    val castlingRights: List<SnapshotCastlingRightResponse>,
    val enPassantTargets: List<SnapshotEnPassantTargetResponse>,
    val participants: List<SnapshotParticipantResponse>,
    val armies: List<SnapshotArmyResponse>,
    val turn: SnapshotTurnResponse? = null,
    val outcome: SnapshotOutcomeResponse? = null,
    val capturedPieces: List<SnapshotCapturedPieceResponse>,
)

@Serializable
data class SnapshotCoordinateResponse(val vertex: Int, val column: Int, val row: Int)

@Serializable
data class SnapshotPieceResponse(
    val id: String,
    val type: String,
    val army: String,
    val coordinate: SnapshotCoordinateResponse,
    val hasMoved: Boolean,
)

@Serializable
data class SnapshotCastlingRightResponse(val army: String, val side: String, val rookId: String? = null)

@Serializable
data class SnapshotEnPassantTargetResponse(
    val pawnId: String,
    val captureCoordinate: SnapshotCoordinateResponse,
    val eligiblePlayers: List<String>,
)

@Serializable
data class SnapshotParticipantResponse(val id: String, val status: SnapshotParticipantStatusResponse)

@Serializable
data class SnapshotParticipantStatusResponse(val kind: String, val by: String? = null, val atPly: Int? = null)

@Serializable
data class SnapshotArmyResponse(val army: String, val controller: String)

@Serializable
data class SnapshotTurnResponse(val player: String, val ply: Int)

@Serializable
data class SnapshotOutcomeResponse(
    val type: String,
    val first: String? = null,
    val second: String? = null,
    val third: String? = null,
    val reason: String? = null,
)

@Serializable
data class SnapshotCapturedPieceResponse(
    val id: String,
    val type: String,
    val army: String,
    val bodyArmy: String,
    val capturedByArmy: String,
)

@Serializable
data class UndoRequestCommand(val expectedRevision: Int)

@Serializable
data class UndoVoteCommand(
    val expectedRevision: Int,
    val requestId: String,
    val approve: Boolean,
)

@Serializable
data class UndoRequestResponse(
    val id: String,
    val requestedByUserId: String,
    val targetMoveCount: Int,
    val approvedByUserIds: List<String>,
)

@Serializable
data class GameStateResponse(
    val game: GameResponse,
    val revision: Int,
    val moves: List<MoveEventResponse>,
    val moveOffset: Int = 0,
    val undoRequest: UndoRequestResponse? = null,
    val position: GamePositionSnapshotResponse? = null,
)

@Serializable
data class GameSocketAuthRequest(
    val accessToken: String,
    val protocolVersion: Int = 1,
)

@Serializable
data class GameStatePush(
    val protocolVersion: Int = API_VERSION,
    val state: GameStateResponse,
)

@Serializable
data class ErrorResponse(val code: String, val message: String)
