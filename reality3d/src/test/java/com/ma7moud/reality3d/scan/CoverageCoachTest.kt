package com.ma7moud.reality3d.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageCoachTest {
    private val target = Vector3(0f, 0f, 0f)

    @Test
    fun resumeSeedIsPreserved() {
        val coach = CoverageCoach()
        val seed = BooleanArray(9)
        seed[0] = true
        seed[3] = true
        coach.seed(seed)

        val state = coach.update(
            camera = Vector3(0f, 0f, 1f),
            cameraForward = Vector3(0f, 0f, -1f),
            target = target,
            nowNanos = 1_000_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 80,
            trackingGood = true,
        )

        assertTrue(state.covered[0])
        assertTrue(state.covered[3])
        assertTrue(state.coveragePercent >= 22)
    }

    @Test
    fun topCaptureUsesTopBinWhenCameraIsAimedAtTarget() {
        val coach = CoverageCoach()
        val camera = Vector3(0f, 1.2f, 0.4f)
        val state = coach.update(
            camera = camera,
            cameraForward = (target - camera).normalized(),
            target = target,
            nowNanos = 1_000_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 80,
            trackingGood = true,
        )

        assertTrue(state.covered[8])
        assertTrue(state.frameUsable)
        assertEquals(11, state.coveragePercent)
    }

    @Test
    fun lookingAwayDoesNotAwardCoverage() {
        val coach = CoverageCoach()
        val state = coach.update(
            camera = Vector3(0f, 0f, 1f),
            cameraForward = Vector3(0f, 0f, 1f),
            target = target,
            nowNanos = 1_000_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 80,
            trackingGood = true,
        )

        assertEquals(0, state.coveragePercent)
        assertFalse(state.frameUsable)
        assertTrue(state.aimErrorDegrees > 150f)
        assertTrue(state.message().contains("Aim"))
    }

    @Test
    fun sparseDepthDoesNotAwardCoverage() {
        val coach = CoverageCoach()
        val state = coach.update(
            camera = Vector3(0f, 0f, 1f),
            cameraForward = Vector3(0f, 0f, -1f),
            target = target,
            nowNanos = 1_000_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 8,
            trackingGood = true,
        )

        assertEquals(0, state.coveragePercent)
        assertFalse(state.frameUsable)
        assertTrue(state.message().contains("enough object depth"))
    }

    @Test
    fun fastMovementDoesNotAwardAnotherSector() {
        val coach = CoverageCoach()
        val firstCamera = Vector3(0f, 0f, 1f)
        val first = coach.update(
            camera = firstCamera,
            cameraForward = (target - firstCamera).normalized(),
            target = target,
            nowNanos = 1_000_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 80,
            trackingGood = true,
        )
        assertEquals(11, first.coveragePercent)

        val secondCamera = Vector3(1f, 0f, 0f)
        val second = coach.update(
            camera = secondCamera,
            cameraForward = (target - secondCamera).normalized(),
            target = target,
            nowNanos = 1_100_000_000L,
            depthConfidence = 0.9f,
            depthPointCount = 80,
            trackingGood = true,
        )

        assertEquals(11, second.coveragePercent)
        assertFalse(second.frameUsable)
        assertTrue(second.speedMetersPerSecond > 0.65f)
        assertTrue(second.message().contains("Too fast"))
    }
}
