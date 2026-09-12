package com.chesstree.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.legalMoveHintsFor
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.scenario.ManualGameScenarios

@Composable
fun App() {
    MaterialTheme {
        val scenarios = remember { ManualGameScenarios.all }
        var selectedScenario by remember { mutableStateOf(scenarios.first()) }
        var gameState by remember { mutableStateOf(selectedScenario.initialState) }
        var selectedPieceId by remember { mutableStateOf<String?>(null) }
        var pendingPromotionMoves by remember { mutableStateOf(emptyList<Move>()) }
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        val pieces = remember(gameState) { gameState.toBoardPieces() }
        val movementHints = remember(gameState, selectedPieceId) {
            selectedPieceId
                ?.let(::PieceId)
                ?.let { pieceId -> legalMoveHintsFor(gameState, pieceId) }
                .orEmpty()
        }
        fun applyMove(move: Move) {
            when (
                val reduction = GameReducer.reduce(
                    gameState,
                    MoveIntent(
                        actor = move.actor,
                        from = move.from,
                        to = move.to,
                        promotion = move.promotion,
                    ),
                )
            ) {
                is MoveReduction.Applied -> gameState = reduction.state
                is MoveReduction.Rejected -> Unit
            }
            selectedPieceId = null
            pendingPromotionMoves = emptyList()
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
                                        selectedScenario = scenario
                                        gameState = scenario.initialState
                                        selectedPieceId = null
                                        pendingPromotionMoves = emptyList()
                                        scenarioMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
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
                                selectedPieceId = tappedPiece
                                    ?.takeIf { piece ->
                                        gameState.turn?.player ==
                                                gameState.armies.getValue(piece.army).controller
                                    }
                                    ?.id
                                    ?.value
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
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
    }
}

private fun PromotionChoice.displayName(): String = when (this) {
    PromotionChoice.QUEEN -> "Ферзь"
    PromotionChoice.ROOK -> "Ладья"
    PromotionChoice.BISHOP -> "Слон"
    PromotionChoice.KNIGHT -> "Конь"
}

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
