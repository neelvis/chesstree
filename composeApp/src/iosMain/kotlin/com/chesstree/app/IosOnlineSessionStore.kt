package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStoreException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSMutableDictionary
import platform.Foundation.dataWithBytes
import platform.Foundation.dictionaryWithCapacity
import platform.Foundation.setObject
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
class IosOnlineSessionStore : OnlineSessionStore {
    override suspend fun load(): AuthResponse? =
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val query = baseQuery()
            val status = query.withCFDictionary { dictionary ->
                CFDictionarySetValue(dictionary.reinterpret(), kSecReturnData, kCFBooleanTrue)
                SecItemCopyMatching(dictionary, result.ptr)
            }
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) {
                throw OnlineSessionStoreException("Keychain read OSStatus=$status")
            }
            val data = CFBridgingRelease(result.value) as? NSData
                ?: throw OnlineSessionStoreException("Keychain returned no session data")
            val bytes = data.bytes?.readBytes(data.length.toInt())
                ?: throw OnlineSessionStoreException("Keychain returned empty session data")
            try {
                json.decodeFromString<AuthResponse>(bytes.decodeToString())
            } catch (_: Throwable) {
                throw OnlineSessionStoreException("Keychain session data could not be decoded")
            }
        }

    override suspend fun save(authentication: AuthResponse) {
        val bytes = json.encodeToString(authentication).encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }

        val attributes = NSMutableDictionary.dictionaryWithCapacity(2u).apply {
            putSecurityValue(kSecValueData, data)
            putSecurityValue(kSecAttrAccessible, bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
        }
        val updateStatus = baseQuery().withCFDictionary { query ->
            attributes.withCFDictionary { updates -> SecItemUpdate(query, updates) }
        }
        when (updateStatus) {
            errSecSuccess -> Unit
            errSecItemNotFound -> {
                val addStatus = baseQuery().apply {
                    putSecurityValue(kSecValueData, data)
                    putSecurityValue(kSecAttrAccessible, bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
                }.withCFDictionary { SecItemAdd(it, null) }
                when (addStatus) {
                    errSecSuccess -> Unit
                    errSecDuplicateItem -> updateExisting(data)
                    else -> throw OnlineSessionStoreException("Keychain write failed (OSStatus=$addStatus)")
                }
            }
            else -> throw OnlineSessionStoreException("Keychain update failed (OSStatus=$updateStatus)")
        }

        val restored = try {
            load()
        } catch (error: OnlineSessionStoreException) {
            throw error
        } catch (_: Throwable) {
            throw OnlineSessionStoreException("Keychain read-back failed unexpectedly")
        }
        if (restored == null) throw OnlineSessionStoreException("Keychain read-back found no saved item")
        if (restored != authentication) throw OnlineSessionStoreException("Keychain read-back data did not match")
    }

    override suspend fun clear() {
        val status = baseQuery().withCFDictionary { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) {
            "Unable to clear online session in Keychain: $status"
        }
    }

    private fun baseQuery(): NSMutableDictionary = NSMutableDictionary.dictionaryWithCapacity(3u).apply {
        putSecurityValue(kSecClass, bridged(kSecClassGenericPassword))
        putSecurityValue(kSecAttrService, SERVICE)
        putSecurityValue(kSecAttrAccount, ACCOUNT)
    }

    private fun updateExisting(data: NSData) {
        val updates = NSMutableDictionary.dictionaryWithCapacity(2u).apply {
            putSecurityValue(kSecValueData, data)
            putSecurityValue(kSecAttrAccessible, bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
        }
        val status = baseQuery().withCFDictionary { query ->
            updates.withCFDictionary { attributes -> SecItemUpdate(query, attributes) }
        }
        if (status != errSecSuccess) {
            throw OnlineSessionStoreException("Keychain retry update failed (OSStatus=$status)")
        }
    }

    private fun NSMutableDictionary.putSecurityValue(key: platform.CoreFoundation.CFStringRef?, value: Any) {
        setObject(value, forKeyedSubscript = bridged(key) as NSCopyingProtocol)
    }

    private fun bridged(value: platform.CoreFoundation.CFTypeRef?): Any =
        checkNotNull(CFBridgingRelease(CFRetain(value)))

    private inline fun <T> NSMutableDictionary.withCFDictionary(block: (CFDictionaryRef) -> T): T {
        val retained = checkNotNull(CFBridgingRetain(this))
        return try {
            block(retained.reinterpret())
        } finally {
            CFBridgingRelease(retained)
        }
    }

    private companion object {
        const val SERVICE = "com.chesstree.app.online-session"
        const val ACCOUNT = "current-user"
        val json = Json { ignoreUnknownKeys = false; explicitNulls = false }
    }
}
