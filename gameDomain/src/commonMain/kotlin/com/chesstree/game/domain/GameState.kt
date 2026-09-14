package com.chesstree.game.domain

data class Turn(
    val player: PlayerId,
    val ply: Int,
) {
    init {
        require(ply > 0) { "Ply must be positive: $ply" }
    }
}

enum class DrawReason {
    SECOND_STALEMATE,
    AGREEMENT,
    INSUFFICIENT_MATERIAL,
}

sealed interface GameOutcome {
    data class Ranked(
        val first: PlayerId,
        val second: PlayerId,
        val third: PlayerId,
    ) : GameOutcome {
        init {
            require(setOf(first, second, third).size == PlayerId.entries.size) {
                "Ranked outcome must contain each player exactly once"
            }
        }
    }

    data class ThreeWayDraw(
        val reason: DrawReason,
    ) : GameOutcome

    data class TwoWayDraw(
        val first: PlayerId,
        val second: PlayerId,
        val third: PlayerId,
        val reason: DrawReason,
    ) : GameOutcome {
        init {
            require(setOf(first, second, third).size == PlayerId.entries.size) {
                "A two-way draw must contain each player exactly once"
            }
        }
    }
}

sealed interface GamePhase {
    data object InProgress : GamePhase

    data class Finished(
        val outcome: GameOutcome,
    ) : GamePhase
}

class GameState(
    position: Position,
    participants: Map<PlayerId, Participant>,
    armies: Map<ArmyColor, ArmyControl>,
    turn: Turn?,
    phase: GamePhase,
) {
    val position: Position = position
    val participants: Map<PlayerId, Participant> = participants.toMap()
    val armies: Map<ArmyColor, ArmyControl> = armies.toMap()
    val turn: Turn? = turn
    val phase: GamePhase = phase

    init {
        require(this.participants.keys == PlayerId.entries.toSet()) {
            "A game must contain white, red, and black participants"
        }
        require(this.participants.all { (id, participant) -> id == participant.id }) {
            "Every participant map key must match the participant id"
        }
        require(this.armies.keys == ArmyColor.entries.toSet()) {
            "A game must contain white, red, and black armies"
        }
        require(this.armies.all { (color, control) -> color == control.army }) {
            "Every army map key must match the army color"
        }
        require(this.armies.values.all { it.controller in this.participants }) {
            "Every army must be controlled by a participant in the game"
        }

        this.participants.forEach { (player, participant) ->
            val status = participant.status
            if (status is ParticipantStatus.Checkmated) {
                require(status.by != player) { "A participant cannot checkmate itself" }
                require(this.armies.values.none { it.controller == player }) {
                    "A checkmated participant must not control an army"
                }
                if (this.phase == GamePhase.InProgress) {
                    val originalArmy = ArmyColor.entries.single { it.originalPlayer == player }
                    require(this.armies.getValue(originalArmy).controller == status.by) {
                        "A checkmated participant's army must pass to the mating player"
                    }
                }
                require(
                    this.position.pieces.none { (_, piece) ->
                        piece.army.originalPlayer == player && piece.type == PieceType.KING
                    },
                ) {
                    "A checkmated participant's king must be removed"
                }
            }
        }

        when (this.phase) {
            GamePhase.InProgress -> {
                require(this.turn != null) { "An active game must have a turn" }
                require(this.participants.getValue(this.turn.player).status == ParticipantStatus.Active) {
                    "Only an active participant may have the turn"
                }
            }

            is GamePhase.Finished -> require(this.turn == null) {
                "A finished game must not have a turn"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is GameState &&
                position == other.position &&
                participants == other.participants &&
                armies == other.armies &&
                turn == other.turn &&
                phase == other.phase

    override fun hashCode(): Int {
        var result = position.hashCode()
        result = 31 * result + participants.hashCode()
        result = 31 * result + armies.hashCode()
        result = 31 * result + (turn?.hashCode() ?: 0)
        result = 31 * result + phase.hashCode()
        return result
    }

    override fun toString(): String =
        "GameState(position=$position, participants=$participants, armies=$armies, turn=$turn, phase=$phase)"

    companion object {
        fun new(position: Position): GameState {
            val participants = PlayerId.entries.associateWith(::Participant)
            val armies = ArmyColor.entries.associateWith { color ->
                ArmyControl(army = color, controller = color.originalPlayer)
            }
            return GameState(
                position = position,
                participants = participants,
                armies = armies,
                turn = Turn(player = PlayerId.WHITE, ply = 1),
                phase = GamePhase.InProgress,
            )
        }
    }
}
