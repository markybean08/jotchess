package com.jotchess.recorder

import android.content.Context
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Image-space point used for board corner calibration (JVM-friendly). */
data class CornerPoint(val x: Float, val y: Float)

/**
 * Homography helper: maps four tapped board corners into a normalized
 * 400x400 top-down board with a8 at top-left and a1 at bottom-left.
 */
class BoardCalibrator {

    data class Calibration(
        val corners: List<CornerPoint>,
        /** Degrees clockwise: which screen corner corresponds to a1 before warp. */
        val rotation: Int = 0,
    ) {
        val isComplete: Boolean get() = corners.size == 4
    }

    var calibration: Calibration? = null
        private set

    fun setCorners(points: List<CornerPoint>, rotation: Int = calibration?.rotation ?: 0) {
        require(points.size == 4) { "Exactly 4 corners required" }
        calibration = Calibration(orderClockwise(points), normalizeRotation(rotation))
    }

    fun cycleRotation() {
        val current = calibration ?: return
        calibration = current.copy(rotation = normalizeRotation(current.rotation + 90))
    }

    fun clear() {
        calibration = null
    }

    fun warp(source: Mat): Mat? {
        val cal = calibration ?: return null
        if (!cal.isComplete) return null

        val ordered = orderClockwise(cal.corners)
        val srcPoints = MatOfPoint2f(
            *ordered.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray(),
        )
        val dstPoints = MatOfPoint2f(*destinationPoints(cal.rotation))
        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val output = Mat()
        Imgproc.warpPerspective(
            source,
            output,
            transform,
            Size(BOARD_SIZE.toDouble(), BOARD_SIZE.toDouble()),
        )
        srcPoints.release()
        dstPoints.release()
        transform.release()
        return output
    }

    /**
     * Returns the analysis ROI for a chess square in algebraic notation (e.g. "e4").
     * Uses the inner 60% of the cell to reduce edge spill from leaning pieces.
     */
    fun squareRect(square: String): Rect {
        val file = square[0].lowercaseChar() - 'a'
        val rank = square[1] - '1'
        require(file in 0..7 && rank in 0..7) { "Invalid square: $square" }

        // Warp space: file a is left (x=0), rank 8 is top (y=0).
        val x = file * CELL_SIZE
        val y = (7 - rank) * CELL_SIZE
        val size = (CELL_SIZE * 3) / 5 // inner 60%
        val inset = (CELL_SIZE - size) / 2
        return Rect(x + inset, y + inset, size, size)
    }

    fun squareCenter(square: String): CornerPoint {
        val rect = squareRect(square)
        return CornerPoint(
            rect.x + rect.width / 2f,
            rect.y + rect.height / 2f,
        )
    }

    /** Label for overlay: algebraic square at grid indices (col 0..7 left→right, row 0..7 top→bottom). */
    fun labelAt(col: Int, row: Int): String {
        val rotation = calibration?.rotation ?: 0
        // In warp space labels are fixed; overlay on camera uses corner mapping.
        // For warped preview overlays we always use a8-top-left convention.
        val file = 'a' + col
        val rank = 8 - row
        // rotation is applied at warp destination; labels on warped board stay fixed.
        return "$file$rank".also { _ ->
            // Keep rotation referenced so callers that draw pre-warp can use mapOverlaySquare.
            require(rotation % 90 == 0)
        }
    }

    fun save(context: Context) {
        val cal = calibration ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray()
        cal.corners.forEach { p ->
            arr.put(JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
        }
        prefs.edit()
            .putString(KEY_CORNERS, arr.toString())
            .putInt(KEY_ROTATION, cal.rotation)
            .apply()
    }

    fun load(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CORNERS, null) ?: return false
        return try {
            val arr = JSONArray(raw)
            if (arr.length() != 4) return false
            val points = (0 until 4).map { i ->
                val obj = arr.getJSONObject(i)
                CornerPoint(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
            }
            setCorners(points, prefs.getInt(KEY_ROTATION, 0))
            true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val BOARD_SIZE = 400
        const val CELL_SIZE = 50
        private const val PREFS = "board_calibration"
        private const val KEY_CORNERS = "corners"
        private const val KEY_ROTATION = "rotation"

        fun orderClockwise(points: List<CornerPoint>): List<CornerPoint> {
            require(points.size == 4)
            val tl = points.minWith(compareBy({ it.x + it.y }, { it.x }))
            val br = points.maxWith(compareBy({ it.x + it.y }, { it.x }))
            val remaining = points.filter { it !== tl && it !== br }
            val tr = remaining.minBy { it.y - it.x }
            val bl = remaining.maxBy { it.y - it.x }
            return listOf(tl, tr, br, bl)
        }

        fun normalizeRotation(degrees: Int): Int {
            var d = degrees % 360
            if (d < 0) d += 360
            // Snap to nearest 90.
            return ((d + 45) / 90) * 90 % 360
        }

        /**
         * Destination quad for getPerspectiveTransform.
         * Base (rotation 0): TL=a8, TR=h8, BR=h1, BL=a1.
         * Positive rotation cycles which physical corner maps to a1.
         */
        fun destinationPoints(rotation: Int): Array<Point> {
            val s = BOARD_SIZE.toDouble()
            val base = arrayOf(
                Point(0.0, 0.0),       // TL
                Point(s, 0.0),         // TR
                Point(s, s),           // BR
                Point(0.0, s),         // BL
            )
            val steps = (normalizeRotation(rotation) / 90) % 4
            // Rotate destination assignment so a1 ends at bottom-left of warp.
            // Rotating source→dest mapping by cycling destination corners.
            return Array(4) { i -> base[(i + steps) % 4] }
        }

        fun nearlyEqual(a: CornerPoint, b: CornerPoint, eps: Float = 0.5f): Boolean {
            return abs(a.x - b.x) < eps && abs(a.y - b.y) < eps
        }
    }
}
