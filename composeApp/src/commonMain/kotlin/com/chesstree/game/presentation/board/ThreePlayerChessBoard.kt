package com.chesstree.game.presentation.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.PieceType
import kotlin.math.abs
import kotlin.math.roundToInt

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
    trophies: List<BoardTrophy> = emptyList(),
    onCellSelected: (BoardCellId?) -> Unit,
    modifier: Modifier = Modifier,
    palette: BoardPalette = BoardPalette(),
) {
    val cells = ThreePlayerBoardGeometry.cells
    val labels = ThreePlayerBoardGeometry.labels
    val textMeasurer = rememberTextMeasurer()
    val piecesByCell = remember(pieces) { pieces.associateBy(BoardPiece::cellId) }
    val hintsByCell = moveHints.associateBy(MoveHint::target)
    val attackedPieceIds = moveHints.mapNotNull(MoveHint::attackedPieceId).toSet()
    val currentOnCellSelected by rememberUpdatedState(onCellSelected)
    var viewport by remember { mutableStateOf(BoardViewport()) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val coerced = coerceBoardViewport(
                    viewport = viewport,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                )
                if (coerced != viewport) viewport = coerced
            }
            .semantics {
                contentDescription = "Доска для шахмат на троих, 96 клеток"
                val selection =
                    if (selectedPieceId == null) "Фигура не выбрана" else "Фигура выбрана"
                stateDescription =
                    "$selection, масштаб ${(viewport.zoom * 100).roundToInt()} процентов"
            }
            .pointerInput(Unit) {
                detectTwoFingerBoardTransformGestures { centroid, pan, zoomChange ->
                    viewport = transformBoardViewport(
                        viewport = viewport,
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                        centroid = BoardPoint(centroid.x, centroid.y),
                        pan = BoardPoint(pan.x, pan.y),
                        zoomChange = zoomChange,
                    )
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { tap ->
                    val boardPoint = viewportPointToBoard(
                        point = BoardPoint(tap.x, tap.y),
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                        viewport = viewport,
                    ) ?: return@detectTapGestures
                    val tappedCell = cells.lastOrNull { contains(it.corners, boardPoint) }
                    currentOnCellSelected(tappedCell?.id)
                }
            },
    ) {
        val scale = boardScale(size.width, size.height, viewport.zoom)
        fun BoardPoint.offset(): Offset =
            boardPointToViewport(
                point = this,
                viewportWidth = size.width,
                viewportHeight = size.height,
                viewport = viewport,
            ).let { Offset(it.x, it.y) }

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

            hintsByCell[cell.id]?.let { hint ->
                when (hint.kind) {
                    MoveHintKind.MOVE -> drawCircle(
                        palette.move,
                        radius = scale * (0.055f / 3f),
                        center = cell.center.offset()
                    )

                    MoveHintKind.CAPTURE -> if (
                        hint.attackedPieceId == null || hint.showLandingMarker
                    ) {
                        drawCircle(
                            palette.capture,
                            radius = scale * (0.055f / 3f),
                            center = cell.center.offset(),
                            style = Stroke(3.dp.toPx()),
                        )
                    }
                }
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
                    strokeWidth = scale * 0.0125f,
                    alpha = 0.55f,
                )
            }
        }

        cells.forEach { cell ->
            piecesByCell[cell.id]?.let { piece ->
                val center = cell.center.offset()
                drawPiece(
                    piece = piece,
                    center = center,
                    radius = scale * 0.07f,
                    textMeasurer = textMeasurer,
                )
                when {
                    piece.id == selectedPieceId -> drawCircle(
                        color = palette.selected,
                        radius = scale * 0.076f,
                        center = center,
                        style = Stroke(3.dp.toPx()),
                    )

                    piece.id in attackedPieceIds -> drawCircle(
                        color = palette.capture,
                        radius = scale * 0.076f,
                        center = center,
                        style = Stroke(3.dp.toPx()),
                    )
                }
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

        ArmyColor.entries.forEach { capturingArmy ->
            val armyTrophies = trophies.filter { it.capturedByArmy == capturingArmy }
            armyTrophies
                .forEachIndexed { index, trophy ->
                    val point = ThreePlayerBoardGeometry.trophyPosition(
                        army = capturingArmy,
                        index = index,
                        count = armyTrophies.size,
                    ).offset()
                    drawPiece(
                        piece = BoardPiece(
                            id = trophy.id,
                            type = trophy.type,
                            army = trophy.army,
                            bodyArmy = trophy.bodyArmy,
                            cellId = BoardCellId(0, 0, 0),
                        ),
                        center = point,
                        radius = scale * 0.035f,
                        textMeasurer = textMeasurer,
                        emphasizeTransferredArmy = false,
                    )
                }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPiece(
    piece: BoardPiece,
    center: Offset,
    radius: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    emphasizeTransferredArmy: Boolean = true,
) {
    val base = pieceColor(piece.army)
    val body = pieceColor(piece.bodyArmy)
    val ink = if (piece.bodyArmy == ArmyColor.WHITE) Color(0xFF2B211B) else Color(0xFFFFF8EC)
    val isTransferred = piece.army != piece.bodyArmy
    val bodyRadius = pieceBodyRadius(
        radius = radius,
        isTransferred = isTransferred,
        transferredRingExtra = if (emphasizeTransferredArmy) 3.dp.toPx() else 0f,
    )
    drawCircle(
        color = Color.Black.copy(alpha = 0.18f),
        radius = radius * (0.072f / 0.07f),
        center = center + Offset(radius * 0.11f, radius * 0.14f),
    )
    drawCircle(color = base, radius = radius, center = center)
    drawCircle(color = body, radius = bodyRadius, center = center)
    drawCircle(
        color = if (piece.army == ArmyColor.BLACK) Color(0xFF8D7565) else Color(0xFF5C382B),
        radius = radius,
        center = center,
        style = Stroke(radius * (0.008f / 0.07f)),
    )
    val layout = textMeasurer.measure(
        text = pieceGlyph(piece),
        style = TextStyle(
            color = ink,
            fontSize = (radius * (0.08f / 0.07f)).toSp(),
            fontWeight = FontWeight.Bold,
        ),
    )
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(
            center.x - layout.size.width / 2f,
            center.y - layout.size.height / 2f,
        ),
    )
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

private suspend fun PointerInputScope.detectTwoFingerBoardTransformGestures(
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulatedZoom = 1f
        var accumulatedPan = Offset.Zero
        var pastTouchSlop = false
        var canceled = false

        do {
            val event = awaitPointerEvent()
            canceled = event.changes.any { it.isConsumed }
            val pointerCount = event.changes.count { it.pressed && it.previousPressed }
            if (!canceled && pointerCount >= 2) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()

                if (!pastTouchSlop) {
                    accumulatedZoom *= zoomChange
                    accumulatedPan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1f - accumulatedZoom) * centroidSize
                    val panMotion = accumulatedPan.getDistance()
                    pastTouchSlop = zoomMotion > viewConfiguration.touchSlop ||
                        panMotion > viewConfiguration.touchSlop
                }

                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(centroid, panChange, zoomChange)
                    }
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
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

internal fun pieceBodyRadius(
    radius: Float,
    isTransferred: Boolean,
    transferredRingExtra: Float,
): Float {
    val ordinaryBodyRadius = radius * (0.052f / 0.07f)
    return if (isTransferred) {
        (ordinaryBodyRadius - transferredRingExtra).coerceAtLeast(radius * 0.25f)
    } else {
        ordinaryBodyRadius
    }
}
