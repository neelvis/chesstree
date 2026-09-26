package com.chesstree.multiplayer.data

import kotlinx.coroutines.flow.Flow

interface PushTokenProvider {
    val platform: String

    suspend fun requestPermission(): Boolean
    suspend fun currentToken(): String?
    fun tokenUpdates(): Flow<String>
}

object NoOpPushTokenProvider : PushTokenProvider {
    override val platform: String = ""
    override suspend fun requestPermission(): Boolean = false
    override suspend fun currentToken(): String? = null
    override fun tokenUpdates(): Flow<String> = kotlinx.coroutines.flow.emptyFlow()
}
