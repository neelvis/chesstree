package com.chesstree.game.presentation.board

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chesstree.app.ChessTreeColors
import com.chesstree.app.ChessTreeTheme
import com.chesstree.app.localized
import com.chesstree.game.domain.ArmyColor
import com.chesstree.game.domain.Move
import com.chesstree.game.domain.PieceType
import com.chesstree.resources.Res
import com.chesstree.resources.allDrawableResources
import com.chesstree.resources.allFontResources
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.imageResource
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

@Preview
@Composable
private fun ThreePlayerChessBoardPreview() {
    ChessTreeTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(threePlayerBoardAspectRatio(PieceSet.FAIRY, showDecorativeBirds = false, zoom = 1f)),
        ) {
            ThreePlayerChessBoard(
                pieces = initialBoardPieces(),
                selectedPieceId = null,
                moveHints = emptyList(),
                pieceSet = PieceSet.FAIRY,
                showDecorativeBirds = false,
                onCellSelected = {},
            )
        }
    }
}

@Composable
fun PromotionPiecePickerDialog(
    moves: List<Move>,
    army: ArmyColor,
    pieceSet: PieceSet,
    onMoveSelected: (Move) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedPieceType by remember(moves) { mutableStateOf<PieceType?>(null) }
    val fairyPieceImages = if (pieceSet == PieceSet.FAIRY) loadFairyPieceImages() else null
    val standardFontResource = Font(Res.allFontResources.getValue("noto_sans_symbols_2_regular"))
    val standardPieceFont = remember(standardFontResource) { FontFamily(standardFontResource) }
    val pieceTypes = listOf(
        PieceType.QUEEN,
        PieceType.ROOK,
        PieceType.BISHOP,
        PieceType.KNIGHT,
        PieceType.PAWN,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localized("promotion_title")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pieceTypes.forEach { type ->
                        val isSelected = selectedPieceType == type
                        val description = localized(type.localizationKey())
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clickable {
                                    if (isSelected) {
                                        if (type == PieceType.PAWN) {
                                            onDismiss()
                                        } else {
                                            moves.firstOrNull { it.promotion?.pieceType == type }
                                                ?.let(onMoveSelected)
                                        }
                                    } else {
                                        selectedPieceType = type
                                    }
                                }
                                .semantics {
                                    contentDescription = description
                                },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) ChessTreeColors.SageContainer else ChessTreeColors.Surface,
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) ChessTreeColors.Sage else MaterialTheme.colorScheme.outline,
                            ),
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(2.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (pieceSet == PieceSet.FAIRY) {
                                    Image(
                                        bitmap = checkNotNull(fairyPieceImages)
                                            .pieces.getValue(type to army),
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    val color = pieceColor(army)
                                    Text(
                                        text = pieceGlyph(type),
                                        color = color,
                                        fontFamily = standardPieceFont,
                                        fontSize = 40.sp,
                                        style = TextStyle(
                                            shadow = Shadow(
                                                color = if (army == ArmyColor.BLACK) {
                                                    Color.White.copy(alpha = 0.7f)
                                                } else {
                                                    Color.Black.copy(alpha = 0.7f)
                                                },
                                                offset = Offset(1.dp.value, 1.dp.value),
                                                blurRadius = 2.dp.value,
                                            ),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selectedPieceType != null) {
                        Text(
                            localized("promotion_confirm_again"),
                            color = ChessTreeColors.OnSageContainer,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
        containerColor = ChessTreeColors.SageContainer,
        titleContentColor = ChessTreeColors.Ink,
        textContentColor = ChessTreeColors.Ink,
    )
}

private fun PieceType.localizationKey(): String = when (this) {
    PieceType.QUEEN -> "queen"
    PieceType.ROOK -> "rook"
    PieceType.BISHOP -> "bishop"
    PieceType.KNIGHT -> "knight"
    PieceType.PAWN -> "pawn"
    PieceType.KING -> "king"
}

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
    moveLineHints: List<MoveHint> = emptyList(),
    trophies: List<BoardTrophy> = emptyList(),
    pieceSet: PieceSet = PieceSet.FAIRY,
    showDecorativeBirds: Boolean = true,
    onCellSelected: (BoardCellId?) -> Unit,
    modifier: Modifier = Modifier,
    palette: BoardPalette = BoardPalette(),
    onZoomChanged: (Float) -> Unit = {},
    zoomToCell: BoardCellId? = null,
    resetViewportKey: Any? = null,
    moveAnimationKey: BoardMoveAnimationKey? = null,
    animatePieceMovement: Boolean = true,
    onMoveAnimationInProgressChanged: (Boolean) -> Unit = {},
) {
    val cells = ThreePlayerBoardGeometry.cells
    val cellsById = remember(cells) { cells.associateBy { it.id } }
    val labels = ThreePlayerBoardGeometry.labels
    val textMeasurer = rememberTextMeasurer(cacheSize = BOARD_TEXT_LAYOUT_CACHE_SIZE)
    val standardPieceFontResource =
        Font(Res.allFontResources.getValue("noto_sans_symbols_2_regular"))
    val standardPieceFont =
        remember(standardPieceFontResource) { FontFamily(standardPieceFontResource) }
    val fairyPieceImages = if (pieceSet == PieceSet.FAIRY) loadFairyPieceImages() else null
    val piecesByCell = remember(pieces) { pieces.associateBy(BoardPiece::cellId) }
    val hintsByCell = moveHints.associateBy(MoveHint::target)
    val attackedPieceIds = moveHints.mapNotNull(MoveHint::attackedPieceId).toSet()
    val showBirdsAroundBoard = pieceSet == PieceSet.FAIRY && showDecorativeBirds
    val contentWidth = if (showBirdsAroundBoard) {
        FAIRY_BOARD_CONTENT_WIDTH
    } else {
        STANDARD_BOARD_CONTENT_WIDTH
    }
    val contentHeight = if (showBirdsAroundBoard) {
        FAIRY_BOARD_CONTENT_HEIGHT
    } else {
        STANDARD_BOARD_CONTENT_HEIGHT
    }
    val currentOnCellSelected by rememberUpdatedState(onCellSelected)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentOnMoveAnimationInProgressChanged by rememberUpdatedState(
        onMoveAnimationInProgressChanged,
    )
    var viewport by remember { mutableStateOf(BoardViewport()) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var previousSelectedPieceId by remember { mutableStateOf(selectedPieceId) }
    var previousResetViewportKey by remember { mutableStateOf(resetViewportKey) }
    val currentBoardSnapshot = BoardAnimationSnapshot(moveAnimationKey, pieces, trophies)
    val previousBoardSnapshot = remember {
        BoardAnimationSnapshotHolder(currentBoardSnapshot)
    }
    val detectedMoveAnimation = remember(
        animatePieceMovement,
        moveAnimationKey,
        pieces,
        trophies,
    ) {
        if (animatePieceMovement) {
            createBoardMoveAnimation(previousBoardSnapshot.snapshot, currentBoardSnapshot)
        } else {
            null
        }
    }
    SideEffect {
        previousBoardSnapshot.snapshot = currentBoardSnapshot
    }
    var completedMoveAnimationKey by remember { mutableStateOf<BoardMoveAnimationKey?>(null) }
    var runningMoveAnimationKey by remember { mutableStateOf<BoardMoveAnimationKey?>(null) }
    var moveAnimationProgress by remember { mutableStateOf(1f) }
    LaunchedEffect(animatePieceMovement, moveAnimationKey, pieces, trophies) {
        if (!animatePieceMovement || detectedMoveAnimation == null) {
            runningMoveAnimationKey = null
            moveAnimationProgress = 1f
            if (!animatePieceMovement) completedMoveAnimationKey = moveAnimationKey
            currentOnMoveAnimationInProgressChanged(false)
            return@LaunchedEffect
        }
        runningMoveAnimationKey = moveAnimationKey
        moveAnimationProgress = 0f
        currentOnMoveAnimationInProgressChanged(true)
        Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = BOARD_MOVE_ANIMATION_MILLIS),
        ) {
            moveAnimationProgress = value
        }
        completedMoveAnimationKey = moveAnimationKey
        runningMoveAnimationKey = null
        currentOnMoveAnimationInProgressChanged(false)
    }
    val activeMoveAnimation = detectedMoveAnimation
        .takeIf { animatePieceMovement && completedMoveAnimationKey != moveAnimationKey }
    val activeMoveAnimationProgress = when {
        activeMoveAnimation == null -> 1f
        runningMoveAnimationKey == moveAnimationKey -> moveAnimationProgress
        else -> 0f
    }
    LaunchedEffect(zoomToCell, viewportSize, contentWidth, contentHeight) {
        val target = zoomToCell?.let { cellId -> cells.firstOrNull { it.id == cellId } }
            ?: return@LaunchedEffect
        val start = viewport
        val targetViewport = focusBoardViewportOnPoint(
            point = target.center,
            viewportWidth = viewportSize.width.toFloat(),
            viewportHeight = viewportSize.height.toFloat(),
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        currentOnZoomChanged(MAX_BOARD_ZOOM)
        Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = BOARD_ZOOM_ANIMATION_MILLIS),
        ) {
            viewport = interpolateBoardViewport(start, targetViewport, value)
        }
    }
    LaunchedEffect(resetViewportKey, selectedPieceId) {
        val selectionWasCleared = previousSelectedPieceId != null && selectedPieceId == null
        val resetWasRequested = previousResetViewportKey != resetViewportKey
        previousSelectedPieceId = selectedPieceId
        previousResetViewportKey = resetViewportKey
        if (!selectionWasCleared && !resetWasRequested) return@LaunchedEffect

        val start = viewport
        val target = BoardViewport()
        currentOnZoomChanged(MIN_BOARD_ZOOM)
        Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = BOARD_ZOOM_ANIMATION_MILLIS),
        ) {
            viewport = interpolateBoardViewport(start, target, value)
        }
    }
    val boardDescription = localized(
        "checkers_board_description",
        localized(if (pieceSet == PieceSet.FAIRY) "premium_pieces" else "standard_pieces"),
    )
    val selectionDescription = localized(
        if (selectedPieceId == null) "piece_not_selected" else "piece_selected",
    )
    val zoomDescription = localized(
        "zoom_percent",
        selectionDescription,
        (viewport.zoom * 100).roundToInt(),
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp))
            .onSizeChanged { size ->
                viewportSize = size
                val coerced = coerceBoardViewport(
                    viewport = viewport,
                    viewportWidth = size.width.toFloat(),
                    viewportHeight = size.height.toFloat(),
                    contentWidth = contentWidth,
                    contentHeight = contentHeight,
                )
                if (coerced != viewport) viewport = coerced
            }
            .semantics {
                contentDescription = boardDescription
                stateDescription = zoomDescription
            }
            .pointerInput(contentWidth, contentHeight) {
                detectTwoFingerBoardTransformGestures { centroid, pan, zoomChange ->
                    val transformed = transformBoardViewport(
                        viewport = viewport,
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                        centroid = BoardPoint(centroid.x, centroid.y),
                        pan = BoardPoint(pan.x, pan.y),
                        zoomChange = zoomChange,
                        contentWidth = contentWidth,
                        contentHeight = contentHeight,
                    )
                    val zoomChanged = transformed.zoom != viewport.zoom
                    viewport = transformed
                    if (zoomChanged) currentOnZoomChanged(transformed.zoom)
                }
            }
            .pointerInput(contentWidth, contentHeight, selectedPieceId, piecesByCell) {
                detectTapGestures { tap ->
                    val boardPoint = viewportPointToBoard(
                        point = BoardPoint(tap.x, tap.y),
                        viewportWidth = size.width.toFloat(),
                        viewportHeight = size.height.toFloat(),
                        viewport = viewport,
                        contentWidth = contentWidth,
                        contentHeight = contentHeight,
                    ) ?: return@detectTapGestures
                    val tappedCell = cells.lastOrNull { contains(it.corners, boardPoint) }
                    val tappedPieceId = tappedCell?.id?.let(piecesByCell::get)?.id
                    currentOnCellSelected(
                        tappedCell?.id?.takeUnless {
                            isRepeatedPieceTap(selectedPieceId, tappedPieceId)
                        },
                    )
                }
            },
    ) {
        val scale = boardScale(
            size.width,
            size.height,
            viewport.zoom,
            contentWidth,
            contentHeight,
        )

        fun BoardPoint.offset(): Offset =
            boardPointToViewport(
                point = this,
                viewportWidth = size.width,
                viewportHeight = size.height,
                viewport = viewport,
                contentWidth = contentWidth,
                contentHeight = contentHeight,
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

        moveLineHints.forEach { hint ->
            val route =
                hint.route.mapNotNull { id -> cells.firstOrNull { it.id == id }?.center?.offset() }
            if (route.size != hint.route.size) return@forEach
            route.zipWithNext().forEach { (start, end) ->
                drawLine(
                    color = if (hint.kind == MoveHintKind.CAPTURE) palette.capture else palette.move,
                    start = start,
                    end = end,
                    strokeWidth = scale * MOVE_DIRECTION_LINE_WIDTH_FACTOR,
                    alpha = 0.55f,
                )
            }
        }

        cells
            .mapNotNull { cell -> piecesByCell[cell.id]?.let { piece -> cell to piece } }
            .sortedBy { (cell, _) -> cell.center.y }
            .forEach { (cell, piece) ->
                if (activeMoveAnimation?.movingPieces?.any { it.piece.id == piece.id } == true) {
                    return@forEach
                }
                val center = cell.center.offset()
                drawPiece(
                    piece = piece,
                    center = center,
                    radius = boardPieceRadius(
                        boardScale = scale,
                        isSelected = piece.id == selectedPieceId,
                    ),
                    textMeasurer = textMeasurer,
                    pieceSet = pieceSet,
                    standardPieceFont = standardPieceFont,
                    fairyPieceImages = fairyPieceImages,
                )
            }

        cells.forEach { cell ->
            piecesByCell[cell.id]?.let { piece ->
                if (piece.id != selectedPieceId && piece.id in attackedPieceIds) {
                    drawCircle(
                        color = palette.capture,
                        radius = scale * 0.091f,
                        center = cell.center.offset(),
                        style = Stroke(3.dp.toPx()),
                    )
                }
            }
        }

        activeMoveAnimation?.let { animation ->
            val progress = activeMoveAnimationProgress.coerceIn(0f, 1f)
            animation.capturedPieces.forEach { captured ->
                val from = cellsById[captured.from]?.center?.offset() ?: return@forEach
                val armyTrophies = trophies.filter { it.capturedByArmy == captured.capturedByArmy }
                val trophyIndex = armyTrophies.indexOfFirst { it.id == captured.piece.id }
                if (trophyIndex < 0) return@forEach
                val to = ThreePlayerBoardGeometry.trophyPosition(
                    army = captured.capturedByArmy,
                    index = trophyIndex,
                    count = armyTrophies.size,
                ).offset()
                drawPiece(
                    piece = captured.piece,
                    center = interpolateOffset(from, to, progress),
                    radius = boardPieceRadius(scale, isSelected = false) *
                            (1f - (1f - BOARD_CAPTURE_MIN_SCALE) * progress),
                    textMeasurer = textMeasurer,
                    pieceSet = pieceSet,
                    standardPieceFont = standardPieceFont,
                    fairyPieceImages = fairyPieceImages,
                )
            }
            animation.movingPieces.forEach { moving ->
                val from = cellsById[moving.from]?.center?.offset() ?: return@forEach
                val to = cellsById[moving.to]?.center?.offset() ?: return@forEach
                drawPiece(
                    piece = moving.piece,
                    center = interpolateOffset(from, to, progress),
                    radius = boardPieceRadius(
                        boardScale = scale,
                        isSelected = moving.piece.id == selectedPieceId,
                    ),
                    textMeasurer = textMeasurer,
                    pieceSet = pieceSet,
                    standardPieceFont = standardPieceFont,
                    fairyPieceImages = fairyPieceImages,
                )
            }
        }

        labels.forEach { label ->
            val layout = textMeasurer.measure(
                text = label.text,
                style = TextStyle(
                    color = palette.label,
                    fontSize = (scale * 0.0595f).toSp(),
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            val edgePosition = label.edgePoint.offset()
            val normalProjection =
                layout.size.width * abs(label.outward.x) / 2f +
                        layout.size.height * abs(label.outward.y) / 2f
            val labelGap = 5.dp.toPx() + normalProjection
            val position = edgePosition + Offset(
                x = label.outward.x * labelGap,
                y = label.outward.y * labelGap,
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    position.x - layout.size.width / 2f,
                    position.y - layout.size.height / 2f
                ),
            )
        }

        if (showBirdsAroundBoard) {
            fairyPieceImages?.birds?.forEach { (army, bird) ->
                drawImageCentered(
                    image = bird,
                    center = ThreePlayerBoardGeometry.birdPosition(army).offset(),
                    maxWidth = scale * 0.26f,
                    maxHeight = scale * 0.26f,
                    flipHorizontally = army == ArmyColor.BLACK,
                )
            }
        }

        ArmyColor.entries.forEach { capturingArmy ->
            val armyTrophies = trophies.filter { it.capturedByArmy == capturingArmy }
            armyTrophies
                .forEachIndexed { index, trophy ->
                    if (activeMoveAnimation?.capturedPieces?.any { it.piece.id == trophy.id } == true) {
                        return@forEachIndexed
                    }
                    val point = ThreePlayerBoardGeometry.trophyPosition(
                        army = capturingArmy,
                        index = index,
                        count = armyTrophies.size,
                    )
                    val motion = activeMoveAnimation?.trophyMotions
                        ?.firstOrNull { it.id == trophy.id }
                    val displayedPoint = motion?.let {
                        interpolateBoardPoint(it.from, it.to, activeMoveAnimationProgress)
                    } ?: point
                    drawPiece(
                        piece = BoardPiece(
                            id = trophy.id,
                            type = trophy.type,
                            army = trophy.army,
                            bodyArmy = trophy.bodyArmy,
                            cellId = BoardCellId(0, 0, 0),
                        ),
                        center = displayedPoint.offset(),
                        radius = scale * 0.063f,
                        textMeasurer = textMeasurer,
                        pieceSet = pieceSet,
                        standardPieceFont = standardPieceFont,
                        fairyPieceImages = fairyPieceImages,
                    )
                }
        }
    }
}

fun threePlayerBoardAspectRatio(
    pieceSet: PieceSet,
    showDecorativeBirds: Boolean,
    zoom: Float,
): Float {
    val showBirdsAroundBoard = pieceSet == PieceSet.FAIRY && showDecorativeBirds
    val contentWidth =
        if (showBirdsAroundBoard) FAIRY_BOARD_CONTENT_WIDTH else STANDARD_BOARD_CONTENT_WIDTH
    val contentHeight =
        if (showBirdsAroundBoard) FAIRY_BOARD_CONTENT_HEIGHT else STANDARD_BOARD_CONTENT_HEIGHT
    return contentWidth / (contentHeight * zoom.coerceIn(MIN_BOARD_ZOOM, MAX_BOARD_ZOOM))
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPiece(
    piece: BoardPiece,
    center: Offset,
    radius: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    pieceSet: PieceSet,
    standardPieceFont: FontFamily,
    fairyPieceImages: FairyPieceImages?,
) {
    when (pieceSet) {
        PieceSet.STANDARD -> {
            val layout = textMeasurer.measure(
                text = pieceGlyph(piece.type),
                style = TextStyle(
                    color = pieceColor(piece.bodyArmy),
                    fontFamily = standardPieceFont,
                    fontSize = (radius * 1.9f).toSp(),
                    fontWeight = FontWeight.Normal,
                    shadow = Shadow(
                        color = if (piece.bodyArmy == ArmyColor.BLACK) {
                            Color.White.copy(alpha = 0.7f)
                        } else {
                            Color.Black.copy(alpha = 0.7f)
                        },
                        offset = Offset(radius * 0.04f, radius * 0.06f),
                        blurRadius = radius * 0.08f,
                    ),
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

        PieceSet.FAIRY -> drawImageCentered(
            image = checkNotNull(fairyPieceImages).pieces.getValue(piece.type to piece.bodyArmy),
            center = center,
            maxWidth = radius * 2f,
            maxHeight = radius * 2f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageCentered(
    image: ImageBitmap,
    center: Offset,
    maxWidth: Float,
    maxHeight: Float,
    flipHorizontally: Boolean = false,
) {
    if (image.width <= 0 || image.height <= 0) return
    val imageSize = scaledImageSize(image.width, image.height, maxWidth, maxHeight)
    val width = imageSize.width
    val height = imageSize.height
    val destination = IntOffset(
        x = (center.x - width / 2f).roundToInt(),
        y = (center.y - height / 1.5f).roundToInt(),
    )

    fun drawAt() {
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = destination,
            dstSize = imageSize,
            filterQuality = FilterQuality.High,
        )
    }

    if (flipHorizontally) {
        scale(scaleX = -1f, scaleY = 1f, pivot = center) { drawAt() }
    } else {
        drawAt()
    }
}

internal fun scaledImageSize(
    sourceWidth: Int,
    sourceHeight: Int,
    maxWidth: Float,
    maxHeight: Float,
): IntSize {
    require(sourceWidth > 0 && sourceHeight > 0) { "Image dimensions must be positive" }
    val imageScale = min(maxWidth / sourceWidth, maxHeight / sourceHeight)
    return IntSize(
        width = (sourceWidth * imageScale).roundToInt().coerceAtLeast(1),
        height = (sourceHeight * imageScale).roundToInt().coerceAtLeast(1),
    )
}

internal fun boardPieceRadius(boardScale: Float, isSelected: Boolean): Float =
    boardScale * BOARD_PIECE_RADIUS_FACTOR * if (isSelected) SELECTED_PIECE_SCALE else 1f

internal fun isRepeatedPieceTap(selectedPieceId: String?, tappedPieceId: String?): Boolean =
    selectedPieceId != null && selectedPieceId == tappedPieceId

private const val BOARD_PIECE_RADIUS_FACTOR = 0.084f * 1.15f
private const val SELECTED_PIECE_SCALE = 1.3f

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

internal fun pieceGlyph(type: PieceType): String = when (type) {
    PieceType.KING -> "♚"
    PieceType.QUEEN -> "♛"
    PieceType.ROOK -> "♜"
    PieceType.BISHOP -> "♝"
    PieceType.KNIGHT -> "♞"
    PieceType.PAWN -> "♟"
}

private fun pieceColor(army: ArmyColor): Color = when (army) {
    ArmyColor.WHITE -> Color(0xFFF7F1E4)
    ArmyColor.RED -> Color(0xFFB72C35)
    ArmyColor.BLACK -> Color(0xFF171310)
}

internal const val BOARD_TEXT_LAYOUT_CACHE_SIZE: Int = 48
internal const val MOVE_DIRECTION_LINE_WIDTH_FACTOR: Float = 0.00625f
private const val BOARD_ZOOM_ANIMATION_MILLIS: Int = 320
private const val BOARD_MOVE_ANIMATION_MILLIS: Int = 420
private const val BOARD_CAPTURE_MIN_SCALE: Float = 0.65f

private data class BoardAnimationSnapshot(
    val key: BoardMoveAnimationKey?,
    val pieces: List<BoardPiece>,
    val trophies: List<BoardTrophy>,
)

private class BoardAnimationSnapshotHolder(var snapshot: BoardAnimationSnapshot)

private data class BoardPieceMotion(
    val piece: BoardPiece,
    val from: BoardCellId,
    val to: BoardCellId,
)

private data class CapturedPieceMotion(
    val piece: BoardPiece,
    val from: BoardCellId,
    val capturedByArmy: ArmyColor,
)

private data class TrophyMotion(
    val id: String,
    val from: BoardPoint,
    val to: BoardPoint,
)

private data class BoardMoveAnimation(
    val movingPieces: List<BoardPieceMotion>,
    val capturedPieces: List<CapturedPieceMotion>,
    val trophyMotions: List<TrophyMotion>,
)

private fun createBoardMoveAnimation(
    previous: BoardAnimationSnapshot,
    next: BoardAnimationSnapshot,
): BoardMoveAnimation? {
    val previousKey = previous.key ?: return null
    val nextKey = next.key ?: return null
    if (previousKey.gameId != nextKey.gameId || nextKey.moveCount != previousKey.moveCount + 1) {
        return null
    }

    val previousPieces = previous.pieces.associateBy(BoardPiece::id)
    val nextPieces = next.pieces.associateBy(BoardPiece::id)
    val movingPieces = previousPieces.values.mapNotNull { oldPiece ->
        val updatedPiece = nextPieces[oldPiece.id] ?: return@mapNotNull null
        if (oldPiece.cellId == updatedPiece.cellId) return@mapNotNull null
        BoardPieceMotion(updatedPiece, oldPiece.cellId, updatedPiece.cellId)
    }
    if (movingPieces.isEmpty()) return null

    val previousTrophyIds = previous.trophies.mapTo(mutableSetOf(), BoardTrophy::id)
    val capturedPieces = next.trophies
        .filter { it.id !in previousTrophyIds }
        .mapNotNull { trophy ->
            previousPieces[trophy.id]
                ?.takeIf { it.id !in nextPieces }
                ?.let { CapturedPieceMotion(it, it.cellId, trophy.capturedByArmy) }
        }
    val previousTrophyPositions = trophyPositions(previous.trophies)
    val trophyMotions = trophyPositions(next.trophies).mapNotNull { (id, to) ->
        val from = previousTrophyPositions[id] ?: return@mapNotNull null
        if (from == to) null else TrophyMotion(id, from, to)
    }
    return BoardMoveAnimation(movingPieces, capturedPieces, trophyMotions)
}

private fun trophyPositions(trophies: List<BoardTrophy>): Map<String, BoardPoint> =
    ArmyColor.entries.flatMap { army ->
        val armyTrophies = trophies.filter { it.capturedByArmy == army }
        armyTrophies.mapIndexed { index, trophy ->
            trophy.id to ThreePlayerBoardGeometry.trophyPosition(army, index, armyTrophies.size)
        }
    }.toMap()

private fun interpolateBoardPoint(from: BoardPoint, to: BoardPoint, fraction: Float): BoardPoint =
    BoardPoint(
        x = from.x + (to.x - from.x) * fraction,
        y = from.y + (to.y - from.y) * fraction,
    )

private fun interpolateOffset(from: Offset, to: Offset, fraction: Float): Offset = Offset(
    x = from.x + (to.x - from.x) * fraction,
    y = from.y + (to.y - from.y) * fraction,
)

private fun interpolateBoardViewport(
    start: BoardViewport,
    target: BoardViewport,
    fraction: Float,
): BoardViewport = BoardViewport(
    zoom = start.zoom + (target.zoom - start.zoom) * fraction,
    panX = start.panX + (target.panX - start.panX) * fraction,
    panY = start.panY + (target.panY - start.panY) * fraction,
)

private data class FairyPieceImages(
    val pieces: Map<Pair<PieceType, ArmyColor>, ImageBitmap>,
    val birds: Map<ArmyColor, ImageBitmap>,
)

@Composable
private fun loadFairyPieceImages(): FairyPieceImages {
    val drawables = Res.allDrawableResources
    return FairyPieceImages(
        pieces = mapOf(
            (PieceType.PAWN to ArmyColor.WHITE) to imageResource(drawables.getValue("pawn_0")),
            (PieceType.PAWN to ArmyColor.RED) to imageResource(drawables.getValue("pawn_1")),
            (PieceType.PAWN to ArmyColor.BLACK) to imageResource(drawables.getValue("pawn_2")),
            (PieceType.KNIGHT to ArmyColor.WHITE) to imageResource(drawables.getValue("knight_0")),
            (PieceType.KNIGHT to ArmyColor.RED) to imageResource(drawables.getValue("knight_1")),
            (PieceType.KNIGHT to ArmyColor.BLACK) to imageResource(drawables.getValue("knight_2")),
            (PieceType.BISHOP to ArmyColor.WHITE) to imageResource(drawables.getValue("bishop_0")),
            (PieceType.BISHOP to ArmyColor.RED) to imageResource(drawables.getValue("bishop_1")),
            (PieceType.BISHOP to ArmyColor.BLACK) to imageResource(drawables.getValue("bishop_2")),
            (PieceType.ROOK to ArmyColor.WHITE) to imageResource(drawables.getValue("rook_0")),
            (PieceType.ROOK to ArmyColor.RED) to imageResource(drawables.getValue("rook_1")),
            (PieceType.ROOK to ArmyColor.BLACK) to imageResource(drawables.getValue("rook_2")),
            (PieceType.QUEEN to ArmyColor.WHITE) to imageResource(drawables.getValue("queen_0")),
            (PieceType.QUEEN to ArmyColor.RED) to imageResource(drawables.getValue("queen_1")),
            (PieceType.QUEEN to ArmyColor.BLACK) to imageResource(drawables.getValue("queen_2")),
            (PieceType.KING to ArmyColor.WHITE) to imageResource(drawables.getValue("king_0")),
            (PieceType.KING to ArmyColor.RED) to imageResource(drawables.getValue("king_1")),
            (PieceType.KING to ArmyColor.BLACK) to imageResource(drawables.getValue("king_2")),
        ),
        birds = mapOf(
            ArmyColor.WHITE to imageResource(drawables.getValue("bird_0")),
            ArmyColor.RED to imageResource(drawables.getValue("bird_1")),
            ArmyColor.BLACK to imageResource(drawables.getValue("bird_2")),
        ),
    )
}
