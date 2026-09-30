package com.ma7moud.reality3d.scan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanFramePolicyTest {

    private val boxSize = 0.30f

    @Test
    fun stableCentredFrameAtWorkingDistanceIsUsable() {
        assertTrue(ScanFramePolicy.captureUsable(boxSize, distance = 0.45f, boxInView = true, steady = true))
    }

    @Test
    fun motionRejectsFrameBeforeCapture() {
        assertFalse(ScanFramePolicy.captureUsable(boxSize, distance = 0.45f, boxInView = true, steady = false))
    }

    @Test
    fun lookingAwayRejectsFrameBeforeCapture() {
        assertFalse(ScanFramePolicy.captureUsable(boxSize, distance = 0.45f, boxInView = false, steady = true))
    }

    @Test
    fun distanceUsesSameLimitsAsCoach() {
        val near = ScanCoach.nearLimit(boxSize)
        val far = ScanCoach.farLimit(boxSize)
        assertFalse(ScanFramePolicy.distanceUsable(boxSize, near - 0.001f))
        assertTrue(ScanFramePolicy.distanceUsable(boxSize, near))
        assertTrue(ScanFramePolicy.distanceUsable(boxSize, far))
        assertFalse(ScanFramePolicy.distanceUsable(boxSize, far + 0.001f))
    }
}
