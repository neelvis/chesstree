package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
internal expect fun AuthenticationForm(
    mode: AuthMode,
    username: String,
    password: String,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: (onSuccess: suspend () -> Unit) -> Unit,
)

@Composable
internal fun ComposeAuthenticationForm(
    mode: AuthMode,
    username: String,
    password: String,
    enabled: Boolean,
    usernameModifier: Modifier,
    passwordModifier: Modifier,
    usernameKeyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    passwordKeyboardOptions: KeyboardOptions,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("Логин") },
        singleLine = true,
        keyboardOptions = usernameKeyboardOptions,
        modifier = usernameModifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("Пароль") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = passwordKeyboardOptions,
        modifier = passwordModifier.fillMaxWidth(),
    )
    Button(
        onClick = onSubmit,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (mode == AuthMode.LOGIN) "Войти" else "Создать аккаунт")
    }
}
