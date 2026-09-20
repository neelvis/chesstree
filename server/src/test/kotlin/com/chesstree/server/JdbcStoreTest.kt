package com.chesstree.server

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.scenario.StandardGame
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcStoreTest {
    @Test
    fun migratesVersionTwoDatabaseToCurrentSchema() = runBlocking {
        val databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE schema_metadata (singleton BOOLEAN PRIMARY KEY, version INTEGER NOT NULL)",
                )
                statement.executeUpdate("INSERT INTO schema_metadata (singleton, version) VALUES (TRUE, 2)")
            }
        }

        JdbcStore(DatabaseConfig(databaseUrl, "sa", "")).initialize()

        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_metadata WHERE singleton = TRUE").use { rows ->
                    rows.next()
                    assertEquals(4, rows.getInt("version"))
                }
            }
        }
    }

    @Test
    fun migratesVersionOneDatabaseToCurrentSchema() = runBlocking {
        val databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    "CREATE TABLE schema_metadata (singleton BOOLEAN PRIMARY KEY, version INTEGER NOT NULL)",
                )
                statement.executeUpdate("INSERT INTO schema_metadata (singleton, version) VALUES (TRUE, 1)")
            }
        }

        JdbcStore(DatabaseConfig(databaseUrl, "sa", "")).initialize()

        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_metadata WHERE singleton = TRUE").use { rows ->
                    rows.next()
                    assertEquals(4, rows.getInt("version"))
                }
                statement.executeQuery("SELECT COUNT(*) FROM game_moves").use { rows ->
                    rows.next()
                    assertEquals(0, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun persistsUsersSessionsAndLobbyTransitions() = runBlocking {
        val databaseUrl = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
        val store = JdbcStore(
            DatabaseConfig(
                url = databaseUrl,
                user = "sa",
                password = "",
            ),
        )
        store.initialize()
        DriverManager.getConnection(databaseUrl, "sa", "").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT version FROM schema_metadata WHERE singleton = TRUE").use { rows ->
                    rows.next()
                    assertEquals(4, rows.getInt("version"))
                }
            }
        }
        val first = store.user("First")
        val second = store.user("Second")
        val third = store.user("Third")
        val duplicate = store.createUser("FIRST", "first", "hash")
        assertIs<CreateUserResult.UsernameTaken>(duplicate)

        store.saveSession("a".repeat(64), first.id, Instant.parse("2030-01-01T00:00:00Z"))
        assertEquals(first.id, store.findSession("a".repeat(64), Instant.parse("2029-01-01T00:00:00Z"))?.user?.id)
        assertNull(store.findSession("a".repeat(64), Instant.parse("2031-01-01T00:00:00Z")))

        val game = assertNotNull(store.createGame(UUID.randomUUID(), "ABC1234", first.id))
        assertEquals(GameStatus.WAITING, game.status)
        assertIs<JoinGameResult.Joined>(store.joinGame("ABC1234", second.id, PlayerColor.entries))
        val active = assertIs<JoinGameResult.Joined>(
            store.joinGame("ABC1234", third.id, PlayerColor.entries),
        ).game
        assertEquals(GameStatus.ACTIVE, active.status)
        assertEquals(PlayerColor.entries.toSet(), active.players.mapNotNull { it.color }.toSet())

        val legal = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()
        val command = GameMoveCommand(
            commandId = UUID.randomUUID(),
            expectedRevision = 0,
            from = legal.from,
            to = legal.to,
            promotion = legal.promotion,
        )
        val competingCommand = command.copy(commandId = UUID.randomUUID())
        val competingResults = coroutineScope {
            listOf(command, competingCommand).map { candidate ->
                async { store.submitMove("ABC1234", first.id, candidate) }
            }.awaitAll()
        }
        assertEquals(1, competingResults.count { it is SubmitMoveResult.Applied })
        assertEquals(1, competingResults.count { it is SubmitMoveResult.Stale })
        val acceptedCommandId = assertNotNull(store.findGameState("ABC1234")).moves.single().commandId
        val acceptedCommand = listOf(command, competingCommand).single { it.commandId == acceptedCommandId }
        assertEquals(
            1,
            assertIs<SubmitMoveResult.Applied>(
                store.submitMove("ABC1234", first.id, acceptedCommand),
            ).state.moves.size,
        )
        assertIs<SubmitMoveResult.Stale>(
            store.submitMove("ABC1234", first.id, command.copy(commandId = UUID.randomUUID())),
        )

        val requested = assertIs<UndoResult.Updated>(
            store.requestUndo("ABC1234", first.id, expectedRevision = 1),
        ).state
        val requestId = assertNotNull(requested.undoRequest).id
        val oneApproval = assertIs<UndoResult.Updated>(
            store.voteUndo("ABC1234", second.id, requestId, requested.revision, approve = true),
        ).state
        val undone = assertIs<UndoResult.Updated>(
            store.voteUndo("ABC1234", third.id, requestId, oneApproval.revision, approve = true),
        ).state
        assertEquals(0, undone.moves.size)
        assertNull(undone.undoRequest)
        assertEquals(4, undone.revision)
    }

    private suspend fun JdbcStore.user(username: String): UserRecord =
        assertIs<CreateUserResult.Created>(
            createUser(username, username.lowercase(), "hash"),
        ).user
}
