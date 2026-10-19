package com.chesstree.bot.wire

import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Verifies the export's initial codec at the transport layer, without a domain-to-wire dependency. */
class ReportedBotInitialStateTest {
    @Test
    fun reportedInitialStateIsExactlyTheStandardRulesState() {
        val source = sourceFile()
        assertTrue(source.isFile, "Missing reported-game source: $source")
        val bytes = source.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("763ce5415434f1f2f214bdf83e2746a3b9b6be7507d085c5023e0860dd419fba", hash)
        val initialRows = bytes.toString(Charsets.UTF_8).lineSequence().filter { it.startsWith("initial|") }.toList()
        assertEquals(1, initialRows.size)
        val decoded = assertNotNull(BotStateWireCodec.decode(initialRows.single().substringAfter('|')))
        // Equality covers pieces/ids/moved flags, rights, en passant, statuses, controllers, turn and phase.
        assertEquals(StandardGame.scenario.initialState, decoded)
        assertEquals(decoded, BotStateWireCodec.decode(BotStateWireCodec.encode(decoded)))
    }

    private fun sourceFile(): File {
        System.getenv("CHESSTREE_BOT_REPORTED_GAME_PATH")?.let { return File(it) }
        System.getenv("CHESSTREE_BOT_FIXTURES_MANIFEST")?.let { return File(File(it).absoluteFile.parentFile, "reported-game.txt") }
        val relative = "docs/bots/agent-plan-20261019/reported-game.txt"
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, relative) }.firstOrNull(File::isFile)
            ?: throw IllegalArgumentException("Cannot find $relative; set CHESSTREE_BOT_REPORTED_GAME_PATH")
    }
}
