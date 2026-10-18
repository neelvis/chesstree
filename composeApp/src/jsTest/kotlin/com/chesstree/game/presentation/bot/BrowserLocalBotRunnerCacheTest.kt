@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.chesstree.game.presentation.bot

import com.chesstree.bot.wire.BotWorkerReply
import com.chesstree.bot.wire.BotWorkerWireCodec
import com.chesstree.bot.wire.toWire
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.bot.BotMoveSource
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotSearchModel
import com.chesstree.game.domain.bot.BotStopReason
import com.chesstree.game.domain.bot.BoundedBotResult
import com.chesstree.game.domain.scenario.StandardGame
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.js.unsafeCast
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BrowserLocalBotRunnerCacheTest {
    private val state = StandardGame.scenario.initialState
    private val legalMove = LegalMoveGenerator.legalMoves(state).first().let {
        MoveIntent(it.actor, it.from, it.to, it.promotion)
    }

    @Test
    fun completedSearchReusesWorkerAndNewGameRetiresItWhileStaleRepliesAreIgnored() = runTest {
        withWorkerStub { workers ->
            val runner = BrowserLocalBotRunner()
            val first = request("first")
            val firstSearch = async { runner.choose(first) }
            runCurrent()
            val worker = workers.latest()
            assertEquals(first.identity.requestId, worker.lastRequest().requestId)
            workers.emit(worker, completed(first))
            runCurrent()
            assertEquals(legalMove, firstSearch.await().move().intent)
            assertHandlersCleared(worker)
            assertEquals(0, worker.terminations)

            val second = request("second", revision = 1)
            val secondSearch = async { runner.choose(second) }
            runCurrent()
            assertEquals(1, workers.createdCount)
            assertSame(worker, workers.latest())
            assertEquals(second.identity.requestId, worker.lastRequest().requestId)
            workers.emit(worker, completed(first))
            runCurrent()
            assertFalse(secondSearch.isCompleted)
            workers.emit(worker, completed(second))
            runCurrent()
            assertEquals(second.identity, secondSearch.await().identity)
            assertHandlersCleared(worker)

            val newGame = request("new-game", gameId = "other-game")
            val thirdSearch = async { runner.choose(newGame) }
            runCurrent()
            assertEquals(1, worker.terminations)
            assertEquals(2, workers.createdCount)
            val replacement = workers.latest()
            workers.emit(replacement, completed(newGame))
            runCurrent()
            assertEquals(legalMove, thirdSearch.await().move().intent)
        }
    }

    @Test
    fun cancellationTimeoutsAndInvalidRepliesNeverRetainTheWorker() = runTest {
        withWorkerStub { workers ->
            for (failure in Failure.entries) {
                val runner = BrowserLocalBotRunner()
                val first = request("prime-${failure.name}")
                val prime = async { runner.choose(first) }
                runCurrent()
                val worker = workers.latest()
                workers.emit(worker, completed(first))
                runCurrent()
                prime.await().move()
                val initialCount = workers.createdCount

                val failing = request("fail-${failure.name}", revision = 1)
                val search = async { runner.choose(failing) }
                runCurrent()
                assertEquals(initialCount, workers.createdCount, failure.name)
                when (failure) {
                    Failure.CANCELLATION -> search.cancelAndJoin()
                    Failure.WATCHDOG -> Unit
                    Failure.READY_ONLY -> workers.emit(worker, ready(failing))
                    Failure.PROGRESS_ONLY -> workers.emit(
                        worker, completed(failing).copy(kind = "PROGRESS", reason = null),
                    )
                    Failure.FALLBACK_FINAL -> workers.emit(worker, ready(failing).copy(
                        kind = "MOVE", source = BotMoveSource.LEGAL_FALLBACK.name,
                        reason = BotStopReason.TIMEOUT.name,
                    ))
                    Failure.MALFORMED -> workers.emitRaw(worker, "{malformed")
                    Failure.ERROR -> assertNotNull(worker.onerror).invoke(workerEvent("error"))
                    Failure.MESSAGE_ERROR -> assertNotNull(worker.onmessageerror).invoke(workerEvent("error"))
                }
                if (failure in setOf(Failure.WATCHDOG, Failure.READY_ONLY, Failure.PROGRESS_ONLY)) {
                    advanceTimeBy(failing.budgetMs.toLong())
                }
                runCurrent()
                if (failure == Failure.CANCELLATION) {
                    assertTrue(search.isCancelled)
                } else {
                    val reply = search.await()
                    when (failure) {
                        Failure.READY_ONLY, Failure.FALLBACK_FINAL -> {
                            assertEquals(BotMoveSource.LEGAL_FALLBACK, reply.move().source)
                            assertEquals(BotStopReason.TIMEOUT, reply.move().reason)
                        }
                        Failure.PROGRESS_ONLY -> {
                            assertEquals(BotMoveSource.COMPLETED_DEPTH, reply.move().source)
                            assertEquals(BotStopReason.TIMEOUT, reply.move().reason)
                        }
                        else -> assertIs<LocalBotReply.Failed>(reply)
                    }
                }
                assertEquals(1, worker.terminations, failure.name)
                assertHandlersCleared(worker)

                val retry = request("retry-${failure.name}", revision = 2)
                val retrySearch = async { runner.choose(retry) }
                runCurrent()
                assertEquals(initialCount + 1, workers.createdCount, failure.name)
                workers.emit(workers.latest(), completed(retry))
                runCurrent()
                assertEquals(legalMove, retrySearch.await().move().intent)
            }
        }
    }

    @Test
    fun fullMaterialRepetitionPenaltyIsAcceptedButHigherProgressAndFinalPenaltiesAreRejected() = runTest {
        withWorkerStub { workers ->
            for (invalidKind in listOf("PROGRESS", "MOVE")) {
                val runner = BrowserLocalBotRunner()
                val first = request("penalty-$invalidKind").copy(
                    searchModel = BotSearchModel.PARANOID,
                    recentPositions = listOf(state, state),
                )
                val search = async { runner.choose(first) }
                runCurrent()
                val worker = workers.latest()
                val count = workers.createdCount
                workers.emit(worker, completed(first, repetitionPenalty = 100.0).copy(
                    kind = "PROGRESS", reason = null,
                ))
                runCurrent()
                assertFalse(search.isCompleted)
                workers.emit(worker, completed(first, repetitionPenalty = 100.0))
                runCurrent()
                assertEquals(100.0, search.await().move().repetitionPenalty)
                assertEquals(0, worker.terminations)

                val invalid = first.copy(identity = first.identity.copy(
                    requestId = "invalid-$invalidKind", positionRevision = 1,
                ))
                val rejected = async { runner.choose(invalid) }
                runCurrent()
                assertEquals(count, workers.createdCount)
                workers.emit(worker, completed(invalid, repetitionPenalty = 100.01).copy(
                    kind = invalidKind,
                    reason = if (invalidKind == "PROGRESS") null else BotStopReason.DEPTH_COMPLETE.name,
                ))
                runCurrent()
                assertIs<LocalBotReply.Failed>(rejected.await())
                assertEquals(1, worker.terminations)
                assertHandlersCleared(worker)
            }
        }
    }

    private fun request(id: String, gameId: String = "game", revision: Long = 0) = LocalBotRequest(
        identity = LocalBotIdentity(id, gameId, revision),
        state = state,
        policy = BotPolicy.DEFAULT,
        budgetMs = 1_000,
        softBudgetMs = 500,
        maxDepth = 1,
        tacticalDepth = 0,
    )

    private fun completed(request: LocalBotRequest, repetitionPenalty: Double = 0.0) = BotWorkerReply(
        requestId = request.identity.requestId,
        gameId = request.identity.gameId,
        positionRevision = request.identity.positionRevision,
        engineVersion = request.engineVersion,
        searchModel = request.searchModel.name,
        profile = request.profile.name,
        profileCatalogVersion = request.profileCatalogVersion,
        difficulty = request.difficulty?.name,
        evaluationVersion = request.evaluation.version,
        openingBookVersion = request.openingBookVersion,
        kind = "MOVE",
        move = legalMove.toWire(),
        source = BotMoveSource.COMPLETED_DEPTH.name,
        reason = BotStopReason.DEPTH_COMPLETE.name,
        completedDepth = 1,
        expandedNodes = 1,
        leafEvaluations = 1,
        repetitionPenalty = repetitionPenalty,
    )

    private fun ready(request: LocalBotRequest) = completed(request).copy(
        kind = "READY", source = null, reason = null,
        completedDepth = 0, expandedNodes = 0, leafEvaluations = 0,
    )

    private fun LocalBotReply.move(): BoundedBotResult.Move =
        assertIs<BoundedBotResult.Move>(assertIs<LocalBotReply.Completed>(this).result)

    private fun assertHandlersCleared(worker: FakeSearchWorker) {
        assertNull(worker.onmessage)
        assertNull(worker.onerror)
        assertNull(worker.onmessageerror)
    }

    private enum class Failure {
        CANCELLATION, WATCHDOG, READY_ONLY, PROGRESS_ONLY, FALLBACK_FINAL, MALFORMED, ERROR, MESSAGE_ERROR,
    }
}

