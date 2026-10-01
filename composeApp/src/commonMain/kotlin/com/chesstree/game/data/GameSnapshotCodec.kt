package com.chesstree.game.data

import com.chesstree.bot.wire.BotStateWireCodec
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice

data class GameSnapshot(
    val scenarioId: String,
    val moves: List<MoveIntent>,
    val botGame: LocalBotGameConfig? = null,
    val botsRunning: Boolean = false,
    val initialState: GameState? = null,
    val diagnostics: List<LocalBotMoveDiagnostic> = emptyList(),
)

object GameSnapshotCodec {
    fun encodeOrNull(snapshot: GameSnapshot): String? = try {
        encode(snapshot)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun encode(snapshot: GameSnapshot): String {
        require(snapshot.scenarioId.matches(SCENARIO_ID_PATTERN))
        require(snapshot.moves.size <= MAX_MOVES)
        require(!snapshot.botsRunning || snapshot.botGame != null)
        require(snapshot.botGame == null || snapshot.initialState != null)
        require(validDiagnostics(snapshot))
        val contents = buildString {
            appendLine(HEADER_V2)
            appendLine("scenario|${snapshot.scenarioId}")
            appendLine("bots-running|${if (snapshot.botsRunning) "1" else "0"}")
            appendLine("initial|${snapshot.initialState?.let(BotStateWireCodec::encode) ?: "-"}")
            val config = snapshot.botGame?.let(LocalBotGameConfigCodec::encode)
            if (config == null) {
                appendLine("bot-game|-")
            } else {
                appendLine("bot-game|${config.lineSequence().count { it.isNotEmpty() }}")
                append(config)
            }
            snapshot.moves.forEach { move ->
                append("move|")
                append(move.actor.name)
                append('|')
                append(move.from.vertex)
                append('|')
                append(move.from.column)
                append('|')
                append(move.from.row)
                append('|')
                append(move.to.vertex)
                append('|')
                append(move.to.column)
                append('|')
                append(move.to.row)
                append('|')
                append(move.promotion?.name ?: NO_PROMOTION)
                appendLine()
            }
            snapshot.diagnostics.forEach { appendLine(it.encode()) }
        }
        require(contents.length <= MAX_CONTENT_LENGTH)
        return contents
    }

    fun decode(contents: String): GameSnapshot? = runCatching {
        if (contents.length > MAX_CONTENT_LENGTH) return null
        val lines = contents.lineSequence().toList().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        if (lines.size !in 2..MAX_LINE_COUNT) return null
        val version = when (lines.first()) {
            HEADER_V1 -> 1
            HEADER_V2 -> 2
            else -> return null
        }

        val scenarioFields = lines[1].split('|')
        if (scenarioFields.size != 2 || scenarioFields[0] != "scenario") return null
        val scenarioId = scenarioFields[1]
        if (!scenarioId.matches(SCENARIO_ID_PATTERN)) return null

        var moveStart = 2
        var botGame: LocalBotGameConfig? = null
        var botsRunning = false
        var initialState: GameState? = null
        if (version == 2) {
            if (lines.size < 5) return null
            botsRunning = when (lines[2]) {
                "bots-running|0" -> false
                "bots-running|1" -> true
                else -> return null
            }
            if (!lines[3].startsWith("initial|")) return null
            val initial = lines[3].removePrefix("initial|")
            initialState = if (initial == "-") null else BotStateWireCodec.decode(initial) ?: return null
            val configFields = lines[4].split('|')
            if (configFields.size != 2 || configFields[0] != "bot-game") return null
            moveStart = 5
            if (configFields[1] != "-") {
                val configLineCount = configFields[1].toIntOrNull() ?: return null
                if (configLineCount !in 7..12 || lines.size < moveStart + configLineCount) return null
                botGame = LocalBotGameConfigCodec.decode(
                    lines.subList(moveStart, moveStart + configLineCount).joinToString("\n"),
                ) ?: return null
                moveStart += configLineCount
            }
            if (botsRunning && botGame == null || botGame != null && initialState == null) return null
        }
        val diagnosticStart = (moveStart until lines.size).firstOrNull { lines[it].startsWith("diagnostic|") } ?: lines.size
        if (diagnosticStart - moveStart > MAX_MOVES || lines.size - diagnosticStart > MAX_MOVES) return null
        if (version == 1 && diagnosticStart != lines.size) return null
        val diagnostics = lines.drop(diagnosticStart).map { LocalBotMoveDiagnostic.decode(it) ?: return null }
        val moves = lines.subList(moveStart, diagnosticStart).map { line ->
            val fields = line.split('|')
            if (fields.size != MOVE_FIELD_COUNT || fields[0] != "move") return null
            MoveIntent(
                actor = enumValueOf<PlayerId>(fields[1]),
                from = coordinate(fields, offset = 2),
                to = coordinate(fields, offset = 5),
                promotion = fields[8].takeUnless { it == NO_PROMOTION }
                    ?.let { enumValueOf<PromotionChoice>(it) },
            )
        }
        GameSnapshot(
            scenarioId = scenarioId,
            moves = moves,
            botGame = botGame,
            botsRunning = botsRunning,
            initialState = initialState,
            diagnostics = diagnostics,
        ).takeIf(::validDiagnostics)
    }.getOrNull()

    private fun validDiagnostics(snapshot: GameSnapshot): Boolean =
        snapshot.diagnostics.size <= MAX_MOVES &&
            snapshot.diagnostics.zipWithNext().all { (a, b) -> a.moveIndex < b.moveIndex } &&
            snapshot.diagnostics.all { diagnostic ->
                diagnostic.moveIndex in 1..snapshot.moves.size &&
                    snapshot.moves[diagnostic.moveIndex - 1].actor == diagnostic.player &&
                    snapshot.botGame?.seatFor(diagnostic.player) != null
            }

    private fun coordinate(fields: List<String>, offset: Int): BoardCoordinate = BoardCoordinate(
        vertex = checkNotNull(fields[offset].toIntOrNull()),
        column = checkNotNull(fields[offset + 1].toIntOrNull()),
        row = checkNotNull(fields[offset + 2].toIntOrNull()),
    )

    private const val HEADER_V1 = "CHESSTREE|1"
    private const val HEADER_V2 = "CHESSTREE|2"
    private const val NO_PROMOTION = "-"
    private const val MOVE_FIELD_COUNT = 9
    const val MAX_MOVES = 10_000
    private const val MAX_LINE_COUNT = MAX_MOVES * 2 + 17
    private const val MAX_CONTENT_LENGTH = 1_000_000
    private val SCENARIO_ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
}
