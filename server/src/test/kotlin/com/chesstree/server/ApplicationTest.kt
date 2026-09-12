package com.chesstree.server

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.GameResponse
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun registrationIsCaseInsensitiveAndLoginUsesGenericFailure() = testApplication {
        application { chessTreeModule(testServices()) }

        val registered = register("Alice", "correct-horse")
        assertEquals("Alice", registered.user.username)

        val duplicate = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(credentials("alice", "another-pass"))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        val failedLogin = client.post("/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(credentials("missing", "correct-horse"))
        }
        assertEquals(HttpStatusCode.Unauthorized, failedLogin.status)
        assertTrue(failedLogin.bodyAsText().contains("Неверный логин или пароль"))
    }

    @Test
    fun gameRequiresAuthenticationAndReturnsShareableSevenCharacterCode() = testApplication {
        application { chessTreeModule(testServices()) }

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/games").status)
        val owner = register("owner", "correct-horse")
        val response = client.post("/api/v1/games") { bearerAuth(owner.accessToken) }

        assertEquals(HttpStatusCode.Created, response.status)
        val game = json.decodeFromString<GameResponse>(response.bodyAsText())
        assertTrue(game.code.matches(Regex("[0-9A-HJKMNP-TV-Z]{7}")))
        assertEquals("https://play.test/g/${game.code}", game.shareUrl)
        assertEquals("WAITING", game.status)
        assertEquals(listOf("owner"), game.players.map { it.user.username })
    }

    @Test
    fun thirdDistinctPlayerStartsGameWithUniqueColors() = testApplication {
        application { chessTreeModule(testServices()) }
        val owner = register("owner", "correct-horse")
        val second = register("second", "correct-horse")
        val third = register("third", "correct-horse")
        val fourth = register("fourth", "correct-horse")
        val game = createGame(owner.accessToken)

        join(game.code, second.accessToken).also { assertEquals("WAITING", it.status) }
        val active = join(game.code, third.accessToken)
        assertEquals("ACTIVE", active.status)
        assertEquals(setOf("WHITE", "RED", "BLACK"), active.players.mapNotNull { it.color }.toSet())

        val full = client.post("/api/v1/games/${game.code}/join") { bearerAuth(fourth.accessToken) }
        assertEquals(HttpStatusCode.Conflict, full.status)
    }

    @Test
    fun joiningTwiceIsIdempotentAndGameIsPrivateToParticipants() = testApplication {
        application { chessTreeModule(testServices()) }
        val owner = register("owner", "correct-horse")
        val player = register("player", "correct-horse")
        val outsider = register("outsider", "correct-horse")
        val game = createGame(owner.accessToken)

        assertEquals(2, join(game.code, player.accessToken).players.size)
        assertEquals(2, join(game.code, player.accessToken).players.size)
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/games/${game.code}") { bearerAuth(outsider.accessToken) }.status,
        )
    }

    @Test
    fun logoutRevokesSession() = testApplication {
        application { chessTreeModule(testServices()) }
        val user = register("owner", "correct-horse")
        assertEquals(
            HttpStatusCode.NoContent,
            client.post("/api/v1/auth/logout") { bearerAuth(user.accessToken) }.status,
        )
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/api/v1/games") { bearerAuth(user.accessToken) }.status,
        )
    }

    @Test
    fun configuredWebOriginCanCallAuthenticatedApi() = testApplication {
        application { chessTreeModule(testServices(), allowedCorsHosts = listOf("localhost:8080")) }

        val response = client.options("/api/v1/games") {
            header(HttpHeaders.Origin, "http://localhost:8080")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, HttpHeaders.Authorization)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://localhost:8080", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.register(
        username: String,
        password: String,
    ): AuthResponse {
        val response = client.post("/api/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(credentials(username, password))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.createGame(token: String): GameResponse {
        val response = client.post("/api/v1/games") { bearerAuth(token) }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.join(code: String, token: String): GameResponse {
        val response = client.post("/api/v1/games/$code/join") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private fun testServices(): ServerServices {
        val store = InMemoryStore()
        val tokens = TokenGenerator()
        return ServerServices(
            store = store,
            auth = AuthService(store, FakePasswordHasher, tokens),
            tokens = tokens,
            publicBaseUrl = "https://play.test",
        )
    }

    private fun credentials(username: String, password: String): String =
        """{"username":"$username","password":"$password"}"""

    private object FakePasswordHasher : PasswordHasher {
        override fun hash(password: CharArray): String = "fake:${password.concatToString()}"
        override fun verify(encoded: String, password: CharArray): Boolean = encoded == hash(password)
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = false }
    }
}
