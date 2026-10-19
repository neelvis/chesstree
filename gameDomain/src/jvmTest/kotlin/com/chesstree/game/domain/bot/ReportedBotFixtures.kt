package com.chesstree.game.domain.bot

import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.PromotionChoice
import com.chesstree.game.domain.scenario.StandardGame
import java.io.File
import java.security.MessageDigest

/** A verified prefix, including every earlier rules state rather than only its board layout. */
data class ReportedBotFixture(
    val id: String,
    val prefixPlies: Int,
    val prefixMoveRowsSha256: String,
    val recordedNextMove: String?,
    val purpose: String,
    val history: List<GameState>,
) {
    val state: GameState get() = history.last()
    val recentPositions: List<GameState> get() = history.takeLast(MAX_BOT_RECENT_POSITIONS)
}

data class ReportedBotFixtureCorpus(
    val source: File,
    val sourceSha256: String,
    val baseCommit: String,
    val initialEncodedState: String,
    val moveRows: List<String>,
    val moves: List<MoveIntent>,
    val history: List<GameState>,
    val cases: List<ReportedBotFixture>,
) {
    fun case(id: String): ReportedBotFixture = cases.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Unknown reported fixture: $id")
}

/** JVM-only corpus support. Initial-state codec equivalence is asserted in botWire's JVM tests. */
object ReportedBotFixtures {
    private const val MANIFEST_PATH = "docs/bots/agent-plan-20261019/fixtures.json"
    private const val MAX_MANIFEST_BYTES = 100_000L
    private const val MAX_SOURCE_BYTES = 5_000_000L

    fun manifestFile(): File {
        System.getenv("CHESSTREE_BOT_FIXTURES_MANIFEST")?.let { return File(it) }
        return generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, MANIFEST_PATH) }
            .firstOrNull(File::isFile)
            ?: throw IllegalArgumentException("Cannot find $MANIFEST_PATH; set CHESSTREE_BOT_FIXTURES_MANIFEST")
    }

    fun load(manifest: File = manifestFile(), source: File? = null): ReportedBotFixtureCorpus {
        require(manifest.isFile && manifest.length() <= MAX_MANIFEST_BYTES) {
            "Fixture manifest is missing or too large: $manifest"
        }
        val root = FixtureManifestJson(manifest.readText(Charsets.UTF_8)).parse().objectFields("manifest")
        require(root.string("schema") == "chesstree-agent-fixtures-v1") { "Unsupported fixture schema" }
        val sourceFile = source ?: System.getenv("CHESSTREE_BOT_REPORTED_GAME_PATH")?.let(::File)
            ?: File(manifest.absoluteFile.parentFile, root.string("source"))
        require(sourceFile.isFile && sourceFile.length() <= MAX_SOURCE_BYTES) {
            "Fixture source is missing or too large: $sourceFile"
        }
        val sourceBytes = sourceFile.readBytes()
        val sourceHash = sha256(sourceBytes)
        require(sourceHash == root.string("source_sha256")) { "Source SHA-256 mismatch: $sourceFile" }
        val lines = sourceBytes.toString(Charsets.UTF_8).lineSequence().toList()
        require(lines.firstOrNull() == "CHESSTREE|2") { "Expected CHESSTREE|2 export" }
        require(lines.count { it == "scenario|standard" } == 1) { "Expected one standard scenario" }
        val encodedInitial = lines.filter { it.startsWith("initial|") }
        require(encodedInitial.size == 1) { "Expected exactly one initial state" }
        val moveRows = lines.filter { it.startsWith("move|") }
        require(moveRows.size == root.integer("move_count")) { "Move row count mismatch: ${moveRows.size}" }
        val moves = moveRows.mapIndexed { index, row -> parseMove(row, index + 1) }
        val descriptions = root["cases"] as? List<*>
            ?: throw IllegalArgumentException("Manifest cases must be an array")
        require(descriptions.isNotEmpty()) { "Manifest cases must not be empty" }
        val caseFields = descriptions.map { it.objectFields("case") }
        require(caseFields.map { it.string("id") }.distinct().size == caseFields.size) { "Duplicate fixture id" }
        caseFields.forEach { fields ->
            val prefix = fields.integer("prefix_plies")
            require(prefix in 0..moves.size) { "Fixture ${fields.string("id")} prefix out of range: $prefix" }
            require(prefixHash(moveRows, prefix) == fields.string("prefix_move_rows_sha256")) {
                "Prefix SHA-256 mismatch: ${fields.string("id")}, prefix=$prefix"
            }
            require(fields.containsKey("recorded_next_move") &&
                (fields["recorded_next_move"] == null || fields["recorded_next_move"] is String)) {
                "Fixture recorded_next_move must be a string or null"
            }
        }
        val history = mutableListOf(StandardGame.scenario.initialState)
        moves.forEachIndexed { index, intent ->
            val reduced = GameReducer.reduce(history.last(), intent)
            require(reduced is MoveReduction.Applied) { "Reducer rejected exported ply ${index + 1}: $reduced" }
            history += reduced.state
        }
        return ReportedBotFixtureCorpus(
            source = sourceFile.canonicalFile,
            sourceSha256 = sourceHash,
            baseCommit = root.string("base_commit"),
            initialEncodedState = encodedInitial.single().substringAfter('|'),
            moveRows = moveRows,
            moves = moves,
            history = history.toList(),
            cases = caseFields.map { fields ->
                val prefix = fields.integer("prefix_plies")
                ReportedBotFixture(
                    id = fields.string("id"),
                    prefixPlies = prefix,
                    prefixMoveRowsSha256 = fields.string("prefix_move_rows_sha256"),
                    recordedNextMove = fields["recorded_next_move"] as String?,
                    purpose = fields.string("purpose"),
                    history = history.take(prefix + 1),
                )
            },
        )
    }

    internal fun parseMove(row: String, ply: Int): MoveIntent {
        val fields = row.split('|')
        require(fields.size == 9 && fields[0] == "move") { "Malformed move row at ply $ply: $row" }
        return try {
            MoveIntent(
                actor = PlayerId.valueOf(fields[1]),
                from = BoardCoordinate(fields[2].toInt(), fields[3].toInt(), fields[4].toInt()),
                to = BoardCoordinate(fields[5].toInt(), fields[6].toInt(), fields[7].toInt()),
                promotion = fields[8].takeUnless { it == "-" }?.let(PromotionChoice::valueOf),
            )
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Malformed move row at ply $ply: $row", failure)
        }
    }

    internal fun prefixHash(rows: List<String>, prefix: Int): String =
        sha256(rows.take(prefix).joinToString("", transform = { "$it\n" }).toByteArray(Charsets.UTF_8))

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun Any?.objectFields(label: String): Map<String, Any?> {
    require(this is Map<*, *> && keys.all { it is String }) { "Manifest $label must be an object" }
    return entries.associate { it.key as String to it.value }
}

private fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("Manifest $key must be a string")

private fun Map<String, Any?>.integer(key: String): Int =
    this[key] as? Int ?: throw IllegalArgumentException("Manifest $key must be an integer")

/** Bounded JSON subset used by this manifest: objects, arrays, strings, integers and null. */
private class FixtureManifestJson(private val text: String) {
    private var offset = 0

    fun parse(): Any? {
        val result = value(0)
        whitespace()
        require(offset == text.length) { "Trailing manifest JSON at offset $offset" }
        return result
    }

    private fun value(depth: Int): Any? {
        require(depth < 16) { "Manifest JSON nesting exceeds limit" }
        whitespace()
        return when (peek()) {
            '{' -> {
                offset++
                val fields = linkedMapOf<String, Any?>()
                whitespace()
                if (!consume('}')) {
                    do {
                        whitespace()
                        require(peek() == '"') { "Expected manifest JSON key at offset $offset" }
                        val key = string()
                        require(!fields.containsKey(key)) { "Duplicate manifest JSON key: $key" }
                        whitespace()
                        expect(':')
                        fields[key] = value(depth + 1)
                        whitespace()
                    } while (consume(','))
                    expect('}')
                }
                fields
            }
            '[' -> {
                offset++
                val elements = mutableListOf<Any?>()
                whitespace()
                if (!consume(']')) {
                    do {
                        elements += value(depth + 1)
                        whitespace()
                    } while (consume(','))
                    expect(']')
                }
                elements
            }
            '"' -> string()
            'n' -> {
                require(text.startsWith("null", offset)) { "Invalid manifest JSON at offset $offset" }
                offset += 4
                null
            }
            else -> {
                val start = offset
                consume('-')
                require(digit()) { "Expected manifest JSON value at offset $offset" }
                if (!consume('0')) while (digit()) offset++
                text.substring(start, offset).toIntOrNull()
                    ?: throw IllegalArgumentException("Invalid manifest JSON integer at offset $start")
            }
        }
    }

    private fun string(): String {
        expect('"')
        return buildString {
            while (true) {
                val next = peek() ?: throw IllegalArgumentException("Unterminated manifest JSON string")
                offset++
                when (next) {
                    '"' -> return@buildString
                    '\\' -> {
                        val escape = peek() ?: throw IllegalArgumentException("Unterminated JSON escape")
                        offset++
                        append(when (escape) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                require(offset + 4 <= text.length) { "Incomplete JSON unicode escape" }
                                val digits = text.substring(offset, offset + 4)
                                require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                    "Invalid JSON unicode escape"
                                }
                                val code = digits.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("Invalid JSON unicode escape")
                                offset += 4
                                code.toChar()
                            }
                            else -> throw IllegalArgumentException("Invalid JSON escape: $escape")
                        })
                    }
                    else -> {
                        require(next >= ' ') { "Control character in manifest JSON string" }
                        append(next)
                    }
                }
            }
        }
    }

    private fun peek(): Char? = text.getOrNull(offset)
    private fun digit(): Boolean = peek()?.let { it in '0'..'9' } == true
    private fun whitespace() { while (peek() in listOf(' ', '\n', '\r', '\t')) offset++ }
    private fun consume(char: Char): Boolean = (peek() == char).also { if (it) offset++ }
    private fun expect(char: Char) { require(consume(char)) { "Expected '$char' at manifest JSON offset $offset" } }
}
