package com.chesstree.game.presentation.board

import com.chesstree.game.domain.ArmyColor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BoardViewportTest {
    @Test
    fun fitScaleKeepsTheWholeBoardInsidePortraitAndLandscapeViewports() {
        listOf(
            328f to 420f,
            760f to 320f,
        ).forEach { (width, height) ->
            val visualAnchors = buildList {
                ThreePlayerBoardGeometry.cells.forEach { cell -> addAll(cell.corners) }
                ThreePlayerBoardGeometry.labels.forEach { label -> add(label.position) }
                ArmyColor.entries.forEach { army ->
                    repeat(16) { index ->
                        add(ThreePlayerBoardGeometry.trophyPosition(army, index, count = 16))
                    }
                }
            }
            val scale = boardScale(width, height)
            visualAnchors.forEach { anchor ->
                val screenPoint = boardPointToViewport(
                    point = anchor,
                    viewportWidth = width,
                    viewportHeight = height,
                    viewport = BoardViewport(),
                )
                val margin = VISUAL_MARGIN * scale
                assertTrue(screenPoint.x >= margin - FLOAT_TOLERANCE)
                assertTrue(screenPoint.y >= margin - FLOAT_TOLERANCE)
                assertTrue(screenPoint.x <= width - margin + FLOAT_TOLERANCE)
                assertTrue(screenPoint.y <= height - margin + FLOAT_TOLERANCE)
            }
        }

        listOf(
            328f to 420f,
            760f to 320f,
        ).forEach { (width, height) ->
            val birdPositions = ArmyColor.entries.map(ThreePlayerBoardGeometry::birdPosition)
            val scale = boardScale(
                width,
                height,
                contentWidth = FAIRY_BOARD_CONTENT_WIDTH,
                contentHeight = FAIRY_BOARD_CONTENT_HEIGHT,
            )
            birdPositions.forEach { anchor ->
                val screenPoint = boardPointToViewport(
                    point = anchor,
                    viewportWidth = width,
                    viewportHeight = height,
                    viewport = BoardViewport(),
                    contentWidth = FAIRY_BOARD_CONTENT_WIDTH,
                    contentHeight = FAIRY_BOARD_CONTENT_HEIGHT,
                )
                val margin = 0.13f * scale
                assertTrue(screenPoint.x >= margin - FLOAT_TOLERANCE)
                assertTrue(screenPoint.y >= margin - FLOAT_TOLERANCE)
                assertTrue(screenPoint.x <= width - margin + FLOAT_TOLERANCE)
                assertTrue(screenPoint.y <= height - margin + FLOAT_TOLERANCE)
            }
        }
    }

    @Test
    fun boardAndViewportCoordinatesRemainInverseAfterZoomAndPan() {
        val viewport = BoardViewport(zoom = 2f, panX = 37f, panY = -24f)
        val boardPoint = BoardPoint(0.42f, -0.31f)
        val screenPoint = boardPointToViewport(boardPoint, 420f, 420f, viewport)
        val restored = assertNotNull(
            viewportPointToBoard(screenPoint, 420f, 420f, viewport),
        )

        assertClose(boardPoint.x, restored.x)
        assertClose(boardPoint.y, restored.y)
    }

    @Test
    fun zoomKeepsTheBoardPointUnderTheGestureCentroidStationary() {
        val width = 420f
        val height = 420f
        val centroid = BoardPoint(120f, 160f)
        val pointBeforeZoom = assertNotNull(
            viewportPointToBoard(centroid, width, height, BoardViewport()),
        )

        val zoomed = transformBoardViewport(
            viewport = BoardViewport(),
            viewportWidth = width,
            viewportHeight = height,
            centroid = centroid,
            pan = BoardPoint(0f, 0f),
            zoomChange = 2f,
        )
        val centroidAfterZoom = boardPointToViewport(pointBeforeZoom, width, height, zoomed)

        assertClose(centroid.x, centroidAfterZoom.x)
        assertClose(centroid.y, centroidAfterZoom.y)
    }

    @Test
    fun zoomAndPanAreClampedToSafeBounds() {
        val viewport = transformBoardViewport(
            viewport = BoardViewport(),
            viewportWidth = 400f,
            viewportHeight = 400f,
            centroid = BoardPoint(200f, 200f),
            pan = BoardPoint(10_000f, -10_000f),
            zoomChange = 10f,
        )

        assertEquals(MAX_BOARD_ZOOM, viewport.zoom)
        assertTrue(viewport.panX < 10_000f)
        assertTrue(viewport.panY > -10_000f)
        assertEquals(BoardViewport(), coerceBoardViewport(BoardViewport(panX = 20f), 400f, 400f))
    }

    @Test
    fun returningToMinimumZoomRecentersTheBoard() {
        val fitted = transformBoardViewport(
            viewport = BoardViewport(zoom = 2f, panX = 80f, panY = -60f),
            viewportWidth = 400f,
            viewportHeight = 400f,
            centroid = BoardPoint(200f, 200f),
            pan = BoardPoint(0f, 0f),
            zoomChange = 0.1f,
        )

        assertEquals(BoardViewport(), fitted)
    }

    @Test
    fun zeroSizedViewportDoesNotProduceInvalidCoordinates() {
        assertEquals(0f, boardScale(0f, 400f))
        assertEquals(
            null,
            viewportPointToBoard(BoardPoint(0f, 0f), 0f, 400f, BoardViewport()),
        )
        assertEquals(
            BoardViewport(),
            transformBoardViewport(
                viewport = BoardViewport(zoom = 2f, panX = 10f),
                viewportWidth = 0f,
                viewportHeight = 400f,
                centroid = BoardPoint(0f, 0f),
                pan = BoardPoint(0f, 0f),
                zoomChange = 1f,
            ),
        )
    }

    private fun assertClose(expected: Float, actual: Float) {
        assertTrue(abs(expected - actual) < FLOAT_TOLERANCE, "Expected $expected, got $actual")
    }

    private companion object {
        const val FLOAT_TOLERANCE: Float = 0.001f
        const val VISUAL_MARGIN: Float = 0.04f
    }
}
