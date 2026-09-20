package com.chesstree.game.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun GameHistoryDialog(
    log: String,
    exporter: GameLogExporter,
    onRestore: (String) -> String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var importMode by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var exportInProgress by remember { mutableStateOf(false) }

    fun export(action: suspend GameLogExporter.(String) -> GameLogExportResult) {
        if (exportInProgress) return
        scope.launch {
            exportInProgress = true
            message = try {
                when (exporter.action(log)) {
                    GameLogExportResult.COPIED -> "Логи скопированы"
                    GameLogExportResult.FILE_SAVED -> "Файл с логами сохранён"
                    GameLogExportResult.FILE_DIALOG_OPENED -> "Выберите место для сохранения файла"
                    GameLogExportResult.UNAVAILABLE -> "Действие недоступно на этой платформе"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                "Не удалось выполнить действие"
            } finally {
                exportInProgress = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (importMode) "Восстановление партии" else "История игры") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = if (importMode) importText else log,
                    onValueChange = { value -> if (importMode) importText = value },
                    readOnly = !importMode,
                    label = {
                        Text(if (importMode) "Вставьте лог или его часть" else "Ходы")
                    },
                    placeholder = {
                        Text(if (importMode) "1: W.e4" else "Ходов пока нет")
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 360.dp),
                )
                message?.let { Text(it) }
            }
        },
        confirmButton = {
            if (importMode) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            message = onRestore(importText)
                            importMode = false
                        },
                    ) {
                        Text("Восстановить")
                    }
                    TextButton(onClick = { importMode = false }) { Text("Назад") }
                }
            } else {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { export(GameLogExporter::save) },
                            enabled = !exportInProgress,
                        ) {
                            Text("Сохранить")
                        }
                        TextButton(
                            onClick = { export(GameLogExporter::copy) },
                            enabled = !exportInProgress,
                        ) {
                            Text("Скопировать")
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
                            Text("Восстановить из лога")
                        }
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                    }
                }
            }
        },
    )
}
