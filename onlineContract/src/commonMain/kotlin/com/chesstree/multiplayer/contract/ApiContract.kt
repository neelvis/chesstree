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
data class ErrorResponse(val code: String, val message: String)
