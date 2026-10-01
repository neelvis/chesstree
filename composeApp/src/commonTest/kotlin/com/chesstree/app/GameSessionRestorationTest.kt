package com.chesstree.app

import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.BotSeatConfig
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.data.LocalBotGameConfig
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.session.GameSession
import com.chesstree.game.domain.session.SessionMoveResult
import com.chesstree.game.presentation.scenario.ManualGameScenarios
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameSessionRestorationTest {
    @Test
    fun savedSessionRestoresScenarioAndAppliedMovesAfterRecreation() {
        val initial = GameSession(ManualGameScenarios.capturePractice)
        val move = LegalMoveGenerator.legalMoves(initial.state).first()
        val moved = assertIs<SessionMoveResult.Applied>(
            initial.apply(MoveIntent(move.actor, move.from, move.to, move.promotion)),
        ).session

        val restored = assertNotNull(
            restoreSession(
                encodeSessionForRestoration(moved),
                ManualGameScenarios.all,
            ),
        )

        assertEquals(moved.scenario.id, restored.scenario.id)
        assertEquals(moved.moves, restored.moves)
        assertEquals(moved.state, restored.state)
        assertEquals(moved.capturedPieces, restored.capturedPieces)
        assertEquals(
            initial.scenario.initialState,
            assertNotNull(GameSnapshotCodec.decode(encodeSessionForRestoration(moved))).initialState,
        )
    }

    @Test
    fun invalidOrUnknownSavedSessionIsIgnored() {
        assertNull(restoreSession("invalid", ManualGameScenarios.all))
        assertNull(
            restoreSession(
                "CHESSTREE|1\nscenario|unknown-scenario\n",
                ManualGameScenarios.all,
            ),
        )
        assertEquals(
            ManualGameScenarios.standard.id,
            restoreSessionOrDefault("invalid", ManualGameScenarios.all).scenario.id,
        )
    }

    @Test
    fun startingStateMustMatchTheKnownScenario() {
        val snapshot = GameSnapshot(
            scenarioId = ManualGameScenarios.standard.id,
            moves = emptyList(),
            initialState = ManualGameScenarios.sparseMovement.initialState,
        )
        assertNull(restoreSnapshot(snapshot, ManualGameScenarios.all))
        assertNull(restoreSession(GameSnapshotCodec.encode(snapshot), ManualGameScenarios.all))
    }

    @Test
    fun matchingPiecesDoNotHideDifferentStartingRights() {
        val initial = ManualGameScenarios.standard.initialState
        val altered = GameState(
            position = Position(initial.position.pieces),
            participants = initial.participants,
            armies = initial.armies,
            turn = initial.turn,
            phase = initial.phase,
        )
        assertEquals(initial.position.pieces, altered.position.pieces)
        assertNull(restoreSnapshot(GameSnapshot("standard", emptyList(), initialState = altered), ManualGameScenarios.all))
    }

    @Test
    fun unknownScenariosAndIllegalMovesAreRejectedByRestoration() {
        val scenario = ManualGameScenarios.standard
        assertNull(
            restoreSnapshot(
                GameSnapshot("unknown-scenario", emptyList(), initialState = scenario.initialState),
                ManualGameScenarios.all,
            ),
        )
        val move = LegalMoveGenerator.legalMoves(scenario.initialState).first()
        val illegal = MoveIntent(PlayerId.RED, move.from, move.to)
        assertNull(
            restoreSnapshot(
                GameSnapshot(scenario.id, listOf(illegal), initialState = scenario.initialState),
                ManualGameScenarios.all,
            ),
        )
    }

    @Test
    fun legacyMoveOnlySaveStillReplaysThroughTheKnownScenario() {
        val scenario = ManualGameScenarios.capturePractice
        val move = LegalMoveGenerator.legalMoves(scenario.initialState).first()
        val intent = MoveIntent(move.actor, move.from, move.to, move.promotion)
        val expected = assertIs<SessionMoveResult.Applied>(GameSession(scenario).apply(intent)).session
        val legacy = buildString {
            appendLine("CHESSTREE|1")
            appendLine("scenario|${scenario.id}")
            appendLine("move|${move.actor.name}|${move.from.vertex}|${move.from.column}|${move.from.row}|${move.to.vertex}|${move.to.column}|${move.to.row}|${move.promotion?.name ?: "-"}")
        }
        val restored = assertNotNull(restoreSession(legacy, ManualGameScenarios.all))
        assertEquals(expected.state, restored.state)
        assertEquals(expected.moves, restored.moves)
        assertEquals(expected.capturedPieces, restored.capturedPieces)
    }

    @Test
    fun failedOrCorruptStartupDoesNotOverwriteTheStoredGameAutomatically() {
        assertFalse(canPersistInitialGame(LoadGameResult.Failed("unreadable"), null, 0))
        assertFalse(canPersistInitialGame(LoadGameResult.Loaded("corrupt"), null, 0))
        assertTrue(canPersistInitialGame(LoadGameResult.Missing, null, 0))
        assertTrue(canPersistInitialGame(LoadGameResult.Loaded("valid"), GameSnapshot("standard", emptyList()), 0))
        assertTrue(canPersistInitialGame(LoadGameResult.Failed("unreadable"), null, 1))
    }

    @Test
    fun humanBotConfigurationRoundTripsAlongsideValidatedReplay() {
        val scenario = ManualGameScenarios.standard
        val move = LegalMoveGenerator.legalMoves(scenario.initialState).first()
        val moved = assertIs<SessionMoveResult.Applied>(
            GameSession(scenario).apply(MoveIntent(move.actor, move.from, move.to, move.promotion)),
        ).session
        val config = LocalBotGameConfig(
            humanSeat = PlayerId.RED,
            seats = listOf(
                BotSeatConfig(PlayerId.WHITE, BotPlayingProfile.ATTACKING, BotDifficulty.BEGINNER, 12L),
                BotSeatConfig(PlayerId.BLACK, BotPlayingProfile.POSITIONAL, BotDifficulty.STRONG, 34L),
            ),
        )
        val snapshot = GameSnapshot(scenario.id, moved.moves, config, botsRunning = true, initialState = scenario.initialState)
        val encoded = GameSnapshotCodec.encode(snapshot)
        val decoded = assertNotNull(GameSnapshotCodec.decode(encoded))
        assertEquals(config, decoded.botGame)
        assertEquals(snapshot, decoded)
        val restored = assertNotNull(restoreSnapshot(decoded, ManualGameScenarios.all))
        assertEquals(moved.state, restored.state)
        assertEquals(moved.moves, restored.moves)
        assertEquals(moved.state, assertNotNull(restoreSession(encoded, ManualGameScenarios.all)).state)
    }
}
