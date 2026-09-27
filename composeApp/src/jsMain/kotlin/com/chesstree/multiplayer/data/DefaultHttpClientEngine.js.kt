package com.chesstree.multiplayer.data

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.js.Js

internal actual fun defaultHttpClientEngine(browserSession: Boolean): HttpClientEngine =
    Js.create {
        if (browserSession) configureRequest { credentials = "include" }
    }
