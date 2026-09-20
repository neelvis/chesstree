package com.chesstree.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.chesstree.game.presentation.history.GameLogExportResult
import com.chesstree.game.presentation.history.GameLogExporter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AndroidGameLogExporter(
    private val activity: ComponentActivity,
) : GameLogExporter {
    private var pendingContents: String? = null
    private val createDocument = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val contents = pendingContents
        pendingContents = null
        if (uri != null && contents != null) {
            runCatching {
                activity.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(contents)
                }
            }
        }
    }

    override suspend fun copy(contents: String): GameLogExportResult {
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("История игры", contents))
        return GameLogExportResult.COPIED
    }

    override suspend fun save(contents: String): GameLogExportResult {
        pendingContents = contents
        createDocument.launch(fileName())
        return GameLogExportResult.FILE_DIALOG_OPENED
    }

    private fun fileName(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return "chess_party_${timestamp}.txt"
    }
}
