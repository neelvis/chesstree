package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReportedBotFixturesTest {
    @Test
    fun verifiesSourceAndEverySuppliedPrefixWithCompleteHistory() {
        assertEquals("763ce5415434f1f2f214bdf83e2746a3b9b6be7507d085c5023e0860dd419fba", corpus.sourceSha256)
        assertEquals(474, corpus.moves.size)
        assertEquals(475, corpus.history.size)
        assertEquals(StandardGame.scenario.initialState, corpus.history.first())
        val expected = linkedMapOf(
            "queen-exposure-139" to 138, "queen-defense-142" to 141, "queen-defense-145" to 144,
            "rook-shuffle-193" to 192, "rook-shuffle-199" to 198,
            "exchange-267" to 266, "exchange-272" to 271,
            "promotion-295" to 294, "promotion-capture-298" to 297, "conversion-409" to 408,
            "promotion-463" to 462, "mate-attribution-473" to 472,
            "army-transfer-474" to 473, "finished-474" to 474,
        )
        assertEquals(expected, corpus.cases.associate { it.id to it.prefixPlies })
        corpus.cases.forEach { fixture ->
            assertEquals(fixture.prefixPlies + 1, fixture.history.size, fixture.id)
            assertEquals(corpus.history.take(fixture.prefixPlies + 1), fixture.history, fixture.id)
            assertEquals(corpus.history[fixture.prefixPlies], fixture.state, fixture.id)
            assertEquals(fixture.history.takeLast(MAX_BOT_RECENT_POSITIONS), fixture.recentPositions, fixture.id)
            assertEquals(fixture.prefixMoveRowsSha256,
                ReportedBotFixtures.prefixHash(corpus.moveRows, fixture.prefixPlies), fixture.id)
            if (fixture.prefixPlies < corpus.moves.size) {
                val next = corpus.moves[fixture.prefixPlies]
                assertEquals(next.actor, fixture.state.turn?.player, fixture.id)
                assertEquals(fixture.prefixPlies + 1, fixture.state.turn?.ply, fixture.id)
                assertEquals(corpus.history[fixture.prefixPlies + 1],
                    assertIs<MoveReduction.Applied>(GameReducer.reduce(fixture.state, next)).state, fixture.id)
            }
        }
        // The supplied corpus includes every root seat, as well as the terminal invariant.
        assertEquals(PlayerId.entries.toSet(), corpus.cases.mapNotNull { it.state.turn?.player }.toSet())
        assertFailsWith<IllegalArgumentException> { corpus.case("missing") }
    }

    @Test
    fun preservesBothExplicitPromotionsAndTheirPublicReducerResults() {
        assertEquals(listOf(295, 463), corpus.moves.mapIndexedNotNull { index, move ->
            (index + 1).takeIf { move.promotion != null }
        })
        listOf(295, 463).forEach { ply ->
            val intent = corpus.moves[ply - 1]
            assertEquals(PromotionChoice.QUEEN, intent.promotion)
            val applied = assertIs<MoveReduction.Applied>(GameReducer.reduce(corpus.history[ply - 1], intent))
            assertEquals(PromotionChoice.QUEEN, applied.move.promotion)
            val promoted = applied.state.position.pieces.values.single { it.coordinate == intent.to }
            assertEquals(PieceType.QUEEN, promoted.type)
            assertEquals(ArmyColor.WHITE, promoted.army)
            assertEquals(corpus.history[ply], applied.state)
        }
    }

    @Test
    fun preservesMateAuthorshipAndGenuineTwoActivePlayerArmyTransfer() {
        val before = corpus.case("mate-attribution-473").state
        assertEquals(PlayerId.RED, before.turn?.player)
        assertEquals(3, before.participants.values.count { it.status == ParticipantStatus.Active })
        val transferred = corpus.case("army-transfer-474").state
        assertEquals(GamePhase.InProgress, transferred.phase)
        assertEquals(setOf(PlayerId.WHITE, PlayerId.RED), transferred.participants.values
            .filter { it.status == ParticipantStatus.Active }.map { it.id }.toSet())
        assertEquals(ParticipantStatus.Checkmated(PlayerId.WHITE, 474),
            transferred.participants.getValue(PlayerId.BLACK).status)
        assertEquals(PlayerId.WHITE, transferred.armies.getValue(ArmyColor.BLACK).controller)
        assertTrue(transferred.position.pieces.values.any { it.army == ArmyColor.BLACK })
        assertTrue(transferred.position.pieces.values.none { it.army == ArmyColor.BLACK && it.type == PieceType.KING })
        assertEquals(PlayerId.WHITE, transferred.turn?.player)
        assertEquals(474, transferred.turn?.ply)
        assertTrue(LegalMoveGenerator.legalMoves(transferred).isNotEmpty())
    }

    @Test
    fun preservesFinalRankingAndNextTurnMatePlies() {
        val terminal = corpus.case("finished-474")
        assertEquals(GamePhase.Finished(GameOutcome.Ranked(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK)),
            terminal.state.phase)
        assertEquals(ParticipantStatus.Checkmated(PlayerId.WHITE, 474),
            terminal.state.participants.getValue(PlayerId.BLACK).status)
        assertEquals(ParticipantStatus.Checkmated(PlayerId.WHITE, 475),
            terminal.state.participants.getValue(PlayerId.RED).status)
        assertNull(terminal.state.turn)
        assertNull(terminal.recordedNextMove)
        assertTrue(LegalMoveGenerator.legalMoves(terminal.state).isEmpty())
        assertIs<MoveReduction.Rejected>(GameReducer.reduce(terminal.state, corpus.moves.last()))
    }

    @Test
    fun rejectsSourceChecksumAndMoveCountMismatch() {
        withInputs(sourceText = sourceText + "\n") { manifest, source ->
            assertFailure("Source SHA-256") { ReportedBotFixtures.load(manifest, source) }
        }
        withInputs(sourceText = sourceText.replaceFirst(corpus.moveRows.first() + "\n", ""), reseal = true) { manifest, source ->
            assertFailure("Move row count") { ReportedBotFixtures.load(manifest, source) }
        }
    }

    @Test
    fun rejectsWrongPrefixHashAndOutOfRangePrefix() {
        withInputs(manifestText = manifestText.replace(corpus.cases.first().prefixMoveRowsSha256, "0".repeat(64))) { manifest, source ->
            assertFailure("Prefix SHA-256") { ReportedBotFixtures.load(manifest, source) }
        }
        withInputs(manifestText = manifestText.replaceFirst("\"prefix_plies\": 138", "\"prefix_plies\": 475")) { manifest, source ->
            assertFailure("prefix out of range") { ReportedBotFixtures.load(manifest, source) }
        }
    }

    @Test
    fun rejectsMalformedMoveFieldsInsteadOfLosingPromotionOrInventingCoordinates() {
        listOf(
            "move|WHITE|1|2|3|1|1|3", // missing promotion field
            "move|WHITE|1|2|3|1|1|3|-|extra",
            "move|GREEN|1|2|3|1|1|3|-",
            "move|WHITE|6|2|3|1|1|3|-",
            "move|WHITE|x|2|3|1|1|3|-",
            "move|WHITE|1|2|3|1|1|3|KING",
            "move|WHITE|1|2|3|1|1|3|",
            "move|WHITE|1|2|3|1|2|3|-",
        ).forEach { row -> assertFailure("ply 12") { ReportedBotFixtures.parseMove(row, 12) } }
        assertEquals(PromotionChoice.KNIGHT,
            ReportedBotFixtures.parseMove("move|WHITE|3|2|1|3|3|1|KNIGHT", 1).promotion)
    }

    @Test
    fun rejectsIllegalPublicReducerReplayWithPlyInError() {
        val wrongActor = corpus.moveRows.first().replace("move|WHITE|", "move|RED|")
        withInputs(sourceText = sourceText.replaceFirst(corpus.moveRows.first(), wrongActor), reseal = true) { manifest, source ->
            assertFailure("Reducer rejected exported ply 1") { ReportedBotFixtures.load(manifest, source) }
        }
    }

    @Test
    fun rejectsMalformedOrAmbiguousManifest() {
        listOf(
            manifestText + " trailing",
            manifestText.replaceFirst("\"schema\":", "\"schema\": \"duplicate\", \"schema\":"),
            manifestText.replaceFirst("chesstree-agent-fixtures-v1", "unknown-schema"),
            manifestText.replaceFirst("queen-exposure-139", "queen-defense-142"),
            manifestText.replaceFirst("\"prefix_plies\": 138", "\"prefix_plies\": 1.38"),
            manifestText.replaceFirst("queen-exposure-139", "bad\\xescape"),
        ).forEach { malformed ->
            withInputs(manifestText = malformed) { manifest, source ->
                assertFailsWith<IllegalArgumentException> { ReportedBotFixtures.load(manifest, source) }
            }
        }
    }

    private fun assertFailure(fragment: String, action: () -> Unit) {
        val failure = assertFailsWith<IllegalArgumentException>(block = action)
        assertTrue(failure.message.orEmpty().contains(fragment), failure.message)
    }

    private fun withInputs(
        sourceText: String = Companion.sourceText,
        manifestText: String = Companion.manifestText,
        reseal: Boolean = false,
        action: (File, File) -> Unit,
    ) {
        val directory = Files.createTempDirectory("chesstree-reported-fixtures-").toFile()
        try {
            val source = File(directory, "reported-game.txt").apply { writeText(sourceText, Charsets.UTF_8) }
            var manifestContents = manifestText
            if (reseal) {
                manifestContents = manifestContents.replace(corpus.sourceSha256, ReportedBotFixtures.sha256(source.readBytes()))
                val rows = sourceText.lineSequence().filter { it.startsWith("move|") }.toList()
                corpus.cases.forEach { fixture ->
                    manifestContents = manifestContents.replace(fixture.prefixMoveRowsSha256,
                        ReportedBotFixtures.prefixHash(rows, fixture.prefixPlies))
                }
            }
            val manifest = File(directory, "fixtures.json").apply { writeText(manifestContents, Charsets.UTF_8) }
            action(manifest, source)
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private val corpus by lazy { ReportedBotFixtures.load() }
        private val sourceText by lazy { corpus.source.readText(Charsets.UTF_8) }
        private val manifestText by lazy { ReportedBotFixtures.manifestFile().readText(Charsets.UTF_8) }
    }
}
