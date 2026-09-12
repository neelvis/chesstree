package com.chesstree.server

import java.time.Instant
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val username: String,
    val normalizedUsername: String,
    val passwordHash: String,
)

data class SessionRecord(val user: UserRecord, val expiresAt: Instant)

enum class GameStatus { WAITING, ACTIVE }

enum class PlayerColor { WHITE, RED, BLACK }

data class GamePlayer(val user: UserRecord, val joinedOrder: Int, val color: PlayerColor?)

data class GameRecord(
    val id: UUID,
    val code: String,
    val status: GameStatus,
    val players: List<GamePlayer>,
)

sealed interface CreateUserResult {
    data class Created(val user: UserRecord) : CreateUserResult
    data object UsernameTaken : CreateUserResult
}

sealed interface JoinGameResult {
    data class Joined(val game: GameRecord) : JoinGameResult
    data object Missing : JoinGameResult
    data object Full : JoinGameResult
}
