package com.chesstree.game.presentation.scenario

import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.BoardCoordinate
import com.chesstree.game.domain.PieceType
import com.chesstree.game.domain.PlayerId
import com.chesstree.game.domain.scenario.GameScenario
import com.chesstree.game.domain.scenario.GameScenarioBuilder
import com.chesstree.game.domain.scenario.gameScenario
import com.chesstree.game.presentation.board.initialBoardPieces

object ManualGameScenarios {
    val standard: GameScenario = gameScenario("standard") {
        title = "Обычное начало"
        description = "Три полных армии; ход белых."
        initialBoardPieces().forEach { piece ->
            piece(
                id = piece.id,
                army = piece.army,
                type = piece.type,
                at = piece.cellId,
            )
        }
    }

    val sparseMovement: GameScenario = gameScenario("sparse-movement") {
        title = "Ходы на свободной доске"
        description = "Короли и несколько фигур без блокирующих пешек."
        kings()
        piece("white-rook", ArmyColor.WHITE, PieceType.ROOK, cell(0, 1, 1))
        piece("red-bishop", ArmyColor.RED, PieceType.BISHOP, cell(2, 1, 1))
        piece("black-knight", ArmyColor.BLACK, PieceType.KNIGHT, cell(4, 1, 1))
    }

    val capturePractice: GameScenario = gameScenario("capture-practice") {
        title = "Взятия"
        description = "Разреженная позиция с фигурами разных армий в центре."
        kings()
        piece("white-queen", ArmyColor.WHITE, PieceType.QUEEN, cell(0, 0, 3))
        piece("red-rook", ArmyColor.RED, PieceType.ROOK, cell(1, 0, 3))
        piece("black-pawn", ArmyColor.BLACK, PieceType.PAWN, cell(5, 0, 3), hasMoved = true)
    }

    val afterRedCheckmate: GameScenario = gameScenario("after-red-checkmate") {
        title = "Красные получили мат"
        description = "Король красных снят, красная армия управляется белыми."
        checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 17)
        turn(PlayerId.BLACK, ply = 18)
        piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 3, 0))
        piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 3, 0))
        piece("white-queen", ArmyColor.WHITE, PieceType.QUEEN, cell(1, 0, 3), hasMoved = true)
        piece("red-rook", ArmyColor.RED, PieceType.ROOK, cell(2, 1, 1), hasMoved = true)
        piece("red-pawn", ArmyColor.RED, PieceType.PAWN, cell(3, 1, 2), hasMoved = true)
        piece("black-bishop", ArmyColor.BLACK, PieceType.BISHOP, cell(5, 1, 1), hasMoved = true)
    }

    val finishedGame: GameScenario = gameScenario("finished-game") {
        title = "Партия завершена"
        description = "Белые заняли первое место; следующего хода нет."
        checkmated(PlayerId.RED, by = PlayerId.WHITE, atPly = 17)
        checkmated(PlayerId.BLACK, by = PlayerId.WHITE, atPly = 31)
        ranked(
            first = PlayerId.WHITE,
            second = PlayerId.BLACK,
            third = PlayerId.RED,
        )
        piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 3, 0))
        piece("white-queen", ArmyColor.WHITE, PieceType.QUEEN, cell(1, 0, 3), hasMoved = true)
        piece("red-rook", ArmyColor.RED, PieceType.ROOK, cell(2, 1, 1), hasMoved = true)
        piece("black-bishop", ArmyColor.BLACK, PieceType.BISHOP, cell(5, 1, 1), hasMoved = true)
    }

    val all: List<GameScenario> = listOf(
        standard,
        sparseMovement,
        capturePractice,
        afterRedCheckmate,
        finishedGame,
    )

    private fun GameScenarioBuilder.kings() {
        piece("white-king", ArmyColor.WHITE, PieceType.KING, cell(0, 3, 0))
        piece("red-king", ArmyColor.RED, PieceType.KING, cell(2, 3, 0))
        piece("black-king", ArmyColor.BLACK, PieceType.KING, cell(4, 3, 0))
    }

    private fun cell(
        vertex: Int,
        column: Int,
        row: Int,
    ): BoardCoordinate = BoardCoordinate(vertex, column, row)
}
