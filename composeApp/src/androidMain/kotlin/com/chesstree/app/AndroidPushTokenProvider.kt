package com.chesstree.app

import com.google.firebase.messaging.FirebaseMessaging
import com.chesstree.multiplayer.data.PushTokenProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidPushTokenProvider(
    private val askNotificationPermission: suspend () -> Boolean,
) : PushTokenProvider {
    override val platform: String = "ANDROID"

    override suspend fun requestPermission(): Boolean = askNotificationPermission()

    override suspend fun currentToken(): String? = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (continuation.isActive) {
                    continuation.resume(if (task.isSuccessful) task.result else null)
                }
            }
    }

    override fun tokenUpdates(): Flow<String> = AndroidPushTokenUpdates.tokens
}
