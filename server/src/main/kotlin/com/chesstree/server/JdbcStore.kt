package com.chesstree.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class DatabaseConfig(val url: String, val user: String, val password: String)

class JdbcStore(private val config: DatabaseConfig) : ChessTreeStore {
    suspend fun initialize() = io {
        connection().use { connection ->
            val isPostgres = connection.metaData.databaseProductName == "PostgreSQL"
            if (isPostgres) connection.execute("SELECT pg_advisory_lock($SCHEMA_LOCK_KEY)")
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS schema_metadata (
                            singleton BOOLEAN DEFAULT TRUE PRIMARY KEY CHECK (singleton),
                            version INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS users (
                            id UUID PRIMARY KEY,
                            username VARCHAR(24) NOT NULL,
                            normalized_username VARCHAR(24) NOT NULL UNIQUE,
                            password_hash TEXT NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS sessions (
                            token_hash CHAR(64) PRIMARY KEY,
                            user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                            expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS games (
                            id UUID PRIMARY KEY,
                            public_code CHAR(7) NOT NULL UNIQUE,
                            status VARCHAR(16) NOT NULL,
                            created_by UUID NOT NULL REFERENCES users(id),
                            revision INTEGER NOT NULL DEFAULT 0,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS game_players (
                            game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
                            user_id UUID NOT NULL REFERENCES users(id),
                            joined_order INTEGER NOT NULL,
                            color VARCHAR(16),
                            joined_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (game_id, user_id),
                            UNIQUE (game_id, joined_order),
                            UNIQUE (game_id, color)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS game_moves (
                            game_id UUID NOT NULL REFERENCES games(id) ON DELETE CASCADE,
                            revision INTEGER NOT NULL,
                            command_id UUID NOT NULL,
                            user_id UUID NOT NULL REFERENCES users(id),
                            expected_revision INTEGER NOT NULL,
                            actor VARCHAR(16) NOT NULL,
                            from_vertex INTEGER NOT NULL,
                            from_column INTEGER NOT NULL,
                            from_row INTEGER NOT NULL,
                            to_vertex INTEGER NOT NULL,
                            to_column INTEGER NOT NULL,
                            to_row INTEGER NOT NULL,
                            promotion VARCHAR(16),
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            PRIMARY KEY (game_id, revision),
                            UNIQUE (game_id, command_id)
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS game_undo_requests (
                            game_id UUID PRIMARY KEY REFERENCES games(id) ON DELETE CASCADE,
                            id UUID NOT NULL UNIQUE,
                            requested_by UUID NOT NULL REFERENCES users(id),
                            target_move_count INTEGER NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )
                        """.trimIndent(),
                    )
                    statement.executeUpdate(
                        """
                        CREATE TABLE IF NOT EXISTS game_undo_votes (
                            request_id UUID NOT NULL REFERENCES game_undo_requests(id) ON DELETE CASCADE,
                            user_id UUID NOT NULL REFERENCES users(id),
                            PRIMARY KEY (request_id, user_id)
                        )
                        """.trimIndent(),
                    )
                    try {
                        statement.executeUpdate(
                            "INSERT INTO schema_metadata (singleton, version) VALUES (TRUE, $SCHEMA_VERSION)",
                        )
                    } catch (error: java.sql.SQLException) {
                        if (error.sqlState != UNIQUE_VIOLATION) throw error
                    }
                    val schemaVersion = statement.executeQuery(
                        "SELECT version FROM schema_metadata WHERE singleton = TRUE",
                    ).use { rows ->
                        check(rows.next()) { "Database schema version is missing" }
                        rows.getInt("version")
                    }
                    when (schemaVersion) {
                        SCHEMA_VERSION -> Unit
                        1, 2, 3 -> {
                            statement.executeUpdate(
                                "ALTER TABLE games ADD COLUMN IF NOT EXISTS revision INTEGER NOT NULL DEFAULT 0",
                            )
                            statement.executeUpdate(
                                """
                                UPDATE games SET revision = (
                                    SELECT COUNT(*) FROM game_moves WHERE game_moves.game_id = games.id
                                )
                                """.trimIndent(),
                            )
                            statement.executeUpdate(
                                "UPDATE schema_metadata SET version = $SCHEMA_VERSION WHERE singleton = TRUE",
                            )
                        }
                        else -> error("Unsupported database schema version: $schemaVersion")
                    }
                    if (isPostgres) installGameUpdateTriggers(connection)
                }
            } finally {
                if (isPostgres) connection.execute("SELECT pg_advisory_unlock($SCHEMA_LOCK_KEY)")
            }
        }
    }

    override suspend fun createUser(
        username: String,
        normalizedUsername: String,
        passwordHash: String,
    ): CreateUserResult = io {
        val user = UserRecord(UUID.randomUUID(), username, normalizedUsername, passwordHash)
        try {
            connection().use { connection ->
                connection.prepareStatement(
                    "INSERT INTO users (id, username, normalized_username, password_hash) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setObject(1, user.id)
                    statement.setString(2, user.username)
                    statement.setString(3, user.normalizedUsername)
                    statement.setString(4, user.passwordHash)
                    statement.executeUpdate()
                }
            }
            CreateUserResult.Created(user)
        } catch (error: java.sql.SQLException) {
            if (error.sqlState == UNIQUE_VIOLATION) CreateUserResult.UsernameTaken else throw error
        }
    }

    override suspend fun findUser(normalizedUsername: String): UserRecord? = io {
        connection().use { connection ->
            connection.prepareStatement(
                "SELECT id, username, normalized_username, password_hash FROM users WHERE normalized_username = ?",
            ).use { statement ->
                statement.setString(1, normalizedUsername)
                statement.executeQuery().use { rows -> if (rows.next()) rows.user() else null }
            }
        }
    }

    override suspend fun saveSession(tokenHash: String, userId: UUID, expiresAt: Instant): Unit = io {
        connection().use { connection ->
            connection.prepareStatement(
                "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(expiresAt))
                statement.executeUpdate()
            }
        }
    }

    override suspend fun findSession(tokenHash: String, now: Instant): SessionRecord? = io {
        connection().use { connection ->
            connection.prepareStatement(
                """
                SELECT u.id, u.username, u.normalized_username, u.password_hash, s.expires_at
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeQuery().use { rows ->
                    if (rows.next()) SessionRecord(rows.user(), rows.getTimestamp("expires_at").toInstant()) else null
                }
            }
        }
    }

    override suspend fun renewSession(
        tokenHash: String,
        now: Instant,
        expiresAt: Instant,
    ): SessionRecord? = io {
        connection().use { connection ->
            val updated = connection.prepareStatement(
                "UPDATE sessions SET expires_at = ? WHERE token_hash = ? AND expires_at > ?",
            ).use { statement ->
                statement.setTimestamp(1, Timestamp.from(expiresAt))
                statement.setString(2, tokenHash)
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeUpdate()
            }
            if (updated == 0) {
                null
            } else connection.prepareStatement(
                """
                SELECT u.id, u.username, u.normalized_username, u.password_hash, s.expires_at
                FROM sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.expires_at > ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tokenHash)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeQuery().use { rows ->
                    if (rows.next()) SessionRecord(rows.user(), rows.getTimestamp("expires_at").toInstant()) else null
                }
            }
        }
    }

    override suspend fun deleteSession(tokenHash: String): Unit = io {
        connection().use { connection ->
            connection.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { statement ->
                statement.setString(1, tokenHash)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun createGame(id: UUID, code: String, ownerId: UUID): GameRecord? = io {
        try {
            transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO games (id, public_code, status, created_by) VALUES (?, ?, 'WAITING', ?)",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setString(2, code)
                    statement.setObject(3, ownerId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "INSERT INTO game_players (game_id, user_id, joined_order) VALUES (?, ?, 0)",
                ).use { statement ->
                    statement.setObject(1, id)
                    statement.setObject(2, ownerId)
                    statement.executeUpdate()
                }
                checkNotNull(loadGame(connection, code))
            }
        } catch (error: java.sql.SQLException) {
            if (error.sqlState == UNIQUE_VIOLATION) null else throw error
        }
    }

    override suspend fun joinGame(
        code: String,
        userId: UUID,
        shuffledColors: List<PlayerColor>,
    ): JoinGameResult = io {
        transaction { connection ->
            val gameId = connection.prepareStatement(
                "SELECT id FROM games WHERE public_code = ? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@transaction JoinGameResult.Missing
                    rows.getObject("id", UUID::class.java)
                }
            }
            val existing = checkNotNull(loadGame(connection, code))
            if (existing.players.any { it.user.id == userId }) {
                return@transaction JoinGameResult.Joined(existing)
            }
            if (existing.players.size >= PLAYER_COUNT) return@transaction JoinGameResult.Full
            connection.prepareStatement(
                "INSERT INTO game_players (game_id, user_id, joined_order) VALUES (?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, gameId)
                statement.setObject(2, userId)
                statement.setInt(3, existing.players.size)
                statement.executeUpdate()
            }
            if (existing.players.size + 1 == PLAYER_COUNT) {
                shuffledColors.forEachIndexed { order, color ->
                    connection.prepareStatement(
                        "UPDATE game_players SET color = ? WHERE game_id = ? AND joined_order = ?",
                    ).use { statement ->
                        statement.setString(1, color.name)
                        statement.setObject(2, gameId)
                        statement.setInt(3, order)
                        statement.executeUpdate()
                    }
                }
                connection.prepareStatement("UPDATE games SET status = 'ACTIVE' WHERE id = ?").use { statement ->
                    statement.setObject(1, gameId)
                    statement.executeUpdate()
                }
            }
            JoinGameResult.Joined(checkNotNull(loadGame(connection, code)))
        }
    }

    override suspend fun findGame(code: String): GameRecord? = io {
        connection().use { loadGame(it, code) }
    }

    override suspend fun findGamesForUser(userId: UUID): List<GameRecord> = io {
        connection().use { connection ->
            val codes = connection.prepareStatement(
                "SELECT g.public_code FROM games g JOIN game_players p ON p.game_id = g.id WHERE p.user_id = ? ORDER BY g.created_at DESC",
            ).use { statement ->
                statement.setObject(1, userId)
                statement.executeQuery().use { rows ->
                    buildList { while (rows.next()) add(rows.getString("public_code").trim()) }
                }
            }
            codes.mapNotNull { code -> loadGame(connection, code) }
        }
    }

    override suspend fun findGameState(code: String): GameStateRecord? = io {
        readTransaction { connection -> loadGameState(connection, code) }
    }

    override suspend fun submitMove(
        code: String,
        userId: UUID,
        command: GameMoveCommand,
    ): SubmitMoveResult = io {
        transaction { connection ->
            connection.prepareStatement("SELECT id FROM games WHERE public_code = ? FOR UPDATE").use { statement ->
                statement.setString(1, code)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return@transaction SubmitMoveResult.Missing
                }
            }
            val state = checkNotNull(loadGameState(connection, code))
            when (val evaluation = evaluateMove(state, userId, command)) {
                is MoveEvaluation.Accepted -> {
                    connection.prepareStatement(
                        """
                        INSERT INTO game_moves (
                            game_id, revision, command_id, user_id, expected_revision, actor,
                            from_vertex, from_column, from_row, to_vertex, to_column, to_row, promotion
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """.trimIndent(),
                    ).use { statement ->
                        val intent = evaluation.move.intent
                        statement.setObject(1, state.game.id)
                        statement.setInt(2, state.moves.size + 1)
                        statement.setObject(3, evaluation.move.commandId)
                        statement.setObject(4, evaluation.move.userId)
                        statement.setInt(5, evaluation.move.expectedRevision)
                        statement.setString(6, intent.actor.name)
                        statement.setInt(7, intent.from.vertex)
                        statement.setInt(8, intent.from.column)
                        statement.setInt(9, intent.from.row)
                        statement.setInt(10, intent.to.vertex)
                        statement.setInt(11, intent.to.column)
                        statement.setInt(12, intent.to.row)
                        statement.setString(13, intent.promotion?.name)
                        statement.executeUpdate()
                    }
                    if (evaluation.finished) {
                        connection.prepareStatement("UPDATE games SET status = 'FINISHED' WHERE id = ?").use { statement ->
                            statement.setObject(1, state.game.id)
                            statement.executeUpdate()
                        }
                    }
                    connection.prepareStatement("UPDATE games SET revision = revision + 1 WHERE id = ?").use { statement ->
                        statement.setObject(1, state.game.id)
                        statement.executeUpdate()
                    }
                    SubmitMoveResult.Applied(checkNotNull(loadGameState(connection, code)))
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
    }

    override suspend fun requestUndo(
        code: String,
        userId: UUID,
        expectedRevision: Int,
    ): UndoResult = io {
        transaction { connection ->
            val state = lockAndLoadState(connection, code) ?: return@transaction UndoResult.Missing
            if (state.game.players.none { it.user.id == userId }) return@transaction UndoResult.NotParticipant
            if (expectedRevision != state.revision) return@transaction UndoResult.Stale(state)
            if (state.moves.isEmpty()) return@transaction UndoResult.NotAvailable
            if (state.undoRequest != null) return@transaction UndoResult.AlreadyPending
            connection.prepareStatement(
                "INSERT INTO game_undo_requests (game_id, id, requested_by, target_move_count) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                statement.setObject(1, state.game.id)
                statement.setObject(2, UUID.randomUUID())
                statement.setObject(3, userId)
                statement.setInt(4, state.moves.size)
                statement.executeUpdate()
            }
            incrementRevision(connection, state.game.id)
            UndoResult.Updated(checkNotNull(loadGameState(connection, code)))
        }
    }

    override suspend fun voteUndo(
        code: String,
        userId: UUID,
        requestId: UUID,
        expectedRevision: Int,
        approve: Boolean,
    ): UndoResult = io {
        transaction { connection ->
            val state = lockAndLoadState(connection, code) ?: return@transaction UndoResult.Missing
            if (state.game.players.none { it.user.id == userId }) return@transaction UndoResult.NotParticipant
            if (expectedRevision != state.revision) return@transaction UndoResult.Stale(state)
            val request = state.undoRequest?.takeIf { it.id == requestId }
                ?: return@transaction UndoResult.NotAvailable
            if (request.requestedByUserId == userId) return@transaction UndoResult.RequesterCannotVote
            if (userId in request.approvedByUserIds) return@transaction UndoResult.AlreadyVoted
            if (!approve) {
                deleteUndoRequest(connection, request.id)
                incrementRevision(connection, state.game.id)
                return@transaction UndoResult.Updated(checkNotNull(loadGameState(connection, code)))
            }
            connection.prepareStatement(
                "INSERT INTO game_undo_votes (request_id, user_id) VALUES (?, ?)",
            ).use { statement ->
                statement.setObject(1, request.id)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }
            if (request.approvedByUserIds.size + 1 == state.game.players.size - 1) {
                connection.prepareStatement(
                    "DELETE FROM game_moves WHERE game_id = ? AND revision = ?",
                ).use { statement ->
                    statement.setObject(1, state.game.id)
                    statement.setInt(2, request.targetMoveCount)
                    check(statement.executeUpdate() == 1)
                }
                deleteUndoRequest(connection, request.id)
                connection.prepareStatement(
                    "UPDATE games SET status = 'ACTIVE', revision = revision + 1 WHERE id = ?",
                ).use { statement ->
                    statement.setObject(1, state.game.id)
                    statement.executeUpdate()
                }
            } else {
                incrementRevision(connection, state.game.id)
            }
            UndoResult.Updated(checkNotNull(loadGameState(connection, code)))
        }
    }

    private fun loadGameState(connection: Connection, code: String): GameStateRecord? {
        val game = loadGame(connection, code) ?: return null
        val revision = connection.prepareStatement("SELECT revision FROM games WHERE id = ?").use { statement ->
            statement.setObject(1, game.id)
            statement.executeQuery().use { rows -> check(rows.next()); rows.getInt("revision") }
        }
        return GameStateRecord(game, loadMoves(connection, game.id), revision, loadUndoRequest(connection, game.id))
    }

    private fun lockAndLoadState(connection: Connection, code: String): GameStateRecord? {
        connection.prepareStatement("SELECT id FROM games WHERE public_code = ? FOR UPDATE").use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows -> if (!rows.next()) return null }
        }
        return loadGameState(connection, code)
    }

    private fun loadUndoRequest(connection: Connection, gameId: UUID): UndoRequestRecord? =
        connection.prepareStatement(
            "SELECT id, requested_by, target_move_count FROM game_undo_requests WHERE game_id = ?",
        ).use { statement ->
            statement.setObject(1, gameId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                val id = rows.getObject("id", UUID::class.java)
                UndoRequestRecord(
                    id = id,
                    requestedByUserId = rows.getObject("requested_by", UUID::class.java),
                    targetMoveCount = rows.getInt("target_move_count"),
                    approvedByUserIds = connection.prepareStatement(
                        "SELECT user_id FROM game_undo_votes WHERE request_id = ?",
                    ).use { votes ->
                        votes.setObject(1, id)
                        votes.executeQuery().use { voteRows ->
                            buildSet { while (voteRows.next()) add(voteRows.getObject("user_id", UUID::class.java)) }
                        }
                    },
                )
            }
        }

    private fun deleteUndoRequest(connection: Connection, requestId: UUID) {
        connection.prepareStatement("DELETE FROM game_undo_requests WHERE id = ?").use { statement ->
            statement.setObject(1, requestId)
            statement.executeUpdate()
        }
    }

    private fun incrementRevision(connection: Connection, gameId: UUID) {
        connection.prepareStatement("UPDATE games SET revision = revision + 1 WHERE id = ?").use { statement ->
            statement.setObject(1, gameId)
            statement.executeUpdate()
        }
    }

    private fun loadMoves(connection: Connection, gameId: UUID): List<GameMoveRecord> =
        connection.prepareStatement(
            """
            SELECT command_id, user_id, expected_revision, actor,
                   from_vertex, from_column, from_row, to_vertex, to_column, to_row, promotion
            FROM game_moves WHERE game_id = ? ORDER BY revision
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, gameId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            GameMoveRecord(
                                commandId = rows.getObject("command_id", UUID::class.java),
                                userId = rows.getObject("user_id", UUID::class.java),
                                expectedRevision = rows.getInt("expected_revision"),
                                intent = com.chesstree.game.domain.MoveIntent(
                                    actor = com.chesstree.game.domain.PlayerId.valueOf(rows.getString("actor")),
                                    from = com.chesstree.game.domain.BoardCoordinate(
                                        rows.getInt("from_vertex"),
                                        rows.getInt("from_column"),
                                        rows.getInt("from_row"),
                                    ),
                                    to = com.chesstree.game.domain.BoardCoordinate(
                                        rows.getInt("to_vertex"),
                                        rows.getInt("to_column"),
                                        rows.getInt("to_row"),
                                    ),
                                    promotion = rows.getString("promotion")
                                        ?.let(com.chesstree.game.domain.PromotionChoice::valueOf),
                                ),
                            ),
                        )
                    }
                }
            }
        }

    private fun loadGame(connection: Connection, code: String): GameRecord? {
        val game = connection.prepareStatement(
            "SELECT id, public_code, status, created_at FROM games WHERE public_code = ?",
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                GameRecord(
                    id = rows.getObject("id", UUID::class.java),
                    code = rows.getString("public_code").trim(),
                    status = GameStatus.valueOf(rows.getString("status")),
                    players = emptyList(),
                    startedAt = rows.getTimestamp("created_at").toInstant(),
                )
            }
        }
        val players = connection.prepareStatement(
            """
            SELECT u.id, u.username, u.normalized_username, u.password_hash, p.joined_order, p.color
            FROM game_players p JOIN users u ON u.id = p.user_id
            WHERE p.game_id = ? ORDER BY p.joined_order
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, game.id)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(
                            GamePlayer(
                                user = rows.user(),
                                joinedOrder = rows.getInt("joined_order"),
                                color = rows.getString("color")?.let(PlayerColor::valueOf),
                            ),
                        )
                    }
                }
            }
        }
        return game.copy(players = players)
    }

    private fun ResultSet.user() = UserRecord(
        id = getObject("id", UUID::class.java),
        username = getString("username"),
        normalizedUsername = getString("normalized_username"),
        passwordHash = getString("password_hash"),
    )

    private fun connection(): Connection = DriverManager.getConnection(config.url, config.user, config.password)

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun installGameUpdateTriggers(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE OR REPLACE FUNCTION chesstree_notify_game_row_update()
                RETURNS TRIGGER AS ${'$'}${'$'}
                DECLARE
                    updated_game_id UUID;
                BEGIN
                    IF TG_OP = 'DELETE' THEN
                        updated_game_id := OLD.id;
                    ELSE
                        updated_game_id := NEW.id;
                    END IF;
                    PERFORM pg_notify('$GAME_UPDATE_CHANNEL', updated_game_id::TEXT);
                    RETURN NULL;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            statement.execute(
                """
                CREATE OR REPLACE FUNCTION chesstree_notify_game_child_update()
                RETURNS TRIGGER AS ${'$'}${'$'}
                DECLARE
                    updated_game_id UUID;
                BEGIN
                    IF TG_OP = 'DELETE' THEN
                        updated_game_id := OLD.game_id;
                    ELSE
                        updated_game_id := NEW.game_id;
                    END IF;
                    PERFORM pg_notify('$GAME_UPDATE_CHANNEL', updated_game_id::TEXT);
                    RETURN NULL;
                END;
                ${'$'}${'$'} LANGUAGE plpgsql
                """.trimIndent(),
            )
            listOf(
                Triple("games", "chesstree_games_notify_update", "chesstree_notify_game_row_update"),
                Triple("game_players", "chesstree_game_players_notify_update", "chesstree_notify_game_child_update"),
                Triple("game_moves", "chesstree_game_moves_notify_update", "chesstree_notify_game_child_update"),
                Triple("game_undo_requests", "chesstree_game_undo_requests_notify_update", "chesstree_notify_game_child_update"),
            ).forEach { (table, trigger, function) ->
                statement.execute(
                    """
                    DO ${'$'}${'$'}
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1 FROM pg_trigger
                            WHERE tgname = '$trigger' AND tgrelid = '$table'::regclass
                        ) THEN
                            CREATE TRIGGER $trigger
                            AFTER INSERT OR UPDATE OR DELETE ON $table
                            FOR EACH ROW EXECUTE FUNCTION $function();
                        END IF;
                    END;
                    ${'$'}${'$'}
                    """.trimIndent(),
                )
            }
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun <T> readTransaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
        connection.isReadOnly = true
        connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val PLAYER_COUNT = 3
        const val SCHEMA_VERSION = 4
        const val SCHEMA_LOCK_KEY = 0x4348455353545245L
    }
}
