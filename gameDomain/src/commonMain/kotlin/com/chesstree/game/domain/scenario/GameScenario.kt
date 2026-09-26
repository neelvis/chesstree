package com.chesstree.game.domain.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.CastlingRight
import com.chesstree.game.domain.CastlingSide
import com.chesstree.game.domain.DrawReason
import com.chesstree.game.domain.EnPassantTarget
import com.chesstree.game.domain.GameOutcome
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.Turn

class GameScenario internal constructor(
    val id: String,
    val title: String,
    val description: String,
    val initialState: GameState,
)

fun gameScenario(
    id: String,
    block: GameScenarioBuilder.() -> Unit,
): GameScenario = GameScenarioBuilder(id).apply(block).build()

class GameScenarioBuilder internal constructor(
    private val id: String,
) {
    var title: String = id
    var description: String = ""

    private val pieces = linkedMapOf<PieceId, Piece>()
    private val participants = PlayerId.entries.associateWith(::Participant).toMutableMap()
    private val armies = ArmyColor.entries.associateWith { army ->
        ArmyControl(army = army, controller = army.originalPlayer)
    }.toMutableMap()
    private val castlingRights = mutableSetOf<CastlingRight>()
    private var enPassantTarget: EnPassantTarget? = null
    private var turn: Turn? = Turn(player = PlayerId.WHITE, ply = 1)
    private var phase: GamePhase = GamePhase.InProgress

    init {
        require(id.matches(ID_PATTERN)) {
            "Scenario id must contain only lowercase letters, digits, and hyphens: $id"
        }
    }

    fun piece(
        id: String,
        army: ArmyColor,
        type: PieceType,
        at: BoardCoordinate,
        hasMoved: Boolean = false,
    ) {
        val pieceId = PieceId(id)
        require(pieceId !in pieces) { "Duplicate piece id in scenario: $pieceId" }
        pieces[pieceId] = Piece(
            id = pieceId,
            type = type,
            army = army,
            coordinate = at,
            hasMoved = hasMoved,
        )
    }

    fun active(player: PlayerId) {
        participants[player] = Participant(player, ParticipantStatus.Active)
    }

    fun checkmated(
        player: PlayerId,
        by: PlayerId,
        atPly: Int,
    ) {
        participants[player] = Participant(player, ParticipantStatus.Checkmated(by, atPly))
        armies.entries.toList().forEach { (army, control) ->
            if (control.controller == player) {
                armies[army] = ArmyControl(army, by)
            }
        }
    }

    fun stalemated(
        player: PlayerId,
        atPly: Int,
    ) {
        participants[player] = Participant(player, ParticipantStatus.Stalemated(atPly))
    }

    fun control(
        army: ArmyColor,
        by: PlayerId,
    ) {
        armies[army] = ArmyControl(army, by)
    }

    fun turn(
        player: PlayerId,
        ply: Int,
    ) {
        turn = Turn(player, ply)
        phase = GamePhase.InProgress
    }

    fun castlingRight(
        army: ArmyColor,
        side: CastlingSide,
        rookId: String? = null,
    ) {
        castlingRights += CastlingRight(
            army = army,
            side = side,
            rookId = rookId?.let(::PieceId),
        )
    }

    fun enPassant(
        pawnId: String,
        captureAt: BoardCoordinate,
        eligiblePlayers: Set<PlayerId>,
    ) {
        enPassantTarget = EnPassantTarget(
            pawnId = PieceId(pawnId),
            captureCoordinate = captureAt,
            eligiblePlayers = eligiblePlayers,
        )
    }

    fun ranked(
        first: PlayerId,
        second: PlayerId,
        third: PlayerId,
    ) {
        finish(GameOutcome.Ranked(first, second, third))
    }

    fun threeWayDraw(reason: DrawReason) {
        finish(GameOutcome.ThreeWayDraw(reason))
    }

    private fun finish(outcome: GameOutcome) {
        phase = GamePhase.Finished(outcome)
        turn = null
    }

    internal fun build(): GameScenario {
        require(title.isNotBlank()) { "Scenario title must not be blank: $id" }
        val position = Position(
            pieces = pieces,
            castlingRights = castlingRights,
            enPassantTarget = enPassantTarget,
        )
        val state = GameState(
            position = position,
            participants = participants,
            armies = armies,
            turn = turn,
            phase = phase,
        )
        validateScenarioState(state)
        return GameScenario(
            id = id,
            title = title,
            description = description,
            initialState = state,
        )
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    }
}

