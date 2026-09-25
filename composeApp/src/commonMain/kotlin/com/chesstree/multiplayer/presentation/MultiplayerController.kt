package com.chesstree.multiplayer.presentation

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoVoteCommand
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStoreException
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
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
    val games: List<GameHistoryResponse> = emptyList(),
    val syncing: Boolean = false,
    val remoteState: GameStateResponse? = null,
    val session: GameSession? = null,
    val loading: Boolean = false,
    val submittingMove: Boolean = false,
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
    private var sync: Job? = null

    init {
        scope.launch {
            try {
                val authentication = sessionStore.load() ?: return@launch
                mutableState.value = mutableState.value.copy(authentication = authentication)
                loadGames(authentication.accessToken)
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

    fun submitAuthentication(onSuccess: suspend () -> Unit = {}) = launchRequest {
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
                } catch (error: OnlineSessionStoreException) {
                    "Вход выполнен, но не удалось безопасно сохранить сессию (${error.safeReason})"
                } catch (_: Throwable) {
                    "Вход выполнен, но не удалось безопасно сохранить сессию"
                }
                // Commit platform autofill while the credential fields are still on screen.
                onSuccess()
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

    fun loadGames() {
        val token = mutableState.value.authentication?.accessToken ?: return
        scope.launch { loadGames(token) }
    }

    fun openGame(code: String) = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.getGame(token, code)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    private suspend fun loadGames(token: String) {
        when (val result = api.getMyGames(token)) {
            is ApiResult.Success -> update {
                if (authentication?.accessToken == token) copy(games = result.value) else this
            }
            is ApiResult.Failure -> Unit
        }
    }

    fun joinGame() = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.joinGame(token, mutableState.value.gameCode)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun refreshGame() {
        val current = mutableState.value
        val token = current.authentication?.accessToken ?: return
        val code = current.game?.code ?: return
        if (sync?.isActive == true) return
        sync = scope.launch {
            mutableState.value = mutableState.value.copy(syncing = true)
            try {
                when (val result = api.getGameState(token, code)) {
                    is ApiResult.Success -> {
                        val latest = mutableState.value
                        mutableState.value = if (latest.game?.code == code) {
                            latest.withRemoteState(result.value).copy(syncing = false)
                        } else {
                            latest.copy(syncing = false)
                        }
                    }
                    is ApiResult.Failure -> mutableState.value = mutableState.value.copy(syncing = false, error = result.message)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(syncing = false, error = "Не удалось выполнить запрос")
            }
        }
    }

    fun submitMove(intent: MoveIntent) {
        val current = mutableState.value
        if (current.loading) return
        val token = current.authentication?.accessToken ?: return
        val confirmedSession = current.session ?: return
        val confirmedRemote = current.remoteState
        if (confirmedRemote == null) {
            update { copy(error = "Сначала обновите состояние партии") }
            return
        }
        val optimisticSession = (confirmedSession.apply(intent) as? SessionMoveResult.Applied)?.session
            ?: return
        mutableState.value = current.copy(session = optimisticSession)
        launchRequest(submittingMove = true) {
            val currentRemote = remoteState ?: return@launchRequest copy(error = "Сначала обновите состояние партии")
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
                        is ApiResult.Failure -> copy(
                            session = remoteState.toSession(),
                            error = refreshed.message,
                        )
                    }
                } else {
                    copy(session = remoteState.toSession(), error = result.message)
                }
            }
        }
    }

    fun requestUndo() = withToken { token ->
        val currentRemote = remoteState ?: return@withToken copy(error = "Сначала обновите состояние партии")
        when (
            val result = api.requestUndo(
                token,
                currentRemote.game.code,
                UndoRequestCommand(currentRemote.revision),
            )
        ) {
            is ApiResult.Success -> withRemoteState(result.value)
            is ApiResult.Failure -> handleStateConflict(token, currentRemote.game.code, result)
        }
    }

    fun voteUndo(approve: Boolean) = withToken { token ->
        val currentRemote = remoteState ?: return@withToken copy(error = "Сначала обновите состояние партии")
        val undoRequest = currentRemote.undoRequest
            ?: return@withToken copy(error = "Запрос на отмену уже закрыт")
        when (
            val result = api.voteUndo(
                token,
                currentRemote.game.code,
                UndoVoteCommand(currentRemote.revision, undoRequest.id, approve),
            )
        ) {
            is ApiResult.Success -> withRemoteState(result.value)
            is ApiResult.Failure -> handleStateConflict(token, currentRemote.game.code, result)
        }
    }

    private suspend fun MultiplayerUiState.handleStateConflict(
        token: String,
        code: String,
        failure: ApiResult.Failure,
    ): MultiplayerUiState = if (failure.code == "stale_revision") {
        when (val refreshed = api.getGameState(token, code)) {
            is ApiResult.Success -> withRemoteState(refreshed.value).copy(error = failure.message)
            is ApiResult.Failure -> copy(error = refreshed.message)
        }
    } else {
        copy(error = failure.message)
    }

    fun logout() {
        val current = mutableState.value
        val token = current.authentication?.accessToken
        request?.cancel()
        observation?.cancel()
        sync?.cancel()
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
        val currentRemoteState = remoteState
        if (currentRemoteState?.game?.code == remote.game.code) {
            if (remote.revision < currentRemoteState.revision) {
                return copy(game = remote.game, reconnecting = false)
            }
        }
        val replayed = remote.toSession(session)
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
        submittingMove: Boolean = false,
        afterUpdate: (MultiplayerUiState) -> Unit = {},
        block: suspend MultiplayerUiState.() -> MultiplayerUiState,
    ) {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(
            loading = true,
            submittingMove = submittingMove,
            error = null,
        )
        request = scope.launch {
            try {
                val updated = mutableState.value.block()
                val latest = mutableState.value
                mutableState.value = if (latest.hasNewerRemoteStateThan(updated)) {
                    latest.copy(loading = false, submittingMove = false)
                } else {
                    updated.copy(loading = false, submittingMove = false)
                }
                afterUpdate(mutableState.value)
                if (mutableState.value.authentication != null) loadGames(mutableState.value.authentication!!.accessToken)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    submittingMove = false,
                    session = if (submittingMove) {
                        mutableState.value.remoteState?.toSession() ?: mutableState.value.session
                    } else {
                        mutableState.value.session
                    },
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

private fun MultiplayerUiState.hasNewerRemoteStateThan(other: MultiplayerUiState): Boolean {
    val latestRemote = remoteState ?: return false
    val otherRemote = other.remoteState ?: return false
    return latestRemote.game.code == otherRemote.game.code && latestRemote.revision > otherRemote.revision
}

private fun GameStateResponse.toSession(
    currentSession: GameSession? = null,
): GameSession? = runCatching {
    val intents = moves.mapIndexed { index, move ->
        require(move.revision == index + 1)
        MoveIntent(
            actor = PlayerId.valueOf(move.actor),
            from = move.from.toDomain(),
            to = move.to.toDomain(),
            promotion = move.promotion?.let(PromotionChoice::valueOf),
        )
    }
    val current = currentSession
    if (current != null) {
        // Reuse derived state for unchanged history; apply only an appended suffix.
        if (current.moves == intents) return@runCatching current
        val currentMoveCount = current.moves.size
        if (
            intents.size > currentMoveCount &&
            current.moves.indices.all { index -> intents[index] == current.moves[index] }
        ) {
            var advanced = current
            intents.drop(currentMoveCount).forEach { intent ->
                advanced = (advanced?.apply(intent) as? SessionMoveResult.Applied)?.session
                    ?: return@runCatching null
            }
            return@runCatching advanced
        }
    }
    GameSession.replay(StandardGame.scenario, intents)
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
