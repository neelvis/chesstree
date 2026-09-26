package com.chesstree.server

import java.time.Instant
import java.util.UUID

class InMemoryStore : ChessTreeStore {
    private val users = linkedMapOf<UUID, UserRecord>()
    private val usernames = mutableMapOf<String, UUID>()
    private val sessions = mutableMapOf<String, Pair<UUID, Instant>>()
    private val games = linkedMapOf<String, MutableGame>()
    private val moves = mutableMapOf<String, MutableList<GameMoveRecord>>()
    private val revisions = mutableMapOf<String, Int>()
    private val undoRequests = mutableMapOf<String, UndoRequestRecord>()

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

    override suspend fun findSession(tokenHash: String, now: Instant): SessionRecord? =
        synchronized(this) {
            val (userId, expiresAt) = sessions[tokenHash] ?: return@synchronized null
            if (!expiresAt.isAfter(now)) {
                sessions.remove(tokenHash)
                return@synchronized null
            }
            users[userId]?.let { SessionRecord(it, expiresAt) }
        }

    override suspend fun renewSession(
        tokenHash: String,
        now: Instant,
        expiresAt: Instant,
    ): SessionRecord? = synchronized(this) {
        val (userId, currentExpiresAt) = sessions[tokenHash] ?: return@synchronized null
        if (!currentExpiresAt.isAfter(now)) {
            sessions.remove(tokenHash)
            return@synchronized null
        }
        val user = users[userId] ?: return@synchronized null
        sessions[tokenHash] = userId to expiresAt
        SessionRecord(user, expiresAt)
    }

    override suspend fun deleteSession(tokenHash: String) {
        synchronized(this) { sessions.remove(tokenHash) }
    }

    override suspend fun createGame(id: UUID, code: String, ownerId: UUID): GameRecord? =
        synchronized(this) {
            if (code in games) return@synchronized null
            val owner = users.getValue(ownerId)
            val game = MutableGame(id, code, mutableListOf(GamePlayer(owner, 0, null)))
            games[code] = game
            moves[code] = mutableListOf()
            revisions[code] = 0
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

    override suspend fun findGame(code: String): GameRecord? =
        synchronized(this) { games[code]?.snapshot() }

    override suspend fun findGamesForUser(userId: UUID): List<GameRecord> = synchronized(this) {
        games.values.filter { game -> game.players.any { it.user.id == userId } }
            .map { it.snapshot() }
            .sortedByDescending { it.startedAt }
    }

    override suspend fun findGameState(code: String): GameStateRecord? = synchronized(this) {
        games[code]?.let { game -> state(code, game) }
    }

    override suspend fun submitMove(
        code: String,
        userId: UUID,
        command: GameMoveCommand,
    ): SubmitMoveResult = synchronized(this) {
        val game = games[code] ?: return@synchronized SubmitMoveResult.Missing
        val gameMoves = moves.getValue(code)
        val state = state(code, game)
        when (val evaluation = evaluateMove(state, userId, command)) {
            is MoveEvaluation.Accepted -> {
                gameMoves += evaluation.move
                revisions[code] = revisions.getValue(code) + 1
                if (evaluation.finished) game.status = GameStatus.FINISHED
                SubmitMoveResult.Applied(state(code, game))
            }

            MoveEvaluation.Duplicate -> SubmitMoveResult.Applied(state)
            MoveEvaluation.Stale -> SubmitMoveResult.Stale(state)
            MoveEvaluation.NotActive -> SubmitMoveResult.NotActive
            MoveEvaluation.NotParticipant -> SubmitMoveResult.NotParticipant
            MoveEvaluation.NotTurn -> SubmitMoveResult.NotTurn
            MoveEvaluation.IllegalMove -> SubmitMoveResult.IllegalMove
            MoveEvaluation.CommandConflict -> SubmitMoveResult.CommandConflict
            MoveEvaluation.UndoPending -> SubmitMoveResult.UndoPending
        }
    }

    override suspend fun requestUndo(
        code: String,
        userId: UUID,
        expectedRevision: Int,
    ): UndoResult = synchronized(this) {
        val game = games[code] ?: return@synchronized UndoResult.Missing
        if (game.players.none { it.user.id == userId }) return@synchronized UndoResult.NotParticipant
        val current = state(code, game)
        if (expectedRevision != current.revision) return@synchronized UndoResult.Stale(current)
        if (current.moves.isEmpty()) return@synchronized UndoResult.NotAvailable
        if (current.undoRequest != null) return@synchronized UndoResult.AlreadyPending
        undoRequests[code] = UndoRequestRecord(UUID.randomUUID(), userId, current.moves.size)
        revisions[code] = current.revision + 1
        UndoResult.Updated(state(code, game))
    }

    override suspend fun voteUndo(
        code: String,
        userId: UUID,
        requestId: UUID,
        expectedRevision: Int,
        approve: Boolean,
    ): UndoResult = synchronized(this) {
        val game = games[code] ?: return@synchronized UndoResult.Missing
        if (game.players.none { it.user.id == userId }) return@synchronized UndoResult.NotParticipant
        val current = state(code, game)
        if (expectedRevision != current.revision) return@synchronized UndoResult.Stale(current)
        val request = current.undoRequest
            ?.takeIf { it.id == requestId }
            ?: return@synchronized UndoResult.NotAvailable
        if (request.requestedByUserId == userId) return@synchronized UndoResult.RequesterCannotVote
        if (userId in request.approvedByUserIds) return@synchronized UndoResult.AlreadyVoted
        revisions[code] = current.revision + 1
        if (!approve) {
            undoRequests.remove(code)
            return@synchronized UndoResult.Updated(state(code, game))
        }
        val approved = request.approvedByUserIds + userId
        if (approved.size == game.players.size - 1) {
            moves.getValue(code).removeLast()
            undoRequests.remove(code)
            game.status = GameStatus.ACTIVE
        } else {
            undoRequests[code] = request.copy(approvedByUserIds = approved)
        }
        UndoResult.Updated(state(code, game))
    }

    private fun state(code: String, game: MutableGame) = GameStateRecord(
        game = game.snapshot(),
        moves = moves.getValue(code).toList(),
        revision = revisions.getValue(code),
        undoRequest = undoRequests[code],
    )

    private data class MutableGame(
        val id: UUID,
        val code: String,
        val players: MutableList<GamePlayer>,
        val startedAt: Instant = Instant.now(),
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
            startedAt = startedAt,
        )
    }

    private companion object {
        const val PLAYER_COUNT = 3
    }
}
