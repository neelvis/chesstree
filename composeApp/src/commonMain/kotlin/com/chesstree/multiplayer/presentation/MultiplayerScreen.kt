package com.chesstree.multiplayer.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesstree.app.AppTab
import com.chesstree.app.localized
import com.chesstree.app.localizedMessage
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
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.BoardMoveAnimationKey
import com.chesstree.game.presentation.board.BoardCellId
import com.chesstree.game.presentation.board.MoveHint
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.PromotionPiecePickerDialog
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.educationalMoveHintsFor
import com.chesstree.game.presentation.board.legalMoveHintsFor
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
    pieceSet: PieceSet = PieceSet.FAIRY,
    showCurrentPossibleMoves: Boolean = true,
    showMoveLines: Boolean = false,
    zoomBeforeMove: Boolean = false,
    animatePieceMovement: Boolean = true,
    onAuthenticationSuccess: (AuthResponse) -> Unit = {},
    onSelectTab: (AppTab) -> Unit = {},
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(api, scope, initialGameCode, sessionStore, resetKey) {
        MultiplayerController(api, scope, initialGameCode, sessionStore)
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }
    val state by controller.state.collectAsState()
    val isGameStarted = state.game?.status == "ACTIVE" || state.game?.status == "FINISHED"
    val currentGame = state.game
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
            } else if (isGameStarted && currentGame != null) {
                val gameTitle = localized("game_title_code", currentGame.code)
                if (state.session != null) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        OnlineGame(
                            state = state,
                            controller = controller,
                            pieceSet = pieceSet,
                            showCurrentPossibleMoves = showCurrentPossibleMoves,
                            showMoveLines = showMoveLines,
                            zoomBeforeMove = zoomBeforeMove,
                            animatePieceMovement = animatePieceMovement,
                            gameTitle = gameTitle,
                            showBackButton = !hasSystemBackNavigation,
                            onBack = ::handleBack,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        GameTitleHeader(
                            title = gameTitle,
                            showBackButton = !hasSystemBackNavigation,
                            onBack = ::handleBack,
                        )
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state.loading) CircularProgressIndicator()
                        }
                        state.error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
                    }
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
                                    TextButton(onClick = ::handleBack) { Text(localized("back_with_chevron")) }
                                } else {
                                    TextButton(
                                        onClick = controller::returnToLobby,
                                        enabled = !state.loading
                                    ) {
                                        Text(localized("back_with_chevron"))
                                    }
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                localized("game_title_code", state.game?.code.orEmpty()),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                            )
                            GameLobby(checkNotNull(state.game), controller, gameLinkSharer)
                            state.error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
                            if (state.loading && !state.submittingMove && state.openingGameCode == null) {
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                            }
                        }
                    } else {
                        Box(
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(localized("online_game"), style = MaterialTheme.typography.headlineMedium)
                            if (!hasSystemBackNavigation) {
                                TextButton(
                                    onClick = ::handleBack,
                                    modifier = Modifier.align(Alignment.CenterStart)
                                ) {
                                    Text(localized("back_with_chevron"))
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
            Button(onClick = { onAuthModeChange(AuthMode.LOGIN) }) { Text(localized("login")) }
            OutlinedButton(onClick = { onAuthModeChange(AuthMode.REGISTER) }) { Text(localized("registration")) }
        } else {
            OutlinedButton(onClick = { onAuthModeChange(AuthMode.LOGIN) }) { Text(localized("login")) }
            Button(onClick = { onAuthModeChange(AuthMode.REGISTER) }) { Text(localized("registration")) }
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
        onCreateOneBotGame = { controller.createBotGame(1) },
        onCreateTwoBotGame = { controller.createBotGame(2) },
        onGameCodeChange = controller::setGameCode,
        onJoinGame = controller::joinGame,
    )
    GameHistory(state, controller)
}

@Composable
private fun LobbyMenu(
    state: MultiplayerUiState,
    onCreateGame: () -> Unit,
    onCreateOneBotGame: () -> Unit,
    onCreateTwoBotGame: () -> Unit,
    onGameCodeChange: (String) -> Unit,
    onJoinGame: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized("create_game"), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onCreateGame,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(localized("create_game"))
            }
            OutlinedButton(
                onClick = onCreateOneBotGame,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(localized("create_game_one_bot")) }
            OutlinedButton(
                onClick = onCreateTwoBotGame,
                enabled = !state.loading,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(localized("create_game_two_bots")) }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(localized("join_game"), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.gameCode,
                onValueChange = onGameCodeChange,
                label = { Text(localized("game_code")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onJoinGame,
                enabled = !state.loading && state.gameCode.length == 7,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(localized("join"))
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
                Text(localized("online_game"), style = MaterialTheme.typography.headlineMedium)
                AuthenticationMenu(
                    state = MultiplayerUiState(username = localized("player")),
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
                Text(localized("online_game"), style = MaterialTheme.typography.headlineMedium)
                LobbyMenu(
                    state = MultiplayerUiState(gameCode = "AB12CDE"),
                    onCreateGame = {},
                    onCreateOneBotGame = {},
                    onCreateTwoBotGame = {},
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
            Text(localized("games_history"), style = MaterialTheme.typography.titleMedium)
            if (state.loadingGames) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(localized("unfinished_games"), style = MaterialTheme.typography.titleSmall)
            if (unfinished.isEmpty()) Text(
                localized("no_unfinished_games"),
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
                    Text(localized(if (showAllUnfinished) "collapse" else "show_more"))
                }
            }
            Text(localized("finished_games"), style = MaterialTheme.typography.titleSmall)
            if (finished.isEmpty()) Text(
                localized("no_finished_games"),
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
                    Text(localized(if (showAllFinished) "collapse" else "show_more"))
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
    val botPlayerName = localized("bot_player")
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
                Text("${game.code} · ${game.startedAt.take(10).ifBlank { localized("unknown_date") }}")
                Text(
                    game.players.joinToString(" · ") { player ->
                        if (player.isBot) botPlayerName else player.user.username
                    },
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
private fun GameTitleHeader(
    title: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        if (showBackButton) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Text(localized("back_with_chevron"))
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
    zoomBeforeMove: Boolean,
    animatePieceMovement: Boolean,
    gameTitle: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
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
    var zoomConfirmationArmed by remember(session.state) { mutableStateOf(false) }
    var zoomToCell by remember(session.state) { mutableStateOf<BoardCellId?>(null) }
    var zoomOutRequest by remember(session.state) { mutableStateOf(0L) }
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

    val turnPlayer = session.state.turn?.player
    val currentTurnArmy = (if (canAct) assignedPlayer else turnPlayer)
        ?.let { ArmyColor.valueOf(it.name) }
        ?: assignedPlayer?.let { ArmyColor.valueOf(it.name) }
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GameTitleHeader(gameTitle, showBackButton, onBack)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                assignedPlayer?.let { ArmyQueenGlyph(ArmyColor.valueOf(it.name), size = 56.dp) }
                Text(localized("your_color"))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                currentTurnArmy?.let { ArmyQueenGlyph(it, size = 56.dp) }
                Text(
                    when {
                        canAct -> localized("your_turn")
                        undoRequest != null -> localized("paused_for_undo_vote")
                        game.status == "FINISHED" -> localized("game_finished")
                        else -> localized("current_turn")
                    },
                )
            }
        }
        ThreePlayerChessBoard(
            pieces = session.state.toBoardPieces(),
            moveAnimationKey = BoardMoveAnimationKey(
                gameId = "online:${game.code}",
                moveCount = session.moves.size,
            ),
            animatePieceMovement = animatePieceMovement,
            selectedPieceId = selectedPieceId,
            moveHints = hints,
            moveLineHints = moveLines,
            showDecorativeBirds = false,
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
                    zoomConfirmationArmed = false
                    zoomToCell = null
                    zoomOutRequest += 1
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
                    if (zoomBeforeMove && !zoomConfirmationArmed) {
                        zoomConfirmationArmed = true
                        zoomToCell = cell
                    } else if (matchingMoves.size == 1) {
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
                        zoomConfirmationArmed = false
                        zoomToCell = null
                    } else {
                        pendingPromotionMoves = matchingMoves
                    }
                } else {
                    zoomConfirmationArmed = false
                    zoomToCell = null
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
            modifier = Modifier.weight(1f).fillMaxWidth(),
            pieceSet = pieceSet,
            onZoomChanged = { boardZoom = it },
            zoomToCell = zoomToCell,
            resetViewportKey = session.state to zoomOutRequest,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = controller::requestUndo,
                enabled = !state.loading && undoRequest == null && session.moves.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(localized("undo_move")) }
            if (undoRequest != null) {
                val requesterName = game.players
                    .firstOrNull { it.user.id == undoRequest.requestedByUserId }
                    ?.user?.username
                    ?: localized("participant")
                if (isUndoRequester) {
                    Text(localized("request_sent_waiting"))
                } else if (hasApprovedUndo) {
                    Text(localized("agreed_waiting"))
                } else {
                    Text(localized("requester_undo", requesterName))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { controller.voteUndo(true) },
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f),
                        ) { Text(localized("agree")) }
                        OutlinedButton(
                            onClick = { controller.voteUndo(false) },
                            enabled = !state.loading,
                            modifier = Modifier.weight(1f),
                        ) { Text(localized("disagree")) }
                    }
                }
            }
            state.error?.let { Text(localizedMessage(it), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (pendingPromotionMoves.isNotEmpty()) {
        PromotionPiecePickerDialog(
            moves = pendingPromotionMoves,
            army = session.state.position.pieces.getValue(pendingPromotionMoves.first().pieceId).army,
            pieceSet = pieceSet,
            onMoveSelected = { move ->
                controller.submitMove(
                    MoveIntent(
                        move.actor,
                        move.from,
                        move.to,
                        move.promotion,
                    ),
                )
                pendingPromotionMoves = emptyList()
                selectedPieceId = null
                zoomConfirmationArmed = false
                zoomToCell = null
            },
            onDismiss = {
                pendingPromotionMoves = emptyList()
                zoomConfirmationArmed = false
                zoomToCell = null
            },
        )
    }
}

@Composable
private fun ArmyQueenGlyph(army: ArmyColor, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val scale = size / 28.dp
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 19.2f.sp * scale * 1.25f)
    val outlineWidth = with(LocalDensity.current) { (1.dp * scale).toPx() }
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(6.dp * scale),
        color = ChessTreeColors.SageContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp * scale, ChessTreeColors.Sage),
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
    Text(localized("game_code_value", game.code), style = MaterialTheme.typography.titleLarge)
    Text(game.shareUrl, style = MaterialTheme.typography.bodySmall)
    if (gameLinkSharer != null) {
        OutlinedButton(
            onClick = {
                shareMessage = null
                scope.launch {
                    sharing = true
                    try {
                        shareMessage = when (gameLinkSharer.share(game.shareUrl)) {
                            GameLinkShareResult.COPIED -> "i18n:link_copied"
                            GameLinkShareResult.SHARE_SHEET_OPENED -> null
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        shareMessage = "i18n:share_failed"
                    } finally {
                        sharing = false
                    }
                }
            },
            enabled = !sharing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(localized("share")) }
        shareMessage?.let { message ->
            Text(
                localizedMessage(message),
                color = if (message == "i18n:link_copied") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
    Text(if (game.status == "ACTIVE") localized("game_ready") else localized("waiting_players", game.players.size))
    game.players.forEach { player ->
        Text("${if (player.isBot) localized("bot_player") else player.user.username}${player.color?.let { " — $it" }.orEmpty()}")
    }
}
