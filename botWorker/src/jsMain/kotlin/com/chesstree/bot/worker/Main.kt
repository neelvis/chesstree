package com.chesstree.bot.worker

import com.chesstree.bot.wire.BotStateWireCodec
import com.chesstree.bot.wire.BotWorkerReply
import com.chesstree.bot.wire.BotWorkerRequest
import com.chesstree.bot.wire.BotWorkerWireCodec
import com.chesstree.bot.wire.toWire
import com.chesstree.game.domain.bot.BotStopProbe
import com.chesstree.game.domain.bot.BotEvaluationMode
import com.chesstree.game.domain.bot.BotStopSignal
import com.chesstree.game.domain.bot.BoundedBotResult
import com.chesstree.game.domain.bot.BoundedMaxNBot

private external val self: BotWorkerScope
private external val performance: BotPerformance

private external interface BotWorkerScope {
    var onmessage: ((BotWorkerEvent) -> Unit)?
    fun postMessage(message: String)
}

private external interface BotWorkerEvent {
    val data: String
}

private external interface BotPerformance {
    fun now(): Double
}

fun main() {
    self.onmessage = { event ->
        val receivedAt = performance.now()
        if (event.data == PING) {
            self.postMessage(PONG)
        } else {
            val request = BotWorkerWireCodec.decodeRequest(event.data)
            if (request != null) {
                handle(request, receivedAt)
            } else {
                self.postMessage(BAD_REQUEST)
            }
        }
    }
}

private const val PING = "CHESSTREE_BOT_WORKER_PING_V5"
private const val PONG = "CHESSTREE_BOT_WORKER_PONG_V5"
private const val BAD_REQUEST = "CHESSTREE_BOT_WORKER_BAD_REQUEST_V5"

private fun handle(request: BotWorkerRequest, receivedAt: Double) {
    val state = BotStateWireCodec.decode(request.state)
    val policy = request.toPolicy()
    if (state == null || policy == null) {
        send(request, receivedAt, kind = "FAILED", reason = "INVALID_REQUEST")
        return
    }
    val deadline = receivedAt + request.budgetMs
    val result = runCatching {
        BoundedMaxNBot(
            request.maxDepth, request.maxNodes, request.maxEvaluations,
            BotEvaluationMode.entries.single { it.version == request.evaluationVersion },
            request.openingBookVersion,
            request.seed,
            tacticalDepth = request.tacticalDepth,
            searchModel = com.chesstree.game.domain.bot.BotSearchModel.valueOf(request.searchModel),
            profile = com.chesstree.game.domain.bot.BotPlayingProfile.valueOf(request.profile),
            profileCatalogVersion = request.profileCatalogVersion,
        ).choose(
            state = state,
            policy = policy,
            stop = BotStopProbe {
                if (performance.now() >= deadline) BotStopSignal.TIMEOUT else BotStopSignal.CONTINUE
            },
            recentPositions = request.recentPositions.map { checkNotNull(BotStateWireCodec.decode(it)) },
            onFallbackReady = { move ->
                send(request, receivedAt, kind = "READY", move = move.toWire())
            },
            onCompletedDepth = { completed ->
                send(
                    request, receivedAt, kind = "PROGRESS", move = completed.intent.toWire(),
                    source = completed.source.name,
                    completedDepth = completed.stats.completedDepth,
                    completedHorizon = completed.stats.completedHorizon.name,
                    repetitionPenalty = completed.repetitionPenalty,
                    expandedNodes = completed.stats.expandedNodes,
                    leafEvaluations = completed.stats.leafEvaluations,
                    openingBookInfluenced = completed.openingBook.influenced,
                )
            },
        )
    }.getOrElse {
        send(request, receivedAt, kind = "FAILED", reason = "SEARCH_ERROR")
        return
    }
    when (result) {
        is BoundedBotResult.Move -> send(
            request,
            receivedAt,
            kind = "MOVE",
            move = result.intent.toWire(),
            source = result.source.name,
            reason = result.reason.name,
            completedDepth = result.stats.completedDepth,
            completedHorizon = result.stats.completedHorizon.name,
            repetitionPenalty = result.repetitionPenalty,
            expandedNodes = result.stats.expandedNodes,
            leafEvaluations = result.stats.leafEvaluations,
            openingBookInfluenced = result.openingBook.influenced,
        )
        is BoundedBotResult.Terminal -> send(request, receivedAt, kind = "TERMINAL")
        is BoundedBotResult.Cancelled -> send(request, receivedAt, kind = "CANCELLED")
        is BoundedBotResult.Failed -> send(request, receivedAt, kind = "FAILED", reason = result.reason.name)
    }
}

private fun send(
    request: BotWorkerRequest,
    receivedAt: Double,
    kind: String,
    move: com.chesstree.bot.wire.BotWireMove? = null,
    source: String? = null,
    reason: String? = null,
    completedDepth: Int = 0,
    completedHorizon: String = "STATIC",
    repetitionPenalty: Double = 0.0,
    expandedNodes: Int = 0,
    leafEvaluations: Int = 0,
    openingBookInfluenced: Boolean = false,
) {
    self.postMessage(
        BotWorkerWireCodec.encodeReply(
            BotWorkerReply(
                requestId = request.requestId,
                gameId = request.gameId,
                positionRevision = request.positionRevision,
                rulesVersion = request.rulesVersion,
                engineVersion = request.engineVersion,
                searchModel = request.searchModel,
                profile = request.profile,
                profileCatalogVersion = request.profileCatalogVersion,
                difficulty = request.difficulty,
                evaluationVersion = request.evaluationVersion,
                openingBookVersion = request.openingBookVersion,
                kind = kind,
                move = move,
                source = source,
                reason = reason,
                elapsedMs = (performance.now() - receivedAt).toInt().coerceAtLeast(0),
                completedDepth = completedDepth,
                completedHorizon = completedHorizon,
                repetitionPenalty = repetitionPenalty,
                expandedNodes = expandedNodes,
                leafEvaluations = leafEvaluations,
                openingBookInfluenced = openingBookInfluenced,
            ),
        ),
    )
}
