package com.chesstree.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chesstree.game.presentation.board.PieceSet

enum class AppTab {
    SETTINGS,
    GAMES,
}

@Composable
internal fun AppTabBar(
    selected: AppTab,
    onSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTabItem(
                tab = AppTab.GAMES,
                selected = selected == AppTab.GAMES,
                label = "Игры",
                onSelected = onSelected,
                modifier = Modifier.weight(1f),
            )
            AppTabItem(
                tab = AppTab.SETTINGS,
                selected = selected == AppTab.SETTINGS,
                label = "Настройки",
                onSelected = onSelected,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppTabItem(
    tab: AppTab,
    selected: Boolean,
    label: String,
    onSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        TextButton(
            onClick = { onSelected(tab) },
            modifier = Modifier.width(96.dp).height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.textButtonColors(
                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppTabIcon(tab, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun AppTabIcon(tab: AppTab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val scale = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        when (tab) {
            AppTab.GAMES -> {
                drawCircle(color, radius = 3f * scale, center = point(12f, 5f))
                val pawn = Path().apply {
                    moveTo(10f * scale, 8f * scale)
                    cubicTo(10.2f * scale, 9f * scale, 10.8f * scale, 9.6f * scale, 11.5f * scale, 10f * scale)
                    lineTo(8f * scale, 17f * scale)
                    cubicTo(7.3f * scale, 18f * scale, 7f * scale, 19f * scale, 7f * scale, 20f * scale)
                    lineTo(17f * scale, 20f * scale)
                    cubicTo(17f * scale, 19f * scale, 16.7f * scale, 18f * scale, 16f * scale, 17f * scale)
                    lineTo(12.5f * scale, 10f * scale)
                    cubicTo(13.2f * scale, 9.6f * scale, 13.8f * scale, 9f * scale, 14f * scale, 8f * scale)
                    close()
                }
                drawPath(pawn, color)
                drawRoundRect(
                    color = color,
                    topLeft = point(4f, 20f),
                    size = Size(16f * scale, 2.5f * scale),
                    cornerRadius = CornerRadius(1.25f * scale),
                )
            }
            AppTab.SETTINGS -> {
                val outline = ChessTreeColors.Sage
                val lightSquare = ChessTreeColors.Surface
                drawRoundRect(
                    color = outline,
                    topLeft = point(2f, 2f),
                    size = Size(20f * scale, 20f * scale),
                    cornerRadius = CornerRadius(2f * scale),
                )
                val cellSize = 7f
                val gap = 2f
                val start = 4f
                for (row in 0..1) {
                    for (column in 0..1) {
                        val isGreen = (row + column) % 2 == 0
                        drawRoundRect(
                            color = if (isGreen) outline else lightSquare,
                            topLeft = point(start + column * (cellSize + gap), start + row * (cellSize + gap)),
                            size = Size(cellSize * scale, cellSize * scale),
                            cornerRadius = CornerRadius(0.75f * scale),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun GamesHomeScreen(
    onPlaySolo: () -> Unit,
    onMultiplayer: (() -> Unit)?,
    username: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Привет, ${username ?: "игрок"}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text("Во что играем?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Выберите игру и начните партию", color = MaterialTheme.colorScheme.onSurfaceVariant)

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    "♟",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Шахматы", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("одиночная и сетевая игра", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onPlaySolo, modifier = Modifier.fillMaxWidth()) {
                    Text("Одиночная игра")
                }
                Button(
                    onClick = { onMultiplayer?.invoke() },
                    enabled = onMultiplayer != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (onMultiplayer != null) "Мультиплеер" else "Мультиплеер недоступен")
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(
    settings: GameSettings,
    onSettingsChanged: (GameSettings) -> Unit,
    onOpenProfile: (() -> Unit)?,
    username: String?,
    accountLoading: Boolean,
    accountError: String?,
    onLogout: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("ПРОФИЛЬ", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(username ?: if (accountLoading) "Проверяем вход…" else "Гость", fontWeight = FontWeight.SemiBold)
                    Text(
                        if (username != null) "Вы вошли в аккаунт" else "Войдите, чтобы играть онлайн",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (username != null && onLogout != null) {
                    TextButton(onClick = onLogout, enabled = !accountLoading) { Text("Выйти") }
                } else if (onOpenProfile != null) {
                    TextButton(onClick = onOpenProfile, enabled = !accountLoading) { Text("Войти") }
                }
            }
        }
        accountError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("ВНЕШНИЙ ВИД", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        SettingsGroup {
            SettingRow("Набор фигур", if (settings.pieceSet == PieceSet.FAIRY) "Премиум" else "Классический") {
                Switch(
                    checked = settings.pieceSet == PieceSet.FAIRY,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(pieceSet = if (enabled) PieceSet.FAIRY else PieceSet.STANDARD))
                    },
                )
            }
        }
        Text("ИГРОВОЙ ПРОЦЕСС", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        SettingsGroup {
            SettingRow("Показывать возможные ходы", "Посказка для выбранной фигуры во время вашего хода") {
                Switch(
                    checked = settings.showCurrentPossibleMoves,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(showCurrentPossibleMoves = enabled))
                    },
                )
            }
            SettingRow(
                "Показывать пути",
                "Отображать линии, по которым может идти любая выбранная фигура на доске из своей текущей позиции",
            ) {
                Switch(
                    checked = settings.showMoveLines,
                    onCheckedChange = { enabled ->
                        onSettingsChanged(settings.copy(showMoveLines = enabled))
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(content = content)
    }
}

@Composable
private fun SettingRow(
    label: String,
    value: String? = null,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(4.dp))
        Box(Modifier.size(width = 52.dp, height = 40.dp), contentAlignment = Alignment.CenterEnd) { control() }
    }
}

@Preview
@Composable
private fun AppTabBarPreview() {
    ChessTreeTheme {
        AppTabBar(selected = AppTab.GAMES, onSelected = {})
    }
}

@Preview
@Composable
private fun SettingsScreenPreview() {
    ChessTreeTheme {
        SettingsScreen(
            settings = GameSettings(),
            onSettingsChanged = {},
            onOpenProfile = {},
            username = null,
            accountLoading = false,
            accountError = null,
            onLogout = null,
        )
    }
}

@Preview
@Composable
private fun GamesHomeScreenPreview() {
    ChessTreeTheme {
        GamesHomeScreen(onPlaySolo = {}, onMultiplayer = {}, username = "Игрок")
    }
}
