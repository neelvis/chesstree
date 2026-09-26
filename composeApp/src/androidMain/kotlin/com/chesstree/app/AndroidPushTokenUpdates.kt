package com.chesstree.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged

object AndroidPushTokenUpdates {
    private val mutableToken = MutableStateFlow<String?>(null)
    val tokens = mutableToken.asStateFlow().filterNotNull().distinctUntilChanged()

    fun publish(token: String) {
        mutableToken.value = token
    }
}
