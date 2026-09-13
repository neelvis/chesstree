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

    @Test
    fun moveCommandCarriesOrderingAndIdempotencyFields() {
        val original = MoveCommandRequest(
            commandId = "00000000-0000-0000-0000-000000000001",
            expectedRevision = 7,
            from = CoordinateResponse(0, 1, 2),
            to = CoordinateResponse(0, 1, 3),
            promotion = "QUEEN",
        )

        assertEquals(original, Json.decodeFromString<MoveCommandRequest>(Json.encodeToString(original)))
    }
}
