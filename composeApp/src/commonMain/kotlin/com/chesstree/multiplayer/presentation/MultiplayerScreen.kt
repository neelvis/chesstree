package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.threePlayerBoardAspectRatio
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.legalMoveHintsFor
import com.chesstree.game.presentation.board.toBoardPieces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun MultiplayerScreen(
    api: ChessTreeApi,
    sessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    initialGameCode: String = "",
    gameLinkSharer: GameLinkSharer? = null,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(api, scope, initialGameCode, sessionStore) {
        MultiplayerController(api, scope, initialGameCode, sessionStore)
    }
    val state by controller.state.collectAsState()
    LaunchedEffect(state.game?.code, state.game?.status) {
        if (state.game?.status == "ACTIVE") {
            while (true) {
                delay(5_000)
                controller.refreshGame()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Онлайн-игра", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = onClose) { Text("К доске") }
            }
            Spacer(Modifier.height(24.dp))
            Column(
                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.authentication == null) {
                    AuthenticationContent(state, controller)
                } else {
                    LobbyContent(state, controller, gameLinkSharer)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.reconnecting && state.game != null) {
                    Text("Переподключение к партии…", color = MaterialTheme.colorScheme.tertiary)
                }
                if (state.loading && !state.submittingMove) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
        }
    }
}

@Composable
private fun AuthenticationContent(state: MultiplayerUiState, controller: MultiplayerController) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.authMode == AuthMode.LOGIN) {
            Button(onClick = { controller.setAuthMode(AuthMode.LOGIN) }) { Text("Вход") }
            OutlinedButton(onClick = { controller.setAuthMode(AuthMode.REGISTER) }) { Text("Регистрация") }
        } else {
            OutlinedButton(onClick = { controller.setAuthMode(AuthMode.LOGIN) }) { Text("Вход") }
            Button(onClick = { controller.setAuthMode(AuthMode.REGISTER) }) { Text("Регистрация") }
        }
    }
    AuthenticationForm(
        mode = state.authMode,
        username = state.username,
        password = state.password,
        enabled = !state.loading && state.username.isNotBlank() && state.password.isNotBlank(),
        onUsernameChange = controller::setUsername,
        onPasswordChange = controller::setPassword,
        onSubmit = controller::submitAuthentication,
    )
}

@Composable
private fun LobbyContent(
    state: MultiplayerUiState,
    controller: MultiplayerController,
    gameLinkSharer: GameLinkSharer?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Игрок: ${state.authentication?.user?.username}")
        TextButton(onClick = controller::logout, enabled = !state.loading) { Text("Выйти") }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Создать игру", style = MaterialTheme.typography.titleMedium)
            Button(onClick = controller::createGame, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text("Создать игру")
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Присоединиться к игре", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.gameCode,
                onValueChange = controller::setGameCode,
                label = { Text("Код игры") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = controller::joinGame, enabled = !state.loading && state.gameCode.length == 7, modifier = Modifier.fillMaxWidth()) {
                Text("Присоединиться")
            }
        }
    }
    GameHistory(state, controller)
    state.game?.let { game ->
        if ((game.status == "ACTIVE" || game.status == "FINISHED") && state.session != null) {
            OnlineGame(state, controller)
        } else {
            GameLobby(game, controller, state.loading, gameLinkSharer)
        }
    }
}

@Composable
private fun GameHistory(state: MultiplayerUiState, controller: MultiplayerController) {
    var showAllUnfinished by remember { mutableStateOf(false) }
    var showAllFinished by remember { mutableStateOf(false) }
    val unfinished = state.games.filter { it.status != "FINISHED" }
    val finished = state.games.filter { it.status == "FINISHED" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("История игр", style = MaterialTheme.typography.titleMedium)
            Text("Незавершённые", style = MaterialTheme.typography.titleSmall)
            if (unfinished.isEmpty()) Text("Нет незавершённых игр", style = MaterialTheme.typography.bodySmall)
            if (showAllUnfinished) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(unfinished, key = { it.id }) { game -> HistoryGameRow(game, true, controller::openGame) }
                }
            } else unfinished.take(5).forEach { game -> HistoryGameRow(game, true, controller::openGame) }
            if (unfinished.size > 5) {
                TextButton(onClick = { showAllUnfinished = !showAllUnfinished }) {
                    Text(if (showAllUnfinished) "Свернуть" else "Показать ещё")
                }
            }
            Text("Завершённые", style = MaterialTheme.typography.titleSmall)
            if (finished.isEmpty()) Text("Нет завершённых игр", style = MaterialTheme.typography.bodySmall)
            if (showAllFinished) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(finished, key = { it.id }) { game -> HistoryGameRow(game, false, controller::openGame) }
                }
            } else {
                finished.take(5).forEach { game -> HistoryGameRow(game, false, controller::openGame) }
            }
            if (finished.size > 5) {
                TextButton(onClick = { showAllFinished = !showAllFinished }) {
                    Text(if (showAllFinished) "Свернуть" else "Показать ещё")
                }
            }
        }
    }
}

