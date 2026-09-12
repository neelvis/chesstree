package com.chesstree.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityTest {
    @Test
    fun argon2idHashesAndVerifiesPassword() {
        val hasher = Argon2PasswordHasher()
        val password = "correct-horse".toCharArray()
        val hash = hasher.hash(password)

        assertTrue(hash.startsWith("\$argon2id\$"))
        assertTrue(hasher.verify(hash, "correct-horse".toCharArray()))
        assertFalse(hasher.verify(hash, "wrong-password".toCharArray()))
    }

    @Test
    fun generatedGameCodesUseSevenUnambiguousCharacters() {
        val codes = List(100) { TokenGenerator().gameCode() }

        assertTrue(codes.all { it.matches(Regex("[0-9A-HJKMNP-TV-Z]{7}")) })
    }
}
