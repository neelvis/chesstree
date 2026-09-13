package com.chesstree.server

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.ErrorResponse
import com.chesstree.multiplayer.contract.GamePlayerResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.MoveEventResponse
import com.chesstree.multiplayer.contract.UserResponse
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.PromotionChoice
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

data class ServerServices(
    val store: ChessTreeStore,
    val auth: AuthService,
    val tokens: TokenGenerator,
    val publicBaseUrl: String,
)

data class AuthenticatedUserPrincipal(val user: UserRecord, val token: String)

fun Application.chessTreeModule(
    services: ServerServices,
    allowedCorsHosts: List<String> = emptyList(),
) {
    val logger = environment.log
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false; explicitNulls = false })
    }
    if (allowedCorsHosts.isNotEmpty()) {
        install(CORS) {
            allowedCorsHosts.forEach { host -> allowHost(host, schemes = listOf("http", "https")) }
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
    }
    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "Некорректный запрос"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            logger.error("Unhandled request failure", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal_error", "Ошибка сервера"))
        }
    }
    install(RateLimit) {
        register(AUTH_RATE_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteHost }
        }
    }
    install(Authentication) {
        bearer(AUTH_PROVIDER) {
            authenticate { credential ->
                services.auth.authenticate(credential.token)?.let { user ->
                    AuthenticatedUserPrincipal(user, credential.token)
                }
            }
        }
    }

    routing {
        get("/health") { call.respond(mapOf("status" to "ok")) }
        route("/api/v1") {
            rateLimit(AUTH_RATE_LIMIT) {
                post("/auth/register") { call.respondAuth(services.auth.register(call.receive())) }
                post("/auth/login") { call.respondAuth(services.auth.login(call.receive())) }
            }
            authenticate(AUTH_PROVIDER) {
                post("/auth/logout") {
                    services.auth.logout(checkNotNull(call.principal<AuthenticatedUserPrincipal>()).token)
                    call.respond(HttpStatusCode.NoContent)
                }
                post("/games") {
                    val user = call.authenticatedUser()
                    val game = createUniqueGame(services, user)
                    call.respond(HttpStatusCode.Created, game.response(services.publicBaseUrl))
                }
                post("/games/{code}/join") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    when (val result = services.store.joinGame(code, user.id, services.tokens.shuffledColors())) {
                        is JoinGameResult.Joined -> call.respond(result.game.response(services.publicBaseUrl))
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
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("game_not_found", "Игра не найдена"))
                    } else {
                        call.respond(game.response(services.publicBaseUrl))
                    }
                }
                get("/games/{code}/state") {
                    val user = call.authenticatedUser()
                    val state = services.store.findGameState(call.gameCode())
                    if (state == null || state.game.players.none { it.user.id == user.id }) {
                        call.respond(HttpStatusCode.NotFound, ErrorResponse("game_not_found", "Игра не найдена"))
                    } else {
                        call.respond(state.response(services.publicBaseUrl))
                    }
                }
                post("/games/{code}/moves") {
                    val user = call.authenticatedUser()
                    val code = call.gameCode()
                    val command = call.receive<MoveCommandRequest>().toDomainCommand()
                    when (val result = services.store.submitMove(code, user.id, command)) {
                        is SubmitMoveResult.Applied -> call.respond(result.state.response(services.publicBaseUrl))
                        is SubmitMoveResult.Stale -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("stale_revision", "Состояние партии изменилось; обновите его"),
                        )
                        SubmitMoveResult.Missing,
                        SubmitMoveResult.NotParticipant,
                            -> call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("game_not_found", "Игра не найдена"),
                            )
                        SubmitMoveResult.NotActive -> call.respond(
                            HttpStatusCode.Conflict,
                            ErrorResponse("game_not_active", "Партия ещё не началась или уже завершена"),
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
                            ErrorResponse("command_conflict", "Идентификатор команды уже использован"),
                        )
                    }
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
            ErrorResponse("invalid_credentials_format", result.message),
        )
        AuthResult.UsernameTaken -> respond(
            HttpStatusCode.Conflict,
            ErrorResponse("username_taken", "Этот логин уже занят"),
        )
        AuthResult.InvalidCredentials -> respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse("invalid_credentials", "Неверный логин или пароль"),
        )
    }
}

private fun ApplicationCall.authenticatedUser(): UserRecord =
    checkNotNull(principal<AuthenticatedUserPrincipal>()).user

private suspend fun createUniqueGame(services: ServerServices, owner: UserRecord): GameRecord {
    repeat(10) {
        services.store.createGame(UUID.randomUUID(), services.tokens.gameCode(), owner.id)?.let { return it }
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

private fun GameStateRecord.response(publicBaseUrl: String) = GameStateResponse(
    game = game.response(publicBaseUrl),
    revision = moves.size,
    moves = moves.mapIndexed { index, move ->
        MoveEventResponse(
            revision = index + 1,
            actor = move.intent.actor.name,
            from = move.intent.from.response(),
            to = move.intent.to.response(),
            promotion = move.intent.promotion?.name,
        )
    },
)

private fun BoardCoordinate.response() = CoordinateResponse(vertex, column, row)

private fun MoveCommandRequest.toDomainCommand(): GameMoveCommand = try {
    require(expectedRevision >= 0)
    val fromCoordinate = BoardCoordinate(from.vertex, from.column, from.row)
    val toCoordinate = BoardCoordinate(to.vertex, to.column, to.row)
    require(fromCoordinate != toCoordinate)
    GameMoveCommand(
        commandId = UUID.fromString(commandId),
        expectedRevision = expectedRevision,
        from = fromCoordinate,
        to = toCoordinate,
        promotion = promotion?.let(PromotionChoice::valueOf),
    )
} catch (_: IllegalArgumentException) {
    throw BadRequestException("Invalid move command")
}

private const val AUTH_PROVIDER = "auth-bearer"
private val AUTH_RATE_LIMIT = RateLimitName("authentication")
private val GAME_CODE = Regex("[0-9A-HJKMNP-TV-Z]{7}")
