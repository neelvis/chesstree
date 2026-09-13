package com.chesstree.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.UserResponse
import com.chesstree.multiplayer.data.OnlineSessionStore
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidOnlineSessionStore(context: Context) : OnlineSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): AuthResponse? = runCatching {
        val encrypted = preferences.getString(ENCRYPTED_VALUE, null) ?: return null
        val iv = preferences.getString(INITIALIZATION_VECTOR, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                encryptionKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
        }
        val decoded = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
        val json = JSONObject(decoded)
        AuthResponse(
            accessToken = json.getString("accessToken"),
            user = UserResponse(
                id = json.getString("userId"),
                username = json.getString("username"),
            ),
        )
    }.getOrElse {
        clearStoredValue()
        null
    }

    override suspend fun save(authentication: AuthResponse) {
        try {
            val plainText = JSONObject()
                .put("accessToken", authentication.accessToken)
                .put("userId", authentication.user.id)
                .put("username", authentication.user.username)
                .toString()
                .encodeToByteArray()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, encryptionKey())
            }
            val encrypted = cipher.doFinal(plainText)
            preferences.edit()
                .putString(ENCRYPTED_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(INITIALIZATION_VECTOR, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .apply()
        } catch (error: Throwable) {
            clearStoredValue()
            throw error
        }
    }

    override suspend fun clear() {
        clearStoredValue()
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun clearStoredValue() {
        preferences.edit().remove(ENCRYPTED_VALUE).remove(INITIALIZATION_VECTOR).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "chesstree_online_session"
        const val ENCRYPTED_VALUE = "encrypted_value"
        const val INITIALIZATION_VECTOR = "initialization_vector"
        const val KEY_ALIAS = "chesstree.online.session.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
