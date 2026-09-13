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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chesstree.multiplayer.contract.GameResponse
import com.chesstree.multiplayer.data.ChessTreeApi
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.BoardTrophy
import com.chesstree.game.presentation.board.legalMoveHintsFor
import com.chesstree.game.presentation.board.toBoardPieces
import kotlinx.coroutines.delay

@Composable
fun MultiplayerScreen(
    api: ChessTreeApi,
    initialGameCode: String = "",
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(api, scope, initialGameCode) {
        MultiplayerController(api, scope, initialGameCode)
    }
    val state by controller.state.collectAsState()

    LaunchedEffect(state.game?.code, state.game?.status) {
        while (state.game?.status == "WAITING" || state.game?.status == "ACTIVE") {
            delay(2_000)
            controller.refreshGame()
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
                    LobbyContent(state, controller)
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.loading) {
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
    OutlinedTextField(
        value = state.username,
        onValueChange = controller::setUsername,
        label = { Text("Логин") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.password,
        onValueChange = controller::setPassword,
        label = { Text("Пароль") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = controller::submitAuthentication,
        enabled = !state.loading && state.username.isNotBlank() && state.password.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (state.authMode == AuthMode.LOGIN) "Войти" else "Создать аккаунт")
    }
}

@Composable
private fun LobbyContent(state: MultiplayerUiState, controller: MultiplayerController) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Игрок: ${state.authentication?.user?.username}")
        TextButton(onClick = controller::logout, enabled = !state.loading) { Text("Выйти") }
    }
    Button(
        onClick = controller::createGame,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Создать игру") }
    OutlinedTextField(
        value = state.gameCode,
        onValueChange = controller::setGameCode,
        label = { Text("Код игры") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = controller::joinGame,
        enabled = !state.loading && state.gameCode.length == 7,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Присоединиться") }
    state.game?.let { game ->
        if ((game.status == "ACTIVE" || game.status == "FINISHED") && state.session != null) {
            OnlineGame(state, controller)
        } else {
            GameLobby(game, controller, state.loading)
        }
    }
}

@Composable
private fun OnlineGame(state: MultiplayerUiState, controller: MultiplayerController) {
    val session = checkNotNull(state.session)
    val game = checkNotNull(state.game)
    val assignedPlayer = game.players
        .firstOrNull { it.user.id == state.authentication?.user?.id }
        ?.color
        ?.let { runCatching { com.chesstree.game.domain.PlayerId.valueOf(it) }.getOrNull() }
    var selectedPieceId by remember(session.state) { mutableStateOf<String?>(null) }
    var pendingPromotionMoves by remember(session.state) { mutableStateOf(emptyList<Move>()) }
    val hints = remember(session.state, selectedPieceId) {
        selectedPieceId?.let(::PieceId)?.let { legalMoveHintsFor(session.state, it) }.orEmpty()
    }
    val canAct = !state.loading && session.state.turn?.player == assignedPlayer

    Text("Ревизия: ${state.remoteState?.revision ?: 0}")
    Text(
        when {
            game.status == "FINISHED" -> "Партия завершена"
            canAct -> "Ваш ход"
            else -> "Ожидаем ход другого игрока"
        },
    )
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 720.dp),
    )
    OutlinedButton(
        onClick = controller::refreshGame,
        enabled = !state.loading,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Синхронизировать") }

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
private fun GameLobby(game: GameResponse, controller: MultiplayerController, loading: Boolean) {
    Spacer(Modifier.height(8.dp))
    Text("Код: ${game.code}", style = MaterialTheme.typography.titleLarge)
    Text(game.shareUrl, style = MaterialTheme.typography.bodySmall)
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
