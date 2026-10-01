package com.chesstree.game.presentation.bot

import com.chesstree.game.domain.bot.BotStopProbe
import com.chesstree.game.domain.bot.BotStopSignal
import com.chesstree.game.domain.bot.BoundedMaxNBot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/** Native composition roots use this shared, single-search background runner. */
object BackgroundLocalBotRunner : LocalBotRunner {
    private val ownership = Mutex()

    override suspend fun choose(request: LocalBotRequest): LocalBotReply {
        val started = TimeSource.Monotonic.markNow()
        return ownership.withLock {
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                try {
                    val result = BoundedMaxNBot(
                        request.maxDepth, request.maxNodes, request.maxEvaluations,
                        request.evaluation,
                        request.openingBookVersion,
                        request.seed,
                        tacticalDepth = request.tacticalDepth,
                        searchModel = request.searchModel,
                        profile = request.profile,
                        profileCatalogVersion = request.profileCatalogVersion,
                    ).choose(request.state, request.policy, BotStopProbe {
                        when {
                            !context.isActive -> BotStopSignal.CANCEL
                            started.elapsedNow().inWholeMilliseconds >= request.searchBudgetMs ->
                                BotStopSignal.TIMEOUT
                            else -> BotStopSignal.CONTINUE
                        }
                    }, recentPositions = request.recentPositions)
                    LocalBotReply.Completed(request.identity, result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    LocalBotReply.Failed(request.identity)
                }
            }
        }
    }
}
