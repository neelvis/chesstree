package com.chesstree.multiplayer.presentation

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.scenario.StandardGame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class AuthMode { LOGIN, REGISTER }

data class MultiplayerUiState(
    val authMode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val password: String = "",
    val authentication: AuthResponse? = null,
    val gameCode: String = "",
    val game: GameResponse? = null,
    val remoteState: GameStateResponse? = null,
    val session: GameSession? = null,
    val loading: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
)

class MultiplayerController(
    private val api: ChessTreeApi,
    private val scope: CoroutineScope,
    initialGameCode: String = "",
    private val sessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    private val commandId: () -> String = ::randomCommandId,
) {
    private val mutableState = MutableStateFlow(
        MultiplayerUiState(gameCode = initialGameCode.uppercase().take(7)),
    )
    val state: StateFlow<MultiplayerUiState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var observation: Job? = null

    init {
        scope.launch {
            try {
                val authentication = sessionStore.load() ?: return@launch
                mutableState.value = mutableState.value.copy(authentication = authentication)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(error = "Не удалось восстановить сохранённый вход")
            }
        }
    }

    fun setAuthMode(mode: AuthMode) = update { copy(authMode = mode, error = null) }
    fun setUsername(value: String) = update { copy(username = value.take(24), error = null) }
    fun setPassword(value: String) = update { copy(password = value.take(128), error = null) }
    fun setGameCode(value: String) = update {
        copy(gameCode = value.uppercase().filter { it.isLetterOrDigit() }.take(7), error = null)
    }

    fun submitAuthentication() = launchRequest {
        val current = mutableState.value
        val result = when (current.authMode) {
            AuthMode.LOGIN -> api.login(current.username, current.password)
            AuthMode.REGISTER -> api.register(current.username, current.password)
        }
        when (result) {
            is ApiResult.Success -> {
                val storageWarning = try {
                    sessionStore.save(result.value)
                    null
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    "Вход выполнен, но не удалось безопасно сохранить сессию"
                }
                copy(
                    authentication = result.value,
                    password = "",
                    game = null,
                    remoteState = null,
                    session = null,
                    error = storageWarning,
                )
            }
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun createGame() = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.createGame(token)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun joinGame() = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.joinGame(token, mutableState.value.gameCode)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun refreshGame() = withToken { token ->
        val code = mutableState.value.game?.code ?: return@withToken copy()
        when (val result = api.getGameState(token, code)) {
            is ApiResult.Success -> withRemoteState(result.value)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun submitMove(intent: MoveIntent) = withToken { token ->
        val currentRemote = remoteState ?: return@withToken copy(error = "Сначала обновите состояние партии")
        val command = MoveCommandRequest(
            commandId = commandId(),
            expectedRevision = currentRemote.revision,
            from = intent.from.response(),
            to = intent.to.response(),
            promotion = intent.promotion?.name,
        )
        when (val result = api.submitMove(token, currentRemote.game.code, command)) {
            is ApiResult.Success -> withRemoteState(result.value)
            is ApiResult.Failure -> if (result.code == "stale_revision") {
                when (val refreshed = api.getGameState(token, currentRemote.game.code)) {
                    is ApiResult.Success -> withRemoteState(refreshed.value).copy(error = result.message)
                    is ApiResult.Failure -> copy(error = refreshed.message)
                }
            } else {
                copy(error = result.message)
            }
        }
    }

    fun logout() {
        val current = mutableState.value
        val token = current.authentication?.accessToken
        request?.cancel()
        observation?.cancel()
        request = scope.launch {
            if (token != null) api.logout(token)
            runCatching { sessionStore.clear() }
            mutableState.value = MultiplayerUiState(
                authMode = current.authMode,
                gameCode = current.gameCode,
            )
        }
    }

    private fun withToken(
        observeAfterSuccess: Boolean = false,
        block: suspend MultiplayerUiState.(String) -> MultiplayerUiState,
    ) {
        val token = mutableState.value.authentication?.accessToken ?: return
        launchRequest(afterUpdate = {
            if (observeAfterSuccess && it.game != null && it.error == null) restartObservation()
        }) { block(token) }
    }

    private suspend fun MultiplayerUiState.withRemoteGame(
        token: String,
        game: GameResponse,
    ): MultiplayerUiState {
        val updated = copy(game = game, gameCode = game.code, error = null)
        if (game.status != "ACTIVE" && game.status != "FINISHED") return updated
        return when (val stateResult = api.getGameState(token, game.code)) {
            is ApiResult.Success -> updated.withRemoteState(stateResult.value)
            is ApiResult.Failure -> updated.copy(error = stateResult.message)
        }
    }

    private fun MultiplayerUiState.withRemoteState(remote: GameStateResponse): MultiplayerUiState {
        if (remoteState?.game?.code == remote.game.code && remote.revision < remoteState.revision) {
            return copy(game = remote.game, reconnecting = false)
        }
        val replayed = remote.toSession()
            ?: return copy(error = "Сервер вернул несовместимое состояние партии")
        return copy(
            game = remote.game,
            gameCode = remote.game.code,
            remoteState = remote,
            session = replayed,
            error = null,
        )
    }

    private fun launchRequest(
        afterUpdate: (MultiplayerUiState) -> Unit = {},
        block: suspend MultiplayerUiState.() -> MultiplayerUiState,
    ) {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        request = scope.launch {
            try {
                val updated = mutableState.value.block()
                mutableState.value = updated.copy(loading = false)
                afterUpdate(mutableState.value)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    error = "Не удалось выполнить запрос",
                )
            }
        }
    }

    private fun restartObservation() {
        observation?.cancel()
        val authentication = mutableState.value.authentication ?: return
        val game = mutableState.value.game ?: return
        mutableState.value = mutableState.value.copy(reconnecting = true)
        observation = scope.launch {
            api.observeGame(authentication.accessToken, game.code).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        val current = mutableState.value
                        mutableState.value = current.withRemoteState(result.value).copy(reconnecting = false)
                    }
                    is ApiResult.Failure -> {
                        mutableState.value = mutableState.value.copy(reconnecting = true)
                    }
                }
            }
        }
    }

    private fun update(block: MultiplayerUiState.() -> MultiplayerUiState) {
        mutableState.value = mutableState.value.block()
    }
}

private fun GameStateResponse.toSession(): GameSession? = runCatching {
    require(revision == moves.size)
    moves.forEachIndexed { index, move -> require(move.revision == index + 1) }
    GameSession.replay(
        StandardGame.scenario,
        moves.map { move ->
            MoveIntent(
                actor = PlayerId.valueOf(move.actor),
                from = move.from.toDomain(),
                to = move.to.toDomain(),
                promotion = move.promotion?.let(PromotionChoice::valueOf),
            )
        },
    )
}.getOrNull()

private fun CoordinateResponse.toDomain() = BoardCoordinate(vertex, column, row)

private fun BoardCoordinate.response() = CoordinateResponse(vertex, column, row)

private fun randomCommandId(): String {
    val lengths = listOf(8, 4, 4, 4, 12)
    return lengths.joinToString("-") { length ->
        buildString(length) { repeat(length) { append(HEX[Random.nextInt(HEX.length)]) } }
    }
}

private const val HEX = "0123456789abcdef"
