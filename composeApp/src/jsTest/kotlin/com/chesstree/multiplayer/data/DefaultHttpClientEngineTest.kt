package com.chesstree.multiplayer.data

import io.ktor.client.engine.js.Js
import kotlin.test.Test
import kotlin.test.assertSame

class DefaultHttpClientEngineTest {
    @Test
    fun browserUsesFetchEngine() {
        assertSame(Js, defaultHttpClientEngine())
    }
}
