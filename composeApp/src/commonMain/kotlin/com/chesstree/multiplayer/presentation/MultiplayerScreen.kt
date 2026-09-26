package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesstree.app.AppTab
import com.chesstree.app.AppTabBar
import com.chesstree.app.ChessTreeColors
import com.chesstree.app.ChessTreeTheme
import com.chesstree.app.PlatformBackHandler
import com.chesstree.app.hasSystemBackNavigation
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.MoveHint
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.educationalMoveHintsFor
import com.chesstree.game.presentation.board.legalMoveHintsFor
import com.chesstree.game.presentation.board.threePlayerBoardAspectRatio
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.contract.GameHistoryResponse
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MultiplayerScreen(
    api: ChessTreeApi,
    sessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    resetKey: Int = 0,
    initialGameCode: String = "",
    gameLinkSharer: GameLinkSharer? = null,
    selectedTab: AppTab = AppTab.GAMES,
    showProfile: Boolean = false,
    profileContent: @Composable (Modifier) -> Unit = {},
    pieceSet: PieceSet = PieceSet.STANDARD,
    showCurrentPossibleMoves: Boolean = true,
    showMoveLines: Boolean = false,
    onAuthenticationSuccess: (AuthResponse) -> Unit = {},
    onSelectTab: (AppTab) -> Unit = {},
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(api, scope, initialGameCode, sessionStore, resetKey) {
        MultiplayerController(api, scope, initialGameCode, sessionStore)
    }
    val state by controller.state.collectAsState()
    val isGameStarted = state.game?.status == "ACTIVE" || state.game?.status == "FINISHED"
    fun handleBack() {
        when {
            showProfile && selectedTab == AppTab.SETTINGS -> onSelectTab(AppTab.GAMES)
            state.game == null -> onClose()
            else -> controller.returnToLobby()
        }
    }
    PlatformBackHandler(enabled = true, onBack = ::handleBack)
    LaunchedEffect(state.game?.code, state.game?.status) {
        val refreshIntervalMillis = when {
            state.game?.status == "ACTIVE" -> 2_000L
            state.game != null && state.game?.status != "FINISHED" -> 5_000L
            else -> null
        }
        if (refreshIntervalMillis != null) {
            while (true) {
                delay(refreshIntervalMillis)
                controller.refreshGame()
            }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showProfile && selectedTab == AppTab.SETTINGS) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    profileContent(Modifier.fillMaxSize())
                }
            } else {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (state.game != null) {
                        if (!isGameStarted || !hasSystemBackNavigation) {
                            Row(
                                modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.Start,
                            ) {
                                if (isGameStarted) {
                                    TextButton(onClick = ::handleBack) { Text("‹ Назад") }
                                } else {
                                    TextButton(
                                        onClick = controller::returnToLobby,
                                        enabled = !state.loading
                                    ) {
                                        Text("‹ Назад")
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                "Партия ${state.game?.code}",
                                style = MaterialTheme.typography.headlineMedium
                            )
                            if (isGameStarted) {
                                val userArmy = state.game?.players
                                    ?.firstOrNull { it.user.id == state.authentication?.user?.id }
                                    ?.color
                                    ?.let { runCatching { ArmyColor.valueOf(it) }.getOrNull() }
                                if (userArmy != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text("Ваш цвет:")
                                        ArmyQueenGlyph(userArmy)
                                    }
                                }
                            }
                            if (state.game?.status == "ACTIVE" || state.game?.status == "FINISHED") {
                                if (state.session != null) {
                                    OnlineGame(
                                        state = state,
                                        controller = controller,
                                        pieceSet = pieceSet,
                                        showCurrentPossibleMoves = showCurrentPossibleMoves,
                                        showMoveLines = showMoveLines,
                                    )
                                } else Text("Загружаем позицию партии…")
                            } else {
                                GameLobby(checkNotNull(state.game), controller, gameLinkSharer)
                            }
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.loading && !state.submittingMove && state.openingGameCode == null) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Онлайн-игра", style = MaterialTheme.typography.headlineMedium)
                            if (!hasSystemBackNavigation) {
                                TextButton(
                                    onClick = ::handleBack,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) {
                                    Text("‹ Назад")
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        Column(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            if (state.authentication == null) AuthenticationContent(
                                state,
                                controller,
                                onAuthenticationSuccess
                            )
                            else LobbyContent(state, controller, gameLinkSharer)
                            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (state.loading && !state.submittingMove && state.openingGameCode == null) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    }
                }
            }
            AppTabBar(selected = selectedTab, onSelected = onSelectTab)
        }
    }
}

@Composable
private fun AuthenticationContent(
    state: MultiplayerUiState,
    controller: MultiplayerController,
    onAuthenticationSuccess: (AuthResponse) -> Unit,
) {
    AuthenticationMenu(
        state = state,
        onAuthModeChange = controller::setAuthMode,
        onUsernameChange = controller::setUsername,
        onPasswordChange = controller::setPassword,
        onSubmit = { onSuccess ->
            controller.submitAuthentication(
                onSuccess = onSuccess,
                onAuthenticated = onAuthenticationSuccess,
            )
        },
    )
}

