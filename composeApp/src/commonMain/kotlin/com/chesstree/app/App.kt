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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.chesstree.game.presentation.board.ThreePlayerChessBoard
import com.chesstree.game.presentation.board.movementHintsFor
import com.chesstree.game.presentation.board.toBoardPieces
import com.chesstree.game.presentation.scenario.ManualGameScenarios

@Composable
fun App() {
    MaterialTheme {
        val scenarios = remember { ManualGameScenarios.all }
        var selectedScenario by remember { mutableStateOf(scenarios.first()) }
        var gameState by remember { mutableStateOf(selectedScenario.initialState) }
        var selectedPieceId by remember { mutableStateOf<String?>(null) }
        var scenarioMenuExpanded by remember { mutableStateOf(false) }
        val pieces = remember(gameState) { gameState.toBoardPieces() }
        val movementHints = remember(selectedPieceId, pieces) {
            pieces.firstOrNull { piece -> piece.id == selectedPieceId }
                ?.let(::movementHintsFor)
                .orEmpty()
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
                        onPieceSelected = { selectedPieceId = it },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun com.chesstree.game.domain.GameState.statusText(): String =
    turn?.let { "Ход: ${it.player.name.lowercase()}, полуход ${it.ply}" }
        ?: "Партия завершена"
