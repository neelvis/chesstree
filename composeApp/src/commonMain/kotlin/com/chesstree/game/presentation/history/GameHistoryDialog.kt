package com.chesstree.game.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chesstree.app.localized
import com.chesstree.app.localizedMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GameHistoryDialog(
    log: String,
    exporter: GameLogExporter,
    onRestore: (String) -> String,
    onDismiss: () -> Unit,
    exportContents: String? = log,
) {
    val scope = rememberCoroutineScope()
    var importMode by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }

    fun export(action: suspend GameLogExporter.(String) -> GameLogExportResult) {
        val contents = exportContents ?: return
        if (exportInProgress) return
        scope.launch {
            exportInProgress = true
            message = try {
                when (exporter.action(contents)) {
                    GameLogExportResult.COPIED -> "i18n:logs_copied"
                    GameLogExportResult.FILE_SAVED -> "i18n:logs_saved"
                    GameLogExportResult.FILE_DIALOG_OPENED -> "i18n:choose_save_location"
                    GameLogExportResult.UNAVAILABLE -> "i18n:action_unavailable"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                "i18n:action_failed"
            } finally {
                exportInProgress = false
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(localized(if (importMode) "restore_dialog_title" else "history_dialog_title"),
                    style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = if (importMode) importText else log,
                    onValueChange = { value -> if (importMode) importText = value },
                    readOnly = !importMode,
                    label = {
                        Text(localized(if (importMode) "paste_log" else "history_moves"))
                    },
                    placeholder = {
                        Text(if (importMode) "1: W.e4" else localized("no_moves"))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                )
                if (exportContents == null) Text(localized("game_record_too_large"))
                message?.let { Text(localizedMessage(it)) }
                if (importMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                message = onRestore(importText)
                                importMode = false
                            },
                        ) {
                            Text(localized("restore"))
                        }
                        TextButton(onClick = { importMode = false }) { Text(localized("back")) }
                    }
                } else {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { export(GameLogExporter::save) },
                                enabled = !exportInProgress && exportContents != null,
                            ) {
                                Text(localized("save"))
                            }
                            TextButton(
                                onClick = { export(GameLogExporter::copy) },
                                enabled = !exportInProgress && exportContents != null,
                            ) {
                                Text(localized("copy"))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = {
                                    importText = ""
                                    message = null
                                    importMode = true
                                },
                            ) {
                                Text(localized("restore_from_log"))
                            }
                            TextButton(onClick = onDismiss) { Text(localized("close")) }
                        }
                    }
                }
            }
        }
    }
}
