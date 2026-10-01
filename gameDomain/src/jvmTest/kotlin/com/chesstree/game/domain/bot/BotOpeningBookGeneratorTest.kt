package com.chesstree.game.domain.bot

import com.chesstree.game.domain.DirectionKind
import com.chesstree.game.domain.MovementDirections
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test

/** Opt-in offline generation; never called by product lookup. */
class BotOpeningBookGeneratorTest {
    @Test
    fun generateExperimentalCorpus() {
        if (System.getenv("BOT_GENERATE_OPENING_BOOK") != "1") return
        val entries = linkedMapOf<String, MutableList<String>>()
        val lines = mutableListOf<List<String>>()
        repeat(6) { branch ->
            val family = branch % 3
            val pawnDistance = if (branch < 3) 2 else 1
            var state = StandardGame.scenario.initialState
            val line = mutableListOf<String>()
            repeat(9) { ply ->
                val actor = checkNotNull(state.turn).player
                val legal = LegalMoveGenerator.legalMoves(state, actor)
                val desired = when (ply / 3) {
                    0 -> PieceType.PAWN
                    1 -> PieceType.KNIGHT
                    else -> PieceType.BISHOP
                }
                val candidates = legal.filter { state.position.pieces.getValue(it.pieceId).type == desired }
                    .sortedWith(compareBy<Move>({ it.pieceId.value }, { it.to.vertex }, { it.to.column }, { it.to.row }))
                check(candidates.isNotEmpty()) { "No $desired development at branch=$branch ply=$ply" }
                fun opensBishop(move: Move): Boolean {
                    val applied = GameReducer.reduce(state, MoveIntent(actor, move.from, move.to, move.promotion))
                    check(applied is MoveReduction.Applied)
                    return LegalMoveGenerator.legalMoves(applied.state, actor).any {
                        applied.state.position.pieces.getValue(it.pieceId).type == PieceType.BISHOP
                    }
                }
                val selected = when (ply / 3) {
                    0 -> {
                        val doubles = candidates.filter { move ->
                            val pawn = state.position.pieces.getValue(move.pieceId)
                            MovementDirections.forPiece(PieceType.PAWN, pawn.coordinate, pawn.army).any {
                                it.kind == DirectionKind.MOVE && it.route.getOrNull(2) == move.to
                            }
                        }.filter(::opensBishop)
                        check(doubles.size >= 3) { "Need three bishop-opening double pawn moves for $actor" }
                        val familyMove = doubles[family]
                        if (pawnDistance == 2) familyMove else {
                            val pawn = state.position.pieces.getValue(familyMove.pieceId)
                            val oneStep = MovementDirections.forPiece(PieceType.PAWN, pawn.coordinate, pawn.army)
                                .first { it.kind == DirectionKind.MOVE && it.route.getOrNull(2) == familyMove.to }
                                .route[1]
                            candidates.single { it.pieceId == pawn.id && it.to == oneStep }.also {
                                check(opensBishop(it)) { "Single advance must open bishop access for $actor family=$family" }
                            }
                        }
                    }
                    1 -> {
                        val preserving = candidates.filter(::opensBishop)
                        check(preserving.isNotEmpty()) { "No knight development preserves bishop access" }
                        preserving[(family + actor.ordinal) % preserving.size]
                    }
                    else -> candidates[(family + actor.ordinal) % candidates.size]
                }
                val intent = MoveIntent(actor, selected.from, selected.to, selected.promotion)
                check(intent.promotion == null)
                val token = intent.token()
                val signature = checkNotNull(BotOpeningBook.signature(state))
                val choices = entries.getOrPut(signature) { mutableListOf() }
                if (token !in choices) choices += token
                line += token
                val result = GameReducer.reduce(state, intent)
                check(result is MoveReduction.Applied) { "Public reducer rejected branch=$branch ply=$ply" }
                state = result.state
            }
            lines += line
        }
        val source = buildString {
            appendLine("package com.chesstree.game.domain.bot")
            appendLine()
            appendLine("// Generated offline through GameReducer from StandardGame; see opening-repertoire.md.")
            appendLine("// Reproduce with BOT_GENERATE_OPENING_BOOK=1 and BotOpeningBookGeneratorTest.")
            appendLine("internal object BotOpeningBookData {")
            appendLine("    val entries: Map<String, List<String>> = mapOf(")
            entries.forEach { (signature, tokens) ->
                appendLine("        \"$signature\" to listOf(${tokens.joinToString { "\"$it\"" }}),")
            }
            appendLine("    )")
            appendLine("    val lines: List<List<String>> = listOf(")
            lines.forEach { line -> appendLine("        listOf(${line.joinToString { "\"$it\"" }}),") }
            appendLine("    )")
            appendLine("}")
        }
        File("src/commonMain/kotlin/com/chesstree/game/domain/bot/BotOpeningBookData.kt").writeText(source)
    }

    private fun MoveIntent.token(): String = listOf(
        actor.ordinal, from.vertex, from.column, from.row, to.vertex, to.column, to.row,
    ).joinToString(",")
}
