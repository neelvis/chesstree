package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.OnlineSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.w3c.dom.BroadcastChannel

class BrowserOnlineSessionStore(
    private val restoreSession: suspend () -> AuthResponse?,
    channelName: String = DEFAULT_CHANNEL_NAME,
) : OnlineSessionStore {
    private val channel = BroadcastChannel(channelName)
    private val mutableInvalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var invalidationVersion = 0L

    init {
        channel.onmessage = { event ->
            if (event.data == LOGOUT_SESSION) {
                invalidationVersion++
                mutableInvalidations.tryEmit(Unit)
            }
        }
    }

    override suspend fun load(): AuthResponse? {
        val startingVersion = invalidationVersion
        val authentication = restoreSession()
        return authentication.takeIf { invalidationVersion == startingVersion }
    }

    override suspend fun save(authentication: AuthResponse) = Unit

    override suspend fun clear() {
        channel.postMessage(LOGOUT_SESSION)
    }

    override fun invalidations(): Flow<Unit> = mutableInvalidations

    internal fun close() {
        channel.close()
    }

    private companion object {
        const val DEFAULT_CHANNEL_NAME = "chesstree.online.session.v1"
        const val LOGOUT_SESSION = "logout"
    }
}
