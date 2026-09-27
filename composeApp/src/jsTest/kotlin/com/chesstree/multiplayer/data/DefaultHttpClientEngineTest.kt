package com.chesstree.multiplayer.data

import kotlin.test.Test
import kotlin.test.assertNotNull

class DefaultHttpClientEngineTest {
    @Test
    fun browserBuildsFetchEngineWithCookieAndWithoutCookieModes() {
        assertNotNull(defaultHttpClientEngine(browserSession = false))
        assertNotNull(defaultHttpClientEngine(browserSession = true))
    }
}
