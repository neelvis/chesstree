package com.chesstree.multiplayer.presentation

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AuthMode { LOGIN, REGISTER }

data class MultiplayerUiState(
    val authMode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val password: String = "",
    val authentication: AuthResponse? = null,
    val gameCode: String = "",
    val game: GameResponse? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

class MultiplayerController(
    private val api: ChessTreeApi,
    private val scope: CoroutineScope,
    initialGameCode: String = "",
) {
    private val mutableState = MutableStateFlow(
        MultiplayerUiState(gameCode = initialGameCode.uppercase().take(7)),
    )
    val state: StateFlow<MultiplayerUiState> = mutableState.asStateFlow()
    private var request: Job? = null

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
            is ApiResult.Success -> copy(
                authentication = result.value,
                password = "",
                game = null,
                error = null,
            )
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun createGame() = withToken { token ->
        when (val result = api.createGame(token)) {
            is ApiResult.Success -> copy(game = result.value, gameCode = result.value.code, error = null)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun joinGame() = withToken { token ->
        when (val result = api.joinGame(token, mutableState.value.gameCode)) {
            is ApiResult.Success -> copy(game = result.value, gameCode = result.value.code, error = null)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun refreshGame() = withToken { token ->
        val code = mutableState.value.game?.code ?: return@withToken copy()
        when (val result = api.getGame(token, code)) {
            is ApiResult.Success -> copy(game = result.value, error = null)
            is ApiResult.Failure -> copy(error = result.message)
        }
    }

    fun logout() {
        val current = mutableState.value
        val token = current.authentication?.accessToken
        request?.cancel()
        request = scope.launch {
            if (token != null) api.logout(token)
            mutableState.value = MultiplayerUiState(
                authMode = current.authMode,
                gameCode = current.gameCode,
            )
        }
    }

    private fun withToken(block: suspend MultiplayerUiState.(String) -> MultiplayerUiState) {
        val token = mutableState.value.authentication?.accessToken ?: return
        launchRequest { block(token) }
    }

    private fun launchRequest(block: suspend MultiplayerUiState.() -> MultiplayerUiState) {
        if (mutableState.value.loading) return
        request = scope.launch {
            mutableState.value = mutableState.value.copy(loading = true, error = null)
            try {
                val updated = mutableState.value.block()
                mutableState.value = updated.copy(loading = false)
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

    private fun update(block: MultiplayerUiState.() -> MultiplayerUiState) {
        mutableState.value = mutableState.value.block()
    }
}
