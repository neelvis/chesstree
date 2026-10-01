package com.chesstree.game.data

import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotMoveSource
import com.chesstree.game.domain.bot.BotOpeningDiagnostics
import com.chesstree.game.domain.bot.BotSearchHorizon
import com.chesstree.game.domain.bot.BotStopReason
import com.chesstree.game.domain.bot.BoundedBotStats

/** The preceding move prefix identifies the searched position without personal identifiers. */
data class LocalBotMoveDiagnostic(
    val moveIndex: Int,
    val player: PlayerId,
    val elapsedMs: Long,
    val source: BotMoveSource,
    val reason: BotStopReason,
    val stats: BoundedBotStats,
    val openingBook: BotOpeningDiagnostics,
    val repetitionPenalty: Double,
) {
    init {
        require(moveIndex in 1..10_000 && elapsedMs >= 0)
        require(stats.expandedNodes >= 0 && stats.leafEvaluations >= 0 && stats.completedDepth >= 0)
        require(repetitionPenalty.isFinite() && repetitionPenalty in 0.0..2400.0)
        require(openingBook.version in 0..1 && (!openingBook.influenced || openingBook.version == 1))
        require(if (source == BotMoveSource.LEGAL_FALLBACK) {
            stats.completedDepth == 0 && stats.completedHorizon == BotSearchHorizon.STATIC && repetitionPenalty == 0.0
        } else stats.completedDepth > 0)
    }

    fun encode(): String = listOf(
        "diagnostic", moveIndex, player.name, elapsedMs, source.name, reason.name,
        stats.expandedNodes, stats.leafEvaluations, stats.completedDepth, stats.completedHorizon.name,
        openingBook.version, if (openingBook.influenced) "1" else "0", repetitionPenalty,
        "0", // This engine has no transposition cache.
    ).joinToString("|")

    companion object {
        fun decode(line: String): LocalBotMoveDiagnostic? = runCatching {
            val fields = line.split('|')
            require(fields.size == 14 && fields[0] == "diagnostic" && fields[13] == "0")
            LocalBotMoveDiagnostic(
                fields[1].toInt(), PlayerId.valueOf(fields[2]), fields[3].toLong(),
                BotMoveSource.valueOf(fields[4]), BotStopReason.valueOf(fields[5]),
                BoundedBotStats(fields[6].toInt(), fields[7].toInt(), fields[8].toInt(), BotSearchHorizon.valueOf(fields[9])),
                BotOpeningDiagnostics(fields[10].toInt(), when (fields[11]) {
                    "0" -> false
                    "1" -> true
                    else -> error("Invalid book flag")
                }), fields[12].toDouble(),
            )
        }.getOrNull()
    }
}
