@file:OptIn(ExperimentalWasmJsInterop::class)

package com.chesstree.game.presentation.bot

import com.chesstree.bot.wire.BotStateWireCodec
import com.chesstree.bot.wire.BotWorkerReply
import com.chesstree.bot.wire.BotWorkerRequest
import com.chesstree.bot.wire.BotWorkerWireCodec
import com.chesstree.bot.wire.toWireRules
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.bot.BotFailureReason
import com.chesstree.game.domain.bot.BotMoveSource
import com.chesstree.game.domain.bot.BotStopReason
import com.chesstree.game.domain.bot.BoundedBotResult
import com.chesstree.game.domain.bot.BoundedBotStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** Each request owns a worker; the page only decodes results and never searches the position. */
class BrowserLocalBotRunner(
    private val workerUrl: String = "botWorker.js",
) : LocalBotRunner {
    private val mutex = Mutex()

    override suspend fun choose(request: LocalBotRequest): LocalBotReply = mutex.withLock {
        currentCoroutineContext().ensureActive()
        val phase = request.state.phase
        if (phase is GamePhase.Finished) {
            return@withLock LocalBotReply.Completed(request.identity, BoundedBotResult.Terminal(phase))
        }
        val actor = request.state.turn?.player ?: return@withLock LocalBotReply.Failed(request.identity)
        val dispatchedAt = TimeSource.Monotonic.markNow()
        val completion = CompletableDeferred<LocalBotReply>()
        var fallback: MoveIntent? = null
        var lastCompleted: BoundedBotResult.Move? = null
        var worker: SearchWorker? = null
        var active = true

        fun fallbackOrFailure(reason: BotStopReason): LocalBotReply {
            lastCompleted?.let {
                return LocalBotReply.Completed(request.identity, it.copy(reason = reason))
            }
            val move = fallback ?: return LocalBotReply.Failed(request.identity)
            return LocalBotReply.Completed(
                request.identity,
                BoundedBotResult.Move(
                    move,
                    BotMoveSource.LEGAL_FALLBACK,
                    reason,
                    BoundedBotStats(0, 0, 0),
                    com.chesstree.game.domain.bot.BotOpeningDiagnostics(request.openingBookVersion, false),
                ),
            )
        }

        fun fail() {
            if (active && !completion.isCompleted) {
                completion.complete(fallbackOrFailure(BotStopReason.EXECUTION_FAILURE))
            }
        }

        try {
            val result = withTimeoutOrNull(request.budgetMs.toLong()) {
                val payload = BotWorkerWireCodec.encodeRequest(
                    BotWorkerRequest(
                        requestId = request.identity.requestId,
                        gameId = request.identity.gameId,
                        positionRevision = request.identity.positionRevision,
                        evaluationVersion = request.evaluation.version,
                        openingBookVersion = request.openingBookVersion,
                        seed = request.seed,
                        player = actor.name,
                        state = BotStateWireCodec.encode(request.state),
                        policy = request.policy.toWireRules(),
                        budgetMs = request.searchBudgetMs,
                        maxDepth = request.maxDepth,
                        maxNodes = request.maxNodes,
                        maxEvaluations = request.maxEvaluations,
                    ),
                )
                currentCoroutineContext().ensureActive()
                if (dispatchedAt.elapsedNow().inWholeMilliseconds >= request.budgetMs) {
                    return@withTimeoutOrNull fallbackOrFailure(BotStopReason.TIMEOUT)
                }
                val searchWorker = createSearchWorker(workerUrl)
                worker = searchWorker
                searchWorker.onmessage = { event ->
                    if (active && !completion.isCompleted) {
                        if (dispatchedAt.elapsedNow().inWholeMilliseconds >= request.budgetMs) {
                            completion.complete(fallbackOrFailure(BotStopReason.TIMEOUT))
                        } else {
                            val reply = messageText(event)?.let(BotWorkerWireCodec::decodeReply)
                            when {
                                reply == null -> fail()
                                !reply.matches(request.identity) -> Unit
                                reply.evaluationVersion != request.evaluation.version -> fail()
                                reply.openingBookVersion != request.openingBookVersion -> fail()
                                reply.kind == "READY" -> {
                                    val move = reply.move?.toIntent()
                                    if (move != null && move.actor == actor &&
                                        reply.source == null && reply.reason == null &&
                                        reply.completedDepth == 0 && reply.expandedNodes == 0 &&
                                        reply.leafEvaluations == 0 && fallback == null
                                    ) {
                                        fallback = move
                                    } else {
                                        fail()
                                    }
                                }
                                reply.kind == "PROGRESS" -> {
                                    val decoded = reply.toResult(request) as? BoundedBotResult.Move
                                    val previous = lastCompleted
                                    if (decoded == null ||
                                        (previous != null &&
                                            (decoded.stats.completedDepth <= previous.stats.completedDepth ||
                                                decoded.stats.expandedNodes < previous.stats.expandedNodes ||
                                                decoded.stats.leafEvaluations < previous.stats.leafEvaluations))
                                    ) {
                                        fail()
                                    } else {
                                        lastCompleted = decoded
                                    }
                                }
                                else -> {
                                    val decoded = reply.toResult(request)
                                    val previous = lastCompleted
                                    if (decoded == null || decoded is BoundedBotResult.Failed) {
                                        fail()
                                    } else if (decoded is BoundedBotResult.Move && previous != null &&
                                        (decoded.stats.completedDepth < previous.stats.completedDepth ||
                                            decoded.stats.expandedNodes < previous.stats.expandedNodes ||
                                            decoded.stats.leafEvaluations < previous.stats.leafEvaluations)
                                    ) {
                                        fail()
                                    } else {
                                        completion.complete(LocalBotReply.Completed(request.identity, decoded))
                                    }
                                }
                            }
                        }
                    }
                }
                searchWorker.onerror = { fail() }
                searchWorker.onmessageerror = { fail() }
                searchWorker.postMessage(payload)
                completion.await()
            } ?: fallbackOrFailure(BotStopReason.TIMEOUT)
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            fallbackOrFailure(BotStopReason.EXECUTION_FAILURE)
        } finally {
            active = false
            worker?.let {
                it.onmessage = null
                it.onerror = null
                it.onmessageerror = null
                it.terminate()
            }
            completion.cancel()
        }
    }
}

