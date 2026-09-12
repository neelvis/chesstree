package com.chesstree.multiplayer.presentation

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.GameResponse
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
    }
}
