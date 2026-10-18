package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Opt-in paired continuation from an owner-provided CHESSTREE|2 move export. */
class BotReportedGameStrategyProbeTest {
    @Test
    fun compareParanoidAndMaxNFromReportedPosition() {
        if (System.getenv("CHESSTREE_BOT_REPORTED_GAME_PROBE") != "true") return
        val report = File(checkNotNull(System.getenv("CHESSTREE_BOT_REPORTED_GAME_PATH")))
        val exportedMoves = report.readLines().filter { it.startsWith("move|") }
        var initial = StandardGame.scenario.initialState
        val history = mutableListOf(initial)
        exportedMoves.forEach { row ->
            val fields = row.split('|')
            val intent = BotOpeningBook.decode(
                "${PlayerId.valueOf(fields[1]).ordinal},${fields.subList(2, 8).joinToString(",")}",
            )
            initial = assertIs<MoveReduction.Applied>(GameReducer.reduce(initial, intent)).state
            history += initial
        }
        require(initial.phase == GamePhase.InProgress)

        val policy = BotPolicy(listOf(
            BotRule(BotFeature.MATERIAL, 100),
            BotRule(BotFeature.KING_SAFETY, 280),
            BotRule(BotFeature.CHECK_PRESSURE, 40),
            BotRule(BotFeature.MOBILITY, 3),
        ))
        val output = File("build/reports/bot-baseline/reported-game-models.tsv")
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine("# source\t${report.name};plies=${exportedMoves.size};sourceVersion=${report.readLines().first()}")
            writer.appendLine("# protocol\tpaired continuation;fixed maxNodes=250,maxEvaluations=400;depth=4;q=4;policy from export")
            writer.appendLine("model\tcoefficient\tply\tactor\tdepth\tfallback\trepeatedLayout\trepetitionPenalty\tpawnMoves\tminorMoves\tcaptures\tevaluations\tnodes\treason\tmove")
            listOf(BotSearchModel.PARANOID, BotSearchModel.MAX_N).forEach { model ->
                listOf(0.12, DEFAULT_REPEATED_LAYOUT_PENALTY_PER_OCCURRENCE).forEach { coefficient ->
                var state = initial
                val recent = history.toMutableList()
                var repeatedReturns = 0
                var fallbacks = 0
                repeat(18) {
                    if (state.phase != GamePhase.InProgress) return@repeat
                    val actor = checkNotNull(state.turn?.player)
                    val before = state
                    val result = assertIs<BoundedBotResult.Move>(BoundedMaxNBot(
                        maxDepth = 4,
                        maxNodes = 250,
                        maxEvaluations = 400,
                        evaluation = BotEvaluationMode.POSITIONAL,
                        tacticalDepth = 4,
                        openingBookVersion = 1,
                        searchModel = model,
                        profile = BotPlayingProfile.UNIVERSAL,
                        profileCatalogVersion = 2,
                        repeatedLayoutPenaltyPerOccurrence = coefficient,
                    ).choose(
                        before,
                        policy,
                        recentPositions = recent.takeLast(MAX_BOT_RECENT_POSITIONS),
                    ))
                    val movingPiece = before.position.pieces.values.single { it.coordinate == result.intent.from }
                    val transition = assertIs<MoveReduction.Applied>(GameReducer.reduce(before, result.intent))
                    state = transition.state
                    val repeated = transition.move.capturedPieceId == null && result.intent.promotion == null &&
                        recent.takeLast(MAX_BOT_RECENT_POSITIONS).any { actorLayout(it, actor) == actorLayout(state, actor) }
                    if (repeated) repeatedReturns++
                    if (result.source == BotMoveSource.LEGAL_FALLBACK) fallbacks++
                    recent += state
                    writer.appendLine(listOf(
                        model,
                        coefficient,
                        checkNotNull(before.turn).ply,
                        actor,
                        result.stats.completedDepth,
                        result.source == BotMoveSource.LEGAL_FALLBACK,
                        repeated,
                        result.repetitionPenalty,
                        movingPiece.type == PieceType.PAWN,
                        movingPiece.type == PieceType.KNIGHT || movingPiece.type == PieceType.BISHOP,
                        transition.move.capturedPieceId != null,
                        result.stats.leafEvaluations,
                        result.stats.expandedNodes,
                        result.reason,
                        "${result.intent.from}>${result.intent.to}",
                    ).joinToString("\t"))
                }
                if (model == BotSearchModel.PARANOID && coefficient == 0.12) {
                    assertEquals(2, repeatedReturns, "The baseline Paranoid continuation should reproduce the reported return loop")
                    assertEquals(0, fallbacks, "The baseline fixed-work continuation must keep a searched legal choice")
                }
                if (model == BotSearchModel.PARANOID && coefficient == DEFAULT_REPEATED_LAYOUT_PENALTY_PER_OCCURRENCE) {
                    assertEquals(0, repeatedReturns, "The tuned Paranoid continuation must break the reported return loop")
                    assertEquals(0, fallbacks, "The tuned fixed-work continuation must keep a searched legal choice")
                }
                if (model == BotSearchModel.MAX_N && coefficient == 0.12) {
                    assertEquals(4, repeatedReturns, "The baseline MaxN continuation should reproduce its return count")
                    assertEquals(3, fallbacks, "The baseline MaxN continuation should reproduce its fallback count")
                }
                if (model == BotSearchModel.MAX_N && coefficient == DEFAULT_REPEATED_LAYOUT_PENALTY_PER_OCCURRENCE) {
                    assertEquals(3, repeatedReturns, "The tuned MaxN continuation should reproduce its return count")
                    assertEquals(2, fallbacks, "The tuned MaxN continuation should reproduce its fallback count")
                }
                val material = PlayerId.entries.joinToString(";") { player ->
                    val pieces = state.position.pieces.values.filter {
                        state.armies.getValue(it.army).controller == player
                    }
                    val value = pieces.sumOf { piece ->
                        when (piece.type) {
                            PieceType.PAWN -> 1
                            PieceType.KNIGHT, PieceType.BISHOP -> 3
                            PieceType.ROOK -> 5
                            PieceType.QUEEN -> 9
                            PieceType.KING -> 0
                        }
                    }
                    "$player:${pieces.size}:$value:${state.participants.getValue(player).status}"
                }
                writer.appendLine("# ending-$model-$coefficient\tphase=${state.phase}\tmaterial=$material;repeatedReturns=$repeatedReturns;fallbacks=$fallbacks")
                }
            }
        }
    }

    private fun actorLayout(state: com.chesstree.game.domain.GameState, actor: PlayerId): String {
        val pieces = state.position.pieces.filterValues {
            state.armies.getValue(it.army).controller == actor
        }
        val own = pieces.entries.sortedBy { it.key.value }
            .joinToString(";") { (id, piece) -> "${id.value}:${piece.coordinate}:${piece.type}:${piece.army}" }
        val identities = state.position.pieces.values.sortedBy { it.id.value }
            .joinToString(";") { "${it.id.value}:${it.type}:${it.army}" }
        val controlledRights = state.position.castlingRights.filter {
            state.armies.getValue(it.army).controller == actor
        }.sortedBy { "${it.army}:${it.side}:${it.rookId?.value}" }
        val enPassant = state.position.enPassantTargets.values.sortedBy { it.pawnId.value }
        return "${state.turn?.player}|${state.phase}|${state.participants}|${state.armies}|$identities|$own|$controlledRights|$enPassant"
    }
}
