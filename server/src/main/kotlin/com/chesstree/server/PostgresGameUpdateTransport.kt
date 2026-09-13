package com.chesstree.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.postgresql.PGConnection
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val GAME_UPDATE_CHANNEL = "chesstree_game_updates"

class PostgresGameUpdateTransport(
    private val config: DatabaseConfig,
) : GameUpdateTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listenerConnection = AtomicReference<Connection?>()
    private val closed = AtomicBoolean(false)
    private var listenerJob: Job? = null

    override fun start(onUpdate: (String) -> Unit, onReconnect: () -> Unit) {
        check(listenerJob == null) { "PostgreSQL game update listener is already started" }
        check(!closed.get()) { "PostgreSQL game update listener is closed" }
        listenerJob = scope.launch {
            var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
            while (currentCoroutineContext().isActive) {
                var connection: Connection? = null
                try {
                    connection = connection()
                    connection.use { activeConnection ->
                        listenerConnection.set(activeConnection)
                        if (!currentCoroutineContext().isActive || closed.get()) return@use
                        activeConnection.createStatement().use { it.execute("LISTEN $GAME_UPDATE_CHANNEL") }
                        if (!currentCoroutineContext().isActive || closed.get()) return@use
                        onReconnect()
                        retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
                        val postgres = activeConnection.unwrap(PGConnection::class.java)
                        while (currentCoroutineContext().isActive) {
                            postgres.getNotifications(LISTENER_TIMEOUT_MILLIS)
                                ?.mapNotNull { notification -> gameCode(activeConnection, notification.parameter) }
                                ?.forEach(onUpdate)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    if (currentCoroutineContext().isActive) {
                        logger.warn("PostgreSQL game update listener disconnected; retrying", error)
                    }
                } finally {
                    connection?.let { listenerConnection.compareAndSet(it, null) }
                }
                delay(retryDelayMillis)
                retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listenerConnection.getAndSet(null)?.runCatching { close() }
        runBlocking { listenerJob?.cancelAndJoin() }
        scope.cancel()
    }

    private fun gameCode(connection: Connection, gameId: String): String? {
        val id = runCatching { UUID.fromString(gameId) }.getOrNull() ?: return null
        return connection.prepareStatement("SELECT public_code FROM games WHERE id = ?").use { statement ->
            statement.setObject(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString("public_code").trim() else null
            }
        }
    }

    private fun connection(): Connection = DriverManager.getConnection(
        config.url,
        Properties().apply {
            setProperty("user", config.user)
            setProperty("password", config.password)
            setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS)
            setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
            setProperty("tcpKeepAlive", "true")
            setProperty("ApplicationName", "chesstree-game-updates")
        },
    )

    private companion object {
        const val LISTENER_TIMEOUT_MILLIS = 10_000
        const val CONNECT_TIMEOUT_SECONDS = "5"
        const val SOCKET_TIMEOUT_SECONDS = "15"
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 10_000L
        val logger = LoggerFactory.getLogger(PostgresGameUpdateTransport::class.java)
    }
}
