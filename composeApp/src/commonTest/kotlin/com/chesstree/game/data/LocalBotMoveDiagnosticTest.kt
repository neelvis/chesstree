package com.chesstree.game.data

import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotMoveSource
import com.chesstree.game.domain.bot.BotOpeningDiagnostics
import com.chesstree.game.domain.bot.BotSearchHorizon
import com.chesstree.game.domain.bot.BotStopReason
import com.chesstree.game.domain.bot.BoundedBotStats
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LocalBotMoveDiagnosticTest {
    private val trace = LocalBotMoveDiagnostic(1, PlayerId.WHITE, 117, BotMoveSource.COMPLETED_DEPTH,
        BotStopReason.TIMEOUT, BoundedBotStats(123, 98, 2, BotSearchHorizon.CHECKS), BotOpeningDiagnostics(1, true), 12.0)
    private val move = LegalMoveGenerator.legalMoves(StandardGame.scenario.initialState).first().let {
        MoveIntent(it.actor, it.from, it.to, it.promotion)
    }
    private val snapshot = GameSnapshot("standard", listOf(move),
        LocalBotGameConfig.watchGame(PlayerId.entries.associateWith { it.ordinal.toLong() }),
        initialState = StandardGame.scenario.initialState, diagnostics = listOf(trace))

    @Test
    fun exportsPreserveBoundedSearchFactsAndTheMovePrefixIdentifier() {
        assertEquals(trace, LocalBotMoveDiagnostic.decode(trace.encode()))
        assertEquals(snapshot, GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot)))
        assertEquals(emptyList(), GameSnapshotCodec.decode(GameSnapshotCodec.encode(snapshot.copy(diagnostics = emptyList())))?.diagnostics)
    }

    @Test
    fun malformedOrUnmatchedDiagnosticsRejectTheEnvelope() {
        val encoded = GameSnapshotCodec.encode(snapshot)
        assertNull(GameSnapshotCodec.decode(encoded.replace("diagnostic|1|WHITE", "diagnostic|2|WHITE")))
        assertNull(GameSnapshotCodec.decode(encoded.replace("diagnostic|1|WHITE", "diagnostic|1|RED")))
        assertNull(GameSnapshotCodec.decode(encoded + trace.encode() + "\n"))
        assertNull(LocalBotMoveDiagnostic.decode(trace.encode().replace("|12.0|0", "|NaN|0")))
        assertNull(LocalBotMoveDiagnostic.decode(trace.encode().replace("|CHECKS|", "|UNKNOWN|")))
        assertNull(LocalBotMoveDiagnostic.decode(trace.encode().replace("|117|", "|-1|")))
        assertNull(LocalBotMoveDiagnostic.decode(trace.encode().dropLast(1) + "1"))
        assertFailsWith<IllegalArgumentException> { GameSnapshotCodec.encode(snapshot.copy(botGame = null)) }
    }

    @Test
    fun fallbackCannotBeRecordedAsACompletedHorizon() {
        assertFailsWith<IllegalArgumentException> { trace.copy(source = BotMoveSource.LEGAL_FALLBACK) }
        val fallback = trace.copy(source = BotMoveSource.LEGAL_FALLBACK,
            stats = BoundedBotStats(7, 0, 0), openingBook = BotOpeningDiagnostics(1), repetitionPenalty = 0.0)
        assertEquals(fallback, LocalBotMoveDiagnostic.decode(fallback.encode()))
    }
}
