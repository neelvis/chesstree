package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.UserResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserOnlineSessionStoreTest {
    @Test
    fun sessionRestoresFromServerAndLogoutInvalidatesOtherTabs() = runTest {
        val authentication = AuthResponse("", UserResponse("user-id", "Alice"))
        val firstTab = BrowserOnlineSessionStore(
            restoreSession = { authentication },
            channelName = TEST_CHANNEL_NAME,
        )
        val secondTab = BrowserOnlineSessionStore(
            restoreSession = { authentication },
            channelName = TEST_CHANNEL_NAME,
        )
        try {
            assertEquals(authentication, firstTab.load())
            val invalidation = async { secondTab.invalidations().first() }

            firstTab.clear()

            assertEquals(Unit, invalidation.await())
        } finally {
            firstTab.close()
            secondTab.close()
        }
    }

    @Test
    fun logoutDuringSessionRestoreDiscardsTheStaleResponse() = runTest {
        val authentication = AuthResponse("", UserResponse("user-id", "Alice"))
        val pendingRestore = CompletableDeferred<AuthResponse?>()
        val firstTab = BrowserOnlineSessionStore(
            restoreSession = { pendingRestore.await() },
            channelName = TEST_CHANNEL_NAME,
        )
        val secondTab = BrowserOnlineSessionStore(
            restoreSession = { authentication },
            channelName = TEST_CHANNEL_NAME,
        )
        try {
            val restore = async { firstTab.load() }
            val invalidation = async { firstTab.invalidations().first() }
            secondTab.clear()
            invalidation.await()
            pendingRestore.complete(authentication)

            assertEquals(null, restore.await())
        } finally {
            firstTab.close()
            secondTab.close()
        }
    }

    private companion object {
        const val TEST_CHANNEL_NAME = "chesstree.online.session.test.wasm"
    }
}
