package com.chesstree.game.domain

enum class PlayerId {
    WHITE,
    RED,
    BLACK,
}

enum class ArmyColor(
    val originalPlayer: PlayerId,
) {
    WHITE(PlayerId.WHITE),
    RED(PlayerId.RED),
    BLACK(PlayerId.BLACK),
}

data class ArmyControl(
    val army: ArmyColor,
    val controller: PlayerId,
)

sealed interface ParticipantStatus {
    data object Active : ParticipantStatus

    data class Checkmated(
        val by: PlayerId,
        val atPly: Int,
    ) : ParticipantStatus {
        init {
            require(atPly > 0) { "Checkmate ply must be positive: $atPly" }
        }
    }

    data class Stalemated(
        val atPly: Int,
    ) : ParticipantStatus {
        init {
            require(atPly > 0) { "Stalemate ply must be positive: $atPly" }
        }
    }
}

data class Participant(
    val id: PlayerId,
    val status: ParticipantStatus = ParticipantStatus.Active,
)
