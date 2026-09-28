package com.chesstree.server

import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotPolicyLearner
import com.chesstree.game.domain.bot.BotTrainingSample
import java.time.Instant
import java.util.UUID

class InMemoryStore : ChessTreeStore {
    private val users = linkedMapOf<UUID, UserRecord>()
    private val passwordHashes = mutableMapOf<UUID, String>()
    private val usernames = mutableMapOf<String, UUID>()
    private val botUserIds = mutableSetOf<UUID>()
    private val sessions = mutableMapOf<String, Pair<UUID, Instant>>()
    private val games = linkedMapOf<String, MutableGame>()
    private val moves = mutableMapOf<String, MutableList<GameMoveRecord>>()
    private val revisions = mutableMapOf<String, Int>()
    private val undoRequests = mutableMapOf<String, UndoRequestRecord>()
    private val pushDevices = linkedMapOf<String, PushDevice>()
    private var botPolicy = BotPolicy.DEFAULT
    private val trainedGameIds = mutableSetOf<UUID>()

    override suspend fun createUser(
        username: String,
        normalizedUsername: String,
        passwordHash: String,
    ): CreateUserResult = synchronized(this) {
        if (normalizedUsername in usernames) return@synchronized CreateUserResult.UsernameTaken
        val user = UserRecord(UUID.randomUUID(), username)
        users[user.id] = user
        passwordHashes[user.id] = passwordHash
        usernames[normalizedUsername] = user.id
        CreateUserResult.Created(user)
    }

