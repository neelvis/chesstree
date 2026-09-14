package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformImeOptions
import platform.UIKit.UITextContentTypeNewPassword
import platform.UIKit.UITextContentTypePassword
import platform.UIKit.UITextContentTypeUsername

@OptIn(ExperimentalComposeUiApi::class)
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
    ComposeAuthenticationForm(
        mode = mode,
        username = username,
        password = password,
        enabled = enabled,
        usernameModifier = Modifier,
        passwordModifier = Modifier,
        usernameKeyboardOptions = KeyboardOptions(
            platformImeOptions = PlatformImeOptions {
                textContentType(UITextContentTypeUsername)
            },
        ),
        passwordKeyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            platformImeOptions = PlatformImeOptions {
                textContentType(
                    if (mode == AuthMode.REGISTER) {
                        UITextContentTypeNewPassword
                    } else {
                        UITextContentTypePassword
                    },
                )
            },
        ),
        onUsernameChange = onUsernameChange,
        onPasswordChange = onPasswordChange,
        onSubmit = { onSubmit {} },
    )
}
