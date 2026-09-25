package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.AuthResponse

interface OnlineSessionStore {
    suspend fun load(): AuthResponse?
    suspend fun save(authentication: AuthResponse)
    suspend fun clear()
}

class OnlineSessionStoreException(val safeReason: String) : Exception(safeReason)

object NoOpOnlineSessionStore : OnlineSessionStore {
    override suspend fun load(): AuthResponse? = null
    override suspend fun save(authentication: AuthResponse) = Unit
    override suspend fun clear() = Unit
}
