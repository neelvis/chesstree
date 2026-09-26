package com.chesstree.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object ChessTreeColors {
    val Canvas = Color(0xFFF7F4EF)
    val Surface = Color(0xFFFFFDF9)
    val SurfaceMuted = Color(0xFFEAE6DF)
    val Sage = Color(0xFF626D5D)
    val SageContainer = Color(0xFFE7E9E0)
    val OnSageContainer = Color(0xFF3D413A)
    val Ink = Color(0xFF37342F)
    val InkMuted = Color(0xFF807970)
    val Stone = Color(0xFF8B837A)
    val Divider = Color(0xFFEEEAE3)
}

private val ChessTreeLightScheme = lightColorScheme(
    primary = ChessTreeColors.Sage,
    onPrimary = Color.White,
    primaryContainer = ChessTreeColors.SageContainer,
    onPrimaryContainer = ChessTreeColors.OnSageContainer,
    secondary = Color(0xFF8B8175),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAE6DF),
    onSecondaryContainer = ChessTreeColors.Ink,
    background = ChessTreeColors.Canvas,
    onBackground = ChessTreeColors.Ink,
    surface = ChessTreeColors.Surface,
    onSurface = ChessTreeColors.Ink,
    surfaceVariant = ChessTreeColors.SurfaceMuted,
    onSurfaceVariant = ChessTreeColors.InkMuted,
    outline = Color(0xFFD4CEC6),
    outlineVariant = ChessTreeColors.Divider,
    error = Color(0xFF9A5E55),
    onError = Color.White,
)

@Composable
internal fun ChessTreeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChessTreeLightScheme,
        content = content,
    )
}