@Composable
private fun AuthenticationMenu(
    state: MultiplayerUiState,
    onAuthModeChange: (AuthMode) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubmit: (onSuccess: suspend () -> Unit) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.authMode == AuthMode.LOGIN) {
            Button(onClick = { onAuthModeChange(AuthMode.LOGIN) }) { Text("Вход") }
            OutlinedButton(onClick = { onAuthModeChange(AuthMode.REGISTER) }) { Text("Регистрация") }
        } else {
            OutlinedButton(onClick = { onAuthModeChange(AuthMode.LOGIN) }) { Text("Вход") }
            Button(onClick = { onAuthModeChange(AuthMode.REGISTER) }) { Text("Регистрация") }
        }
    }
    AuthenticationForm(
        mode = state.authMode,
        username = state.username,
        password = state.password,
        enabled = !state.loading && state.username.isNotBlank() && state.password.isNotBlank(),
        onUsernameChange = onUsernameChange,
        onPasswordChange = onPasswordChange,
        onSubmit = onSubmit,
    )
}

@Composable
private fun LobbyContent(
    state: MultiplayerUiState,
    controller: MultiplayerController,
    gameLinkSharer: GameLinkSharer?,
) {
    LobbyMenu(
        state = state,
        onCreateGame = controller::createGame,
        onGameCodeChange = controller::setGameCode,
        onJoinGame = controller::joinGame,
    )
    GameHistory(state, controller)
}

@Composable
private fun LobbyMenu(
    state: MultiplayerUiState,
    onCreateGame: () -> Unit,
    onGameCodeChange: (String) -> Unit,
    onJoinGame: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Создать игру", style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onCreateGame,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Создать игру")
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Присоединиться к игре", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.gameCode,
                onValueChange = onGameCodeChange,
                label = { Text("Код игры") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onJoinGame,
                enabled = !state.loading && state.gameCode.length == 7,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Присоединиться")
            }
        }
    }
}

@Preview
@Composable
private fun LoginMenuPreview() {
    ChessTreeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Онлайн-игра", style = MaterialTheme.typography.headlineMedium)
                AuthenticationMenu(
                    state = MultiplayerUiState(username = "игрок"),
                    onAuthModeChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onSubmit = {},
                )
            }
        }
    }
}

