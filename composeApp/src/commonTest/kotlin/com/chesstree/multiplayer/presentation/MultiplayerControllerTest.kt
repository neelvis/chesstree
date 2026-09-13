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
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.ChessTreeApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class MultiplayerControllerTest {
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

    private class FakeApi(private val joinFails: Boolean = false) : ChessTreeApi {
        private val auth = AuthResponse("token", UserResponse("user-id", "Alice"))
        private val game = GameResponse(
            id = "game-id",
            code = "ABC1234",
            shareUrl = "https://play.test/g/ABC1234",
            status = "WAITING",
            players = emptyList(),
        )

        override suspend fun register(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun login(username: String, password: String) = ApiResult.Success(auth)
        override suspend fun logout(token: String) = ApiResult.Success(Unit)
        override suspend fun createGame(token: String) = ApiResult.Success(game)
        override suspend fun joinGame(token: String, code: String): ApiResult<GameResponse> =
            if (joinFails) ApiResult.Failure("game_not_found", "Игра не найдена") else ApiResult.Success(game)

        override suspend fun getGame(token: String, code: String) = ApiResult.Success(game)
        override suspend fun getGameState(token: String, code: String) = ApiResult.Success(
            GameStateResponse(game, revision = 0, moves = emptyList()),
        )
        override suspend fun submitMove(
            token: String,
            code: String,
            command: MoveCommandRequest,
        ) = ApiResult.Success(GameStateResponse(game, revision = 0, moves = emptyList()))
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
}
