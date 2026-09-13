package com.chesstree.server

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PromotionChoice
import java.time.Instant
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val username: String,
    val normalizedUsername: String,
    val passwordHash: String,
)

data class SessionRecord(val user: UserRecord, val expiresAt: Instant)

enum class GameStatus { WAITING, ACTIVE, FINISHED }

enum class PlayerColor { WHITE, RED, BLACK }

data class GamePlayer(val user: UserRecord, val joinedOrder: Int, val color: PlayerColor?)

data class GameRecord(
    val id: UUID,
    val code: String,
    val status: GameStatus,
    val players: List<GamePlayer>,
)

data class GameMoveCommand(
    val commandId: UUID,
    val expectedRevision: Int,
    val from: BoardCoordinate,
    val to: BoardCoordinate,
    val promotion: PromotionChoice?,
)

data class GameMoveRecord(
    val commandId: UUID,
    val userId: UUID,
    val expectedRevision: Int,
    val intent: MoveIntent,
)

data class GameStateRecord(val game: GameRecord, val moves: List<GameMoveRecord>)

sealed interface CreateUserResult {
    data class Created(val user: UserRecord) : CreateUserResult
    data object UsernameTaken : CreateUserResult
}

sealed interface JoinGameResult {
    data class Joined(val game: GameRecord) : JoinGameResult
    data object Missing : JoinGameResult
    data object Full : JoinGameResult
}

sealed interface SubmitMoveResult {
    data class Applied(val state: GameStateRecord) : SubmitMoveResult
    data class Stale(val state: GameStateRecord) : SubmitMoveResult
    data object Missing : SubmitMoveResult
    data object NotActive : SubmitMoveResult
    data object NotParticipant : SubmitMoveResult
    data object NotTurn : SubmitMoveResult
    data object IllegalMove : SubmitMoveResult
    data object CommandConflict : SubmitMoveResult
}
