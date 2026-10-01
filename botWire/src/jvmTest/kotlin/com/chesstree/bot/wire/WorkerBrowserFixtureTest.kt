package com.chesstree.bot.wire

import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** A deterministic request for the browser worker packaging smoke check. */
class WorkerBrowserFixtureTest {
    @Test
    fun writesACompleteOpeningRequest() {
        val state = StandardGame.scenario.initialState
        val request = BotWorkerRequest(
            requestId = "worker-smoke-request",
            gameId = "worker-smoke-game",
            positionRevision = 0,
            player = checkNotNull(state.turn).player.name,
            state = BotStateWireCodec.encode(state),
            policy = BotPolicy.DEFAULT.toWireRules(),
            budgetMs = 1_200,
            maxDepth = 2,
            maxNodes = 128,
            maxEvaluations = 128,
        )
        val encoded = BotWorkerWireCodec.encodeRequest(request)
        assertEquals(request, BotWorkerWireCodec.decodeRequest(encoded))

        val output = File("build/reports/bot-worker/worker-smoke-request.json")
        output.parentFile.mkdirs()
        output.writeText(encoded)
    }
}
