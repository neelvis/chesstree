package com.chesstree.server

import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcStoreTest {
    @Test
    fun persistsUsersSessionsAndLobbyTransitions() = runBlocking {
        val store = JdbcStore(
            DatabaseConfig(
                url = "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            ),
        )
        store.initialize()
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
    }

    private suspend fun JdbcStore.user(username: String): UserRecord =
        assertIs<CreateUserResult.Created>(
            createUser(username, username.lowercase(), "hash"),
        ).user
}
