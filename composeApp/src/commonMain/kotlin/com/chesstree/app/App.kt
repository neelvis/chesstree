package com.chesstree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.NoOpGameSaveStore
import com.chesstree.game.data.SaveGameResult
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.session.GameLogCodec
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.BoardMoveAnimationKey
import com.chesstree.game.presentation.board.BoardCellId
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.PromotionPiecePickerDialog
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.history.GameHistoryDialog
import com.chesstree.game.presentation.history.GameHistoryNavigation
import com.chesstree.game.presentation.history.GameLogExporter
import com.chesstree.game.presentation.history.NoOpGameLogExporter
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.ApiResult
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.data.NoOpPushTokenProvider
import com.chesstree.multiplayer.data.PushTokenProvider
import com.chesstree.multiplayer.presentation.GameLinkSharer
import com.chesstree.multiplayer.presentation.MultiplayerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
fun App(
    gameSaveStore: GameSaveStore = NoOpGameSaveStore,
    onlineApi: ChessTreeApi? = null,
    onlineSessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    pushTokenProvider: PushTokenProvider = NoOpPushTokenProvider,
    initialGameCode: String? = null,
    gameLinkSharer: GameLinkSharer? = null,
    gameLogExporter: GameLogExporter = NoOpGameLogExporter,
) {
    ChessTreeTheme {
        val scenarios = remember { ManualGameScenarios.all }
        val sessionSaver = remember(scenarios) {
            Saver<GameSession, String>(
                save = { encodeSessionForRestoration(it) },
                restore = { restoreSessionOrDefault(it, scenarios) },
            )
        }
        var session by rememberSaveable(stateSaver = sessionSaver) {
            mutableStateOf(GameSession(scenarios.first()))
        }
        val settingsSaver = remember {
            Saver<GameSettings, String>(
                save = { encodeGameSettings(it) },
                restore = ::restoreGameSettings,
            )
        }
        var settings by rememberSaveable(stateSaver = settingsSaver) {
            mutableStateOf(GameSettings())
        }
        var selectedTab by rememberSaveable { mutableStateOf(AppTab.GAMES) }
        var authenticatedUser by remember { mutableStateOf<AuthResponse?>(null) }
        var accountLoading by remember { mutableStateOf(onlineApi != null) }
        var accountError by remember { mutableStateOf<String?>(null) }
        val accountScope = rememberCoroutineScope()
        var showChessGame by rememberSaveable { mutableStateOf(false) }
        var returnToSettingsAfterAuthentication by rememberSaveable { mutableStateOf(false) }
        var showProfileInMultiplayer by rememberSaveable { mutableStateOf(false) }
        var multiplayerResetKey by rememberSaveable { mutableStateOf(0) }
        var showMultiplayer by rememberSaveable { mutableStateOf(initialGameCode != null) }
        PlatformBackHandler(enabled = showChessGame && !showMultiplayer) {
            if (selectedTab == AppTab.SETTINGS) {
                selectedTab = AppTab.GAMES
            } else {
                selectedTab = AppTab.GAMES
                showChessGame = false
            }
        }
        LaunchedEffect(initialGameCode) {
            if (initialGameCode != null) showMultiplayer = true
        }
        LaunchedEffect(onlineApi, onlineSessionStore, initialGameCode) {
            if (onlineApi != null) {
                try {
                    authenticatedUser = onlineSessionStore.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    accountError = "i18n:login_restore_failed"
                } finally {
                    accountLoading = false
                }
            }
        }
        LaunchedEffect(onlineSessionStore) {
            onlineSessionStore.invalidations().collect {
                authenticatedUser = null
                accountError = null
                accountLoading = false
                multiplayerResetKey += 1
            }
        }
        LaunchedEffect(onlineApi, authenticatedUser?.accessToken, pushTokenProvider) {
            val api = onlineApi ?: return@LaunchedEffect
            val authToken = authenticatedUser?.accessToken ?: return@LaunchedEffect
            try {
                if (!pushTokenProvider.requestPermission()) return@LaunchedEffect
                pushTokenProvider.currentToken()?.let { deviceToken ->
                    api.registerPushDevice(authToken, deviceToken, pushTokenProvider.platform)
                }
                pushTokenProvider.tokenUpdates().collect { deviceToken ->
                    api.registerPushDevice(authToken, deviceToken, pushTokenProvider.platform)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Push registration is optional and retried on the next launch or token refresh.
            }
        }
        val profileContent: @Composable (Modifier) -> Unit = { modifier ->
            SettingsScreen(
                settings = settings,
                onSettingsChanged = { updated -> settings = updated },
                username = authenticatedUser?.user?.username,
                accountLoading = accountLoading,
                accountError = accountError,
                onLogout = if (authenticatedUser != null && onlineApi != null) {
                    {
                        val authentication = authenticatedUser
                        accountScope.launch {
                            accountLoading = true
                            try {
                                if (authentication != null) {
                                    try {
                                        pushTokenProvider.currentToken()?.let { deviceToken ->
                                            onlineApi.unregisterPushDevice(
                                                authentication.accessToken,
                                                deviceToken,
                                            )
                                        }
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (_: Throwable) {
                                    }
                                }
                                if (authentication != null) {
                                    when (onlineApi.logout(authentication.accessToken)) {
                                        is ApiResult.Success -> Unit
                                        is ApiResult.Failure -> {
                                            accountError = "i18n:logout_failed"
                                            return@launch
                                        }
                                    }
                                }
                                onlineSessionStore.clear()
                                authenticatedUser = null
                                accountError = null
                                multiplayerResetKey += 1
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                accountError = "i18n:logout_failed"
                            } finally {
                                accountLoading = false
                            }
                        }
                    }
                } else null,
                onOpenProfile = if (onlineApi != null) {
                    {
                        returnToSettingsAfterAuthentication = true
                        showProfileInMultiplayer = false
                        showMultiplayer = true
                    }
                } else null,
                modifier = modifier,
            )
        }
        if (showMultiplayer && onlineApi != null) {
            MultiplayerScreen(
                api = onlineApi,
                sessionStore = onlineSessionStore,
                resetKey = multiplayerResetKey,
                initialGameCode = initialGameCode.orEmpty(),
                gameLinkSharer = gameLinkSharer,
                selectedTab = selectedTab,
                showProfile = showProfileInMultiplayer,
                profileContent = profileContent,
                pieceSet = settings.pieceSet,
                showCurrentPossibleMoves = settings.showCurrentPossibleMoves,
                showMoveLines = settings.showMoveLines,
                zoomBeforeMove = settings.zoomBeforeMove,
                animatePieceMovement = settings.animatePieceMovement,
                onAuthenticationSuccess = { authentication ->
                    authenticatedUser = authentication
                    if (returnToSettingsAfterAuthentication) {
                        returnToSettingsAfterAuthentication = false
                        showMultiplayer = false
                        showChessGame = false
                        selectedTab = AppTab.SETTINGS
                        showProfileInMultiplayer = false
                    }
                },
                onSelectTab = { tab ->
                    selectedTab = tab
                    showProfileInMultiplayer = tab == AppTab.SETTINGS
                },
                onClose = {
                    showMultiplayer = false
                    showChessGame = false
                    showProfileInMultiplayer = false
                    returnToSettingsAfterAuthentication = false
                },
            )
            return@ChessTreeTheme
        }
        if (selectedTab == AppTab.SETTINGS || !showChessGame) {
            Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                when (selectedTab) {
                    AppTab.GAMES -> GamesHomeScreen(
                        onPlaySolo = {
                            selectedTab = AppTab.GAMES
                            showChessGame = true
                        },
                        onMultiplayer = onlineApi?.let {
                            {
                                returnToSettingsAfterAuthentication = false
                                showProfileInMultiplayer = false
                                showMultiplayer = true
                            }
                        },
                        username = authenticatedUser?.user?.username,
                        modifier = Modifier.weight(1f),
                    )

                    AppTab.SETTINGS -> profileContent(Modifier.weight(1f))
                }
                AppTabBar(
                    selected = selectedTab,
                    onSelected = { selectedTab = it },
                )
            }
            return@ChessTreeTheme
        }
        var selectedPieceId by remember { mutableStateOf<String?>(null) }
        var pendingPromotionMoves by remember { mutableStateOf(emptyList<Move>()) }
        var zoomConfirmationArmed by remember { mutableStateOf(false) }
        var zoomToCell by remember { mutableStateOf<BoardCellId?>(null) }
        var zoomOutRequest by remember { mutableStateOf(0L) }
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        var historyNavigation by remember { mutableStateOf(GameHistoryNavigation.latest()) }
        val displayedSession = remember(session, historyNavigation) {
            historyNavigation.displayedSession(session)
        }
        val gameState = displayedSession.state
        LaunchedEffect(storageMessage) {
            if (storageMessage != null) {
                delay(3_000)
                storageMessage = null
            }
        }
        val isViewingLatest = historyNavigation.isAtLatest(session)
        val displayedMoveCount = historyNavigation.displayedMoveCount(session)
        val pieces = remember(gameState) { gameState.toBoardPieces() }
        val selectedMoveHints = remember(gameState, selectedPieceId, settings) {
            movementHintsForSelection(
                state = gameState,
                selectedPieceId = selectedPieceId,
                showCurrentPossibleMoves = settings.showCurrentPossibleMoves,
                showMoveLines = settings.showMoveLines,
            )
        }
        val trophies = remember(displayedSession.capturedPieces) {
            displayedSession.capturedPieces.map { captured ->
                BoardTrophy(
                    id = captured.id,
                    type = captured.type,
                    army = captured.army,
                    bodyArmy = captured.bodyArmy,
                    capturedByArmy = captured.capturedByArmy,
                )
            }
        }

        fun clearTransientState() {
            selectedPieceId = null
            pendingPromotionMoves = emptyList()
            zoomConfirmationArmed = false
            zoomToCell = null
        }

        fun save(updatedSession: GameSession): SaveGameResult {
            val snapshot = GameSnapshot(
                scenarioId = updatedSession.scenario.id,
                moves = updatedSession.moves,
            )
            return gameSaveStore.save(GameSnapshotCodec.encode(snapshot))
        }

        fun applyMove(move: Move) {
            if (!isViewingLatest) return
            when (val result = session.apply(
                MoveIntent(
                    actor = move.actor,
                    from = move.from,
                    to = move.to,
                    promotion = move.promotion,
                ),
            )) {
                is SessionMoveResult.Applied -> {
                    session = result.session
                    historyNavigation = GameHistoryNavigation.latest()
                    storageMessage = when (val saveResult = save(result.session)) {
                        SaveGameResult.Saved -> null
                        is SaveGameResult.Failed ->
                            "i18n:save_failed|${saveResult.message}"
                    }
                }

                SessionMoveResult.Rejected -> Unit
            }
            clearTransientState()
        }

        fun restart() {
            session = GameSession(session.scenario)
            historyNavigation = GameHistoryNavigation.latest()
            clearTransientState()
            storageMessage = "i18n:game_restarted"
        }
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Surface(
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.background,
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.background,
                                    ChessTreeColors.SurfaceMuted,
                                ),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val compactLayout = maxWidth < 600.dp
                    val horizontalPadding = if (compactLayout) 0.dp else 16.dp
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = horizontalPadding, vertical = 8.dp)
                            .widthIn(max = 920.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!hasSystemBackNavigation) {
                                TextButton(
                                    onClick = {
                                        selectedTab = AppTab.GAMES
                                        showChessGame = false
                                    },
                                ) {
                                    Text(localized("back_with_chevron"))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::restart) { Text(localized("restart")) }
                            Box {
                                TextButton(onClick = { scenarioMenuExpanded = true }) {
                                    Text(localized("scenario"))
                                }
                                DropdownMenu(
                                    expanded = scenarioMenuExpanded,
                                    onDismissRequest = { scenarioMenuExpanded = false },
                                ) {
                                    scenarios.forEach { scenario ->
                                        DropdownMenuItem(
                                            text = {
                                                val titleKey = when (scenario.id) {
                                                    "standard" -> "scenario_standard"
                                                    "sparse-movement" -> "scenario_free_board"
                                                    "capture-practice" -> "scenario_captures"
                                                    "after-red-checkmate" -> "scenario_mate"
                                                    "finished-game" -> "scenario_finished"
                                                    else -> null
                                                }
                                                Text(titleKey?.let { localized(it) } ?: scenario.id)
                                            },
                                            onClick = {
                                                session = GameSession(scenario)
                                                historyNavigation = GameHistoryNavigation.latest()
                                                clearTransientState()
                                                storageMessage = null
                                                scenarioMenuExpanded = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        TurnPieceIndicator(
                            player = gameState.turn?.player,
                            finishedText = gameState.statusText(),
                        )
                        if (gameState.turn != null) {
                            Text(
                                text = localized("current_turn"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        Text(
                            text = if (isViewingLatest) {
                                localized("total_moves", session.moves.size)
                            } else {
                                localized("move_review", displayedMoveCount, session.moves.size)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    historyNavigation = historyNavigation.back(session)
                                    clearTransientState()
                                },
                                enabled = historyNavigation.canGoBack(session),
                            ) {
                                Text(localized("back"))
                            }
                            TextButton(
                                onClick = {
                                    historyNavigation = historyNavigation.forward(session)
                                    clearTransientState()
                                },
                                enabled = historyNavigation.canGoForward(session),
                            ) {
                                Text(localized("forward"))
                            }
                            TextButton(
                                onClick = {
                                    val undone = session.undoLastMove() ?: return@TextButton
                                    storageMessage = when (val saveResult = save(undone)) {
                                        SaveGameResult.Saved -> {
                                            session = undone
                                            historyNavigation = GameHistoryNavigation.latest()
                                            clearTransientState()
                                            "i18n:last_move_undone"
                                        }

                                        is SaveGameResult.Failed ->
                                            "i18n:undo_failed|${saveResult.message}"
                                    }
                                },
                                enabled = isViewingLatest && session.moves.isNotEmpty(),
                            ) {
                                Text(localized("undo_move"))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            ThreePlayerChessBoard(
                                pieces = pieces,
                                selectedPieceId = selectedPieceId,
                                moveHints = selectedMoveHints.currentPossibleMoves,
                                moveLineHints = selectedMoveHints.moveLines,
                                trophies = trophies,
                                moveAnimationKey = BoardMoveAnimationKey(
                                    gameId = "solo:${session.scenario.id}",
                                    moveCount = session.moves.size,
                                ),
                                animatePieceMovement = settings.animatePieceMovement,
                                pieceSet = settings.pieceSet,
                                showDecorativeBirds = gameState.turn == null,
                                onCellSelected = { cell ->
                                    if (!isViewingLatest) return@ThreePlayerChessBoard
                                    if (cell == null) {
                                        selectedPieceId = null
                                        zoomConfirmationArmed = false
                                        zoomToCell = null
                                        zoomOutRequest += 1
                                        return@ThreePlayerChessBoard
                                    }

                                    val selectedPiece = selectedPieceId
                                        ?.let(::PieceId)
                                        ?.let(gameState.position.pieces::get)
                                    val matchingMoves = selectedPiece?.let { piece ->
                                        LegalMoveGenerator.legalMoves(gameState, piece.id)
                                            .filter { move -> move.to == cell }
                                    }.orEmpty()
                                    if (selectedPiece != null && matchingMoves.isNotEmpty()) {
                                        if (settings.zoomBeforeMove && !zoomConfirmationArmed) {
                                            zoomConfirmationArmed = true
                                            zoomToCell = cell
                                        } else if (matchingMoves.size == 1) {
                                            applyMove(matchingMoves.single())
                                        } else {
                                            pendingPromotionMoves = matchingMoves
                                        }
                                    } else {
                                        zoomConfirmationArmed = false
                                        zoomToCell = null
                                        val tappedPiece = gameState.position.pieces.values
                                            .firstOrNull { piece -> piece.coordinate == cell }
                                        val canMovePiece = tappedPiece?.let { piece ->
                                            gameState.turn?.player ==
                                                    gameState.armies.getValue(piece.army).controller
                                        } == true
                                        selectedPieceId = tappedPiece
                                            ?.takeIf { settings.showMoveLines || canMovePiece }
                                            ?.id?.value
                                    }
                                },
                                zoomToCell = zoomToCell,
                                resetViewportKey = gameState to zoomOutRequest,
                                modifier = Modifier.fillMaxSize(),
                            )
                            storageMessage?.let { message ->
                                Box(
                                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        localizedMessage(message),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (pendingPromotionMoves.isNotEmpty()) {
                PromotionPiecePickerDialog(
                    moves = pendingPromotionMoves,
                    army = gameState.position.pieces.getValue(pendingPromotionMoves.first().pieceId).army,
                    pieceSet = settings.pieceSet,
                    onMoveSelected = ::applyMove,
                    onDismiss = {
                        pendingPromotionMoves = emptyList()
                        zoomConfirmationArmed = false
                        zoomToCell = null
                    },
                )
            }
            AppTabBar(
                selected = selectedTab,
                onSelected = { tab ->
                    selectedTab = tab
                    if (settings.showGameHistory) {
                        settings = settings.copy(showGameHistory = false)
                    }
                },
            )
        }
        if (showChessGame && settings.showGameHistory) {
            GameHistoryDialog(
                log = remember(session) { GameLogCodec.encode(session) },
                exporter = gameLogExporter,
                onRestore = { contents ->
                    val restored = GameLogCodec.restore(session.scenario, contents)
                    session = restored.session
                    historyNavigation = GameHistoryNavigation.latest()
                    clearTransientState()
                    val snapshot = GameSnapshot(
                        scenarioId = restored.session.scenario.id,
                        moves = restored.session.moves,
                    )
                    gameSaveStore.save(GameSnapshotCodec.encode(snapshot))
                    "i18n:restore_moves|${restored.restoredMoves}|${restored.totalMoves}"
                },
                onDismiss = {
                    settings = settings.copy(showGameHistory = false)
                },
            )
        }
    }
}

@Composable
private fun TurnPieceIndicator(
    player: PlayerId?,
    finishedText: String,
) {
    if (player == null) {
        Text(
            text = finishedText,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(64.dp),
        )
        return
    }
    val army = ArmyColor.valueOf(player.name)
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontSize = 76.8f.sp)
    val outlineWidth = with(LocalDensity.current) { 4.dp.toPx() }
    Surface(
        modifier = Modifier.size(112.dp),
        shape = RoundedCornerShape(24.dp),
        color = ChessTreeColors.SageContainer,
        border = BorderStroke(4.dp, ChessTreeColors.Sage),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("♛", style = textStyle.copy(color = ChessTreeColors.Sage, drawStyle = Stroke(width = outlineWidth)))
            Text("♛", style = textStyle.copy(color = army.displayColor()))
        }
    }
}

private fun ArmyColor.displayColor(): Color = when (this) {
    ArmyColor.WHITE -> Color(0xFFF7F1E4)
    ArmyColor.RED -> Color(0xFFD62839)
    ArmyColor.BLACK -> Color(0xFF343A40)
}

@Composable
internal fun com.chesstree.game.domain.GameState.statusText(): String {
    val currentPhase = phase
    val phaseText = when (currentPhase) {
        GamePhase.InProgress -> turn?.let { currentTurn ->
            val check = if (LegalMoveGenerator.isKingInCheck(this, currentTurn.player)) {
                localized("check_suffix")
            } else {
                ""
            }
            localized(
                "turn_status",
                localized(
                    when (currentTurn.player) {
                        PlayerId.WHITE -> "turn_player_white"
                        PlayerId.RED -> "turn_player_red"
                        PlayerId.BLACK -> "turn_player_black"
                    },
                ),
                currentTurn.ply,
                check,
            )
        } ?: localized("game_in_progress")

        is GamePhase.Finished -> when (currentPhase.outcome) {
            is GameOutcome.Ranked -> localized("game_finished")
            is GameOutcome.ThreeWayDraw -> localized("draw")
            is GameOutcome.TwoWayDraw -> localized("draw")
        }
    }
    val eliminated = participants.values.mapNotNull { participant ->
        when (participant.status) {
            ParticipantStatus.Active -> null
            is ParticipantStatus.Checkmated -> localized(
                "checkmated",
                localized(
                    when (participant.id) {
                        PlayerId.WHITE -> "turn_player_white"
                        PlayerId.RED -> "turn_player_red"
                        PlayerId.BLACK -> "turn_player_black"
                    },
                ),
            )
            is ParticipantStatus.Stalemated -> localized(
                "stalemated",
                localized(
                    when (participant.id) {
                        PlayerId.WHITE -> "turn_player_white"
                        PlayerId.RED -> "turn_player_red"
                        PlayerId.BLACK -> "turn_player_black"
                    },
                ),
            )
        }
    }
    return listOf(phaseText, eliminated.joinToString()).filter(String::isNotEmpty)
        .joinToString(" · ")
}
