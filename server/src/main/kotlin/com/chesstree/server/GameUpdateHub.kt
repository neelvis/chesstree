package com.chesstree.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

class GameUpdateHub {
    private val versions = ConcurrentHashMap<String, MutableStateFlow<Long>>()

    fun updates(code: String): StateFlow<Long> = flow(code)

    fun publish(code: String) {
        flow(code).update { it + 1 }
    }

    private fun flow(code: String): MutableStateFlow<Long> =
        versions.computeIfAbsent(code) { MutableStateFlow(0) }
}
