package com.chesstree.app

import androidx.compose.runtime.Composable

internal actual val hasSystemBackNavigation: Boolean = false

@Composable
internal actual fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
) = Unit
