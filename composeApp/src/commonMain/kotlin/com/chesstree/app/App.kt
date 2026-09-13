package com.chesstree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.dp
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.educationalMoveHintsFor
import com.chesstree.game.presentation.board.legalMoveHintsFor
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.NoOpGameSaveStore
import com.chesstree.game.data.SaveGameResult
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.multiplayer.data.NoOpOnlineSessionStore
import com.chesstree.multiplayer.data.OnlineSessionStore
import com.chesstree.multiplayer.presentation.MultiplayerScreen

@Composable
fun App(
    gameSaveStore: GameSaveStore = NoOpGameSaveStore,
    onlineApi: ChessTreeApi? = null,
    onlineSessionStore: OnlineSessionStore = NoOpOnlineSessionStore,
    initialGameCode: String? = null,
) {
    MaterialTheme {
        var showMultiplayer by remember { mutableStateOf(initialGameCode != null) }
        LaunchedEffect(initialGameCode) {
            if (initialGameCode != null) showMultiplayer = true
        }
        if (showMultiplayer && onlineApi != null) {
            MultiplayerScreen(
                api = onlineApi,
                sessionStore = onlineSessionStore,
                initialGameCode = initialGameCode.orEmpty(),
                onClose = { showMultiplayer = false },
            )
            return@MaterialTheme
        }
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
        var selectedPieceId by remember { mutableStateOf<String?>(null) }
        var pendingPromotionMoves by remember { mutableStateOf(emptyList<Move>()) }
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        var controlsExpanded by remember { mutableStateOf(false) }
        var showMoveLines by remember { mutableStateOf(false) }
        val pieceSetSaver = remember {
            Saver<PieceSet, String>(
                save = { it.name },
                restore = ::restorePieceSet,
            )
        }
        var pieceSet by rememberSaveable(stateSaver = pieceSetSaver) {
            mutableStateOf(PieceSet.STANDARD)
        }
        var storageMessage by remember { mutableStateOf<String?>(null) }
        val selectedScenario = session.scenario
        val gameState = session.state
        val pieces = remember(gameState) { gameState.toBoardPieces() }
        val movementHints = remember(gameState, selectedPieceId, showMoveLines) {
            val pieceId = selectedPieceId?.let(::PieceId) ?: return@remember emptyList()
            if (showMoveLines) {
                educationalMoveHintsFor(gameState, pieceId)
            } else {
                legalMoveHintsFor(gameState, pieceId)
            }
        }
        val trophies = remember(session.capturedPieces) {
            session.capturedPieces.map { captured ->
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
        fun applyMove(move: Move) {
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
                    val snapshot = GameSnapshot(
                        scenarioId = result.session.scenario.id,
                        moves = result.session.moves,
                    )
                    storageMessage = when (
                        val saveResult = gameSaveStore.save(GameSnapshotCodec.encode(snapshot))
                    ) {
                        SaveGameResult.Saved -> "Партия сохранена"
                        is SaveGameResult.Failed -> "Не удалось сохранить: ${saveResult.message}"
                    }
                }
                SessionMoveResult.Rejected -> Unit
            }
            clearTransientState()
        }
        fun restart() {
            session = GameSession(session.scenario)
            clearTransientState()
            storageMessage = "Партия перезапущена; последнее сохранение не изменено"
        }
        fun load() {
            storageMessage = when (val loadResult = gameSaveStore.load()) {
                LoadGameResult.Missing -> "Сохранённая партия не найдена"
                is LoadGameResult.Failed -> "Не удалось загрузить: ${loadResult.message}"
                is LoadGameResult.Loaded -> {
                    val snapshot = GameSnapshotCodec.decode(loadResult.contents)
                    val scenario = snapshot?.let { saved ->
                        scenarios.firstOrNull { it.id == saved.scenarioId }
                    }
                    val loadedSession = if (snapshot != null && scenario != null) {
                        GameSession.replay(scenario, snapshot.moves)
                    } else {
                        null
                    }
                    if (loadedSession == null) {
                        "Сохранение повреждено или несовместимо"
                    } else {
                        session = loadedSession
                        clearTransientState()
                        "Партия загружена"
                    }
                }
            }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF3EEE6)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFFF8F4EE), Color(0xFFE6DDD1)),
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 920.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "ChessTree",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color(0xFF34251D),
                    )
                    Text(
                        text = "Шахматы для троих",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF735E50),
                    )
                    Text(
                        text = selectedScenario.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF735E50),
                    )
                    Text(
                        text = gameState.statusText(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF5C4336),
                    )
                    Spacer(Modifier.height(8.dp))
                    ThreePlayerChessBoard(
                        pieces = pieces,
                        selectedPieceId = selectedPieceId,
                        moveHints = movementHints,
                        trophies = trophies,
                        pieceSet = pieceSet,
                        onCellSelected = { cell ->
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
                                    ?.takeIf { showMoveLines || canMovePiece }
                                    ?.id?.value
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
                TextButton(
                    onClick = { controlsExpanded = !controlsExpanded },
                    modifier = Modifier.align(Alignment.TopStart),
                ) {
                    Text(if (controlsExpanded) "Закрыть" else "☰")
                }
                if (controlsExpanded) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 48.dp)
                            .widthIn(max = 320.dp),
                        color = Color(0xFFFFFBF6),
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("Управление", style = MaterialTheme.typography.titleMedium)
                            Button(onClick = ::restart, modifier = Modifier.fillMaxWidth()) {
                                Text("Рестарт")
                            }
                            Button(onClick = ::load, modifier = Modifier.fillMaxWidth()) {
                                Text("Загрузить")
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
                                Text("Показывать линии ходов")
                                Switch(
                                    checked = showMoveLines,
                                    onCheckedChange = { enabled ->
                                        showMoveLines = enabled
                                        if (!enabled) selectedPieceId = null
                                    },
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Сказочные фигурки")
                                Switch(
                                    checked = pieceSet == PieceSet.FAIRY,
                                    onCheckedChange = { enabled ->
                                        pieceSet = if (enabled) PieceSet.FAIRY else PieceSet.STANDARD
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
    }
}

private fun PromotionChoice.displayName(): String = when (this) {
    PromotionChoice.QUEEN -> "Ферзь"
    PromotionChoice.ROOK -> "Ладья"
    PromotionChoice.BISHOP -> "Слон"
    PromotionChoice.KNIGHT -> "Конь"
}

internal fun restorePieceSet(savedName: String): PieceSet =
    PieceSet.entries.firstOrNull { it.name == savedName } ?: PieceSet.STANDARD

private fun com.chesstree.game.domain.GameState.statusText(): String {
    val phaseText = when (phase) {
        GamePhase.InProgress -> turn?.let { currentTurn ->
            val check = if (LegalMoveGenerator.isKingInCheck(this, currentTurn.player)) {
                ", шах"
            } else {
                ""
            }
            "Ход: ${currentTurn.player.name.lowercase()}, полуход ${currentTurn.ply}$check"
        } ?: "Партия продолжается"

        is GamePhase.Finished -> "Партия завершена"
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
