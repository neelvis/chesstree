package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.API_VERSION
import com.chesstree.multiplayer.contract.API_VERSION_HEADER
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.BrowserAuthResponse
import com.chesstree.multiplayer.contract.CreateBotGameRequest
import com.chesstree.multiplayer.contract.ErrorResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameSocketAuthRequest
import com.chesstree.multiplayer.contract.GameStatePush
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.LoginRequest
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.PushDeviceRegistrationRequest
import com.chesstree.multiplayer.contract.PushDeviceRemovalRequest
import com.chesstree.multiplayer.contract.RegisterRequest
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoVoteCommand
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

interface ChessTreeApi {
    suspend fun register(username: String, password: String): ApiResult<AuthResponse>
    suspend fun login(username: String, password: String): ApiResult<AuthResponse>
    suspend fun logout(token: String): ApiResult<Unit>
    suspend fun restoreBrowserSession(): ApiResult<BrowserAuthResponse> =
        ApiResult.Failure("unsupported", "i18n:login_restore_failed")
    suspend fun registerPushDevice(
        sessionToken: String,
        deviceToken: String,
        platform: String,
    ): ApiResult<Unit> = ApiResult.Failure("unsupported", "i18n:push_unavailable")
    suspend fun unregisterPushDevice(token: String, deviceToken: String): ApiResult<Unit> =
        ApiResult.Failure("unsupported", "i18n:push_unavailable")
    suspend fun createGame(token: String): ApiResult<GameResponse>
    suspend fun createBotGame(token: String, botCount: Int): ApiResult<GameResponse> =
        ApiResult.Failure("unsupported", "i18n:online_bot_unavailable")
    suspend fun getMyGames(token: String): ApiResult<List<GameHistoryResponse>> =
        ApiResult.Failure("unsupported", "i18n:history_unavailable")

    suspend fun joinGame(token: String, code: String): ApiResult<GameResponse>
    suspend fun getGame(token: String, code: String): ApiResult<GameResponse>
    suspend fun getGameState(token: String, code: String): ApiResult<GameStateResponse>
    suspend fun getGameState(
        token: String,
        code: String,
        afterMoveCount: Int,
    ): ApiResult<GameStateResponse> = getGameState(token, code)
    fun observeGame(token: String, code: String): Flow<ApiResult<GameStateResponse>>
    suspend fun submitMove(
        token: String,
        code: String,
        command: MoveCommandRequest,
    ): ApiResult<GameStateResponse>

    suspend fun requestUndo(
        token: String,
        code: String,
        command: UndoRequestCommand,
    ): ApiResult<GameStateResponse> = ApiResult.Failure("unsupported", "i18n:undo_unavailable")

    suspend fun voteUndo(
        token: String,
        code: String,
        command: UndoVoteCommand,
    ): ApiResult<GameStateResponse> = ApiResult.Failure("unsupported", "i18n:vote_unavailable")
}

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val code: String, val message: String) : ApiResult<Nothing>
}

