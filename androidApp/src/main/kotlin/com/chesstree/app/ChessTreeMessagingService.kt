package com.chesstree.app

import com.google.firebase.messaging.FirebaseMessagingService

class ChessTreeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        AndroidPushTokenUpdates.publish(token)
    }
}