private suspend fun withWorkerStub(block: suspend (FakeWorkers) -> Unit) {
    val workers = FakeWorkers()
    try {
        block(workers)
    } finally {
        workers.close()
    }
}

private class FakeWorkers {
    private val originalConstructor: dynamic = js("globalThis.Worker")
    private val instances: dynamic = js("[]")

    init {
        replaceWorkerConstructor(fakeWorkerConstructor(instances))
    }

    val createdCount: Int get() = instances.length as Int

    fun latest(): FakeSearchWorker = instances[createdCount - 1].unsafeCast<FakeSearchWorker>()

    fun emit(worker: FakeSearchWorker, reply: BotWorkerReply) {
        emitRaw(worker, BotWorkerWireCodec.encodeReply(reply))
    }

    fun emitRaw(worker: FakeSearchWorker, data: String) {
        assertNotNull(worker.onmessage).invoke(workerEvent(data))
    }

    fun close() {
        replaceWorkerConstructor(originalConstructor)
    }
}

private external interface FakeSearchWorker {
    var onmessage: ((FakeSearchEvent) -> Unit)?
    var onerror: ((FakeSearchEvent) -> Unit)?
    var onmessageerror: ((FakeSearchEvent) -> Unit)?
    val messages: Array<String>
    val terminations: Int
}

private external interface FakeSearchEvent

private fun FakeSearchWorker.lastRequest() = assertNotNull(
    BotWorkerWireCodec.decodeRequest(messages.last()),
)

@Suppress("UNUSED_PARAMETER")
private fun workerEvent(data: String): FakeSearchEvent = js("({ data: data })")

@Suppress("UNUSED_PARAMETER")
private fun replaceWorkerConstructor(constructor: dynamic) {
    js("globalThis.Worker = constructor")
}

@Suppress("UNUSED_PARAMETER")
private fun fakeWorkerConstructor(instances: dynamic): dynamic = js("""
    (function () {
        function FakeWorker(url) {
            this.url = url;
            this.messages = [];
            this.terminations = 0;
            this.onmessage = null;
            this.onerror = null;
            this.onmessageerror = null;
            instances.push(this);
        }
        FakeWorker.prototype.postMessage = function (message) {
            this.messages.push(message);
        };
        FakeWorker.prototype.terminate = function () {
            this.terminations++;
        };
        return FakeWorker;
    })()
""")
