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

/**
 * Converts CameraX YUV analysis frames into upright OpenCV RGB Mats / Bitmaps.
 */
object FrameConverter {

    fun imageProxyToRgbMat(image: ImageProxy): Mat {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        // NV21 expects V then U interleaved; CameraX often provides VU planes.
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

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
