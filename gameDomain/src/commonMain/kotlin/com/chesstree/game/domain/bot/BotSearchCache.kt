package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.GeneratedLegalMoveTransition
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.PlayerId

/** Local diagnostics; these counters are deliberately outside the game/export protocol. */
data class BotSearchCacheStats(
    val searchHits: Long,
    val evaluationHits: Long,
    val transitionHits: Long,
    val entries: Int,
    val evictions: Long,
)

/**
 * Bounded reuse owned by one serial bot runner. Do not access concurrently.
 * Keys retain complete immutable states, including ply and special-move rights.
 * No move decision, history preference, or incomplete search is stored here.
 */
class BotSearchCache(val maxEntries: Int = 2_048) {
    init {
        require(maxEntries > 0)
    }

    // Keep shallow work alive while a large tactical frontier streams past it.
    private val rootCapacity = if (maxEntries >= 8) maxEntries / 8 else 0
    private val completedCapacity = if (maxEntries >= 8) maxEntries / 4 else 0
    private val transientCapacity = maxEntries - rootCapacity - completedCapacity
    private val entries = linkedMapOf<Any, Any>()
    private val roots = linkedMapOf<Any, Any>()
    private val completed = linkedMapOf<Int, LinkedHashMap<SearchKey, DoubleArray>>()
    private var completedSize = 0
    private var searchHits = 0L
    private var evaluationHits = 0L
    private var transitionHits = 0L
    private var evictions = 0L

    val stats: BotSearchCacheStats
        get() = BotSearchCacheStats(searchHits, evaluationHits, transitionHits, entries.size + roots.size + completedSize, evictions)

    fun clear() {
        entries.clear()
        roots.clear()
        completed.clear()
        completedSize = 0
        searchHits = 0
        evaluationHits = 0
        transitionHits = 0
        evictions = 0
    }

    internal fun beginSearch(rootPly: Int) {
        // Older plies cannot be reached again; do not let their deeper entries
        // permanently prevent admission of the next turn's shallow searches.
        val depths = completed.keys.toList()
        for (depth in depths) {
            val bucket = completed.getValue(depth)
            val obsolete = bucket.keys.filter { (it.state.turn?.ply ?: Int.MAX_VALUE) < rootPly }
            obsolete.forEach { bucket.remove(it) }
            completedSize -= obsolete.size
            if (bucket.isEmpty()) completed.remove(depth)
        }
    }

    internal fun search(key: SearchKey): DoubleArray? {
        val scores = if (key.remaining == null && key.depth > 0 && completedCapacity > 0) {
            completed[key.depth]?.let { bucket ->
                bucket.remove(key)?.also { bucket[key] = it }
            }
        } else get(entries, key) as? DoubleArray
        if (scores != null) searchHits++
        return scores?.copyOf()
    }

    internal fun rememberSearch(key: SearchKey, scores: DoubleArray) {
        if (key.remaining != null || key.depth <= 0 || completedCapacity == 0) {
            put(entries, transientCapacity, key, scores.copyOf())
            return
        }
        val existing = completed[key.depth]?.remove(key)
        if (existing != null) completedSize--
        if (completedSize >= completedCapacity) {
            val shallowest = checkNotNull(completed.keys.minOrNull())
            // A stream of cheap depth-one nodes must not replace deeper exact work.
            if (key.depth < shallowest) return
            val bucket = completed.getValue(shallowest)
            bucket.remove(bucket.keys.first())
            if (bucket.isEmpty()) completed.remove(shallowest)
            completedSize--
            evictions++
        }
        completed.getOrPut(key.depth) { linkedMapOf() }[key] = scores.copyOf()
        completedSize++
    }

    internal fun evaluation(key: EvaluationKey): DoubleArray? = (get(entries, key) as? DoubleArray)?.also {
        evaluationHits++
    }?.copyOf()

    internal fun rememberEvaluation(key: EvaluationKey, scores: DoubleArray) =
        put(entries, transientCapacity, key, scores.copyOf())

    internal fun transition(state: GameState, move: Move, root: Boolean = false): GeneratedLegalMoveTransition? {
        val table = if (root && rootCapacity > 0) roots else entries
        return (get(table, TransitionKey(state, move)) as? GeneratedLegalMoveTransition)?.also { transitionHits++ }
    }

    internal fun rememberTransition(
        state: GameState,
        move: Move,
        transition: GeneratedLegalMoveTransition,
        root: Boolean = false,
    ) {
        val table = if (root && rootCapacity > 0) roots else entries
        val capacity = if (root && rootCapacity > 0) rootCapacity else transientCapacity
        put(table, capacity, TransitionKey(state, move), transition.copy(nextLegalMoves = transition.nextLegalMoves.toList()))
    }

    private fun get(table: MutableMap<Any, Any>, key: Any): Any? {
        val value = table.remove(key) ?: return null
        table[key] = value
        return value
    }

    private fun put(table: MutableMap<Any, Any>, capacity: Int, key: Any, value: Any) {
        table.remove(key)
        if (table.size >= capacity) {
            table.remove(table.keys.first())
            evictions++
        }
        table[key] = value
    }

    internal data class Configuration(
        val rules: List<BotRule>,
        val evaluation: BotEvaluationMode,
        val model: BotSearchModel,
        val rootActor: PlayerId,
        val tacticalDepth: Int,
    )

    internal data class SearchKey(
        val state: GameState,
        val configuration: Configuration,
        val depth: Int,
        val horizon: BotSearchHorizon,
        val responders: Set<PlayerId>,
        // Null identifies a regular search node, rather than a tactical frontier.
        val remaining: Int? = null,
        val allowNewExchange: Boolean = true,
    ) {
        private val storedHash: Int = run {
            var value = state.hashCode()
            value = 31 * value + configuration.hashCode()
            value = 31 * value + depth
            value = 31 * value + horizon.hashCode()
            value = 31 * value + responders.hashCode()
            value = 31 * value + (remaining?.hashCode() ?: 0)
            31 * value + allowNewExchange.hashCode()
        }

        override fun hashCode(): Int = storedHash
    }

    internal data class EvaluationKey(val state: GameState, val configuration: Configuration) {
        private val storedHash = 31 * state.hashCode() + configuration.hashCode()
        override fun hashCode(): Int = storedHash
    }

    private data class TransitionKey(val state: GameState, val move: Move) {
        private val storedHash = 31 * state.hashCode() + move.hashCode()
        override fun hashCode(): Int = storedHash
    }
}
