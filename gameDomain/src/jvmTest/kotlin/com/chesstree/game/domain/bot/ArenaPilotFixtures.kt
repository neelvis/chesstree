package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn
import com.chesstree.game.domain.scenario.StandardGame

/** Frozen pilot origins; the mixed-material guards are not tournament starts. */
internal object ArenaPilotFixtures {
    data class Start(val id: String, val provenance: String, val state: GameState)

    fun starts(): List<Start> = listOf(
        Start("standard", "StandardGame.scenario.initialState;reachable opening", StandardGame.scenario.initialState),
        Start(
            "white-changed-file",
            "synthetic constructor ArenaPilotFixtures.changedFile;StandardGame reachability unproven;not video reconstruction",
            changedFile(),
        ),
    )

    fun mixedMaterial(checked: Boolean): GameState = changedFile(mixed = true, checked = checked)

    fun at(label: String): BoardCoordinate = requireNotNull(ThreePlayerBoardNotation.parse(label))

    private fun changedFile(mixed: Boolean = false, checked: Boolean = false): GameState {
        val pieces = listOfNotNull(
            moved("white-back-king", ArmyColor.WHITE, PieceType.KING, "H12"),
            moved("red-back-king", ArmyColor.RED, PieceType.KING, "A1"),
            moved("black-back-king", ArmyColor.BLACK, PieceType.KING, "N8"),
            moved("white-pawn-progress", ArmyColor.WHITE, PieceType.PAWN, "K9"),
            moved("red-pawn-progress", ArmyColor.RED, PieceType.PAWN, "D3"),
            moved("black-pawn-progress", ArmyColor.BLACK, PieceType.PAWN, "K6"),
            if (mixed) moved("white-back-knight", ArmyColor.WHITE, PieceType.KNIGHT, "A8") else null,
            if (checked) moved("red-back-rook", ArmyColor.RED, PieceType.ROOK, "H10") else null,
        )
        return GameState(
            position = Position(
                pieces = pieces.associateBy(Piece::id),
                castlingRights = emptySet(),
                enPassantTargets = emptyList(),
            ),
            participants = PlayerId.entries.associateWith { Participant(it, ParticipantStatus.Active) },
            armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
            turn = Turn(PlayerId.WHITE, ply = 293),
            phase = GamePhase.InProgress,
        )
    }

    private fun moved(id: String, army: ArmyColor, type: PieceType, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)
}
