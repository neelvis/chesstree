package com.chesstree.multiplayer.presentation

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.CoordinateResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoVoteCommand
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStoreException
import com.chesstree.multiplayer.data.isValidGameCode
import com.chesstree.multiplayer.data.toSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

enum class AuthMode { LOGIN, REGISTER }

data class MultiplayerUiState(
    val authMode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val password: String = "",
    val authentication: AuthResponse? = null,
    val gameCode: String = "",
    val openingGameCode: String? = null,
    val game: GameResponse? = null,
    val games: List<GameHistoryResponse> = emptyList(),
    val loadingGames: Boolean = false,
    val syncing: Boolean = false,
    val remoteState: GameStateResponse? = null,
    val session: GameSession? = null,
    val loading: Boolean = false,
    val submittingMove: Boolean = false,
    val error: String? = null,
)

class MultiplayerController(
    private val api: ChessTreeApi,
    private val scope: CoroutineScope,
    initialGameCode: String = "",
    private val sessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    private val commandId: () -> String = ::randomCommandId,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val inviteCode = initialGameCode.uppercase().takeIf(::isValidGameCode)
    private val mutableState = MutableStateFlow(
        MultiplayerUiState(gameCode = inviteCode.orEmpty()),
    )
    val state: StateFlow<MultiplayerUiState> = mutableState.asStateFlow()
    private var request: Job? = null
    private var observation: Job? = null
    private var sync: Job? = null
    private var invalidationJob: Job? = null
    private var gamesLoadId = 0

    init {
        scope.launch {
            try {
                val authentication = sessionStore.load() ?: return@launch
                mutableState.value = mutableState.value.copy(authentication = authentication)
                loadGames(authentication.accessToken)
                if (inviteCode != null) joinGame()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value =
                    mutableState.value.copy(error = "i18n:saved_login_restore_failed")
            }
        }
        invalidationJob = scope.launch {
            sessionStore.invalidations().collect {
                val current = mutableState.value
                gamesLoadId++
                request?.cancel()
                observation?.cancel()
                sync?.cancel()
                mutableState.value = MultiplayerUiState(
                    authMode = current.authMode,
                    gameCode = current.gameCode,
                )
            }
        }
    }

    fun close() {
        invalidationJob?.cancel()
        request?.cancel()
        observation?.cancel()
        sync?.cancel()
    }

    fun setAuthMode(mode: AuthMode) = update { copy(authMode = mode, error = null) }
    fun setUsername(value: String) = update { copy(username = value.take(24), error = null) }
    fun setPassword(value: String) = update { copy(password = value.take(128), error = null) }
    fun setGameCode(value: String) = update {
        copy(gameCode = value.uppercase().filter { it.isLetterOrDigit() }.take(7), error = null)
    }

    fun submitAuthentication(
        onSuccess: suspend () -> Unit = {},
        onAuthenticated: (AuthResponse) -> Unit = {},
    ) = launchRequest(
        afterUpdate = { updated ->
            updated.authentication?.let(onAuthenticated)
            if (updated.authentication != null && inviteCode != null) joinGame()
        },
    ) {
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
                    "i18n:login_save_failed_reason|${error.safeReason}"
                } catch (_: Throwable) {
                    "i18n:login_save_failed"
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

            is ApiResult.Failure -> copy(error = result.toUiMessage())
        }
    }

    fun createGame() = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.createGame(token)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.toUiMessage())
        }
    }

    fun createBotGame(botCount: Int) = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.createBotGame(token, botCount)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.toUiMessage())
        }
    }

    fun loadGames() {
        val token = mutableState.value.authentication?.accessToken ?: return
        scope.launch { loadGames(token) }
    }

    fun openGame(code: String) {
        if (mutableState.value.loading || mutableState.value.authentication == null) return
        update { copy(openingGameCode = code, error = null) }
        withToken(observeAfterSuccess = true) { token ->
            when (val result = api.getGame(token, code)) {
                is ApiResult.Success -> withRemoteGame(
                    token,
                    result.value
                ).copy(openingGameCode = null)

                is ApiResult.Failure -> copy(error = result.toUiMessage(), openingGameCode = null)
            }
        }
    }

    private suspend fun loadGames(token: String) {
        val loadId = ++gamesLoadId
        update { copy(loadingGames = true) }
        try {
            when (val result = api.getMyGames(token)) {
                is ApiResult.Success -> update {
                    if (authentication?.accessToken == token) copy(games = result.value) else this
                }

                is ApiResult.Failure -> Unit
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Keep history loading isolated from the rest of the lobby.
        } finally {
            if (loadId == gamesLoadId) update { copy(loadingGames = false) }
        }
    }

    fun joinGame() = withToken(observeAfterSuccess = true) { token ->
        when (val result = api.joinGame(token, mutableState.value.gameCode)) {
            is ApiResult.Success -> withRemoteGame(token, result.value)
            is ApiResult.Failure -> copy(error = result.toUiMessage())
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
                val currentState = mutableState.value.remoteState
                val afterMoveCount = if (currentState?.game?.code == code) {
                    currentState.moveOffset + currentState.moves.size
                } else 0
                when (val result = api.getGameState(token, code, afterMoveCount)) {
                    is ApiResult.Success -> {
                        applyRemoteState(result.value, expectedGameCode = code)
                        mutableState.value = mutableState.value.copy(syncing = false)
                    }

                    is ApiResult.Failure -> mutableState.value =
                        mutableState.value.copy(syncing = false, error = result.toUiMessage())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value =
                    mutableState.value.copy(syncing = false, error = "i18n:request_failed")
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
            update { copy(error = "i18n:state_refresh_first") }
            return
        }
        val optimisticSession =
            (confirmedSession.apply(intent) as? SessionMoveResult.Applied)?.session
                ?: return
        mutableState.value = current.copy(session = optimisticSession)
        launchRequest(submittingMove = true) {
            val currentRemote = remoteState
                ?: return@launchRequest copy(error = "i18n:state_refresh_first")
            val command = MoveCommandRequest(
                commandId = commandId(),
                expectedRevision = currentRemote.revision,
                expectedMoveCount = confirmedSession.moves.size,
                from = intent.from.response(),
                to = intent.to.response(),
                promotion = intent.promotion?.name,
            )
            when (val result = api.submitMove(token, currentRemote.game.code, command)) {
                is ApiResult.Success -> withRemoteState(result.value)
                is ApiResult.Failure -> if (result.code == "stale_revision") {
                    when (val refreshed = api.getGameState(token, currentRemote.game.code)) {
                        is ApiResult.Success -> withRemoteState(refreshed.value).copy(error = result.toUiMessage())
                        is ApiResult.Failure -> copy(
                            session = remoteState.toSession(dispatcher = computationDispatcher),
                            error = refreshed.toUiMessage(),
                        )
                    }
                } else {
                    copy(
                        session = remoteState.toSession(dispatcher = computationDispatcher),
                        error = result.toUiMessage(),
                    )
                }
            }
        }
    }

    fun requestUndo() = withToken { token ->
        val currentRemote =
            remoteState ?: return@withToken copy(error = "i18n:state_refresh_first")
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
        val currentRemote =
            remoteState ?: return@withToken copy(error = "i18n:state_refresh_first")
        val undoRequest = currentRemote.undoRequest
            ?: return@withToken copy(error = "i18n:undo_request_closed")
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
            is ApiResult.Success -> withRemoteState(refreshed.value).copy(error = failure.toUiMessage())
            is ApiResult.Failure -> copy(error = refreshed.toUiMessage())
        }
    } else {
        copy(error = failure.toUiMessage())
    }

    fun logout() {
        val current = mutableState.value
        val token = current.authentication?.accessToken
        gamesLoadId++
        request?.cancel()
        observation?.cancel()
        sync?.cancel()
        request = scope.launch {
            if (token != null) {
                when (val result = api.logout(token)) {
                    is ApiResult.Success -> Unit
                    is ApiResult.Failure -> {
                        mutableState.value = current.copy(error = result.toUiMessage())
                        return@launch
                    }
                }
            }
            runCatching { sessionStore.clear() }
            mutableState.value = MultiplayerUiState(
                authMode = current.authMode,
                gameCode = current.gameCode,
                loadingGames = false,
            )
        }
    }

    fun returnToLobby() {
        if (mutableState.value.loading) return
        observation?.cancel()
        sync?.cancel()
        mutableState.value = mutableState.value.copy(
            game = null,
            remoteState = null,
            session = null,
            syncing = false,
            error = null,
        )
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
            is ApiResult.Failure -> updated.copy(error = stateResult.toUiMessage())
        }
    }

    private suspend fun MultiplayerUiState.withRemoteState(remote: GameStateResponse): MultiplayerUiState {
        val currentRemoteState = remoteState
        if (currentRemoteState?.game?.code == remote.game.code) {
            if (remote.revision < currentRemoteState.revision) {
                return copy(game = remote.game)
            }
        }
        val replayed = remote.toSession(session, computationDispatcher)
            ?: return copy(error = "i18n:incompatible_game_state")
        return copy(
            game = remote.game,
            gameCode = remote.game.code,
            remoteState = remote,
            session = replayed,
            error = null,
        )
    }

    /**
     * Replaying a remote history runs off the UI thread and can overlap a newer WebSocket or poll
     * update. Re-check the revision after replay so a slower, older response cannot roll the
     * displayed turn back and leave every client waiting for the wrong player.
     */
    private suspend fun applyRemoteState(
        remote: GameStateResponse,
        expectedGameCode: String? = null
    ) {
        val beforeReplay = mutableState.value
        if (expectedGameCode != null && beforeReplay.game?.code != expectedGameCode) return
        val updated = beforeReplay.withRemoteState(remote)
        val latest = mutableState.value
        val latestRemote = latest.remoteState
        if (
            latest.game?.code != remote.game.code ||
            (latestRemote?.game?.code == remote.game.code && latestRemote.revision > remote.revision)
        ) return
        mutableState.value = updated.copy(
            loading = latest.loading,
            submittingMove = latest.submittingMove,
            syncing = latest.syncing,
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
                    openingGameCode = null,
                    session = if (submittingMove) {
                        mutableState.value.remoteState?.toSession(dispatcher = computationDispatcher)
                            ?: mutableState.value.session
                    } else {
                        mutableState.value.session
                    },
                    error = "i18n:request_failed",
                )
            }
        }
    }

    private fun restartObservation() {
        observation?.cancel()
        val authentication = mutableState.value.authentication ?: return
        val game = mutableState.value.game ?: return
        observation = scope.launch {
            api.observeGame(authentication.accessToken, game.code).collect { result ->
                when (result) {
                    is ApiResult.Success -> {
                        applyRemoteState(result.value)
                    }

                    is ApiResult.Failure -> {
                        if (result.code != "connection_lost") {
                            mutableState.value = mutableState.value.copy(error = result.toUiMessage())
                        }
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

private suspend fun GameStateResponse.toSession(
    currentSession: GameSession? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): GameSession? = withContext(dispatcher) {
    runCatching {
        val intents = moves.mapIndexed { index, move ->
            require(move.revision == moveOffset + index + 1)
            MoveIntent(
                actor = PlayerId.valueOf(move.actor),
                from = move.from.toDomain(),
                to = move.to.toDomain(),
                promotion = move.promotion?.let(PromotionChoice::valueOf),
            )
        }
        val current = currentSession
        if (current != null) {
            // A WebSocket suffix can arrive after its move was already applied optimistically.
            // Reconcile the part that overlaps local history, then apply only unseen moves.
            if (moveOffset > 0 && moveOffset <= current.moves.size) {
                val overlapCount = minOf(intents.size, current.moves.size - moveOffset)
                if ((0 until overlapCount).any { index ->
                        current.moves[moveOffset + index] != intents[index]
                    }
                ) {
                    return@runCatching null
                }
                if (overlapCount == intents.size) return@runCatching current
                var advanced = current
                intents.drop(overlapCount).forEach { intent ->
                    advanced = (advanced?.apply(intent) as? SessionMoveResult.Applied)?.session
                        ?: return@runCatching null
                }
                return@runCatching advanced
            }
            // Full responses are used for initial sync and after an undo truncates history.
            if (moveOffset == 0 && current.moves == intents) return@runCatching current
            val currentMoveCount = current.moves.size
            if (moveOffset == 0 &&
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
        if (moveOffset == 0) {
            position
                ?.takeIf { it.moveCount == intents.size }
                ?.toSession(StandardGame.scenario, intents)
                ?.let { return@runCatching it }
        }
        GameSession.replay(StandardGame.scenario, intents)
    }
        .getOrNull()
}

private fun CoordinateResponse.toDomain() = BoardCoordinate(vertex, column, row)

private fun BoardCoordinate.response() = CoordinateResponse(vertex, column, row)

private fun randomCommandId(): String {
    val lengths = listOf(8, 4, 4, 4, 12)
    return lengths.joinToString("-") { length ->
        buildString(length) { repeat(length) { append(HEX[Random.nextInt(HEX.length)]) } }
    }
}

private const val HEX = "0123456789abcdef"

private fun ApiResult.Failure.toUiMessage(): String = when (code) {
    "invalid_request" -> "i18n:invalid_request"
    "internal_error" -> "i18n:internal_error"
    "invalid_credentials" -> "i18n:invalid_credentials"
    "invalid_username" -> "i18n:invalid_username"
    "invalid_password" -> "i18n:invalid_password"
    "game_not_found" -> "i18n:game_not_found"
    "game_full" -> "i18n:game_already_full"
    "stale_revision" -> "i18n:state_changed_refresh"
    "game_not_active" -> "i18n:game_not_active"
    "not_your_turn" -> "i18n:not_your_turn"
    "illegal_move" -> "i18n:illegal_move"
    "command_conflict" -> "i18n:command_conflict"
    "undo_pending" -> "i18n:undo_pending"
    "undo_not_available" -> "i18n:undo_not_available"
    "undo_already_pending" -> "i18n:undo_already_pending"
    "requester_cannot_vote" -> "i18n:requester_cannot_vote"
    "already_voted" -> "i18n:already_voted"
    "username_taken" -> "i18n:username_taken"
    else -> message
}
