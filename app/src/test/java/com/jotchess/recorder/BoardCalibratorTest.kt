package com.jotchess.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardCalibratorTest {

    @Test
    fun orderClockwise_sortsShuffledCorners() {
        val tl = CornerPoint(10f, 10f)
        val tr = CornerPoint(200f, 15f)
        val br = CornerPoint(190f, 210f)
        val bl = CornerPoint(5f, 200f)
        val ordered = BoardCalibrator.orderClockwise(listOf(br, tl, bl, tr))
        assertTrue(BoardCalibrator.nearlyEqual(ordered[0], tl))
        assertTrue(BoardCalibrator.nearlyEqual(ordered[1], tr))
        assertTrue(BoardCalibrator.nearlyEqual(ordered[2], br))
        assertTrue(BoardCalibrator.nearlyEqual(ordered[3], bl))
    }

    @Test
    fun normalizeRotation_snapsToNearest90() {
        assertEquals(0, BoardCalibrator.normalizeRotation(10))
        assertEquals(90, BoardCalibrator.normalizeRotation(100))
        assertEquals(180, BoardCalibrator.normalizeRotation(200))
        assertEquals(270, BoardCalibrator.normalizeRotation(260))
        assertEquals(0, BoardCalibrator.normalizeRotation(350))
    }

    @Test
    fun destinationPoints_rotationCyclesQuad() {
        val base = BoardCalibrator.destinationPoints(0)
        val rot90 = BoardCalibrator.destinationPoints(90)
        assertEquals(base[1], rot90[0])
        assertEquals(base[2], rot90[1])
        assertEquals(base[3], rot90[2])
        assertEquals(base[0], rot90[3])
    }

    @Test
    fun squareRect_a1IsBottomLeftInnerCell() {
        val calibrator = BoardCalibrator()
        val rect = calibrator.squareRect("a1")
        assertEquals(10, rect.x)
        assertEquals(360, rect.y)
        assertEquals(30, rect.width)
        assertEquals(30, rect.height)
    }

    @Test
    fun destinationPoints_areBoardSized() {
        val pts = BoardCalibrator.destinationPoints(0)
        assertEquals(0.0, pts[0].x, 0.01)
        assertEquals(0.0, pts[0].y, 0.01)
        assertEquals(BoardCalibrator.BOARD_SIZE.toDouble(), pts[2].x, 0.01)
        assertEquals(BoardCalibrator.BOARD_SIZE.toDouble(), pts[2].y, 0.01)
    }
}
