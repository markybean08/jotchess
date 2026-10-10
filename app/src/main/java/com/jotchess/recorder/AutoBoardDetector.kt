package com.jotchess.recorder

import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.pow

/**
 * Automatic chessboard corner detection + white-side orientation.
 *
 * Primary: findChessboardCornersSB on the empty middle band (7×3 internal
 * corners spanning ranks 3–6), then RANSAC homography to extrapolate the
 * true outer 8×8 quad — ignoring vinyl border margins.
 */
class AutoBoardDetector(
    private val calibrator: BoardCalibrator = BoardCalibrator(),
) {

    data class Detection(
        val corners: List<CornerPoint>,
        val rotation: Int,
        val orientationAmbiguous: Boolean,
        val source: Source,
    )

    data class OrientationResult(
        val rotation: Int,
        val ambiguous: Boolean,
        val polarityGap: Double,
        val parity: Double,
    )

    enum class Source { MIDDLE_BAND }

    /**
     * Find the outer board quad and the rotation that puts white on ranks 1–2.
     */
    fun detect(rgb: Mat): Detection? {
        val corners = detectMiddleBandOuterCorners(rgb) ?: return null
        val ordered = BoardCalibrator.orderClockwise(corners)
        val orientation = bestOrientation(rgb, ordered)
        return Detection(
            corners = ordered,
            rotation = orientation.rotation,
            orientationAmbiguous = orientation.ambiguous,
            source = Source.MIDDLE_BAND,
        )
    }

    /** Live candidate quad for overlay (no orientation lock). */
    fun detectQuadOnly(rgb: Mat): List<CornerPoint>? {
        val corners = detectMiddleBandOuterCorners(rgb) ?: return null
        return BoardCalibrator.orderClockwise(corners)
    }

    private fun detectMiddleBandOuterCorners(rgb: Mat): List<CornerPoint>? {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.equalizeHist(gray, gray)

        val corners = MatOfPoint2f()
        val pattern = Size(PATTERN_COLS.toDouble(), PATTERN_ROWS.toDouble())
        val flags = Calib3d.CALIB_CB_EXHAUSTIVE or Calib3d.CALIB_CB_NORMALIZE_IMAGE

        val found = Calib3d.findChessboardCornersSB(gray, pattern, corners, flags)
        gray.release()
        if (!found || corners.rows() < PATTERN_COLS * PATTERN_ROWS) {
            corners.release()
            return null
        }

        val imagePts = corners.toArray()
        corners.release()
        if (imagePts.size < PATTERN_COLS * PATTERN_ROWS) return null

        val outer = outerCornersFromMiddleBand(imagePts)
            ?: outerCornersFromMiddleBand(reverseRows(imagePts))
            ?: return null

        return outer.map { toCorner(it) }
    }

    /**
     * Map 7×3 SB corners to board plane (i+1, j+3), fit homography, project
     * outer corners (0,0)/(8,0)/(8,8)/(0,8). Returns null if reprojection is poor.
     */
    fun outerCornersFromMiddleBand(imagePts: Array<Point>): Array<Point>? {
        if (imagePts.size != PATTERN_COLS * PATTERN_ROWS) return null

        val boardPts = MatOfPoint2f(*middleBandBoardPoints().toTypedArray())
        val imgPts = MatOfPoint2f(*imagePts)
        val homography = Calib3d.findHomography(
            boardPts,
            imgPts,
            Calib3d.RANSAC,
            RANSAC_REPROJ_THRESHOLD,
        )
        boardPts.release()
        imgPts.release()
        if (homography.empty()) {
            homography.release()
            return null
        }

        val err = meanReprojectionError(imagePts, middleBandBoardPoints(), homography)
        if (err > MAX_MEAN_REPROJ_ERROR) {
            homography.release()
            return null
        }

        val outerBoard = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(8.0, 0.0),
            Point(8.0, 8.0),
            Point(0.0, 8.0),
        )
        val outerImage = MatOfPoint2f()
        Core.perspectiveTransform(outerBoard, outerImage, homography)
        val result = outerImage.toArray()
        outerBoard.release()
        outerImage.release()
        homography.release()
        return result
    }

    /**
     * Score rotations with checkerboard parity (axis) + relative occupancy (polarity).
     */
    fun bestOrientation(rgb: Mat, corners: List<CornerPoint>): OrientationResult {
        var best: OrientationResult? = null

        // Try axis candidates 0° and 90°; polarity chooses 0 vs 180 within each.
        for (axis in listOf(0, 90)) {
            for (flip in listOf(0, 180)) {
                val rot = BoardCalibrator.normalizeRotation(axis + flip)
                calibrator.setCorners(corners, rot)
                val warped = calibrator.warp(rgb) ?: continue
                val parity = checkerboardParity(warped)
                val polarity = whiteBottomPolarity(warped)
                warped.release()

                // Prefer positive parity (dark a1 / standard coloring) and
                // positive polarity (white pieces on ranks 1–2).
                val score = paritySignScore(parity) + polarity
                val candidate = OrientationResult(
                    rotation = rot,
                    ambiguous = abs(polarity) < POLARITY_GAP,
                    polarityGap = polarity,
                    parity = parity,
                )
                if (best == null || score > (paritySignScore(best.parity) + best.polarityGap)) {
                    best = candidate
                }
            }
        }

        return best ?: OrientationResult(0, ambiguous = true, polarityGap = 0.0, parity = 0.0)
    }

    companion object {
        const val PATTERN_COLS = 7
        const val PATTERN_ROWS = 3
        const val RANSAC_REPROJ_THRESHOLD = 3.0
        const val MAX_MEAN_REPROJ_ERROR = 4.0
        /** |polarity| below this → show Flip 180° in the UI. */
        const val POLARITY_GAP = 6.0

        fun toCorner(p: Point): CornerPoint = CornerPoint(p.x.toFloat(), p.y.toFloat())

        /** Ideal board-plane points for SB corner (i, j): (i+1, j+3). */
        fun middleBandBoardPoints(): List<Point> {
            val pts = ArrayList<Point>(PATTERN_COLS * PATTERN_ROWS)
            for (j in 0 until PATTERN_ROWS) {
                for (i in 0 until PATTERN_COLS) {
                    pts += Point((i + 1).toDouble(), (j + 3).toDouble())
                }
            }
            return pts
        }

        /** Reverse row order of a 7×3 row-major corner array (SB origin flip). */
        fun reverseRows(pts: Array<Point>): Array<Point> {
            val out = Array(pts.size) { Point() }
            for (j in 0 until PATTERN_ROWS) {
                val srcRow = PATTERN_ROWS - 1 - j
                for (i in 0 until PATTERN_COLS) {
                    out[j * PATTERN_COLS + i] = pts[srcRow * PATTERN_COLS + i]
                }
            }
            return out
        }

        /**
         * Lighting-cancelled checkerboard parity over empty middle ranks 3–6.
         * Positive C matches dark-a1 coloring under linear gradients.
         */
        fun parityFromMeans(means: Map<String, Double>): Double {
            var sum = 0.0
            for (file in 0 until 8) {
                for (rank in 3..6) {
                    val sq = "${'a' + file}$rank"
                    val l = means[sq] ?: continue
                    val sign = (-1.0).pow(file + rank)
                    sum += sign * l
                }
            }
            return sum
        }

        /**
         * White-at-bottom polarity: occupancy brightness of ranks 1–2 vs 7–8
         * relative to adjacent empty ranks 3 and 6 on the same files.
         */
        fun polarityFromMeans(means: Map<String, Double>): Double {
            var total = 0.0
            var count = 0
            for (file in 0 until 8) {
                val f = 'a' + file
                val r1 = means["${f}1"] ?: continue
                val r2 = means["${f}2"] ?: continue
                val r3 = means["${f}3"] ?: continue
                val r6 = means["${f}6"] ?: continue
                val r7 = means["${f}7"] ?: continue
                val r8 = means["${f}8"] ?: continue
                val bottom = (r1 + r2) / 2.0 - r3
                val top = (r7 + r8) / 2.0 - r6
                total += bottom - top
                count++
            }
            return if (count == 0) 0.0 else total / count
        }

        private fun paritySignScore(parity: Double): Double {
            // Soft preference for the dark-a1 axis; magnitude still matters.
            return parity
        }

        private fun meanReprojectionError(
            imagePts: Array<Point>,
            boardPts: List<Point>,
            homography: Mat,
        ): Double {
            val src = MatOfPoint2f(*boardPts.toTypedArray())
            val projected = MatOfPoint2f()
            Core.perspectiveTransform(src, projected, homography)
            val proj = projected.toArray()
            src.release()
            projected.release()
            if (proj.size != imagePts.size) return Double.POSITIVE_INFINITY
            var sum = 0.0
            for (i in imagePts.indices) {
                val dx = imagePts[i].x - proj[i].x
                val dy = imagePts[i].y - proj[i].y
                sum += kotlin.math.hypot(dx, dy)
            }
            return sum / imagePts.size
        }
    }

    private fun checkerboardParity(warpedRgb: Mat): Double {
        return parityFromMeans(squareLMeans(warpedRgb, ranks = 3..6))
    }

    private fun whiteBottomPolarity(warpedRgb: Mat): Double {
        return polarityFromMeans(squareLMeans(warpedRgb, ranks = 1..8))
    }

    private fun squareLMeans(warpedRgb: Mat, ranks: IntRange): Map<String, Double> {
        val lab = Mat()
        Imgproc.cvtColor(warpedRgb, lab, Imgproc.COLOR_RGB2Lab)
        if (lab.type() != CvType.CV_8UC3) {
            val converted = Mat()
            lab.convertTo(converted, CvType.CV_8UC3)
            lab.release()
            return squareLMeansFromLab(converted, ranks).also { converted.release() }
        }
        return squareLMeansFromLab(lab, ranks).also { lab.release() }
    }

    private fun squareLMeansFromLab(lab: Mat, ranks: IntRange): Map<String, Double> {
        val means = HashMap<String, Double>(64)
        for (file in 'a'..'h') {
            for (rank in ranks) {
                val square = "$file$rank"
                val rect = calibrator.squareRect(square)
                val roi = Mat(lab, rect)
                // Channel 0 = L in OpenCV Lab.
                means[square] = Core.mean(roi).`val`[0]
                roi.release()
            }
        }
        return means
    }
}
