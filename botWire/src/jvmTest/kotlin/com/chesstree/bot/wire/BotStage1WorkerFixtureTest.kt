package com.chesstree.bot.wire

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.bot.BotPolicy
import com.chesstree.game.domain.bot.MAX_BOT_RECENT_POSITIONS
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Builds real reported-position worker inputs; recorded moves are provenance, not best-move expectations. */
class BotStage1WorkerFixtureTest {
    @Test
    fun writesReportedStrongRequestsWithFullRecentHistory() {
        if (System.getenv("CHESSTREE_BOT_STAGE1_WORKER_FIXTURES") != "true") return
        val root = repositoryRoot()
        val source = System.getenv("CHESSTREE_BOT_REPORTED_GAME_PATH")?.let(::File)
            ?: File(root, SOURCE_PATH)
        assertTrue(source.isFile, "Missing reported source: $source")
        val bytes = source.readBytes()
        assertEquals(SOURCE_SHA256, sha256(bytes))
        val lines = bytes.toString(Charsets.UTF_8).lineSequence().toList()
        assertEquals("CHESSTREE|2", lines.first())
        val encodedInitial = lines.filter { it.startsWith("initial|") }.single().substringAfter('|')
        val initial = assertNotNull(BotStateWireCodec.decode(encodedInitial))
        assertEquals(StandardGame.scenario.initialState, initial)
        val moveRows = lines.filter { it.startsWith("move|") }
        assertEquals(474, moveRows.size)
        val moves = moveRows.mapIndexed { index, row -> parseMove(row, index + 1) }
        assertEquals(listOf(295, 463), moves.mapIndexedNotNull { index, move ->
            (index + 1).takeIf { move.promotion != null }
        })
        val history = mutableListOf(initial)
        moves.forEachIndexed { index, intent ->
            val reduced = assertIs<MoveReduction.Applied>(GameReducer.reduce(history.last(), intent),
                "Public reducer rejected exported ply ${index + 1}")
            assertEquals(intent.promotion, reduced.move.promotion)
            history += reduced.state
        }
        assertEquals(GamePhase.Finished(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK)),
            history.last().phase)
        val output = System.getenv("CHESSTREE_BOT_STAGE1_WORKER_FIXTURES_OUTPUT")?.let(::File)
            ?: File(root, "botWire/build/reports/bot-quality/stage1-worker-fixtures")
        assertTrue(output.isDirectory || output.mkdirs(), "Cannot create output: $output")
        val cases = linkedMapOf(
            "queen-defense-142" to 141,
            "rook-shuffle-193" to 192,
            "exchange-267" to 266,
            "army-transfer-474" to 473,
        )
        val manifest = buildJsonObject {
            put("schema", "chesstree-stage1-worker-fixtures-v1")
            put("sourceSha256", SOURCE_SHA256)
            put("source", source.canonicalPath)
            put("wireVersion", BotWorkerWireCodec.VERSION)
            put("recordedMovesAreBestMoveExpectations", false)
            put("cases", buildJsonArray {
                cases.forEach { (id, prefix) ->
                    val state = history[prefix]
                    val actor = checkNotNull(state.turn).player
                    assertEquals(prefix + 1, state.turn?.ply)
                    assertEquals(moves[prefix].actor, actor)
                    val recent = history.take(prefix + 1).takeLast(MAX_BOT_RECENT_POSITIONS)
                    assertEquals(MAX_BOT_RECENT_POSITIONS, recent.size)
                    val legal = LegalMoveGenerator.legalMoves(state)
                    assertTrue(legal.isNotEmpty(), "Worker fixture $id must have legal moves")
                    legal.forEach { move ->
                        assertIs<MoveReduction.Applied>(GameReducer.reduce(state,
                            MoveIntent(move.actor, move.from, move.to, move.promotion)),
                            "Generated move must apply through public reducer: $id / $move")
                    }
                    if (prefix == 473) {
                        assertEquals(2, state.participants.values.count { it.status == ParticipantStatus.Active })
                        assertEquals(ParticipantStatus.Checkmated(PlayerId.WHITE, 474),
                            state.participants.getValue(PlayerId.BLACK).status)
                        assertEquals(PlayerId.WHITE, state.armies.getValue(ArmyColor.BLACK).controller)
                    }
                    val request = BotWorkerRequest(
                        version = 5,
                        requestId = "stage1-$id",
                        gameId = "stage1-reported-game",
                        positionRevision = prefix.toLong(),
                        rulesVersion = 1,
                        engineVersion = 3,
                        searchModel = "PARANOID",
                        profile = "UNIVERSAL",
                        profileCatalogVersion = 2,
                        difficulty = "STRONG",
                        evaluationVersion = 3,
                        player = actor.name,
                        state = BotStateWireCodec.encode(state),
                        policy = BotPolicy.DEFAULT.toWireRules(),
                        budgetMs = 30_000,
                        maxDepth = 4,
                        maxNodes = 1_000_000,
                        maxEvaluations = 1_000_000,
                        openingBookVersion = 1,
                        seed = 0,
                        tacticalDepth = 4,
                        recentPositions = recent.map(BotStateWireCodec::encode),
                    )
                    assertEquals(5, BotWorkerWireCodec.VERSION)
                    val encoded = BotWorkerWireCodec.encodeRequest(request)
                    val decoded = assertNotNull(BotWorkerWireCodec.decodeRequest(encoded))
                    assertEquals(request, decoded)
                    assertEquals(state, BotStateWireCodec.decode(decoded.state))
                    assertEquals(recent, decoded.recentPositions.map { BotStateWireCodec.decode(it) })
                    assertEquals(state, BotStateWireCodec.decode(decoded.recentPositions.last()))
                    val file = File(output, "$id.json")
                    file.writeText(encoded, Charsets.UTF_8)
                    add(buildJsonObject {
                        put("id", id)
                        put("file", file.name)
                        put("requestSha256", sha256(encoded.toByteArray(Charsets.UTF_8)))
                        put("prefixPlies", prefix)
                        put("actor", actor.name)
                        put("recentPositionCount", recent.size)
                        put("recentFirstPly", checkNotNull(recent.first().turn).ply)
                        put("recordedNextMove", moves[prefix].token())
                        put("activePlayers", state.participants.values.count { it.status == ParticipantStatus.Active })
                        put("legalMoves", buildJsonArray {
                            legal.forEach { move ->
                                add(buildJsonObject {
                                    put("actor", move.actor.name)
                                    put("from", buildJsonObject {
                                        put("vertex", move.from.vertex)
                                        put("column", move.from.column)
                                        put("row", move.from.row)
                                    })
                                    put("to", buildJsonObject {
                                        put("vertex", move.to.vertex)
                                        put("column", move.to.column)
                                        put("row", move.to.row)
                                    })
                                    put("promotion", move.promotion?.name)
                                })
                            }
                        })
                    })
                }
            })
        }
        File(output, "manifest.json").writeText(manifest.toString(), Charsets.UTF_8)
    }

    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .firstOrNull { File(it, SOURCE_PATH).isFile && File(it, "settings.gradle.kts").isFile }
            ?: throw IllegalArgumentException("Cannot find ChessTree repository from current working directory")

    // gameDomain's JVM test helper is not consumable from this module's separate test source set.
    private fun parseMove(row: String, ply: Int): MoveIntent {
        val fields = row.split('|')
        require(fields.size == 9 && fields.first() == "move") { "Malformed exported move row at ply $ply" }
        return MoveIntent(
            PlayerId.valueOf(fields[1]),
            BoardCoordinate(fields[2].toInt(), fields[3].toInt(), fields[4].toInt()),
            BoardCoordinate(fields[5].toInt(), fields[6].toInt(), fields[7].toInt()),
            fields[8].takeUnless { it == "-" }?.let(PromotionChoice::valueOf),
        )
    }

    private fun MoveIntent.token(): String =
        "$actor:${ThreePlayerBoardNotation.square(from)}>${ThreePlayerBoardNotation.square(to)}:${promotion ?: "-"}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SOURCE_PATH = "docs/bots/agent-plan-20261019/reported-game.txt"
        private const val SOURCE_SHA256 = "763ce5415434f1f2f214bdf83e2746a3b9b6be7507d085c5023e0860dd419fba"
    }
}
