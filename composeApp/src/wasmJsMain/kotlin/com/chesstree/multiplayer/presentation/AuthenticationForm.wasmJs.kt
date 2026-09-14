package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.HtmlElementView
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement

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
    val currentUsernameChange = rememberUpdatedState(onUsernameChange)
    val currentPasswordChange = rememberUpdatedState(onPasswordChange)
    val currentSubmit = rememberUpdatedState(onSubmit)
    HtmlElementView(
        factory = {
            createAuthenticationForm(
                onUsernameChange = { currentUsernameChange.value(it) },
                onPasswordChange = { currentPasswordChange.value(it) },
                onSubmit = {
                    currentSubmit.value {
                        window.history.replaceState(null, document.title, window.location.href)
                    }
                },
            )
        },
        modifier = Modifier.fillMaxWidth().height(180.dp),
        update = { form ->
            form.authenticationUsername().setValueIfChanged(username)
            form.authenticationPassword().apply {
                setValueIfChanged(password)
                autocomplete = if (mode == AuthMode.LOGIN) "current-password" else "new-password"
            }
            form.authenticationSubmit().apply {
                disabled = !enabled
                textContent = if (mode == AuthMode.LOGIN) "Войти" else "Создать аккаунт"
            }
        },
    )
}

private fun createAuthenticationForm(
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
): HTMLFormElement {
    val form = document.createElement("form") as HTMLFormElement
    form.className = "authentication-form"
    form.autocomplete = "on"

    val username = (document.createElement("input") as HTMLInputElement).apply {
        id = "authentication-username"
        name = "username"
        type = "text"
        autocomplete = "username"
        placeholder = "Логин"
        setAttribute("aria-label", "Логин")
        maxLength = 24
        required = true
        addEventListener("input", { onUsernameChange(value) })
    }
    val password = (document.createElement("input") as HTMLInputElement).apply {
        id = "authentication-password"
        name = "password"
        type = "password"
        placeholder = "Пароль"
        setAttribute("aria-label", "Пароль")
        maxLength = 128
        required = true
        addEventListener("input", { onPasswordChange(value) })
    }
    val submit = (document.createElement("button") as HTMLButtonElement).apply {
        id = "authentication-submit"
        type = "submit"
    }
    form.addEventListener("submit", { event ->
        event.preventDefault()
        if (!submit.disabled) onSubmit()
    })
    form.append(username, password, submit)
    return form
}

private fun HTMLFormElement.authenticationUsername(): HTMLInputElement =
    querySelector("#authentication-username") as HTMLInputElement

private fun HTMLFormElement.authenticationPassword(): HTMLInputElement =
    querySelector("#authentication-password") as HTMLInputElement

private fun HTMLFormElement.authenticationSubmit(): HTMLButtonElement =
    querySelector("#authentication-submit") as HTMLButtonElement

private fun HTMLInputElement.setValueIfChanged(newValue: String) {
    if (value != newValue) value = newValue
}
