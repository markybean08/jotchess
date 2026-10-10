package com.jotchess.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareChangeLatchTest {

    @Test
    fun joinsAboveHighThreshold() {
        val latch = SquareChangeLatch(join = 12.0, leave = 8.0)
        val changed = latch.update(mapOf("e2" to 13.0, "e4" to 2.0))
        assertTrue(changed.contains("e2"))
        assertFalse(changed.contains("e4"))
    }

    @Test
    fun staysLatchedBetweenThresholds() {
        val latch = SquareChangeLatch(join = 12.0, leave = 8.0)
        latch.update(mapOf("e4" to 15.0))
        val mid = latch.update(mapOf("e4" to 10.0))
        assertTrue(mid.contains("e4"))
    }

    @Test
    fun leavesBelowLowThreshold() {
        val latch = SquareChangeLatch(join = 12.0, leave = 8.0)
        latch.update(mapOf("e4" to 15.0))
        val cleared = latch.update(mapOf("e4" to 5.0))
        assertEquals(emptySet<String>(), cleared)
    }

    @Test
    fun clearResetsLatch() {
        val latch = SquareChangeLatch()
        latch.update(mapOf("a1" to 20.0))
        latch.clear()
        assertEquals(emptySet<String>(), latch.snapshot())
    }
}
