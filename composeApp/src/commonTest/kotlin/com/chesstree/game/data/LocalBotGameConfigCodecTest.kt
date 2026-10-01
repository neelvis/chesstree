package com.chesstree.game.data

import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotFeature
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.BotRule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalBotGameConfigCodecTest {
    @Test
    fun independentSeatsAndFrozenPolicyRoundTrip() {
        val config = config()
        assertEquals(config, LocalBotGameConfigCodec.decode(LocalBotGameConfigCodec.encode(config)))
        assertEquals(Long.MIN_VALUE, config.seatFor(PlayerId.WHITE)?.seed)
        assertEquals(Long.MAX_VALUE, config.seatFor(PlayerId.BLACK)?.seed)
        assertNull(config.seatFor(PlayerId.RED))
    }

    @Test
    fun humanAndWatchDefaultsRequireExplicitSeedsForEveryBot() {
        val human = LocalBotGameConfig.humanGame(PlayerId.WHITE, mapOf(PlayerId.RED to 12L, PlayerId.BLACK to 34L))
        assertEquals(listOf(BotDifficulty.NORMAL, BotDifficulty.NORMAL), human.seats.map { it.difficulty })
        val watch = LocalBotGameConfig.watchGame(PlayerId.entries.associateWith { it.ordinal.toLong() })
        assertNull(watch.humanSeat)
        assertEquals(PlayerId.entries.toSet(), watch.seats.map { it.player }.toSet())
        assertEquals(List(3) { BotDifficulty.STRONG }, watch.seats.map { it.difficulty })
        assertEquals(watch, LocalBotGameConfigCodec.decode(LocalBotGameConfigCodec.encode(watch)))
        assertFailsWith<IllegalArgumentException> {
            LocalBotGameConfig.humanGame(PlayerId.WHITE, mapOf(PlayerId.RED to 12L))
        }
    }

    @Test
    fun previousCatalogIsPreservedRatherThanSilentlyUpgradedOnRestore() {
        val current = config()
        val legacy = assertNotNull(LocalBotGameConfigCodec.decode(
            LocalBotGameConfigCodec.encode(current).replace("versions|1|3|3|1|2", "versions|1|3|3|1|1"),
        ))
        assertEquals(1, legacy.profileCatalogVersion)
        assertEquals(2, current.profileCatalogVersion)
        assertEquals(current.seats, legacy.seats)
        assertEquals(legacy, LocalBotGameConfigCodec.decode(LocalBotGameConfigCodec.encode(legacy)))
    }

    @Test
    fun sourceCollectionsCannotChangeAnActiveConfiguration() {
        val seats = config().seats.toMutableList()
        val rules = mutableListOf(BotRule(BotFeature.MATERIAL, 250))
        val frozen = LocalBotGameConfig(PlayerId.RED, seats, BotPolicy(rules))
        seats.clear()
        rules.clear()
        assertEquals(2, frozen.seats.size)
        assertEquals(250, frozen.basePolicy.weight(BotFeature.MATERIAL))
    }

    @Test
    fun unknownVersionsEnumsFlagsAndOutOfBoundsValuesAreRejected() {
        val encoded = LocalBotGameConfigCodec.encode(config())
        for (field in 1..5) {
            val versions = "versions|1|3|3|1|2".split('|').toMutableList().also { it[field] = "999" }
            assertNull(LocalBotGameConfigCodec.decode(encoded.replace("versions|1|3|3|1|2", versions.joinToString("|"))))
        }
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("LOCALBOT|1", "LOCALBOT|2")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("ATTACKING", "COOPERATIVE")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("BEGINNER", "EXPERT")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace(Long.MIN_VALUE.toString(), "9223372036854775808")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("rule|MATERIAL|125|0", "rule|MATERIAL|10001|0")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("rule|MATERIAL|125|0", "rule|MATERIAL|125|true")))
    }

    @Test
    fun duplicateMissingOrHumanBotSeatsAndUnexpectedRecordsAreRejected() {
        val encoded = LocalBotGameConfigCodec.encode(config())
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("seat|BLACK", "seat|WHITE")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("seat|BLACK", "seat|RED")))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("seats|2", "seats|3")))
        assertNull(LocalBotGameConfigCodec.decode(encoded + "extra|value\n"))
        assertNull(LocalBotGameConfigCodec.decode(encoded.replace("human|RED\n", "human|RED\n\n")))
        assertNull(LocalBotGameConfigCodec.decode(encoded + "x".repeat(20_000)))
    }

    @Test
    fun duplicatePolicyFeaturesAreRejectedRatherThanSilentlyMerged() {
        val encoded = LocalBotGameConfigCodec.encode(config())
            .replace("policy|1", "policy|2") + "rule|MATERIAL|300|1\n"
        assertNull(LocalBotGameConfigCodec.decode(encoded))
    }

    private fun config() = LocalBotGameConfig(
        humanSeat = PlayerId.RED,
        seats = listOf(
            BotSeatConfig(PlayerId.WHITE, BotPlayingProfile.ATTACKING, BotDifficulty.BEGINNER, Long.MIN_VALUE),
            BotSeatConfig(PlayerId.BLACK, BotPlayingProfile.ENDGAME, BotDifficulty.STRONG, Long.MAX_VALUE),
        ),
        basePolicy = BotPolicy(listOf(BotRule(BotFeature.MATERIAL, weight = 125, enabled = false))),
    )
}
