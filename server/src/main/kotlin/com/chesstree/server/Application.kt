package com.chesstree.server

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import com.chesstree.multiplayer.contract.API_VERSION
import com.chesstree.multiplayer.contract.API_VERSION_HEADER
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.BrowserAuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.ErrorResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.contract.GamePlayerResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameSocketAuthRequest
import com.chesstree.multiplayer.contract.GameStatePush
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.MoveEventResponse
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoRequestResponse
import com.chesstree.multiplayer.contract.UndoVoteCommand
import com.chesstree.multiplayer.contract.UserResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.auth.AuthScheme
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID
import java.net.URI
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class ServerServices(
    val store: ChessTreeStore,
    val auth: AuthService,
    val tokens: TokenGenerator,
    val publicBaseUrl: String,
    val updates: GameUpdateHub = GameUpdateHub(),
    val pushNotifications: PushNotifications = NoOpPushNotifications,
    val browserCookieSecure: Boolean = publicBaseUrl.startsWith("https://"),
)

data class AuthenticatedUserPrincipal(val user: UserRecord, val token: String)

fun Application.chessTreeModule(
    services: ServerServices,
    allowedCorsHosts: List<String> = emptyList(),
    trustedProxyAddresses: Set<String> = setOf("127.0.0.1", "::1"),
) {
    val logger = environment.log
    val browserAllowedHosts = (allowedCorsHosts + runCatching {
        URI(services.publicBaseUrl).rawAuthority
    }.getOrNull().orEmpty()).distinct()
    val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    install(ContentNegotiation) {
        json(json)
    }
    install(XForwardedHeaders)
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(json)
        pingPeriodMillis = 20_000
        timeoutMillis = 20_000
        maxFrameSize = 64 * 1024
        masking = false
    }
    if (allowedCorsHosts.isNotEmpty()) {
        install(CORS) {
            allowedCorsHosts.forEach { host -> allowHost(host, schemes = listOf("http", "https")) }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
            allowCredentials = true
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "Некорректный запрос")
            )
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("Unhandled request failure", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", "Ошибка сервера")
            )
        }
    }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call ->
                val directPeer = call.request.local.remoteHost
                if (directPeer in trustedProxyAddresses) call.request.origin.remoteHost else directPeer
            }
        }
    }
    install(Authentication) {
        bearer(AUTH_PROVIDER) {
            authHeader { call ->
                call.request.headers[HttpHeaders.Authorization]?.let(::parseAuthorizationHeader)
                    ?: call.request.cookies[WEB_SESSION_COOKIE]?.takeIf {
                        call.request.httpMethod in setOf(HttpMethod.Get, HttpMethod.Head) ||
                            call.hasAllowedBrowserOrigin(browserAllowedHosts)
                    }?.let { HttpAuthHeader.Single(AuthScheme.Bearer, it) }
            }
            authenticate { credential ->
                services.auth.authenticate(credential.token)?.let { user ->
                    if (request.cookies[WEB_SESSION_COOKIE] == credential.token) {
                        response.headers.append(HttpHeaders.CacheControl, "no-store")
                        setBrowserSessionCookie(credential.token, services)
                    }
                    AuthenticatedUserPrincipal(user, credential.token)
                }
            }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        authenticate(AUTH_PROVIDER) {
            get("/get-history") {
                val id = call.request.queryParameters["id"]
                    ?: throw BadRequestException("Missing game ID")
                val uuid = runCatching { UUID.fromString(id) }
                    .getOrNull()
                    ?.takeIf { it.toString().equals(id, ignoreCase = true) }
                val gameCode = id.uppercase().takeIf(GAME_CODE::matches)
                if (uuid == null && gameCode == null) throw BadRequestException("Invalid game ID")
                val state = if (uuid != null) {
                    services.store.findGameState(uuid)
                } else {
                    services.store.findGameState(checkNotNull(gameCode))
                }
                if (state == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("game_not_found", "Игра не найдена"))
                } else {
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                    call.response.headers.append(
                        HttpHeaders.ContentDisposition,
                        "attachment; filename=\"${state.game.code}.json\"",
                    )
                    call.respond(state.response(services.publicBaseUrl))
                }
            }
        }
        route("/api/v1") {
            rateLimit(AUTH_RATE_LIMIT) {
                post("/auth/register") { call.respondAuth(services.auth.register(call.receive())) }
                post("/auth/login") { call.respondAuth(services.auth.login(call.receive())) }
                post("/auth/browser/register") {
                    if (!call.hasAllowedBrowserOrigin(browserAllowedHosts)) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        call.respondBrowserAuth(services.auth.register(call.receive()), services)
                    }
                }
                post("/auth/browser/login") {
                    if (!call.hasAllowedBrowserOrigin(browserAllowedHosts)) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        call.respondBrowserAuth(services.auth.login(call.receive()), services)
                    }
                }
                post("/auth/browser/logout") {
                    if (!call.hasAllowedBrowserOrigin(browserAllowedHosts)) {
                        call.respond(HttpStatusCode.Forbidden)
                    } else {
                        call.request.cookies[WEB_SESSION_COOKIE]?.let { token ->
                            services.auth.logout(token)
                        }
                        call.clearBrowserSessionCookie(services)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
            authenticate(AUTH_PROVIDER) {
                get("/auth/browser/session") {
                    if (call.request.cookies[WEB_SESSION_COOKIE] == null ||
                        !call.hasAllowedBrowserOriginOrSameSite(browserAllowedHosts)
                    ) {
                        call.respond(HttpStatusCode.Unauthorized)
                    } else {
                        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                        call.respond(BrowserAuthResponse(call.authenticatedUser().response()))
                    }
                }
                post("/push/devices") {
                    val user = call.authenticatedUser()
                    val request = call.receive<PushDeviceRegistrationRequest>()
                    if (request.token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
                        throw BadRequestException("Invalid push token")
                    }
                    val platform = runCatching { PushPlatform.valueOf(request.platform) }
                        .getOrElse { throw BadRequestException("Invalid push platform") }
                    val registered = services.store.registerPushDevice(
                        PushDevice(user.id, request.token, platform),
                    )
                    if (!registered) {
                        call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("push_device_limit", "Достигнут лимит устройств для уведомлений"),
                        )
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                post("/push/devices/unregister") {
                    val user = call.authenticatedUser()
                    val request = call.receive<PushDeviceRemovalRequest>()
                    if (request.token.length !in MIN_PUSH_TOKEN_LENGTH..MAX_PUSH_TOKEN_LENGTH) {
                        throw BadRequestException("Invalid push token")
                    }
                    services.store.removePushDevice(user.id, request.token)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/auth/logout") {
                    services.auth.logout(checkNotNull(call.principal<AuthenticatedUserPrincipal>()).token)
                    if (call.request.cookies[WEB_SESSION_COOKIE] != null) {
                        call.clearBrowserSessionCookie(services)
                    }
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games") {
                    val user = call.authenticatedUser()
                    val game = createUniqueGame(services, user)
                    services.updates.publish(game.code)
                    call.respond(HttpStatusCode.Created, game.response(services.publicBaseUrl))
                }
                get("/games") {
                    val user = call.authenticatedUser()
                    call.respond(
                        services.store.findGamesForUser(user.id).map { it.historyResponse() })
                }
                post("/games/{code}/join") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    when (val result =
                        services.store.joinGame(code, user.id, services.tokens.shuffledColors())) {
                        is JoinGameResult.Joined -> {
                            if (result.newlyJoined && result.game.status == GameStatus.ACTIVE) {
                                services.pushNotifications.gameStarted(result.game)
                            }
                            services.updates.publish(code)
                            call.respond(result.game.response(services.publicBaseUrl))
                        }

                        JoinGameResult.Missing -> call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("game_not_found", "Игра не найдена"),
                        )

                        JoinGameResult.Full -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("game_full", "В игре уже три участника"),
                        )
                    }
                }
                get("/games/{code}") {
                    val user = call.authenticatedUser()
                    val game = services.store.findGame(call.gameCode())
                    if (game == null || game.players.none { it.user.id == user.id }) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("game_not_found", "Игра не найдена")
                        )
                    } else {
                        call.respond(game.response(services.publicBaseUrl))
                    }
                }
                get("/games/{code}/state") {
                    val user = call.authenticatedUser()
                    val afterMoveCount = call.request.queryParameters["afterMoveCount"]?.let { value ->
                        value.toIntOrNull()?.takeIf { it >= 0 }
                            ?: throw BadRequestException("Invalid move cursor")
                    } ?: 0
                    val state = services.store.findGameState(call.gameCode(), afterMoveCount)
                    if (state == null || state.game.players.none { it.user.id == user.id }) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("game_not_found", "Игра не найдена")
                        )
                    } else {
                        val clientApiVersion = call.request.headers[API_VERSION_HEADER]?.toIntOrNull() ?: 0
                        call.respond(
                            state.response(
                                services.publicBaseUrl,
                                includeUndoRequest = clientApiVersion >= 3,
                                includePosition = clientApiVersion >= 4,
                            ),
                        )
                    }
                }
                post("/games/{code}/moves") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    val clientApiVersion = call.request.headers[API_VERSION_HEADER]?.toIntOrNull() ?: 0
                    val command = call.receive<MoveCommandRequest>().toDomainCommand()
                    when (val result = services.store.submitMove(code, user.id, command)) {
                        is SubmitMoveResult.Applied -> {
                            if (!result.wasDuplicate) {
                                services.pushNotifications.gameStateChanged(result.state)
                            }
                            services.updates.publish(code)
                            call.respond(
                                result.state.response(
                                    services.publicBaseUrl,
                                    includePosition = clientApiVersion >= 4,
                                ),
                            )
                        }

                        is SubmitMoveResult.Stale -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse(
                                "stale_revision",
                                "Состояние партии изменилось; обновите его"
                            ),
                        )

                        SubmitMoveResult.Missing,
                        SubmitMoveResult.NotParticipant,
                            -> call.respond(
                            HttpStatusCode.NotFound,
                            ErrorResponse("game_not_found", "Игра не найдена"),
                        )

                        SubmitMoveResult.NotActive -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse(
                                "game_not_active",
                                "Партия ещё не началась или уже завершена"
                            ),
                        )

                        SubmitMoveResult.NotTurn -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("not_your_turn", "Сейчас ход другого игрока"),
                        )

                        SubmitMoveResult.IllegalMove -> call.respond(
                            HttpStatusCode.UnprocessableEntity,
                            ErrorResponse("illegal_move", "Недопустимый ход"),
                        )

                        SubmitMoveResult.CommandConflict -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse(
                                "command_conflict",
                                "Идентификатор команды уже использован"
                            ),
                        )

                        SubmitMoveResult.UndoPending -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse(
                                "undo_pending",
                                "Сначала завершите голосование за отмену хода"
                            ),
                        )
                    }
                }
                post("/games/{code}/undo-requests") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    val command = call.receive<UndoRequestCommand>()
                    if (command.expectedRevision < 0) throw BadRequestException("Invalid revision")
                    call.respondUndoResult(
                        services,
                        code,
                        services.store.requestUndo(code, user.id, command.expectedRevision),
                    )
                }
                post("/games/{code}/undo-requests/{requestId}/votes") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    val command = call.receive<UndoVoteCommand>()
                    val requestId = runCatching { UUID.fromString(command.requestId) }
                        .getOrElse { throw BadRequestException("Invalid undo request") }
                    if (
                        command.expectedRevision < 0 ||
                        call.parameters["requestId"] != command.requestId
                    ) throw BadRequestException("Invalid undo vote")
                    call.respondUndoResult(
                        services,
                        code,
                        services.store.voteUndo(
                            code,
                            user.id,
                            requestId,
                            command.expectedRevision,
                            command.approve,
                        ),
                    )
                }
            }
            webSocket("/games/{code}/events") {
                val code = runCatching { call.gameCode() }.getOrNull()
                if (code == null) {
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Invalid game code"))
                    return@webSocket
                }
                val authRequest = runCatching {
                    withTimeout(10.seconds) { receiveDeserialized<GameSocketAuthRequest>() }
                }.getOrNull()
                val cookieToken = call.request.cookies[WEB_SESSION_COOKIE]
                    ?.takeIf { call.hasAllowedBrowserOrigin(browserAllowedHosts) }
                val sessionToken = cookieToken ?: authRequest?.accessToken?.takeIf(String::isNotBlank)
                val user = sessionToken?.let { services.auth.authenticate(it) }
                val initialState = user?.let { authenticated ->
                    services.store.findGameState(code)?.takeIf { state ->
                        state.game.players.any { it.user.id == authenticated.id }
                    }
                }
                if (initialState == null) {
                    close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Authentication failed"))
                    return@webSocket
                }
                if (authRequest?.protocolVersion != API_VERSION) {
                    sendSerialized(
                        GameStatePush(
                            protocolVersion = API_VERSION,
                            state = initialState.response(
                                services.publicBaseUrl,
                                includeUndoRequest = false,
                            ),
                        ),
                    )
                    close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "Protocol mismatch"))
                    return@webSocket
                }
                var afterMoveCount = 0
                services.updates.updates(code).collect {
                    if (services.auth.authenticate(checkNotNull(sessionToken))?.id != user.id) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Session expired"))
                        throw CancellationException("WebSocket session expired")
                    }
                    val state = services.store.findGameState(code, afterMoveCount) ?: return@collect
                    if (state.game.players.none { it.user.id == user.id }) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Access revoked"))
                        throw CancellationException("WebSocket access revoked")
                    }
                    sendSerialized(
                        GameStatePush(
                            state = state.response(services.publicBaseUrl, includePosition = true),
                        ),
                    )
                    afterMoveCount = state.moveOffset + state.moves.size
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondAuth(result: AuthResult) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    when (result) {
        is AuthResult.Authenticated -> respond(
            HttpStatusCode.OK,
            AuthResponse(result.token, result.user.response()),
        )

        is AuthResult.Invalid -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(result.reason.apiCode, result.reason.apiCode),
        )

        AuthResult.UsernameTaken -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("username_taken", "username_taken"),
        )

        AuthResult.InvalidCredentials -> respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("invalid_credentials", "invalid_credentials"),
        )
    }
}

