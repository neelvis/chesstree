package com.chesstree.game.data

import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.bot.BotDifficulty
import com.chesstree.game.domain.bot.BotPlayingProfile
import com.chesstree.game.domain.bot.BotPolicy

data class BotSeatConfig(
    val player: PlayerId,
    val profile: BotPlayingProfile,
    val difficulty: BotDifficulty,
    val seed: Long,
)

/** A game's frozen bot setup, independent of subsequent global policy changes. */
class LocalBotGameConfig(
    val humanSeat: PlayerId?,
    seats: List<BotSeatConfig>,
    basePolicy: BotPolicy = BotPolicy.DEFAULT,
    val rulesVersion: Int = RULES_VERSION,
    val engineVersion: Int = ENGINE_VERSION,
    val evalVersion: Int = EVAL_VERSION,
    val bookVersion: Int = BOOK_VERSION,
    val profileCatalogVersion: Int = PROFILE_CATALOG_VERSION,
) {
    val seats: List<BotSeatConfig> = seats.toList()
    val basePolicy: BotPolicy = BotPolicy(basePolicy.rules.toList())

    init {
        val expectedPlayers = PlayerId.entries.filter { it != humanSeat }.toSet()
        require(this.seats.size == expectedPlayers.size && this.seats.map { it.player }.toSet() == expectedPlayers) {
            "Bot seats must cover every player except the human seat exactly once"
        }
        require(rulesVersion == RULES_VERSION && engineVersion == ENGINE_VERSION && evalVersion == EVAL_VERSION &&
                bookVersion == BOOK_VERSION && profileCatalogVersion in 1..PROFILE_CATALOG_VERSION) {
            "Unsupported local bot configuration versions"
        }
    }

    fun seatFor(player: PlayerId): BotSeatConfig? = seats.firstOrNull { it.player == player }

    override fun equals(other: Any?): Boolean =
        other is LocalBotGameConfig && humanSeat == other.humanSeat && seats == other.seats &&
                basePolicy == other.basePolicy && rulesVersion == other.rulesVersion &&
                engineVersion == other.engineVersion && evalVersion == other.evalVersion &&
                bookVersion == other.bookVersion && profileCatalogVersion == other.profileCatalogVersion

    override fun hashCode(): Int {
        var result = humanSeat?.hashCode() ?: 0
        result = 31 * result + seats.hashCode()
        result = 31 * result + basePolicy.hashCode()
        result = 31 * result + rulesVersion
        result = 31 * result + engineVersion
        result = 31 * result + evalVersion
        result = 31 * result + bookVersion
        return 31 * result + profileCatalogVersion
    }

    companion object {
        const val RULES_VERSION = 1
        const val ENGINE_VERSION = 3
        const val EVAL_VERSION = 3
        const val BOOK_VERSION = 1
        const val PROFILE_CATALOG_VERSION = 2

        fun humanGame(
            humanSeat: PlayerId,
            seeds: Map<PlayerId, Long>,
            basePolicy: BotPolicy = BotPolicy.DEFAULT,
        ): LocalBotGameConfig = defaults(humanSeat, seeds, BotDifficulty.NORMAL, basePolicy)

        fun watchGame(
            seeds: Map<PlayerId, Long>,
            basePolicy: BotPolicy = BotPolicy.DEFAULT,
        ): LocalBotGameConfig = defaults(null, seeds, BotDifficulty.STRONG, basePolicy)

        private fun defaults(
            humanSeat: PlayerId?,
            seeds: Map<PlayerId, Long>,
            difficulty: BotDifficulty,
            basePolicy: BotPolicy,
        ): LocalBotGameConfig {
            val players = PlayerId.entries.filter { it != humanSeat }
            require(seeds.keys == players.toSet()) { "An explicit seed is required for every bot seat" }
            return LocalBotGameConfig(
                humanSeat = humanSeat,
                seats = players.map { BotSeatConfig(it, BotPlayingProfile.UNIVERSAL, difficulty, seeds.getValue(it)) },
                basePolicy = basePolicy,
            )
        }
    }
}
