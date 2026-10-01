package com.chesstree.game.domain.bot

import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.LegalMoveGenerator
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import java.io.BufferedWriter
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** E2 development pilot only: two dependent origins cannot establish release strength. */
class BotArenaPilotTest {
    @Test
    fun mixedMaterialFixtureGuardsRemainAuthoritative() {
        val failureMessages = mutableListOf<String>()
        val destination = reportFile("arena-fixture-guards.tsv")
        destination.bufferedWriter().use { writer ->
            val report = Report(writer)
            report.note("scope", "two synthetic mixed-material guards;not included in 48 Arena games;no objectively good-move claim")
            listOf(false, true).forEach { checked ->
                val state = ArenaPilotFixtures.mixedMaterial(checked)
                val id = if (checked) "mixed-material-checked" else "mixed-material-quiet"
                report.row("fixture", detail = "id=$id;initial_full_state=${stateText(state)}")
                try {
                    val legal = LegalMoveGenerator.legalMoves(state)
                    val pawn = MoveIntent(PlayerId.WHITE, ArenaPilotFixtures.at("K9"), ArenaPilotFixtures.at("K10"))
                    assertEquals(checked, LegalMoveGenerator.isKingInCheck(state, PlayerId.WHITE))
                    if (checked) {
                        assertFalse(legal.any { it.intent() == pawn })
                        assertIs<MoveReduction.Rejected>(GameReducer.reduce(state, pawn))
                        assertTrue(legal.isNotEmpty())
                        assertTrue(legal.all { state.position.pieces.getValue(it.pieceId).type == PieceType.KING })
                        val escape = MoveIntent(PlayerId.WHITE, ArenaPilotFixtures.at("H12"), ArenaPilotFixtures.at("G12"))
                        val applied = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, escape))
                        assertFalse(LegalMoveGenerator.isKingInCheck(applied.state, PlayerId.WHITE))
                    } else {
                        assertTrue(legal.any { it.intent() == pawn })
                        val applied = assertIs<MoveReduction.Applied>(GameReducer.reduce(state, pawn))
                        assertFalse(LegalMoveGenerator.isKingInCheck(applied.state, PlayerId.WHITE))
                    }
                    assertEquals(
                        BotEvaluationMode.CONTROL.evaluate(state, BotPolicy.DEFAULT)[PlayerId.WHITE.ordinal],
                        BotEvaluationMode.PAWN_PROGRESS.evaluate(state, BotPolicy.DEFAULT)[PlayerId.WHITE.ordinal],
                        "WHITE mixed material must disable its pawn-only bonus",
                    )
                    BotEvaluationMode.entries.forEach { mode ->
                        val agent = Agent("fixture-guard", mode, BotPolicy.DEFAULT)
                        val search = search(state, agent, FIXED_WORK)
                        val move = assertIs<BoundedBotResult.Move>(search.result)
                        validateMove(state, agent, FIXED_WORK, move)
                        if (checked) assertEquals(PieceType.KING, state.position.pieces.values.single {
                            it.coordinate == move.intent.from
                        }.type)
                        report.row(
                            "fixture_choice", actor = move.intent.actor, agent = agent,
                            intent = move.intent, result = move, elapsed = search.elapsed,
                            detail = "id=$id;checked=$checked;validated=true;not_a_tournament_win",
                        )
                    }
                    report.row("fixture_validation", detail = "id=$id;passed=true;WHITE_progress_gate=inactive")
                } catch (failure: AssertionError) {
                    failureMessages += "$id:${failure.message}"
                    report.row("fixture_validation", detail = "id=$id;passed=false;failure=${failure.message}")
                } catch (failure: Exception) {
                    failureMessages += "$id:${failure.message}"
                    report.row("fixture_validation", detail = "id=$id;passed=false;failure=${failure.javaClass.name}:${failure.message}")
                }
                report.flush()
            }
        }
        assertTrue(failureMessages.isEmpty(), failureMessages.joinToString("; "))
    }

    @Test
    fun optInFrozenFortyEightGamePilot() {
        val enabled = System.getProperty("chesstree.bot.arenaPilot").equals("true", ignoreCase = true) ||
                System.getenv("CHESSTREE_BOT_ARENA_PILOT").equals("true", ignoreCase = true)
        if (!enabled) {
            reportFile("arena-pilot-skip.tsv").writeText(
                "status\treason\nSkipped\tEnable CHESSTREE_BOT_ARENA_PILOT=true or Test JVM property chesstree.bot.arenaPilot=true;no games measured;existing arena-pilot.tsv may belong to an earlier invocation\n",
            )
            println("Arena pilot skipped: explicit opt-in absent; see build/reports/bot-baseline/arena-pilot-skip.tsv")
            return
        }

        val failures = mutableListOf<String>()
        val results = mutableListOf<GameResult>()
        val destination = reportFile("arena-pilot.tsv")
        destination.bufferedWriter().use { writer ->
            val report = Report(writer)
            report.note("schema", "bot-arena-pilot-v1")
            report.note("status", "Running;manifest frozen before warmup/measurement;see final status for completeness")
            report.note("scope", "E2 pilot;48 games;2 origins;no confidence interval/noninferiority/superiority/release acceptance claim")
            report.note("reward_version", "arena-reward-v1;ranked=1/.5/0;two-way=.75/.75/0;three-way=.5 each")
            report.note("unfinished", "Unfinished(MoveCap);60 applied plies;no draw/placement/reward fabricated;failure is separate")
            report.note("randomness", "none;seed_slot=none;no learned policies;seat identities are not three independent algorithms")
            report.note("opt_in", "property=${System.getProperty("chesstree.bot.arenaPilot")};environment=${System.getenv("CHESSTREE_BOT_ARENA_PILOT")}")
            report.note("runtime", "java=${System.getProperty("java.version")};os=${System.getProperty("os.name")};os_version=${System.getProperty("os.version")};arch=${System.getProperty("os.arch")}")
            report.note("legacy", "MaxNBot external diagnostic only;not a timed opponent;all Arena agents use BoundedMaxNBot")
            report.note("replay", "strict public GameReducer.reduce per intent, including automatic outcomes;GameState equality plus canonical full-state equality;no permissive SAN restoration")
            report.note("state_encoding", "arena-full-state-v1;explicit sorted pieces/flags,castling,all en-passant fields,participants/controllers,turn/ply,phase/outcome;constructor and source hashes below anchor replay")
            report.note("hash_scope", "checked-out source bytes captured before measurement;not compiled-bytecode attestation")
            val sourceHashes = sourcePaths.associateWith { sha256(sourceFile(it).readBytes()) }
            sourceHashes.forEach { (path, hash) -> report.note("source_sha256", "$path=$hash") }
            report.note("source_manifest_sha256", sha256(sourceHashes.entries.joinToString("\n") { "${it.key}=${it.value}" }.toByteArray(Charsets.UTF_8)))
            report.note("engine_version", "source-sha256:${sourceHashes.getValue("src/commonMain/kotlin/com/chesstree/game/domain/bot/BoundedMaxNBot.kt")};eval_version recorded per agent")
            report.note("rules_version", "source-sha256:${sourceHashes.getValue("src/commonMain/kotlin/com/chesstree/game/domain/GameReducer.kt")};other rule sources in manifest;not a deployed rules codec version")
            val starts = ArenaPilotFixtures.starts()
            val games = schedule(starts)
            report.note("schedule_sha256", sha256(games.joinToString("\n") { it.manifest() }.toByteArray(Charsets.UTF_8)))
            report.note("game_count", games.size.toString())
            protocols.forEach { report.note("protocol", it.manifest()) }
            listOf(CONTROL_FOCAL, PROGRESS_FOCAL, GREEDY, CONTROL_REFERENCE).forEach { agent ->
                report.note("agent", "${agent.id};evaluation=${agent.evaluation.name};eval_version=${agent.evaluation.version};depth=${if (agent.greedy) "1" else "protocol"};policy=${BotPolicyCodec.encode(agent.policy).trim().replace('\n', ';')}")
            }
            starts.forEach { start ->
                report.row("origin", detail = "id=${start.id};block=${blockId(start)};provenance=${start.provenance};initial_full_state=${stateText(start.state)}")
            }
            games.forEach { game -> report.row("game_manifest", game, detail = game.manifest()) }
            report.flush()

            if (games.size != 48 || games.map(GameSpec::id).distinct().size != 48) {
                failures += "Frozen schedule must contain exactly 48 distinct games"
            } else {
                // Both starts and focal evaluations receive the same predeclared warmup.
                starts.forEach { start ->
                    listOf(CONTROL_FOCAL, PROGRESS_FOCAL).forEach { agent ->
                        val warm = search(start.state, agent, TIMED)
                        report.row("warmup", agent = agent, elapsed = warm.elapsed, detail = "origin=${start.id};not_in_game_count;result=${warm.result}")
                        report.flush()
                    }
                }
                games.forEach { game ->
                    val result = play(game, report)
                    results += result
                    if (result.outcome is PilotOutcome.Failed) failures += "${game.id}:${result.outcome.detail}"
                    report.flush()
                }
            }

            summarize(results, report)
            if (results.size != 48) failures += "Measured ${results.size}/48 games"
            report.note("final_status", "games=${results.size};failures=${failures.size};complete=${results.size == 48};assertions_pass=${failures.isEmpty()}")
            failures.forEach { report.note("failure", it) }
            report.flush()
        }
        // Persist every diagnostic outcome before asserting final acceptance of the harness run.
        assertEquals(48, results.size, "Expected frozen 48-game pilot; report=$destination")
        assertTrue(failures.isEmpty(), "Arena failures (${failures.size}); report=$destination;${failures.joinToString("; ")}")
        println("Arena pilot recorded 48 games; see ${destination.absolutePath}; no release strength claim")
    }

    private fun play(game: GameSpec, report: Report): GameResult {
        var state = game.start.state
        val moves = mutableListOf<MoveIntent>()
        val initial = stateText(state)
        var outcome: PilotOutcome? = null
        try {
            while (state.phase == GamePhase.InProgress && moves.size < MOVE_CAP) {
                val actor = checkNotNull(state.turn).player
                val agent = game.seats.getValue(actor)
                val before = stateText(state)
                val searched = search(state, agent, game.protocol)
                val result = searched.result
                if (result !is BoundedBotResult.Move) {
                    report.row("search_failure", game, state.turn?.ply, actor, agent, elapsed = searched.elapsed, detail = "result=$result;state=${stateText(state)}")
                    outcome = PilotOutcome.Failed("InProgress search returned $result")
                    break
                }
                report.row(
                    "search_result", game, checkNotNull(state.turn).ply, actor, agent, result.intent,
                    result, searched.elapsed, "step=${moves.size + 1};validation=pending",
                )
                report.flush()
                val problem = validateMove(state, agent, game.protocol, result)
                check(stateText(state) == before) { "Search mutated input state" }
                check(problem.state.phase != GamePhase.InProgress || !LegalMoveGenerator.isKingInCheck(problem.state, actor)) {
                    "Applied legal intent left acting king unsafe"
                }
                report.row(
                    "ply", game, checkNotNull(state.turn).ply, actor, agent, result.intent,
                    result, searched.elapsed,
                    "step=${moves.size + 1};before_sha256=${sha256(before.toByteArray(Charsets.UTF_8))};" +
                            "after_sha256=${sha256(stateText(problem.state).toByteArray(Charsets.UTF_8))};" +
                            "elapsed_over_soft=${game.protocol.budgetNanos?.let { searched.elapsed > it } ?: false};" +
                            "move_type=${problem.move.type};captured=${problem.move.capturedPieceId};promotion=${problem.move.promotion};phase_after=${problem.state.phase}",
                )
                moves += result.intent
                state = problem.state
                report.flush()
            }
            if (outcome == null) {
                outcome = when (val phase = state.phase) {
                    is GamePhase.Finished -> PilotOutcome.Finished(phase.outcome)
                    GamePhase.InProgress -> PilotOutcome.Unfinished("MoveCap")
                }
            }
            check(stateText(game.start.state) == initial) { "Game mutated frozen initial state" }
            var replayed = game.start.state
            moves.forEach { intent ->
                val reduction = GameReducer.reduce(replayed, intent)
                check(reduction is MoveReduction.Applied) { "Strict replay rejected $intent" }
                replayed = reduction.state
            }
            check(replayed == state) { "Strict replay did not restore GameState equality" }
            check(stateText(replayed) == stateText(state)) { "Strict replay did not restore canonical complete final state" }
            report.row("replay", game, detail = "strict=true;plies=${moves.size};game_state_equal=true;canonical_full_state_equal=true")
        } catch (failure: Exception) {
            outcome = PilotOutcome.Failed("${failure.javaClass.name}:${failure.message}")
            report.row("game_failure", game, detail = "failure=$outcome")
        }
        val actualOutcome = checkNotNull(outcome)
        val rewards = (actualOutcome as? PilotOutcome.Finished)?.let { rewards(it.raw) }
        report.row(
            "game_result", game,
            detail = "plies=${moves.size};outcome=$actualOutcome;raw_outcome=${(actualOutcome as? PilotOutcome.Finished)?.raw ?: "none"};" +
                    "reward_version=arena-reward-v1;white=${rewards?.get(PlayerId.WHITE) ?: "none"};" +
                    "red=${rewards?.get(PlayerId.RED) ?: "none"};black=${rewards?.get(PlayerId.BLACK) ?: "none"};" +
                    "initial_full_state=${stateText(game.start.state)};final_full_state=${stateText(state)}",
        )
        return GameResult(game, actualOutcome, rewards)
    }

    private fun validateMove(
        state: GameState, agent: Agent, protocol: Protocol, result: BoundedBotResult.Move,
    ): MoveReduction.Applied {
        check(result.intent.actor == state.turn?.player) { "Wrong root actor" }
        check(result.stats.expandedNodes in 0..protocol.maxNodes) { "Node cap violated" }
        check(result.stats.leafEvaluations in 0..protocol.maxEvaluations) { "Evaluation cap violated" }
        val depth = if (agent.greedy) 1 else protocol.maxDepth
        check(result.stats.completedDepth in 0..depth) { "Invalid completed depth" }
        check((result.source == BotMoveSource.LEGAL_FALLBACK) == (result.stats.completedDepth == 0)) {
            "Source/completed-depth mismatch"
        }
        check(LegalMoveGenerator.legalMoves(state).any { it.intent() == result.intent }) { "Selected intent absent from authoritative legal moves" }
        val reduction = GameReducer.reduce(state, result.intent)
        check(reduction is MoveReduction.Applied) { "Public reducer rejected selected intent: $reduction" }
        check(!LegalMoveGenerator.isKingInCheck(reduction.state, result.intent.actor)) { "Applied move left king unsafe" }
        return reduction
    }

    private fun search(state: GameState, agent: Agent, protocol: Protocol): SearchResult {
        val bot = BoundedMaxNBot(
            maxDepth = if (agent.greedy) 1 else protocol.maxDepth,
            maxNodes = protocol.maxNodes, maxEvaluations = protocol.maxEvaluations,
            evaluation = agent.evaluation,
        )
        val started = System.nanoTime()
        val result = bot.choose(state, agent.policy, stop = BotStopProbe {
            if (protocol.budgetNanos?.let { System.nanoTime() - started >= it } == true) {
                BotStopSignal.TIMEOUT
            } else BotStopSignal.CONTINUE
        })
        return SearchResult(result, System.nanoTime() - started)
    }

    private fun schedule(starts: List<ArenaPilotFixtures.Start>): List<GameSpec> = buildList {
        starts.forEachIndexed { originIndex, start ->
            protocols.forEach { protocol ->
                permutations.forEachIndexed { permutationIndex, permutation ->
                    val focalOrder = if ((originIndex + permutationIndex) % 2 == 0) {
                        listOf(CONTROL_FOCAL, PROGRESS_FOCAL)
                    } else listOf(PROGRESS_FOCAL, CONTROL_FOCAL)
                    focalOrder.forEach { focal ->
                        val identities = listOf(focal, GREEDY, CONTROL_REFERENCE)
                        val seats = PlayerId.entries.associateWith { player -> identities[permutation[player.ordinal]] }
                        add(GameSpec("${start.id}-${protocol.id}-${focal.id}-seat$permutationIndex", start, protocol, focal, permutationIndex, seats))
                    }
                }
            }
        }
    }

    private fun rewards(outcome: GameOutcome): Map<PlayerId, Double> = when (outcome) {
        is GameOutcome.Ranked -> mapOf(outcome.first to 1.0, outcome.second to 0.5, outcome.third to 0.0)
        is GameOutcome.TwoWayDraw -> mapOf(outcome.first to 0.75, outcome.second to 0.75, outcome.third to 0.0)
        is GameOutcome.ThreeWayDraw -> PlayerId.entries.associateWith { 0.5 }
    }

    private fun summarize(results: List<GameResult>, report: Report) {
        protocols.forEach { protocol ->
            val bounds = mutableMapOf<String, Pair<Double, Double>>()
            listOf(CONTROL_FOCAL, PROGRESS_FOCAL).forEach { focal ->
                val games = results.filter { it.game.protocol == protocol && it.game.focal == focal }
                val finished = games.filter { it.outcome is PilotOutcome.Finished }
                val unfinished = games.count { it.outcome is PilotOutcome.Unfinished }
                val failed = games.count { it.outcome is PilotOutcome.Failed }
                val knownReward = finished.sumOf { result ->
                    val seat = result.game.seats.entries.single { it.value.id == focal.id }.key
                    checkNotNull(result.rewards).getValue(seat)
                }
                val expectedGames = 12
                val unknown = expectedGames - finished.size
                val lower = knownReward / expectedGames
                val upper = (knownReward + unknown) / expectedGames
                bounds[focal.id] = lower to upper
                report.row(
                    "configuration_summary",
                    detail = "protocol=${protocol.id};focal=${focal.id};planned_games=$expectedGames;recorded_games=${games.size};" +
                            "origin_blocks=2;finished=${finished.size};unfinished=$unfinished;failed=$failed;" +
                            "unfinished_fraction=${unfinished.toDouble() / expectedGames};" +
                            "completed_only_mean=${if (finished.isEmpty()) "none" else knownReward / finished.size};" +
                            "conservative_mean_lower=$lower;conservative_mean_upper=$upper;" +
                            "unknown_reward_range=[0,1];unknown_includes_unfinished_failed_missing;CI=not_estimated",
                )
            }
            val control = bounds.getValue(CONTROL_FOCAL.id)
            val candidate = bounds.getValue(PROGRESS_FOCAL.id)
            report.row(
                "comparison_bounds",
                detail = "protocol=${protocol.id};candidate_minus_control_lower=${candidate.first - control.second};" +
                        "candidate_minus_control_upper=${candidate.second - control.first};" +
                        "type=conservative_sensitivity_not_confidence_interval;origin_blocks=2;no_noninferiority_claim",
            )
        }
    }

    private fun reportFile(name: String): File = File("build/reports/bot-baseline/$name").also { it.parentFile.mkdirs() }

    private fun sourceFile(path: String): File = listOf(File(path), File("gameDomain/$path"), File("../gameDomain/$path"))
        .firstOrNull(File::isFile) ?: error("Source unavailable for frozen manifest: $path")

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private fun blockId(start: ArenaPilotFixtures.Start): String =
        "${start.id}:${sha256(stateText(start.state).toByteArray(Charsets.UTF_8))}:seed-none"

    /** Explicit complete fields, with sorted collections for stable hashes and independent replay comparison. */
    private fun stateText(state: GameState): String = buildString {
        append("arena-full-state-v1;pieces=[")
        append(state.position.pieces.values.sortedBy { it.id.value }.joinToString("|") {
            "${it.id.value}:${it.type}:${it.army}:${it.coordinate.vertex},${it.coordinate.column},${it.coordinate.row}:${it.hasMoved}"
        })
        append("];castling=[")
        append(state.position.castlingRights.sortedWith(compareBy({ it.army.ordinal }, { it.side.ordinal })).joinToString("|") {
            "${it.army}:${it.side}:${it.rookId?.value ?: "-"}"
        })
        append("];en_passant=[")
        append(state.position.enPassantTargets.values.sortedBy { it.pawnId.value }.joinToString("|") {
            val coordinate = it.captureCoordinate
            "${it.pawnId.value}:${coordinate.vertex},${coordinate.column},${coordinate.row}:" +
                    it.eligiblePlayers.sortedBy(PlayerId::ordinal).joinToString(",") { player -> player.name }
        })
        append("];participants=[")
        append(PlayerId.entries.joinToString("|") { "${it.name}:${state.participants.getValue(it).status}" })
        append("];armies=[")
        append(state.armies.values.sortedBy { it.army.ordinal }.joinToString("|") { "${it.army}:${it.controller}" })
        append("];turn=${state.turn?.let { "${it.player}:${it.ply}" } ?: "none"};phase=${state.phase}")
    }

    private fun com.chesstree.game.domain.Move.intent(): MoveIntent = MoveIntent(actor, from, to, promotion)

    private fun MoveIntent.token(): String =
        "${actor.name}:${from.vertex},${from.column},${from.row}>${to.vertex},${to.column},${to.row}:${promotion?.name ?: "-"}"

    private inner class Report(private val writer: BufferedWriter) {
        init {
            writer.write("kind\tgame_id\tblock_id\tprotocol\tfocal\tseat_assignment\tply\tactor\tagent\tintent\tsource\tstop_reason\tnodes\tevaluations\tcompleted_depth\telapsed_ns\tdetail\n")
        }

        fun note(key: String, value: String) = row("manifest", detail = "$key=$value")

        fun row(
            kind: String, game: GameSpec? = null, ply: Int? = null, actor: PlayerId? = null,
            agent: Agent? = null, intent: MoveIntent? = null, result: BoundedBotResult.Move? = null,
            elapsed: Long? = null, detail: String = "",
        ) {
            val fields = listOf(
                kind, game?.id ?: "-", game?.let { blockId(it.start) } ?: "-", game?.protocol?.id ?: "-",
                game?.focal?.id ?: "-", game?.assignment() ?: "-", ply ?: "-", actor?.name ?: "-",
                agent?.id ?: "-", intent?.token() ?: "-", result?.source?.name ?: "-", result?.reason?.name ?: "-",
                result?.stats?.expandedNodes ?: "-", result?.stats?.leafEvaluations ?: "-",
                result?.stats?.completedDepth ?: "-", elapsed ?: "-",
                "${agent?.let { "evaluation=${it.evaluation.name};eval_version=${it.evaluation.version};" } ?: ""}$detail",
            )
            writer.write(fields.joinToString("\t") { it.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ') })
            writer.newLine()
        }

        fun flush() = writer.flush()
    }

    private data class Agent(val id: String, val evaluation: BotEvaluationMode, val policy: BotPolicy, val greedy: Boolean = false)

    private data class Protocol(val id: String, val maxDepth: Int, val maxNodes: Int, val maxEvaluations: Int, val budgetNanos: Long?) {
        fun manifest(): String = "$id;maxDepth=$maxDepth;maxNodes=$maxNodes;maxEvaluations=$maxEvaluations;soft_budget_ns=${budgetNanos ?: "none"};move_cap=$MOVE_CAP"
    }

    private data class GameSpec(
        val id: String, val start: ArenaPilotFixtures.Start, val protocol: Protocol,
        val focal: Agent, val permutation: Int, val seats: Map<PlayerId, Agent>,
    ) {
        fun assignment(): String = PlayerId.entries.joinToString(",") { "${it.name}:${seats.getValue(it).id}" }
        fun manifest(): String = "$id;origin=${start.id};protocol=${protocol.manifest()};focal=${focal.id};permutation=$permutation;seats=${assignment()};seed_slot=none"
    }

    private data class SearchResult(val result: BoundedBotResult, val elapsed: Long)
    private data class GameResult(val game: GameSpec, val outcome: PilotOutcome, val rewards: Map<PlayerId, Double>?)

    private sealed interface PilotOutcome {
        data class Finished(val raw: GameOutcome) : PilotOutcome
        data class Unfinished(val reason: String) : PilotOutcome
        data class Failed(val detail: String) : PilotOutcome
    }

    private companion object {
        const val MOVE_CAP = 60
        val FIXED_WORK = Protocol("fixed-work-v1", 2, 128, 128, null)
        val TIMED = Protocol("equal-time-v1", 3, 20_000, 20_000, 900_000_000L)
        val protocols = listOf(FIXED_WORK, TIMED)
        val CONTROL_FOCAL = Agent("focal-control-v1", BotEvaluationMode.CONTROL, BotPolicy.DEFAULT)
        val PROGRESS_FOCAL = Agent("focal-pawn-progress-v2", BotEvaluationMode.PAWN_PROGRESS, BotPolicy.DEFAULT)
        val GREEDY = Agent("reference-greedy-v1", BotEvaluationMode.CONTROL, BotPolicy(listOf(BotRule(BotFeature.MATERIAL, 100))), greedy = true)
        val CONTROL_REFERENCE = Agent("reference-control-v1", BotEvaluationMode.CONTROL, BotPolicy.DEFAULT)
        val permutations = listOf(
            listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2),
            listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
        )
        val sourcePaths = listOf(
            "src/jvmTest/kotlin/com/chesstree/game/domain/bot/BotArenaPilotTest.kt",
            "src/jvmTest/kotlin/com/chesstree/game/domain/bot/ArenaPilotFixtures.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/bot/BoundedMaxNBot.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/bot/BotEvaluationMode.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/bot/MaxNBot.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/GameReducer.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/LegalMoveGenerator.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/scenario/StandardGame.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/GameState.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/Position.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/MovementDirections.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/BoardTopology.kt",
            "src/commonMain/kotlin/com/chesstree/game/domain/BoardNotation.kt",
        )
    }
}
