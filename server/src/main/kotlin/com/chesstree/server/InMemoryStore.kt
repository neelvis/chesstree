package com.chesstree.server

import java.time.Instant
import java.util.UUID

class InMemoryStore : ChessTreeStore {
    private val users = linkedMapOf<UUID, UserRecord>()
    private val usernames = mutableMapOf<String, UUID>()
    private val sessions = mutableMapOf<String, Pair<UUID, Instant>>()
    private val games = linkedMapOf<String, MutableGame>()
    private val moves = mutableMapOf<String, MutableList<GameMoveRecord>>()

    override suspend fun createUser(
        username: String,
        normalizedUsername: String,
        passwordHash: String,
    ): CreateUserResult = synchronized(this) {
        if (normalizedUsername in usernames) return@synchronized CreateUserResult.UsernameTaken
        val user = UserRecord(UUID.randomUUID(), username, normalizedUsername, passwordHash)
        users[user.id] = user
        usernames[normalizedUsername] = user.id
        CreateUserResult.Created(user)
    }

    override suspend fun findUser(normalizedUsername: String): UserRecord? = synchronized(this) {
        usernames[normalizedUsername]?.let(users::get)
    }

    override suspend fun saveSession(tokenHash: String, userId: UUID, expiresAt: Instant) {
        synchronized(this) { sessions[tokenHash] = userId to expiresAt }
    }

    override suspend fun findSession(tokenHash: String, now: Instant): SessionRecord? = synchronized(this) {
        val (userId, expiresAt) = sessions[tokenHash] ?: return@synchronized null
        if (!expiresAt.isAfter(now)) {
            sessions.remove(tokenHash)
            return@synchronized null
        }
        users[userId]?.let { SessionRecord(it, expiresAt) }
    }

    override suspend fun deleteSession(tokenHash: String) {
        synchronized(this) { sessions.remove(tokenHash) }
    }

    override suspend fun createGame(id: UUID, code: String, ownerId: UUID): GameRecord? = synchronized(this) {
        if (code in games) return@synchronized null
        val owner = users.getValue(ownerId)
        val game = MutableGame(id, code, mutableListOf(GamePlayer(owner, 0, null)))
        games[code] = game
        moves[code] = mutableListOf()
        game.snapshot()
    }

    override suspend fun joinGame(
        code: String,
        userId: UUID,
        shuffledColors: List<PlayerColor>,
    ): JoinGameResult = synchronized(this) {
        val game = games[code] ?: return@synchronized JoinGameResult.Missing
        game.players.firstOrNull { it.user.id == userId }?.let {
            return@synchronized JoinGameResult.Joined(game.snapshot())
        }
        if (game.players.size >= PLAYER_COUNT) return@synchronized JoinGameResult.Full
        game.players += GamePlayer(users.getValue(userId), game.players.size, null)
        if (game.players.size == PLAYER_COUNT) {
            game.players.replaceAll { player -> player.copy(color = shuffledColors[player.joinedOrder]) }
        }
        JoinGameResult.Joined(game.snapshot())
    }

    override suspend fun findGame(code: String): GameRecord? = synchronized(this) { games[code]?.snapshot() }

    override suspend fun findGameState(code: String): GameStateRecord? = synchronized(this) {
        games[code]?.let { game -> GameStateRecord(game.snapshot(), moves.getValue(code).toList()) }
    }

    override suspend fun submitMove(
        code: String,
        userId: UUID,
        command: GameMoveCommand,
    ): SubmitMoveResult = synchronized(this) {
        val game = games[code] ?: return@synchronized SubmitMoveResult.Missing
        val gameMoves = moves.getValue(code)
        val state = GameStateRecord(game.snapshot(), gameMoves.toList())
        when (val evaluation = evaluateMove(state, userId, command)) {
            is MoveEvaluation.Accepted -> {
                gameMoves += evaluation.move
                if (evaluation.finished) game.status = GameStatus.FINISHED
                SubmitMoveResult.Applied(GameStateRecord(game.snapshot(), gameMoves.toList()))
            }
            MoveEvaluation.Duplicate -> SubmitMoveResult.Applied(state)
            MoveEvaluation.Stale -> SubmitMoveResult.Stale(state)
            MoveEvaluation.NotActive -> SubmitMoveResult.NotActive
            MoveEvaluation.NotParticipant -> SubmitMoveResult.NotParticipant
            MoveEvaluation.NotTurn -> SubmitMoveResult.NotTurn
            MoveEvaluation.IllegalMove -> SubmitMoveResult.IllegalMove
            MoveEvaluation.CommandConflict -> SubmitMoveResult.CommandConflict
        }
    }

    private data class MutableGame(
        val id: UUID,
        val code: String,
        val players: MutableList<GamePlayer>,
        var status: GameStatus = GameStatus.WAITING,
    ) {
        fun snapshot() = GameRecord(
            id = id,
            code = code,
            status = when {
                status == GameStatus.FINISHED -> GameStatus.FINISHED
                players.size == PLAYER_COUNT -> GameStatus.ACTIVE
                else -> GameStatus.WAITING
            },
            players = players.toList(),
        )
    }

    private companion object {
        const val PLAYER_COUNT = 3
    }
}