private fun BotWorkerReply.matches(identity: LocalBotIdentity): Boolean =
    requestId == identity.requestId && gameId == identity.gameId &&
        positionRevision == identity.positionRevision

private fun BotWorkerReply.toResult(request: LocalBotRequest): BoundedBotResult? {
    if (completedDepth > request.maxDepth || expandedNodes > request.maxNodes ||
        leafEvaluations > request.maxEvaluations
    ) return null
    val stats = BoundedBotStats(expandedNodes, leafEvaluations, completedDepth)
    return when (kind) {
        "PROGRESS" -> {
            val intent = move?.toIntent() ?: return null
            if (intent.actor != request.state.turn?.player ||
                source != BotMoveSource.COMPLETED_DEPTH.name || reason != null ||
                completedDepth == 0 || (openingBookInfluenced && openingBookVersion != 1)
            ) return null
            BoundedBotResult.Move(
                intent,
                BotMoveSource.COMPLETED_DEPTH,
                BotStopReason.DEPTH_COMPLETE,
                stats,
                com.chesstree.game.domain.bot.BotOpeningDiagnostics(openingBookVersion, openingBookInfluenced),
            )
        }
        "MOVE" -> {
            val intent = move?.toIntent() ?: return null
            if (intent.actor != request.state.turn?.player) return null
            val parsedSource = BotMoveSource.entries.firstOrNull { it.name == source } ?: return null
            val parsedReason = BotStopReason.entries.firstOrNull { it.name == reason } ?: return null
            if (parsedReason == BotStopReason.EXECUTION_FAILURE) return null
            if ((parsedSource == BotMoveSource.COMPLETED_DEPTH && completedDepth == 0) ||
                (parsedSource == BotMoveSource.LEGAL_FALLBACK && completedDepth != 0) ||
                (parsedReason == BotStopReason.DEPTH_COMPLETE && completedDepth != request.maxDepth) ||
                (openingBookInfluenced && (parsedSource != BotMoveSource.COMPLETED_DEPTH || completedDepth == 0))
            ) return null
            BoundedBotResult.Move(
                intent, parsedSource, parsedReason, stats,
                com.chesstree.game.domain.bot.BotOpeningDiagnostics(openingBookVersion, openingBookInfluenced),
            )
        }
        "TERMINAL" -> {
            if (source != null || reason != null || !stats.isEmpty()) return null
            val phase = request.state.phase as? GamePhase.Finished ?: return null
            BoundedBotResult.Terminal(phase)
        }
        "CANCELLED" -> {
            if (source != null || reason != null) return null
            BoundedBotResult.Cancelled(stats)
        }
        "FAILED" -> {
            if (source != null || !stats.isEmpty()) return null
            val parsedReason = BotFailureReason.entries.firstOrNull { it.name == reason }
            if (parsedReason != null) {
                BoundedBotResult.Failed(parsedReason)
            } else {
                null
            }
        }
        else -> null
    }
}

private fun BoundedBotStats.isEmpty(): Boolean =
    completedDepth == 0 && expandedNodes == 0 && leafEvaluations == 0

private external interface SearchWorker : JsAny {
    var onmessage: ((SearchWorkerEvent) -> Unit)?
    var onerror: ((SearchWorkerEvent) -> Unit)?
    var onmessageerror: ((SearchWorkerEvent) -> Unit)?
    fun postMessage(message: String)
    fun terminate()
}

private external interface SearchWorkerEvent : JsAny

@Suppress("UNUSED_PARAMETER")
private fun createSearchWorker(url: String): SearchWorker = js("new Worker(url)")

@Suppress("UNUSED_PARAMETER")
private fun messageText(event: SearchWorkerEvent): String? =
    js("typeof event.data === 'string' ? event.data : null")
