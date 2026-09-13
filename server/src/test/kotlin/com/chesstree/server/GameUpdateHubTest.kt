package com.chesstree.server

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameUpdateHubTest {
    @Test
    fun databaseNotificationReachesEveryInstance() = runTest {
        val broker = TestBroker()
        val first = GameUpdateHub(broker.transport())
        val second = GameUpdateHub(broker.transport())
        val receivedByFirst = async { first.updates(GAME_CODE).drop(1).first() }
        val receivedBySecond = async { second.updates(GAME_CODE).drop(1).first() }
        runCurrent()

        broker.publish(GAME_CODE)

        receivedByFirst.await()
        receivedBySecond.await()
        first.close()
        second.close()
    }

    @Test
    fun reconnectResynchronizesOnlyActiveLocalGames() = runTest {
        val broker = TestBroker()
        val hub = GameUpdateHub(broker.transport())
        val resynchronized = async { hub.updates(GAME_CODE).drop(1).first() }
        runCurrent()

        broker.reconnect()
        broker.publish("invalid")

        resynchronized.await()
        hub.close()
    }

    private class TestBroker {
        private val transports = mutableListOf<TestTransport>()

        fun transport(): TestTransport = TestTransport().also(transports::add)

        fun publish(code: String) {
            transports.forEach { it.onUpdate(code) }
        }

        fun reconnect() {
            transports.forEach { it.onReconnect() }
        }
    }

    private class TestTransport : GameUpdateTransport {
        lateinit var onUpdate: (String) -> Unit
        lateinit var onReconnect: () -> Unit

        override fun start(onUpdate: (String) -> Unit, onReconnect: () -> Unit) {
            this.onUpdate = onUpdate
            this.onReconnect = onReconnect
        }

        override fun close() = Unit
    }

    private companion object {
        const val GAME_CODE = "ABC1234"
    }
}
