package com.chesstree.multiplayer.contract

import kotlinx.serialization.Serializable

const val API_VERSION: Int = 2
const val API_VERSION_HEADER: String = "X-ChessTree-Protocol-Version"

@Serializable
data class RegisterRequest(val username: String, val password: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class UserResponse(val id: String, val username: String)

@Serializable
data class AuthResponse(val accessToken: String, val user: UserResponse)

@Serializable
data class GamePlayerResponse(val user: UserResponse, val color: String? = null)

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
    val undoRequest: UndoRequestResponse? = null,
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
