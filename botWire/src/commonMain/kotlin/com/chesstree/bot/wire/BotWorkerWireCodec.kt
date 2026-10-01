package com.chesstree.bot.wire

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.bot.BotFeature
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Message schema shared by the browser page and the dedicated JS search worker. */
object BotWorkerWireCodec {
    const val VERSION = 5
    private const val MAX_LENGTH = 1_000_000
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encodeRequest(request: BotWorkerRequest): String = json.encodeToString(request)

    fun decodeRequest(contents: String): BotWorkerRequest? = runCatching {
        if (contents.length > MAX_LENGTH) return null
        json.decodeFromString<BotWorkerRequest>(contents).takeIf {
            validProfile(it.profile, it.difficulty) && it.profileCatalogVersion in 1..2 && it.version == VERSION && it.rulesVersion == 1 && validEngineAndTacticalDepth(it.engineVersion, it.tacticalDepth, it.searchModel) &&
                it.evaluationVersion in 1..3 && it.openingBookVersion in 0..1 && it.requestId.isNotBlank() && it.gameId.isNotBlank() &&
                it.positionRevision >= 0 && it.budgetMs > 0 &&
                it.maxDepth > 0 && it.maxNodes > 0 && it.maxEvaluations > 0 &&
                BotStateWireCodec.decode(it.state)?.turn?.player?.name == it.player &&
                it.recentPositions.size <= com.chesstree.game.domain.bot.MAX_BOT_RECENT_POSITIONS && (it.engineVersion != 1 || it.recentPositions.isEmpty()) &&
                it.recentPositions.all { encoded -> BotStateWireCodec.decode(encoded) != null } &&
                it.toPolicy() != null
        }
    }.getOrNull()

    fun encodeReply(reply: BotWorkerReply): String = json.encodeToString(reply)

    fun decodeReply(contents: String): BotWorkerReply? = runCatching {
        if (contents.length > MAX_LENGTH) return null
        json.decodeFromString<BotWorkerReply>(contents).takeIf {
            validProfile(it.profile, it.difficulty) && it.profileCatalogVersion in 1..2 && it.version == VERSION && it.rulesVersion == 1 && it.engineVersion in 1..3 &&
                it.evaluationVersion in 1..3 && it.openingBookVersion in 0..1 && it.requestId.isNotBlank() && it.gameId.isNotBlank() &&
                it.positionRevision >= 0 && it.kind in REPLY_KINDS &&
                it.elapsedMs >= 0 && it.completedDepth >= 0 &&
                it.repetitionPenalty.isFinite() && it.repetitionPenalty in 0.0..2400.0 &&
                (it.completedDepth > 0 || it.repetitionPenalty == 0.0) &&
                (it.engineVersion != 1 || it.repetitionPenalty == 0.0) &&
                it.completedHorizon in setOf("STATIC", "CHECKS", "EXCHANGES") &&
                ((it.engineVersion == 3 && it.searchModel == "PARANOID") || (it.engineVersion != 3 && it.searchModel == "MAX_N")) &&
                (if (it.engineVersion == 1 || it.completedDepth == 0) it.completedHorizon == "STATIC"
                    else it.engineVersion == 3 || it.completedHorizon != "STATIC") &&
                it.expandedNodes >= 0 && it.leafEvaluations >= 0 &&
                ((it.kind in MOVE_KINDS && it.move?.toIntent() != null) ||
                    (it.kind !in MOVE_KINDS && it.move == null)) &&
                (!it.openingBookInfluenced || (it.openingBookVersion == 1 &&
                it.kind in setOf("PROGRESS", "MOVE") && it.completedDepth > 0)) &&
                (it.kind != "READY" || !it.openingBookInfluenced)
        }
    }.getOrNull()

    private fun validProfile(profile: String, difficulty: String?): Boolean =
        com.chesstree.game.domain.bot.BotPlayingProfile.entries.any { it.name == profile } &&
            (difficulty == null || com.chesstree.game.domain.bot.BotDifficulty.entries.any { it.name == difficulty })

