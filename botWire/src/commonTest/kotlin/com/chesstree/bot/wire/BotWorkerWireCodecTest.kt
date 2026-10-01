package com.chesstree.bot.wire

import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BotWorkerWireCodecTest {
    private val initial = StandardGame.scenario.initialState

    @Test
    fun requestRoundTripsWithStateAndFrozenPolicy() {
        val request = BotWorkerRequest(
            requestId = "request-1",
            gameId = "game-1",
            positionRevision = 7,
            player = checkNotNull(initial.turn).player.name,
            state = BotStateWireCodec.encode(initial),
            policy = BotPolicy.DEFAULT.toWireRules(),
            budgetMs = 350,
            maxDepth = 2,
            maxNodes = 128,
            maxEvaluations = 128,
            openingBookVersion = 1,
            seed = Long.MIN_VALUE,
        )

        val decoded = BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request))
        assertEquals(request, decoded)
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(version = 3))))
        assertEquals(BotPolicy.DEFAULT, decoded?.toPolicy())
        val catalog = request.copy(profileCatalogVersion = 2)
        assertEquals(catalog, BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(catalog)))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(profileCatalogVersion = 3))))
        val paranoid = request.copy(engineVersion = 3, searchModel = "PARANOID", tacticalDepth = 4)
        assertEquals(paranoid, BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(paranoid)))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(paranoid.copy(searchModel = "MAX_N"))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(paranoid.copy(engineVersion = 2))))
        val history = request.copy(engineVersion = 2, tacticalDepth = 2,
            recentPositions = List(com.chesstree.game.domain.bot.MAX_BOT_RECENT_POSITIONS) { request.state })
        assertEquals(history, BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(history)))
        listOf(
            history.copy(recentPositions = history.recentPositions + request.state),
            history.copy(recentPositions = listOf("invalid")),
            request.copy(recentPositions = listOf(request.state)),
        ).forEach { invalid ->
            assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(invalid)))
        }
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(player = "RED"))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(version = 2))))
        assertEquals(request.copy(evaluationVersion = 3), BotWorkerWireCodec.decodeRequest(
            BotWorkerWireCodec.encodeRequest(request.copy(evaluationVersion = 3)),
        ))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(evaluationVersion = 4))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(openingBookVersion = -1))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(openingBookVersion = 2))))
        assertEquals(
            request.copy(engineVersion = 2, tacticalDepth = 1),
            BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(
                request.copy(engineVersion = 2, tacticalDepth = 1),
            )),
        )
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(
            request.copy(engineVersion = 1, tacticalDepth = 1),
        )))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(
            request.copy(engineVersion = 2, tacticalDepth = 0),
        )))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(
            request.copy(engineVersion = 2, tacticalDepth = 7),
        )))
    }

    @Test
    fun replyRequiresMoveForReadyAndMoveKinds() {
        val reply = BotWorkerReply(
            requestId = "request-1",
            gameId = "game-1",
            positionRevision = 7,
            kind = "READY",
            move = com.chesstree.game.domain.LegalMoveGenerator.legalMoves(initial).first().let {
                com.chesstree.game.domain.MoveIntent(it.actor, it.from, it.to, it.promotion).toWire()
            },
        )

        assertEquals(reply, BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply)))
        val progress = reply.copy(kind = "PROGRESS", source = "COMPLETED_DEPTH", completedDepth = 1)
        assertEquals(progress, BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(progress)))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(progress.copy(move = null))))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(move = null))))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(kind = "UNKNOWN"))))
        assertEquals(
            reply.copy(engineVersion = 2),
            BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(engineVersion = 2))),
        )
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(engineVersion = 3))))
        val catalogReply = progress.copy(profileCatalogVersion = 2)
        assertEquals(catalogReply, BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(catalogReply)))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(progress.copy(profileCatalogVersion = 3))))
        val paranoidReply = progress.copy(engineVersion = 3, searchModel = "PARANOID", completedHorizon = "CHECKS")
        assertEquals(paranoidReply, BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(paranoidReply)))
        listOf("CHECKS", "EXCHANGES").forEach { horizon ->
            val tactical = progress.copy(engineVersion = 2, completedHorizon = horizon)
            assertEquals(tactical, BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(tactical)))
        }
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(progress.copy(engineVersion = 2))))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(progress.copy(completedHorizon = "CHECKS"))))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(completedHorizon = "CHECKS"))))
        assertEquals(
            progress.copy(openingBookVersion = 1, openingBookInfluenced = true),
            BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(
                progress.copy(openingBookVersion = 1, openingBookInfluenced = true),
            )),
        )
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(
            progress.copy(openingBookInfluenced = true),
        )))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(
            reply.copy(kind = "READY", openingBookVersion = 1, openingBookInfluenced = true),
        )))
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(
            reply.copy(openingBookVersion = 2),
        )))
    }
}
