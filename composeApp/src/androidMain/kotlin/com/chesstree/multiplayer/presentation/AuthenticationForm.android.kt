package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.autofill.contentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal actual fun AuthenticationForm(
    mode: AuthMode,
    username: String,
    password: String,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: (onSuccess: () -> Unit) -> Unit,
) {
    val autofillManager = LocalAutofillManager.current
    ComposeAuthenticationForm(
        mode = mode,
        username = username,
        password = password,
        enabled = enabled,
        usernameModifier = Modifier.contentType(
            if (mode == AuthMode.REGISTER) ContentType.NewUsername else ContentType.Username,
        ),
        passwordModifier = Modifier.contentType(
            if (mode == AuthMode.REGISTER) ContentType.NewPassword else ContentType.Password,
        ),
        passwordKeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        onUsernameChange = onUsernameChange,
        onPasswordChange = onPasswordChange,
        onSubmit = { onSubmit { autofillManager?.commit() } },
    )
}
