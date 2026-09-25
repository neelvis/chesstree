package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CredentialManager
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException

@Composable
internal actual fun AuthenticationForm(
    mode: AuthMode,
    username: String,
    password: String,
    enabled: Boolean,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: (onSuccess: suspend () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val autofillManager = LocalAutofillManager.current

    ComposeAuthenticationForm(
        mode = mode,
        username = username,
        password = password,
        enabled = enabled,
        usernameModifier = Modifier.semantics {
            contentType = if (mode == AuthMode.REGISTER) {
                ContentType.NewUsername
            } else {
                ContentType.Username
            }
        },
        passwordModifier = Modifier.semantics {
            contentType = if (mode == AuthMode.REGISTER) {
                ContentType.NewPassword
            } else {
                ContentType.Password
            }
        },
        passwordKeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        onUsernameChange = onUsernameChange,
        onPasswordChange = onPasswordChange,
        onSubmit = {
            onSubmit {
                try {
                    credentialManager.createCredential(
                        context = context,
                        request = CreatePasswordRequest(id = username, password = password),
                    )
                } catch (_: CreateCredentialCancellationException) {
                    // The user dismissed the save prompt.
                } catch (_: CreateCredentialException) {
                    // Signing in remains successful when saving is unavailable or declined.
                    autofillManager?.commit()
                }
            }
        },
    )
}
