package com.chesstree.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.NoOpGameSaveStore
import com.chesstree.game.data.SaveGameResult
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.session.GameLogCodec
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.threePlayerBoardAspectRatio
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.history.GameHistoryDialog
import com.chesstree.game.presentation.history.GameHistoryNavigation
import com.chesstree.game.presentation.history.GameLogExporter
import com.chesstree.game.presentation.history.NoOpGameLogExporter
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import com.chesstree.multiplayer.contract.AuthResponse
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.presentation.GameLinkSharer
import com.chesstree.multiplayer.presentation.MultiplayerScreen
import com.chesstree.resources.Res
import com.chesstree.resources.allDrawableResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.imageResource

@Composable
fun App(
    gameSaveStore: GameSaveStore = NoOpGameSaveStore,
    onlineApi: ChessTreeApi? = null,
    onlineSessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
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
                    accountError = "Не удалось восстановить вход"
                } finally {
                    accountLoading = false
                }
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
                                if (authentication != null) onlineApi.logout(authentication.accessToken)
                                onlineSessionStore.clear()
                                authenticatedUser = null
                                accountError = null
                                multiplayerResetKey += 1
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Throwable) {
                                accountError = "Не удалось завершить выход"
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
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        var boardZoom by remember { mutableStateOf(1f) }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        var historyNavigation by remember { mutableStateOf(GameHistoryNavigation.latest()) }
        val displayedSession = remember(session, historyNavigation) {
            historyNavigation.displayedSession(session)
        }
        val gameState = displayedSession.state
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
                        SaveGameResult.Saved -> "Партия сохранена"
                        is SaveGameResult.Failed ->
                            "Не удалось сохранить: ${saveResult.message}"
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
            storageMessage = "Партия перезапущена; последнее сохранение не изменено"
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
                            .then(if (compactLayout) Modifier.verticalScroll(rememberScrollState()) else Modifier)
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
                                    Text("‹ Назад")
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = ::restart) { Text("Рестарт") }
                            Box {
                                TextButton(onClick = { scenarioMenuExpanded = true }) {
                                    Text("Сценарий")
                                }
                                DropdownMenu(
                                    expanded = scenarioMenuExpanded,
                                    onDismissRequest = { scenarioMenuExpanded = false },
                                ) {
                                    scenarios.forEach { scenario ->
                                        DropdownMenuItem(
                                            text = { Text(scenario.title) },
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
                        storageMessage?.let { message ->
                            Text(message, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = "Сейчас ход:",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        TurnPieceIndicator(
                            player = gameState.turn?.player,
                            pieceSet = settings.pieceSet,
                            finishedText = gameState.statusText(),
                        )
                        Text(
                            text = if (isViewingLatest) {
                                "Всего ходов: ${session.moves.size}"
                            } else {
                                "Просмотр: $displayedMoveCount из ${session.moves.size} ходов"
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
                                Text("Назад")
                            }
                            TextButton(
                                onClick = {
                                    historyNavigation = historyNavigation.forward(session)
                                    clearTransientState()
                                },
                                enabled = historyNavigation.canGoForward(session),
                            ) {
                                Text("Вперёд")
                            }
                            TextButton(
                                onClick = {
                                    val undone = session.undoLastMove() ?: return@TextButton
                                    storageMessage = when (val saveResult = save(undone)) {
                                        SaveGameResult.Saved -> {
                                            session = undone
                                            historyNavigation = GameHistoryNavigation.latest()
                                            clearTransientState()
                                            "Последний ход отменён"
                                        }

                                        is SaveGameResult.Failed ->
                                            "Не удалось отменить ход: ${saveResult.message}"
                                    }
                                },
                                enabled = isViewingLatest && session.moves.isNotEmpty(),
                            ) {
                                Text("Отменить ход")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ThreePlayerChessBoard(
                            pieces = pieces,
                            selectedPieceId = selectedPieceId,
                            moveHints = selectedMoveHints.currentPossibleMoves,
                            moveLineHints = selectedMoveHints.moveLines,
                            trophies = trophies,
                            pieceSet = settings.pieceSet,
                            showDecorativeBirds = gameState.turn == null,
                            onCellSelected = { cell ->
                                if (!isViewingLatest) return@ThreePlayerChessBoard
                                if (cell == null) {
                                    selectedPieceId = null
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
                                    if (matchingMoves.size == 1) {
                                        applyMove(matchingMoves.single())
                                    } else {
                                        pendingPromotionMoves = matchingMoves
                                    }
                                } else {
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
                            modifier = if (compactLayout) {
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(
                                        threePlayerBoardAspectRatio(
                                            settings.pieceSet,
                                            gameState.turn == null,
                                            boardZoom,
                                        ),
                                    )
                            } else {
                                Modifier.weight(1f).fillMaxWidth()
                            },
                            onZoomChanged = { boardZoom = it },
                        )
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
                                TextButton(onClick = { applyMove(move) }) {
                                    Text(checkNotNull(move.promotion).displayName())
                                }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingPromotionMoves = emptyList() }) {
                            Text("Отмена")
                        }
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
                    "Восстановлено ${restored.restoredMoves} ходов из ${restored.totalMoves}"
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
    pieceSet: PieceSet,
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
    val resourceName = turnIndicatorAssetName(player, pieceSet)
    Image(
        bitmap = imageResource(Res.allDrawableResources.getValue(resourceName)),
        contentDescription = turnIndicatorDescription(player, pieceSet),
        modifier = Modifier.size(128.dp),
        contentScale = ContentScale.Fit,
    )
}

internal fun turnIndicatorAssetName(player: PlayerId, pieceSet: PieceSet): String {
    val index = when (player) {
        PlayerId.WHITE -> 0
        PlayerId.RED -> 1
        PlayerId.BLACK -> 2
    }
    return if (pieceSet == PieceSet.FAIRY) "bird_$index" else "king_$index"
}

private fun turnIndicatorDescription(player: PlayerId, pieceSet: PieceSet): String {
    val color = when (player) {
        PlayerId.WHITE -> "Белая"
        PlayerId.RED -> "Красная"
        PlayerId.BLACK -> "Чёрная"
    }
    val figure = if (pieceSet == PieceSet.FAIRY) "птица" else "фигура короля"
    return "$color $figure"
}

private fun PromotionChoice.displayName(): String = when (this) {
    PromotionChoice.QUEEN -> "Ферзь"
    PromotionChoice.ROOK -> "Ладья"
    PromotionChoice.BISHOP -> "Слон"
    PromotionChoice.KNIGHT -> "Конь"
}

internal fun com.chesstree.game.domain.GameState.statusText(): String {
    val currentPhase = phase
    val phaseText = when (currentPhase) {
        GamePhase.InProgress -> turn?.let { currentTurn ->
            val check = if (LegalMoveGenerator.isKingInCheck(this, currentTurn.player)) {
                ", шах"
            } else {
                ""
            }
            "Ход: ${currentTurn.player.name.lowercase()}, полуход ${currentTurn.ply}$check"
        } ?: "Партия продолжается"

        is GamePhase.Finished -> when (currentPhase.outcome) {
            is GameOutcome.Ranked -> "Партия завершена"
            is GameOutcome.ThreeWayDraw -> "Ничья"
            is GameOutcome.TwoWayDraw -> "Ничья"
        }
    }
    val eliminated = participants.values.mapNotNull { participant ->
        when (participant.status) {
            ParticipantStatus.Active -> null
            is ParticipantStatus.Checkmated -> "${participant.id.name.lowercase()}: мат"
            is ParticipantStatus.Stalemated -> "${participant.id.name.lowercase()}: пат"
        }
    }
    return listOf(phaseText, eliminated.joinToString()).filter(String::isNotEmpty)
        .joinToString(" · ")
}
