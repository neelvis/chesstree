package com.chesstree.app

import com.google.firebase.messaging.FirebaseMessagingService
import com.chesstree.app.AndroidPushTokenUpdates

class ChessTreeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidPushTokenUpdates.publish(token)
    }
}
