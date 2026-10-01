package com.chesstree.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesstree.game.data.LocalBotMoveDiagnostic
import kotlin.time.TimeSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.LocalBotGameConfig
import com.chesstree.game.data.LocalBotGameConfigCodec
import com.chesstree.game.presentation.components.LocalBotSetupScreen
import com.chesstree.game.data.GameSaveStore
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.SaveGameResult
import com.chesstree.game.domain.bot.BoundedBotResult
import com.chesstree.game.domain.bot.BotPolicyLearner
import com.chesstree.game.domain.bot.BotTrainingSample
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
import com.chesstree.game.presentation.board.BoardCellId
import com.chesstree.game.presentation.board.BoardMoveAnimationKey
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.PieceSet
import com.chesstree.game.presentation.board.PromotionPiecePickerDialog
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.displayedTurnPlayer
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.history.GameHistoryDialog
import com.chesstree.game.presentation.history.GameHistoryNavigation
import com.chesstree.game.presentation.history.GameLogExporter
import com.chesstree.game.presentation.bot.requestForTurn
import com.chesstree.game.presentation.bot.beforeLatestHumanMove
import com.chesstree.game.presentation.bot.LocalBotIdentity
import com.chesstree.game.presentation.bot.LocalBotReply
import com.chesstree.game.presentation.bot.LocalBotActivity
import com.chesstree.game.presentation.bot.LocalBotRunner
import com.chesstree.game.presentation.bot.matchesCurrentRequest
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random
@Composable
internal fun ChessNavigationRoot(
    botRunner: LocalBotRunner,
    botActivity: LocalBotActivity,
    gameSaveStore: GameSaveStore,
    settings: GameSettings,
    onSettingsChanged: (GameSettings) -> Unit,
    gameLogExporter: GameLogExporter,
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    onBack: () -> Unit,
) {
    val scenarios = remember { ManualGameScenarios.all }
    val loadedSave = remember(gameSaveStore) { gameSaveStore.load() }
    val loadedSnapshot = remember(loadedSave, scenarios) {
        (loadedSave as? LoadGameResult.Loaded)?.contents?.let(GameSnapshotCodec::decode)
            ?.takeIf { restoreSnapshot(it, scenarios) != null }
    }
    val sessionSaver = remember(scenarios) {
        Saver<GameSession, String>(
            save = { encodeSessionForRestorationOrNull(it) },
            restore = { restoreSessionOrDefault(it, scenarios) },
        )
    }
    var session by rememberSaveable(stateSaver = sessionSaver) {
        mutableStateOf(loadedSnapshot?.let { restoreSnapshot(it, scenarios) } ?: GameSession(scenarios.first()))
    }
    var selectedPieceId by remember { mutableStateOf<String?>(null) }
    var pendingPromotionMoves by remember { mutableStateOf(emptyList<Move>()) }
    var zoomConfirmationArmed by remember { mutableStateOf(false) }
    var zoomToCell by remember { mutableStateOf<BoardCellId?>(null) }
    var zoomOutRequest by remember { mutableStateOf(0L) }
    var scenarioMenuExpanded by remember { mutableStateOf(false) }
    var botConfigContents by rememberSaveable {
        mutableStateOf(loadedSnapshot?.botGame?.let(LocalBotGameConfigCodec::encode))
    }
    val botConfig = remember(botConfigContents) { botConfigContents?.let(LocalBotGameConfigCodec::decode) }
    val diagnosticsSaver = remember {
        Saver<List<LocalBotMoveDiagnostic>, String>(
            save = { it.joinToString("\n", transform = LocalBotMoveDiagnostic::encode) },
            restore = { contents ->
                if (contents.isEmpty()) emptyList() else {
                    contents.lineSequence().map { LocalBotMoveDiagnostic.decode(it) }.toList()
                        .takeIf { it.all { entry -> entry != null } }?.filterNotNull()
                }
            },
        )
    }
    var botDiagnostics by rememberSaveable(stateSaver = diagnosticsSaver) {
        mutableStateOf(loadedSnapshot?.diagnostics ?: emptyList())
    }
    var botGameEnabled by rememberSaveable { mutableStateOf(loadedSnapshot?.botsRunning == true) }
    var showBotSetup by remember { mutableStateOf(false) }
    var moveAnimationInProgress by remember { mutableStateOf(false) }
    var localGameId by rememberSaveable { mutableStateOf(Random.nextLong().toString()) }
    var positionRevision by rememberSaveable { mutableStateOf(0L) }
    var requestSequence by rememberSaveable { mutableStateOf(0L) }
    var activeRequest by remember { mutableStateOf<LocalBotIdentity?>(null) }
    var botFailed by remember { mutableStateOf(false) }
    var retrySequence by remember { mutableStateOf(0L) }
    val legacyPolicy = remember(localGameId) { gameSaveStore.loadBotPolicy() }
    val activePolicy = botConfig?.basePolicy ?: legacyPolicy
    var learnedGameId by rememberSaveable { mutableStateOf<String?>(null) }
    var storageMessage by remember {
        mutableStateOf<String?>(if (loadedSave is LoadGameResult.Failed || loadedSave is LoadGameResult.Loaded && loadedSnapshot == null) "i18n:bot_restore_invalid" else null)
    }
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
    val restartDescription = localized("restart")
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

    fun invalidateBotPosition() {
        activeRequest = null
        positionRevision += 1
        botFailed = false
    }

    fun save(updatedSession: GameSession, diagnostics: List<LocalBotMoveDiagnostic> = botDiagnostics): SaveGameResult {
        val snapshot = GameSnapshot(
            scenarioId = updatedSession.scenario.id,
            moves = updatedSession.moves,
            botGame = botConfigContents?.let(LocalBotGameConfigCodec::decode),
            botsRunning = botGameEnabled && botConfigContents != null,
            initialState = updatedSession.scenario.initialState,
            diagnostics = diagnostics,
        )
        return try {
            val contents = GameSnapshotCodec.encodeOrNull(snapshot)
                ?: return SaveGameResult.Failed("i18n:game_record_too_large")
            gameSaveStore.save(contents)
        } catch (_: IllegalArgumentException) {
            SaveGameResult.Failed("i18n:action_unavailable")
        }
    }

    fun applyMove(move: Move) {
        if (!isViewingLatest || (botConfig != null && session.state.turn?.player != botConfig.humanSeat)) return
        when (val result = session.apply(
            MoveIntent(
                actor = move.actor,
                from = move.from,
                to = move.to,
                promotion = move.promotion,
            ),
        )) {
            is SessionMoveResult.Applied -> {
                invalidateBotPosition()
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
        invalidateBotPosition()
        session = GameSession(session.scenario)
        botDiagnostics = emptyList()
        localGameId = Random.nextLong().toString()
        historyNavigation = GameHistoryNavigation.latest()
        clearTransientState()
        storageMessage = when (val result = save(session)) {
            SaveGameResult.Saved -> "i18n:game_restarted"
            is SaveGameResult.Failed -> "i18n:save_failed|${result.message}"
        }
    }

    LaunchedEffect(session.state, botGameEnabled, botConfigContents, showBotSetup, localGameId, positionRevision,
        retrySequence, isViewingLatest, settings.showGameHistory, botRunner, botActivity) {
        botActivity.state.collectLatest { activityState ->
            val currentSession = session
            if (!activityState.active || !botGameEnabled || botConfig == null || showBotSetup || !isViewingLatest || settings.showGameHistory) return@collectLatest
            val finishedOutcome = (currentSession.state.phase as? GamePhase.Finished)?.outcome
            if (finishedOutcome != null) {
                if (botConfig.humanSeat != null) return@collectLatest
                if (learnedGameId == localGameId) return@collectLatest
                val finishedGameId = localGameId
                val finishedRevision = positionRevision
                val samples = withContext(Dispatchers.Default) {
                    currentSession.moves.indices.mapNotNull { moveIndex ->
                        val before = currentSession.atMoveCount(moveIndex)?.state ?: return@mapNotNull null
                        val after = currentSession.atMoveCount(moveIndex + 1)?.state ?: return@mapNotNull null
                        BotTrainingSample.fromTransition(
                            before = before,
                            after = after,
                            player = currentSession.moves[moveIndex].actor,
                        )
                    }
                }
                val learnedPolicy = BotPolicyLearner.learn(
                    policy = activePolicy,
                    outcome = finishedOutcome,
                    samples = samples,
                )
                if (botActivity.state.value != activityState || !botGameEnabled || localGameId != finishedGameId || positionRevision != finishedRevision) {
                    return@collectLatest
                }
                storageMessage = when (val result = gameSaveStore.saveBotPolicy(learnedPolicy)) {
                    SaveGameResult.Saved -> {
                        learnedGameId = localGameId
                        "i18n:bot_policy_learned"
                    }

                    is SaveGameResult.Failed -> "i18n:bot_policy_save_failed|${result.message}"
                }
                return@collectLatest
            }
            val turn = currentSession.state.turn
            if (turn == null || currentSession.state.phase != GamePhase.InProgress) {
                return@collectLatest
            }
            if (botConfig.seatFor(turn.player) == null) return@collectLatest
            requestSequence += 1
            val identity = LocalBotIdentity(requestSequence.toString(), localGameId, positionRevision)
            val request = botConfig.requestForTurn(identity, currentSession) ?: return@collectLatest
            activeRequest = identity
            botFailed = false
            val started = TimeSource.Monotonic.markNow()
            try {
                val reply = try {
                    botRunner.choose(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    LocalBotReply.Failed(identity)
                }
                currentCoroutineContext().ensureActive()
                if (botActivity.state.value != activityState || !botGameEnabled || !historyNavigation.isAtLatest(session)) return@collectLatest
                if (!reply.matchesCurrentRequest(request, activeRequest, localGameId, positionRevision, session.state)) {
                    if (activeRequest == identity) botFailed = true
                    return@collectLatest
                }
                val decision = (reply as? LocalBotReply.Completed)?.result
                if (decision is BoundedBotResult.Move) {
                    val result = currentSession.apply(decision.intent) as? SessionMoveResult.Applied
                    if (result == null) {
                        botFailed = true
                        return@collectLatest
                    }
                    invalidateBotPosition()
                    session = result.session
                    if (result.session.moves.size <= GameSnapshotCodec.MAX_MOVES) botDiagnostics = botDiagnostics + LocalBotMoveDiagnostic(
                        moveIndex = result.session.moves.size, player = turn.player,
                        elapsedMs = started.elapsedNow().inWholeMilliseconds, source = decision.source,
                        reason = decision.reason, stats = decision.stats, openingBook = decision.openingBook,
                        repetitionPenalty = decision.repetitionPenalty,
                    )
                    historyNavigation = GameHistoryNavigation.latest()
                    storageMessage = when (val saveResult = save(result.session)) {
                        SaveGameResult.Saved -> null
                        is SaveGameResult.Failed -> "i18n:save_failed|${saveResult.message}"
                    }
                } else {
                    botFailed = true
                }
            } finally {
                if (activeRequest == identity) activeRequest = null
            }
        }
    }
    LaunchedEffect(botConfigContents, botGameEnabled, localGameId) {
        // Freeze the setup with the position even before the first move or after a pause.
        if (canPersistInitialGame(loadedSave, loadedSnapshot, positionRevision)) {
            when (val result = save(session)) {
                SaveGameResult.Saved -> Unit
                is SaveGameResult.Failed -> storageMessage = "i18n:save_failed|${result.message}"
            }
        }
    }
    if (showBotSetup) {
        LocalBotSetupScreen(
            currentConfig = botConfig,
            onDismiss = { showBotSetup = false },
            onStart = { config ->
                invalidateBotPosition()
                session = GameSession(scenarios.first { it.id == "standard" })
                botDiagnostics = emptyList()
                botConfigContents = LocalBotGameConfigCodec.encode(
                    LocalBotGameConfig(config.humanSeat, config.seats),
                )
                localGameId = Random.nextLong().toString()
                learnedGameId = null
                botGameEnabled = true
                showBotSetup = false
                historyNavigation = GameHistoryNavigation.latest()
                clearTransientState()
            },
        )
        return
    }
    if (settings.showGameHistory) {
        GameHistoryDialog(
            log = remember(session) { GameLogCodec.encode(session) },
            exporter = gameLogExporter,
            exportContents = GameSnapshotCodec.encodeOrNull(GameSnapshot(
                scenarioId = session.scenario.id,
                moves = session.moves,
                botGame = botConfig,
                botsRunning = botGameEnabled && botConfig != null,
                initialState = session.scenario.initialState,
                diagnostics = botDiagnostics,
            )),
            onRestore = { contents ->
                val snapshot = GameSnapshotCodec.decode(contents)
                val restoredSession = if (snapshot != null) restoreSnapshot(snapshot, scenarios) else null
                if (contents.trimStart().startsWith("CHESSTREE|") && restoredSession == null) {
                    "i18n:bot_restore_invalid"
                } else {
                    val restoredLog = if (snapshot == null) GameLogCodec.restore(session.scenario, contents) else null
                    val restored = restoredSession ?: checkNotNull(restoredLog).session
                    invalidateBotPosition()
                    localGameId = Random.nextLong().toString()
                    learnedGameId = null
                    botConfigContents = snapshot?.botGame?.let(LocalBotGameConfigCodec::encode)
                    // Imported games open paused so the restored position remains inspectable.
                    botGameEnabled = false
                    session = restored
                    botDiagnostics = snapshot?.diagnostics ?: emptyList()
                    historyNavigation = GameHistoryNavigation.latest()
                    clearTransientState()
                    when (val result = save(restored)) {
                        SaveGameResult.Saved -> "i18n:restore_moves|${restored.moves.size}|${snapshot?.moves?.size ?: restoredLog?.totalMoves ?: 0}"
                        is SaveGameResult.Failed -> "i18n:save_failed|${result.message}"
                    }
                }
            },
            onDismiss = {
                onSettingsChanged(settings.copy(showGameHistory = false))
            },
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.background,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
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
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!hasSystemBackNavigation) {
                            TextButton(
                                onClick = {
                                    activeRequest = null
                                    onBack()
                                },
                            ) {
                                Text(
                                    localized("back_with_chevron"),
                                    maxLines = 1,
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            invalidateBotPosition()
                            clearTransientState()
                            showBotSetup = true
                        }) { Text(localized("bot_new_game"), maxLines = 1) }
                        TextButton(
                            onClick = {
                                if (botConfig != null) {
                                    invalidateBotPosition()
                                    clearTransientState()
                                    botGameEnabled = !botGameEnabled
                                } else if (botGameEnabled) {
                                    invalidateBotPosition()
                                    botGameEnabled = false
                                    botConfigContents = null
                                } else {
                                    invalidateBotPosition()
                                    session = GameSession(scenarios.first { it.id == "standard" })
                                    botDiagnostics = emptyList()
                                    localGameId = Random.nextLong().toString()
                                    learnedGameId = null
                                    historyNavigation = GameHistoryNavigation.latest()
                                    clearTransientState()
                                    botConfigContents = LocalBotGameConfigCodec.encode(
                                        LocalBotGameConfig.watchGame(
                                            seeds = PlayerId.entries.associateWith { Random.nextLong() },
                                            basePolicy = gameSaveStore.loadBotPolicy(),
                                        ),
                                    )
                                    botGameEnabled = true
                                }
                                // State changes are saved by the setup persistence effect below.
                            },
                        ) {
                            Text(localized(
                                if (botConfig != null) {
                                    if (botGameEnabled) "bot_pause" else "bot_resume"
                                } else if (botGameEnabled) "stop_three_bots" else "watch_three_bots",
                            ), maxLines = 1)
                        }
                        Box {
                            TextButton(
                                onClick = { scenarioMenuExpanded = true },
                            ) {
                                Text(
                                    localized("scenario"),
                                    maxLines = 1,
                                )
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
                                            invalidateBotPosition()
                                            session = GameSession(scenario)
                                            botDiagnostics = emptyList()
                                            localGameId = Random.nextLong().toString()
                                            learnedGameId = null
                                            botGameEnabled = false
                                            botConfigContents = null
                                            historyNavigation = GameHistoryNavigation.latest()
                                            clearTransientState()
                                            storageMessage = null
                                            scenarioMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = ::restart,
                            modifier = Modifier.semantics {
                                contentDescription = restartDescription
                            },
                        ) {
                            Text("↻", fontSize = 24.sp)
                        }
                    }
                    TurnPieceIndicator(
                        player = displayedTurnPlayer(
                            gameTurn = gameState.turn?.player,
                            lastMoveActor = session.moves.lastOrNull()?.actor,
                            moveAnimationInProgress = moveAnimationInProgress,
                        ),
                        finishedText = gameState.statusText(),
                    )
                    if (gameState.turn != null) {
                        Text(
                            text = if (activeRequest != null) localized("bot_thinking_seat", localized(when (gameState.turn?.player) {
                                PlayerId.WHITE -> "turn_player_white"
                                PlayerId.RED -> "turn_player_red"
                                PlayerId.BLACK -> "turn_player_black"
                                null -> "current_turn"
                            })) else localized("current_turn"),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    if (botFailed) {
                        Text(localized("bot_search_failed"), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retrySequence += 1 }) {
                            Text(localized("bot_retry"))
                        }
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
                        TextButton(onClick = {
                            invalidateBotPosition()
                            clearTransientState()
                            onSettingsChanged(settings.copy(showGameHistory = true))
                        }) { Text(localized("history_dialog_title")) }
                        TextButton(
                            onClick = {
                                val undone = botConfig?.let { session.beforeLatestHumanMove(it) }
                                    ?: if (botConfig == null) session.undoLastMove() else null
                                if (undone == null) return@TextButton
                                val remainingDiagnostics = botDiagnostics.filter { it.moveIndex <= undone.moves.size }
                                storageMessage = when (val saveResult = save(undone, remainingDiagnostics)) {
                                    SaveGameResult.Saved -> {
                                        invalidateBotPosition()
                                        session = undone
                                        botDiagnostics = remainingDiagnostics
                                        historyNavigation = GameHistoryNavigation.latest()
                                        clearTransientState()
                                        "i18n:last_move_undone"
                                    }

                                    is SaveGameResult.Failed ->
                                        "i18n:undo_failed|${saveResult.message}"
                                }
                            },
                            enabled = isViewingLatest && if (botConfig?.humanSeat != null) {
                                session.moves.any { it.actor == botConfig.humanSeat }
                            } else !botGameEnabled && session.moves.isNotEmpty(),
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
                                gameId = "solo:$localGameId",
                                moveCount = session.moves.size,
                            ),
                            animatePieceMovement = settings.animatePieceMovement,
                            onMoveAnimationInProgressChanged = { inProgress ->
                                moveAnimationInProgress = inProgress
                            },
                            pieceSet = settings.pieceSet,
                            showDecorativeBirds = gameState.turn == null,
                            onCellSelected = { cell ->
                                if (showBotSetup || (botConfig != null && gameState.turn?.player != botConfig.humanSeat) ||
                                    (botConfig == null && botGameEnabled)) return@ThreePlayerChessBoard
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
                                } else if (selectedPieceId != null) {
                                    selectedPieceId = null
                                    zoomConfirmationArmed = false
                                    zoomToCell = null
                                    zoomOutRequest += 1
                                    return@ThreePlayerChessBoard
                                } else {
                                    if (zoomConfirmationArmed) {
                                        zoomOutRequest += 1
                                    }
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
                if (settings.showGameHistory) {
                    onSettingsChanged(settings.copy(showGameHistory = false))
                }
                onSelectTab(tab)
            },
        )
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
