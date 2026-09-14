package com.chesstree.multiplayer.data

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

internal actual fun defaultHttpClientEngine(): HttpClientEngineFactory<*> = CIO