@Preview
@Composable
private fun MultiplayerMenuPreview() {
    ChessTreeTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Онлайн-игра", style = MaterialTheme.typography.headlineMedium)
                LobbyMenu(
                    state = MultiplayerUiState(gameCode = "AB12CDE"),
                    onCreateGame = {},
                    onGameCodeChange = {},
                    onJoinGame = {},
                )
            }
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
            if (state.loadingGames) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text("Незавершённые", style = MaterialTheme.typography.titleSmall)
            if (unfinished.isEmpty()) Text(
                "Нет незавершённых игр",
                style = MaterialTheme.typography.bodySmall
            )
            if (showAllUnfinished) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(unfinished, key = { it.id }) { game ->
                        HistoryGameRow(
                            game,
                            true,
                            state.openingGameCode == game.code,
                            controller::openGame
                        )
                    }
                }
            } else unfinished.take(5).forEach { game ->
                HistoryGameRow(game, true, state.openingGameCode == game.code, controller::openGame)
            }
            if (unfinished.size > 5) {
                TextButton(onClick = { showAllUnfinished = !showAllUnfinished }) {
                    Text(if (showAllUnfinished) "Свернуть" else "Показать ещё")
                }
            }
            Text("Завершённые", style = MaterialTheme.typography.titleSmall)
            if (finished.isEmpty()) Text(
                "Нет завершённых игр",
                style = MaterialTheme.typography.bodySmall
            )
            if (showAllFinished) {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(finished, key = { it.id }) { game ->
                        HistoryGameRow(
                            game,
                            false,
                            state.openingGameCode == game.code,
                            controller::openGame
                        )
                    }
                }
            } else {
                finished.take(5).forEach { game ->
                    HistoryGameRow(
                        game,
                        false,
                        state.openingGameCode == game.code,
                        controller::openGame
                    )
                }
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
private fun HistoryGameRow(
    game: GameHistoryResponse,
    unfinished: Boolean,
    opening: Boolean,
    onOpen: (String) -> Unit,
) {
    TextButton(
        onClick = { onOpen(game.code) },
        enabled = !opening,
        modifier = Modifier.fillMaxWidth()
    ) {
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
            if (opening) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun OnlineGame(
    state: MultiplayerUiState,
    controller: MultiplayerController,
    pieceSet: PieceSet,
    showCurrentPossibleMoves: Boolean,
    showMoveLines: Boolean,
) {
    val session = checkNotNull(state.session)
    val game = checkNotNull(state.game)
    var boardZoom by remember(game.code) { mutableStateOf(1f) }
    val assignedPlayer = game.players
        .firstOrNull { it.user.id == state.authentication?.user?.id }
        ?.color
        ?.let { runCatching { com.chesstree.game.domain.PlayerId.valueOf(it) }.getOrNull() }
    var selectedPieceId by remember(session.state) { mutableStateOf<String?>(null) }
    var pendingPromotionMoves by remember(session.state) { mutableStateOf(emptyList<Move>()) }
    val hints = remember(session.state, selectedPieceId, showCurrentPossibleMoves) {
        if (showCurrentPossibleMoves) {
            selectedPieceId?.let(::PieceId)?.let { legalMoveHintsFor(session.state, it) }.orEmpty()
        } else {
            emptyList<MoveHint>()
        }
    }
    val moveLines = remember(session.state, selectedPieceId, showMoveLines) {
        if (showMoveLines) {
            selectedPieceId?.let(::PieceId)?.let { educationalMoveHintsFor(session.state, it) }
                .orEmpty()
        } else {
            emptyList<MoveHint>()
        }
    }
    val undoRequest = state.remoteState?.undoRequest
    val canAct =
        !state.loading && undoRequest == null && session.state.turn?.player == assignedPlayer
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when {
                undoRequest != null -> "Игра приостановлена: голосование за отмену хода"
                game.status == "FINISHED" -> "Партия завершена"
                canAct -> "Ваш ход"
                else -> "Ожидаем ход другого игрока:"
            },
        )
        turnPlayer?.let { ArmyQueenGlyph(ArmyColor.valueOf(it.name)) }
    }
    ThreePlayerChessBoard(
        pieces = session.state.toBoardPieces(),
        selectedPieceId = selectedPieceId,
        moveHints = hints,
        moveLineHints = moveLines,
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
            if (cell == null) {
                selectedPieceId = null
                return@ThreePlayerChessBoard
            }
            val selected = selectedPieceId?.let(::PieceId)
            val matchingMoves = if (canAct) {
                selected?.let { pieceId ->
                    LegalMoveGenerator.legalMoves(session.state, pieceId).filter { it.to == cell }
                }.orEmpty()
            } else {
                emptyList()
            }
            if (canAct && matchingMoves.isNotEmpty()) {
                if (matchingMoves.size == 1) {
                    val move = matchingMoves.single()
                    controller.submitMove(
                        MoveIntent(
                            move.actor,
                            move.from,
                            move.to,
                            move.promotion
                        )
                    )
                    selectedPieceId = null
                } else {
                    pendingPromotionMoves = matchingMoves
                }
            } else {
                val piece =
                    session.state.position.pieces.values.firstOrNull { it.coordinate == cell }
                selectedPieceId = piece
                    ?.takeIf {
                        showMoveLines ||
                                (canAct && session.state.armies.getValue(it.army).controller == assignedPlayer)
                    }
                    ?.id?.value
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(threePlayerBoardAspectRatio(pieceSet, true, boardZoom)),
        pieceSet = pieceSet,
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
    if (pendingPromotionMoves.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingPromotionMoves = emptyList() },
            title = { Text("Превращение пешки") },
            text = { Text("Выберите новую фигуру") },
            confirmButton = {
                Column {
                    pendingPromotionMoves.forEach { move ->
                        TextButton(onClick = {
                            controller.submitMove(
                                MoveIntent(
                                    move.actor,
                                    move.from,
                                    move.to,
                                    move.promotion
                                )
                            )
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
private fun ArmyQueenGlyph(army: ArmyColor) {
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 19.2f.sp)
    val outlineWidth = with(LocalDensity.current) { 1.dp.toPx() }
    Surface(
        modifier = Modifier.size(28.dp),
        shape = RoundedCornerShape(6.dp),
        color = ChessTreeColors.SageContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, ChessTreeColors.Sage),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "♛",
                style = textStyle.copy(
                    color = ChessTreeColors.Sage,
                    drawStyle = Stroke(width = outlineWidth),
                ),
            )
            Text(
                text = "♛",
                style = textStyle.copy(color = army.displayColor()),
            )
        }
    }
}

private fun ArmyColor.displayColor(): Color = when (this) {
    ArmyColor.WHITE -> Color(0xFFF7F1E4)
    ArmyColor.RED -> Color(0xFFB72C35)
    ArmyColor.BLACK -> Color(0xFF171310)
}

@Composable
private fun GameLobby(
    game: GameResponse,
    controller: MultiplayerController,
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
}

private fun PromotionChoice.displayName(): String = when (this) {
    PromotionChoice.QUEEN -> "Ферзь"
    PromotionChoice.ROOK -> "Ладья"
    PromotionChoice.BISHOP -> "Слон"
    PromotionChoice.KNIGHT -> "Конь"
}
