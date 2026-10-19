package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.ThreePlayerBoardNotation
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Verified legal continuations plus an opt-in expanded report; no best-move or forced-loss oracle. */
class BotStage1TacticalEvidenceTest {
    @Test
    fun everyQueen145EvasionAllowsTheExplicitTwoOpponentPunishment() {
        verifyQueen145Punishments()
    }

    @Test
    fun queen142HasAFiniteQueenPreservingDefenseThroughBothOpponents() {
        verifyQueen142Defense()
    }

    @Test
    fun promotionTradesTheOriginalPawnForRedsQueenThroughBothOpponents() {
        verifyPromotionTrade()
    }

    @Test
    fun inspectReportedQueenAndPromotionTactics() {
        if (System.getenv("CHESSTREE_BOT_STAGE1_EVIDENCE") != "true") return
        val output = File(System.getenv("CHESSTREE_BOT_STAGE1_EVIDENCE_OUTPUT")
            ?: "build/reports/bot-quality/stage1-tactical-evidence.tsv")
        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            fun log(vararg fields: Any?) { writer.appendLine(fields.joinToString("\t")); writer.flush() }
            log("# source_sha256", corpus.sourceSha256)
            log("# semantics", "All lines use public reducer and actual turn order; recorded replies are legal examples, not forced policies.")
            for (id in listOf("queen-exposure-139", "queen-defense-142", "queen-defense-145",
                "promotion-295", "promotion-capture-298")) {
                val fixture = corpus.case(id)
                val state = fixture.state
                val legal = LegalMoveGenerator.legalMoves(state)
                log("fixture", id, "actor=${state.turn?.player}", "legal=${legal.size}",
                    "checks=${checks(state)}", "material=${material(state)}")
                log("pieces", id, state.position.pieces.values.sortedBy { it.id.value }.joinToString(";") {
                    "${it.id}:${it.army}:${it.type}:${square(it.coordinate)}"
                })
                legal.forEach { move ->
                    val after = apply(state, move.intent()).state
                    assertFalse(LegalMoveGenerator.isKingInCheck(after, move.actor))
                    log("legal", id, move.intent().token(), "capture=${move.capturedPieceId}",
                        "checks=${checks(after)}", "queen=${after.position.pieces[queenId]?.coordinate?.let(::square)}",
                        "material=${material(after)}")
                }
            }

            // The recorded loss is a concrete three-actor continuation, not proof that Qf2 loses by force.
            var queenLine = corpus.case("queen-exposure-139").state
            for (ply in 139..148) {
                val intent = corpus.moves[ply - 1]
                val reduced = apply(queenLine, intent)
                if (ply == 147) assertEquals(queenId, reduced.move.capturedPieceId)
                queenLine = reduced.state
                log("queen-recorded", ply, intent.token(), "captured=${reduced.move.capturedPieceId}",
                    "queenPresent=${queenId in queenLine.position.pieces}", "material=${material(queenLine)}")
            }
            assertFalse(queenId in queenLine.position.pieces)

            verifyQueen145Punishments { writer.appendLine(it); writer.flush() }

            verifyQueen142Defense { writer.appendLine(it); writer.flush() }

            // For each root alternative, replay both recorded opponents only when still reducer-legal.
            // A surviving queen refutes only that concrete continuation, not every opponent defense.
            for (prefix in listOf(138, 141, 144)) {
                val root = corpus.history[prefix]
                LegalMoveGenerator.legalMoves(root).forEach { first ->
                    var after = apply(root, first.intent()).state
                    var replyFailure: String? = null
                    for (replyIndex in (prefix + 1)..(prefix + 2)) {
                        val intent = corpus.moves[replyIndex]
                        when (val reduced = GameReducer.reduce(after, intent)) {
                            is MoveReduction.Applied -> after = reduced.state
                            is MoveReduction.Rejected -> {
                                replyFailure = "ply=${replyIndex + 1}:${reduced.reason}"
                                break
                            }
                        }
                    }
                    val queenMoves = if (replyFailure == null) LegalMoveGenerator.legalMoves(after).filter {
                        it.pieceId == queenId
                    }.map { it.intent().token() } else emptyList()
                    log("two-opponent-line", "rootPly=${prefix + 1}", first.intent().token(),
                        "recordedReplies=${replyFailure ?: "applied"}", "nextActor=${after.turn?.player}",
                        "queenPresent=${queenId in after.position.pieces}", "checks=${checks(after)}",
                        "nextLegalQueenMoves=${queenMoves.joinToString(";")}")
                }
            }

            verifyPromotionTrade { writer.appendLine(it); writer.flush() }

            log("# status", "complete;all stated recorded continuations reducer-applied;alternatives remain conditional on listed replies")
        }
    }

    private fun verifyQueen145Punishments(log: (String) -> Unit = {}) {
        // All actual 145 evasions lose the queen against this explicit RED/BLACK response.
        // This is conditional on RED's reply, not a claim that independent RED must cooperate.
        val defense145 = corpus.case("queen-defense-145").state
        assertTrue(LegalMoveGenerator.isKingInCheck(defense145, PlayerId.WHITE))
        val evasions = LegalMoveGenerator.legalMoves(defense145)
        assertEquals(setOf("WHITE:h2>h3:-", "WHITE:h2>g2:-", "WHITE:h2>g1:-"),
            evasions.map { it.intent().token() }.toSet())
        evasions.forEach { evasion ->
            var after = apply(defense145, evasion.intent()).state
            after = apply(after, corpus.moves[145]).state // RED's actual Rc8.
            val punishment = apply(after, corpus.moves[146]) // BLACK's actual Nxf2.
            assertEquals(queenId, punishment.move.capturedPieceId)
            assertFalse(queenId in punishment.state.position.pieces)
            assertEquals(PlayerId.WHITE, punishment.state.turn?.player)
            emit(log, "verified-145-punishment", evasion.intent().token(),
                corpus.moves[145].token(), corpus.moves[146].token(), "queenCaptured=true")
        }
    }

    private fun verifyQueen142Defense(log: (String) -> Unit = {}) {
        // A concrete earlier defense preserves the queen through both real opponent turns.
        // Therefore the recorded Qf2 line alone cannot establish a forced queen loss at 139/142.
        var defended = corpus.case("queen-defense-142").state
        val defendedLine = listOf(
            intent(PlayerId.WHITE, "f2", "f1"), // 142: Qf1 instead of Kh2.
            corpus.moves[142], // 143: RED Bm7.
            corpus.moves[143], // 144: BLACK Ng4; WHITE king is still h3, so this is not check.
            intent(PlayerId.WHITE, "f1", "e1"),
            corpus.moves[145], // 146: RED Rc8.
            corpus.moves[146], // 147: BLACK Nf2, now an empty-square move checking h3.
            intent(PlayerId.WHITE, "e1", "f2"), // 148: Qxf2 answers that check.
        )
        defendedLine.forEachIndexed { index, intent ->
            val reduced = apply(defended, intent)
            defended = reduced.state
            assertTrue(queenId in defended.position.pieces)
            emit(log, "verified-142-defense", 142 + index, intent.token(),
                "captured=${reduced.move.capturedPieceId}", "checks=${checks(defended)}")
        }
        assertEquals(35, material(defended).getValue(PlayerId.WHITE))
        assertEquals(36, material(defended).getValue(PlayerId.BLACK))
    }

    private fun verifyPromotionTrade(log: (String) -> Unit = {}) {
        // The first promotion trades this pawn, after promotion, for Red's original queen.
        val promotionRoot = corpus.case("promotion-295").state
        val promotionIntent = corpus.moves[294]
        assertEquals(PromotionChoice.QUEEN, promotionIntent.promotion)
        val pawnId = promotionRoot.position.pieces.values.single { it.coordinate == promotionIntent.from }.id
        assertEquals(PieceType.PAWN, promotionRoot.position.pieces.getValue(pawnId).type)
        val redQueenId = promotionRoot.position.pieces.values.single {
            it.army == ArmyColor.RED && it.type == PieceType.QUEEN
        }.id
        var promotionLine = promotionRoot
        for (ply in 295..301) {
            val intent = corpus.moves[ply - 1]
            val reduced = apply(promotionLine, intent)
            if (ply == 295) assertEquals(PieceType.QUEEN, reduced.state.position.pieces.getValue(pawnId).type)
            if (ply == 298) assertEquals(redQueenId, reduced.move.capturedPieceId)
            if (ply == 300) assertEquals(pawnId, reduced.move.capturedPieceId)
            promotionLine = reduced.state
            emit(log, "promotion-recorded", ply, intent.token(), "captured=${reduced.move.capturedPieceId}",
                "material=${material(promotionLine)}", "checks=${checks(promotionLine)}")
        }
        assertFalse(pawnId in promotionLine.position.pieces)
        assertFalse(redQueenId in promotionLine.position.pieces)
        val beforeWhite = material(promotionRoot).getValue(PlayerId.WHITE)
        val beforeRed = material(promotionRoot).getValue(PlayerId.RED)
        assertEquals(beforeWhite - 1, material(promotionLine).getValue(PlayerId.WHITE))
        assertEquals(beforeRed - 9, material(promotionLine).getValue(PlayerId.RED))
        assertEquals(material(promotionRoot).getValue(PlayerId.BLACK), material(promotionLine).getValue(PlayerId.BLACK))
        assertTrue(listOf(PlayerId.WHITE, PlayerId.RED, PlayerId.BLACK).all { actor ->
            corpus.moves.subList(294, 301).any { it.actor == actor }
        })
    }

    private val queenId get() = corpus.history[138].position.pieces.values.single {
        it.army == ArmyColor.WHITE && it.type == PieceType.QUEEN
    }.id

    private fun emit(log: (String) -> Unit, vararg fields: Any?) = log(fields.joinToString("\t"))

    private fun material(state: GameState): Map<PlayerId, Int> = PlayerId.entries.associateWith { actor ->
        state.position.pieces.values.filter { state.armies.getValue(it.army).controller == actor }.sumOf { piece ->
            when (piece.type) {
                PieceType.KING -> 0
                PieceType.QUEEN -> 9
                PieceType.ROOK -> 5
                PieceType.BISHOP, PieceType.KNIGHT -> 3
                PieceType.PAWN -> 1
            }
        }
    }

    private fun checks(state: GameState): String = PlayerId.entries.filter {
        LegalMoveGenerator.isKingInCheck(state, it)
    }.joinToString(",")

    private fun apply(state: GameState, intent: MoveIntent): MoveReduction.Applied =
        assertIs<MoveReduction.Applied>(GameReducer.reduce(state, intent), "Rejected actual continuation $intent")

    private fun square(coordinate: BoardCoordinate): String =
        ThreePlayerBoardNotation.square(coordinate).toString()

    private fun intent(actor: PlayerId, from: String, to: String): MoveIntent = MoveIntent(
        actor, checkNotNull(ThreePlayerBoardNotation.parse(from)), checkNotNull(ThreePlayerBoardNotation.parse(to)),
    )

    companion object {
        private val corpus by lazy { ReportedBotFixtures.load() }
    }

    private fun Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)
    private fun MoveIntent.token(): String = "$actor:${square(from)}>${square(to)}:${promotion ?: "-"}"
}
