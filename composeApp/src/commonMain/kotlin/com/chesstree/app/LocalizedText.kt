package com.chesstree.app

import androidx.compose.runtime.Composable
import com.chesstree.resources.Res
import com.chesstree.resources.allStringResources
import org.jetbrains.compose.resources.stringResource

/** Resolves shared UI text from the device locale. */
@Composable
internal fun localized(key: String, vararg args: Any): String =
    stringResource(Res.allStringResources.getValue(key), *args)

/** Resolves a message token stored by non-UI layers, leaving external messages intact. */
@Composable
internal fun localizedMessage(message: String): String {
    if (!message.startsWith("i18n:")) return message
    val parts = message.removePrefix("i18n:").split('|')
    val key = parts.first()
    val args = parts.drop(1).map { arg ->
        if (arg.startsWith("i18n:")) localizedMessage(arg) else arg
    }.toTypedArray()
    return if (args.isEmpty()) localized(key) else localized(key, *args)
}
