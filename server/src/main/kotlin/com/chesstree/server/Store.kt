package com.chesstree.server

import java.time.Instant
import java.util.UUID

interface ChessTreeStore {
    suspend fun createUser(username: String, normalizedUsername: String, passwordHash: String): CreateUserResult
    suspend fun findUser(normalizedUsername: String): UserRecord?
    suspend fun saveSession(tokenHash: String, userId: UUID, expiresAt: Instant)
    suspend fun findSession(tokenHash: String, now: Instant): SessionRecord?
    suspend fun deleteSession(tokenHash: String)
    suspend fun createGame(id: UUID, code: String, ownerId: UUID): GameRecord?
    suspend fun joinGame(code: String, userId: UUID, shuffledColors: List<PlayerColor>): JoinGameResult
    suspend fun findGame(code: String): GameRecord?
}
