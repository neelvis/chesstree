package com.chesstree.app

import com.chesstree.game.data.LoadGameResult
import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.domain.scenario.GameScenario
import com.chesstree.game.domain.session.GameSession

internal fun encodeSessionForRestoration(session: GameSession): String =
    GameSnapshotCodec.encode(
        GameSnapshot(
            scenarioId = session.scenario.id,
            moves = session.moves,
            initialState = session.scenario.initialState,
        ),
    )

internal fun encodeSessionForRestorationOrNull(session: GameSession): String? =
    GameSnapshotCodec.encodeOrNull(GameSnapshot(
        scenarioId = session.scenario.id,
        moves = session.moves,
        initialState = session.scenario.initialState,
    ))

internal fun restoreSession(
    contents: String,
    scenarios: List<GameScenario>,
): GameSession? {
    val snapshot = GameSnapshotCodec.decode(contents) ?: return null
    return restoreSnapshot(snapshot, scenarios)
}

internal fun restoreSnapshot(
    snapshot: GameSnapshot,
    scenarios: List<GameScenario>,
): GameSession? {
    val scenario = scenarios.firstOrNull { it.id == snapshot.scenarioId } ?: return null
    if (snapshot.initialState != null && snapshot.initialState != scenario.initialState) return null
    return GameSession.replay(scenario, snapshot.moves)
}

internal fun restoreSessionOrDefault(
    contents: String,
    scenarios: List<GameScenario>,
): GameSession = restoreSession(contents, scenarios) ?: GameSession(scenarios.first())

/** Preserve an unreadable save until a valid restore or an explicit game action. */
internal fun canPersistInitialGame(
    load: LoadGameResult,
    restoredSnapshot: GameSnapshot?,
    positionRevision: Long,
): Boolean = load == LoadGameResult.Missing || restoredSnapshot != null || positionRevision > 0
