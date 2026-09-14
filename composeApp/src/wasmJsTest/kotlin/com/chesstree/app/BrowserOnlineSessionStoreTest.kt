package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.UserResponse
import kotlinx.browser.localStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserOnlineSessionStoreTest {
    @Test
    fun activeSessionIsSharedWithoutPersistentBrowserStorage() = runTest {
        val firstTab = BrowserOnlineSessionStore(TEST_CHANNEL_NAME)
        val authentication = AuthResponse("token", UserResponse("user-id", "Alice"))

        var secondTab: BrowserOnlineSessionStore? = null
        try {
            localStorage.removeItem(TEST_CHANNEL_NAME)
            firstTab.save(authentication)
            val restoredTab = BrowserOnlineSessionStore(TEST_CHANNEL_NAME)
            secondTab = restoredTab

            assertEquals(authentication, restoredTab.load())
            assertNull(localStorage.getItem(TEST_CHANNEL_NAME))
        } finally {
            firstTab.close()
            secondTab?.close()
        }
    }

    private companion object {
        const val TEST_CHANNEL_NAME = "chesstree.online.session.test.wasm"
    }
}
