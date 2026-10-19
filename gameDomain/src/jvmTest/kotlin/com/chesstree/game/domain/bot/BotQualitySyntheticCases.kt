package com.chesstree.game.domain.bot

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.ArmyControl
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.GamePhase
import com.chesstree.game.domain.GameReducer
import com.chesstree.game.domain.GameState
import com.chesstree.game.domain.MoveIntent
import com.chesstree.game.domain.MoveReduction
import com.chesstree.game.domain.Participant
import com.chesstree.game.domain.ParticipantStatus
import com.chesstree.game.domain.Piece
import com.chesstree.game.domain.PieceId
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.Position
import com.chesstree.game.domain.ThreePlayerBoardNotation
import com.chesstree.game.domain.Turn

/** Synthetic rule-valid positions; reachability from StandardGame is not claimed. */
internal object BotQualitySyntheticCases {
    data class Case(
        val id: String,
        val state: GameState,
        val classification: String,
        val expectation: String,
        val history: List<GameState> = listOf(state),
    )

    fun all(): List<Case> {
        val mate = state(
            piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
            piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, "G11"),
            piece("red-king", PieceType.KING, ArmyColor.RED, "H12"),
            piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
        )
        val afterMate = checkNotNull(
            GameReducer.reduce(mate, intent(PlayerId.WHITE, "G11", "G12")) as? MoveReduction.Applied,
        ).state
        val finalMate = GameState(
            position = Position(listOf(
                piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
                piece("white-queen", PieceType.QUEEN, ArmyColor.WHITE, "G11"),
                piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
            ).associateBy(Piece::id)),
            participants = PlayerId.entries.associateWith { player ->
                Participant(player, if (player == PlayerId.RED) {
                    ParticipantStatus.Checkmated(by = PlayerId.WHITE, atPly = 1)
                } else ParticipantStatus.Active)
            },
            armies = ArmyColor.entries.associateWith { army ->
                ArmyControl(army, if (army == ArmyColor.BLACK) PlayerId.BLACK else PlayerId.WHITE)
            },
            turn = Turn(PlayerId.WHITE, ply = 2),
            phase = GamePhase.InProgress,
        )
        return listOf(
            Case(
                "synthetic-forced-recapture", state(
                    piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "G10"),
                    piece("white-king", PieceType.KING, ArmyColor.WHITE, "F12"),
                    piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "G12"),
                    piece("red-king", PieceType.KING, ArmyColor.RED, "A2"),
                    piece("black-bishop", PieceType.BISHOP, ArmyColor.BLACK, "H11"),
                    piece("black-king", PieceType.KING, ArmyColor.BLACK, "H12"),
                ),
                "verified-tactical-expectation",
                "G10-G12 wins a pawn but loses the rook to H11-G12 after every legal RED reply; " +
                    "check analysis must include RED's actual intervening move.",
            ),
            Case(
                "synthetic-unchecked-recapture", state(
                    piece("a-white-rook", PieceType.ROOK, ArmyColor.WHITE, "D4"),
                    piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
                    piece("red-pawn", PieceType.PAWN, ArmyColor.RED, "E4"),
                    piece("red-king", PieceType.KING, ArmyColor.RED, "B1"),
                    piece("black-rook", PieceType.ROOK, ArmyColor.BLACK, "F4"),
                    piece("black-king", PieceType.KING, ArmyColor.BLACK, "N7"),
                ),
                "verified-tactical-expectation",
                "The legal D4-E4, RED B1-A1, BLACK F4-E4 line trades a rook for a pawn " +
                    "without a check trigger; this is an available punishment, not a forced opponent policy.",
            ),
            Case(
                "synthetic-red-king-evasion", state(
                    piece("red-king", PieceType.KING, ArmyColor.RED, "H12"),
                    piece("black-king", PieceType.KING, ArmyColor.BLACK, "A1"),
                    piece("white-king", PieceType.KING, ArmyColor.WHITE, "N8"),
                    piece("black-rook", PieceType.ROOK, ArmyColor.BLACK, "H10"),
                    actor = PlayerId.RED,
                ),
                "verified-tactical-expectation",
                "RED is checked; every legal reply is a quiet king evasion and must remove RED's check.",
            ),
            Case(
                "synthetic-mate-army-transfer", mate,
                "terminal-invariant",
                "WHITE G11-G12 mates RED, transfers RED army control to WHITE, removes RED's king " +
                    "and gives BLACK the next turn; no best-move uniqueness is asserted.",
            ),
            Case(
                "synthetic-black-after-transfer", afterMate,
                "terminal-invariant",
                "Only WHITE and BLACK remain active; BLACK's legal reply returns the turn to WHITE " +
                    "while RED remains eliminated and WHITE controls RED's army.",
                history = listOf(mate, afterMate),
            ),
            Case(
                "synthetic-two-active-final-mate", finalMate,
                "terminal-invariant",
                "With RED already eliminated, WHITE G11-G12 finishes with WHITE / BLACK / RED ranking.",
            ),
            Case(
                "synthetic-promotion-choices", state(
                    piece("white-king", PieceType.KING, ArmyColor.WHITE, "H12"),
                    piece("red-king", PieceType.KING, ArmyColor.RED, "A1"),
                    piece("black-king", PieceType.KING, ArmyColor.BLACK, "N8"),
                    piece("white-pawn", PieceType.PAWN, ArmyColor.WHITE, "K11"),
                ),
                "verified-tactical-expectation",
                "K11-K12 requires a promotion choice and offers exactly queen, rook, bishop and knight; " +
                    "each choice is reducer-valid, with no strategic preference claimed.",
            ),
        )
    }

    private fun state(vararg pieces: Piece, actor: PlayerId = PlayerId.WHITE): GameState = GameState(
        position = Position(pieces.associateBy(Piece::id)),
        participants = PlayerId.entries.associateWith(::Participant),
        armies = ArmyColor.entries.associateWith { ArmyControl(it, it.originalPlayer) },
        turn = Turn(actor, ply = 1),
        phase = GamePhase.InProgress,
    )

    private fun piece(id: String, type: PieceType, army: ArmyColor, label: String): Piece =
        Piece(PieceId(id), type, army, at(label), hasMoved = true)

    private fun at(label: String): BoardCoordinate = checkNotNull(ThreePlayerBoardNotation.parse(label))

    private fun intent(actor: PlayerId, from: String, to: String): MoveIntent =
        MoveIntent(actor, at(from), at(to))
}
