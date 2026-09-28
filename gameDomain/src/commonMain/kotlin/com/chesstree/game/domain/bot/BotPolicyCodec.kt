package com.chesstree.game.domain.bot

import com.chesstree.game.domain.PlayerId

/** Small versioned text codec for the bounded bot policy persisted by clients and server. */
object BotPolicyCodec {
    fun encode(policy: BotPolicy): String = buildString {
        appendLine(VERSION)
        policy.rules.sortedBy { it.feature.ordinal }.forEach { rule ->
            append(rule.feature.name)
            append('|')
            append(rule.weight)
            append('|')
            appendLine(if (rule.enabled) "1" else "0")
        }
    }

    fun decode(contents: String?): BotPolicy? = runCatching {
        val lines = contents?.lineSequence()?.filter(String::isNotBlank)?.toList().orEmpty()
        require(lines.firstOrNull() == VERSION)
        val rules = lines.drop(1).map { line ->
            val fields = line.split('|')
            require(fields.size == 3)
            BotRule(
                feature = BotFeature.valueOf(fields[0]),
                weight = fields[1].toInt(),
                enabled = when (fields[2]) {
                    "1" -> true
                    "0" -> false
                    else -> error("Invalid enabled flag")
                },
            )
        }
        BotPolicy(rules)
    }.getOrNull()

    private const val VERSION = "chesstree-bot-policy-v1"
}

object BotTrainingSampleCodec {
    fun encode(sample: BotTrainingSample): String = buildString {
        append(VERSION)
        append('|')
        append(sample.player.name)
        BotFeature.entries.forEach { feature ->
            append('|')
            append(feature.name)
            append(':')
            append(sample.featureDeltas[feature] ?: 0)
        }
    }

    fun decode(contents: String?): BotTrainingSample? = runCatching {
        val fields = contents?.split('|').orEmpty()
        require(fields.size == BotFeature.entries.size + 2 && fields[0] == VERSION)
        val deltas = fields.drop(2).associate { field ->
            val parts = field.split(':')
            require(parts.size == 2)
            BotFeature.valueOf(parts[0]) to parts[1].toInt().also {
                require(it in -FEATURE_DELTA_LIMIT..FEATURE_DELTA_LIMIT)
            }
        }
        require(deltas.keys == BotFeature.entries.toSet())
        BotTrainingSample(PlayerId.valueOf(fields[1]), deltas)
    }.getOrNull()

    private const val VERSION = "sample-v1"
}
