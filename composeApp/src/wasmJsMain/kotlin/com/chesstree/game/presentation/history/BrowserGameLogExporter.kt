package com.chesstree.game.presentation.history

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

@OptIn(ExperimentalWasmJsInterop::class)
class BrowserGameLogExporter : GameLogExporter {
    override suspend fun copy(contents: String): GameLogExportResult {
        window.navigator.clipboard.writeText(contents).await()
        return GameLogExportResult.COPIED
    }

    override suspend fun save(contents: String): GameLogExportResult {
        val blob = createTextBlob(contents)
        val url = URL.createObjectURL(blob)
        val anchor = document.createElement("a") as HTMLAnchorElement
        anchor.href = url
        anchor.download = fileName()
        anchor.style.display = "none"
        document.body?.appendChild(anchor)
        anchor.click()
        anchor.remove()
        URL.revokeObjectURL(url)
        return GameLogExportResult.FILE_SAVED
    }

    private fun fileName(): String {
        val timestamp = currentIsoTimestamp()
            .substring(0, 19)
            .replace('T', '_')
            .replace(':', '-')
        return "chess_party_${timestamp}.txt"
    }
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun createTextBlob(contents: String): Blob =
    js("new Blob([contents], { type: 'text/plain;charset=utf-8' })")

@OptIn(ExperimentalWasmJsInterop::class)
private fun currentIsoTimestamp(): String = js("new Date().toISOString()")
