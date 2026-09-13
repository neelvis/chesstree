package com.chesstree.game.presentation.board

import kotlin.math.min

internal data class BoardViewport(
    val zoom: Float = MIN_BOARD_ZOOM,
    val panX: Float = 0f,
    val panY: Float = 0f,
)

internal fun boardScale(
    viewportWidth: Float,
    viewportHeight: Float,
    zoom: Float = MIN_BOARD_ZOOM,
    contentWidth: Float = STANDARD_BOARD_CONTENT_WIDTH,
    contentHeight: Float = STANDARD_BOARD_CONTENT_HEIGHT,
): Float {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return 0f
    return min(
        viewportWidth / contentWidth,
        viewportHeight / contentHeight,
    ) * zoom.coerceIn(MIN_BOARD_ZOOM, MAX_BOARD_ZOOM)
}

internal fun boardPointToViewport(
    point: BoardPoint,
    viewportWidth: Float,
    viewportHeight: Float,
    viewport: BoardViewport,
    contentWidth: Float = STANDARD_BOARD_CONTENT_WIDTH,
    contentHeight: Float = STANDARD_BOARD_CONTENT_HEIGHT,
): BoardPoint {
    val scale = boardScale(viewportWidth, viewportHeight, viewport.zoom, contentWidth, contentHeight)
    return BoardPoint(
        x = viewportWidth / 2f + viewport.panX + point.x * scale,
        y = viewportHeight / 2f + viewport.panY + point.y * scale,
    )
}

internal fun viewportPointToBoard(
    point: BoardPoint,
    viewportWidth: Float,
    viewportHeight: Float,
    viewport: BoardViewport,
    contentWidth: Float = STANDARD_BOARD_CONTENT_WIDTH,
    contentHeight: Float = STANDARD_BOARD_CONTENT_HEIGHT,
): BoardPoint? {
    val scale = boardScale(viewportWidth, viewportHeight, viewport.zoom, contentWidth, contentHeight)
    if (scale <= 0f) return null
    return BoardPoint(
        x = (point.x - viewportWidth / 2f - viewport.panX) / scale,
        y = (point.y - viewportHeight / 2f - viewport.panY) / scale,
    )
}

internal fun transformBoardViewport(
    viewport: BoardViewport,
    viewportWidth: Float,
    viewportHeight: Float,
    centroid: BoardPoint,
    pan: BoardPoint,
    zoomChange: Float,
    contentWidth: Float = STANDARD_BOARD_CONTENT_WIDTH,
    contentHeight: Float = STANDARD_BOARD_CONTENT_HEIGHT,
): BoardViewport {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return BoardViewport()

    val oldZoom = viewport.zoom.coerceIn(MIN_BOARD_ZOOM, MAX_BOARD_ZOOM)
    val newZoom = (oldZoom * zoomChange).coerceIn(MIN_BOARD_ZOOM, MAX_BOARD_ZOOM)
    val zoomRatio = newZoom / oldZoom
    val centroidFromCenterX = centroid.x - viewportWidth / 2f
    val centroidFromCenterY = centroid.y - viewportHeight / 2f
    val transformed = BoardViewport(
        zoom = newZoom,
        panX = centroidFromCenterX + pan.x -
            (centroidFromCenterX - viewport.panX) * zoomRatio,
        panY = centroidFromCenterY + pan.y -
            (centroidFromCenterY - viewport.panY) * zoomRatio,
    )
    return coerceBoardViewport(
        transformed,
        viewportWidth,
        viewportHeight,
        contentWidth,
        contentHeight,
    )
}

internal fun coerceBoardViewport(
    viewport: BoardViewport,
    viewportWidth: Float,
    viewportHeight: Float,
    contentWidth: Float = STANDARD_BOARD_CONTENT_WIDTH,
    contentHeight: Float = STANDARD_BOARD_CONTENT_HEIGHT,
): BoardViewport {
    if (viewportWidth <= 0f || viewportHeight <= 0f) return BoardViewport()

    val zoom = viewport.zoom.coerceIn(MIN_BOARD_ZOOM, MAX_BOARD_ZOOM)
    val scale = boardScale(viewportWidth, viewportHeight, zoom, contentWidth, contentHeight)
    val maxPanX = ((contentWidth * scale - viewportWidth) / 2f).coerceAtLeast(0f)
    val maxPanY = ((contentHeight * scale - viewportHeight) / 2f).coerceAtLeast(0f)
    return BoardViewport(
        zoom = zoom,
        panX = if (maxPanX == 0f) 0f else viewport.panX.coerceIn(-maxPanX, maxPanX),
        panY = if (maxPanY == 0f) 0f else viewport.panY.coerceIn(-maxPanY, maxPanY),
    )
}

internal const val MIN_BOARD_ZOOM: Float = 1f
internal const val MAX_BOARD_ZOOM: Float = 2.5f

internal const val STANDARD_BOARD_CONTENT_WIDTH: Float = 2.35f
internal const val STANDARD_BOARD_CONTENT_HEIGHT: Float = 2.18f
internal const val FAIRY_BOARD_CONTENT_WIDTH: Float = 2.50f
internal const val FAIRY_BOARD_CONTENT_HEIGHT: Float = 2.78f
