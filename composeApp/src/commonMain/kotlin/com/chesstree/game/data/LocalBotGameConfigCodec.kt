package com.chesstree.game.data

import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotFeature
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotRule

/** Strict, versioned records containing only gameplay settings. */
object LocalBotGameConfigCodec {
    fun encode(config: LocalBotGameConfig): String = buildString {
        appendLine(HEADER)
        appendLine("versions|${config.rulesVersion}|${config.engineVersion}|${config.evalVersion}|${config.bookVersion}|${config.profileCatalogVersion}")
        appendLine("human|${config.humanSeat?.name ?: "-"}")
        appendLine("seats|${config.seats.size}")
        config.seats.forEach { seat ->
            appendLine("seat|${seat.player.name}|${seat.profile.name}|${seat.difficulty.name}|${seat.seed}")
        }
        appendLine("policy|${config.basePolicy.rules.size}")
        config.basePolicy.rules.forEach { rule ->
            appendLine("rule|${rule.feature.name}|${rule.weight}|${if (rule.enabled) "1" else "0"}")
        }
    }

    fun decode(contents: String): LocalBotGameConfig? = runCatching {
        if (contents.length > MAX_CONTENT_LENGTH) return null
        val lines = contents.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        if (lines.size !in MIN_LINE_COUNT..MAX_LINE_COUNT || lines.first() != HEADER) return null
        val versions = fields(lines[1], "versions", 6)
        val human = fields(lines[2], "human", 2)[1].takeUnless { it == "-" }?.let(PlayerId::valueOf)
        val seatCount = fields(lines[3], "seats", 2)[1].toInt().also { require(it in 2..3) }
        val seats = lines.subList(4, 4 + seatCount).map { line ->
            val seat = fields(line, "seat", 5)
            BotSeatConfig(
                player = PlayerId.valueOf(seat[1]),
                profile = BotPlayingProfile.valueOf(seat[2]),
                difficulty = BotDifficulty.valueOf(seat[3]),
                seed = seat[4].toLong(),
            )
        }
        val policyIndex = 4 + seatCount
        val ruleCount = fields(lines[policyIndex], "policy", 2)[1].toInt()
            .also { require(it in 0..BotFeature.entries.size) }
        require(lines.size == policyIndex + 1 + ruleCount)
        val rules = lines.drop(policyIndex + 1).map { line ->
            val rule = fields(line, "rule", 4)
            BotRule(
                feature = BotFeature.valueOf(rule[1]),
                weight = rule[2].toInt(),
                enabled = when (rule[3]) {
                    "1" -> true
                    "0" -> false
                    else -> error("Invalid policy flag")
                },
            )
        }
        LocalBotGameConfig(
            humanSeat = human,
            seats = seats,
            basePolicy = BotPolicy(rules),
            rulesVersion = versions[1].toInt(),
            engineVersion = versions[2].toInt(),
            evalVersion = versions[3].toInt(),
            bookVersion = versions[4].toInt(),
            profileCatalogVersion = versions[5].toInt(),
        )
    }.getOrNull()

    private fun fields(line: String, record: String, count: Int): List<String> = line.split('|').also {
        require(it.size == count && it[0] == record)
    }

    private const val HEADER = "LOCALBOT|1"
    private const val MIN_LINE_COUNT = 7
    private const val MAX_LINE_COUNT = 12
    private const val MAX_CONTENT_LENGTH = 20_000
}