private fun validateScenarioState(state: GameState) {
    ArmyColor.entries.forEach { army ->
        val armyPieces = state.position.pieces.values.filter { it.army == army }
        require(armyPieces.size <= MAX_PIECES_PER_ARMY) {
            "Army $army has ${armyPieces.size} pieces; maximum is $MAX_PIECES_PER_ARMY"
        }

        val kingCount = armyPieces.count { it.type == PieceType.KING }
        val player = army.originalPlayer
        when (state.participants.getValue(player).status) {
            ParticipantStatus.Active,
            is ParticipantStatus.Stalemated,
                -> {
                require(kingCount == 1) {
                    "$player must have exactly one king, but has $kingCount"
                }
                require(state.armies.getValue(army).controller == player) {
                    "$player must control its own $army army while not checkmated"
                }
            }

            is ParticipantStatus.Checkmated -> require(kingCount == 0) {
                "$player is checkmated, so its king must be absent"
            }
        }
    }

    when (val phase = state.phase) {
        GamePhase.InProgress -> {
            val activePlayers = state.participants.values.filter { participant ->
                participant.status == ParticipantStatus.Active
            }
            require(activePlayers.size >= 2) {
                "An in-progress scenario must have at least two active players"
            }
            state.participants.values.forEach { participant ->
                val checkmate = participant.status as? ParticipantStatus.Checkmated
                    ?: return@forEach
                require(
                    state.participants.getValue(checkmate.by).status == ParticipantStatus.Active,
                ) {
                    "The mating player ${checkmate.by} must still be active while the game continues"
                }
            }
        }

        is GamePhase.Finished -> validateFinishedState(state, phase.outcome)
    }

    state.position.enPassantTargets.values.forEach { target ->
        val pawn = state.position.pieces.getValue(target.pawnId)
        require(pawn.type == PieceType.PAWN) {
            "En passant target must refer to a pawn: ${target.pawnId}"
        }
        require(target.eligiblePlayers.all { player ->
            state.participants.getValue(player).status == ParticipantStatus.Active
        }) {
            "Only active players may be eligible for en passant"
        }
        require(pawn.hasMoved) {
            "An en passant pawn must be marked as having moved: ${target.pawnId}"
        }
        require(pawn.coordinate != target.captureCoordinate) {
            "An en passant capture coordinate must differ from the pawn coordinate"
        }
        require(state.position.pieces.values.none { piece ->
            piece.coordinate == target.captureCoordinate
        }) {
            "An en passant capture coordinate must be empty"
        }
        val pawnController = state.armies.getValue(pawn.army).controller
        require(pawnController !in target.eligiblePlayers) {
            "The controller of an en passant pawn cannot capture its own pawn"
        }
    }

    state.position.castlingRights.groupBy(CastlingRight::army).forEach { (army, rights) ->
        val armyPieces = state.position.pieces.values.filter { piece -> piece.army == army }
        require(armyPieces.any { piece ->
            piece.type == PieceType.KING && !piece.hasMoved
        }) {
            "$army cannot have castling rights without an unmoved king"
        }
        val unmovedRooks = armyPieces.filter { piece ->
            piece.type == PieceType.ROOK && !piece.hasMoved
        }
        require(unmovedRooks.size >= rights.size) {
            "$army needs one unmoved rook for each castling right"
        }
        rights.mapNotNull(CastlingRight::rookId).forEach { rookId ->
            require(unmovedRooks.any { rook -> rook.id == rookId }) {
                "$army castling right must refer to an unmoved rook: $rookId"
            }
        }
    }
}

private fun validateFinishedState(
    state: GameState,
    outcome: GameOutcome,
) {
    when (outcome) {
        is GameOutcome.Ranked -> {
            require(
                state.participants.getValue(outcome.first).status == ParticipantStatus.Active,
            ) {
                "The first-place player must be active in a ranked outcome"
            }
            val secondStatus = state.participants.getValue(outcome.second).status
            val thirdStatus = state.participants.getValue(outcome.third).status
            require(secondStatus != ParticipantStatus.Active) {
                "The second-place player must be eliminated in a ranked outcome"
            }
            require(thirdStatus != ParticipantStatus.Active) {
                "The third-place player must be eliminated in a ranked outcome"
            }
            require(thirdStatus.atPly() <= secondStatus.atPly()) {
                "The third-place player must not be eliminated after the second-place player"
            }
        }

        is GameOutcome.ThreeWayDraw -> if (outcome.reason == DrawReason.SECOND_STALEMATE) {
            val stalematedCount = state.participants.values.count { participant ->
                participant.status is ParticipantStatus.Stalemated
            }
            require(stalematedCount >= 2) {
                "A second-stalemate draw requires at least two stalemated players"
            }
        }

        is GameOutcome.TwoWayDraw -> {
            require(state.participants.getValue(outcome.first).status == ParticipantStatus.Active) {
                "The first tied player must be active in a two-way draw"
            }
            require(state.participants.getValue(outcome.second).status == ParticipantStatus.Active) {
                "The second tied player must be active in a two-way draw"
            }
            require(state.participants.getValue(outcome.third).status != ParticipantStatus.Active) {
                "The third-place player must be eliminated in a two-way draw"
            }
        }
    }
}

private fun ParticipantStatus.atPly(): Int = when (this) {
    ParticipantStatus.Active -> error("An active participant has no elimination ply")
    is ParticipantStatus.Checkmated -> atPly
    is ParticipantStatus.Stalemated -> atPly
}

private const val MAX_PIECES_PER_ARMY: Int = 16
