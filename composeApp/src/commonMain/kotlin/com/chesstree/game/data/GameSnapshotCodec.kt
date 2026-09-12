package com.chesstree.game.data

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice

data class GameSnapshot(
    val scenarioId: String,
    val moves: List<MoveIntent>,
)

object GameSnapshotCodec {
    fun encode(snapshot: GameSnapshot): String = buildString {
        appendLine(HEADER)
        appendLine("scenario|${snapshot.scenarioId}")
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
    }

    fun decode(contents: String): GameSnapshot? = runCatching {
        if (contents.length > MAX_CONTENT_LENGTH) return null
        val lines = contents.lineSequence().filter(String::isNotBlank).toList()
        if (lines.size !in 2..MAX_LINE_COUNT || lines.first() != HEADER) return null

        val scenarioFields = lines[1].split('|')
        if (scenarioFields.size != 2 || scenarioFields[0] != "scenario") return null
        val scenarioId = scenarioFields[1]
        if (!scenarioId.matches(SCENARIO_ID_PATTERN)) return null

        val moves = lines.drop(2).map { line ->
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
        GameSnapshot(scenarioId = scenarioId, moves = moves)
    }.getOrNull()

    private fun coordinate(fields: List<String>, offset: Int): BoardCoordinate = BoardCoordinate(
        vertex = checkNotNull(fields[offset].toIntOrNull()),
        column = checkNotNull(fields[offset + 1].toIntOrNull()),
        row = checkNotNull(fields[offset + 2].toIntOrNull()),
    )

    private const val HEADER = "CHESSTREE|1"
    private const val NO_PROMOTION = "-"
    private const val MOVE_FIELD_COUNT = 9
    private const val MAX_LINE_COUNT = 10_002
    private const val MAX_CONTENT_LENGTH = 1_000_000
    private val SCENARIO_ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
}
