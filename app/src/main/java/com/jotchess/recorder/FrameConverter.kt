package com.jotchess.recorder

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import java.nio.ByteBuffer

/**
 * Converts CameraX YUV analysis frames into upright OpenCV RGB Mats / Bitmaps.
 *
 * Analysis frames are never horizontally mirrored: board files a–h must stay
 * sensor-true so e2e4 is not recorded as d2d4. Preview and tap overlays share
 * that same coordinate space.
 */
object FrameConverter {

    fun imageProxyToRgbMat(image: ImageProxy): Mat {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }

        val nv21 = yuv420888ToNv21(image)
        val yuv = Mat(image.height + image.height / 2, image.width, CvType.CV_8UC1)
        yuv.put(0, 0, nv21)
        val bgr = Mat()
        Imgproc.cvtColor(yuv, bgr, Imgproc.COLOR_YUV2BGR_NV21, 3)
        yuv.release()

        val upright = rotateToUpright(bgr, image.imageInfo.rotationDegrees)
        if (upright !== bgr) {
            bgr.release()
        }

        val rgb = Mat()
        Imgproc.cvtColor(upright, rgb, Imgproc.COLOR_BGR2RGB)
        upright.release()
        return rgb
    }

    /**
     * Pack YUV_420_888 into tightly packed NV21 (Y plane + interleaved VU),
     * respecting each plane's rowStride and pixelStride.
     */
    fun yuv420888ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val out = ByteArray(width * height * 3 / 2)
        copyPlaneTight(yPlane.buffer, yPlane.rowStride, yPlane.pixelStride, width, height, out, 0)

        // NV21: chroma is V then U interleaved, half resolution.
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val chromaOut = width * height
        val vBuf = vPlane.buffer.duplicate()
        val uBuf = uPlane.buffer.duplicate()
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        var dst = chromaOut
        for (row in 0 until chromaHeight) {
            val vRowStart = row * vRowStride
            val uRowStart = row * uRowStride
            for (col in 0 until chromaWidth) {
                out[dst++] = vBuf.get(vRowStart + col * vPixelStride)
                out[dst++] = uBuf.get(uRowStart + col * uPixelStride)
            }
        }
        return out
    }

    /**
     * Copy a single plane into [out] at [outOffset] with packed row width [width].
     * Exposed for unit tests with synthetic buffers.
     */
    fun copyPlaneTight(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int,
    ) {
        val src = buffer.duplicate()
        var dst = outOffset
        if (pixelStride == 1 && rowStride == width) {
            src.position(0)
            src.get(out, dst, width * height)
            return
        }
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (pixelStride == 1) {
                src.position(rowStart)
                src.get(out, dst, width)
                dst += width
            } else {
                for (col in 0 until width) {
                    out[dst++] = src.get(rowStart + col * pixelStride)
                }
            }
        }
    }

    fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        when (mat.channels()) {
            1 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_GRAY2RGBA)
            3 -> Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_RGB2RGBA)
            4 -> mat.copyTo(rgba)
            else -> error("Unsupported channel count: ${mat.channels()}")
        }
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun rotateToUpright(bgr: Mat, rotationDegrees: Int): Mat {
        if (rotationDegrees == 0) return bgr
        val rotated = Mat()
        when (rotationDegrees) {
            90 -> Core.rotate(bgr, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(bgr, rotated, Core.ROTATE_180)
            270 -> Core.rotate(bgr, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> {
                bgr.copyTo(rotated)
            }
        }
        return rotated
    }
}
