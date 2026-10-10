package com.jotchess.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class FrameConverterTest {

    @Test
    fun copyPlaneTight_packedPlaneCopiesContiguously() {
        val width = 4
        val height = 2
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val out = ByteArray(width * height)
        FrameConverter.copyPlaneTight(
            buffer = ByteBuffer.wrap(data),
            rowStride = width,
            pixelStride = 1,
            width = width,
            height = height,
            out = out,
            outOffset = 0,
        )
        assertArrayEquals(data, out)
    }

    @Test
    fun copyPlaneTight_respectsRowStridePadding() {
        val width = 3
        val height = 2
        // Each row: 3 useful bytes + 1 pad
        val data = byteArrayOf(
            10, 11, 12, 0,
            20, 21, 22, 0,
        )
        val out = ByteArray(width * height)
        FrameConverter.copyPlaneTight(
            buffer = ByteBuffer.wrap(data),
            rowStride = 4,
            pixelStride = 1,
            width = width,
            height = height,
            out = out,
            outOffset = 0,
        )
        assertArrayEquals(byteArrayOf(10, 11, 12, 20, 21, 22), out)
    }

    @Test
    fun copyPlaneTight_respectsPixelStride() {
        val width = 2
        val height = 1
        // Interleaved: V0 U0 V1 U1 — copy V with pixelStride 2
        val data = byteArrayOf(70, 80.toByte(), 71, 81.toByte())
        val out = ByteArray(2)
        FrameConverter.copyPlaneTight(
            buffer = ByteBuffer.wrap(data),
            rowStride = 4,
            pixelStride = 2,
            width = width,
            height = height,
            out = out,
            outOffset = 0,
        )
        assertEquals(70.toByte(), out[0])
        assertEquals(71.toByte(), out[1])
    }
}
