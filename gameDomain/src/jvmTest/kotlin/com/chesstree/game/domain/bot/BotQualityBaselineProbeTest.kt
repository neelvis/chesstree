package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.ThreePlayerBoardNotation
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Opt-in corpus evidence. Recorded SAN is deliberately never a best-move oracle. */
class BotQualityBaselineProbeTest {
    private data class Case(
        val id: String,
        val group: String,
        val classification: String,
        val expectation: String,
        val history: List<GameState>,
        val prefix: String,
    ) {
        val state get() = history.last()
    }

    @Test
    fun heldOutSelectionIsFrozenLegalAndCoversAllRootSeats() {
        val cases = BotQualityHeldOutCases.all()
        assertEquals(6, cases.size)
        assertEquals(PlayerId.entries.toSet(), cases.map { it.state.turn?.player }.toSet())
        assertEquals(6, cases.map { it.state }.distinct().size)
        assertEquals(cases, BotQualityHeldOutCases.all())
    }

    @Test
    fun measureFrozenCorpus() {
        if (System.getenv("CHESSTREE_BOT_QUALITY_PROBE") != "true") return
        val corpus = ReportedBotFixtures.load()
        val root = File(git(File(System.getProperty("user.dir")), "rev-parse", "--show-toplevel"))
        val synthetic = BotQualitySyntheticCases.all()
        val cases = corpus.cases.map {
            Case(it.id, "reported", if (it.prefixPlies >= 472) "terminal-invariant" else "strategic-hypothesis",
                it.purpose, it.history, "reported:${it.prefixPlies}")
        } + synthetic.map {
            Case(it.id, "synthetic", it.classification, it.expectation, it.history, "constructor:${it.id}")
        } + BotQualityHeldOutCases.all().map {
            Case(it.id, "held-out", "strategic-hypothesis", "Legal reproducible choice; no golden move",
                it.history, it.moves.joinToString(";") { move -> move.token() })
        }
        assertTrue(cases.size >= 26)
        assertEquals(PlayerId.entries.toSet(), cases.mapNotNull { it.state.turn?.player }.toSet())
        assertTrue(cases.any { fixture -> fixture.state.participants.values.count {
            it.status == ParticipantStatus.Active
        } == 2 && fixture.state.phase == GamePhase.InProgress })

        val cap = (System.getenv("CHESSTREE_BOT_QUALITY_WORK") ?: "2000").toInt()
        require(cap in 1..1_000_000)
        val timed = System.getenv("CHESSTREE_BOT_QUALITY_TIMED") == "true"
        val contextSetting = System.getenv("CHESSTREE_BOT_QUALITY_KING_CONTEXT") ?: "true"
        require(contextSetting in setOf("false", "true", "both"))
        val contextVariants = if (contextSetting == "both") listOf(false, true) else listOf(contextSetting == "true")
        val repetitions = (System.getenv("CHESSTREE_BOT_QUALITY_REPEATS") ?: if (timed) "1" else "2").toInt()
        require(repetitions in 1..5)
        val selected = System.getenv("CHESSTREE_BOT_QUALITY_CASES")?.split(',')?.toSet()
        require(selected == null || selected.isNotEmpty() && selected.all { id -> cases.any { it.id == id } }) {
            "CHESSTREE_BOT_QUALITY_CASES must contain known case IDs"
        }
        val destination = File(System.getenv("CHESSTREE_BOT_QUALITY_OUTPUT")
            ?: "build/reports/bot-quality/oct19-baseline.tsv")
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { writer ->
            fun log(line: String) { writer.appendLine(line); writer.flush() }
            log("# schema\tbot-quality-baseline-v1")
            log("# source_commit\t${git(root, "rev-parse", "HEAD")}")
            log("# source_status\t${git(root, "status", "--porcelain").replace('\n', ';')}")
            log("# export_sha256\t${corpus.sourceSha256}")
            log("# runtime\t${System.getProperty("java.version")};${System.getProperty("os.name")};${System.getProperty("os.arch")}")
            log("# protocol\tseed=0;depth=4;tactical=4;model=PARANOID;eval=POSITIONAL:3;profile=UNIVERSAL:2;book=1;repetition=.50;history=36;cache=cold2048;savedMoveOrdering=false;reuseKingAttackContext=$contextSetting;nodes=$cap;evaluations=$cap;softMs=${if (timed) 30000 else "none"};repeats=$repetitions;order=alternating-by-case-and-repeat")
            log("# semantics\tFixed-work runs have no time cutoff; timed mode keeps the 30-second soft cap. Heuristic terms describe the selected immediate child, not the minimax leaf score. Terminal scores are excluded from term decomposition.")
            log("# interpretation\tNo recorded move is a best-move expectation. Held-out states were selected by seeded random legal trajectories before tuning; six trajectories from one start are not six independent strength trials.")
            val sources = listOf(
                "gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/bot",
                "gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/GameReducer.kt",
                "gameDomain/src/commonMain/kotlin/com/chesstree/game/domain/LegalMoveGenerator.kt",
                "gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotQualityHeldOutCases.kt",
                "gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotQualitySyntheticCases.kt",
                "gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotQualityBaselineProbeTest.kt",
                "gameDomain/src/jvmTest/kotlin/com/chesstree/game/domain/bot/ReportedBotFixtures.kt",
            ).flatMap { path -> File(root, path).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .sortedBy { it.relativeTo(root).path }
            check(sources.isNotEmpty())
            sources.forEach { log("# source_sha256\t${it.relativeTo(root).path}=${sha256(it.readBytes())}") }
            log("kind\tcase\tgroup\tclassification\tactor\trootLegal\trepeat\telapsedMs\tdepth\thorizon\tnodes\tevaluations\tmove\tstop\tsource\tcacheSearchHits\tcacheEvaluationHits\tcacheTransitionHits\tcacheEntries\tcacheEvictions\tdetail\treuseKingAttackContext")
            for ((caseIndex, fixture) in cases.filter { selected == null || it.id in selected }.withIndex()) {
                val before = fixture.state
                val actor = before.turn?.player
                val legal = if (before.phase == GamePhase.InProgress) LegalMoveGenerator.legalMoves(before) else emptyList()
                log("# fixture\t${fixture.id};prefix=${fixture.prefix};expectation=${fixture.expectation}")
                val results = mutableMapOf<Boolean, BoundedBotResult>()
                val traces = mutableMapOf<Boolean, BotSearchDiagnostics?>()
                val caches = mutableMapOf<Boolean, BotSearchCacheStats>()
                repeat(repetitions) { repeatIndex ->
                    val variants = if ((caseIndex + repeatIndex) % 2 == 0) contextVariants else contextVariants.reversed()
                    for (reuseKingAttacks in variants) {
                        val cache = BotSearchCache()
                        var diagnostics: BotSearchDiagnostics? = null
                        val started = System.nanoTime()
                        val result = BoundedMaxNBot(
                            maxDepth = 4, maxNodes = cap, maxEvaluations = cap,
                            evaluation = BotEvaluationMode.POSITIONAL, openingBookVersion = BotOpeningBook.VERSION,
                            seed = 0, tacticalDepth = 4, searchModel = BotSearchModel.PARANOID,
                            profile = BotPlayingProfile.UNIVERSAL, profileCatalogVersion = 2, cache = cache,
                        ).chooseWithDiagnostics(
                            before, diagnostics = { diagnostics = it },
                            reuseKingAttackContext = reuseKingAttacks,
                            recentPositions = fixture.history.takeLast(MAX_BOT_RECENT_POSITIONS),
                            stop = BotStopProbe {
                                if (timed && (System.nanoTime() - started) / 1_000_000 >= 30_000) BotStopSignal.TIMEOUT
                                else BotStopSignal.CONTINUE
                            },
                        )
                        val elapsedMs = (System.nanoTime() - started) / 1_000_000
                        if (!timed && repeatIndex > 0) {
                            assertEquals(results[reuseKingAttacks], result, "${fixture.id}: fixed-work result")
                            assertEquals(traces[reuseKingAttacks], diagnostics, "${fixture.id}: fixed-work attempts")
                            assertEquals(caches[reuseKingAttacks], cache.stats, "${fixture.id}: fixed-work cache")
                        }
                        results[reuseKingAttacks] = result
                        traces[reuseKingAttacks] = diagnostics
                        caches[reuseKingAttacks] = cache.stats
                        if (!timed && contextVariants.size == 2 && results.size == 2) {
                            assertEquals(results[false], results[true], "${fixture.id}: reuse must preserve fixed-work decision")
                            assertEquals(traces[false], traces[true], "${fixture.id}: reuse must preserve attempts")
                            assertEquals(caches[false], caches[true], "${fixture.id}: reuse must preserve cache behavior")
                        }
                        val stats = (result as? BoundedBotResult.Move)?.stats
                        val move = result as? BoundedBotResult.Move
                        if (move != null) {
                            assertTrue(move.stats.expandedNodes <= cap && move.stats.leafEvaluations <= cap)
                            val after = assertIs<MoveReduction.Applied>(GameReducer.reduce(before, move.intent)).state
                            log("# selected_child_terms\t${fixture.id};repeat=$repeatIndex;reuse=$reuseKingAttacks;${terms(after)}")
                        } else assertIs<BoundedBotResult.Terminal>(result)
                        log(listOf("result", fixture.id, fixture.group, fixture.classification, actor,
                            legal.size, repeatIndex, elapsedMs, stats?.completedDepth, stats?.completedHorizon,
                            stats?.expandedNodes, stats?.leafEvaluations, move?.intent?.token(), move?.reason,
                            move?.source ?: "TERMINAL", cache.stats.searchHits, cache.stats.evaluationHits,
                            cache.stats.transitionHits, cache.stats.entries, cache.stats.evictions, diagnostics, reuseKingAttacks,
                        ).joinToString("\t"))
                    }
                }
            }
            log(if (timed) "# status\tcomplete;timed outcomes may differ with scheduling"
                else "# status\tcomplete;fixed-work results/cache/attempts asserted identical across repeats and context variants")
        }
    }

    private fun terms(state: GameState): String {
        if (state.phase is GamePhase.Finished) return "terminal=${state.phase}"
        val status = BotEvaluationMode.CONTROL.evaluate(state, BotPolicy(emptyList()))
        val terms = BotFeature.entries.associateWith { feature ->
            BotEvaluationMode.CONTROL.evaluate(state, BotPolicy(BotPolicy.DEFAULT.rules.filter { it.feature == feature }))
        }
        val control = BotEvaluationMode.CONTROL.evaluate(state, BotPolicy.DEFAULT)
        val positional = BotEvaluationMode.POSITIONAL.evaluate(state, BotPolicy.DEFAULT)
        return PlayerId.entries.joinToString(";") { actor ->
            val i = actor.ordinal
            val sum = status[i] + terms.values.sumOf { it[i] - status[i] }
            assertEquals(control[i], sum, 0.000001, "Feature terms must reconcile with CONTROL")
            "$actor:status=${status[i]},${terms.entries.joinToString(",") { "${it.key}=${it.value[i] - status[i]}" }},positionalDelta=${positional[i] - control[i]},total=${positional[i]}"
        }
    }

    private fun git(directory: File, vararg args: String): String {
        val command = listOf("git", "-C", directory.absolutePath) + args
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun MoveIntent.token(): String = "$actor:${ThreePlayerBoardNotation.square(from)}>${ThreePlayerBoardNotation.square(to)}:${promotion ?: "-"}"
}
