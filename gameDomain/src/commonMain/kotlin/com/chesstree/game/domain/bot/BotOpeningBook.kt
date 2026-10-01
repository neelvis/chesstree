package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.PlayerId

/** Experimental ChessTree continuations; callers retain legality and search authority. */
object BotOpeningBook {
    const val VERSION: Int = 1

    fun preferredMoves(state: GameState, seed: Long): List<MoveIntent> {
        val signature = signature(state) ?: return emptyList()
        val tokens = BotOpeningBookData.entries[signature] ?: return emptyList()
        val actor = checkNotNull(state.turn).player
        // Each actor has an independent deterministic rotation; no shared mutable RNG.
        val mixed = seed xor (seed ushr 32) xor ((actor.ordinal + 1L) * 0x9E3779B9L)
        val offset = ((mixed and Long.MAX_VALUE) % tokens.size).toInt()
        return tokens.indices.map { decode(tokens[(it + offset) % tokens.size]) }
    }

    /** Length-prefixed fields make equality exact even for unusual piece identifiers. */
    internal fun signature(state: GameState): String? {
        val turn = state.turn ?: return null
        if (state.phase != GamePhase.InProgress || turn.ply !in 1..9) return null
        if (state.participants.values.any { it.status != ParticipantStatus.Active }) return null
        if (state.armies.values.any { it.controller != it.army.originalPlayer }) return null
        return buildString {
            fun field(value: Any?) {
                val text = value?.toString() ?: ""
                append(text.length).append(':').append(text)
            }
            field(VERSION)
            field("InProgress")
            field(turn.player.name)
            field(turn.ply)
            PlayerId.entries.forEach { player ->
                field(state.participants.getValue(player).id.name)
                field("Active")
            }
            ArmyColor.entries.forEach { army ->
                val control = state.armies.getValue(army)
                field(control.army.name)
                field(control.controller.name)
            }
            field(state.position.pieces.size)
            state.position.pieces.values.sortedBy { it.id.value }.forEach { piece ->
                field(piece.id.value)
                field(piece.type.name)
                field(piece.army.name)
                field(piece.coordinate.vertex)
                field(piece.coordinate.column)
                field(piece.coordinate.row)
                field(piece.hasMoved)
            }
            val rights = state.position.castlingRights.sortedWith(
                compareBy({ it.army.ordinal }, { it.side.ordinal }),
            )
            field(rights.size)
            rights.forEach { right ->
                field(right.army.name)
                field(right.side.name)
                field(right.rookId?.value)
            }
            val targets = state.position.enPassantTargets.values.sortedBy { it.pawnId.value }
            field(targets.size)
            targets.forEach { target ->
                field(target.pawnId.value)
                field(target.captureCoordinate.vertex)
                field(target.captureCoordinate.column)
                field(target.captureCoordinate.row)
                field(target.eligiblePlayers.size)
                target.eligiblePlayers.sortedBy { it.ordinal }.forEach { field(it.name) }
            }
        }
    }

    internal fun decode(token: String): MoveIntent {
        val parts = token.split(',').map(String::toInt)
        require(parts.size == 7)
        return MoveIntent(
            actor = PlayerId.entries[parts[0]],
            from = BoardCoordinate(parts[1], parts[2], parts[3]),
            to = BoardCoordinate(parts[4], parts[5], parts[6]),
        )
    }
}