private suspend fun ApplicationCall.respondBrowserAuth(result: AuthResult, services: ServerServices) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append(HttpHeaders.Pragma, "no-cache")
    when (result) {
        is AuthResult.Authenticated -> {
            setBrowserSessionCookie(result.token, services)
            respond(BrowserAuthResponse(result.user.response()))
        }

        is AuthResult.Invalid -> respond(
            HttpStatusCode.BadRequest,
            ErrorResponse(result.reason.apiCode, result.reason.apiCode),
        )

        AuthResult.UsernameTaken -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("username_taken", "username_taken"),
        )

        AuthResult.InvalidCredentials -> respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("invalid_credentials", "invalid_credentials"),
        )
    }
}

private fun ApplicationCall.setBrowserSessionCookie(token: String, services: ServerServices) {
    response.cookies.append(
        name = WEB_SESSION_COOKIE,
        value = token,
        maxAge = WEB_SESSION_MAX_AGE_SECONDS,
        path = "/",
        secure = services.browserCookieSecure,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
    clearLegacyBrowserSessionCookie(services)
}

private fun ApplicationCall.clearBrowserSessionCookie(services: ServerServices) {
    response.cookies.append(
        name = WEB_SESSION_COOKIE,
        value = "",
        maxAge = 0,
        path = "/",
        secure = services.browserCookieSecure,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
    clearLegacyBrowserSessionCookie(services)
}

private fun ApplicationCall.clearLegacyBrowserSessionCookie(services: ServerServices) {
    response.cookies.append(
        name = WEB_SESSION_COOKIE,
        value = "",
        maxAge = 0,
        path = "/api/v1",
        secure = services.browserCookieSecure,
        httpOnly = true,
        extensions = mapOf("SameSite" to "Strict"),
    )
}

private fun ApplicationCall.hasAllowedBrowserOrigin(allowedHosts: List<String>): Boolean {
    val origin = request.headers[HttpHeaders.Origin]?.let { runCatching { URI(it) }.getOrNull() }
        ?: return false
    if (origin.scheme !in setOf("http", "https") || origin.rawAuthority.isNullOrBlank()) return false
    return allowedHosts.any { allowed -> origin.rawAuthority.equals(allowed, ignoreCase = true) }
}

private fun ApplicationCall.hasAllowedBrowserOriginOrSameSite(allowedHosts: List<String>): Boolean =
    request.headers[HttpHeaders.Origin]?.let { hasAllowedBrowserOrigin(allowedHosts) } ?: true

private fun ApplicationCall.authenticatedUser(): UserRecord =
    checkNotNull(principal<AuthenticatedUserPrincipal>()).user

private suspend fun createUniqueGame(services: ServerServices, owner: UserRecord): GameRecord {
    repeat(10) {
        services.store.createGame(UUID.randomUUID(), services.tokens.gameCode(), owner.id)
            ?.let { return it }
    }
    error("Unable to generate a unique game code")
}

private fun ApplicationCall.gameCode(): String {
    val code = parameters["code"]?.uppercase() ?: throw BadRequestException("Missing game code")
    if (!GAME_CODE.matches(code)) throw BadRequestException("Invalid game code")
    return code
}

private fun UserRecord.response() = UserResponse(id.toString(), username)

private fun GameRecord.response(publicBaseUrl: String) = GameResponse(
    id = id.toString(),
    code = code,
    shareUrl = "${publicBaseUrl.trimEnd('/')}/g/$code",
    status = status.name,
    players = players.map { GamePlayerResponse(it.user.response(), it.color?.name) },
)

private fun GameRecord.historyResponse() = GameHistoryResponse(
    id = id.toString(),
    code = code,
    status = status.name,
    players = players.map { GamePlayerResponse(it.user.response(), it.color?.name) },
    startedAt = startedAt.toString(),
)

private fun GameStateRecord.response(
    publicBaseUrl: String,
    includeUndoRequest: Boolean = true,
    includePosition: Boolean = false,
): GameStateResponse {
    val position = if (includePosition && moveOffset == 0 && game.status != GameStatus.WAITING) {
        runCatching {
            val state = domainState
            val captures = capturedPieces
            if (state != null && captures != null) {
                GameStateSnapshotCodec.toResponse(state, moves.size, captures)
            } else {
                GameSession.replay(StandardGame.scenario, moves.map(GameMoveRecord::intent))
                    ?.let { GameStateSnapshotCodec.toResponse(it.state, moves.size, it.capturedPieces) }
            }
        }.getOrNull()
    } else {
        null
    }
    return GameStateResponse(
        game = game.response(publicBaseUrl),
        revision = if (includeUndoRequest) revision else moveOffset + moves.size,
        moves = moves.mapIndexed { index, move ->
            MoveEventResponse(
                revision = moveOffset + index + 1,
                actor = move.intent.actor.name,
                from = move.intent.from.response(),
                to = move.intent.to.response(),
                promotion = move.intent.promotion?.name,
            )
        },
        moveOffset = moveOffset,
        undoRequest = undoRequest?.takeIf { includeUndoRequest }?.let { request ->
            UndoRequestResponse(
                id = request.id.toString(),
                requestedByUserId = request.requestedByUserId.toString(),
                targetMoveCount = request.targetMoveCount,
                approvedByUserIds = request.approvedByUserIds.map(UUID::toString).sorted(),
            )
        },
        position = position,
    )
}

private suspend fun ApplicationCall.respondUndoResult(
    services: ServerServices,
    code: String,
    result: UndoResult,
) {
    val clientApiVersion = request.headers[API_VERSION_HEADER]?.toIntOrNull() ?: 0
    when (result) {
        is UndoResult.Updated -> {
            services.updates.publish(code)
            respond(
                result.state.response(
                    services.publicBaseUrl,
                    includeUndoRequest = true,
                    includePosition = clientApiVersion >= 4,
                ),
            )
        }

        is UndoResult.Stale -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("stale_revision", "Состояние партии изменилось; обновите его"),
        )

        UndoResult.Missing,
        UndoResult.NotParticipant,
            -> respond(HttpStatusCode.NotFound, ErrorResponse("game_not_found", "Игра не найдена"))

        UndoResult.NotAvailable -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("undo_not_available", "Этот запрос на отмену больше недоступен"),
        )

        UndoResult.AlreadyPending -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("undo_already_pending", "Запрос на отмену уже рассматривается"),
        )

        UndoResult.RequesterCannotVote -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("requester_cannot_vote", "Инициатор не голосует за свой запрос"),
        )

        UndoResult.AlreadyVoted -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("already_voted", "Ваш голос уже учтён"),
        )
    }
}

