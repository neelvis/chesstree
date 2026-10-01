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
        assertEquals(BotPolicy.DEFAULT, decoded?.toPolicy())
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(player = "RED"))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(version = 2))))
        assertEquals(request.copy(evaluationVersion = 3), BotWorkerWireCodec.decodeRequest(
            BotWorkerWireCodec.encodeRequest(request.copy(evaluationVersion = 3)),
        ))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(evaluationVersion = 4))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(openingBookVersion = -1))))
        assertNull(BotWorkerWireCodec.decodeRequest(BotWorkerWireCodec.encodeRequest(request.copy(openingBookVersion = 2))))
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
        assertNull(BotWorkerWireCodec.decodeReply(BotWorkerWireCodec.encodeReply(reply.copy(engineVersion = 2))))
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