    override suspend fun findUser(normalizedUsername: String): UserCredentials? = synchronized(this) {
        val userId = usernames[normalizedUsername] ?: return@synchronized null
        if (userId in botUserIds) return@synchronized null
        val user = users[userId] ?: return@synchronized null
        UserCredentials(user, passwordHashes.getValue(userId))
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

    override suspend fun createGameWithBots(
        id: UUID,
        code: String,
        ownerId: UUID,
        botCount: Int,
        shuffledColors: List<PlayerColor>,
    ): GameRecord? = synchronized(this) {
        require(botCount in 1..2)
        require(shuffledColors.toSet() == PlayerColor.entries.toSet())
        if (code in games) return@synchronized null
        val owner = users.getValue(ownerId)
        val players = mutableListOf(GamePlayer(owner, joinedOrder = 0, color = null))
        repeat(botCount) { index ->
            val botId = UUID.randomUUID()
            val suffix = UUID.randomUUID().toString().replace("-", "").take(20)
            val bot = UserRecord(botId, "ChessTree Bot ${index + 1}")
            users[botId] = bot
            passwordHashes[botId] = "disabled-bot-login"
            usernames["bot_$suffix"] = botId
            botUserIds += botId
            players += GamePlayer(bot, joinedOrder = index + 1, color = null, isBot = true)
        }
        if (players.size == PLAYER_COUNT) {
            players.replaceAll { player -> player.copy(color = shuffledColors[player.joinedOrder]) }
        }
        val game = MutableGame(id, code, players, status = if (players.size == PLAYER_COUNT) {
            GameStatus.ACTIVE
        } else {
            GameStatus.WAITING
        })
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
        JoinGameResult.Joined(game.snapshot(), newlyJoined = true)
    }

    override suspend fun findGame(code: String): GameRecord? =
        synchronized(this) { games[code]?.snapshot() }

    override suspend fun findGameState(id: UUID): GameStateRecord? = synchronized(this) {
        games.values.firstOrNull { it.id == id }?.let { game -> state(game.code, game) }
    }

    override suspend fun findGamesForUser(userId: UUID): List<GameRecord> = synchronized(this) {
        games.values.filter { game -> game.players.any { it.user.id == userId } }
            .map { it.snapshot() }
            .sortedByDescending { it.startedAt }
    }

    override suspend fun findGameState(code: String, afterMoveCount: Int): GameStateRecord? = synchronized(this) {
        games[code]?.let { game ->
            val full = state(code, game)
            val offset = afterMoveCount.takeIf { it in 0..full.moves.size } ?: 0
            full.copy(moves = full.moves.drop(offset), moveOffset = offset)
        }
    }

    override suspend fun submitMove(
        code: String,
        userId: UUID,
        command: GameMoveCommand,
    ): SubmitMoveResult = submitMoveInternal(code, userId, command, isBotCommand = false)

    override suspend fun submitBotMove(
        code: String,
        botUserId: UUID,
        command: GameMoveCommand,
    ): SubmitMoveResult = submitMoveInternal(code, botUserId, command, isBotCommand = true)

    private suspend fun submitMoveInternal(
        code: String,
        userId: UUID,
        command: GameMoveCommand,
        isBotCommand: Boolean,
    ): SubmitMoveResult = synchronized(this) {
        val game = games[code] ?: return@synchronized SubmitMoveResult.Missing
        val gameMoves = moves.getValue(code)
        val state = state(code, game)
        when (val evaluation = evaluateMove(state, userId, command, isBotCommand = isBotCommand)) {
            is MoveEvaluation.Accepted -> {
                gameMoves += evaluation.move
                revisions[code] = revisions.getValue(code) + 1
                if (evaluation.finished) {
                    game.status = GameStatus.FINISHED
                    trainFromFinishedGame(game, evaluation.state, gameMoves)
                }
                SubmitMoveResult.Applied(
                    stateAfterMoveCount(code, game, command.expectedMoveCount)
                        .copy(domainState = evaluation.state, capturedPieces = evaluation.capturedPieces),
                )
            }

            MoveEvaluation.Duplicate -> SubmitMoveResult.Applied(
                stateAfterMoveCount(code, game, command.expectedMoveCount),
                wasDuplicate = true,
            )
            MoveEvaluation.Stale -> SubmitMoveResult.Stale(state)
            MoveEvaluation.NotActive -> SubmitMoveResult.NotActive
            MoveEvaluation.NotParticipant -> SubmitMoveResult.NotParticipant
            MoveEvaluation.NotTurn -> SubmitMoveResult.NotTurn
            MoveEvaluation.IllegalMove -> SubmitMoveResult.IllegalMove
            MoveEvaluation.CommandConflict -> SubmitMoveResult.CommandConflict
            MoveEvaluation.UndoPending -> SubmitMoveResult.UndoPending
        }
    }

    override suspend fun loadBotPolicy(): BotPolicy = synchronized(this) { botPolicy }

    private fun stateAfterMoveCount(
        code: String,
        game: MutableGame,
        expectedMoveCount: Int?,
    ): GameStateRecord {
        val full = state(code, game)
        val offset = expectedMoveCount?.takeIf { it in 0..full.moves.size } ?: 0
        return full.copy(moves = full.moves.drop(offset), moveOffset = offset)
    }

    override suspend fun requestUndo(
        code: String,
        userId: UUID,
        expectedRevision: Int,
    ): UndoResult = synchronized(this) {
        val game = games[code] ?: return@synchronized UndoResult.Missing
        if (game.players.none { !it.isBot && it.user.id == userId }) return@synchronized UndoResult.NotParticipant
        val current = state(code, game)
        if (expectedRevision != current.revision) return@synchronized UndoResult.Stale(current)
        if (current.game.status != GameStatus.ACTIVE) return@synchronized UndoResult.NotAvailable
        if (current.moves.isEmpty()) return@synchronized UndoResult.NotAvailable
        if (current.undoRequest != null) return@synchronized UndoResult.AlreadyPending
        val botApprovals = game.players.filter(GamePlayer::isBot).mapTo(linkedSetOf()) { it.user.id }
        val request = UndoRequestRecord(UUID.randomUUID(), userId, current.moves.size, botApprovals)
        if (botApprovals.size == game.players.size - 1) {
            moves.getValue(code).removeLast()
            game.status = GameStatus.ACTIVE
            undoRequests.remove(code)
        } else {
            undoRequests[code] = request
        }
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
        if (game.players.none { !it.isBot && it.user.id == userId }) return@synchronized UndoResult.NotParticipant
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

    override suspend fun registerPushDevice(device: PushDevice): Boolean = synchronized(this) {
        val alreadyRegistered = pushDevices[device.token]?.userId == device.userId
        if (
            !alreadyRegistered &&
            pushDevices.values.count { it.userId == device.userId } >= MAX_PUSH_DEVICES_PER_USER
        ) {
            return@synchronized false
        }
        pushDevices[device.token] = device
        true
    }

    override suspend fun removePushDevice(userId: UUID, token: String) {
        synchronized(this) {
            if (pushDevices[token]?.userId == userId) pushDevices.remove(token)
        }
    }

    override suspend fun findPushDevices(userIds: Set<UUID>): List<PushDevice> = synchronized(this) {
        pushDevices.values.filter { it.userId in userIds }
    }

    private fun state(code: String, game: MutableGame) = GameStateRecord(
        game = game.snapshot(),
        moves = moves.getValue(code).toList(),
        revision = revisions.getValue(code),
        undoRequest = undoRequests[code],
    )

    private fun trainFromFinishedGame(
        game: MutableGame,
        state: com.chesstree.game.domain.GameState,
        gameMoves: List<GameMoveRecord>,
    ) {
        if (game.id in trainedGameIds || game.players.none(GamePlayer::isBot)) return
        val outcome = (state.phase as? GamePhase.Finished)?.outcome ?: return
        val samples = gameMoves.mapNotNull(GameMoveRecord::trainingSample)
        botPolicy = BotPolicyLearner.learn(botPolicy, outcome, samples)
        trainedGameIds += game.id
    }

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
