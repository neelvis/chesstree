package com.chesstree.multiplayer.data

import com.chesstree.multiplayer.contract.AuthResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface OnlineSessionStore {
    suspend fun load(): AuthResponse?
    suspend fun save(authentication: AuthResponse)
    suspend fun clear()
    fun invalidations(): Flow<Unit> = emptyFlow()
}

class OnlineSessionStoreException(val safeReason: String) : Exception(safeReason)

object NoOpOnlineSessionStore : OnlineSessionStore {
    override suspend fun load(): AuthResponse? = null
    override suspend fun save(authentication: AuthResponse) = Unit
    override suspend fun clear() = Unit
}
