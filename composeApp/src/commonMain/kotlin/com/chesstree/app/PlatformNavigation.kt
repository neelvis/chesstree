package com.chesstree.app

import androidx.compose.runtime.Composable

internal expect val hasSystemBackNavigation: Boolean

@Composable
internal expect fun PlatformBackHandler(
    enabled: Boolean,
    onBack: () -> Unit,
)
