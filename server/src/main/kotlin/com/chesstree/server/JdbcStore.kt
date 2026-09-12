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
                try {
                    statement.executeUpdate("INSERT INTO schema_metadata (singleton, version) VALUES (TRUE, 1)")
                } catch (error: java.sql.SQLException) {
                    if (error.sqlState != UNIQUE_VIOLATION) throw error
                }
                statement.executeQuery("SELECT version FROM schema_metadata WHERE singleton = TRUE").use { rows ->
                    check(rows.next() && rows.getInt("version") == SCHEMA_VERSION) {
                        "Unsupported database schema version"
                    }
                }
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

    private fun loadGame(connection: Connection, code: String): GameRecord? {
        val game = connection.prepareStatement(
            "SELECT id, public_code, status FROM games WHERE public_code = ?",
        ).use { statement ->
            statement.setString(1, code)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                Triple(
                    rows.getObject("id", UUID::class.java),
                    rows.getString("public_code").trim(),
                    GameStatus.valueOf(rows.getString("status")),
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
            statement.setObject(1, game.first)
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
        return GameRecord(game.first, game.second, game.third, players)
    }

    private fun ResultSet.user() = UserRecord(
        id = getObject("id", UUID::class.java),
        username = getString("username"),
        normalizedUsername = getString("normalized_username"),
        passwordHash = getString("password_hash"),
    )

    private fun connection(): Connection = DriverManager.getConnection(config.url, config.user, config.password)

    private fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
        connection.autoCommit = false
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
        const val SCHEMA_VERSION = 1
    }
}
