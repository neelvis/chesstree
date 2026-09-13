package com.chesstree.app

import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.OnlineSessionStore
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
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFRetain
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
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

@OptIn(ExperimentalForeignApi::class)
class IosOnlineSessionStore : OnlineSessionStore {
    override suspend fun load(): AuthResponse? = runCatching {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val query = baseQuery().apply {
                putSecurityValue(kSecReturnData, true)
                putSecurityValue(kSecMatchLimit, bridged(kSecMatchLimitOne))
            }
            val status = query.withCFDictionary { SecItemCopyMatching(it, result.ptr) }
            if (status != errSecSuccess) return@memScoped null
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            val bytes = data.bytes?.readBytes(data.length.toInt()) ?: return@memScoped null
            json.decodeFromString<AuthResponse>(bytes.decodeToString())
        }
    }.getOrElse {
        clear()
        null
    }

    override suspend fun save(authentication: AuthResponse) {
        val bytes = json.encodeToString(authentication).encodeToByteArray()
        val data = bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
        }
        baseQuery().withCFDictionary { SecItemDelete(it) }
        val status = baseQuery().apply {
            putSecurityValue(kSecValueData, data)
            putSecurityValue(kSecAttrAccessible, bridged(kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly))
        }.withCFDictionary { SecItemAdd(it, null) }
        check(status == errSecSuccess) { "Unable to save online session in Keychain: $status" }
    }

    override suspend fun clear() {
        baseQuery().withCFDictionary { SecItemDelete(it) }
    }

    private fun baseQuery(): NSMutableDictionary = NSMutableDictionary.dictionaryWithCapacity(3u).apply {
        putSecurityValue(kSecClass, bridged(kSecClassGenericPassword))
        putSecurityValue(kSecAttrService, SERVICE)
        putSecurityValue(kSecAttrAccount, ACCOUNT)
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
