package com.chesstree.multiplayer.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiContractTest {
    @Test
    fun gameResponseRoundTripsWithoutPlatformTypes() {
        val original = GameResponse(
            id = "game-id",
            code = "ABC1234",
            shareUrl = "https://play.test/g/ABC1234",
            status = "WAITING",
            players = listOf(GamePlayerResponse(UserResponse("user-id", "Alice"))),
        )

        assertEquals(original, Json.decodeFromString<GameResponse>(Json.encodeToString(original)))
    }
}