class KtorChessTreeApi(
    serverBaseUrl: String,
    client: HttpClient? = null,
    private val browserSession: Boolean = false,
) : ChessTreeApi {
    private val client: HttpClient = client ?: defaultHttpClient(browserSession)
    private val apiBaseUrl = "${serverBaseUrl.trimEnd('/')}/api/v1"
    private val socketApiBaseUrl = serverBaseUrl.trimEnd('/').toWebSocketUrl() + "/api/v1"

    override suspend fun register(username: String, password: String): ApiResult<AuthResponse> =
        request {
            client.post("$apiBaseUrl/auth/${if (browserSession) "browser/" else ""}register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest(username, password))
            }.let { response ->
                if (browserSession) response.decode<BrowserAuthResponse>().mapBrowserAuth()
                else response.decode()
            }
        }

    override suspend fun login(username: String, password: String): ApiResult<AuthResponse> =
        request {
            client.post("$apiBaseUrl/auth/${if (browserSession) "browser/" else ""}login") {
                contentType(ContentType.Application.Json)
                setBody(LoginRequest(username, password))
            }.let { response ->
                if (browserSession) response.decode<BrowserAuthResponse>().mapBrowserAuth()
                else response.decode()
            }
        }

    override suspend fun logout(token: String): ApiResult<Unit> = request {
        val response = client.post("$apiBaseUrl/auth/${if (browserSession) "browser/" else ""}logout") {
            if (!browserSession) bearerAuth(token)
        }
        if (response.status == HttpStatusCode.NoContent) ApiResult.Success(Unit) else response.failure()
    }

    override suspend fun restoreBrowserSession(): ApiResult<BrowserAuthResponse> = request {
        client.get("$apiBaseUrl/auth/browser/session").decode()
    }

    override suspend fun registerPushDevice(
        sessionToken: String,
        deviceToken: String,
        platform: String,
    ): ApiResult<Unit> = request {
        val response = client.post("$apiBaseUrl/push/devices") {
            authorize(sessionToken)
            contentType(ContentType.Application.Json)
            setBody(PushDeviceRegistrationRequest(token = deviceToken, platform = platform))
        }
        if (response.status == HttpStatusCode.NoContent) ApiResult.Success(Unit) else response.failure()
    }

    override suspend fun unregisterPushDevice(token: String, deviceToken: String): ApiResult<Unit> = request {
        val response = client.post("$apiBaseUrl/push/devices/unregister") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(PushDeviceRemovalRequest(deviceToken))
        }
        if (response.status == HttpStatusCode.NoContent) ApiResult.Success(Unit) else response.failure()
    }

    override suspend fun createGame(token: String): ApiResult<GameResponse> = request {
        client.post("$apiBaseUrl/games") { authorize(token) }.decode()
    }

    override suspend fun createBotGame(token: String, botCount: Int): ApiResult<GameResponse> = request {
        client.post("$apiBaseUrl/games/bots") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(CreateBotGameRequest(botCount))
        }.decode()
    }

    override suspend fun getMyGames(token: String): ApiResult<List<GameHistoryResponse>> = request {
        client.get("$apiBaseUrl/games") { authorize(token) }.decode()
    }

    override suspend fun joinGame(token: String, code: String): ApiResult<GameResponse> = request {
        client.post("$apiBaseUrl/games/${code.trim().uppercase()}/join") { authorize(token) }
            .decode()
    }

    override suspend fun getGame(token: String, code: String): ApiResult<GameResponse> = request {
        client.get("$apiBaseUrl/games/${code.trim().uppercase()}") { authorize(token) }.decode()
    }

    override suspend fun getGameState(token: String, code: String): ApiResult<GameStateResponse> =
        getGameState(token, code, afterMoveCount = 0)

    override suspend fun getGameState(
        token: String,
        code: String,
        afterMoveCount: Int,
    ): ApiResult<GameStateResponse> =
        request {
            client.get("$apiBaseUrl/games/${code.trim().uppercase()}/state") {
                authorize(token)
                header(API_VERSION_HEADER, API_VERSION)
                if (afterMoveCount > 0) url.parameters.append("afterMoveCount", afterMoveCount.toString())
            }.decode()
        }

    override fun observeGame(token: String, code: String): Flow<ApiResult<GameStateResponse>> =
        flow {
            var retryDelayMillis = 1_000L
            while (currentCoroutineContext().isActive) {
                var protocolSupported = true
                try {
                    client.webSocket("$socketApiBaseUrl/games/${code.trim().uppercase()}/events") {
                        sendSerialized(GameSocketAuthRequest(if (browserSession) "" else token, API_VERSION))
                        retryDelayMillis = 1_000L
                        while (currentCoroutineContext().isActive) {
                            val push = receiveDeserialized<GameStatePush>()
                            if (push.protocolVersion != API_VERSION) {
                                emit(
                                    ApiResult.Failure(
                                        "protocol_mismatch",
                                        "i18n:update_required"
                                    )
                                )
                                protocolSupported = false
                                return@webSocket
                            }
                            emit(ApiResult.Success(push.state))
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                }
                if (!protocolSupported) return@flow
                emit(
                    ApiResult.Failure(
                        "connection_lost",
                        "i18n:connection_lost"
                    )
                )
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(10_000L)
            }
        }

    override suspend fun submitMove(
        token: String,
        code: String,
        command: MoveCommandRequest,
    ): ApiResult<GameStateResponse> = request {
        client.post("$apiBaseUrl/games/${code.trim().uppercase()}/moves") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()
    }

    override suspend fun requestUndo(
        token: String,
        code: String,
        command: UndoRequestCommand,
    ): ApiResult<GameStateResponse> = request {
        client.post("$apiBaseUrl/games/${code.trim().uppercase()}/undo-requests") {
            authorize(token)
            contentType(ContentType.Application.Json)
            setBody(command)
        }.decode()
    }

    override suspend fun voteUndo(
        token: String,
        code: String,
        command: UndoVoteCommand,
    ): ApiResult<GameStateResponse> = request {
        client.post(
            "$apiBaseUrl/games/${
                code.trim().uppercase()
            }/undo-requests/${command.requestId}/votes"
        ) {
            authorize(token)
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
        ApiResult.Failure("network_error", "i18n:network_unavailable")
    }

    private suspend inline fun <reified T> io.ktor.client.statement.HttpResponse.decode(): ApiResult<T> =
        if (status.value in 200..299) ApiResult.Success(body()) else failure()

    private fun io.ktor.client.request.HttpRequestBuilder.authorize(token: String) {
        if (!browserSession) bearerAuth(token)
    }

    private suspend fun io.ktor.client.statement.HttpResponse.failure(): ApiResult.Failure =
        runCatching { body<ErrorResponse>() }
            .getOrNull()
            ?.let { ApiResult.Failure(it.code, it.message) }
            ?: ApiResult.Failure("http_${status.value}", "i18n:server_rejected")

    companion object {
        fun defaultHttpClient(browserSession: Boolean = false): HttpClient =
            HttpClient(defaultHttpClientEngine(browserSession)) {
                expectSuccess = false
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = false; explicitNulls = false })
                }
                install(WebSockets) {
                    contentConverter =
                        io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter(
                            Json { ignoreUnknownKeys = true; explicitNulls = false },
                        )
                }
            }
    }
}

private fun ApiResult<BrowserAuthResponse>.mapBrowserAuth(): ApiResult<AuthResponse> = when (this) {
    is ApiResult.Success -> ApiResult.Success(AuthResponse(accessToken = "", user = value.user))
    is ApiResult.Failure -> this
}

internal expect fun defaultHttpClientEngine(browserSession: Boolean): HttpClientEngine

private fun String.toWebSocketUrl(): String = when {
    startsWith("https://") -> "wss://${removePrefix("https://")}"
    startsWith("http://") -> "ws://${removePrefix("http://")}"
    startsWith("wss://") || startsWith("ws://") -> this
    else -> "ws://$this"
}
