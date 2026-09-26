package com.chesstree.server

import com.chesstree.multiplayer.contract.LoginRequest
import com.chesstree.multiplayer.contract.RegisterRequest
import de.mkammerer.argon2.Argon2Factory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.Locale

interface PasswordHasher {
    fun hash(password: CharArray): String
    fun verify(encoded: String, password: CharArray): Boolean
}

class Argon2PasswordHasher : PasswordHasher {
    override fun hash(password: CharArray): String =
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).hash(2, 19_456, 1, password)

    override fun verify(encoded: String, password: CharArray): Boolean =
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(encoded, password)
}

class TokenGenerator(private val random: SecureRandom = SecureRandom()) {
    fun accessToken(): String = ByteArray(32).also(random::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun gameCode(): String = buildString(GAME_CODE_LENGTH) {
        repeat(GAME_CODE_LENGTH) { append(GAME_CODE_ALPHABET[random.nextInt(GAME_CODE_ALPHABET.length)]) }
    }

    fun shuffledColors(): List<PlayerColor> = PlayerColor.entries.shuffled(random)

    companion object {
        private const val GAME_CODE_LENGTH = 7
        private const val GAME_CODE_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}

fun hashToken(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .let(HexFormat.of()::formatHex)

class AuthService(
    private val store: ChessTreeStore,
    private val passwordHasher: PasswordHasher,
    private val tokenGenerator: TokenGenerator,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dummyPasswordHash: String =
        passwordHasher.hash("dummy-password-value".toCharArray())

    suspend fun register(request: RegisterRequest): AuthResult {
        val username = request.username.trim()
        val validation = validateCredentials(username, request.password)
        if (validation != null) return AuthResult.Invalid(validation)
        val chars = request.password.toCharArray()
        val passwordHash = try {
            passwordHasher.hash(chars)
        } finally {
            chars.fill('\u0000')
        }
        return when (val created =
            store.createUser(username, normalizeUsername(username), passwordHash)) {
            is CreateUserResult.Created -> sessionFor(created.user)
            CreateUserResult.UsernameTaken -> AuthResult.UsernameTaken
        }
    }

    suspend fun login(request: LoginRequest): AuthResult {
        val user = store.findUser(normalizeUsername(request.username.trim()))
        val chars = request.password.toCharArray()
        val valid = try {
            passwordHasher.verify(user?.passwordHash ?: dummyPasswordHash, chars)
        } finally {
            chars.fill('\u0000')
        }
        return if (user != null && valid) sessionFor(user) else AuthResult.InvalidCredentials
    }

    suspend fun authenticate(token: String): UserRecord? {
        val now = clock.instant()
        return store.renewSession(hashToken(token), now, now.plus(SESSION_DURATION))?.user
    }

    suspend fun logout(token: String) = store.deleteSession(hashToken(token))

    private suspend fun sessionFor(user: UserRecord): AuthResult.Authenticated {
        val token = tokenGenerator.accessToken()
        store.saveSession(hashToken(token), user.id, clock.instant().plus(SESSION_DURATION))
        return AuthResult.Authenticated(token, user)
    }

    private fun validateCredentials(username: String, password: String): String? = when {
        !USERNAME.matches(username) -> "Логин должен содержать 3–24 латинских символа, цифры или подчёркивания"
        password.length !in 10..128 -> "Пароль должен содержать от 10 до 128 символов"
        else -> null
    }

    companion object {
        private val USERNAME = Regex("[A-Za-z0-9_]{3,24}")
        private val SESSION_DURATION: Duration = Duration.ofDays(30)
        fun normalizeUsername(username: String): String = username.lowercase(Locale.ROOT)
    }
}

sealed interface AuthResult {
    data class Authenticated(val token: String, val user: UserRecord) : AuthResult
    data class Invalid(val message: String) : AuthResult
    data object UsernameTaken : AuthResult
    data object InvalidCredentials : AuthResult
}
