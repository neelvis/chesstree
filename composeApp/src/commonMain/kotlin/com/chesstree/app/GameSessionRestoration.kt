package com.chesstree.app

import com.chesstree.game.data.GameSnapshot
import com.chesstree.game.data.GameSnapshotCodec
import com.chesstree.game.domain.scenario.GameScenario
import com.chesstree.game.domain.session.GameSession

internal fun encodeSessionForRestoration(session: GameSession): String =
    GameSnapshotCodec.encode(
        GameSnapshot(
            scenarioId = session.scenario.id,
            moves = session.moves,
        ),
    )

internal fun restoreSession(
    contents: String,
    scenarios: List<GameScenario>,
): GameSession? {
    val snapshot = GameSnapshotCodec.decode(contents) ?: return null
    val scenario = scenarios.firstOrNull { it.id == snapshot.scenarioId } ?: return null
    return GameSession.replay(scenario, snapshot.moves)
}

internal fun restoreSessionOrDefault(
    contents: String,
    scenarios: List<GameScenario>,
): GameSession = restoreSession(contents, scenarios) ?: GameSession(scenarios.first())
