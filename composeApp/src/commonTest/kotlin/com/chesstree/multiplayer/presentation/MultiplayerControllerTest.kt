package com.chesstree.multiplayer.presentation

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameStateResponse
import com.chesstree.multiplayer.contract.MoveCommandRequest
import com.chesstree.multiplayer.contract.MoveEventResponse
import com.chesstree.multiplayer.contract.GamePlayerResponse
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.scenario.StandardGame
import com.chesstree.multiplayer.contract.UserResponse
import com.chesstree.multiplayer.contract.UndoRequestCommand
import com.chesstree.multiplayer.contract.UndoRequestResponse
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.OnlineSessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerControllerTest {
    @Test
    fun credentialSaveIsRequestedOnlyAfterSuccessfulAuthentication() = runTest {
        var successfulRequests = 0
        val successful = MultiplayerController(FakeApi(), this)
        successful.setUsername("Alice")
        successful.setPassword("correct-horse")

        successful.submitAuthentication { successfulRequests += 1 }
        runCurrent()

        assertEquals(1, successfulRequests)

        var failedRequests = 0
        val failed = MultiplayerController(FakeApi(authFails = true), this)
        failed.setUsername("Alice")
        failed.setPassword("incorrect-password")

        failed.submitAuthentication { failedRequests += 1 }
        runCurrent()

        assertEquals(0, failedRequests)
    }

    @Test
    fun registrationClearsPasswordAndCreatesLobby() = runTest {
        val controller = MultiplayerController(FakeApi(), this)
        controller.setAuthMode(AuthMode.REGISTER)
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")

        controller.submitAuthentication()
        runCurrent()
        assertEquals("Alice", controller.state.value.authentication?.user?.username)
        assertEquals("", controller.state.value.password)

        controller.createGame()
        runCurrent()
        assertEquals("ABC1234", controller.state.value.game?.code)
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun failedJoinKeepsEnteredCodeAndShowsServerMessage() = runTest {
        val controller = MultiplayerController(FakeApi(joinFails = true), this)
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()
        controller.setGameCode("abc1234")

        controller.joinGame()
        runCurrent()

        assertEquals("ABC1234", controller.state.value.gameCode)
        assertEquals("Игра не найдена", controller.state.value.error)
        assertNull(controller.state.value.game)
    }

    @Test
    fun logoutKeepsDeepLinkCodeForNextLogin() = runTest {
        val controller = MultiplayerController(FakeApi(), this, initialGameCode = "abc1234")
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()

        controller.logout()
        runCurrent()

        assertNull(controller.state.value.authentication)
        assertEquals("ABC1234", controller.state.value.gameCode)
    }

    @Test
    fun authenticationIsStoredRestoredAndCleared() = runTest {
        val store = FakeSessionStore()
        val first = MultiplayerController(FakeApi(), this, sessionStore = store)
        first.setUsername("Alice")
        first.setPassword("correct-horse")
        first.submitAuthentication()
        runCurrent()
        assertEquals("Alice", store.authentication?.user?.username)

        val restored = MultiplayerController(FakeApi(), this, sessionStore = store)
        runCurrent()
        assertEquals("Alice", restored.state.value.authentication?.user?.username)

        restored.logout()
        runCurrent()
        assertNull(store.authentication)
    }

    @Test
    fun acceptedServerMoveAdvancesReplayedSession() = runTest {
        val api = MoveApi()
        val controller = MultiplayerController(api, this, commandId = { "00000000-0000-0000-0000-000000000001" })
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()
        controller.setGameCode("ABC1234")
        controller.joinGame()
        runCurrent()
        val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()

        controller.submitMove(com.chesstree.game.domain.MoveIntent(move.actor, move.from, move.to, move.promotion))
        runCurrent()

        assertEquals(0, api.submitted?.expectedRevision)
        assertEquals("00000000-0000-0000-0000-000000000001", api.submitted?.commandId)
        assertEquals(1, controller.state.value.remoteState?.revision)
        assertEquals(1, controller.state.value.session?.moves?.size)
    }

    @Test
    fun staleMoveRefreshesAuthoritativeHistory() = runTest {
        val api = MoveApi(staleOnSubmit = true)
        val controller = MultiplayerController(api, this)
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()
        controller.setGameCode("ABC1234")
        controller.joinGame()
        runCurrent()
        val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()

        controller.submitMove(com.chesstree.game.domain.MoveIntent(move.actor, move.from, move.to, move.promotion))
        runCurrent()

        assertEquals(1, controller.state.value.remoteState?.revision)
        assertEquals("Состояние партии изменилось; обновите его", controller.state.value.error)
    }

    @Test
    fun pushedStateUpdatesGameWithoutManualRefresh() = runTest {
        val api = PushApi()
        val controller = MultiplayerController(api, backgroundScope)
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()
        controller.setGameCode("ABC1234")
        controller.joinGame()
        runCurrent()

        api.pushRevisionOne()
        runCurrent()

        assertEquals(1, controller.state.value.remoteState?.revision)
        assertEquals(1, controller.state.value.session?.moves?.size)
        assertFalse(controller.state.value.reconnecting)

        api.pushInitialState()
        runCurrent()
        assertEquals(1, controller.state.value.remoteState?.revision)
    }

    @Test
    fun lateUndoHttpResponseCannotOverwriteANewerWebSocketRevision() = runTest {
        val api = PushApi()
        val controller = MultiplayerController(api, backgroundScope)
        controller.setUsername("Alice")
        controller.setPassword("correct-horse")
        controller.submitAuthentication()
        runCurrent()
        controller.setGameCode("ABC1234")
        controller.joinGame()
        runCurrent()
        api.pushRevisionOne()
        runCurrent()

        controller.requestUndo()
        runCurrent()
        api.pushUndoCompleted()
        runCurrent()
        api.completeUndoRequestWithStalePending()
        runCurrent()

        assertEquals(4, controller.state.value.remoteState?.revision)
        assertEquals(0, controller.state.value.session?.moves?.size)
        assertNull(controller.state.value.remoteState?.undoRequest)
    }

    private class FakeApi(
        private val joinFails: Boolean = false,
        private val authFails: Boolean = false,
    ) : ChessTreeApi {
        private val auth = AuthResponse("token", UserResponse("user-id", "Alice"))
        private val game = GameResponse(
            id = "game-id",
            code = "ABC1234",
            shareUrl = "https://play.test/g/ABC1234",
            status = "WAITING",
            players = emptyList(),
        )

        override suspend fun register(username: String, password: String): ApiResult<AuthResponse> = authenticate()
        override suspend fun login(username: String, password: String): ApiResult<AuthResponse> = authenticate()
        override suspend fun logout(token: String) = ApiResult.Success(Unit)
        override suspend fun createGame(token: String) = ApiResult.Success(game)
        override suspend fun joinGame(token: String, code: String): ApiResult<GameResponse> =
            if (joinFails) ApiResult.Failure("game_not_found", "Игра не найдена") else ApiResult.Success(game)

        override suspend fun getGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGameState(token: String, code: String) = ApiResult.Success(
            GameStateResponse(game, revision = 0, moves = emptyList()),
        )
        override fun observeGame(token: String, code: String): Flow<ApiResult<GameStateResponse>> = emptyFlow()
        override suspend fun submitMove(
            token: String,
            code: String,
            command: MoveCommandRequest,
        ) = ApiResult.Success(GameStateResponse(game, revision = 0, moves = emptyList()))

        private fun authenticate(): ApiResult<AuthResponse> = if (authFails) {
            ApiResult.Failure("invalid_credentials", "Неверный логин или пароль")
        } else {
            ApiResult.Success(auth)
        }
    }

    private class MoveApi(private val staleOnSubmit: Boolean = false) : ChessTreeApi {
        private val auth = AuthResponse("token", UserResponse("user-id", "Alice"))
        private val game = GameResponse(
            id = "game-id",
            code = "ABC1234",
            shareUrl = "https://play.test/g/ABC1234",
            status = "ACTIVE",
            players = listOf(GamePlayerResponse(auth.user, "WHITE")),
        )
        var submitted: MoveCommandRequest? = null

        override suspend fun register(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun login(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun logout(token: String) = ApiResult.Success(Unit)
        override suspend fun createGame(token: String) = ApiResult.Success(game)
        override suspend fun joinGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGameState(token: String, code: String): ApiResult<GameStateResponse> =
            ApiResult.Success(submitted?.let(::movedState) ?: GameStateResponse(game, revision = 0, moves = emptyList()))

        override fun observeGame(token: String, code: String): Flow<ApiResult<GameStateResponse>> = emptyFlow()

        override suspend fun submitMove(
            token: String,
            code: String,
            command: MoveCommandRequest,
        ): ApiResult<GameStateResponse> {
            submitted = command
            if (staleOnSubmit) {
                return ApiResult.Failure("stale_revision", "Состояние партии изменилось; обновите его")
            }
            return ApiResult.Success(movedState(command))
        }

        private fun movedState(command: MoveCommandRequest) = GameStateResponse(
            game = game,
            revision = 1,
            moves = listOf(
                MoveEventResponse(
                    revision = 1,
                    actor = "WHITE",
                    from = command.from,
                    to = command.to,
                    promotion = command.promotion,
                ),
            ),
        )
    }

    private class PushApi : ChessTreeApi {
        private val auth = AuthResponse("token", UserResponse("user-id", "Alice"))
        private val game = GameResponse(
            id = "game-id",
            code = "ABC1234",
            shareUrl = "https://play.test/g/ABC1234",
            status = "ACTIVE",
            players = listOf(GamePlayerResponse(auth.user, "WHITE")),
        )
        private val updates = MutableSharedFlow<ApiResult<GameStateResponse>>(extraBufferCapacity = 1)
        private val undoResponse = CompletableDeferred<ApiResult<GameStateResponse>>()

        override suspend fun register(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun login(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun logout(token: String) = ApiResult.Success(Unit)
        override suspend fun createGame(token: String) = ApiResult.Success(game)
        override suspend fun joinGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGameState(token: String, code: String) = ApiResult.Success(initialState())
        override fun observeGame(token: String, code: String): Flow<ApiResult<GameStateResponse>> = updates
        override suspend fun submitMove(token: String, code: String, command: MoveCommandRequest) =
            ApiResult.Success(initialState())
        override suspend fun requestUndo(token: String, code: String, command: UndoRequestCommand) =
            undoResponse.await()

        fun pushRevisionOne() {
            val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()
            updates.tryEmit(
                ApiResult.Success(
                    GameStateResponse(
                        game = game,
                        revision = 1,
                        moves = listOf(
                            MoveEventResponse(
                                revision = 1,
                                actor = move.actor.name,
                                from = com.chesstree.multiplayer.contract.CoordinateResponse(
                                    move.from.vertex,
                                    move.from.column,
                                    move.from.row,
                                ),
                                to = com.chesstree.multiplayer.contract.CoordinateResponse(
                                    move.to.vertex,
                                    move.to.column,
                                    move.to.row,
                                ),
                                promotion = move.promotion?.name,
                            ),
                        ),
                    ),
                ),
            )
        }

        fun pushInitialState() {
            updates.tryEmit(ApiResult.Success(initialState()))
        }

        fun pushUndoCompleted() {
            updates.tryEmit(ApiResult.Success(GameStateResponse(game, revision = 4, moves = emptyList())))
        }

        fun completeUndoRequestWithStalePending() {
            undoResponse.complete(
                ApiResult.Success(
                    GameStateResponse(
                        game = game,
                        revision = 2,
                        moves = listOf(firstMoveEvent()),
                        undoRequest = UndoRequestResponse(
                            id = "00000000-0000-0000-0000-000000000001",
                            requestedByUserId = auth.user.id,
                            targetMoveCount = 1,
                            approvedByUserIds = emptyList(),
                        ),
                    ),
                ),
            )
        }

        private fun initialState() = GameStateResponse(game, revision = 0, moves = emptyList())

        private fun firstMoveEvent(): MoveEventResponse {
            val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first()
            return MoveEventResponse(
                revision = 1,
                actor = move.actor.name,
                from = com.chesstree.multiplayer.contract.CoordinateResponse(
                    move.from.vertex,
                    move.from.column,
                    move.from.row,
                ),
                to = com.chesstree.multiplayer.contract.CoordinateResponse(
                    move.to.vertex,
                    move.to.column,
                    move.to.row,
                ),
                promotion = move.promotion?.name,
            )
        }
    }

    private class FakeSessionStore : OnlineSessionStore {
        var authentication: AuthResponse? = null

        override suspend fun load(): AuthResponse? = authentication
        override suspend fun save(authentication: AuthResponse) {
            this.authentication = authentication
        }
        override suspend fun clear() {
            authentication = null
        }
    }
}
