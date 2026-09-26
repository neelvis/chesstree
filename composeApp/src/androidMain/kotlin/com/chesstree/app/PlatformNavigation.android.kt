package com.chesstree.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

internal actual val hasSystemBackNavigation: Boolean = true

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) {
    BackHandler(enabled = enabled, onBack = onBack)
}
