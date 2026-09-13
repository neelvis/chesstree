package com.chesstree.multiplayer.contract

import kotlinx.serialization.Serializable

const val API_VERSION: Int = 1

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
data class GameStateResponse(
    val game: GameResponse,
    val revision: Int,
    val moves: List<MoveEventResponse>,
)

@Serializable
data class GameSocketAuthRequest(val accessToken: String)

@Serializable
data class GameStatePush(
    val protocolVersion: Int = API_VERSION,
    val state: GameStateResponse,
)

@Serializable
data class ErrorResponse(val code: String, val message: String)
