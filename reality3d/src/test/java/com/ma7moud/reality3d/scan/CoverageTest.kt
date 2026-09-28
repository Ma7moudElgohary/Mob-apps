package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CoverageTest {

    private fun direction(azimuthDeg: Double, elevationDeg: Double): FloatArray {
        val a = Math.toRadians(azimuthDeg)
        val e = Math.toRadians(elevationDeg)
        return floatArrayOf((cos(e) * sin(a)).toFloat(), sin(e).toFloat(), (cos(e) * cos(a)).toFloat())
    }

    private fun cell(azimuthDeg: Double, elevationDeg: Double): Int =
        direction(azimuthDeg, elevationDeg).let { CoverageTracker.cellOf(it[0], it[1], it[2]) }

    @Test
    fun directionsMapToRingsAndSegments() {
        assertEquals(0, cell(5.0, 10.0))
        assertEquals(1, cell(35.0, 10.0))
        assertEquals(11, cell(355.0, 10.0))
        assertEquals(CoverageTracker.SEGMENTS + 3, cell(100.0, 40.0))
        assertEquals(2 * CoverageTracker.SEGMENTS + 6, cell(190.0, 70.0))
        assertEquals(CoverageTracker.TOP_CELL, cell(0.0, 85.0))
        // Below the object's middle still counts as the low ring.
        assertEquals(0, cell(10.0, -20.0))
    }

    @Test
    fun guidanceGoesFromLowToHighToTop() {
        val tracker = CoverageTracker()
        assertEquals(CoverageTracker.Step.LOW_RING, tracker.nextStep())
        for (a in 0 until 360 step 30) tracker.mark(cell(a + 1.0, 10.0))
        assertEquals(1f, tracker.ringFraction(0))
        assertEquals(CoverageTracker.Step.MIDDLE_RING, tracker.nextStep())
        for (a in 0 until 360 step 30) tracker.mark(cell(a + 1.0, 40.0))
        assertEquals(CoverageTracker.Step.HIGH_RING, tracker.nextStep())
        for (a in 0 until 270 step 30) tracker.mark(cell(a + 1.0, 70.0))
        assertEquals(CoverageTracker.Step.TOP, tracker.nextStep())
        assertTrue(tracker.mark(CoverageTracker.TOP_CELL))
        assertFalse(tracker.mark(CoverageTracker.TOP_CELL))
        assertEquals(CoverageTracker.Step.DONE, tracker.nextStep())
        assertEquals(34f / CoverageTracker.CELLS, tracker.fraction, 1e-6f)
    }

    @Test
    fun keyframesStayApart() {
        val selector = KeyframeSelector(minAngleDegrees = 10f)
        val first = direction(0.0, 20.0)
        assertTrue(selector.isNew(first))
        selector.add(first)
        assertFalse(selector.isNew(direction(6.0, 20.0)))
        assertTrue(selector.isNew(direction(12.0, 20.0)))
        assertTrue(selector.isNew(direction(0.0, 33.0)))
        assertEquals(1, selector.count)
    }

    @Test
    fun boxIsPushedBehindTheTappedSurface() {
        // Tapped the front of an object 0.5 m in front of a camera looking along -Z; table at y = 0.72.
        val box = ScanBox.around(0f, 0.8f, -0.5f, 0f, 0f, size = 0.4f, floorY = 0.72f)
        assertEquals(0f, box.centerX, 1e-6f)
        assertEquals(-0.62f, box.centerZ, 1e-5f)
        assertEquals(0.72f + ScanBox.FLOOR_MARGIN, box.bottomY, 1e-6f)
        // Without a table the box is centred on the tapped point's height.
        assertEquals(0.6f, ScanBox.around(0f, 0.8f, -0.5f, 0f, 0f, 0.4f, null).bottomY, 1e-6f)
    }
}