@Composable
private fun HistoryGameRow(game: GameHistoryResponse, unfinished: Boolean, onOpen: (String) -> Unit) {
    TextButton(onClick = { onOpen(game.code) }, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (unfinished) {
                Surface(Modifier.size(10.dp), shape = CircleShape, color = Color(0xFF2E7D32)) {}
                Spacer(Modifier.size(8.dp))
            }
            Column {
                Text("${game.code} · ${game.startedAt.take(10).ifBlank { "Дата неизвестна" }}")
                Text(
                    game.players.joinToString(" · ") { it.user.username },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OnlineGame(state: MultiplayerUiState, controller: MultiplayerController) {
    val session = checkNotNull(state.session)
    val game = checkNotNull(state.game)
    var boardZoom by remember(game.code) { mutableStateOf(1f) }
    val assignedPlayer = game.players
        .firstOrNull { it.user.id == state.authentication?.user?.id }
        ?.color
        ?.let { runCatching { com.chesstree.game.domain.PlayerId.valueOf(it) }.getOrNull() }
    var selectedPieceId by remember(session.state) { mutableStateOf<String?>(null) }
    var pendingPromotionMoves by remember(session.state) { mutableStateOf(emptyList<Move>()) }
    val hints = remember(session.state, selectedPieceId) {
        selectedPieceId?.let(::PieceId)?.let { legalMoveHintsFor(session.state, it) }.orEmpty()
    }
    val undoRequest = state.remoteState?.undoRequest
    val canAct = !state.loading && undoRequest == null && session.state.turn?.player == assignedPlayer
    val currentUserId = state.authentication?.user?.id
    val isUndoRequester = undoRequest?.requestedByUserId == currentUserId
    val hasApprovedUndo = currentUserId in undoRequest?.approvedByUserIds.orEmpty()
    LaunchedEffect(undoRequest?.id) {
        if (undoRequest != null) {
            selectedPieceId = null
            pendingPromotionMoves = emptyList()
        }
    }

    Text("Ревизия: ${state.remoteState?.revision ?: 0}")
    val turnPlayer = session.state.turn?.player
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            modifier = Modifier.size(14.dp),
            shape = CircleShape,
            color = when (turnPlayer?.name) {
                "WHITE" -> MaterialTheme.colorScheme.surface
                "RED" -> MaterialTheme.colorScheme.error
                "BLACK" -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.outline
            },
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {}
        Text(
        when {
            undoRequest != null -> "Игра приостановлена: голосование за отмену хода"
            game.status == "FINISHED" -> "Партия завершена"
            canAct -> "Ваш ход"
            else -> "Ожидаем ход другого игрока"
        },
        )
    }
    ThreePlayerChessBoard(
        pieces = session.state.toBoardPieces(),
        selectedPieceId = selectedPieceId,
        moveHints = hints,
        trophies = session.capturedPieces.map { captured ->
            BoardTrophy(
                id = captured.id,
                type = captured.type,
                army = captured.army,
                bodyArmy = captured.bodyArmy,
                capturedByArmy = captured.capturedByArmy,
            )
        },
        onCellSelected = { cell ->
            if (!canAct || cell == null) {
                selectedPieceId = null
                return@ThreePlayerChessBoard
            }
            val selected = selectedPieceId?.let(::PieceId)
            val matchingMoves = selected?.let { pieceId ->
                LegalMoveGenerator.legalMoves(session.state, pieceId).filter { it.to == cell }
            }.orEmpty()
            if (matchingMoves.isNotEmpty()) {
                if (matchingMoves.size == 1) {
                    val move = matchingMoves.single()
                    controller.submitMove(MoveIntent(move.actor, move.from, move.to, move.promotion))
                    selectedPieceId = null
                } else {
                    pendingPromotionMoves = matchingMoves
                }
            } else {
                val piece = session.state.position.pieces.values.firstOrNull { it.coordinate == cell }
                selectedPieceId = piece
                    ?.takeIf { session.state.armies.getValue(it.army).controller == assignedPlayer }
                    ?.id?.value
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(threePlayerBoardAspectRatio(PieceSet.STANDARD, true, boardZoom)),
        onZoomChanged = { boardZoom = it },
    )
    OutlinedButton(
        onClick = controller::requestUndo,
        enabled = !state.loading && undoRequest == null && session.moves.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Отменить ход") }
    if (undoRequest != null) {
        val requesterName = game.players
            .firstOrNull { it.user.id == undoRequest.requestedByUserId }
            ?.user?.username
            ?: "Участник"
        if (isUndoRequester) {
            Text("Запрос отправлен. Ожидаем согласия двух других участников.")
        } else if (hasApprovedUndo) {
            Text("Вы согласились. Ожидаем решение второго участника.")
        } else {
            Text("$requesterName просит отменить последний ход")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { controller.voteUndo(true) },
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                ) { Text("Согласен") }
                OutlinedButton(
                    onClick = { controller.voteUndo(false) },
                    enabled = !state.loading,
                    modifier = Modifier.weight(1f),
                ) { Text("Не согласен") }
            }
        }
    }
    OutlinedButton(
        onClick = controller::refreshGame,
        enabled = !state.syncing,
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (state.syncing) "Синхронизация…" else "Синхронизировать") }

    if (pendingPromotionMoves.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingPromotionMoves = emptyList() },
            title = { Text("Превращение пешки") },
            text = { Text("Выберите новую фигуру") },
            confirmButton = {
                Column {
                    pendingPromotionMoves.forEach { move ->
                        TextButton(onClick = {
                            controller.submitMove(MoveIntent(move.actor, move.from, move.to, move.promotion))
                            pendingPromotionMoves = emptyList()
                            selectedPieceId = null
                        }) {
                            Text(checkNotNull(move.promotion).displayName())
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPromotionMoves = emptyList() }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun GameLobby(
    game: GameResponse,
    controller: MultiplayerController,
    loading: Boolean,
    gameLinkSharer: GameLinkSharer?,
) {
    val scope = rememberCoroutineScope()
    var shareMessage by remember(game.shareUrl) { mutableStateOf<String?>(null) }
    var sharing by remember(game.shareUrl) { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Text("Код: ${game.code}", style = MaterialTheme.typography.titleLarge)
    Text(game.shareUrl, style = MaterialTheme.typography.bodySmall)
    if (gameLinkSharer != null) {
        OutlinedButton(
            onClick = {
                shareMessage = null
                scope.launch {
                    sharing = true
                    try {
                        shareMessage = when (gameLinkSharer.share(game.shareUrl)) {
                            GameLinkShareResult.COPIED -> "Ссылка скопирована"
                            GameLinkShareResult.SHARE_SHEET_OPENED -> null
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        shareMessage = "Не удалось поделиться ссылкой"
                    } finally {
                        sharing = false
                    }
                }
            },
            enabled = !sharing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Поделиться") }
        shareMessage?.let { message ->
            Text(
                message,
                color = if (message == "Ссылка скопирована") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
    Text(if (game.status == "ACTIVE") "Игра готова" else "Ожидаем игроков: ${game.players.size}/3")
    game.players.forEach { player ->
        Text("${player.user.username}${player.color?.let { " — $it" }.orEmpty()}")
    }
    OutlinedButton(
        onClick = controller::refreshGame,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Обновить лобби") }
}

private fun PromotionChoice.displayName(): String = when (this) {
    PromotionChoice.QUEEN -> "Ферзь"
    PromotionChoice.ROOK -> "Ладья"
    PromotionChoice.BISHOP -> "Слон"
    PromotionChoice.KNIGHT -> "Конь"
}
