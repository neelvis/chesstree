package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.ErrorResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.LoginRequest
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.RegisterRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

interface ChessTreeApi {
    suspend fun register(username: String, password: String): ApiResult<AuthResponse>
    suspend fun login(username: String, password: String): ApiResult<AuthResponse>
    suspend fun logout(token: String): ApiResult<Unit>
    suspend fun createGame(token: String): ApiResult<GameResponse>
    suspend fun joinGame(token: String, code: String): ApiResult<GameResponse>
    suspend fun getGame(token: String, code: String): ApiResult<GameResponse>
    suspend fun getGameState(token: String, code: String): ApiResult<GameStateResponse>
    suspend fun submitMove(
        token: String,
        code: String,
        command: MoveCommandRequest,
    ): ApiResult<GameStateResponse>
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val code: String, val message: String) : ApiResult<Nothing>
}

class KtorChessTreeApi(
    serverBaseUrl: String,
    private val client: HttpClient = defaultHttpClient(),
) : ChessTreeApi {
    private val apiBaseUrl = "${serverBaseUrl.trimEnd('/')}/api/v1"

    override suspend fun register(username: String, password: String): ApiResult<AuthResponse> = request {
        client.post("$apiBaseUrl/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(username, password))
        }.decode()
    }

    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> = request {
        client.post("$apiBaseUrl/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username, password))
        }.decode()
    }

    override suspend fun logout(token: String): ApiResult<Unit> = request {
        val response = client.post("$apiBaseUrl/auth/logout") { bearerAuth(token) }
        if (response.status == HttpStatusCode.NoContent) ApiResult.Success(Unit) else response.failure()
    }

    override suspend fun createGame(token: String): ApiResult<GameResponse> = request {
        client.post("$apiBaseUrl/games") { bearerAuth(token) }.decode()
    }

    override suspend fun joinGame(token: String, code: String): ApiResult<GameResponse> = request {
        client.post("$apiBaseUrl/games/${code.trim().uppercase()}/join") { bearerAuth(token) }.decode()
    }

    override suspend fun getGame(token: String, code: String): ApiResult<GameResponse> = request {
        client.get("$apiBaseUrl/games/${code.trim().uppercase()}") { bearerAuth(token) }.decode()
    }

    override suspend fun getGameState(token: String, code: String): ApiResult<GameStateResponse> = request {
        client.get("$apiBaseUrl/games/${code.trim().uppercase()}/state") { bearerAuth(token) }.decode()
    }

    override suspend fun submitMove(
        token: String,
        code: String,
        command: MoveCommandRequest,
    ): ApiResult<GameStateResponse> = request {
        client.post("$apiBaseUrl/games/${code.trim().uppercase()}/moves") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()
    }

    fun close() = client.close()

    private suspend inline fun <T> request(block: suspend () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        ApiResult.Failure("network_error", "Не удалось связаться с сервером")
    }

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.decode(): ApiResult<T> =
        if (status.value in 200..299) ApiResult.Success(body()) else failure()

    private suspend fun io.ktor.client.statement.HttpResponse.failure(): ApiResult.Failure =
        runCatching { body<ErrorResponse>() }
            .getOrNull()
            ?.let { ApiResult.Failure(it.code, it.message) }
            ?: ApiResult.Failure("http_${status.value}", "Сервер отклонил запрос")

    companion object {
        fun defaultHttpClient(): HttpClient = HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false; explicitNulls = false })
            }
        }
    }
}