private fun BoardCoordinate.response() = CoordinateResponse(vertex, column, row)

private fun MoveCommandRequest.toDomainCommand(): GameMoveCommand = try {
    require(expectedRevision >= 0 && expectedMoveCount?.let { it >= 0 } != false)
    val fromCoordinate = BoardCoordinate(from.vertex, from.column, from.row)
    val toCoordinate = BoardCoordinate(to.vertex, to.column, to.row)
    require(fromCoordinate != toCoordinate)
    GameMoveCommand(
        commandId = UUID.fromString(commandId),
        expectedRevision = expectedRevision,
        expectedMoveCount = expectedMoveCount,
        from = fromCoordinate,
        to = toCoordinate,
        promotion = promotion?.let(PromotionChoice::valueOf),
    )
} catch (_: IllegalArgumentException) {
    throw BadRequestException("Invalid move command")
}

private const val AUTH_PROVIDER = "auth-bearer"
private const val WEB_SESSION_COOKIE = "chesstree_web_session"
private const val WEB_SESSION_MAX_AGE_SECONDS = 30L * 24 * 60 * 60
private const val MIN_PUSH_TOKEN_LENGTH = 20
private const val MAX_PUSH_TOKEN_LENGTH = 4096
private val AUTH_RATE_LIMIT = RateLimitName("authentication")
private val GAME_CODE = Regex("[0-9A-HJKMNP-TV-Z]{7}")
