package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.OnlineSessionStore
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import org.w3c.dom.BroadcastChannel

@OptIn(ExperimentalWasmJsInterop::class)
class BrowserOnlineSessionStore(
    channelName: String = DEFAULT_CHANNEL_NAME,
) : OnlineSessionStore {
    private val channel = BroadcastChannel(channelName)
    private var authentication: AuthResponse? = null
    private var pendingLoad: CompletableDeferred<AuthResponse?>? = null

    init {
        channel.onmessage = { event -> handleMessage(event.data?.toString()) }
    }

    override suspend fun load(): AuthResponse? {
        authentication?.let { return it }
        val request = CompletableDeferred<AuthResponse?>()
        pendingLoad = request
        postMessage(REQUEST_SESSION)
        val timeoutCallback: () -> JsAny? = {
            request.complete(null)
            null
        }
        val timeout = window.setTimeout(timeoutCallback, SESSION_REQUEST_TIMEOUT_MILLIS)
        return try {
            request.await()
        } finally {
            window.clearTimeout(timeout)
            if (pendingLoad === request) pendingLoad = null
        }
    }

    override suspend fun save(authentication: AuthResponse) {
        this.authentication = authentication
    }

    override suspend fun clear() {
        authentication = null
        pendingLoad?.complete(null)
        pendingLoad = null
        postMessage(CLEAR_SESSION)
    }

    internal fun close() {
        channel.close()
    }

    private fun handleMessage(message: String?) {
        when {
            message == REQUEST_SESSION -> authentication?.let {
                postMessage(SESSION_PREFIX + json.encodeToString(it))
            }

            message == CLEAR_SESSION -> {
                authentication = null
                pendingLoad?.complete(null)
                pendingLoad = null
            }

            message?.startsWith(SESSION_PREFIX) == true -> runCatching {
                json.decodeFromString<AuthResponse>(message.removePrefix(SESSION_PREFIX))
            }.getOrNull()?.let { restored ->
                authentication = restored
                pendingLoad?.complete(restored)
                pendingLoad = null
            }
        }
    }

    private fun postMessage(message: String) {
        channel.postMessage(message.toJsString())
    }

    private companion object {
        const val DEFAULT_CHANNEL_NAME = "chesstree.online.session.v1"
        const val REQUEST_SESSION = "request"
        const val CLEAR_SESSION = "clear"
        const val SESSION_PREFIX = "session:"
        const val SESSION_REQUEST_TIMEOUT_MILLIS = 1_000
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    }
}
