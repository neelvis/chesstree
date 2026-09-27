package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoVoteCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ChessTreeApiTest {
    @Test
    fun createGameSendsBearerTokenAndDecodesContract() = runTest {
        val engine = MockEngine { request ->
            assertEquals("Bearer session-token", request.headers[HttpHeaders.Authorization])
            assertEquals("https://server.test/api/v1/games", request.url.toString())
            respond(
                content = GAME_JSON,
                status = HttpStatusCode.Created,
                headers = JSON_HEADERS,
            )
        }

        val result = api(engine).createGame("session-token")

        assertEquals("ABC1234", assertIs<ApiResult.Success<GameResponse>>(result).value.code)
    }

    @Test
    fun browserAuthUsesCookieRoutesAndNeverSendsBearerHeaders() = runTest {
        val visited = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertNull(request.headers[HttpHeaders.Authorization])
            visited += "${request.method.value} ${request.url}"
            when {
                request.url.encodedPath.endsWith("/auth/browser/register") -> respond(
                    content = """{"user":{"id":"user-id","username":"Alice"}}""",
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )

                request.url.encodedPath.endsWith("/auth/browser/session") -> respond(
                    content = """{"user":{"id":"user-id","username":"Alice"}}""",
                    status = HttpStatusCode.OK,
                    headers = JSON_HEADERS,
                )

                request.url.encodedPath.endsWith("/auth/browser/logout") -> respond(
                    "",
                    HttpStatusCode.NoContent,
                )

                else -> error("Unexpected browser request: ${request.url.encodedPath}")
            }
        }
        val api = api(engine, browserSession = true)

        val registered = assertIs<ApiResult.Success<com.chesstree.multiplayer.contract.AuthResponse>>(
            api.register("Alice", "correct-horse"),
        ).value
        assertEquals("", registered.accessToken)
        assertEquals("Alice", registered.user.username)
        assertEquals("Alice", assertIs<ApiResult.Success<com.chesstree.multiplayer.contract.BrowserAuthResponse>>(
            api.restoreBrowserSession(),
        ).value.user.username)
        assertIs<ApiResult.Success<Unit>>(api.logout(registered.accessToken))

        assertEquals(
            listOf(
                "POST https://server.test/api/v1/auth/browser/register",
                "GET https://server.test/api/v1/auth/browser/session",
                "POST https://server.test/api/v1/auth/browser/logout",
            ),
            visited,
        )
    }

    @Test
    fun serverErrorIsMappedToTypedFailure() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"game_full","message":"В игре уже три участника"}""",
                status = HttpStatusCode.Conflict,
                headers = JSON_HEADERS,
            )
        }

        val result = api(engine).joinGame("session-token", "abc1234")

        val failure = assertIs<ApiResult.Failure>(result)
        assertEquals("game_full", failure.code)
        assertEquals("В игре уже три участника", failure.message)
    }

    @Test
    fun transportFailureDoesNotLeakPlatformException() = runTest {
        val engine = MockEngine { error("offline") }

        val result = api(engine).login("alice", "correct-horse")

        assertEquals("network_error", assertIs<ApiResult.Failure>(result).code)
    }

    @Test
    fun submitMoveUsesVersionedGameEndpoint() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://server.test/api/v1/games/ABC1234/moves", request.url.toString())
            respond(
                content = """{"game":$GAME_JSON,"revision":0,"moves":[]}""",
                status = HttpStatusCode.OK,
                headers = JSON_HEADERS,
            )
        }
        val command = MoveCommandRequest(
            commandId = "00000000-0000-0000-0000-000000000001",
            expectedRevision = 0,
            from = CoordinateResponse(0, 0, 0),
            to = CoordinateResponse(0, 0, 1),
        )

        val result = api(engine).submitMove("session-token", "abc1234", command)

        assertEquals(0, assertIs<ApiResult.Success<GameStateResponse>>(result).value.revision)
    }

    @Test
    fun pushDevicesUseAuthenticatedRegistrationAndRemovalEndpoints() = runTest {
        val visited = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("Bearer session-token", request.headers[HttpHeaders.Authorization])
            visited += request.url.toString()
            respond("", HttpStatusCode.NoContent)
        }

        val api = api(engine)
        assertIs<ApiResult.Success<Unit>>(
            api.registerPushDevice("session-token", "fcm-device-token", "ANDROID"),
        )
        assertIs<ApiResult.Success<Unit>>(
            api.unregisterPushDevice("session-token", "fcm-device-token"),
        )

        assertEquals(
            listOf(
                "https://server.test/api/v1/push/devices",
                "https://server.test/api/v1/push/devices/unregister",
            ),
            visited,
        )
    }

    @Test
    fun undoRequestAndVoteUseTheGameScopedEndpoints() = runTest {
        val visited = mutableListOf<String>()
        val engine = MockEngine { request ->
            visited += request.url.toString()
            respond(
                content = """{"game":$GAME_JSON,"revision":3,"moves":[]}""",
                status = HttpStatusCode.OK,
                headers = JSON_HEADERS,
            )
        }
        val api = api(engine)
        val requestId = "00000000-0000-0000-0000-000000000001"

        api.requestUndo("session-token", "abc1234", UndoRequestCommand(1))
        api.voteUndo("session-token", "abc1234", UndoVoteCommand(2, requestId, true))

        assertEquals(
            listOf(
                "https://server.test/api/v1/games/ABC1234/undo-requests",
                "https://server.test/api/v1/games/ABC1234/undo-requests/$requestId/votes",
            ),
            visited,
        )
    }

    private fun api(engine: MockEngine, browserSession: Boolean = false): KtorChessTreeApi = KtorChessTreeApi(
        serverBaseUrl = "https://server.test",
        client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { explicitNulls = false }) }
        },
        browserSession = browserSession,
    )

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val GAME_JSON =
            """{"id":"game-id","code":"ABC1234","shareUrl":"https://play.test/g/ABC1234","status":"WAITING","players":[]}"""
    }
}