    private val REPLY_KINDS = setOf("READY", "PROGRESS", "MOVE", "TERMINAL", "CANCELLED", "FAILED")
    private val MOVE_KINDS = setOf("READY", "PROGRESS", "MOVE")

    private fun validEngineAndTacticalDepth(engineVersion: Int, tacticalDepth: Int, model: String): Boolean =
        (model == "MAX_N" && ((engineVersion == 1 && tacticalDepth == 0) || (engineVersion == 2 && tacticalDepth in 1..6))) ||
            (model == "PARANOID" && engineVersion == 3 && tacticalDepth in 0..6)
}

@Serializable
data class BotWorkerRequest(
    val version: Int = BotWorkerWireCodec.VERSION,
    val requestId: String,
    val gameId: String,
    val positionRevision: Long,
    val rulesVersion: Int = 1,
    val engineVersion: Int = 1,
    val searchModel: String = "MAX_N",
    val profile: String = "UNIVERSAL",
    val profileCatalogVersion: Int = 1,
    val difficulty: String? = null,
    val evaluationVersion: Int = 1,
    val player: String,
    val state: String,
    val policy: List<BotWireRule>,
    val budgetMs: Int,
    val maxDepth: Int,
    val maxNodes: Int,
    val maxEvaluations: Int,
    val openingBookVersion: Int = 0,
    val seed: Long = 0L,
    val tacticalDepth: Int = 0,
    val recentPositions: List<String> = emptyList(),
) {
    fun toPolicy(): BotPolicy? = runCatching {
        BotPolicy(policy.map { BotRule(BotFeature.valueOf(it.feature), it.weight, it.enabled) })
    }.getOrNull()
}

@Serializable
data class BotWireRule(val feature: String, val weight: Int, val enabled: Boolean)

fun BotPolicy.toWireRules(): List<BotWireRule> = rules.map {
    BotWireRule(it.feature.name, it.weight, it.enabled)
}

@Serializable
data class BotWireCoordinate(val vertex: Int, val column: Int, val row: Int) {
    fun toDomain(): BoardCoordinate? = runCatching { BoardCoordinate(vertex, column, row) }.getOrNull()
}

@Serializable
data class BotWireMove(
    val actor: String,
    val from: BotWireCoordinate,
    val to: BotWireCoordinate,
    val promotion: String? = null,
) {
    fun toIntent(): MoveIntent? = runCatching {
        MoveIntent(
            PlayerId.valueOf(actor),
            checkNotNull(from.toDomain()),
            checkNotNull(to.toDomain()),
            promotion?.let(PromotionChoice::valueOf),
        )
    }.getOrNull()
}

fun MoveIntent.toWire(): BotWireMove = BotWireMove(
    actor.name,
    BotWireCoordinate(from.vertex, from.column, from.row),
    BotWireCoordinate(to.vertex, to.column, to.row),
    promotion?.name,
)

@Serializable
data class BotWorkerReply(
    val version: Int = BotWorkerWireCodec.VERSION,
    val requestId: String,
    val gameId: String,
    val positionRevision: Long,
    val rulesVersion: Int = 1,
    val engineVersion: Int = 1,
    val searchModel: String = "MAX_N",
    val profile: String = "UNIVERSAL",
    val profileCatalogVersion: Int = 1,
    val difficulty: String? = null,
    val evaluationVersion: Int = 1,
    val kind: String,
    val move: BotWireMove? = null,
    val source: String? = null,
    val reason: String? = null,
    val elapsedMs: Int = 0,
    val completedDepth: Int = 0,
    val completedHorizon: String = "STATIC",
    val repetitionPenalty: Double = 0.0,
    val expandedNodes: Int = 0,
    val leafEvaluations: Int = 0,
    val openingBookVersion: Int = 0,
    val openingBookInfluenced: Boolean = false,
)
