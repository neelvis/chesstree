package com.chesstree.app

import com.chesstree.multiplayer.data.PushTokenProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Foundation.NSNotificationCenter
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

object IosPushTokenProvider : PushTokenProvider {
    override val platform: String = "IOS"

    override suspend fun requestPermission(): Boolean = suspendCancellableCoroutine { continuation ->
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { granted, _ ->
                if (granted) {
                    NSNotificationCenter.defaultCenter().postNotificationName(
                        "ChessTreeRegisterForRemoteNotifications",
                        null,
                    )
                }
                continuation.resume(granted)
            }
    }

    override suspend fun currentToken(): String? = iosPushToken.value

    override fun tokenUpdates(): Flow<String> = iosPushToken.filterNotNull().distinctUntilChanged()
}
