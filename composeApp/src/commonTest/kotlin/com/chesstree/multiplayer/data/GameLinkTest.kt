package com.chesstree.multiplayer.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameLinkTest {
    @Test
    fun extractsGameCodeFromWebAndCustomLinks() {
        assertEquals("ABC1234", gameCodeFromUrl("https://play.example/g/abc1234?source=share"))
        assertEquals("ABC1234", gameCodeFromUrl("chesstree://host/g/ABC1234"))
    }

    @Test
    fun rejectsMissingOrAmbiguousCharacters() {
        assertNull(gameCodeFromUrl("https://play.example/g/ABC10O4"))
        assertNull(gameCodeFromUrl("https://play.example/other/ABC1234"))
    }
}
