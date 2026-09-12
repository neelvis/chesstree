package com.chesstree.game.presentation.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PieceType
import kotlin.math.min

data class BoardPalette(
    val lightCell: Color = Color(0xFFD7B98E),
    val darkCell: Color = Color(0xFF70452E),
    val line: Color = Color(0xFF2B1A12),
    val grainLight: Color = Color.White.copy(alpha = 0.055f),
    val grainDark: Color = Color.Black.copy(alpha = 0.055f),
    val label: Color = Color(0xFF3A281E),
    val selected: Color = Color(0xFF26A6C8),
    val move: Color = Color(0xFF2D9C89),
    val capture: Color = Color(0xFFF2A93B),
)

@Composable
fun ThreePlayerChessBoard(
    pieces: List<BoardPiece>,
    selectedPieceId: String?,
    moveHints: List<MoveHint>,
    onPieceSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    palette: BoardPalette = BoardPalette(),
) {
    val cells = ThreePlayerBoardGeometry.cells
    val labels = ThreePlayerBoardGeometry.labels
    val textMeasurer = rememberTextMeasurer()
    val piecesByCell = remember(pieces) { pieces.associateBy(BoardPiece::cellId) }
    val selectedCell = pieces.firstOrNull { it.id == selectedPieceId }?.cellId
    val hintsByCell = moveHints.associateBy(MoveHint::target)
    val currentOnPieceSelected by rememberUpdatedState(onPieceSelected)

    Canvas(
        modifier = modifier
            .aspectRatio(1.08f, matchHeightConstraintsFirst = true)
            .semantics {
                contentDescription = "Доска для шахмат на троих, 96 клеток"
                stateDescription =
                    if (selectedPieceId == null) "Фигура не выбрана" else "Фигура выбрана"
            }
            .pointerInput(pieces) {
                detectTapGestures { tap ->
                    val scale = boardScale(size.width.toFloat(), size.height.toFloat())
                    val boardPoint = BoardPoint(
                        x = (tap.x - size.width / 2f) / scale,
                        y = (tap.y - size.height / 2f) / scale,
                    )
                    val tappedCell = cells.lastOrNull { contains(it.corners, boardPoint) }
                    currentOnPieceSelected(tappedCell?.let { piecesByCell[it.id]?.id })
                }
            },
    ) {
        val scale = boardScale(size.width, size.height)
        fun BoardPoint.offset(): Offset =
            Offset(size.width / 2f + x * scale, size.height / 2f + y * scale)

        cells.forEach { cell ->
            val path = cell.path { point -> point.offset() }
            drawPath(path, if (cell.isDark) palette.darkCell else palette.lightCell)
            clipPath(path) {
                val grain = if (cell.isDark) palette.grainLight else palette.grainDark
                val center = cell.center.offset()
                repeat(2) { lineIndex ->
                    val shift = (lineIndex * 0.055f - 0.025f) * scale
                    drawLine(
                        color = grain,
                        start = Offset(center.x - scale * 0.16f, center.y + shift),
                        end = Offset(center.x + scale * 0.16f, center.y + shift + scale * 0.018f),
                        strokeWidth = scale * 0.006f,
                    )
                }
            }
            drawPath(path, palette.line.copy(alpha = 0.72f), style = Stroke(scale * 0.008f))

            if (cell.id == selectedCell) {
                drawPath(path, palette.selected, style = Stroke(scale * 0.035f))
            }

            hintsByCell[cell.id]?.let { hint ->
                when (hint.kind) {
                    MoveHintKind.MOVE -> drawCircle(
                        palette.move,
                        radius = scale * 0.055f,
                        center = cell.center.offset()
                    )

                    MoveHintKind.CAPTURE -> drawPath(
                        path,
                        palette.capture,
                        style = Stroke(scale * 0.04f)
                    )
                }
            }

            piecesByCell[cell.id]?.let { piece ->
                val glyph = pieceGlyph(piece)
                val base = pieceColor(piece.army)
                val body = pieceColor(piece.bodyArmy)
                val ink =
                    if (piece.bodyArmy == ArmyColor.WHITE) Color(0xFF2B211B) else Color(0xFFFFF8EC)
                val pieceCenter = cell.center.offset()
                drawCircle(
                    color = Color.Black.copy(alpha = 0.18f),
                    radius = scale * 0.072f,
                    center = pieceCenter + Offset(scale * 0.008f, scale * 0.01f),
                )
                drawCircle(color = base, radius = scale * 0.07f, center = pieceCenter)
                drawCircle(color = body, radius = scale * 0.052f, center = pieceCenter)
                drawCircle(
                    color = if (piece.army == ArmyColor.BLACK) Color(0xFF8D7565) else Color(
                        0xFF5C382B
                    ),
                    radius = scale * 0.07f,
                    center = pieceCenter,
                    style = Stroke(scale * 0.008f),
                )
                val layout = textMeasurer.measure(
                    text = glyph,
                    style = TextStyle(
                        color = ink,
                        fontSize = (scale * 0.08f).toSp(),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                drawText(
                    textLayoutResult = layout,
                    topLeft = Offset(
                        pieceCenter.x - layout.size.width / 2f,
                        pieceCenter.y - layout.size.height / 2f,
                    ),
                )
            }
        }

        moveHints.forEach { hint ->
            val route =
                hint.route.mapNotNull { id -> cells.firstOrNull { it.id == id }?.center?.offset() }
            if (route.size != hint.route.size) return@forEach
            route.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = if (hint.kind == MoveHintKind.CAPTURE) palette.capture else palette.move,
                    start = start,
                    end = end,
                    strokeWidth = scale * 0.025f,
                    alpha = 0.55f,
                )
            }
        }

        labels.forEach { label ->
            val layout = textMeasurer.measure(
                text = label.text,
                style = TextStyle(
                    color = palette.label,
                    fontSize = (scale * 0.085f).toSp(),
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            val position = label.position.offset()
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    position.x - layout.size.width / 2f,
                    position.y - layout.size.height / 2f
                ),
            )
        }
    }
}

private fun BoardCell.path(transform: (BoardPoint) -> Offset): Path = Path().apply {
    val first = transform(corners.first())
    moveTo(first.x, first.y)
    corners.drop(1).forEach { point ->
        val offset = transform(point)
        lineTo(offset.x, offset.y)
    }
    close()
}

private fun boardScale(width: Float, height: Float): Float = min(width / 2.35f, height / 2.12f)

private fun contains(polygon: List<BoardPoint>, point: BoardPoint): Boolean {
    var inside = false
    var previous = polygon.last()
    polygon.forEach { current ->
        val intersects = (current.y > point.y) != (previous.y > point.y) &&
                point.x < (previous.x - current.x) * (point.y - current.y) /
                (previous.y - current.y) + current.x
        if (intersects) inside = !inside
        previous = current
    }
    return inside
}

private fun pieceGlyph(piece: BoardPiece): String = when (piece.type) {
    PieceType.KING -> "K"
    PieceType.QUEEN -> "Q"
    PieceType.ROOK -> "R"
    PieceType.BISHOP -> "B"
    PieceType.KNIGHT -> "N"
    PieceType.PAWN -> "P"
}

private fun pieceColor(army: ArmyColor): Color = when (army) {
    ArmyColor.WHITE -> Color(0xFFF7F1E4)
    ArmyColor.RED -> Color(0xFFB72C35)
    ArmyColor.BLACK -> Color(0xFF171310)
}
