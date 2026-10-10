package com.jotchess.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Point
import kotlin.math.abs

class AutoBoardDetectorTest {

    @Test
    fun middleBandBoardPoints_mapToFilePlusOneRankPlusThree() {
        val pts = AutoBoardDetector.middleBandBoardPoints()
        assertEquals(21, pts.size)
        // (i=0,j=0) → (1, 3); (i=6,j=2) → (7, 5)
        assertEquals(1.0, pts[0].x, 0.01)
        assertEquals(3.0, pts[0].y, 0.01)
        assertEquals(7.0, pts[20].x, 0.01)
        assertEquals(5.0, pts[20].y, 0.01)
    }

    @Test
    fun reverseRows_flipsBandOrigin() {
        val pts = Array(21) { i -> Point(i.toDouble(), 0.0) }
        val rev = AutoBoardDetector.reverseRows(pts)
        // First row becomes old last row (indices 14..20)
        assertEquals(14.0, rev[0].x, 0.01)
        assertEquals(20.0, rev[6].x, 0.01)
        assertEquals(0.0, rev[14].x, 0.01)
    }

    @Test
    fun parityFromMeans_positiveForDarkA1Checkerboard() {
        // Dark squares (f+r odd) darker; light squares brighter.
        val means = HashMap<String, Double>()
        for (file in 0 until 8) {
            for (rank in 3..6) {
                val dark = (file + rank) % 2 == 1
                means["${'a' + file}$rank"] = if (dark) 40.0 else 200.0
            }
        }
        val c = AutoBoardDetector.parityFromMeans(means)
        assertTrue(c > 0.0)
    }

    @Test
    fun polarityFromMeans_prefersWhiteOnBottom() {
        val means = HashMap<String, Double>()
        for (file in 0 until 8) {
            val f = 'a' + file
            // Empty middle baseline
            means["${f}3"] = 120.0
            means["${f}4"] = 120.0
            means["${f}5"] = 120.0
            means["${f}6"] = 120.0
            // Bright white pieces on ranks 1–2
            means["${f}1"] = 200.0
            means["${f}2"] = 200.0
            // Dark black pieces on ranks 7–8
            means["${f}7"] = 50.0
            means["${f}8"] = 50.0
        }
        val p = AutoBoardDetector.polarityFromMeans(means)
        assertTrue(p > AutoBoardDetector.POLARITY_GAP)
    }

    @Test
    fun polarityFromMeans_ambiguousWhenSymmetric() {
        val means = HashMap<String, Double>()
        for (file in 0 until 8) {
            val f = 'a' + file
            for (rank in 1..8) {
                means["$f$rank"] = 100.0
            }
        }
        val p = AutoBoardDetector.polarityFromMeans(means)
        assertTrue(abs(p) < AutoBoardDetector.POLARITY_GAP)
    }
}
