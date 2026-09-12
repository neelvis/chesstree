package com.chesstree.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking

fun main() {
    val environment = System.getenv()
    val store = JdbcStore(
        DatabaseConfig(
            url = environment.required("CHESSTREE_DATABASE_URL"),
            user = environment.required("CHESSTREE_DATABASE_USER"),
            password = environment.required("CHESSTREE_DATABASE_PASSWORD"),
        ),
    )
    runBlocking { store.initialize() }
    val tokens = TokenGenerator()
    val services = ServerServices(
        store = store,
        auth = AuthService(store, Argon2PasswordHasher(), tokens),
        tokens = tokens,
        publicBaseUrl = environment["CHESSTREE_PUBLIC_BASE_URL"] ?: "http://localhost:8080",
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
            )
        },
    ).start(wait = true)
}

private fun Map<String, String>.required(name: String): String =
    get(name)?.takeIf(String::isNotBlank) ?: error("Required environment variable is missing: $name")
