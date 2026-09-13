package com.chesstree.server

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameSocketAuthRequest
import com.chesstree.multiplayer.contract.GameStatePush
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.scenario.StandardGame
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
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

    @Test
    fun serverValidatesMovesAndDeduplicatesRetriedCommands() = testApplication {
        application { chessTreeModule(testServices()) }
        val users = listOf(
            register("owner", "correct-horse"),
            register("second", "correct-horse"),
            register("third", "correct-horse"),
        )
        val game = createGame(users[0].accessToken)
        join(game.code, users[1].accessToken)
        val active = join(game.code, users[2].accessToken)
        val whiteUsername = active.players.single { it.color == "WHITE" }.user.username
        val white = users.single { it.user.username == whiteUsername }
        val redUsername = active.players.single { it.color == "RED" }.user.username
        val red = users.single { it.user.username == redUsername }
        val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()
        val command = MoveCommandRequest(
            commandId = UUID.randomUUID().toString(),
            expectedRevision = 0,
            from = move.from.response(),
            to = move.to.response(),
            promotion = move.promotion?.name,
        )

        val malformed = postMove(
            game.code,
            white.accessToken,
            command.copy(commandId = UUID.randomUUID().toString(), to = command.from),
        )
        assertEquals(HttpStatusCode.BadRequest, malformed.status)

        val wrongTurn = postMove(game.code, red.accessToken, command)
        assertEquals(HttpStatusCode.Conflict, wrongTurn.status)
        assertTrue(wrongTurn.bodyAsText().contains("not_your_turn"))

        val occupiedTarget = StandardGame.pieces.first {
            it.army.name == "WHITE" && it.coordinate != move.from
        }.coordinate.response()
        val illegal = postMove(
            game.code,
            white.accessToken,
            command.copy(commandId = UUID.randomUUID().toString(), to = occupiedTarget),
        )
        assertEquals(HttpStatusCode.UnprocessableEntity, illegal.status)
        assertTrue(illegal.bodyAsText().contains("illegal_move"))

        val applied = submitMove(game.code, white.accessToken, command)
        assertEquals(1, applied.revision)
        assertEquals("WHITE", applied.moves.single().actor)
        assertEquals(1, submitMove(game.code, white.accessToken, command).revision)

        val stale = client.post("/api/v1/games/${game.code}/moves") {
            bearerAuth(white.accessToken)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(command.copy(commandId = UUID.randomUUID().toString())))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue(stale.bodyAsText().contains("stale_revision"))
    }

    @Test
    fun participantReceivesLobbyChangesThroughWebSocket() = testApplication {
        application { chessTreeModule(testServices()) }
        val socketClient = createClient {
            install(WebSockets) {
                contentConverter = KotlinxWebsocketSerializationConverter(json)
            }
        }
        val owner = register("owner", "correct-horse")
        val second = register("second", "correct-horse")
        val third = register("third", "correct-horse")
        val game = createGame(owner.accessToken)
        join(game.code, second.accessToken)

        socketClient.webSocket("/api/v1/games/${game.code}/events") {
            sendSerialized(GameSocketAuthRequest(owner.accessToken))
            val waiting = receiveDeserialized<GameStatePush>()
            assertEquals("WAITING", waiting.state.game.status)

            val activeGame = join(game.code, third.accessToken)
            val active = receiveDeserialized<GameStatePush>()
            assertEquals("ACTIVE", active.state.game.status)
            assertEquals(3, active.state.game.players.size)

            val players = listOf(owner, second, third)
            val whiteName = activeGame.players.single { it.color == "WHITE" }.user.username
            val white = players.single { it.user.username == whiteName }
            val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()
            submitMove(
                game.code,
                white.accessToken,
                MoveCommandRequest(
                    commandId = UUID.randomUUID().toString(),
                    expectedRevision = 0,
                    from = move.from.response(),
                    to = move.to.response(),
                    promotion = move.promotion?.name,
                ),
            )
            assertEquals(1, receiveDeserialized<GameStatePush>().state.revision)
        }
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

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.submitMove(
        code: String,
        token: String,
        command: MoveCommandRequest,
    ): GameStateResponse {
        val response = postMove(code, token, command)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return json.decodeFromString(response.bodyAsText())
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.postMove(
        code: String,
        token: String,
        command: MoveCommandRequest,
    ) = client.post("/api/v1/games/$code/moves") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(command))
    }

    private fun com.chesstree.game.domain.BoardCoordinate.response() = CoordinateResponse(vertex, column, row)

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
