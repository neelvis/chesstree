package com.chesstree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.history.GameHistoryDialog
import com.chesstree.game.presentation.history.GameHistoryNavigation
import com.chesstree.game.presentation.history.GameLogExporter
import com.chesstree.game.presentation.history.NoOpGameLogExporter
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.GameLogCodec
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.NoOpGameSaveStore
import com.chesstree.game.data.SaveGameResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.presentation.GameLinkSharer
import com.chesstree.multiplayer.presentation.MultiplayerScreen
import com.chesstree.resources.Res
import com.chesstree.resources.allDrawableResources
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
    MaterialTheme {
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
        var showMultiplayer by rememberSaveable { mutableStateOf(initialGameCode != null) }
        LaunchedEffect(initialGameCode) {
            if (initialGameCode != null) showMultiplayer = true
        }
        if (showMultiplayer && onlineApi != null) {
            MultiplayerScreen(
                api = onlineApi,
                sessionStore = onlineSessionStore,
                initialGameCode = initialGameCode.orEmpty(),
                gameLinkSharer = gameLinkSharer,
                onClose = { showMultiplayer = false },
            )
            return@MaterialTheme
        }
        var selectedPieceId by remember { mutableStateOf<String?>(null) }
        var pendingPromotionMoves by remember { mutableStateOf(emptyList<Move>()) }
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        var controlsExpanded by remember { mutableStateOf(false) }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        var historyNavigation by remember { mutableStateOf(GameHistoryNavigation.latest()) }
        val selectedScenario = session.scenario
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
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF3EEE6)) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFF8F4EE), Color(0xFFE6DDD1)),
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
                    Text(
                        text = "Сейчас ход:",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF34251D),
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
                        color = Color(0xFF5C4336),
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                if (controlsExpanded) {
                    val panelMaxHeight = (maxHeight - 56.dp).coerceAtLeast(200.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures { controlsExpanded = false }
                            },
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 48.dp)
                            .widthIn(min = 280.dp, max = 340.dp)
                            .heightIn(max = panelMaxHeight)
                            .pointerInput(Unit) { detectTapGestures { } },
                        color = Color(0xFFFFFBF6),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Управление", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = ::restart, modifier = Modifier.fillMaxWidth()) {
                                Text("Рестарт")
                            }
                            Button(
                                onClick = { showMultiplayer = true },
                                enabled = onlineApi != null,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Онлайн")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Показывать линии ходов")
                                    Text(
                                        "Показывать принципиально возможные направления хода фигур",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = settings.showMoveLines,
                                    onCheckedChange = { enabled ->
                                        settings = settings.copy(showMoveLines = enabled)
                                        if (!enabled) {
                                            val selectedPiece = selectedPieceId
                                                ?.let(::PieceId)
                                                ?.let(gameState.position.pieces::get)
                                            val belongsToCurrentPlayer = selectedPiece?.let { piece ->
                                                gameState.turn?.player ==
                                                    gameState.armies.getValue(piece.army).controller
                                            } == true
                                            if (!belongsToCurrentPlayer) selectedPieceId = null
                                        }
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Показать историю игры",
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = settings.showGameHistory,
                                    onCheckedChange = { enabled ->
                                        settings = settings.copy(showGameHistory = enabled)
                                        if (enabled) controlsExpanded = false
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    "Показывать текущие возможные ходы",
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = settings.showCurrentPossibleMoves,
                                    onCheckedChange = { enabled ->
                                        settings = settings.copy(showCurrentPossibleMoves = enabled)
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Premium фигуры")
                                Switch(
                                    checked = settings.pieceSet == PieceSet.FAIRY,
                                    onCheckedChange = { enabled ->
                                        settings = settings.copy(
                                            pieceSet = if (enabled) {
                                                PieceSet.FAIRY
                                            } else {
                                                PieceSet.STANDARD
                                            },
                                        )
                                    },
                                )
                            }
                            Box {
                                Button(onClick = { scenarioMenuExpanded = true }) {
                                    Text("Сценарий: ${selectedScenario.title}")
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
                            storageMessage?.let { message ->
                                Text(message, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { controlsExpanded = !controlsExpanded },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(if (controlsExpanded) "Закрыть" else "☰")
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
        if (settings.showGameHistory) {
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
            color = Color(0xFF5C4336),
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
    return listOf(phaseText, eliminated.joinToString()).filter(String::isNotEmpty).joinToString(" · ")
}
