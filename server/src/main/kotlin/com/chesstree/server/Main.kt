package com.chesstree.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.util.logging.Logger

fun main() {
    val environment = System.getenv()
    val databaseConfig = DatabaseConfig(
        url = environment.required("CHESSTREE_DATABASE_URL"),
        user = environment.required("CHESSTREE_DATABASE_USER"),
        password = environment.required("CHESSTREE_DATABASE_PASSWORD"),
        maximumPoolSize = environment["CHESSTREE_DB_POOL_SIZE"]?.toIntOrNull() ?: 10,
    )
    val store = JdbcStore(databaseConfig)
    var updates: GameUpdateHub? = null
    var pushNotifications: PushNotifications? = null
    try {
        runBlocking { store.initialize() }
        val tokens = TokenGenerator()
        updates = GameUpdateHub(PostgresGameUpdateTransport(databaseConfig))
        pushNotifications = FcmPushNotifications.fromServiceAccountFile(
            store = store,
            serviceAccountFile = environment["CHESSTREE_FCM_SERVICE_ACCOUNT_FILE"]
                ?: DEFAULT_FCM_SERVICE_ACCOUNT_FILE,
            publicBaseUrl = environment["CHESSTREE_PUBLIC_BASE_URL"] ?: "http://localhost:8080",
            logWarning = Logger.getLogger("com.chesstree.push")::warning,
        ) ?: NoOpPushNotifications
        val services = ServerServices(
            store = store,
            auth = AuthService(store, Argon2PasswordHasher(), tokens),
            tokens = tokens,
            publicBaseUrl = environment["CHESSTREE_PUBLIC_BASE_URL"] ?: "http://localhost:8080",
            updates = checkNotNull(updates),
            pushNotifications = checkNotNull(pushNotifications),
        )
        embeddedServer(
            factory = Netty,
            port = environment["PORT"]?.toIntOrNull() ?: 8081,
            module = {
                chessTreeModule(
                    services = services,
                    allowedCorsHosts = environment["CHESSTREE_CORS_HOSTS"]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        .orEmpty(),
                    trustedProxyAddresses = environment["CHESSTREE_TRUSTED_PROXY_ADDRESSES"]
                        ?.split(',')
                        ?.map(String::trim)
                        ?.filter(String::isNotEmpty)
                        ?.toSet()
                        ?: setOf("127.0.0.1", "::1"),
                )
            },
        ).start(wait = true)
    } finally {
        (pushNotifications as? AutoCloseable)?.close()
        updates?.close()
        store.close()
    }
}

private const val DEFAULT_FCM_SERVICE_ACCOUNT_FILE =
    "/etc/chesstree/secrets/firebase-service-account.json"

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank)
        ?: error("Required environment variable is missing: $name")
