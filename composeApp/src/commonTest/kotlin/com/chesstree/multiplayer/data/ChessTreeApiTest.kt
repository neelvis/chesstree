package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.GameResponse
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

    private fun api(engine: MockEngine): KtorChessTreeApi = KtorChessTreeApi(
        serverBaseUrl = "https://server.test",
        client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { explicitNulls = false }) }
        },
    )

    private companion object {
        val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")
        const val GAME_JSON =
            """{"id":"game-id","code":"ABC1234","shareUrl":"https://play.test/g/ABC1234","status":"WAITING","players":[]}"""
    }
}
