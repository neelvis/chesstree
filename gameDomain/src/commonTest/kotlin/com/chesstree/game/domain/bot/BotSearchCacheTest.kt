package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.EnPassantTarget
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotSearchCacheTest {
    @Test
    fun repeatedCompleteSearchPreservesDecisionsAndSavesActualWork() {
        for (model in BotSearchModel.entries) {
            val state = fixture()
            val cache = BotSearchCache()
            val plain = move(state, model = model)
            val cold = move(state, cache, model)
            val warm = move(state, cache, model)
            assertDecision(plain, cold)
            assertDecision(plain, warm)
            assertTrue(warm.stats.leafEvaluations < cold.stats.leafEvaluations)
            assertTrue(warm.stats.expandedNodes < cold.stats.expandedNodes)
            assertTrue(cache.stats.searchHits > 0)
            assertTrue(cache.stats.transitionHits > 0)
            assertTrue(cache.stats.entries <= cache.maxEntries)
        }
    }

    @Test
    fun evictionUnderTinyCapacityPreservesCompletedAnswers() {
        val state = fixture()
        for (model in BotSearchModel.entries) {
            val cache = BotSearchCache(maxEntries = 2)
            assertDecision(move(state, model = model), move(state, cache, model))
            assertEquals(2, cache.stats.entries)
            assertTrue(cache.stats.evictions > 0)
        }
    }

    @Test
    fun tacticalSearchAndConfigurationChangesPreserveCompletedAnswers() {
        val initial = fixture()
        fun piece(id: String, army: ArmyColor, type: PieceType, label: String) =
            Piece(PieceId(id), type, army, at(label), hasMoved = true)
        val pieces = listOf(
            piece("a-white-rook", ArmyColor.WHITE, PieceType.ROOK, "G10"),
            piece("white-king", ArmyColor.WHITE, PieceType.KING, "F12"),
            piece("red-pawn", ArmyColor.RED, PieceType.PAWN, "G12"),
            piece("red-king", ArmyColor.RED, PieceType.KING, "A2"),
            piece("black-bishop", ArmyColor.BLACK, PieceType.BISHOP, "H11"),
            piece("black-king", ArmyColor.BLACK, PieceType.KING, "H12"),
        )
        val state = GameState(Position(pieces.associateBy(Piece::id)), initial.participants,
            initial.armies, Turn(PlayerId.WHITE, 1), GamePhase.InProgress)
        val cache = BotSearchCache()
        val policies = listOf(BotPolicy.DEFAULT, BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 75))))
        for (model in BotSearchModel.entries) {
            for (mode in listOf(BotEvaluationMode.CONTROL, BotEvaluationMode.POSITIONAL)) {
                for (policy in policies) {
                    fun choose(cache: BotSearchCache?) = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                        maxDepth = 1, maxNodes = 10_000, maxEvaluations = 10_000,
                        tacticalDepth = 2, searchModel = model, evaluation = mode, cache = cache,
                    ).choose(state, policy))
                    val plain = choose(null)
                    assertDecision(plain, choose(cache))
                    assertDecision(plain, choose(cache))
                }
            }
        }
    }

    @Test
    fun reuseAcrossIterativeDepthsAvoidsRepeatedLegalTransitions() {
        val cache = BotSearchCache()
        move(fixture(), cache)
        assertTrue(cache.stats.transitionHits > 0)
    }

    @Test
    fun rootHistoryAndPlayingPreferencesAreAppliedFresh() {
        val state = fixture()
        val cache = BotSearchCache()
        move(state, cache)
        val successors = LegalMoveGenerator.legalMoves(state).map {
            checkNotNull(GameReducer.reduceGeneratedLegalMove(state, it)).state
        }
        val recent = successors + successors
        assertTrue(recent.size <= MAX_BOT_RECENT_POSITIONS)
        for (profile in listOf(BotPlayingProfile.UNIVERSAL, BotPlayingProfile.entries.last())) {
            val plain = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000,
                profile = profile, profileCatalogVersion = 2,
            ).choose(state, recentPositions = recent))
            val reused = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000,
                profile = profile, profileCatalogVersion = 2, cache = cache,
            ).choose(state, recentPositions = recent))
            assertDecision(plain, reused)
            assertTrue(reused.repetitionPenalty > 0.0)
        }
    }

    @Test
    fun partialAndInterruptedSearchesDoNotPoisonLaterResults() {
        val state = fixture()
        for (signal in listOf(BotStopSignal.CANCEL, BotStopSignal.TIMEOUT)) {
            val cache = BotSearchCache()
            var probes = 0
            val interrupted = BoundedMaxNBot(
                maxDepth = 3, maxNodes = 10_000, maxEvaluations = 10_000, cache = cache,
            ).choose(state, stop = BotStopProbe {
                probes++
                if (probes > 40) signal else BotStopSignal.CONTINUE
            })
            if (signal == BotStopSignal.CANCEL) assertIs<BoundedBotResult.Cancelled>(interrupted)
            else assertEquals(BotStopReason.TIMEOUT, assertIs<BoundedBotResult.Move>(interrupted).reason)
            assertDecision(move(state), move(state, cache))
        }
        val cache = BotSearchCache()
        val limited = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 2, maxEvaluations = 1, cache = cache,
        ).choose(state))
        assertEquals(BotStopReason.EVALUATION_LIMIT, limited.reason)
        assertDecision(move(state), move(state, cache))
    }

    @Test
    fun exactSearchKeysSeparateEverySearchContextAndConfiguration() {
        val cache = BotSearchCache()
        val state = fixture()
        val configuration = configuration()
        val key = BotSearchCache.SearchKey(state, configuration, 2, BotSearchHorizon.CHECKS, emptySet())
        cache.rememberSearch(key, doubleArrayOf(1.0, 2.0, 3.0))
        val otherConfigurations = listOf(
            configuration.copy(rules = listOf(BotRule(BotFeature.MATERIAL, 999))),
            configuration.copy(evaluation = BotEvaluationMode.POSITIONAL),
            configuration.copy(model = BotSearchModel.PARANOID),
            configuration.copy(rootActor = PlayerId.RED),
            configuration.copy(tacticalDepth = 3),
        )
        val otherContexts = listOf(
            key.copy(depth = 1),
            key.copy(horizon = BotSearchHorizon.EXCHANGES),
            key.copy(responders = setOf(PlayerId.BLACK)),
            key.copy(remaining = 0),
            key.copy(allowNewExchange = false),
        ) + otherConfigurations.map { key.copy(configuration = it) }
        otherContexts.forEach { assertNull(cache.search(it), "$it") }
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), cache.search(key))
    }

    @Test
    fun exactStateKeysIncludePlyRightsParticipantsAndControllers() {
        val initial = StandardGame.scenario.initialState
        val configuration = configuration()
        val cache = BotSearchCache()
        val key = BotSearchCache.EvaluationKey(initial, configuration)
        cache.rememberEvaluation(key, doubleArrayOf(1.0, 2.0, 3.0))
        val variants = listOf(
            GameState(initial.position, initial.participants, initial.armies, Turn(PlayerId.WHITE, 4), initial.phase),
            GameState(initial.position, initial.participants, initial.armies, Turn(PlayerId.RED, 1), initial.phase),
            GameState(Position(initial.position.pieces), initial.participants, initial.armies, initial.turn, initial.phase),
            GameState(initial.position, initial.participants + (PlayerId.RED to
                Participant(PlayerId.RED, ParticipantStatus.Stalemated(1))), initial.armies, initial.turn, initial.phase),
            GameState(initial.position, initial.participants, initial.armies + (ArmyColor.RED to
                ArmyControl(ArmyColor.RED, PlayerId.WHITE)), initial.turn, initial.phase),
        )
        variants.forEach { assertNull(cache.evaluation(key.copy(state = it))) }
        val state = fixture()
        val target = EnPassantTarget(PieceId("white-pawn"), at("K10"), setOf(PlayerId.RED))
        val withTarget = GameState(Position(state.position.pieces, enPassantTarget = target),
            state.participants, state.armies, state.turn, state.phase)
        cache.rememberEvaluation(key.copy(state = state), doubleArrayOf(4.0, 5.0, 6.0))
        assertNull(cache.evaluation(key.copy(state = withTarget)))
        // Equal states reconstructed from separate objects must still reuse the entry.
        val equal = GameState(initial.position, initial.participants, initial.armies, initial.turn, initial.phase)
        assertNotNull(cache.evaluation(key.copy(state = equal)))
    }

    @Test
    fun leafFloodingCannotEvictRootTransitionsOrDeeperCompletedSearches() {
        val cache = BotSearchCache(maxEntries = 16)
        val state = fixture()
        val first = LegalMoveGenerator.legalMoves(state).first()
        val transition = checkNotNull(GameReducer.reduceGeneratedLegalMove(state, first))
        cache.rememberTransition(state, first, transition, root = true)
        val completed = BotSearchCache.SearchKey(state, configuration(), 2, BotSearchHorizon.CHECKS, emptySet())
        val scores = doubleArrayOf(1.0, 2.0, 3.0)
        cache.rememberSearch(completed, scores)
        repeat(64) { index ->
            val config = configuration().copy(rules = listOf(BotRule(BotFeature.MATERIAL, index)))
            cache.rememberEvaluation(BotSearchCache.EvaluationKey(state, config), scores)
            cache.rememberSearch(completed.copy(configuration = config, depth = 1), scores)
            assertTrue(cache.stats.entries <= cache.maxEntries)
        }
        assertNotNull(cache.transition(state, first, root = true))
        assertContentEquals(scores, cache.search(completed))
        assertTrue(cache.stats.evictions > 0)
        cache.beginSearch(checkNotNull(state.turn).ply + 1)
        assertNull(cache.search(completed))
    }

    @Test
    fun quietStaticFrontiersReuseEvaluationWithoutDuplicateSearchEntries() {
        val state = fixture()
        val cache = BotSearchCache()
        fun choose() = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
            maxDepth = 1, tacticalDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000, cache = cache,
        ).choose(state))
        val cold = choose()
        val warm = choose()
        assertDecision(cold, warm)
        assertEquals(0L, cache.stats.searchHits)
        assertTrue(cache.stats.evaluationHits > 0)
        assertTrue(warm.stats.leafEvaluations < cold.stats.leafEvaluations)
    }

    @Test
    fun cachedScoresAreCopiedAndLeastRecentlyUsedEntriesAreEvicted() {
        val cache = BotSearchCache(maxEntries = 2)
        val key = BotSearchCache.EvaluationKey(fixture(), configuration())
        val second = key.copy(configuration = configuration().copy(rootActor = PlayerId.RED))
        val third = key.copy(configuration = configuration().copy(rootActor = PlayerId.BLACK))
        val scores = doubleArrayOf(1.0, 2.0, 3.0)
        cache.rememberEvaluation(key, scores)
        scores[0] = -100.0
        cache.rememberEvaluation(second, scores)
        val returned = assertNotNull(cache.evaluation(key))
        assertEquals(1.0, returned[0])
        returned[0] = -200.0
        cache.rememberEvaluation(third, scores)
        assertNull(cache.evaluation(second))
        assertEquals(1.0, assertNotNull(cache.evaluation(key))[0])
        assertEquals(2, cache.stats.entries)
        assertEquals(1L, cache.stats.evictions)
        val searchKey = BotSearchCache.SearchKey(fixture(), configuration(), 1, BotSearchHorizon.STATIC, emptySet())
        cache.rememberSearch(searchKey, scores)
        scores[1] = -300.0
        val searched = assertNotNull(cache.search(searchKey))
        assertEquals(2.0, searched[1])
        searched[1] = -400.0
        assertEquals(2.0, assertNotNull(cache.search(searchKey))[1])
        cache.clear()
        assertEquals(BotSearchCacheStats(0, 0, 0, 0, 0), cache.stats)
    }

    private fun configuration() = BotSearchCache.Configuration(
        BotPolicy.DEFAULT.rules.toList(), BotEvaluationMode.CONTROL, BotSearchModel.MAX_N, PlayerId.WHITE, 2,
    )

    private fun move(
        state: GameState,
        cache: BotSearchCache? = null,
        model: BotSearchModel = BotSearchModel.MAX_N,
    ): BoundedBotResult.Move = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
        maxDepth = 2, maxNodes = 10_000, maxEvaluations = 10_000, searchModel = model, cache = cache,
    ).choose(state))

    private fun assertDecision(expected: BoundedBotResult.Move, actual: BoundedBotResult.Move) {
        assertEquals(expected.intent, actual.intent)
        assertEquals(expected.source, actual.source)
        assertEquals(expected.reason, actual.reason)
        assertEquals(expected.stats.completedDepth, actual.stats.completedDepth)
        assertEquals(expected.stats.completedHorizon, actual.stats.completedHorizon)
        assertEquals(expected.openingBook, actual.openingBook)
        assertEquals(expected.repetitionPenalty, actual.repetitionPenalty)
    }

    private fun at(label: String) = checkNotNull(ThreePlayerBoardNotation.parse(label))

    private fun fixture(): GameState {
        fun piece(id: String, army: ArmyColor, type: PieceType, label: String) =
            Piece(PieceId(id), type, army, at(label), hasMoved = true)
        val pieces = listOf(
            piece("white-king", ArmyColor.WHITE, PieceType.KING, "H12"),
            piece("red-king", ArmyColor.RED, PieceType.KING, "A1"),
            piece("black-king", ArmyColor.BLACK, PieceType.KING, "N8"),
            piece("white-pawn", ArmyColor.WHITE, PieceType.PAWN, "K9"),
            piece("red-pawn", ArmyColor.RED, PieceType.PAWN, "D3"),
            piece("black-pawn", ArmyColor.BLACK, PieceType.PAWN, "K6"),
        )
        return GameState(Position(pieces.associateBy(Piece::id)),
            PlayerId.entries.associateWith(::Participant),
            ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            Turn(PlayerId.WHITE, 293), GamePhase.InProgress)
    }
}
