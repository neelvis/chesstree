package com.chesstree.server

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

class GameUpdateHub(
    private val transport: GameUpdateTransport? = null,
) : AutoCloseable {
    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArraySet<SendChannel<Unit>>>()

    init {
        transport?.start(
            onUpdate = ::notifySubscribers,
            onReconnect = ::resynchronizeActiveGames,
        )
    }

    fun updates(code: String): Flow<Unit> = callbackFlow {
        subscribers.compute(code) { _, existing ->
            (existing ?: CopyOnWriteArraySet()).also { it += channel }
        }
        trySend(Unit)
        awaitClose {
            subscribers.computeIfPresent(code) { _, existing ->
                existing.apply { remove(channel) }.takeUnless { it.isEmpty() }
            }
        }
    }.conflate()

    fun publish(code: String) = notifySubscribers(code)

    override fun close() {
        transport?.close()
    }

    private fun notifySubscribers(code: String) {
        subscribers[code]?.forEach { it.trySend(Unit) }
    }

    private fun resynchronizeActiveGames() {
        subscribers.values.forEach { gameSubscribers ->
            gameSubscribers.forEach { it.trySend(Unit) }
        }
    }
}

interface GameUpdateTransport : AutoCloseable {
    fun start(onUpdate: (String) -> Unit, onReconnect: () -> Unit)
}
